// Roadmap V3.1 (R31-Q2): optional fields snowHeight / sprayType / category / fuel / dayTimeMs, the blocks
// vehiclePositions and fieldShapes, the three new instruction types and the control endpoints.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BridgeSimulator, MS_PER_GAME_DAY, MS_PER_GAME_HOUR } from '../src/simulator.js';
import { validate } from '../src/validate.js';
import { SCENARIOS } from '../src/scenarios.js';

const read = (p) => JSON.parse(readFileSync(p, 'utf8'));
function setup(scenario) {
  const sim = new BridgeSimulator({ dir: mkdtempSync(join(tmpdir(), 'rpsim-sim-')), scenario });
  sim.start();
  const write = (instructions) => {
    const doc = { savegameId: sim.savegameId, instructions };
    assert.equal(validate('instructions', doc), null, 'backend-side document must be schema valid');
    writeFileSync(sim.paths.instructions, JSON.stringify(doc));
  };
  const run = (instructions) => {
    write(instructions);
    sim.processInstructions();
    return Object.fromEntries(read(sim.paths.ack).acks.map((a) => [a.instructionId, a]));
  };
  return { sim, write, run };
}
const V31 = ['winter-schnee', 'lohnunternehmer', 'viehhandel'];

test('the Roadmap V3.1 scenarios export the new fields, all others leave them out (older mod)', () => {
  for (const scenario of Object.keys(SCENARIOS)) {
    const sim = setup(scenario).sim;
    const facts = read(sim.paths.farmFacts);
    const ctx = read(sim.paths.marketContext);
    assert.equal(validate('farmFacts', facts), null, scenario);
    assert.equal(validate('marketContext', ctx), null, scenario);
    const isV31 = V31.includes(scenario);
    assert.equal('vehiclePositions' in facts, isV31, scenario);
    assert.equal('dayTimeMs' in facts.calendar, isV31, scenario);
    assert.equal(facts.assets.vehicles.some((v) => 'category' in v || 'fuel' in v), isV31, scenario);
    assert.equal(facts.weather?.snowHeight !== undefined, isV31, scenario);
    assert.equal((facts.fields ?? []).some((f) => 'sprayType' in f), scenario === 'lohnunternehmer', scenario);
    assert.equal('fieldShapes' in ctx, scenario === 'lohnunternehmer', scenario);
  }
});

test('winter-schnee: December with snow, categories, diesel and the time of day', () => {
  const { sim } = setup('winter-schnee');
  const facts = read(sim.paths.farmFacts);
  assert.equal(facts.calendar.period, 10);
  assert.equal(facts.calendar.season, 'WINTER');
  assert.equal(facts.weather.snowHeight, 0.15);
  assert.deepEqual(facts.vehiclePositions, []);
  assert.deepEqual(facts.assets.vehicles.map((v) => v.category), ['TRACTORSL', 'TRACTORSM', 'TRAILERS']);
  assert.deepEqual(facts.assets.vehicles[0].fuel, { liters: 260, capacity: 400 });
  assert.equal(facts.assets.vehicles[2].fuel, undefined); // a trailer has no diesel tank
  sim.advance(6 * MS_PER_GAME_HOUR);
  assert.equal(sim.buildFarmFacts().calendar.dayTimeMs, sim.gameTime % MS_PER_GAME_DAY);
});

test('control: snow, vehicle positions and diesel', () => {
  const { sim } = setup('winter-schnee');
  assert.equal(sim.setSnow(0.4).snowHeight, 0.4);
  assert.throws(() => sim.setSnow(-1), /height/);
  sim.setVehiclePositions([{ uniqueId: 'veh_00002', x: 10.5, z: -3, farmlandId: 7, onCrop: true },
    { uniqueId: 'veh_00001', x: 1, z: 2 }]);
  const facts = sim.buildFarmFacts();
  assert.equal(validate('farmFacts', facts), null);
  assert.deepEqual(facts.vehiclePositions.map((p) => p.uniqueId), ['veh_00001', 'veh_00002']);
  assert.deepEqual(sim.setFuel('veh_00002', 999).fuel, { liters: 150, capacity: 150 });
  assert.throws(() => sim.setFuel('veh_00003', 10), /diesel tank/);
  assert.throws(() => setup('wohlhabender-hof').sim.setVehiclePositions([]), /vehiclePositions/);
});

