#!/usr/bin/env node
// Bridge simulator CLI. See README.md for usage.
import { parseArgs } from 'node:util';
import { resolve } from 'node:path';
import { BridgeSimulator, MS_PER_GAME_HOUR } from './simulator.js';
import { SCENARIOS } from './scenarios.js';
import { startControlServer } from './control.js';

const { values } = parseArgs({
  options: {
    dir: { type: 'string', default: './runtime/modSettings/FS25_RPSim' },
    scenario: { type: 'string', default: 'wohlhabender-hof' },
    interval: { type: 'string', default: '5000' },
    'game-minutes-per-tick': { type: 'string', default: '60' },
    'savegame-id': { type: 'string' },
    seed: { type: 'string', default: '42' },
    'control-port': { type: 'string', default: '8099' },
    once: { type: 'boolean', default: false },
    'advance-hours': { type: 'string', default: '0' },
    reset: { type: 'boolean', default: false },
    'list-scenarios': { type: 'boolean', default: false },
    help: { type: 'boolean', default: false },
  },
});

if (values.help) {
  console.log(`rpsim bridge simulator
  --dir <path>                  bridge folder (default ./runtime/modSettings/FS25_RPSim)
  --scenario <name>             ${Object.keys(SCENARIOS).join(' | ')}
  --interval <ms>               real-time ms between cycles (default 5000)
  --game-minutes-per-tick <n>   game time advanced per cycle (default 60)
  --savegame-id <id>            simulated savegameId
  --seed <n>                    random seed (default 42)
  --control-port <port>         HTTP control API port, 0 = off (default 8099)
  --once                        export once, process instructions once, exit
  --advance-hours <n>           with --once: advance game time by n hours first (in 24 h steps)
  --reset                       delete previous simulator state before starting
  --list-scenarios              print scenarios and exit`);
  process.exit(0);
}
if (values['list-scenarios']) {
  for (const [name, s] of Object.entries(SCENARIOS)) console.log(`${name.padEnd(20)} ${s.description}`);
  process.exit(0);
}

const sim = new BridgeSimulator({
  dir: resolve(values.dir),
  scenario: values.scenario,
  savegameId: values['savegame-id'],
  seed: Number(values.seed),
  log: (m) => console.log(`[sim] ${m}`),
});
if (values.reset) {
  sim.reset();
}
sim.start();
console.log(`[sim] scenario=${values.scenario} savegameId=${sim.savegameId} dir=${sim.dir}`);

if (values.once) {
  let remaining = Number(values['advance-hours']);
  while (remaining > 0) {
    const step = Math.min(24, remaining);
    sim.advance(step * MS_PER_GAME_HOUR);
    remaining -= step;
  }
  const res = sim.processInstructions();
  sim.exportFarmFacts();
  console.log(`[sim] processed: ${JSON.stringify(res)}`);
  process.exit(0);
}

const tickMs = Number(values['game-minutes-per-tick']) * 60 * 1000;
const timer = setInterval(() => {
  try {
    const res = sim.tick(tickMs);
    if (res.applied || res.rejected || res.discarded) console.log(`[sim] t=${(sim.gameTime / MS_PER_GAME_HOUR).toFixed(1)}h ${JSON.stringify(res)}`);
  } catch (e) {
    console.error(`[sim] cycle failed: ${e.message}`);
  }
}, Number(values.interval));

let server;
const port = Number(values['control-port']);
if (port > 0) {
  server = startControlServer(sim, port, (m) => console.log(`[sim] ${m}`));
}
const stop = () => { clearInterval(timer); server?.close(); process.exit(0); };
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
