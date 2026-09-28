import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BridgeSimulator, MS_PER_GAME_DAY, MS_PER_GAME_HOUR } from '../src/simulator.js';
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

// ------------------------------------------------------------------ Roadmap V2 (R2-Q2)
const V2_BLOCKS = ['finances', 'workforce', 'husbandries', 'fields', 'weather'];
const V2_SCENARIOS = { 'helfer-hof': ['finances', 'workforce', 'weather'],
  'tierhof-krank': ['finances', 'husbandries', 'weather'], 'ernte-herbst': ['finances', 'fields', 'weather'] };

test('Roadmap V2: the new scenarios export their blocks, all others leave them out (older mod)', () => {
  for (const scenario of Object.keys(SCENARIOS)) {
    const facts = new BridgeSimulator({ dir: tmp(), scenario }).buildFarmFacts();
    const expected = V2_SCENARIOS[scenario] ?? [];
    assert.deepEqual(V2_BLOCKS.filter((b) => b in facts), expected, scenario);
    assert.equal(validate('farmFacts', facts), null, scenario);
  }
  const covered = new Set(Object.values(V2_SCENARIOS).flat());
  assert.deepEqual([...covered].sort(), [...V2_BLOCKS].sort());
});

test('Roadmap V2: the booking journal sums per FS25 period and money type (R2-B1)', () => {
  const sim = new BridgeSimulator({ dir: tmp(), scenario: 'helfer-hof' });
  assert.deepEqual(sim.buildFarmFacts().finances, { periods: [] });
  sim.advance(MS_PER_GAME_DAY); // day 2 = period 3 (May) with one day per period
  sim.sell('MillNorth', 'WHEAT', 1000);
  const periods = sim.buildFarmFacts().finances.periods;
  assert.equal(periods.length, 1);
  assert.deepEqual(Object.keys(periods[0].byType).sort(), ['AI', 'SOLD_PRODUCTS']);
  assert.ok(periods[0].byType.SOLD_PRODUCTS > 0);
  assert.equal(periods[0].byType.AI, -2600);
  for (let day = 0; day < 20; day++) sim.advance(MS_PER_GAME_DAY);
  const all = sim.buildFarmFacts().finances.periods;
  assert.equal(all.length, 13, 'only the last 13 periods are kept');
  assert.deepEqual(all.at(-1), { year: 2, period: 11, byType: all.at(-1).byType });
});

test('Roadmap V2: worked game time is counted for helpers driven by an employee only (R2-A4)', () => {
  const sim = new BridgeSimulator({ dir: tmp(), scenario: 'helfer-hof' });
  sim.advance(3 * MS_PER_GAME_HOUR);
  let wf = sim.buildFarmFacts().workforce;
  assert.deepEqual(wf.activeJobs, [{ jobId: 1, employeeId: 1, title: 'Fendt 942 Vario' },
    { jobId: 2, title: 'CLAAS LEXION 8900' }]);
  assert.deepEqual(wf.workedGameMs, { 1: 3 * MS_PER_GAME_HOUR, 2: 0 });
  sim.setActiveJobs([{ jobId: 5, employeeId: 2, title: 'John Deere 8R' }]);
  sim.advance(MS_PER_GAME_HOUR);
  wf = sim.buildFarmFacts().workforce;
  assert.deepEqual(wf.workedGameMs, { 1: 3 * MS_PER_GAME_HOUR, 2: MS_PER_GAME_HOUR });
});

test('Roadmap V2: husbandries, fields and weather can be changed like in the game (R2-A7, R2-C1, R2-C2)', () => {
  const stable = new BridgeSimulator({ dir: tmp(), scenario: 'tierhof-krank' });
  const [cows, pigs] = stable.buildFarmFacts().husbandries;
  assert.equal(cows.husbandryUniqueId, 'hus_00001');
  assert.equal(pigs.productivity, undefined, 'no productivity for pigs');
  stable.setHusbandry({ husbandryUniqueId: 'hus_00001', health: 80 });
  assert.equal(stable.buildFarmFacts().husbandries[0].health, 80);
  assert.throws(() => stable.setField({ farmlandId: 2 }), /unknown field/);

  const harvest = new BridgeSimulator({ dir: tmp(), scenario: 'ernte-herbst' });
  const facts = harvest.buildFarmFacts();
  assert.equal(facts.calendar.period, 7, 'the scenario starts in September');
  assert.deepEqual(facts.fields.map((f) => [f.farmlandId, f.name, f.fruitType ?? null]),
    [[2, '2', 'MAIZE'], [4, '4', 'POTATO'], [6, '6', 'WHEAT'], [7, '7', null]]);
  assert.equal(facts.fields[0].hectares, harvest.farmlands.find((f) => f.farmlandId === 2).hectares);
  harvest.setField({ farmlandId: 7, weedState: 0 });
  harvest.setWeather({ raining: false, rainFallScale: 0 });
  // a field that leaves the farm is no longer exported
  harvest.farmlands.find((f) => f.farmlandId === 6).ownerFarmId = 0;
  const after = harvest.buildFarmFacts();
  assert.deepEqual(after.fields.map((f) => f.farmlandId), [2, 4, 7]);
  assert.equal(after.fields[2].weedState, 0);
  assert.deepEqual(after.weather, { raining: false, rainFallScale: 0, groundWetness: 0.7 });
  assert.equal(validate('farmFacts', after), null);
  assert.throws(() => new BridgeSimulator({ dir: tmp(), scenario: 'leerer-hof' }).setWeather({}), /no weather/);
});

test('Roadmap V2: journal and worked time go back on a reload without saving', () => {
  const sim = new BridgeSimulator({ dir: tmp(), scenario: 'helfer-hof' });
  sim.start();
  sim.advance(MS_PER_GAME_DAY);
  sim.saveGame();
  const saved = sim.buildFarmFacts();
  sim.advance(MS_PER_GAME_DAY);
  assert.notDeepEqual(sim.buildFarmFacts().finances, saved.finances);
  sim.reloadWithoutSaving();
  const reloaded = sim.buildFarmFacts();
  assert.deepEqual(reloaded.finances, saved.finances);
  assert.deepEqual(reloaded.workforce, saved.workforce);
});

test('Roadmap V2: game bookings land in the journal under their money type (R2-B1)', () => {
  const sim = new BridgeSimulator({ dir: tmp(), scenario: 'ernte-herbst' });
  const before = sim.balance;
  const res = sim.bookGame('SHOP_PROPERTY_BUY', -90000);
  assert.equal(sim.balance, before - 90000);
  assert.equal(res.finances.periods.at(-1).byType.SHOP_PROPERTY_BUY, -90000);
  sim.bookGame('LEASING_COSTS', -1500);
  assert.equal(sim.buildFarmFacts().finances.periods.at(-1).byType.LEASING_COSTS, -1500);
  assert.throws(() => sim.bookGame('', 5), /required/);
  // a scenario without journal only changes the balance
  const old = new BridgeSimulator({ dir: tmp(), scenario: 'wohlhabender-hof' });
  assert.equal(old.bookGame('AI', -10).finances, null);
});