test('VEHICLE_FUEL takes at most the diesel in the tank and only from a parked own vehicle', () => {
  const { sim, run } = setup('winter-schnee');
  sim.setVehiclePositions([{ uniqueId: 'veh_00001', x: 0, z: 0 }]);
  const acks = run([
    { instructionId: 'f1', type: 'VEHICLE_FUEL', vehicleId: 'veh_00002', delta: -500 },
    { instructionId: 'f2', type: 'VEHICLE_FUEL', vehicleId: 'veh_00001', delta: -50 },
    { instructionId: 'f3', type: 'VEHICLE_FUEL', vehicleId: 'veh_00003', delta: -50 },
    { instructionId: 'f4', type: 'VEHICLE_FUEL', vehicleId: 'veh_09999', delta: -50 }]);
  assert.equal(acks.f1.status, 'APPLIED');
  assert.deepEqual(acks.f1.result, { liters: 90 });
  assert.equal(sim.vehicles[1].fuel.liters, 0);
  assert.equal(acks.f2.message, 'VEHICLE_IN_USE');
  assert.equal(acks.f3.message, 'NO_DIESEL_TANK');
  assert.equal(acks.f4.message, 'VEHICLE_NOT_FOUND');
  assert.equal(validate('instructionsAck', read(sim.paths.ack)), null);
});

test('lohnunternehmer: spray types, field outlines and the contractor works', () => {
  const { sim, run } = setup('lohnunternehmer');
  const facts = read(sim.paths.farmFacts);
  assert.deepEqual(facts.fields.map((f) => f.sprayType), ['NONE', 'MANURE', 'LIQUID_MANURE', 'NONE']);
  const ctx = read(sim.paths.marketContext);
  assert.equal(ctx.fieldShapes.mapSize, 2048);
  assert.equal(ctx.fieldShapes.fields.length, 15);

  const acks = run([
    // harvest + storage + fee as one batch, like the backend will send it (R31-A1)
    { instructionId: 'h1', batchId: 'b_harvest', type: 'FIELD_WORK', farmlandId: 2, work: 'HARVEST' },
    { instructionId: 'h2', batchId: 'b_harvest', type: 'STORAGE_TRANSFER', direction: 'IN', fillType: 'WHEAT',
      amount: 9000 },
    { instructionId: 'h3', batchId: 'b_harvest', type: 'MONEY_TRANSACTION', amount: -1200, reason: 'CONTRACTOR_FEE' },
    { instructionId: 'p1', type: 'FIELD_WORK', farmlandId: 4, work: 'PLOW' },
    { instructionId: 's1', type: 'FIELD_WORK', farmlandId: 5, work: 'SOW', fruitType: 'BARLEY' },
    { instructionId: 'l1', type: 'FIELD_WORK', farmlandId: 7, work: 'LIME' },
    { instructionId: 'x1', type: 'FIELD_WORK', farmlandId: 3, work: 'CULTIVATE' },
    { instructionId: 'x2', type: 'FIELD_WORK', farmlandId: 5, work: 'SOW', fruitType: 'DRAGONFRUIT' }]);
  for (const id of ['h1', 'h2', 'h3', 'p1', 's1', 'l1']) assert.equal(acks[id].status, 'APPLIED', id);
  assert.equal(acks.x1.message, 'FIELD_NOT_FOUND');
  assert.equal(acks.x2.message, 'UNKNOWN_FRUIT_TYPE');
  const after = Object.fromEntries(sim.buildFarmFacts().fields.map((f) => [f.farmlandId, f]));
  assert.equal(after[2].cut, true);
  assert.equal(after[4].fruitType, undefined);
  assert.equal(after[4].groundType, 'PLOWED');
  assert.equal(after[5].fruitType, 'BARLEY');
  assert.equal(after[5].groundType, 'SOWN');
  assert.equal(after[7].sprayType, 'LIME');
  assert.equal(sim.tradeStorage.WHEAT.amount, 29000);
  assert.equal(sim.moneyLog.at(-1).reason, 'CONTRACTOR_FEE');
});

