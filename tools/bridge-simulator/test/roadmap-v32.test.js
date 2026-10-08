// Roadmap V3.2 (R32-Q2): the scenarios grossauftrag and investor-milch, the optional milk storage of a husbandry
// (husbandries[].storage[]), HUSBANDRY_TRANSFER with its failure codes, the new money reasons and the control endpoint.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BridgeSimulator } from '../src/simulator.js';
import { validate } from '../src/validate.js';
import { SCENARIOS } from '../src/scenarios.js';

const read = (p) => JSON.parse(readFileSync(p, 'utf8'));
function setup(scenario) {
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
const milk = (id, extra = {}) => ({ instructionId: id, type: 'HUSBANDRY_TRANSFER', husbandryUniqueId: 'hus_00001',
  fillType: 'MILK', amount: 5000, ...extra });

test('only investor-milch exports the milk storage of a husbandry (older mod without it)', () => {
  for (const scenario of Object.keys(SCENARIOS)) {
    const facts = read(setup(scenario).sim.paths.farmFacts);
    assert.equal(validate('farmFacts', facts), null, scenario);
    assert.equal((facts.husbandries ?? []).some((h) => 'storage' in h), scenario === 'investor-milch', scenario);
  }
});

test('grossauftrag: full canola silo and the oil mill as sell point of the map', () => {
  const { sim } = setup('grossauftrag');
  const facts = read(sim.paths.farmFacts);
  const ctx = read(sim.paths.marketContext);
  assert.deepEqual(facts.tradeStorage.find((e) => e.fillType === 'CANOLA'),
    { fillType: 'CANOLA', amount: 600000, freeCapacity: 0 });
  const mill = ctx.sellPoints.find((s) => s.id === 'OilMillNorth');
  assert.deepEqual(mill, { id: 'OilMillNorth', name: 'Ölmühle Nord', acceptedFillTypes: ['CANOLA', 'SOYBEAN', 'SUNFLOWER'] });
  assert.ok(facts.prices.some((p) => p.sellPoint === 'OilMillNorth' && p.fillType === 'CANOLA' && p.currentPrice > 0));
  // the oil mill belongs to this scenario only
  assert.equal(read(setup('nachbarhandel').sim.paths.marketContext).sellPoints.some((s) => s.id === 'OilMillNorth'), false);
});

test('investor-milch: HUSBANDRY_TRANSFER takes the milk out of the stable', () => {
  const { sim, run } = setup('investor-milch');
  assert.deepEqual(sim.buildFarmFacts().husbandries[0].storage, [{ fillType: 'MILK', amount: 12000, capacity: 30000 }]);
  const acks = run([milk('m1')]);
  assert.equal(acks.m1.status, 'APPLIED');
  assert.deepEqual(sim.buildFarmFacts().husbandries[0].storage, [{ fillType: 'MILK', amount: 7000, capacity: 30000 }]);
});

test('HUSBANDRY_TRANSFER failure codes take nothing out', () => {
  const { sim, run } = setup('investor-milch');
  const acks = run([milk('nf', { husbandryUniqueId: 'hus_x' }), milk('uf', { fillType: 'UNICORNMILK' }),
    milk('wf', { fillType: 'WHEAT' }), milk('is', { amount: 12001 })]);
  assert.equal(acks.nf.message, 'HUSBANDRY_NOT_FOUND');
  assert.equal(acks.uf.message, 'UNKNOWN_FILLTYPE');
  assert.equal(acks.wf.message, 'WRONG_FILLTYPE');
  assert.equal(acks.is.message, 'INSUFFICIENT_STOCK');
  for (const a of Object.values(acks)) assert.equal(a.status, 'FAILED');
  assert.equal(sim.buildFarmFacts().husbandries[0].storage[0].amount, 12000);
  // a stable without milk storage (older mod / no milk): every milk sort is the wrong fill type
  assert.equal(setup('viehhandel').run([milk('old')]).old.message, 'WRONG_FILLTYPE');
});

test('HUSBANDRY_TRANSFER and the money reasons are validated like the mod', () => {
  const { sim, run } = setup('investor-milch');
  writeFileSync(sim.paths.instructions, JSON.stringify({ savegameId: sim.savegameId, instructions: [
    milk('a0', { amount: 0 }), milk('f0', { fillType: '' }), milk('h0', { husbandryUniqueId: '' })] }));
  sim.processInstructions();
  const acks = Object.fromEntries(read(sim.paths.ack).acks.map((a) => [a.instructionId, a]));
  assert.match(acks.a0.message, /amount must be > 0/);
  assert.match(acks.f0.message, /fillType is required/);
  assert.match(acks.h0.message, /husbandryUniqueId is required/);
  for (const a of Object.values(acks)) assert.equal(a.status, 'REJECTED');
  const money = run(['INVESTOR_CAPITAL', 'INVESTOR_REPAYMENT', 'INVESTOR_PAYOUT', 'INVESTOR_COMPENSATION']
    .map((reason, i) => ({ instructionId: `r${i}`, type: 'MONEY_TRANSACTION', amount: 1, reason })));
  for (let i = 0; i < 4; i++) assert.equal(money[`r${i}`].status, 'APPLIED');
});

test('control: milk of a stable and its persistence', () => {
  const { sim, run } = setup('investor-milch');
  assert.deepEqual(sim.setHusbandryMilk('hus_00001', 'MILK', 40000),
    { husbandryUniqueId: 'hus_00001', fillType: 'MILK', amount: 30000, capacity: 30000 }); // capped at the capacity
  assert.throws(() => sim.setHusbandryMilk('hus_00001', 'WHEAT', 1), /no milk sort/);
  assert.throws(() => sim.setHusbandryMilk('hus_x', 'MILK', 1), /unknown husbandry/);
  sim.saveGame();
  run([milk('m1')]);
  assert.equal(sim.husbandries[0].storage[0].amount, 25000);
  sim.reloadWithoutSaving();
  assert.equal(sim.husbandries[0].storage[0].amount, 30000);
});
