// Roadmap V3 (R3-Q2): optional blocks npcFields / tradeStorage / storeVehicles, the four new instruction types and the
// optional result of an ack.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BridgeSimulator, MS_PER_GAME_DAY } from '../src/simulator.js';
import { validate } from '../src/validate.js';

const read = (p) => JSON.parse(readFileSync(p, 'utf8'));
function setup(scenario = 'nachbarhandel') {
  const sim = new BridgeSimulator({ dir: mkdtempSync(join(tmpdir(), 'rpsim-sim-')), scenario });
  sim.start();
  const write = (instructions) => {
    const doc = { savegameId: sim.savegameId, instructions };
    assert.equal(validate('instructions', doc), null, 'backend-side document must be schema valid');
    writeFileSync(sim.paths.instructions, JSON.stringify(doc));
  };
  const acks = () => Object.fromEntries(read(sim.paths.ack).acks.map((a) => [a.instructionId, a]));
  return { sim, write, acks };
}
const spawn = (extra = {}) => ({ instructionId: 'ins_vs', type: 'VEHICLE_SPAWN',
  storeXmlFilename: 'data/vehicles/deutzFahr/series5/series5.xml', ageMonths: 36, operatingHours: 2400, damage: 0.2,
  wear: 0.3, price: 52000, moneyReason: 'VEHICLE_PURCHASE', ...extra });

test('nachbarhandel exports npcFields, tradeStorage and storeVehicles; other scenarios leave them out', () => {
  const { sim } = setup();
  const facts = read(sim.paths.farmFacts);
  assert.equal(validate('farmFacts', facts), null);
  // only farmlands without an owner (1 and 2 belong to the player)
  assert.deepEqual(facts.npcFields.map((f) => f.farmlandId), [3, 5, 6, 8]);
  assert.equal(facts.npcFields[0].name, '3');
  assert.equal(facts.npcFields[0].cut, true);
  assert.deepEqual(facts.tradeStorage, [
    { fillType: 'BARLEY', amount: 12000, freeCapacity: 38000 },
    { fillType: 'STRAW', amount: 0, freeCapacity: 25000 },
    { fillType: 'WHEAT', amount: 40000, freeCapacity: 60000 }]);
  const ctx = read(sim.paths.marketContext);
  assert.equal(validate('marketContext', ctx), null);
  // R3-V3: own vehicles with name and shop XML
  assert.equal(facts.assets.vehicles[0].name, 'Fendt 700 Vario');
  assert.equal(facts.assets.vehicles[0].xmlFilename, 'data/vehicles/fendt/vario700/vario700.xml');
  assert.equal(ctx.storeVehicles.length, 5);
  assert.equal(ctx.storeVehicles.find((v) => v.categoryName === 'TRAILERS').motorized, undefined);

  for (const scenario of ['wohlhabender-hof', 'duerre-sommer']) {
    const other = setup(scenario).sim;
    const f = read(other.paths.farmFacts);
    assert.equal(f.npcFields, undefined, scenario);
    assert.equal(f.tradeStorage, undefined, scenario);
    assert.equal(read(other.paths.marketContext).storeVehicles, undefined, scenario);
  }
});

test('duerre-sommer starts in June with growing crops and stays dry for months', () => {
  const { sim } = setup('duerre-sommer');
  let facts = read(sim.paths.farmFacts);
  assert.equal(facts.calendar.period, 4);
  assert.ok(facts.fields.every((f) => f.growthState < f.minHarvestingGrowthState));
  sim.tick(3 * MS_PER_GAME_DAY); // one day per period: June -> September
  facts = read(sim.paths.farmFacts);
  assert.equal(facts.calendar.period, 7);
  assert.equal(facts.weather.raining, false);
  assert.equal(facts.weather.rainFallScale, 0);
});

