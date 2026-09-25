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
