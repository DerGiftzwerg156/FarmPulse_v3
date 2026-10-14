// Roadmap V4 (R4-Q3): the scenario hofplanung, the consumption counter per field (farm_facts.inputs), the feed stock
// (feedStock), the food values of a husbandry, the purchase prices (market_context.fillTypeCosts), the food groups
// (animalFood) and the new values of the crops, with the control endpoints and their place in the savegame.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BridgeSimulator } from '../src/simulator.js';
import { validate } from '../src/validate.js';
import { SCENARIOS } from '../src/scenarios.js';

const read = (p) => JSON.parse(readFileSync(p, 'utf8'));
function setup(scenario) {
  const sim = new BridgeSimulator({ dir: mkdtempSync(join(tmpdir(), 'rpsim-sim-')), scenario });
  sim.start();
  return sim;
}
const FEED_KEYS = ['foodLitersPerHour', 'foodCapacity', 'trough', 'strawLitersPerHour', 'straw', 'feedConsumed'];

test('only hofplanung exports the Roadmap V4 values (older mod without them)', () => {
  for (const scenario of Object.keys(SCENARIOS)) {
    const sim = setup(scenario);
    const facts = read(sim.paths.farmFacts);
    const ctx = read(sim.paths.marketContext);
    assert.equal(validate('farmFacts', facts), null, scenario);
    assert.equal(validate('marketContext', ctx), null, scenario);
    const expected = scenario === 'hofplanung';
    for (const key of ['inputs', 'feedStock']) assert.equal(key in facts, expected, `${scenario} ${key}`);
    for (const key of ['fillTypeCosts', 'animalFood']) assert.equal(key in ctx, expected, `${scenario} ${key}`);
    assert.equal((facts.husbandries ?? []).some((h) => FEED_KEYS.some((k) => k in h)), expected, scenario);
    assert.equal((ctx.fruitTypes ?? []).some((t) => 'plantablePeriods' in t), expected, scenario);
  }
});

test('hofplanung: counter, feed stock, food values, prices, food groups and crop windows', () => {
  const sim = setup('hofplanung');
  const facts = read(sim.paths.farmFacts);
  assert.deepEqual(facts.inputs, [
    { farmlandId: 2, fillType: 'FERTILIZER', liters: 1800, helperLiters: 1800 },
    { farmlandId: 2, fillType: 'SEEDS', liters: 3200, helperLiters: 0 },
    { farmlandId: 4, fillType: 'LIQUIDMANURE', liters: 60000, helperLiters: 0 },
  ]);
  assert.deepEqual(facts.feedStock.map((e) => e.fillType), ['DRYGRASS_WINDROW', 'SILAGE', 'STRAW']);
  const h = facts.husbandries[0];
  assert.deepEqual([h.foodLitersPerHour, h.foodCapacity, h.strawLitersPerHour, h.straw], [41.67, 20000, 8.33, 1200]);
  assert.deepEqual(h.trough, [{ fillType: 'DRYGRASS_WINDROW', amount: 2000 }, { fillType: 'SILAGE', amount: 4000 }]);
  const ctx = read(sim.paths.marketContext);
  assert.deepEqual(ctx.fillTypeCosts.find((c) => c.fillType === 'SEEDS'), { fillType: 'SEEDS', costPerLiter: 0.9 });
  assert.deepEqual(ctx.animalFood[0].groups.map((g) => g.title), ['Futtermischung', 'Silage', 'Heu']);
  assert.deepEqual(ctx.animalFood[0].mixtures, ['FORAGE']);
  const wheat = ctx.fruitTypes.find((t) => t.name === 'WHEAT');
  assert.deepEqual([wheat.seedUsagePerSqm, wheat.litersPerSqm, wheat.plantablePeriods, wheat.harvestablePeriods],
    [0.1, 0.95, [6, 7], [4, 5]]);
});