test('FIELD_WORK refuses a field that is not the player\'s or has a running contract', () => {
  const { sim, run } = setup('lohnunternehmer');
  sim.farmlands.find((f) => f.farmlandId === 7).ownerFarmId = 0;
  sim.toolMissions.push({ uniqueId: 'm1', status: 'RUNNING', field: '2' });
  const acks = run([{ instructionId: 'a', type: 'FIELD_WORK', farmlandId: 7, work: 'PLOW' },
    { instructionId: 'b', batchId: 'b1', type: 'FIELD_WORK', farmlandId: 2, work: 'CULTIVATE' },
    { instructionId: 'c', batchId: 'b1', type: 'MONEY_TRANSACTION', amount: -500, reason: 'CONTRACTOR_FEE' }]);
  assert.equal(acks.a.message, 'NOT_OWN_FIELD');
  assert.equal(acks.b.message, 'MISSION_RUNNING');
  assert.match(acks.c.message, /BATCH_ABORTED/); // the fee is not booked
});

test('viehhandel: ANIMAL_TRANSFER checks places, type and stock and updates assets.animals', () => {
  const { sim, run } = setup('viehhandel');
  const acks = run([
    { instructionId: 'in', type: 'ANIMAL_TRANSFER', husbandryUniqueId: 'hus_00001', subType: 'COW_ANGUS', count: 5,
      age: 6, direction: 'IN' },
    { instructionId: 'out', type: 'ANIMAL_TRANSFER', husbandryUniqueId: 'hus_00001', subType: 'COW_HOLSTEIN', count: 10,
      direction: 'OUT' },
    { instructionId: 'full', type: 'ANIMAL_TRANSFER', husbandryUniqueId: 'hus_00002', subType: 'SHEEP_LANDRACE',
      count: 1, direction: 'IN' },
    { instructionId: 'few', type: 'ANIMAL_TRANSFER', husbandryUniqueId: 'hus_00001', subType: 'COW_ANGUS', count: 50,
      direction: 'OUT' },
    { instructionId: 'type', type: 'ANIMAL_TRANSFER', husbandryUniqueId: 'hus_00002', subType: 'COW_ANGUS', count: 1,
      direction: 'IN' },
    { instructionId: 'sub', type: 'ANIMAL_TRANSFER', husbandryUniqueId: 'hus_00001', subType: 'UNICORN', count: 1,
      direction: 'IN' },
    { instructionId: 'hus', type: 'ANIMAL_TRANSFER', husbandryUniqueId: 'hus_09999', subType: 'COW_ANGUS', count: 1,
      direction: 'IN' }]);
  assert.equal(acks.in.status, 'APPLIED');
  assert.equal(acks.out.status, 'APPLIED');
  assert.equal(acks.full.message, 'NO_ANIMAL_SPACE');
  assert.equal(acks.few.message, 'NOT_ENOUGH_ANIMALS');
  assert.equal(acks.type.message, 'WRONG_ANIMAL_TYPE');
  assert.equal(acks.sub.message, 'UNKNOWN_SUB_TYPE');
  assert.equal(acks.hus.message, 'HUSBANDRY_NOT_FOUND');
  const cows = sim.buildFarmFacts().assets.animals.find((a) => a.husbandryUniqueId === 'hus_00001');
  assert.deepEqual(cows, { husbandryUniqueId: 'hus_00001', type: 'COW', count: 35, estimatedValue: 140000 });
});

test('the three new types and money reasons are validated like the mod', () => {
  const { sim, write } = setup('viehhandel');
  writeFileSync(sim.paths.instructions, JSON.stringify({ savegameId: sim.savegameId, instructions: [
    { instructionId: 'w1', type: 'FIELD_WORK', farmlandId: 3, work: 'PLOW', fruitType: 'WHEAT' },
    { instructionId: 'w2', type: 'FIELD_WORK', farmlandId: 3, work: 'SOW' },
    { instructionId: 'a1', type: 'ANIMAL_TRANSFER', husbandryUniqueId: 'hus_00001', subType: 'COW_ANGUS', count: 1.5,
      direction: 'IN' },
    { instructionId: 'v1', type: 'VEHICLE_FUEL', vehicleId: 'veh_00001', delta: 10 }] }));
  sim.processInstructions();
  const acks = Object.fromEntries(read(sim.paths.ack).acks.map((a) => [a.instructionId, a]));
  assert.match(acks.w1.message, /fruitType is only allowed for SOW/);
  assert.match(acks.w2.message, /fruitType is required for SOW/);
  assert.match(acks.a1.message, /count must be a whole number/);
  assert.match(acks.v1.message, /delta must be < 0/);
  for (const a of Object.values(acks)) assert.equal(a.status, 'REJECTED');
  // all new money reasons pass the schema and the validation
  write(['CONTRACTOR_FEE', 'MACHINE_RENT', 'LIVESTOCK_PURCHASE', 'LIVESTOCK_SALE', 'WINTER_SERVICE', 'DIRECT_PAYMENT',
    'INVESTMENT_GRANT', 'SOCIAL_INSURANCE', 'GUEST_INCOME', 'COOP_SHARES', 'COOP_DIVIDEND']
    .map((reason, i) => ({ instructionId: `m${i}`, type: 'MONEY_TRANSACTION', amount: 1, reason })));
  assert.equal(sim.processInstructions().applied, 11);
});

