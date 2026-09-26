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
          notifications: sim.notifications.slice(-50) });
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
  server.listen(port, () => log(`control API on http://localhost:${port} (GET /state, POST /advance|/tick|/sell|/balance|/days-per-period|/save|/reload-without-saving)`));
  return server;
}
