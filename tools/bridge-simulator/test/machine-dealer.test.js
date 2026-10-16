// Roadmap vehicle dealer (RFH-Q5): the scenario fahrzeughaendler with store_catalog.json, the delivery points and the shop
// places, VEHICLE_DELIVERY executed like the planned mod action (place, fallback to the shop, NO_SPACE, trade-ins,
// bookings), the new money reasons and the control endpoints.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BridgeSimulator } from '../src/simulator.js';
import { validate } from '../src/validate.js';
import { SCENARIOS } from '../src/scenarios.js';

const read = (p) => JSON.parse(readFileSync(p, 'utf8'));
function setup(scenario = 'fahrzeughaendler') {
  const sim = new BridgeSimulator({ dir: mkdtempSync(join(tmpdir(), 'rpsim-sim-')), scenario });
  sim.start();
  const run = (instructions) => {
    const doc = { savegameId: sim.savegameId, instructions };
    assert.equal(validate('instructions', doc), null, 'backend-side document must be schema valid');
    writeFileSync(sim.paths.instructions, JSON.stringify(doc));
    sim.processInstructions();
    return Object.fromEntries(read(sim.paths.ack).acks.map((a) => [a.instructionId, a]));
  };
  return { sim, run };
}
const FENDT = 'data/vehicles/fendt/vario700/vario700.xml';
const CATROS = 'data/vehicles/amazone/catros/catros.xml';
const delivery = (id, extra = {}) => ({ instructionId: id, type: 'VEHICLE_DELIVERY', deliveryId: `dlv_${id}`,
  items: [{ storeXmlFilename: FENDT, configurations: { baseColor: { saveId: 'BLACK', index: 2 }, motor: { index: 2 } } },
    { storeXmlFilename: CATROS }],
  place: { x: -412.5, z: 233.75, ry: 1.571 }, fallbackToShop: true,
  bookings: [{ amount: 180000, moneyReason: 'VEHICLE_NEW_PURCHASE', note: 'Fendt 700 Vario, Amazone Catros' },
    { amount: 1100, moneyReason: 'VEHICLE_DELIVERY_FEE', note: 'Lieferung' }],
  tradeIns: ['veh_00001'], ...extra });

test('only fahrzeughaendler writes store_catalog.json and the delivery blocks (older mod without them)', () => {
  for (const scenario of Object.keys(SCENARIOS)) {
    const { sim } = setup(scenario);
    const facts = read(sim.paths.farmFacts);
    const ctx = read(sim.paths.marketContext);
    const dealer = scenario === 'fahrzeughaendler';
    assert.equal(existsSync(sim.paths.storeCatalog), dealer, scenario);
    assert.equal('deliveryPoints' in facts, dealer, scenario);
    assert.equal('storePlaces' in ctx, dealer, scenario);
  }
});

test('store_catalog.json carries every option, sorted by xmlFilename, and matches the schema', () => {
  const { sim } = setup();
  const cat = read(sim.paths.storeCatalog);
  assert.equal(validate('storeCatalog', cat), null);
  assert.equal(cat.savegameId, sim.savegameId);
  assert.deepEqual(cat.items.map((i) => i.xmlFilename), [...cat.items.map((i) => i.xmlFilename)].sort());
  const fendt = cat.items.find((i) => i.xmlFilename === FENDT);
  assert.deepEqual(fendt.defaultConfigurations, { baseColor: 1, motor: 1, wheel: 1 });
  assert.equal(fendt.configurations.find((c) => c.name === 'motor').options[2].price, 34000);
  assert.deepEqual(fendt.configurations.find((c) => c.name === 'wheel').options[1].dependent,
    [{ name: 'wheelWeight', index: 2 }]);
  // RFH-K1: display names of the game and power in hp (range of the item, value per motor option)
  assert.equal(fendt.brandTitle, 'Fendt');
  assert.equal(fendt.categoryTitle, 'Große Traktoren');
  assert.deepEqual([fendt.powerMin, fendt.powerMax], [200, 280]);
  assert.deepEqual(fendt.configurations.find((c) => c.name === 'motor').options.map((o) => o.power), [200, 240, 280]);
  const noPower = structuredClone(cat);
  noPower.items.find((i) => i.xmlFilename === FENDT).powerMin = 0;
  assert.notEqual(validate('storeCatalog', noPower), null);
  // the schema refuses an option without index
  const broken = structuredClone(cat);
  delete broken.items[0].configurations[0].options[0].index;
  assert.notEqual(validate('storeCatalog', broken), null);
});