test('STORAGE_TRANSFER IN + GOODS_PURCHASE fills the own silo and books the money', () => {
  const { sim, write, acks } = setup();
  const balance = sim.balance;
  write([{ instructionId: 'ins_in', batchId: 'b1', type: 'STORAGE_TRANSFER', direction: 'IN', fillType: 'STRAW',
    amount: 8000 },
  { instructionId: 'ins_m', batchId: 'b1', type: 'MONEY_TRANSACTION', amount: -1200, reason: 'GOODS_PURCHASE' }]);
  sim.processInstructions();
  assert.equal(acks().ins_in.status, 'APPLIED');
  assert.equal(sim.balance, balance - 1200);
  sim.exportFarmFacts();
  const facts = read(sim.paths.farmFacts);
  assert.deepEqual(facts.tradeStorage.find((e) => e.fillType === 'STRAW'), { fillType: 'STRAW', amount: 8000,
    freeCapacity: 17000 });
  assert.equal(facts.assets.storage.find((e) => e.fillType === 'STRAW').amount, 8000);
  assert.equal(facts.finances, undefined);
});

test('STORAGE_TRANSFER fails without capacity or stock and the batch books nothing', () => {
  const { sim, write, acks } = setup();
  const balance = sim.balance;
  write([{ instructionId: 'ins_in', batchId: 'b1', type: 'STORAGE_TRANSFER', direction: 'IN', fillType: 'WHEAT',
    amount: 70000 },
  { instructionId: 'ins_m1', batchId: 'b1', type: 'MONEY_TRANSACTION', amount: -9000, reason: 'GOODS_PURCHASE' },
  { instructionId: 'ins_out', batchId: 'b2', type: 'STORAGE_TRANSFER', direction: 'OUT', fillType: 'BARLEY',
    amount: 12001 },
  { instructionId: 'ins_m2', batchId: 'b2', type: 'MONEY_TRANSACTION', amount: 2000, reason: 'GOODS_SALE' },
  // a fill type no own silo accepts
  { instructionId: 'ins_oat', type: 'STORAGE_TRANSFER', direction: 'IN', fillType: 'OAT', amount: 1 }]);
  sim.processInstructions();
  const a = acks();
  assert.equal(a.ins_in.message, 'NO_CAPACITY');
  assert.equal(a.ins_out.message, 'INSUFFICIENT_STOCK');
  assert.equal(a.ins_oat.message, 'NO_CAPACITY');
  assert.match(a.ins_m1.message, /^BATCH_ABORTED/);
  assert.match(a.ins_m2.message, /^BATCH_ABORTED/);
  assert.equal(sim.balance, balance);
});

test('STORAGE_TRANSFER OUT + GOODS_SALE takes the goods out of the silo', () => {
  const { sim, write } = setup();
  write([{ instructionId: 'ins_out', batchId: 'b', type: 'STORAGE_TRANSFER', direction: 'OUT', fillType: 'WHEAT',
    amount: 10000 },
  { instructionId: 'ins_m', batchId: 'b', type: 'MONEY_TRANSACTION', amount: 2100, reason: 'GOODS_SALE' }]);
  sim.processInstructions();
  sim.exportFarmFacts();
  const facts = read(sim.paths.farmFacts);
  assert.equal(facts.tradeStorage.find((e) => e.fillType === 'WHEAT').amount, 30000);
  assert.equal(facts.assets.storage.find((e) => e.fillType === 'WHEAT').amount, 30000);
});

test('MISSION_CREATE creates a contract of the neighbour with the missionId in the ack', () => {
  const { sim, write, acks } = setup();
  write([{ instructionId: 'ins_mc', type: 'MISSION_CREATE', missionType: 'plow', farmlandId: 3 },
    { instructionId: 'ins_own', type: 'MISSION_CREATE', missionType: 'plow', farmlandId: 1 },
    { instructionId: 'ins_nofield', type: 'MISSION_CREATE', missionType: 'plow', farmlandId: 9 }]);
  sim.processInstructions();
  const a = acks();
  assert.equal(a.ins_mc.status, 'APPLIED');
  assert.deepEqual(a.ins_mc.result, { missionId: 'mission_sim_001' });
  assert.equal(a.ins_own.message, 'NOT_AVAILABLE');
  assert.equal(a.ins_nofield.message, 'NOT_AVAILABLE');
  assert.equal(validate('instructionsAck', read(sim.paths.ack)), null);
  sim.exportFarmFacts();
  const m = read(sim.paths.farmFacts).missions.find((x) => x.uniqueId === 'mission_sim_001');
  assert.equal(m.status, 'AVAILABLE');
  assert.equal(m.field, '3');
  assert.equal(m.npcTitle, sim.farmlands.find((f) => f.farmlandId === 3).npc.title);
  // a second contract on the same field is not possible while the first one is open
  write([{ instructionId: 'ins_mc2', type: 'MISSION_CREATE', missionType: 'stonePick', farmlandId: 3 }]);
  sim.processInstructions();
  assert.equal(acks().ins_mc2.message, 'NOT_AVAILABLE');
  // the player finishes it like a vanilla contract
  sim.setMission('mission_sim_001', 'FINISHED', true);
  sim.exportFarmFacts();
  assert.equal(read(sim.paths.farmFacts).missions.find((x) => x.uniqueId === 'mission_sim_001').success, true);
});

