import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BridgeSimulator, MS_PER_GAME_HOUR, MS_PER_GAME_DAY, multiplierAt } from '../src/simulator.js';
import { validate } from '../src/validate.js';

const read = (p) => JSON.parse(readFileSync(p, 'utf8'));
function setup(scenario = 'wohlhabender-hof') {
  const sim = new BridgeSimulator({ dir: mkdtempSync(join(tmpdir(), 'rpsim-sim-')), scenario });
  sim.start();
  const write = (instructions, savegameId = sim.savegameId) => {
    const doc = { savegameId, instructions };
    assert.equal(validate('instructions', doc), null, 'backend-side document must be schema valid');
    writeFileSync(sim.paths.instructions, JSON.stringify(doc));
  };
  return { sim, write };
}

test('backend writes instruction -> simulator applies -> ack -> next facts show new state', () => {
  const { sim, write } = setup();
  const before = read(sim.paths.farmFacts).liquidity.balance;
  write([{ instructionId: 'ins_1', type: 'MONEY_TRANSACTION', amount: -1800, reason: 'SALARY_PAYMENT', note: 'Gehalt' }]);
  sim.processInstructions();
  const ack = read(sim.paths.ack);
  assert.deepEqual(ack.acks.map((a) => [a.instructionId, a.status]), [['ins_1', 'APPLIED']]);
  sim.exportFarmFacts();
  assert.equal(read(sim.paths.farmFacts).liquidity.balance, before - 1800);
});

test('same instruction twice is applied once (idempotency)', () => {
  const { sim, write } = setup();
  const ins = [{ instructionId: 'dup', type: 'MONEY_TRANSACTION', amount: 1000, reason: 'SUBSIDY' }];
  write(ins);
  sim.processInstructions();
  write(ins);
  const res = sim.processInstructions();
  assert.equal(res.duplicates, 1);
  assert.equal(sim.moneyLog.length, 1);
});

test('idempotency survives a simulator restart (persisted like the savegame XML)', () => {
  const { sim, write } = setup();
  write([{ instructionId: 'p1', type: 'MONEY_TRANSACTION', amount: 5, reason: 'OTHER' }]);
  sim.processInstructions();
  const again = new BridgeSimulator({ dir: sim.dir, scenario: 'wohlhabender-hof' });
  const res = again.processInstructions();
  assert.equal(res.applied, 0);
  assert.equal(res.duplicates, 1);
});

test('foreign savegameId is discarded', () => {
  const { sim, write } = setup();
  write([{ instructionId: 'x', type: 'MONEY_TRANSACTION', amount: 5, reason: 'OTHER' }], 'other');
  assert.equal(sim.processInstructions().discarded, true);
  assert.equal(sim.moneyLog.length, 0);
});

test('FARMLAND_TRANSFER + MONEY_TRANSACTION batch re-exports market context', () => {
  const { sim, write } = setup();
  const target = read(sim.paths.marketContext).farmlands.find((f) => f.ownerFarmId === 0);
  write([
    { instructionId: 't1', batchId: 'neg_1', type: 'FARMLAND_TRANSFER', farmlandId: target.farmlandId, direction: 'TO_PLAYER', price: 47500 },
    { instructionId: 't2', batchId: 'neg_1', type: 'MONEY_TRANSACTION', amount: -47500, reason: 'FARMLAND_PURCHASE' },
  ]);
  const res = sim.processInstructions();
  assert.equal(res.applied, 2);
  const ctx = read(sim.paths.marketContext);
  assert.equal(ctx.farmlands.find((f) => f.farmlandId === target.farmlandId).ownerFarmId, 1);
  sim.exportFarmFacts();
  assert.ok(read(sim.paths.farmFacts).assets.farmland.some((f) => f.farmlandId === target.farmlandId));
});

