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
