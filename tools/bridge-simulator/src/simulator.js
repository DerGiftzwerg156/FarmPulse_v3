// Core of the bridge simulator: holds a simulated FS25 farm state, writes the export files exactly like the
// real mod and consumes instructions with the same idempotency/batch/savegameId semantics
// (see mod/FS25_RPSim/src/import/Processor.lua and docs/dev/bridge-protocol.md).
import { mkdirSync, readFileSync, writeFileSync, renameSync, existsSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { SCENARIOS, MAP, MISSIONS } from './scenarios.js';
import { validate } from './validate.js';

export const MS_PER_GAME_HOUR = 60 * 60 * 1000;
export const MS_PER_GAME_DAY = 24 * MS_PER_GAME_HOUR;
const SIM_SEASONS = ['SPRING', 'SUMMER', 'AUTUMN', 'WINTER'];

const MONEY_REASONS = new Set(['CREDIT_DISBURSEMENT', 'CREDIT_INSTALLMENT', 'CREDIT_PENALTY', 'CREDIT_CALLBACK',
  'SALARY_PAYMENT', 'EMPLOYEE_EFFECT', 'SUBSIDY', 'STARTING_CAPITAL_ADJUSTMENT', 'FARMLAND_PURCHASE',
  'FARMLAND_SALE', 'OTHER', 'INSURANCE_PREMIUM', 'INSURANCE_PAYOUT', 'DAMAGE', 'WILDLIFE_COMPENSATION', 'VET_INVOICE',
  'LIVESTOCK_PREMIUM', 'LEASE_PAYMENT', 'MAINTENANCE_FEE',
  // Roadmap V2 (R2-Q1)
  'TAX_PAYMENT', 'TAX_REFUND', 'FINE', 'FAMILY', 'SPONSORING', 'COMPENSATION']);
// Roadmap V2 R2-B1: number of FS25 periods kept in the booking journal (proposed mod config financeJournalPeriods)
export const FINANCE_JOURNAL_PERIODS = 13;

/** Small deterministic PRNG (mulberry32) so scenario runs are reproducible. */
export function rng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export function multiplierAt(ev, gameTime) {
  const t = (gameTime - ev.gameTimeStart) / MS_PER_GAME_HOUR;
  const { peakMultiplier: peak, rampUpHours: ramp, holdHours: hold, decayHours: decay } = ev;
  if (t < 0) return 1;
  if (t < ramp) return 1 + (peak - 1) * (t / ramp);
  if (t < ramp + hold) return peak;
  if (t < ramp + hold + decay) return peak - (peak - 1) * ((t - ramp - hold) / decay);
  return 1;
}

export function validateInstruction(ins) {
  if (!ins || typeof ins !== 'object') return 'instruction is not an object';
  if (typeof ins.instructionId !== 'string' || !ins.instructionId) return 'missing instructionId';
  const num = (v) => typeof v === 'number' && Number.isFinite(v);
  switch (ins.type) {
    case 'MONEY_TRANSACTION':
      if (!num(ins.amount)) return 'amount must be a number';
      if (!MONEY_REASONS.has(ins.reason)) return `unknown reason ${ins.reason}`;
      return null;
    case 'PRICE_EVENT':
      if (!ins.fillType || !ins.sellPoint) return 'fillType and sellPoint are required';
      if (ins.priceMode === 'MULTIPLIER') {
        if (!num(ins.peakMultiplier) || ins.peakMultiplier <= 0) return 'peakMultiplier must be > 0';
        for (const f of ['rampUpHours', 'holdHours', 'decayHours']) if (!num(ins[f]) || ins[f] < 0) return `${f} must be >= 0`;
        return null;
      }
      if (ins.priceMode === 'FIXED') {
        if (!num(ins.fixedPrice) || ins.fixedPrice <= 0) return 'fixedPrice must be > 0';
        if (!num(ins.maxQuantity) || ins.maxQuantity <= 0) return 'maxQuantity must be > 0';
        if (!num(ins.deadlineGameTime)) return 'deadlineGameTime must be a number';
        return null;
      }
      return `unknown priceMode ${ins.priceMode}`;
    case 'FARMLAND_TRANSFER':
      if (!num(ins.farmlandId)) return 'farmlandId must be a number';
      if (!['TO_PLAYER', 'FROM_PLAYER'].includes(ins.direction)) return `unknown direction ${ins.direction}`;
      return null;
    case 'REPAIR_VEHICLE': // TODO T-22
      if (typeof ins.vehicleId !== 'string' || !ins.vehicleId) return 'vehicleId is required';
      // Roadmap V2 R2-A6: partial repair
      if (ins.targetDamage !== undefined && (!num(ins.targetDamage) || ins.targetDamage < 0 || ins.targetDamage > 1)) {
        return 'targetDamage must be between 0 and 1';
      }
      return null;
    case 'NOTIFICATION': // TODO T-21
      if (typeof ins.text !== 'string' || !ins.text) return 'text is required';
      if (ins.level !== undefined && !['INFO', 'OK', 'CRITICAL'].includes(ins.level)) return `unknown level ${ins.level}`;
      if (ins.expiresAtGameTime !== undefined && !num(ins.expiresAtGameTime)) return 'expiresAtGameTime must be a number';
      return null;
    case 'EMPLOYEE_ROSTER': { // Roadmap V2 R2-A0
      if (!Array.isArray(ins.employees)) return 'employees must be an array';
      for (const [i, e] of ins.employees.entries()) {
        if (!e || !num(e.employeeId)) return `employees[${i + 1}].employeeId must be a number`;
        if (typeof e.name !== 'string' || !e.name || typeof e.role !== 'string' || !e.role) {
          return `employees[${i + 1}]: name and role are required`;
        }
        if (!['ACTIVE', 'ON_LEAVE', 'STRIKE'].includes(e.status)) return `employees[${i + 1}]: unknown status ${e.status}`;
      }
      if (!['EMPLOYEES', 'VANILLA'].includes(ins.helperWageMode)) return `unknown helperWageMode ${ins.helperWageMode}`;
      if (typeof ins.strictHelperLimit !== 'boolean') return 'strictHelperLimit must be a boolean';
      return null;
    }
    case 'PROMPT': // Roadmap V2 R2-F2
      if (typeof ins.promptId !== 'string' || !ins.promptId) return 'promptId is required';
      if (typeof ins.title !== 'string' || !ins.title || typeof ins.text !== 'string' || !ins.text) {
        return 'title and text are required';
      }
      for (const f of ['yesLabel', 'noLabel']) {
        if (ins[f] !== undefined && (typeof ins[f] !== 'string' || !ins[f])) return `${f} must be a non-empty string`;
      }
      if (!num(ins.expiresGameTime)) return 'expiresGameTime must be a number';
      return null;
    default:
      return `unknown type ${ins.type}`;
  }
}

/** Mod parity (RPSimInstructions.checkFunds): the net money change of a batch must not push the balance below 0. */
export function fundsCover(balance, items) {
  const net = items.filter((i) => i?.type === 'MONEY_TRANSACTION' && typeof i.amount === 'number')
    .reduce((s, i) => s + i.amount, 0);
  return !(net < 0 && balance + net < 0);
}

export class BridgeSimulator {
  constructor({ dir, scenario = 'wohlhabender-hof', savegameId, seed = 42, startGameTime = MS_PER_GAME_DAY,
    retentionGameDays = 30, daysPerPeriod = 1, log = () => {} } = {}) {
    const preset = SCENARIOS[scenario];
    if (!preset) throw new Error(`unknown scenario '${scenario}' (${Object.keys(SCENARIOS).join(', ')})`);
    this.dir = dir;
    this.paths = {
      exportDir: join(dir, 'export'),
      importDir: join(dir, 'import'),
      farmFacts: join(dir, 'export', 'farm_facts.json'),
      marketContext: join(dir, 'export', 'market_context.json'),
      instructions: join(dir, 'import', 'instructions.json'),
      ack: join(dir, 'import', 'instructions_ack.json'),
      savegame: join(dir, 'simulator_savegame.json'),
    };
    this.log = log;
    this.scenario = scenario;
    this.random = rng(seed);
    this.retentionGameDays = retentionGameDays;
    this.savegameId = savegameId ?? `map_erlengrund_sim_${scenario.replace(/[^a-z0-9]+/g, '_')}`;
    this.gameTime = startGameTime;
    this.balance = preset.balance;
    this.vanillaLoan = preset.vanillaLoan;
    this.drift = preset.drift;
    this.vehicles = preset.vehicles.map((v) => ({ ...v }));
    this.leasedVehicles = (preset.leasedVehicles ?? []).map((v) => ({ ...v }));
    this.placeables = preset.placeables.map((p) => ({ ...p }));
    this.animals = preset.animals.map((a) => ({ ...a }));
    this.storage = structuredClone(preset.storage);
    this.farmlands = MAP.farmlands.map((f) => ({ ...f, ownerFarmId: preset.ownedFarmlands.includes(f.farmlandId) ? 1 : 0 }));
    this.detectedMods = [...(preset.detectedMods ?? [])];
    // FS25 calendar (TODO T-08): period index counted from monotonic day 0 = period 1 (March) of year 1
    // Roadmap V2: a scenario may start in another period (startPeriod = period on the first simulated day)
    this.calendar = preset.startPeriod
      ? { daysPerPeriod, anchorDay: Math.floor(this.gameTime / MS_PER_GAME_DAY), anchorIndex: preset.startPeriod - 1 }
      : { daysPerPeriod, anchorDay: 0, anchorIndex: 0 };
    this.priceWalk = {};
    this.priceTrend = {};
    for (const sp of MAP.sellPoints) for (const ft of sp.acceptedFillTypes) this.priceWalk[`${sp.id}|${ft}`] = 1;
    this.processed = {};
    this.priceEvents = [];
    this.contractReports = [];
    this.moneyLog = [];
    this.notifications = []; // TODO T-21: in-game notifications shown to the "player"
    this.missions = MISSIONS.map((m) => ({ ...m })); // TODO T-22: vanilla contracts
    // Roadmap V2 (R2-Q2): optional farm_facts blocks - null = not exported (like a mod without the block)
    this.journal = preset.journal ?? null;
    this.finances = preset.journal ? { periods: [] } : null;
    this.workforce = preset.workforce ? structuredClone(preset.workforce) : null;
    this.husbandries = preset.husbandries ? structuredClone(preset.husbandries) : null;
    this.fields = preset.fields ? structuredClone(preset.fields) : null;
    this.weather = preset.weather ? { ...preset.weather } : null;
    this.fieldRules = preset.fieldRules ? { ...preset.fieldRules } : null;
    this.roster = null; // R2-A0: last EMPLOYEE_ROSTER (replaced completely)
    this.prompts = []; // R2-F2: yes/no questions shown to the "player"
    this.lastMarketContextJson = null;
    this.loadSavegame();
    this.savedGame = this.gameState();
  }

  // --------------------------------------------------------------- FS25 "save" / "load without saving" (TODO T-02)
  /** Everything the FS25 savegame (incl. the mod's FS25_RPSim.xml) would contain. */
  gameState() {
    return structuredClone({ gameTime: this.gameTime, balance: this.balance, vanillaLoan: this.vanillaLoan,
      vehicles: this.vehicles, leasedVehicles: this.leasedVehicles, placeables: this.placeables, animals: this.animals,
      storage: this.storage, farmlands: this.farmlands, processed: this.processed, priceEvents: this.priceEvents,
      contractReports: this.contractReports, calendar: this.calendar, ...this.roadmapV2State() });
  }

  /** Roadmap V2 state the mod keeps in its savegame XML (journal R2-B1, worked time R2-A4, roster R2-A0). */
  roadmapV2State() {
    return { finances: this.finances, workforce: this.workforce, husbandries: this.husbandries, fields: this.fields,
      weather: this.weather, roster: this.roster };
  }

  // --------------------------------------------------------------- FS25 calendar (TODO T-08)
  monotonicDay() {
    return Math.floor(this.gameTime / MS_PER_GAME_DAY);
  }

  /** Exported like g_currentMission.environment: currentPeriod, currentDayInPeriod, daysPerPeriod, currentYear. */
  buildCalendar() {
    const { daysPerPeriod: n, anchorDay, anchorIndex } = this.calendar;
    const day = this.monotonicDay();
    const index = anchorIndex + Math.floor((day - anchorDay) / n);
    const period = (((index % 12) + 12) % 12) + 1;
    return { period, dayInPeriod: (((day - anchorDay) % n) + n) % n + 1,
      daysPerPeriod: n, year: Math.floor(index / 12) + 1, monotonicDay: day,
      // simulated season name (the mod exports the name from the game's Season table, TODO T-21)
      season: SIM_SEASONS[Math.floor((period - 1) / 3)] };
  }

  /** The player changes "days per period" in FS25: the current period keeps its start day. */
  setDaysPerPeriod(n) {
    const c = this.buildCalendar();
    const index = this.calendar.anchorIndex + Math.floor((c.monotonicDay - this.calendar.anchorDay) / this.calendar.daysPerPeriod);
    const dayInPeriod = Math.min(c.dayInPeriod, n);
    this.calendar = { daysPerPeriod: n, anchorDay: c.monotonicDay - (dayInPeriod - 1), anchorIndex: index };
    return this.buildCalendar();
  }

  /** The player saves the game in FS25. */
  saveGame() {
    this.savedGame = this.gameState();
    return this.savedGame.gameTime;
  }

  /** The player quits without saving and loads the last save: game state and the mod's processed list go back. */
  reloadWithoutSaving() {
    Object.assign(this, structuredClone(this.savedGame));
    this.lastMarketContextJson = null;
    this.start();
    this.saveSavegame();
    return this.gameTime;
  }

  // --------------------------------------------------------------- persistence (simulated savegame XML)
  loadSavegame() {
    if (!existsSync(this.paths.savegame)) return;
    try {
      const s = JSON.parse(readFileSync(this.paths.savegame, 'utf8'));
      if (s.savegameId !== this.savegameId) return;
      Object.assign(this, { processed: s.processed ?? {}, priceEvents: s.priceEvents ?? [],
        contractReports: s.contractReports ?? [] });
      for (const k of ['gameTime', 'balance', 'vanillaLoan', 'vehicles', 'leasedVehicles', 'placeables', 'animals',
        'storage', 'farmlands', 'calendar', 'finances', 'workforce', 'husbandries', 'fields', 'weather', 'roster']) {
        if (s[k] !== undefined) this[k] = s[k];
      }
    } catch (e) {
      this.log(`WARN could not load simulator savegame: ${e.message}`);
    }
  }

  saveSavegame() {
    const s = { savegameId: this.savegameId, processed: this.processed, priceEvents: this.priceEvents,
      contractReports: this.contractReports, gameTime: this.gameTime, balance: this.balance, vanillaLoan: this.vanillaLoan,
      vehicles: this.vehicles, leasedVehicles: this.leasedVehicles, placeables: this.placeables, animals: this.animals,
      storage: this.storage, farmlands: this.farmlands, calendar: this.calendar, ...this.roadmapV2State() };
    this.writeJson(this.paths.savegame, s);
  }

  // --------------------------------------------------------------- file helpers
  bootstrap() {
    mkdirSync(this.paths.exportDir, { recursive: true });
    mkdirSync(this.paths.importDir, { recursive: true });
  }

  writeJson(path, doc) {
    const tmp = `${path}.tmp`;
    writeFileSync(tmp, JSON.stringify(doc, null, 2));
    renameSync(tmp, path);
  }

  // --------------------------------------------------------------- prices
  basePrice(sellPoint, fillType) {
    return MAP.basePrices[fillType] * (this.priceWalk[`${sellPoint}|${fillType}`] ?? 1);
  }

  activeContract(sellPoint, fillType) {
    return this.priceEvents.find((e) => e.priceMode === 'FIXED' && e.sellPoint === sellPoint && e.fillType === fillType
      && this.gameTime >= e.gameTimeStart && this.gameTime < e.deadlineGameTime && e.deliveredQuantity < e.maxQuantity);
  }

  effectivePrice(sellPoint, fillType) {
    const contract = this.activeContract(sellPoint, fillType);
    if (contract) return contract.fixedPrice;
    let factor = 1;
    for (const e of this.priceEvents) {
      if (e.priceMode === 'MULTIPLIER' && e.sellPoint === sellPoint && e.fillType === fillType) {
        factor *= multiplierAt(e, this.gameTime);
      }
    }
    return this.basePrice(sellPoint, fillType) * factor;
  }

  /** Simulates the player selling goods (used for FIXED contract quantity tracking). */
  sell(sellPoint, fillType, liters) {
    const price = this.effectivePrice(sellPoint, fillType);
    const contract = this.activeContract(sellPoint, fillType);
    if (contract) contract.deliveredQuantity += Math.min(liters, contract.maxQuantity - contract.deliveredQuantity);
    const stock = this.storage[fillType];
    if (stock) stock.amount = Math.max(0, stock.amount - liters);
    const revenue = Math.round((price * liters) / 1000);
    this.balance += revenue;
    if (this.journal) this.book(this.journal.income, revenue);
    return price;
  }

  // --------------------------------------------------------------- time
  /** Advances game time; balance/prices/wear drift proportionally (deterministic with seed). */
  advance(ms) {
    const hours = ms / MS_PER_GAME_HOUR;
    this.gameTime += ms;
    const days = hours / 24;
    const income = this.drift.income * days + (this.random() - 0.5) * this.drift.income * days;
    const expense = this.drift.expense * days;
    this.balance += Math.round(income - expense);
    if (this.journal) {
      this.book(this.journal.income, income);
      this.book(this.journal.expense, -expense);
    }
    // R2-A4: game time of every helper job that an employee drives counts as his working time
    for (const job of this.workforce?.activeJobs ?? []) {
      if (job.employeeId === undefined) continue;
      const key = String(job.employeeId);
      this.workforce.workedGameMs[key] = (this.workforce.workedGameMs[key] ?? 0) + ms;
    }
    for (const k of Object.keys(this.priceWalk)) {
      const step = (this.random() - 0.5) * 0.02 * Math.min(hours, 48) / 24;
      const before = this.priceWalk[k];
      this.priceWalk[k] = Math.min(1.3, Math.max(0.7, before * (1 + step)));
      // like SellingStation.getCurrentPricingTrend (TODO T-10)
      const change = this.priceWalk[k] / before - 1;
      this.priceTrend[k] = change > 0.002 ? 'CLIMBING' : change < -0.002 ? 'FALLING' : 'STABLE';
    }
    for (const v of this.vehicles) v.damage = Math.min(1, v.damage + 0.001 * days);
    this.collectEnded();
  }

  collectEnded() {
    const keep = [];
    for (const e of this.priceEvents) {
      if (e.priceMode === 'FIXED') {
        let endReason = null;
        if (e.deliveredQuantity >= e.maxQuantity) endReason = 'MAX_QUANTITY_REACHED';
        else if (this.gameTime >= e.deadlineGameTime) endReason = 'DEADLINE_REACHED';
        if (endReason) {
          this.contractReports.push({ instructionId: e.id, deliveredQuantity: Math.round(e.deliveredQuantity),
            maxQuantity: e.maxQuantity, endReason, endedAtGameTime: this.gameTime });
        } else keep.push(e);
      } else if (this.gameTime < e.gameTimeStart + (e.rampUpHours + e.holdHours + e.decayHours) * MS_PER_GAME_HOUR) {
        keep.push(e);
      }
    }
    this.priceEvents = keep;
  }

  // --------------------------------------------------------------- Roadmap V2 blocks (R2-Q2)
  /** R2-B1: cumulative sum per FS25 period and money type; only the last FINANCE_JOURNAL_PERIODS periods are kept. */
  book(moneyType, amount) {
    if (!this.finances || !moneyType || !amount) return;
    const { year, period } = this.buildCalendar();
    let entry = this.finances.periods.find((p) => p.year === year && p.period === period);
    if (!entry) {
      entry = { year, period, byType: {} };
      this.finances.periods.push(entry);
      this.finances.periods.sort((a, b) => a.year - b.year || a.period - b.period);
      this.finances.periods = this.finances.periods.slice(-FINANCE_JOURNAL_PERIODS);
    }
    entry.byType[moneyType] = (entry.byType[moneyType] ?? 0) + amount;
  }

  /** Adds the optional blocks the scenario has; the others stay absent. */
  roadmapV2Blocks() {
    const blocks = {};
    if (this.finances) {
      blocks.finances = { periods: this.finances.periods.map((p) => ({ year: p.year, period: p.period,
        byType: Object.fromEntries(Object.entries(p.byType).map(([k, v]) => [k, Math.round(v)])) })) };
    }
    if (this.workforce) {
      blocks.workforce = { activeJobs: this.workforce.activeJobs.map((j) => ({ ...j })).sort((a, b) => a.jobId - b.jobId),
        workedGameMs: Object.fromEntries(Object.entries(this.workforce.workedGameMs).map(([k, v]) => [k, Math.round(v)])) };
    }
    if (this.husbandries) {
      blocks.husbandries = this.husbandries.map((h) => structuredClone(h))
        .sort((a, b) => a.husbandryUniqueId.localeCompare(b.husbandryUniqueId));
    }
    if (this.fields) {
      // R2-C1: only fields on farmlands the player owns
      const owned = new Map(this.farmlands.filter((f) => f.ownerFarmId === 1).map((f) => [f.farmlandId, f]));
      blocks.fields = this.fields.filter((f) => owned.has(f.farmlandId))
        .map((f) => ({ name: String(f.farmlandId), hectares: owned.get(f.farmlandId).hectares, ...f }))
        .sort((a, b) => a.farmlandId - b.farmlandId);
    }
    if (this.fieldRules) blocks.fieldRules = { ...this.fieldRules };
    if (this.weather) blocks.weather = { ...this.weather };
    return blocks;
  }

  /**
   * Control API: a booking of the game (R2-B1), e.g. a vehicle purchase or leasing costs. It changes the balance and
   * lands in the journal under its FS25 money type, like Farm:changeBalance in the mod.
   */
  bookGame(moneyType, amount) {
    if (typeof moneyType !== 'string' || !moneyType || typeof amount !== 'number' || !Number.isFinite(amount)) {
      throw new Error('moneyType (string) and amount (number) are required');
    }
    this.balance += amount;
    this.book(moneyType, amount);
    return { balance: this.balance, finances: this.roadmapV2Blocks().finances ?? null };
  }

  /** Control API: the player changes the soil settings of the savegame (R2-C). */
  setFieldRules(patch) {
    if (!this.fieldRules) throw new Error(`scenario ${this.scenario} exports no fieldRules`);
    Object.assign(this.fieldRules, patch);
    return this.fieldRules;
  }

  /** Control API: the weather changes in the game (R2-C2). */
  setWeather(patch) {
    if (!this.weather) throw new Error(`scenario ${this.scenario} exports no weather`);
    Object.assign(this.weather, patch);
    return this.weather;
  }

  /** Control API: values of a husbandry change (R2-A7). */
  setHusbandry(patch) {
    const h = this.husbandries?.find((x) => x.husbandryUniqueId === patch.husbandryUniqueId);
    if (!h) throw new Error(`unknown husbandry ${patch.husbandryUniqueId}`);
    Object.assign(h, patch);
    return h;
  }

  /** Control API: the state of a field changes (R2-C1). */
  setField(patch) {
    const f = this.fields?.find((x) => x.farmlandId === patch.farmlandId);
    if (!f) throw new Error(`unknown field on farmland ${patch.farmlandId}`);
    Object.assign(f, patch);
    return f;
  }

  /**
   * Control API: helpers are started / stopped in the game (R2-A4); worked time already counted stays. Like the mod, a
   * job without employee gets the first free ACTIVE machine operator of the last roster (R2-A2).
   */
  setActiveJobs(activeJobs) {
    if (!this.workforce) throw new Error(`scenario ${this.scenario} exports no workforce`);
    this.workforce.activeJobs = activeJobs.map((j) => ({ ...j }));
    this.assignFreeOperators();
    for (const j of this.workforce.activeJobs) {
      if (j.employeeId !== undefined) this.workforce.workedGameMs[String(j.employeeId)] ??= 0;
    }
    return this.workforce;
  }

  /**
   * R2-A0 / R2-A2 / R2-A5 like the mod: helpers of striking employees are stopped, helpers of employees no longer ACTIVE
   * (dismissed, on leave) keep working as vanilla helpers, free jobs get the first free ACTIVE machine operator in list
   * order.
   */
  applyRosterToJobs() {
    if (!this.workforce) return;
    const byId = new Map(this.roster.employees.map((e) => [e.employeeId, e]));
    const kept = [];
    for (const j of this.workforce.activeJobs) {
      const e = j.employeeId === undefined ? undefined : byId.get(j.employeeId);
      if (e?.status === 'STRIKE') {
        this.log(`helper job ${j.jobId} stopped: ${e.name} is on strike`);
        continue;
      }
      if (j.employeeId !== undefined && e?.status !== 'ACTIVE') delete j.employeeId;
      kept.push(j);
    }
    this.workforce.activeJobs = kept;
    this.assignFreeOperators();
    for (const j of kept) {
      if (j.employeeId !== undefined) this.workforce.workedGameMs[String(j.employeeId)] ??= 0;
    }
  }

  assignFreeOperators() {
    if (!this.roster || !this.workforce) return;
    const busy = new Set(this.workforce.activeJobs.map((j) => j.employeeId).filter((id) => id !== undefined));
    for (const j of this.workforce.activeJobs) {
      if (j.employeeId !== undefined) continue;
      const free = this.roster.employees.find((e) => e.role === 'MACHINE_OPERATOR' && e.status === 'ACTIVE'
        && !busy.has(e.employeeId));
      if (!free) return;
      j.employeeId = free.employeeId;
      busy.add(free.employeeId);
    }
  }

  // --------------------------------------------------------------- exports
  buildFarmFacts() {
    const storage = Object.entries(this.storage).filter(([, s]) => s.amount > 0)
      .map(([fillType, s]) => ({ fillType, amount: Math.round(s.amount), capacity: Math.round(s.capacity) }))
      .sort((a, b) => a.fillType.localeCompare(b.fillType));
    const prices = [];
    for (const sp of MAP.sellPoints) {
      for (const ft of sp.acceptedFillTypes) {
        prices.push({ sellPoint: sp.id, fillType: ft, currentPrice: Math.round(this.effectivePrice(sp.id, ft)),
          trend: this.priceTrend[`${sp.id}|${ft}`] ?? 'STABLE' });
      }
    }
    return {
      schemaVersion: 1,
      gameTime: Math.round(this.gameTime),
      savegameId: this.savegameId,
      liquidity: { balance: Math.round(this.balance) },
      assets: {
        vehicles: this.vehicles.map((v) => ({ uniqueId: v.uniqueId, value: v.value,
          condition: Math.round((1 - Math.min(1, Math.max(0, v.damage))) * 100) })),
        placeables: this.placeables.map((p) => ({ ...p })),
        farmland: this.farmlands.filter((f) => f.ownerFarmId === 1)
          .map((f) => ({ farmlandId: f.farmlandId, hectares: f.hectares, price: f.price })),
        animals: this.animals.map((a) => ({ ...a })),
        storage,
      },
      liabilities: { vanillaLoan: { active: this.vanillaLoan > 0, remainingAmount: Math.round(this.vanillaLoan) },
        // leased vehicles are no assets (TODO T-04); leasing costs stay absent until the FS25 API is verified
        leasing: this.leasedVehicles.map((v) => ({ uniqueId: v.uniqueId,
          ...(typeof v.costPerPeriod === 'number' ? { costPerPeriod: v.costPerPeriod } : {}) }))
          .sort((a, b) => a.uniqueId.localeCompare(b.uniqueId)) },
      prices,
      calendar: this.buildCalendar(),
      missions: this.missions.map((m) => ({ ...m })).sort((a, b) => a.uniqueId.localeCompare(b.uniqueId)),
      ...this.roadmapV2Blocks(),
    };
  }

  /** The "player" takes / finishes a vanilla contract in the game (TODO T-22). */
  setMission(uniqueId, status, success) {
    const m = this.missions.find((x) => x.uniqueId === uniqueId);
    if (!m) throw new Error(`unknown mission ${uniqueId}`);
    m.status = status;
    if (status === 'FINISHED') {
      m.success = success !== false;
      if (m.success) {
        this.balance += m.reward;
        this.book('MISSIONS', m.reward);
      }
    } else {
      delete m.success;
    }
    return m;
  }

  buildMarketContext() {
    const fillTypes = [...new Set(MAP.sellPoints.flatMap((s) => s.acceptedFillTypes))].sort();
    return {
      savegameId: this.savegameId,
      mapName: MAP.mapName,
      sellPoints: MAP.sellPoints.map((s) => ({ id: s.id, name: s.name, acceptedFillTypes: [...s.acceptedFillTypes].sort(),
        ...(s.production ? { production: true, ownedByPlayer: s.ownedByPlayer === true } : {}) })),
      fillTypes,
      farmlands: this.farmlands.map((f) => ({ ...f, showOnFarmlandsScreen: f.showOnFarmlandsScreen !== false,
        defaultFarmProperty: f.defaultFarmProperty === true })),
      detectedMods: [...this.detectedMods].sort(),
    };
  }

  exportFarmFacts() {
    const doc = this.buildFarmFacts();
    const err = validate('farmFacts', doc);
    if (err) throw new Error(`farm_facts.json does not match schema: ${err}`);
    this.writeJson(this.paths.farmFacts, doc);
    return doc;
  }

  /** Like the mod: written on start / after FARMLAND_TRANSFER (force) and otherwise only when its content changed. */
  exportMarketContext(force = true) {
    const doc = this.buildMarketContext();
    const err = validate('marketContext', doc);
    if (err) throw new Error(`market_context.json does not match schema: ${err}`);
    const text = JSON.stringify(doc);
    if (!force && text === this.lastMarketContextJson) return null;
    this.writeJson(this.paths.marketContext, doc);
    this.lastMarketContextJson = text;
    return doc;
  }

  // --------------------------------------------------------------- import
  applyOne(ins) {
    switch (ins.type) {
      case 'MONEY_TRANSACTION':
        this.balance += ins.amount;
        this.moneyLog.push({ id: ins.instructionId, amount: ins.amount, reason: ins.reason, note: ins.note });
        // R2-B1: bookings of the tool are marked RPSIM_<REASON> instead of an FS25 money type
        this.book(`RPSIM_${ins.reason}`, ins.amount);
        return null;
      case 'FARMLAND_TRANSFER': {
        const f = this.farmlands.find((x) => x.farmlandId === ins.farmlandId);
        if (!f) return `unknown farmland ${ins.farmlandId}`;
        f.ownerFarmId = ins.direction === 'TO_PLAYER' ? 1 : 0;
        return null;
      }
      case 'PRICE_EVENT': {
        const ev = { id: ins.instructionId, priceMode: ins.priceMode, fillType: ins.fillType, sellPoint: ins.sellPoint,
          gameTimeStart: ins.gameTimeEarliest ?? this.gameTime };
        if (ins.priceMode === 'MULTIPLIER') {
          Object.assign(ev, { peakMultiplier: ins.peakMultiplier, rampUpHours: ins.rampUpHours, holdHours: ins.holdHours,
            decayHours: ins.decayHours });
        } else {
          Object.assign(ev, { fixedPrice: ins.fixedPrice, maxQuantity: ins.maxQuantity,
            deadlineGameTime: ins.deadlineGameTime, deliveredQuantity: 0 });
        }
        this.priceEvents.push(ev);
        return null;
      }
      case 'REPAIR_VEHICLE': {
        // like the mod: Wearable:setDamageAmount(0, true) on an own vehicle
        const v = this.vehicles.find((x) => x.uniqueId === ins.vehicleId);
        if (!v) return 'VEHICLE_NOT_FOUND';
        // R2-A6: partial repair down to targetDamage; a repair never raises the damage
        v.damage = Math.min(v.damage, ins.targetDamage ?? 0);
        return null;
      }
      case 'NOTIFICATION':
        // like the mod: a hint processed too late is acknowledged but not shown
        if (ins.expiresAtGameTime !== undefined && this.gameTime > ins.expiresAtGameTime) {
          this.applyNote = 'EXPIRED';
          return null;
        }
        this.notifications.push({ id: ins.instructionId, text: ins.text, level: ins.level ?? 'INFO', gameTime: this.gameTime });
        this.log(`in-game notification: ${ins.text}`);
        return null;
      case 'EMPLOYEE_ROSTER': // R2-A0: the list is replaced completely (idempotent)
        this.roster = { employees: ins.employees.map((e) => ({ ...e })), helperWageMode: ins.helperWageMode,
          strictHelperLimit: ins.strictHelperLimit };
        this.applyRosterToJobs();
        return null;
      case 'PROMPT': // R2-F2: expired prompts are dropped without being shown
        if (this.gameTime > ins.expiresGameTime) {
          this.applyNote = 'EXPIRED';
          return null;
        }
        this.prompts.push({ id: ins.instructionId, promptId: ins.promptId, title: ins.title, text: ins.text,
          yesLabel: ins.yesLabel, noLabel: ins.noLabel, expiresGameTime: ins.expiresGameTime, gameTime: this.gameTime });
        this.log(`in-game prompt: ${ins.title} - ${ins.text}`);
        return null;
      default:
        return 'unsupported type';
    }
  }

  /** Reads instructions.json and applies it. Returns a summary like the mod's processor. */
  processInstructions() {
    const res = { discarded: false, applied: 0, rejected: 0, deferred: 0, duplicates: 0, marketContextDirty: false,
      skipped: false };
    let doc = null;
    if (existsSync(this.paths.instructions)) {
      try {
        doc = JSON.parse(readFileSync(this.paths.instructions, 'utf8'));
        if (!doc || typeof doc !== 'object' || Array.isArray(doc)) throw new Error('document is not an object');
      } catch (e) {
        this.log(`WARN skipping unreadable instructions.json (retry next cycle): ${e.message}`);
        res.skipped = true;
        doc = null;
      }
    }
    if (doc) {
      if (doc.savegameId !== this.savegameId) {
        this.log(`WARN discarding instructions.json: savegameId '${doc.savegameId}' != '${this.savegameId}'`);
        res.discarded = true;
      } else {
        const batches = new Map();
        for (const ins of doc.instructions ?? []) {
          const key = ins?.batchId ?? ins?.instructionId ?? `#${batches.size}`;
          if (!batches.has(key)) batches.set(key, []);
          batches.get(key).push(ins);
        }
        const seen = new Set();
        for (const items of batches.values()) {
          const pending = items.filter((ins) => {
            const id = ins?.instructionId;
            if (typeof id === 'string' && (this.processed[id] || seen.has(id))) { res.duplicates++; return false; }
            if (typeof id === 'string') seen.add(id);
            return true;
          });
          if (pending.length === 0) continue;
          let invalid = null;
          for (const ins of pending) {
            let why = validateInstruction(ins);
            if (!why && ins.savegameId !== undefined && ins.savegameId !== this.savegameId) why = 'savegameId mismatch';
            if (why) { invalid = `${ins?.instructionId}: ${why}`; break; }
          }
          const mark = (status, message) => {
            for (const ins of pending) {
              if (typeof ins?.instructionId === 'string') {
                this.processed[ins.instructionId] = { gameTime: this.gameTime, status, ...(message ? { message } : {}) };
              }
            }
          };
          if (invalid) {
            this.log(`WARN rejecting batch: ${invalid}`);
            mark('REJECTED', invalid);
            res.rejected += pending.length;
          } else if (pending.some((ins) => ins.gameTimeEarliest !== undefined && ins.gameTimeEarliest > this.gameTime)) {
            res.deferred += pending.length;
          } else if (!fundsCover(this.balance, pending)) {
            // like the mod (TODO T-03): debits the balance does not cover are refused for the whole batch
            this.log(`WARN batch not executed: INSUFFICIENT_FUNDS`);
            mark('FAILED', 'INSUFFICIENT_FUNDS');
            res.rejected += pending.length;
          } else {
            let aborted = null;
            for (const ins of pending) {
              // like the mod: after a failed member the rest of the batch is not executed
              this.applyNote = null;
              const err = aborted ? `BATCH_ABORTED: ${aborted}` : this.applyOne(ins);
              if (err && !aborted && pending.length > 1) aborted = ins.instructionId;
              this.processed[ins.instructionId] = err
                ? { gameTime: this.gameTime, status: 'FAILED', message: err }
                : { gameTime: this.gameTime, status: 'APPLIED', ...(this.applyNote ? { message: this.applyNote } : {}) };
              if (err) res.rejected++; else res.applied++;
              if (!err && ins.type === 'FARMLAND_TRANSFER') res.marketContextDirty = true;
            }
          }
        }
        if (res.marketContextDirty) this.exportMarketContext();
      }
    }
    this.collectEnded();
    this.prune();
    this.writeAck();
    this.saveSavegame();
    return res;
  }

  prune() {
    const limit = this.gameTime - this.retentionGameDays * MS_PER_GAME_DAY;
    for (const [id, e] of Object.entries(this.processed)) if (e.gameTime < limit) delete this.processed[id];
    this.contractReports = this.contractReports.filter((r) => (r.endedAtGameTime ?? this.gameTime) >= limit);
  }

  buildAck() {
    return {
      savegameId: this.savegameId,
      acks: Object.entries(this.processed).sort(([a], [b]) => a.localeCompare(b))
        .map(([instructionId, e]) => ({ instructionId, appliedAtGameTime: e.gameTime, status: e.status,
          ...(e.message ? { message: e.message } : {}) })),
      contractReports: this.contractReports.map(({ instructionId, deliveredQuantity, maxQuantity, endReason }) =>
        ({ instructionId, deliveredQuantity, maxQuantity, endReason })),
    };
  }

  writeAck() {
    const doc = this.buildAck();
    const err = validate('instructionsAck', doc);
    if (err) throw new Error(`instructions_ack.json does not match schema: ${err}`);
    this.writeJson(this.paths.ack, doc);
  }

  // --------------------------------------------------------------- lifecycle
  /** Equivalent of the mod's loadMap: bootstrap + immediate fresh export. */
  start() {
    this.bootstrap();
    this.exportMarketContext();
    this.exportFarmFacts();
    this.writeAck();
  }

  /** One simulator cycle: advance game time, apply instructions, export facts. */
  tick(gameMs) {
    this.advance(gameMs);
    const res = this.processInstructions();
    this.exportFarmFacts();
    this.exportMarketContext(false);
    return res;
  }

  reset() {
    for (const p of [this.paths.savegame, this.paths.farmFacts, this.paths.marketContext, this.paths.ack]) {
      rmSync(p, { force: true });
    }
  }
}