test('invalid member rejects the whole batch', () => {
  const { sim } = setup();
  writeFileSync(sim.paths.instructions, JSON.stringify({ savegameId: sim.savegameId, instructions: [
    { instructionId: 'a', batchId: 'b', type: 'FARMLAND_TRANSFER', farmlandId: 1, direction: 'TO_PLAYER' },
    { instructionId: 'c', batchId: 'b', type: 'MONEY_TRANSACTION', amount: -1, reason: 'NOPE' },
  ] }));
  const res = sim.processInstructions();
  assert.equal(res.rejected, 2);
  assert.equal(sim.farmlands.find((f) => f.farmlandId === 1).ownerFarmId, 1); // unchanged (owned in scenario)
  assert.deepEqual(read(sim.paths.ack).acks.map((a) => a.status), ['REJECTED', 'REJECTED']);
});

test('MULTIPLIER price event changes only its sell point and decays back', () => {
  const { sim, write } = setup();
  const base = sim.basePrice('MillNorth', 'WHEAT');
  write([{ instructionId: 'pe', type: 'PRICE_EVENT', priceMode: 'MULTIPLIER', fillType: 'WHEAT', sellPoint: 'MillNorth',
    peakMultiplier: 1.18, rampUpHours: 24, holdHours: 120, decayHours: 96 }]);
  sim.processInstructions();
  sim.gameTime += 24 * MS_PER_GAME_HOUR;
  assert.ok(Math.abs(sim.effectivePrice('MillNorth', 'WHEAT') - base * 1.18) < 1e-9);
  assert.equal(sim.effectivePrice('MillSouth', 'WHEAT'), sim.basePrice('MillSouth', 'WHEAT'));
  assert.equal(multiplierAt(sim.priceEvents[0], sim.priceEvents[0].gameTimeStart + 240 * MS_PER_GAME_HOUR), 1);
});

test('FIXED contract reports delivered quantity when the deadline passes', () => {
  const { sim, write } = setup();
  write([{ instructionId: 'fx', type: 'PRICE_EVENT', priceMode: 'FIXED', fillType: 'WHEAT', sellPoint: 'MillNorth',
    fixedPrice: 250, maxQuantity: 10000, deadlineGameTime: sim.gameTime + MS_PER_GAME_DAY }]);
  sim.processInstructions();
  assert.equal(sim.sell('MillNorth', 'WHEAT', 8200), 250);
  sim.tick(MS_PER_GAME_DAY);
  assert.deepEqual(read(sim.paths.ack).contractReports,
    [{ instructionId: 'fx', deliveredQuantity: 8200, maxQuantity: 10000, endReason: 'DEADLINE_REACHED' }]);
});

test('gameTimeEarliest defers application', () => {
  const { sim, write } = setup();
  write([{ instructionId: 'later', type: 'MONEY_TRANSACTION', amount: 1, reason: 'OTHER', gameTimeEarliest: sim.gameTime + MS_PER_GAME_HOUR }]);
  assert.equal(sim.processInstructions().deferred, 1);
  sim.advance(MS_PER_GAME_HOUR);
  assert.equal(sim.processInstructions().applied, 1);
});

test('truncated instructions file is skipped without crashing', () => {
  const { sim } = setup();
  writeFileSync(sim.paths.instructions, '{"savegameId": "x", "instr');
  assert.equal(sim.processInstructions().skipped, true);
});