test('the consumption counter grows per field and fill type, only on own fields', () => {
  const sim = setup('hofplanung');
  sim.addInput(2, 'LIME', 4000.4);
  sim.addInput(2, 'SEEDS', 100, true);
  const facts = sim.exportFarmFacts();
  assert.equal(validate('farmFacts', facts), null);
  assert.deepEqual(facts.inputs.filter((e) => e.farmlandId === 2), [
    { farmlandId: 2, fillType: 'FERTILIZER', liters: 1800, helperLiters: 1800 },
    { farmlandId: 2, fillType: 'LIME', liters: 4000, helperLiters: 0 },
    { farmlandId: 2, fillType: 'SEEDS', liters: 3300, helperLiters: 100 },
  ]);
  assert.throws(() => sim.addInput(3, 'LIME', 100), /not owned/);
  assert.throws(() => sim.addInput(2, 'LIME', 0), /liters/);
  assert.throws(() => sim.addInput(2, '', 1), /fillType/);
  assert.throws(() => setup('feldbuch').addInput(2, 'LIME', 1), /exports no inputs/);
});

test('feed into silo and trough, the animals eat from the trough and the feed counter grows', () => {
  const sim = setup('hofplanung');
  sim.changeFeed({ fillType: 'SILAGE', amount: -20000 });
  sim.changeFeed({ fillType: 'HAY', amount: 500 });
  // the trough holds at most its capacity (20000 l, 6000 l inside)
  sim.changeFeed({ husbandryUniqueId: 'hus_00001', fillType: 'SILAGE', amount: 20000 });
  assert.deepEqual(sim.eat('hus_00001', 'SILAGE', 1000).taken, 1000);
  assert.deepEqual(sim.eat('hus_00001', 'DRYGRASS_WINDROW', 5000).taken, 2000);
  const facts = sim.exportFarmFacts();
  assert.equal(validate('farmFacts', facts), null);
  assert.deepEqual(facts.feedStock.find((e) => e.fillType === 'SILAGE'), { fillType: 'SILAGE', amount: 130000 });
  assert.deepEqual(facts.feedStock.find((e) => e.fillType === 'HAY'), { fillType: 'HAY', amount: 500 });
  const h = facts.husbandries[0];
  assert.deepEqual(h.trough, [{ fillType: 'SILAGE', amount: 17000 }]);
  assert.deepEqual(h.feedConsumed, [{ fillType: 'DRYGRASS_WINDROW', liters: 14000 },
    { fillType: 'SILAGE', liters: 37000 }]);
  assert.throws(() => sim.changeFeed({ fillType: 'SILAGE', amount: 0 }), /amount/);
  assert.throws(() => sim.changeFeed({ husbandryUniqueId: 'hus_x', fillType: 'SILAGE', amount: 1 }), /no trough/);
  assert.throws(() => sim.eat('hus_00001', 'SILAGE', -1), /liters/);
  assert.throws(() => setup('investor-milch').eat('hus_00001', 'SILAGE', 1), /exports no feed values/);
});

test('counters and feed stock are part of the savegame: a reload without saving brings back the saved values', () => {
  const sim = setup('hofplanung');
  sim.addInput(2, 'LIME', 1000);
  sim.eat('hus_00001', 'SILAGE', 500);
  sim.saveGame();
  sim.addInput(2, 'LIME', 2000);
  sim.eat('hus_00001', 'SILAGE', 1500);
  sim.changeFeed({ fillType: 'SILAGE', amount: -50000 });
  sim.reloadWithoutSaving();
  const facts = read(sim.paths.farmFacts);
  assert.equal(facts.inputs.find((e) => e.fillType === 'LIME').liters, 1000);
  assert.equal(facts.husbandries[0].feedConsumed.find((e) => e.fillType === 'SILAGE').liters, 36500);
  assert.equal(facts.feedStock.find((e) => e.fillType === 'SILAGE').amount, 150000);
  const again = new BridgeSimulator({ dir: sim.dir, scenario: 'hofplanung' });
  again.start();
  assert.equal(read(again.paths.farmFacts).inputs.find((e) => e.fillType === 'LIME').liters, 1000);
});
