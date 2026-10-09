// Roadmap V3.3 (R33-Q2): the scenario feldbuch, the optional rolling / mulching levels of the fields, the crops of the
// map (market_context.fruitTypes), the harvest counter (farm_facts.harvests) with its control endpoint and its place in
// the savegame.
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

test('only feldbuch exports the Roadmap V3.3 values (older mod without them)', () => {
  for (const scenario of Object.keys(SCENARIOS)) {
    const sim = setup(scenario);
    const facts = read(sim.paths.farmFacts);
    const ctx = read(sim.paths.marketContext);
    assert.equal(validate('farmFacts', facts), null, scenario);
    assert.equal(validate('marketContext', ctx), null, scenario);
    const expected = scenario === 'feldbuch';
    assert.equal('harvests' in facts, expected, scenario);
    assert.equal('fruitTypes' in ctx, expected, scenario);
    assert.equal((facts.fields ?? []).some((f) => 'rollerLevel' in f || 'stubbleShredLevel' in f), expected, scenario);
  }
});

test('feldbuch: fields with rolling and mulching levels, the crops of the map sorted by name', () => {
  const sim = setup('feldbuch');
  const facts = read(sim.paths.farmFacts);
  const wheat = facts.fields.find((f) => f.farmlandId === 2);
  assert.deepEqual([wheat.rollerLevel, wheat.stubbleShredLevel], [0, 0]);
  assert.equal(facts.fields.find((f) => f.farmlandId === 5).rollerLevel, 1);
  const ctx = read(sim.paths.marketContext);
  assert.deepEqual(ctx.fruitTypes.map((t) => t.name), ['BARLEY', 'CANOLA', 'GRASS', 'MAIZE', 'POTATO', 'WHEAT']);
  assert.deepEqual(ctx.fruitTypes.find((t) => t.name === 'MAIZE'),
    { name: 'MAIZE', fillType: 'MAIZE', title: 'Mais', regrows: false, needsRolling: true, products: ['CHAFF'] });
  assert.equal(ctx.fruitTypes.find((t) => t.name === 'POTATO').needsRolling, false);
  assert.equal(ctx.fruitTypes.find((t) => t.name === 'GRASS').regrows, true);
  assert.deepEqual(facts.harvests, [{ farmlandId: 4, fruitType: 'GRASS', fillType: 'GRASS_WINDROW', liters: 18000 }]);
});

test('the harvest counter grows per field, crop and product, only on own fields', () => {
  const sim = setup('feldbuch');
  sim.addHarvest(5, 'MAIZE', 'CHAFF', 30000.4);
  sim.addHarvest(5, 'MAIZE', 'CHAFF', 22000.2);
  sim.addHarvest(2, 'WHEAT', 'WHEAT', 8000);
  sim.addHarvest(4, 'GRASS', 'GRASS_WINDROW', 15000);
  const facts = sim.exportFarmFacts();
  assert.equal(validate('farmFacts', facts), null);
  assert.deepEqual(facts.harvests, [
    { farmlandId: 2, fruitType: 'WHEAT', fillType: 'WHEAT', liters: 8000 },
    { farmlandId: 4, fruitType: 'GRASS', fillType: 'GRASS_WINDROW', liters: 33000 },
    { farmlandId: 5, fruitType: 'MAIZE', fillType: 'CHAFF', liters: 52001 },
  ]);
  assert.throws(() => sim.addHarvest(3, 'WHEAT', 'WHEAT', 100), /not owned/);
  assert.throws(() => sim.addHarvest(2, 'WHEAT', 'WHEAT', 0), /liters/);
  assert.throws(() => sim.addHarvest(2, '', 'WHEAT', 1), /fruitType/);
  assert.throws(() => setup('lohnunternehmer').addHarvest(2, 'WHEAT', 'WHEAT', 1), /exports no harvests/);
});

test('the harvest counter is part of the savegame: a reload without saving brings back the saved value', () => {
  const sim = setup('feldbuch');
  sim.addHarvest(5, 'MAIZE', 'CHAFF', 30000);
  sim.saveGame();
  sim.addHarvest(5, 'MAIZE', 'CHAFF', 20000);
  assert.equal(sim.buildFarmFacts().harvests.find((h) => h.farmlandId === 5).liters, 50000);
  sim.reloadWithoutSaving();
  assert.equal(read(sim.paths.farmFacts).harvests.find((h) => h.farmlandId === 5).liters, 30000);
  // a restarted simulator (FS25 loads the savegame) keeps it too
  const again = new BridgeSimulator({ dir: sim.dir, scenario: 'feldbuch' });
  again.start();
  assert.equal(read(again.paths.farmFacts).harvests.find((h) => h.farmlandId === 5).liters, 30000);
});
