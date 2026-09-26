import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BridgeSimulator, MS_PER_GAME_DAY } from '../src/simulator.js';
import { SCENARIOS } from '../src/scenarios.js';
import { validate } from '../src/validate.js';

const tmp = () => mkdtempSync(join(tmpdir(), 'rpsim-sim-'));
const read = (p) => JSON.parse(readFileSync(p, 'utf8'));

for (const scenario of Object.keys(SCENARIOS)) {
  test(`scenario ${scenario} writes schema-valid export files`, () => {
    const sim = new BridgeSimulator({ dir: tmp(), scenario });
    sim.start();
    assert.equal(validate('farmFacts', read(sim.paths.farmFacts)), null);
    assert.equal(validate('marketContext', read(sim.paths.marketContext)), null);
    assert.equal(validate('instructionsAck', read(sim.paths.ack)), null);
  });
}

test('leerer-hof has no assets and zero balance', () => {
  const sim = new BridgeSimulator({ dir: tmp(), scenario: 'leerer-hof' });
  const facts = sim.buildFarmFacts();
  assert.equal(facts.liquidity.balance, 0);
  assert.deepEqual(facts.assets.vehicles, []);
  assert.deepEqual(facts.assets.farmland, []);
  assert.deepEqual(facts.assets.storage, []);
  assert.equal(facts.liabilities.vanillaLoan.active, false);
});

test('verschuldeter-hof has an active vanilla loan', () => {
  const facts = new BridgeSimulator({ dir: tmp(), scenario: 'verschuldeter-hof' }).buildFarmFacts();
  assert.equal(facts.liabilities.vanillaLoan.active, true);
  assert.ok(facts.liabilities.vanillaLoan.remainingAmount > facts.liquidity.balance);
});

test('voller-silobestand exports several filled storage entries', () => {
  const facts = new BridgeSimulator({ dir: tmp(), scenario: 'voller-silobestand' }).buildFarmFacts();
  assert.ok(facts.assets.storage.length >= 3);
  assert.ok(facts.prices.length > 0);
});

test('values drift over time deterministically with the same seed', () => {
  const a = new BridgeSimulator({ dir: tmp(), scenario: 'wohlhabender-hof', seed: 7 });
  const b = new BridgeSimulator({ dir: tmp(), scenario: 'wohlhabender-hof', seed: 7 });
  a.advance(3 * MS_PER_GAME_DAY);
  b.advance(3 * MS_PER_GAME_DAY);
  assert.deepEqual(a.buildFarmFacts(), b.buildFarmFacts());
  assert.notEqual(a.buildFarmFacts().liquidity.balance, 2400000);
  assert.equal(a.gameTime, MS_PER_GAME_DAY + 3 * MS_PER_GAME_DAY);
});

test('unknown scenario is rejected', () => {
  assert.throws(() => new BridgeSimulator({ dir: tmp(), scenario: 'nope' }), /unknown scenario/);
});

test('leasing-hof exports leased vehicles as liabilities, not as assets (TODO T-04)', () => {
  const facts = new BridgeSimulator({ dir: tmp(), scenario: 'leasing-hof' }).buildFarmFacts();
  assert.deepEqual(facts.assets.vehicles.map((v) => v.uniqueId), ['veh_00001']);
  assert.deepEqual(facts.liabilities.leasing, [{ uniqueId: 'veh_00101' }, { uniqueId: 'veh_00102' }]);
});

test('market_context is re-written on a regular tick only when it changed (TODO T-01)', () => {
  const sim = new BridgeSimulator({ dir: tmp(), scenario: 'wohlhabender-hof' });
  sim.start();
  assert.equal(sim.exportMarketContext(false), null);
  sim.farmlands[0].ownerFarmId = sim.farmlands[0].ownerFarmId === 1 ? 0 : 1;
  assert.notEqual(sim.exportMarketContext(false), null);
});

test('the FS25 calendar is exported and follows a change of days per period (TODO T-08)', () => {
  const sim = new BridgeSimulator({ dir: tmp(), scenario: 'wohlhabender-hof', daysPerPeriod: 3 });
  sim.gameTime = 40 * MS_PER_GAME_DAY + 1000; // day 40: period index 13 -> period 2 (April) of year 2, day 2
  assert.deepEqual(sim.buildFarmFacts().calendar, { period: 2, dayInPeriod: 2, daysPerPeriod: 3, year: 2, monotonicDay: 40,
    season: 'SPRING' });
  assert.deepEqual(sim.setDaysPerPeriod(5), { period: 2, dayInPeriod: 2, daysPerPeriod: 5, year: 2, monotonicDay: 40,
    season: 'SPRING' });
  sim.gameTime = 44 * MS_PER_GAME_DAY;
  assert.equal(sim.buildFarmFacts().calendar.period, 3);
  assert.equal(validate('farmFacts', sim.buildFarmFacts()), null);
});

test('konflikt-mods reports detected mods, farmland 16 is not buyable (TODO T-09 / T-11)', () => {
  const ctx = new BridgeSimulator({ dir: tmp(), scenario: 'konflikt-mods' }).buildMarketContext();
  assert.deepEqual(ctx.detectedMods, ['FS25_MarketDynamics', 'FS25_UsedPlus']);
  assert.equal(ctx.farmlands.find((f) => f.farmlandId === 16).showOnFarmlandsScreen, false);
  assert.equal(ctx.farmlands.find((f) => f.farmlandId === 1).showOnFarmlandsScreen, true);
  assert.equal(validate('marketContext', ctx), null);
});

test('prices carry the trend of the price walk (TODO T-10)', () => {
  const sim = new BridgeSimulator({ dir: tmp(), scenario: 'wohlhabender-hof' });
  sim.advance(24 * 60 * 60 * 1000);
  const trends = new Set(sim.buildFarmFacts().prices.map((p) => p.trend));
  for (const t of trends) assert.ok(['CLIMBING', 'FALLING', 'STABLE'].includes(t));
});

test('vanilla contracts are exported and follow the player (TODO T-22)', () => {
  const sim = new BridgeSimulator({ dir: tmp(), scenario: 'wohlhabender-hof' });
  const before = sim.balance;
  assert.deepEqual(sim.buildFarmFacts().missions.map((m) => [m.uniqueId, m.status]),
    [['mission_001', 'AVAILABLE'], ['mission_002', 'AVAILABLE']]);
  sim.setMission('mission_001', 'RUNNING');
  sim.setMission('mission_001', 'FINISHED', true);
  const m = sim.buildFarmFacts().missions[0];
  assert.equal(m.status, 'FINISHED');
  assert.equal(m.success, true);
  assert.equal(sim.balance, before + 5200);
  assert.equal(validate('farmFacts', sim.buildFarmFacts()), null);
});