test('vehicle positions and stables survive saving and reloading without saving', () => {
  const { sim, run } = setup('viehhandel');
  sim.saveGame();
  run([{ instructionId: 'in', type: 'ANIMAL_TRANSFER', husbandryUniqueId: 'hus_00001', subType: 'COW_ANGUS',
    count: 5, direction: 'IN' }]);
  sim.setVehiclePositions([{ uniqueId: 'veh_00001', x: 1, z: 2 }]);
  assert.equal(sim.stables.hus_00001.subTypes.COW_ANGUS, 15);
  sim.reloadWithoutSaving();
  assert.equal(sim.stables.hus_00001.subTypes.COW_ANGUS, 10);
  assert.deepEqual(sim.vehiclePositions, []);
  const again = new BridgeSimulator({ dir: sim.dir, scenario: 'viehhandel' });
  assert.equal(again.stables.hus_00001.subTypes.COW_ANGUS, 10);
});

test('viehhandel: the husbandries export subtypes, supported subtypes and free places (R31-A3)', () => {
  const { sim, run } = setup('viehhandel');
  const facts = () => sim.buildFarmFacts();
  let cows = facts().husbandries.find((h) => h.husbandryUniqueId === 'hus_00001');
  assert.deepEqual(cows.subTypes, [{ name: 'COW_ANGUS', count: 10 }, { name: 'COW_HOLSTEIN', count: 30 }]);
  assert.deepEqual(cows.supportedSubTypes, ['COW_ANGUS', 'COW_HOLSTEIN', 'COW_SWISS_BROWN']);
  assert.equal(cows.freeSlots, 20);
  assert.equal(facts().husbandries.find((h) => h.husbandryUniqueId === 'hus_00002').freeSlots, 0);
  run([{ instructionId: 'out', type: 'ANIMAL_TRANSFER', husbandryUniqueId: 'hus_00001', subType: 'COW_ANGUS', count: 10,
    direction: 'OUT' }]);
  cows = facts().husbandries.find((h) => h.husbandryUniqueId === 'hus_00001');
  assert.deepEqual(cows.subTypes, [{ name: 'COW_HOLSTEIN', count: 30 }]); // a subtype without animals is left out
  assert.equal(cows.freeSlots, 30);
  assert.equal(validate('farmFacts', facts()), null);
  // a mod without the R31-Q1 contract exports the husbandries without them
  const old = setup('tierhof-krank').sim.buildFarmFacts();
  for (const h of old.husbandries) for (const k of ['subTypes', 'supportedSubTypes', 'freeSlots']) assert.equal(k in h, false);
});

test('lohnunternehmer: VEHICLE_SPAWN with price 0 brings a borrowed machine without a booking (R31-A2)', () => {
  const { sim, run } = setup('lohnunternehmer');
  const balance = sim.balance;
  const acks = run([{ instructionId: 'loan', type: 'VEHICLE_SPAWN', storeXmlFilename: 'data/vehicles/claas/lexion8000/lexion8000.xml',
    ageMonths: 36, operatingHours: 900, damage: 0.1, wear: 0.2, price: 0, moneyReason: 'MACHINE_RENT' }]);
  assert.equal(acks.loan.status, 'APPLIED');
  const id = acks.loan.result.vehicleId;
  assert.equal(sim.balance, balance);
  const v = sim.buildFarmFacts().assets.vehicles.find((x) => x.uniqueId === id);
  assert.equal(v.category, 'HARVESTERS');
  assert.equal(v.value, 780000);
  assert.equal(sim.buildMarketContext().storeVehicles.length > 0, true);
  const back = run([{ instructionId: 'back', type: 'VEHICLE_REMOVE', vehicleId: id }]);
  assert.equal(back.back.status, 'APPLIED');
});