// --------------------------------------------------------------- TODO T-02 / T-03 / T-04 parity with the mod
test('debits the balance does not cover are refused for the whole batch (INSUFFICIENT_FUNDS)', () => {
  const sim = new BridgeSimulator({ dir: mkdtempSync(join(tmpdir(), 'rpsim-sim-')), scenario: 'knappe-kasse' });
  sim.start();
  writeFileSync(sim.paths.instructions, JSON.stringify({ savegameId: sim.savegameId, instructions: [
    { instructionId: 'rate', type: 'MONEY_TRANSACTION', amount: -2500, reason: 'CREDIT_INSTALLMENT' },
    { instructionId: 't', batchId: 'b', type: 'FARMLAND_TRANSFER', farmlandId: 1, direction: 'TO_PLAYER' },
    { instructionId: 'm', batchId: 'b', type: 'MONEY_TRANSACTION', amount: -30000, reason: 'FARMLAND_PURCHASE' },
    { instructionId: 'ok', type: 'MONEY_TRANSACTION', amount: -200, reason: 'OTHER' },
  ] }));
  sim.processInstructions();
  const acks = Object.fromEntries(JSON.parse(readFileSync(sim.paths.ack, 'utf8')).acks.map((a) => [a.instructionId, a]));
  assert.equal(acks.rate.status, 'FAILED');
  assert.equal(acks.rate.message, 'INSUFFICIENT_FUNDS');
  assert.equal(acks.t.status, 'FAILED');
  assert.equal(acks.m.status, 'FAILED');
  assert.equal(acks.ok.status, 'APPLIED');
  assert.equal(sim.balance, 300);
  assert.equal(sim.farmlands.find((f) => f.farmlandId === 1).ownerFarmId, 0);
});

test('reload without saving restores the saved game and forgets later bookings', () => {
  const sim = new BridgeSimulator({ dir: mkdtempSync(join(tmpdir(), 'rpsim-sim-')), scenario: 'wohlhabender-hof' });
  sim.start();
  const savedAt = sim.saveGame();
  const balance = sim.balance;
  sim.advance(5 * 60 * 60 * 1000);
  writeFileSync(sim.paths.instructions, JSON.stringify({ savegameId: sim.savegameId, instructions: [
    { instructionId: 'lost', type: 'MONEY_TRANSACTION', amount: 25000, reason: 'CREDIT_DISBURSEMENT' }] }));
  sim.processInstructions();
  assert.ok(sim.processed.lost);
  assert.equal(sim.reloadWithoutSaving(), savedAt);
  assert.equal(sim.balance, balance);
  assert.equal(sim.processed.lost, undefined);
  const facts = JSON.parse(readFileSync(sim.paths.farmFacts, 'utf8'));
  assert.equal(facts.gameTime, savedAt);
  // the same instructionId is executed again after the reload
  sim.processInstructions();
  assert.equal(sim.processed.lost.status, 'APPLIED');
  assert.equal(sim.balance, balance + 25000);
});

test('NOTIFICATION is shown once; a late one is acknowledged but not shown (TODO T-21)', () => {
  const { sim, write } = setup();
  write([{ instructionId: 'n1', type: 'NOTIFICATION', text: 'FarmPulse: Neue Mail von Frau Berger', level: 'INFO',
    expiresAtGameTime: sim.gameTime + MS_PER_GAME_HOUR },
  { instructionId: 'n2', type: 'NOTIFICATION', text: 'alt', expiresAtGameTime: sim.gameTime - 1 }]);
  sim.processInstructions();
  sim.processInstructions();
  assert.deepEqual(sim.notifications.map((n) => n.text), ['FarmPulse: Neue Mail von Frau Berger']);
  const acks = Object.fromEntries(read(sim.paths.ack).acks.map((a) => [a.instructionId, a]));
  assert.equal(acks.n1.status, 'APPLIED');
  assert.equal(acks.n1.message, undefined);
  assert.equal(acks.n2.message, 'EXPIRED');
});

test('REPAIR_VEHICLE removes the damage of an own vehicle, unknown vehicles fail (TODO T-22)', () => {
  const { sim, write } = setup();
  const v = sim.vehicles[0];
  v.damage = 0.4;
  write([{ instructionId: 'r1', type: 'REPAIR_VEHICLE', vehicleId: v.uniqueId },
    { instructionId: 'r2', type: 'REPAIR_VEHICLE', vehicleId: 'veh_gone' }]);
  sim.processInstructions();
  assert.equal(v.damage, 0);
  const acks = Object.fromEntries(read(sim.paths.ack).acks.map((a) => [a.instructionId, a]));
  assert.equal(acks.r1.status, 'APPLIED');
  assert.equal(acks.r2.status, 'FAILED');
  assert.equal(acks.r2.message, 'VEHICLE_NOT_FOUND');
});
