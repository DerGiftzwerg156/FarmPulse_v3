// Optional HTTP control API for manual testing and E2E tests (e.g. "fast-forward game time").
import { createServer } from 'node:http';
import { MS_PER_GAME_HOUR } from './simulator.js';

async function body(req) {
  let raw = '';
  for await (const chunk of req) raw += chunk;
  return raw ? JSON.parse(raw) : {};
}

export function startControlServer(sim, port, log = () => {}) {
  const server = createServer(async (req, res) => {
    const send = (code, doc) => {
      res.writeHead(code, { 'content-type': 'application/json', 'access-control-allow-origin': '*' });
      res.end(JSON.stringify(doc));
    };
    try {
      const url = new URL(req.url, 'http://localhost');
      if (req.method === 'GET' && url.pathname === '/state') {
        return send(200, { savegameId: sim.savegameId, scenario: sim.scenario, gameTime: sim.gameTime,
          balance: sim.balance, priceEvents: sim.priceEvents, moneyLog: sim.moneyLog.slice(-50),
          notifications: sim.notifications.slice(-50), roster: sim.roster, prompts: sim.openPrompts(),
          responses: sim.responses });
      }
      if (req.method === 'POST' && url.pathname === '/advance') {
        const b = await body(req);
        const hours = Number(b.hours ?? 0) + Number(b.days ?? 0) * 24;
        // advance in steps of max 24h so every intermediate day is exported/processed like in the game
        let remaining = hours;
        while (remaining > 0) {
          const step = Math.min(24, remaining);
          sim.tick(step * MS_PER_GAME_HOUR);
          remaining -= step;
        }
        log(`advanced ${hours}h`);
        return send(200, { gameTime: sim.gameTime });
      }
      if (req.method === 'POST' && url.pathname === '/tick') {
        const b = await body(req);
        return send(200, sim.tick(Number(b.gameMinutes ?? 0) * 60 * 1000));
      }
      if (req.method === 'POST' && url.pathname === '/sell') {
        const b = await body(req);
        const price = sim.sell(b.sellPoint, b.fillType, Number(b.liters));
        sim.exportFarmFacts();
        return send(200, { price, balance: sim.balance });
      }
      if (req.method === 'POST' && url.pathname === '/days-per-period') {
        const b = await body(req);
        const calendar = sim.setDaysPerPeriod(Number(b.daysPerPeriod));
        sim.exportFarmFacts();
        return send(200, calendar);
      }
      if (req.method === 'POST' && url.pathname === '/save') {
        return send(200, { savedAtGameTime: sim.saveGame() });
      }
      if (req.method === 'POST' && url.pathname === '/reload-without-saving') {
        const gameTime = sim.reloadWithoutSaving();
        log(`reloaded the last save (game time ${gameTime})`);
        return send(200, { gameTime });
      }
      if (req.method === 'POST' && url.pathname === '/mission') {
        const b = await body(req);
        const m = sim.setMission(b.uniqueId, b.status, b.success);
        sim.exportFarmFacts();
        return send(200, m);
      }
      // Roadmap V2 (R2-Q2): change the optional farm_facts blocks of the scenario
      const patches = { '/book': (b) => sim.bookGame(b.moneyType, Number(b.amount)),
        '/weather': (b) => sim.setWeather(b), '/husbandry': (b) => sim.setHusbandry(b),
        '/field': (b) => sim.setField(b), '/field-rules': (b) => sim.setFieldRules(b), '/jobs': (b) => sim.setActiveJobs(b.activeJobs ?? []),
        // Roadmap V3 R3-H1 / R3-H5 (scenario nachbarhandel)
        '/npc-field': (b) => sim.setNpcField(b), '/mission-limit': (b) => sim.setMissionLimit(b.reached),
        // Roadmap V3.1 (R31-Q2)
        '/snow': (b) => sim.setSnow(b.height), '/vehicle-positions': (b) => sim.setVehiclePositions(b.positions),
        '/fuel': (b) => sim.setFuel(b.uniqueId, b.liters) };
      if (req.method === 'POST' && patches[url.pathname]) {
        const result = patches[url.pathname](await body(req));
        sim.exportFarmFacts();
        return send(200, result);
      }
      // Roadmap V2 R2-D: the player bypasses the tool in the game menus
      if (req.method === 'POST' && url.pathname === '/vanilla-loan') {
        const result = sim.changeVanillaLoan(Number((await body(req)).change));
        sim.exportFarmFacts();
        return send(200, result);
      }
      if (req.method === 'POST' && url.pathname === '/vanilla-farmland') {
        const b = await body(req);
        const result = sim.vanillaFarmland(Number(b.farmlandId), b.toPlayer === true);
        sim.exportFarmFacts();
        sim.exportMarketContext();
        return send(200, result);
      }
      // Roadmap V2 R2-F: the player answers a yes/no question in the game
      if (req.method === 'POST' && url.pathname === '/answer') {
        const b = await body(req);
        try {
          return send(200, sim.answer(b.promptId, b.answer));
        } catch (e) {
          return send(400, { error: e.message });
        }
      }
      if (req.method === 'POST' && url.pathname === '/balance') {
        const b = await body(req);
        sim.balance = Number(b.balance);
        sim.exportFarmFacts();
        return send(200, { balance: sim.balance });
      }
      return send(404, { error: 'unknown endpoint' });
    } catch (e) {
      return send(500, { error: e.message });
    }
  });
  server.listen(port, () => log(`control API on http://localhost:${port} (GET /state, POST /advance|/tick|/sell|/balance|/mission|/days-per-period|/save|/reload-without-saving|/book|/weather|/husbandry|/field|/field-rules|/jobs|/npc-field|/mission-limit|/vanilla-loan|/vanilla-farmland|/answer)`));
  return server;
}