test('delivery to the delivery point: vehicles loaded, trade-in removed, every booking single', () => {
  const { sim, run } = setup();
  const before = sim.balance;
  const acks = run([delivery('d1')]);
  assert.equal(acks.d1.status, 'APPLIED');
  assert.equal(acks.d1.result.placeUsed, 'DELIVERY_POINT');
  assert.equal(acks.d1.result.vehicleCount, 2);
  assert.equal(acks.d1.result.tradeInsRemoved, 1);
  assert.match(acks.d1.result.vehicleId1, /^veh_\d{5}$/);
  assert.notEqual(acks.d1.result.vehicleId1, acks.d1.result.vehicleId2);
  assert.equal(sim.balance, before - 181100);
  const facts = sim.buildFarmFacts();
  assert.ok(!facts.assets.vehicles.some((v) => v.uniqueId === 'veh_00001'));
  assert.ok(facts.assets.vehicles.some((v) => v.xmlFilename === FENDT && v.category === 'TRACTORSL'));
  const own = facts.bookings.entries.filter((e) => e.category.startsWith('RPSIM_VEHICLE'));
  assert.deepEqual(own.map((e) => [e.category, e.amount]),
    [['RPSIM_VEHICLE_NEW_PURCHASE', -180000], ['RPSIM_VEHICLE_DELIVERY_FEE', -1100]]);
});

test('occupied delivery point: to the shop places, or NO_SPACE without fallback or with full shop places', () => {
  const { sim, run } = setup();
  sim.setDeliveryPlaceBlocked(true);
  assert.equal(run([delivery('d1', { tradeIns: [] })]).d1.result.placeUsed, 'SHOP');
  const before = sim.balance;
  const acks = run([delivery('d2', { tradeIns: [], fallbackToShop: false })]);
  assert.equal(acks.d2.status, 'FAILED');
  assert.equal(acks.d2.message, 'NO_SPACE');
  sim.setShopFull(true);
  assert.equal(run([delivery('d3', { tradeIns: [] })]).d3.message, 'NO_SPACE');
  // without a place the shop places are used directly
  sim.setShopFull(false);
  const noPlace = delivery('d4', { tradeIns: [] });
  delete noPlace.place;
  assert.equal(run([noPlace]).d4.result.placeUsed, 'SHOP');
  assert.equal(sim.balance, before - 181100, 'failed deliveries book nothing');
});

test('failure codes of a delivery book and load nothing', () => {
  const { sim, run } = setup();
  sim.setVehiclePositions([{ uniqueId: 'veh_00001', x: 0, z: 0 }]);
  const vehicles = sim.vehicles.length;
  const before = sim.balance;
  const acks = run([delivery('busy'), delivery('att', { tradeIns: ['veh_00002'] }),
    delivery('gone', { tradeIns: ['veh_09999'] }),
    delivery('unk', { tradeIns: [], items: [{ storeXmlFilename: 'data/vehicles/unicorn.xml' }] }),
    delivery('poor', { tradeIns: [], bookings: [{ amount: 900000, moneyReason: 'VEHICLE_PURCHASE' }] })]);
  assert.equal(acks.busy.message, 'TRADE_IN_IN_USE: veh_00001');
  assert.equal(acks.att.message, 'TRADE_IN_ATTACHED: veh_00002');
  assert.equal(acks.gone.message, 'TRADE_IN_NOT_FOUND: veh_09999');
  assert.equal(acks.unk.message, 'UNKNOWN_STORE_ITEM');
  assert.equal(acks.poor.message, 'INSUFFICIENT_FUNDS');
  assert.equal(sim.vehicles.length, vehicles);
  assert.equal(sim.balance, before);
  // the overdraft floor allows it
  const ok = run([delivery('od', { tradeIns: [], bookings: [{ amount: 450000, moneyReason: 'VEHICLE_PURCHASE' }],
    minBalance: -100000 })]);
  assert.equal(ok.od.status, 'APPLIED');
  assert.equal(sim.balance, before - 450000);
});