test('VEHICLE_SPAWN delivers a used vehicle, books the price and returns the vehicleId', () => {
  const { sim, write, acks } = setup();
  const balance = sim.balance;
  write([spawn()]);
  sim.processInstructions();
  const a = acks().ins_vs;
  assert.equal(a.status, 'APPLIED');
  assert.deepEqual(a.result, { vehicleId: 'veh_00003' });
  assert.equal(sim.balance, balance - 52000);
  assert.deepEqual(sim.moneyLog.at(-1), { id: 'ins_vs', amount: -52000, reason: 'VEHICLE_PURCHASE',
    note: 'Deutz-Fahr Serie 5' });
  sim.exportFarmFacts();
  assert.deepEqual(read(sim.paths.farmFacts).assets.vehicles.at(-1), { uniqueId: 'veh_00003', value: 52000,
    condition: 80, name: 'Deutz-Fahr Serie 5', xmlFilename: 'data/vehicles/deutzFahr/series5/series5.xml' });
});

test('VEHICLE_SPAWN fails for an unknown shop item or without money and books nothing', () => {
  const { sim, write, acks } = setup();
  const balance = sim.balance;
  write([spawn({ instructionId: 'ins_unknown', storeXmlFilename: 'data/vehicles/none.xml' }),
    spawn({ instructionId: 'ins_rich', storeXmlFilename: 'data/vehicles/claas/lexion8000/lexion8000.xml',
      price: 600000 })]);
  sim.processInstructions();
  assert.equal(acks().ins_unknown.message, 'UNKNOWN_STORE_ITEM');
  assert.equal(acks().ins_rich.message, 'INSUFFICIENT_FUNDS');
  assert.equal(sim.balance, balance);
  assert.equal(sim.vehicles.length, 2);
});

test('VEHICLE_REMOVE + VEHICLE_SALE removes an own vehicle; unknown or leased ones fail', () => {
  const { sim, write, acks } = setup();
  write([{ instructionId: 'ins_vr', batchId: 'b', type: 'VEHICLE_REMOVE', vehicleId: 'veh_00002' },
    { instructionId: 'ins_m', batchId: 'b', type: 'MONEY_TRANSACTION', amount: 45000, reason: 'VEHICLE_SALE' },
    { instructionId: 'ins_none', type: 'VEHICLE_REMOVE', vehicleId: 'veh_09999' }]);
  sim.processInstructions();
  assert.equal(acks().ins_vr.status, 'APPLIED');
  assert.equal(acks().ins_vr.result, undefined);
  assert.equal(acks().ins_none.message, 'VEHICLE_NOT_FOUND');
  assert.deepEqual(sim.vehicles.map((v) => v.uniqueId), ['veh_00001']);

  // R3-V3 fallback: a vehicle with something attached stays, nothing is booked
  const coupled = setup();
  coupled.sim.vehicles[0].attached = true;
  const before = coupled.sim.balance;
  coupled.write([{ instructionId: 'ins_c', batchId: 'c', type: 'VEHICLE_REMOVE', vehicleId: 'veh_00001' },
    { instructionId: 'ins_cm', batchId: 'c', type: 'MONEY_TRANSACTION', amount: 1000, reason: 'VEHICLE_SALE' }]);
  coupled.sim.processInstructions();
  assert.equal(coupled.acks().ins_c.message, 'VEHICLE_ATTACHED');
  assert.equal(coupled.sim.balance, before);
  assert.equal(coupled.sim.vehicles.length, 2);

  const leased = setup('leasing-hof');
  leased.write([{ instructionId: 'ins_l', type: 'VEHICLE_REMOVE', vehicleId: 'veh_00101' }]);
  leased.sim.processInstructions();
  assert.equal(leased.acks().ins_l.message, 'NOT_OWN_VEHICLE');
});

