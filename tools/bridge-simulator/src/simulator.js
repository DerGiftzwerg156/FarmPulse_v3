// Core of the bridge simulator: holds a simulated FS25 farm state, writes the export files exactly like the
// real mod and consumes instructions with the same idempotency/batch/savegameId semantics
// (see mod/FS25_RPSim/src/import/Processor.lua and docs/dev/bridge-protocol.md).
import { mkdirSync, readFileSync, writeFileSync, renameSync, existsSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { SCENARIOS, MAP, MISSIONS, FRUIT_TYPES, SUB_TYPES } from './scenarios.js';
import { validate } from './validate.js';

export const MS_PER_GAME_HOUR = 60 * 60 * 1000;
export const MS_PER_GAME_DAY = 24 * MS_PER_GAME_HOUR;
const SIM_SEASONS = ['SPRING', 'SUMMER', 'AUTUMN', 'WINTER'];

const MONEY_REASONS = new Set(['CREDIT_DISBURSEMENT', 'CREDIT_INSTALLMENT', 'CREDIT_PENALTY', 'CREDIT_CALLBACK',
  'CREDIT_SPECIAL_REPAYMENT', 'CREDIT_PREPAYMENT_FEE',
  'SALARY_PAYMENT', 'EMPLOYEE_EFFECT', 'SUBSIDY', 'STARTING_CAPITAL_ADJUSTMENT', 'FARMLAND_PURCHASE',
  'FARMLAND_SALE', 'OTHER', 'INSURANCE_PREMIUM', 'INSURANCE_PAYOUT', 'DAMAGE', 'WILDLIFE_COMPENSATION', 'VET_INVOICE',
  'LIVESTOCK_PREMIUM', 'LEASE_PAYMENT', 'MAINTENANCE_FEE',
  // Roadmap V2 (R2-Q1)
  'TAX_PAYMENT', 'TAX_REFUND', 'FINE', 'FAMILY', 'SPONSORING', 'COMPENSATION',
  // "Schulungen"
  'TRAINING',
  // Roadmap V3 (R3-Q1)
  'LEASE_INCOME', 'GOODS_PURCHASE', 'GOODS_SALE', 'VEHICLE_PURCHASE', 'VEHICLE_SALE', 'CONTRACT_PENALTY',
  // Roadmap V3.1 (R31-Q1)
  'CONTRACTOR_FEE', 'MACHINE_RENT', 'LIVESTOCK_PURCHASE', 'LIVESTOCK_SALE', 'WINTER_SERVICE', 'DIRECT_PAYMENT',
  'INVESTMENT_GRANT', 'SOCIAL_INSURANCE', 'GUEST_INCOME', 'COOP_SHARES', 'COOP_DIVIDEND',
  'FARM_HOLIDAY_SETUP', 'TANK_LOCK',
  // owner decision 2026-10-06: severance before the first working day
  'SEVERANCE']);
// Roadmap V3.1 R31-A1: works of the contractor
const FIELD_WORKS = ['PLOW', 'CULTIVATE', 'LIME', 'SOW', 'FERTILIZE', 'HARVEST'];
// crop details a field loses when the contractor plows, cultivates or sows (R2-C1 fields of a standing crop)
const CROP_KEYS = ['fruitType', 'minHarvestingGrowthState', 'maxHarvestingGrowthState', 'withered', 'cut', 'fillType',
  'litersPerSqm'];
// Roadmap V2 R2-B1: number of FS25 periods kept in the booking journal (proposed mod config financeJournalPeriods)
export const FINANCE_JOURNAL_PERIODS = 13;
// Booking statement (owner decisions 2026-10-06): like the mod config bookingLogEntries / bookingLogSingleTypes
export const BOOKING_LOG_ENTRIES = 200;
export const BOOKING_LOG_SINGLE_TYPES = ['SHOP_VEHICLE_BUY', 'SHOP_VEHICLE_SELL', 'SHOP_PROPERTY_BUY',
  'SHOP_PROPERTY_SELL', 'FIELD_BUY', 'FIELD_SELL'];

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
        if (e.trainings !== undefined && !Array.isArray(e.trainings)) return `employees[${i + 1}].trainings must be an array`;
      }
      if (ins.trainingCategories !== undefined) { // "Schulungen"
        if (!ins.trainingCategories || typeof ins.trainingCategories !== 'object' || Array.isArray(ins.trainingCategories)) {
          return 'trainingCategories must be an object';
        }
        for (const [code, cats] of Object.entries(ins.trainingCategories)) {
          if (!Array.isArray(cats)) return `trainingCategories.${code} must be an array`;
        }
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
    // Roadmap V3 (R3-Q1): same checks as RPSimInstructions.validate
    case 'STORAGE_TRANSFER':
      if (!['IN', 'OUT'].includes(ins.direction)) return `unknown direction ${ins.direction}`;
      if (typeof ins.fillType !== 'string' || !ins.fillType) return 'fillType is required';
      if (!num(ins.amount) || ins.amount <= 0) return 'amount must be > 0';
      return null;
    case 'MISSION_CREATE':
      if (typeof ins.missionType !== 'string' || !ins.missionType) return 'missionType is required';
      if (!num(ins.farmlandId)) return 'farmlandId must be a number';
      return null;
    case 'VEHICLE_SPAWN':
      if (typeof ins.storeXmlFilename !== 'string' || !ins.storeXmlFilename) return 'storeXmlFilename is required';
      for (const f of ['ageMonths', 'operatingHours']) if (!num(ins[f]) || ins[f] < 0) return `${f} must be >= 0`;
      for (const f of ['damage', 'wear']) if (!num(ins[f]) || ins[f] < 0 || ins[f] > 1) return `${f} must be between 0 and 1`;
      // R31-A2: price 0 = borrowed or demo machine (no booking)
      if (!num(ins.price) || ins.price < 0) return 'price must be >= 0';
      if (!MONEY_REASONS.has(ins.moneyReason)) return `unknown moneyReason ${ins.moneyReason}`;
      return null;
    case 'VEHICLE_REMOVE':
      if (typeof ins.vehicleId !== 'string' || !ins.vehicleId) return 'vehicleId is required';
      return null;
    // Roadmap V3.1 (R31-Q1): same checks as RPSimInstructions.validate
    case 'FIELD_WORK':
      if (!num(ins.farmlandId)) return 'farmlandId must be a number';
      if (!FIELD_WORKS.includes(ins.work)) return `unknown work ${ins.work}`;
      if (ins.work === 'SOW') {
        if (typeof ins.fruitType !== 'string' || !ins.fruitType) return 'fruitType is required for SOW';
      } else if (ins.fruitType !== undefined) return 'fruitType is only allowed for SOW';
      return null;
    case 'ANIMAL_TRANSFER':
      if (typeof ins.husbandryUniqueId !== 'string' || !ins.husbandryUniqueId) return 'husbandryUniqueId is required';
      if (typeof ins.subType !== 'string' || !ins.subType) return 'subType is required';
      if (!Number.isInteger(ins.count) || ins.count < 1) return 'count must be a whole number > 0';
      if (ins.age !== undefined && (!num(ins.age) || ins.age < 0)) return 'age must be >= 0';
      if (!['IN', 'OUT'].includes(ins.direction)) return `unknown direction ${ins.direction}`;
      return null;
    case 'VEHICLE_FUEL':
      if (typeof ins.vehicleId !== 'string' || !ins.vehicleId) return 'vehicleId is required';
      if (!num(ins.delta) || ins.delta >= 0) return 'delta must be < 0';
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
    retentionGameDays = 30, daysPerPeriod = 1, marketContextIntervalMs = 60000, now = () => Date.now(), reset = false,
    log = () => {} } = {}) {
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
      playerResponses: join(dir, 'export', 'player_responses.json'), // Roadmap V2 R2-F1
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
    this.vehicles = structuredClone(preset.vehicles); // deep: R31-Q2 fuel is an object
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
    this.bookings = preset.journal ? { nextSeq: 1, entries: [] } : null; // booking statement, same hook as the journal
    this.workforce = preset.workforce ? structuredClone(preset.workforce) : null;
    this.husbandries = preset.husbandries ? structuredClone(preset.husbandries) : null;
    this.fields = preset.fields ? structuredClone(preset.fields) : null;
    this.weather = preset.weather ? { ...preset.weather } : null;
    this.fieldRules = preset.fieldRules ? { ...preset.fieldRules } : null;
    // Roadmap V3 (R3-Q2): optional blocks - null = not exported (like a mod without the block)
    this.npcFields = preset.npcFields ? structuredClone(preset.npcFields) : null; // R3-H1
    this.tradeStorage = preset.tradeStorage ? structuredClone(preset.tradeStorage) : null; // R3-H2: own silos
    this.storeVehicles = preset.storeVehicles ? structuredClone(preset.storeVehicles) : null; // R3-V1
    this.toolMissions = []; // R3-H5: contracts created by MISSION_CREATE (the vanilla ones stay in this.missions)
    // R3-H5: the game's contract limit of the player farm (hasFarmReachedMissionLimit), only in scenarios that have it
    this.missionLimitReached = preset.missionLimitReached ?? null;
    // Roadmap V3.1 (R31-Q2): a mod with the R31-Q1 contract (roadmapV31) exports dayTimeMs and vehiclePositions; the
    // field outlines only where the scenario has them; stables = free places and animals per subtype (not exported)
    this.roadmapV31 = preset.roadmapV31 === true;
    this.vehiclePositions = this.roadmapV31 ? structuredClone(preset.vehiclePositions ?? []) : null; // R31-D5
    this.fieldShapes = preset.fieldShapes ? structuredClone(preset.fieldShapes) : null; // R31-K1
    this.stables = preset.stables ? structuredClone(preset.stables) : null; // R31-A3
    this.roster = null; // R2-A0: last EMPLOYEE_ROSTER (replaced completely)
    this.prompts = []; // R2-F2: yes/no questions shown to the "player" (waiting for an answer)
    this.responses = []; // R2-F1: answers not yet acknowledged by the backend (ackedResponses)
    this.handledPrompts = {}; // R2-F1: answered / withdrawn questions (promptId -> expiresGameTime)
    this.lastMarketContextJson = null;
    // Like the mod's marketContextIntervalMs: market_context.json is rewritten every interval (real time), changed or not
    this.marketContextIntervalMs = marketContextIntervalMs;
    this.now = now;
    this.marketContextRefreshedAt = now();
    // --reset: forget the previous run before loading, otherwise its state (e.g. without the blocks of a newer
    // scenario) would survive in memory
    if (reset) this.reset();
    this.loadSavegame();
    this.savedGame = this.gameState();
  }

  // --------------------------------------------------------------- FS25 "save" / "load without saving" (TODO T-02)
  /** Everything the FS25 savegame (incl. the mod's FS25_RPSim.xml) would contain. */
  gameState() {
    return structuredClone({ gameTime: this.gameTime, balance: this.balance, vanillaLoan: this.vanillaLoan,
      vehicles: this.vehicles, leasedVehicles: this.leasedVehicles, placeables: this.placeables, animals: this.animals,
      storage: this.storage, farmlands: this.farmlands, processed: this.processed, priceEvents: this.priceEvents,
      contractReports: this.contractReports, calendar: this.calendar, ...this.roadmapV2State(),
      ...this.roadmapV3State(), ...this.roadmapV31State() });
  }

  /** Roadmap V3.1 state that changes through instructions or the control API (positions R31-D5, stables R31-A3). */
  roadmapV31State() {
    return { vehiclePositions: this.vehiclePositions, stables: this.stables };
  }

  /** Roadmap V3 state that changes through instructions (silo goods R3-H3/H4, contracts R3-H5). */
  roadmapV3State() {
    return { npcFields: this.npcFields, tradeStorage: this.tradeStorage, toolMissions: this.toolMissions,
      missionLimitReached: this.missionLimitReached };
  }

  /** Roadmap V2 state the mod keeps in its savegame XML (journal R2-B1, worked time R2-A4, roster R2-A0). */
  roadmapV2State() {
    return { finances: this.finances, bookings: this.bookings, workforce: this.workforce, husbandries: this.husbandries, fields: this.fields,
      weather: this.weather, roster: this.roster, prompts: this.prompts, responses: this.responses,
      handledPrompts: this.handledPrompts };
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
        'storage', 'farmlands', 'calendar', 'finances', 'bookings', 'workforce', 'husbandries', 'fields', 'weather', 'roster',
        'prompts', 'responses', 'handledPrompts', 'npcFields', 'tradeStorage', 'toolMissions', 'missionLimitReached',
        'vehiclePositions', 'stables']) {
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
      storage: this.storage, farmlands: this.farmlands, calendar: this.calendar, ...this.roadmapV2State(),
      ...this.roadmapV3State(), ...this.roadmapV31State() };
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
    if (this.journal) this.book(this.journal.income, revenue, { fillType, sellPoint, liters });
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
  book(moneyType, amount, detail = {}) {
    if (!this.finances || !moneyType || !amount) return;
    const { year, period, dayInPeriod } = this.buildCalendar();
    this.recordSingleBooking(year, period, dayInPeriod, moneyType, amount, detail);
    let entry = this.finances.periods.find((p) => p.year === year && p.period === period);
    if (!entry) {
      entry = { year, period, byType: {} };
      this.finances.periods.push(entry);
      this.finances.periods.sort((a, b) => a.year - b.year || a.period - b.period);
      this.finances.periods = this.finances.periods.slice(-FINANCE_JOURNAL_PERIODS);
    }
    entry.byType[moneyType] = (entry.byType[moneyType] ?? 0) + amount;
  }

  /**
   * Booking statement, like RPSimBookingLog.record: tool bookings and BOOKING_LOG_SINGLE_TYPES are single entries, the
   * rest is summed per game day and money type (sales also per fill type and sell point, with litres).
   */
  recordSingleBooking(year, period, day, category, amount, detail) {
    if (!this.bookings) return;
    const monotonicDay = this.monotonicDay();
    const single = category.startsWith('RPSIM_') || BOOKING_LOG_SINGLE_TYPES.includes(category);
    const liters = detail.liters > 0 ? detail.liters : undefined;
    if (!single) {
      const e = [...this.bookings.entries].reverse().find((x) => !x.single && x.monotonicDay === monotonicDay
        && x.category === category && x.fillType === detail.fillType && x.sellPoint === detail.sellPoint);
      if (e) {
        e.amount += amount;
        e.count += 1;
        if (liters !== undefined) e.liters = (e.liters ?? 0) + liters;
        return;
      }
    }
    this.bookings.entries.push({ seq: this.bookings.nextSeq++, gameTime: this.gameTime, year, period, day,
      monotonicDay, category, amount, count: 1, single, liters, fillType: detail.fillType,
      sellPoint: detail.sellPoint, note: detail.note });
    this.bookings.entries = this.bookings.entries.slice(-BOOKING_LOG_ENTRIES);
  }

  /** Adds the optional blocks the scenario has; the others stay absent. */
  roadmapV2Blocks() {
    const blocks = {};
    if (this.bookings) {
      blocks.bookings = { nextSeq: this.bookings.nextSeq, entries: this.bookings.entries.map((e) => {
        const out = { seq: e.seq, gameTime: Math.round(e.gameTime), year: e.year, period: e.period, day: e.day,
          category: e.category, amount: Math.round(e.amount), count: e.count, single: e.single };
        if (e.liters !== undefined) out.liters = Math.round(e.liters);
        if (e.fillType !== undefined) out.fillType = e.fillType;
        if (e.sellPoint !== undefined) out.sellPoint = e.sellPoint;
        if (e.note) out.note = e.note;
        return out;
      }) };
    }
    if (this.finances) {
      blocks.finances = { periods: this.finances.periods.map((p) => ({ year: p.year, period: p.period,
        byType: Object.fromEntries(Object.entries(p.byType).map(([k, v]) => [k, Math.round(v)])) })) };
    }
    if (this.workforce) {
      // the shop categories ("Schulungen") only steer the assignment - the mod does not export them
      blocks.workforce = { activeJobs: this.workforce.activeJobs.map(({ categories, ...j }) => ({ ...j }))
        .sort((a, b) => a.jobId - b.jobId),
        workedGameMs: Object.fromEntries(Object.entries(this.workforce.workedGameMs).map(([k, v]) => [k, Math.round(v)])) };
    }
    if (this.husbandries) {
      blocks.husbandries = this.husbandries.map((h) => ({ ...structuredClone(h), ...this.stableExport(h.husbandryUniqueId) }))
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

  // --------------------------------------------------------------- Roadmap V3 blocks (R3-Q2)
  /** Adds the optional Roadmap V3 farm_facts blocks the scenario has; the others stay absent. */
  roadmapV3Blocks() {
    const blocks = {};
    if (this.npcFields) {
      // R3-H1: only fields without an owner (farmlands no farm owns)
      const unowned = new Map(this.farmlands.filter((f) => f.ownerFarmId === 0).map((f) => [f.farmlandId, f]));
      blocks.npcFields = this.npcFields.filter((f) => unowned.has(f.farmlandId))
        .map((f) => ({ name: String(f.farmlandId), hectares: unowned.get(f.farmlandId).hectares, ...f }))
        .sort((a, b) => a.farmlandId - b.farmlandId);
    }
    if (this.tradeStorage) {
      // R3-H2: fill level and free capacity of the own silos per fill type (an own silo accepts it)
      blocks.tradeStorage = Object.entries(this.tradeStorage)
        .map(([fillType, s]) => ({ fillType, amount: Math.round(s.amount),
          freeCapacity: Math.max(0, Math.round(s.capacity - s.amount)) }))
        .filter((e) => e.amount + e.freeCapacity > 0)
        .sort((a, b) => a.fillType.localeCompare(b.fillType));
    }
    if (typeof this.missionLimitReached === 'boolean') blocks.missionLimitReached = this.missionLimitReached;
    return blocks;
  }

  /** Control API (R3-H1): the game changes a neighbour field (e.g. harvest: {"farmlandId":3,"cut":true}). */
  setNpcField(patch) {
    const f = this.npcFields?.find((x) => x.farmlandId === patch.farmlandId);
    if (!f) throw new Error(`unknown neighbour field on farmland ${patch.farmlandId}`);
    for (const [k, v] of Object.entries(patch)) {
      if (v === null) delete f[k]; else f[k] = v;
    }
    return f;
  }

  /** Control API (R3-H5): the contract limit of the player farm is reached or not. */
  setMissionLimit(reached) {
    this.missionLimitReached = Boolean(reached);
    return { missionLimitReached: this.missionLimitReached };
  }

  /** R3-H3/H4 like the planned mod action: moves goods into / out of the own silos (also in assets.storage). */
  storageTransfer(ins) {
    const silo = this.tradeStorage?.[ins.fillType];
    if (ins.direction === 'IN') {
      if (!silo || silo.capacity - silo.amount < ins.amount) return 'NO_CAPACITY';
      silo.amount += ins.amount;
      const stock = this.storage[ins.fillType] ??= { amount: 0, capacity: silo.capacity };
      stock.amount += ins.amount;
    } else {
      if (!silo || silo.amount < ins.amount) return 'INSUFFICIENT_STOCK';
      silo.amount -= ins.amount;
      const stock = this.storage[ins.fillType];
      if (stock) stock.amount = Math.max(0, stock.amount - ins.amount);
    }
    this.log(`silo ${ins.direction === 'IN' ? '+' : '-'}${ins.amount} l ${ins.fillType}`);
    return null;
  }

  /**
   * R3-H5 like the planned mod action: a contract on the field of an NPC farmland; NOT_AVAILABLE when the farmland has
   * an owner, no exported NPC field or already a running / available contract. The client is the farmland's NPC.
   */
  missionCreate(ins) {
    const farmland = this.farmlands.find((f) => f.farmlandId === ins.farmlandId);
    const field = this.npcFields?.find((f) => f.farmlandId === ins.farmlandId);
    if (!farmland || farmland.ownerFarmId !== 0 || !field) return 'NOT_AVAILABLE';
    const fieldName = String(ins.farmlandId);
    if ([...this.missions, ...this.toolMissions].some((m) => m.field === fieldName && m.status !== 'FINISHED')) {
      return 'NOT_AVAILABLE';
    }
    const uniqueId = `mission_sim_${String(this.toolMissions.length + 1).padStart(3, '0')}`;
    this.toolMissions.push({ uniqueId, status: 'AVAILABLE', title: ins.missionType, typeName: ins.missionType,
      field: fieldName, ...(farmland.npc ? { npcIndex: farmland.npc.index, npcTitle: farmland.npc.title } : {}) });
    this.applyResult = { missionId: uniqueId };
    this.log(`contract ${ins.missionType} on field ${fieldName} created`);
    return null;
  }

  /** R3-V2 like the planned mod action: loads a shop vehicle as used machine and books the price itself. */
  vehicleSpawn(ins) {
    const item = this.storeVehicles?.find((v) => v.xmlFilename === ins.storeXmlFilename);
    if (!item) return 'UNKNOWN_STORE_ITEM';
    if (this.balance < ins.price) return 'INSUFFICIENT_FUNDS';
    const next = Math.max(0, ...this.vehicles.map((v) => Number(v.uniqueId.replace(/\D/g, '')) || 0)) + 1;
    const uniqueId = `veh_${String(next).padStart(5, '0')}`;
    // the game value of a borrowed machine (price 0) is its list price; a mod with the R31-Q1 contract exports the
    // shop category
    this.vehicles.push({ uniqueId, value: ins.price > 0 ? ins.price : item.price, damage: ins.damage, name: item.name,
      xmlFilename: ins.storeXmlFilename, ageMonths: ins.ageMonths, operatingHours: ins.operatingHours, wear: ins.wear,
      ...(this.roadmapV31 && item.categoryName ? { category: item.categoryName } : {}) });
    if (ins.price > 0) {
      this.balance -= ins.price;
      this.moneyLog.push({ id: ins.instructionId, amount: -ins.price, reason: ins.moneyReason, note: item.name });
      this.book(`RPSIM_${ins.moneyReason}`, -ins.price, { note: 'Gebrauchtmaschine' });
    }
    this.applyResult = { vehicleId: uniqueId };
    this.log(`${ins.price > 0 ? 'used vehicle' : 'borrowed vehicle'} ${item.name} delivered as ${uniqueId}`);
    return null;
  }

  /** R3-V3 like the planned mod action: removes an own vehicle (leased ones never). */
  vehicleRemove(ins) {
    if (this.leasedVehicles.some((v) => v.uniqueId === ins.vehicleId)) return 'NOT_OWN_VEHICLE';
    const i = this.vehicles.findIndex((v) => v.uniqueId === ins.vehicleId);
    if (i < 0) return 'VEHICLE_NOT_FOUND';
    // R3-V3 fallback of the mod: only a root vehicle with nothing attached is removed
    if (this.vehicles[i].attached) return 'VEHICLE_ATTACHED';
    this.vehicles.splice(i, 1);
    this.log(`vehicle ${ins.vehicleId} removed`);
    return null;
  }

  // --------------------------------------------------------------- Roadmap V3.1 (R31-Q2)
  /** Adds the optional Roadmap V3.1 farm_facts block (R31-D5) when the scenario stands for a mod with it. */
  roadmapV31Blocks() {
    if (!this.vehiclePositions) return {};
    return { vehiclePositions: this.vehiclePositions.map((p) => ({ ...p }))
      .sort((a, b) => a.uniqueId.localeCompare(b.uniqueId)) };
  }

  /**
   * R31-A3: subtypes in a stable (count > 0, sorted), the subtypes it can hold and its free places, like the mod's
   * buildHusbandries; nothing for a husbandry without a stable in the model or a scenario without the R31-Q1 contract.
   */
  stableExport(husbandryUniqueId) {
    const stable = this.roadmapV31 ? this.stables?.[husbandryUniqueId] : null;
    const animal = this.animals.find((a) => a.husbandryUniqueId === husbandryUniqueId);
    if (!stable || !animal) return {};
    const subTypes = Object.entries(stable.subTypes).filter(([, n]) => n > 0).map(([name, count]) => ({ name, count }))
      .sort((a, b) => a.name.localeCompare(b.name));
    const total = subTypes.reduce((s, x) => s + x.count, 0);
    const supportedSubTypes = Object.entries(SUB_TYPES).filter(([, type]) => type === animal.type).map(([name]) => name)
      .sort();
    return { subTypes, supportedSubTypes, freeSlots: Math.max(0, stable.capacity - total) };
  }

  /** Control API (R31-A4): the snow height of the world changes (metres, snowSystem.height). */
  setSnow(height) {
    if (!this.weather) throw new Error(`scenario ${this.scenario} exports no weather`);
    if (typeof height !== 'number' || !Number.isFinite(height) || height < 0) throw new Error('height must be >= 0');
    this.weather.snowHeight = Math.round(height * 100) / 100;
    return this.weather;
  }

  /**
   * Control API (R31-D5): the own vehicles being driven right now, [{uniqueId, x, z, farmlandId?, onCrop?}]; replaces
   * the last sample. A vehicle in the list counts as in use (VEHICLE_FUEL refuses it).
   */
  setVehiclePositions(positions) {
    if (!this.vehiclePositions) throw new Error(`scenario ${this.scenario} exports no vehiclePositions`);
    if (!Array.isArray(positions)) throw new Error('positions must be an array');
    this.vehiclePositions = positions.map((p) => ({ ...p }));
    return this.roadmapV31Blocks().vehiclePositions;
  }

  /** Control API (R31-D8): the diesel level of an own vehicle with a diesel tank changes (e.g. refuelled). */
  setFuel(uniqueId, liters) {
    const v = this.vehicles.find((x) => x.uniqueId === uniqueId);
    if (!v?.fuel) throw new Error(`no vehicle with a diesel tank: ${uniqueId}`);
    if (typeof liters !== 'number' || !Number.isFinite(liters)) throw new Error('liters (number) is required');
    v.fuel.liters = Math.min(v.fuel.capacity, Math.max(0, Math.round(liters)));
    return { uniqueId, fuel: { ...v.fuel } };
  }

  /**
   * R31-A1 like the planned mod action: end state of the work on an own field (AbstractFieldMission:finishField). The
   * values are simulated: plowing / cultivating remove the crop, liming sets the lime level and sprayType LIME, sowing
   * starts the crop, fertilising raises the spray level by one (at most 2, sprayType FERTILIZER), harvesting cuts it
   * (HARVESTED). The harvest goes into the silo by the STORAGE_TRANSFER of the batch.
   */
  fieldWork(ins) {
    const field = this.fields?.find((f) => f.farmlandId === ins.farmlandId);
    if (!field) return 'FIELD_NOT_FOUND';
    if (this.farmlands.find((f) => f.farmlandId === ins.farmlandId)?.ownerFarmId !== 1) return 'NOT_OWN_FIELD';
    const name = String(ins.farmlandId);
    if ([...this.missions, ...this.toolMissions].some((m) => m.field === name && m.status === 'RUNNING')) {
      return 'MISSION_RUNNING';
    }
    if (ins.work === 'SOW' && !FRUIT_TYPES.includes(ins.fruitType)) return 'UNKNOWN_FRUIT_TYPE';
    const clearCrop = () => {
      for (const k of CROP_KEYS) delete field[k];
      field.growthState = 0;
    };
    switch (ins.work) {
      case 'PLOW':
        clearCrop();
        Object.assign(field, { groundType: 'PLOWED', plowLevel: 1 });
        break;
      case 'CULTIVATE':
        clearCrop();
        field.groundType = 'CULTIVATED';
        break;
      case 'LIME':
        Object.assign(field, { limeLevel: 1, sprayType: 'LIME' });
        break;
      case 'FERTILIZE':
        Object.assign(field, { sprayLevel: Math.min(2, (field.sprayLevel ?? 0) + 1), sprayType: 'FERTILIZER' });
        break;
      case 'SOW':
        clearCrop();
        Object.assign(field, { fruitType: ins.fruitType, fillType: ins.fruitType, growthState: 1, withered: false,
          cut: false, groundType: 'SOWN' });
        break;
      default: // HARVEST
        if (field.fruitType) field.cut = true;
    }
    this.log(`contractor: ${ins.work} on field ${name}`);
    return null;
  }

  /**
   * R31-A3 like the planned mod action: animals of one subtype into (free places) or out of (enough animals) an own
   * husbandry; assets.animals follows (count and value per animal of the stable).
   */
  animalTransfer(ins) {
    const stable = this.stables?.[ins.husbandryUniqueId];
    const animal = this.animals.find((a) => a.husbandryUniqueId === ins.husbandryUniqueId);
    if (!stable || !animal) return 'HUSBANDRY_NOT_FOUND';
    const type = SUB_TYPES[ins.subType];
    if (!type) return 'UNKNOWN_SUB_TYPE';
    if (type !== animal.type) return 'WRONG_ANIMAL_TYPE';
    const total = Object.values(stable.subTypes).reduce((s, n) => s + n, 0);
    if (ins.direction === 'IN') {
      if (stable.capacity - total < ins.count) return 'NO_ANIMAL_SPACE';
      stable.subTypes[ins.subType] = (stable.subTypes[ins.subType] ?? 0) + ins.count;
    } else {
      if ((stable.subTypes[ins.subType] ?? 0) < ins.count) return 'NOT_ENOUGH_ANIMALS';
      stable.subTypes[ins.subType] -= ins.count;
    }
    animal.count = Object.values(stable.subTypes).reduce((s, n) => s + n, 0);
    animal.estimatedValue = Math.round(animal.count * stable.valuePerAnimal);
    this.log(`stable ${ins.husbandryUniqueId} ${ins.direction === 'IN' ? '+' : '-'}${ins.count} ${ins.subType}`);
    return null;
  }

  /**
   * R31-D8 like the planned mod action: diesel taken out of an own vehicle that nobody drives (not in vehiclePositions);
   * at most the level in the tank. result.liters = diesel actually taken.
   */
  vehicleFuel(ins) {
    if (this.leasedVehicles.some((v) => v.uniqueId === ins.vehicleId)) return 'NOT_OWN_VEHICLE';
    const v = this.vehicles.find((x) => x.uniqueId === ins.vehicleId);
    if (!v) return 'VEHICLE_NOT_FOUND';
    if ((this.vehiclePositions ?? []).some((p) => p.uniqueId === ins.vehicleId)) return 'VEHICLE_IN_USE';
    if (!v.fuel) return 'NO_DIESEL_TANK';
    const taken = Math.min(v.fuel.liters, Math.round(-ins.delta));
    v.fuel.liters -= taken;
    this.applyResult = { liters: taken };
    this.log(`diesel -${taken} l from ${ins.vehicleId}`);
    return null;
  }

  /**
   * Control API: a booking of the game (R2-B1), e.g. a vehicle purchase or leasing costs. It changes the balance and
   * lands in the journal under its FS25 money type, like Farm:changeBalance in the mod.
   */
  bookGame(moneyType, amount, vehicle = {}) {
    if (typeof moneyType !== 'string' || !moneyType || typeof amount !== 'number' || !Number.isFinite(amount)) {
      throw new Error('moneyType (string) and amount (number) are required');
    }
    // booking statement: a shop purchase with vehicleName delivers that vehicle, a sale with vehicleId removes it
    if (moneyType === 'SHOP_VEHICLE_BUY' && vehicle.vehicleName) {
      const next = Math.max(0, ...this.vehicles.map((v) => Number(v.uniqueId.replace(/\D/g, '')) || 0)) + 1;
      this.vehicles.push({ uniqueId: `veh_${String(next).padStart(5, '0')}`, value: Math.abs(amount), damage: 0,
        name: vehicle.vehicleName });
    }
    if (moneyType === 'SHOP_VEHICLE_SELL' && vehicle.vehicleId) {
      this.vehicles = this.vehicles.filter((v) => v.uniqueId !== vehicle.vehicleId);
    }
    this.balance += amount;
    this.book(moneyType, amount);
    return { balance: this.balance, finances: this.roadmapV2Blocks().finances ?? null };
  }

  /**
   * Control API (Roadmap V2 R2-D1): the player takes (change > 0) or repays (change < 0) the vanilla loan in the finance
   * menu of the game; the balance moves by the same amount, a repayment never goes below 0.
   */
  changeVanillaLoan(change) {
    if (typeof change !== 'number' || !Number.isFinite(change)) throw new Error('change (number) is required');
    const applied = Math.max(-this.vanillaLoan, change);
    this.vanillaLoan += applied;
    this.balance += applied;
    return { vanillaLoan: this.vanillaLoan, balance: this.balance };
  }

  /**
   * Control API (Roadmap V2 R2-D2): the player buys (toPlayer) or sells a farmland in the field menu of the game at its
   * price. Booked as FIELD_BUY / FIELD_SELL (the money types the game's journal shows for the farmland menu).
   */
  vanillaFarmland(farmlandId, toPlayer) {
    const f = this.farmlands.find((x) => x.farmlandId === farmlandId);
    if (!f) throw new Error(`unknown farmland ${farmlandId}`);
    if ((f.ownerFarmId === 1) === Boolean(toPlayer)) throw new Error(`farmland ${farmlandId} already has that owner`);
    f.ownerFarmId = toPlayer ? 1 : 0;
    const amount = toPlayer ? -f.price : f.price;
    this.balance += amount;
    this.book(toPlayer ? 'FIELD_BUY' : 'FIELD_SELL', amount);
    return { farmlandId, ownerFarmId: f.ownerFarmId, balance: this.balance };
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

  /** "Schulungen" like the mod: trainings a job needs for the FS25 shop categories of its vehicle (job.categories). */
  requiredTrainings(categories) {
    const byTraining = this.roster?.trainingCategories ?? {};
    const upper = new Set((categories ?? []).map((c) => String(c).toUpperCase()));
    return Object.keys(byTraining).filter((t) => byTraining[t].some((c) => upper.has(String(c).toUpperCase()))).sort();
  }

  /**
   * Free ACTIVE machine operator with the required trainings; the one with the fewest trainings first (specialists
   * stay free), ties in list order - as RPSimWorkforce.assign. R3-P2: apprentices drive too, without trainings and only
   * after the machine operators.
   */
  assignFreeOperators() {
    if (!this.roster || !this.workforce) return;
    const busy = new Set(this.workforce.activeJobs.map((j) => j.employeeId).filter((id) => id !== undefined));
    for (const j of this.workforce.activeJobs) {
      if (j.employeeId !== undefined) continue;
      const required = this.requiredTrainings(j.categories);
      const rank = { MACHINE_OPERATOR: 1, APPRENTICE: 2 };
      const trainingsOf = (e) => (e.role === 'APPRENTICE' ? [] : e.trainings ?? []);
      let free;
      for (const e of this.roster.employees) {
        const trainings = trainingsOf(e);
        if (!rank[e.role] || e.status !== 'ACTIVE' || busy.has(e.employeeId)
          || !required.every((t) => trainings.includes(t))) continue;
        if (!free || rank[e.role] < rank[free.role]
          || (rank[e.role] === rank[free.role] && trainings.length < trainingsOf(free).length)) free = e;
      }
      if (!free) continue;
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
          condition: Math.round((1 - Math.min(1, Math.max(0, v.damage))) * 100),
          // R3-V3: optional name and shop XML (getFullName / configFileName)
          ...(v.name ? { name: v.name } : {}), ...(v.xmlFilename ? { xmlFilename: v.xmlFilename } : {}),
          // R31-Q1: shop category (A4, D8) and diesel of a vehicle with a diesel tank (D8)
          ...(v.category ? { category: v.category } : {}), ...(v.fuel ? { fuel: { ...v.fuel } } : {}) })),
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
      // R31-Q1 (D4): the time of day only from a mod with the Roadmap V3.1 contract
      calendar: { ...this.buildCalendar(),
        ...(this.roadmapV31 ? { dayTimeMs: Math.floor(this.gameTime % MS_PER_GAME_DAY) } : {}) },
      missions: [...this.missions, ...this.toolMissions].map((m) => ({ ...m }))
        .sort((a, b) => a.uniqueId.localeCompare(b.uniqueId)),
      ...this.roadmapV2Blocks(),
      ...this.roadmapV3Blocks(),
      ...this.roadmapV31Blocks(),
    };
  }

  /** The "player" takes / finishes a vanilla contract in the game (TODO T-22). */
  setMission(uniqueId, status, success) {
    const m = [...this.missions, ...this.toolMissions].find((x) => x.uniqueId === uniqueId);
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
      // Roadmap V3 R3-V1: shop vehicle catalog, only in scenarios that have it
      ...(this.storeVehicles ? { storeVehicles: this.storeVehicles.map((v) => ({ ...v }))
        .sort((a, b) => a.xmlFilename.localeCompare(b.xmlFilename)) } : {}),
      // Roadmap V3.1 R31-K1: field outlines and map size, only in scenarios that have them
      ...(this.fieldShapes ? { fieldShapes: structuredClone(this.fieldShapes) } : {}),
    };
  }

  exportFarmFacts() {
    const doc = this.buildFarmFacts();
    const err = validate('farmFacts', doc);
    if (err) throw new Error(`farm_facts.json does not match schema: ${err}`);
    this.writeJson(this.paths.farmFacts, doc);
    return doc;
  }

  /**
   * Like the mod: written on start / after FARMLAND_TRANSFER / every marketContextIntervalMs (force) and otherwise only
   * when its content changed.
   */
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
        this.book(`RPSIM_${ins.reason}`, ins.amount, { note: ins.note });
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
          strictHelperLimit: ins.strictHelperLimit, trainingCategories: ins.trainingCategories ?? {} };
        this.applyRosterToJobs();
        return null;
      case 'PROMPT': // R2-F2: expired prompts are dropped without being shown
        if (this.gameTime > ins.expiresGameTime) {
          this.applyNote = 'EXPIRED';
          return null;
        }
        // like the mod: a question already queued, answered or withdrawn is not queued again (e.g. resent after a rewind)
        if (this.handledPrompts[ins.promptId] !== undefined || this.prompts.some((p) => p.promptId === ins.promptId)) {
          this.applyNote = 'DUPLICATE';
          return null;
        }
        this.prompts.push({ id: ins.instructionId, promptId: ins.promptId, title: ins.title, text: ins.text,
          yesLabel: ins.yesLabel, noLabel: ins.noLabel, expiresGameTime: ins.expiresGameTime, gameTime: this.gameTime });
        this.log(`in-game prompt: ${ins.title} - ${ins.text}`);
        return null;
      // Roadmap V3 (R3-Q2): executed like the planned mod actions (R3-H3/H4/M3, R3-H5, R3-V2, R3-V3)
      case 'STORAGE_TRANSFER':
        return this.storageTransfer(ins);
      case 'MISSION_CREATE':
        return this.missionCreate(ins);
      case 'VEHICLE_SPAWN':
        return this.vehicleSpawn(ins);
      case 'VEHICLE_REMOVE':
        return this.vehicleRemove(ins);
      // Roadmap V3.1 (R31-Q2): executed like the planned mod actions (R31-A1, R31-A3, R31-D8)
      case 'FIELD_WORK':
        return this.fieldWork(ins);
      case 'ANIMAL_TRANSFER':
        return this.animalTransfer(ins);
      case 'VEHICLE_FUEL':
        return this.vehicleFuel(ins);
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
              this.applyResult = null; // Roadmap V3 (R3-Q1): optional result for the ack
              const err = aborted ? `BATCH_ABORTED: ${aborted}` : this.applyOne(ins);
              if (err && !aborted && pending.length > 1) aborted = ins.instructionId;
              this.processed[ins.instructionId] = err
                ? { gameTime: this.gameTime, status: 'FAILED', message: err }
                : { gameTime: this.gameTime, status: 'APPLIED', ...(this.applyNote ? { message: this.applyNote } : {}),
                  ...(this.applyResult ? { result: this.applyResult } : {}) };
              if (err) res.rejected++; else res.applied++;
              if (!err && ins.type === 'FARMLAND_TRANSFER') res.marketContextDirty = true;
            }
          }
        }
        if (res.marketContextDirty) this.exportMarketContext();
        if (this.applyResponseDocument(doc)) this.writeResponses();
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
          ...(e.message ? { message: e.message } : {}), ...(e.result ? { result: { ...e.result } } : {}) })),
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

  // --------------------------------------------------------------- Roadmap V2 R2-F: questions in the game
  /** instructions.json: processed answers (ackedResponses) and questions decided in the browser (withdrawnPrompts). */
  applyResponseDocument(doc) {
    const acked = new Set(Array.isArray(doc.ackedResponses) ? doc.ackedResponses : []);
    const before = this.responses.length;
    this.responses = this.responses.filter((r) => !acked.has(r.responseId));
    for (const id of Array.isArray(doc.withdrawnPrompts) ? doc.withdrawnPrompts : []) {
      const p = this.prompts.find((x) => x.promptId === id);
      if (p) {
        this.prompts = this.prompts.filter((x) => x !== p);
        this.handledPrompts[id] = p.expiresGameTime;
        this.log(`in-game question withdrawn: ${p.title}`);
      }
    }
    return this.responses.length !== before;
  }

  /** The "player" answers a question in the game dialog; the file is written at once (like the mod). */
  answer(promptId, answer) {
    if (answer !== 'YES' && answer !== 'NO') throw new Error('answer must be YES or NO');
    const p = this.prompts.find((x) => x.promptId === promptId);
    if (!p) throw new Error(`no open question ${promptId}`);
    this.prompts = this.prompts.filter((x) => x !== p);
    this.handledPrompts[promptId] = p.expiresGameTime;
    const r = { responseId: `rsp_${promptId}`, promptId, answer, gameTime: this.gameTime };
    this.responses.push(r);
    this.writeResponses();
    this.saveSavegame();
    this.log(`in-game answer ${answer}: ${p.title}`);
    return r;
  }

  /** Questions still waiting (expired ones are dropped like in the mod). */
  openPrompts() {
    this.prompts = this.prompts.filter((p) => this.gameTime <= p.expiresGameTime);
    return this.prompts;
  }

  writeResponses() {
    const doc = { savegameId: this.savegameId, responses: this.responses };
    const err = validate('playerResponses', doc);
    if (err) throw new Error(`player_responses.json does not match schema: ${err}`);
    this.writeJson(this.paths.playerResponses, doc);
  }

  // --------------------------------------------------------------- lifecycle
  /** Equivalent of the mod's loadMap: bootstrap + immediate fresh export. */
  start() {
    this.bootstrap();
    this.exportMarketContext();
    this.marketContextRefreshedAt = this.now();
    this.exportFarmFacts();
    this.writeAck();
    this.writeResponses(); // R2-F1: the file follows the loaded savegame
  }

  /** One simulator cycle: advance game time, apply instructions, export facts. */
  tick(gameMs) {
    this.advance(gameMs);
    const res = this.processInstructions();
    this.exportFarmFacts();
    const due = this.now() - this.marketContextRefreshedAt >= this.marketContextIntervalMs;
    if (due) this.marketContextRefreshedAt = this.now();
    this.exportMarketContext(due);
    return res;
  }

  reset() {
    for (const p of [this.paths.savegame, this.paths.farmFacts, this.paths.marketContext, this.paths.ack,
      this.paths.playerResponses]) {
      rmSync(p, { force: true });
    }
  }
}