test('VEHICLE_DELIVERY and the new money reasons are validated like the mod', () => {
  const { sim } = setup();
  const cases = {
    a: { deliveryId: '' }, b: { items: [] }, c: { items: [{ storeXmlFilename: FENDT, configurations: { motor: {} } }] },
    d: { items: [{ storeXmlFilename: FENDT, configurations: { motor: { index: 0 } } }] }, e: { place: { x: 1, z: 2 } },
    f: { bookings: [{ amount: -1, moneyReason: 'VEHICLE_PURCHASE' }] },
    g: { bookings: [{ amount: 1, moneyReason: 'NEW_TRACTOR' }] }, h: { tradeIns: [''] }, i: { minBalance: 5 } };
  writeFileSync(sim.paths.instructions, JSON.stringify({ savegameId: sim.savegameId,
    instructions: Object.entries(cases).map(([id, extra]) => delivery(id, extra)) }));
  sim.processInstructions();
  const acks = Object.fromEntries(read(sim.paths.ack).acks.map((a) => [a.instructionId, a]));
  for (const id of Object.keys(cases)) assert.equal(acks[id].status, 'REJECTED', id);
  assert.match(acks.a.message, /deliveryId is required/);
  assert.match(acks.c.message, /needs saveId or index/);
  assert.match(acks.g.message, /unknown moneyReason NEW_TRACTOR/);
  for (const reason of ['VEHICLE_DEPOSIT', 'VEHICLE_NEW_PURCHASE', 'VEHICLE_DELIVERY_FEE', 'VEHICLE_EXPRESS_FEE', 'VEHICLE_REFUND',
    'VEHICLE_CANCELLATION_FEE']) {
    const doc = { savegameId: sim.savegameId, instructions: [{ instructionId: reason, type: 'MONEY_TRANSACTION',
      amount: -1, reason }] };
    assert.equal(validate('instructions', doc), null, reason);
  }
});

test('the key "Lieferort hier festlegen" adds a point on own land only and the mod keeps it in its savegame', () => {
  const { sim } = setup();
  const p = sim.setDeliveryPoint({ x: -300, y: 99, z: 120, ry: 3.14, farmlandId: 3 });
  assert.equal(p.seq, 3);
  assert.equal(p.farmlandId, 3);
  assert.throws(() => sim.setDeliveryPoint({ x: 1, z: 1, farmlandId: 9 }), /eigenem oder gepachtetem Land/);
  sim.exportFarmFacts();
  const facts = read(sim.paths.farmFacts);
  assert.equal(validate('farmFacts', facts), null);
  assert.deepEqual(facts.deliveryPoints.map((x) => x.seq), [1, 2, 3]);
  // the points live in the mod savegame: a point set after the last save is gone after loading without saving
  sim.saveGame();
  sim.setDeliveryPoint({ x: -310, z: 125, farmlandId: 1 });
  assert.equal(sim.buildFarmFacts().deliveryPoints.length, 4);
  sim.reloadWithoutSaving();
  assert.deepEqual(sim.buildFarmFacts().deliveryPoints.map((x) => x.seq), [1, 2, 3]);
});