test('new instruction types and money reasons are validated like in the mod', () => {
  const { sim, write, acks } = setup();
  const docs = [
    [{ instructionId: 'a', type: 'STORAGE_TRANSFER', direction: 'UP', fillType: 'WHEAT', amount: 1 }, /unknown direction/],
    [{ instructionId: 'b', type: 'STORAGE_TRANSFER', direction: 'IN', fillType: 'WHEAT', amount: 0 }, /amount must be > 0/],
    [{ instructionId: 'c', type: 'MISSION_CREATE', farmlandId: 3 }, /missionType is required/],
    [spawn({ instructionId: 'd', price: -1 }), /price must be >= 0/], // R31-A2: 0 = borrowed machine
    [spawn({ instructionId: 'e', damage: 2 }), /damage must be between 0 and 1/],
    [spawn({ instructionId: 'f', moneyReason: 'FREE' }), /unknown moneyReason/],
    [{ instructionId: 'g', type: 'VEHICLE_REMOVE' }, /vehicleId is required/],
  ];
  // the schema describes the same rules (the backend side must write valid documents)
  for (const [ins] of docs) {
    assert.notEqual(validate('instructions', { savegameId: sim.savegameId, instructions: [ins] }), null, ins.instructionId);
  }
  writeFileSync(sim.paths.instructions, JSON.stringify({ savegameId: sim.savegameId, instructions: docs.map(([i]) => i) }));
  sim.processInstructions();
  const a = acks();
  for (const [ins, why] of docs) {
    assert.equal(a[ins.instructionId].status, 'REJECTED', ins.instructionId);
    assert.match(a[ins.instructionId].message, why, ins.instructionId);
  }
  write([{ instructionId: 'ok', type: 'MONEY_TRANSACTION', amount: 1, reason: 'LEASE_INCOME' },
    { instructionId: 'ok2', type: 'MONEY_TRANSACTION', amount: -1, reason: 'CONTRACT_PENALTY' }]);
  sim.processInstructions();
  assert.equal(acks().ok.status, 'APPLIED');
  assert.equal(acks().ok2.status, 'APPLIED');
});

test('a reload without saving forgets silo changes, contracts and the result of later instructions', () => {
  const { sim, write } = setup();
  sim.saveGame();
  write([{ instructionId: 'ins_in', type: 'STORAGE_TRANSFER', direction: 'IN', fillType: 'STRAW', amount: 5000 },
    { instructionId: 'ins_mc', type: 'MISSION_CREATE', missionType: 'plow', farmlandId: 3 }]);
  sim.processInstructions();
  assert.equal(sim.tradeStorage.STRAW.amount, 5000);
  sim.reloadWithoutSaving();
  assert.equal(sim.tradeStorage.STRAW.amount, 0);
  assert.equal(sim.toolMissions.length, 0);
  assert.equal(read(sim.paths.ack).acks.length, 0);
});

test('neighbour fields change through the control API and the mission limit is exported (R3-H1 / R3-H5)', () => {
  const { sim } = setup();
  assert.equal(read(sim.paths.farmFacts).missionLimitReached, false);
  sim.setNpcField({ farmlandId: 8, cut: true, growthState: 9 });
  sim.setMissionLimit(true);
  sim.exportFarmFacts();
  const facts = read(sim.paths.farmFacts);
  assert.equal(facts.npcFields.find((f) => f.farmlandId === 8).cut, true);
  assert.equal(facts.missionLimitReached, true);
  assert.throws(() => sim.setNpcField({ farmlandId: 1 }), /unknown neighbour field/);
  assert.equal(read(setup('wohlhabender-hof').sim.paths.farmFacts).missionLimitReached, undefined);
});
