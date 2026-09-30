// Scenario presets (AP-2.1). Values are raw FS-like states; prices are per 1000 l.
// Simulated FS25 NPCs of the map (the real names come from the map's NPC list in the game).
const NPCS = [
  { index: 1, name: 'NPC_HEINRICH', title: 'Heinrich Brandt' },
  { index: 2, name: 'NPC_GRETA', title: 'Greta Lindner' },
  { index: 3, name: 'NPC_OTTO', title: 'Otto Wendler' },
  { index: 4, name: 'NPC_MARTHA', title: 'Martha Siebert' },
];

const MAP = {
  mapName: 'Erlengrund',
  sellPoints: [
    { id: 'MillNorth', name: 'Mühle Nord', acceptedFillTypes: ['WHEAT', 'BARLEY', 'OAT'] },
    { id: 'MillSouth', name: 'Mühle Süd', acceptedFillTypes: ['WHEAT', 'BARLEY', 'CANOLA'] },
    { id: 'AgriTrade', name: 'Landhandel Erlengrund', acceptedFillTypes: ['WHEAT', 'BARLEY', 'CANOLA', 'CORN', 'SUNFLOWER', 'SOYBEAN'] },
    // a production point of the map as buyer (TODO T-22 delivery contracts)
    { id: 'Dairy', name: 'Molkerei Talblick', acceptedFillTypes: ['MILK'], production: true, ownedByPlayer: false },
  ],
  basePrices: { WHEAT: 215, BARLEY: 190, OAT: 260, CANOLA: 430, CORN: 205, SUNFLOWER: 390, SOYBEAN: 450, MILK: 520 },
  farmlands: Array.from({ length: 16 }, (_, i) => ({
    farmlandId: i + 1,
    hectares: Math.round((2 + ((i * 7) % 11) * 0.9) * 100) / 100,
  })).map((f) => ({ ...f, price: Math.round(f.hectares * 12000),
    // FS25 NPC of the farmland (TODO T-21, Farmland.npcIndex -> g_npcManager:getNPCByIndex)
    npc: NPCS[f.farmlandId % NPCS.length],
    // farmland 16 = village area: hidden in the vanilla farmland menu, never traded (TODO T-11)
    ...(f.farmlandId === 16 ? { showOnFarmlandsScreen: false } : {}) })),
};

const vehicle = (n, value, damage) => ({ uniqueId: `veh_${String(n).padStart(5, '0')}`, value, damage });

export const SCENARIOS = {
  'leerer-hof': {
    description: 'Start ohne Vermögen: kein Geld, keine Maschinen, keine Flächen.',
    balance: 0, vanillaLoan: 0, ownedFarmlands: [], vehicles: [], placeables: [], animals: [], storage: {},
    drift: { income: 0, expense: 0 },
  },
  'verschuldeter-hof': {
    description: 'Aktiver Vanilla-Kredit, wenig Eigenkapital, knappe Liquidität.',
    balance: 8000, vanillaLoan: 320000, ownedFarmlands: [3],
    vehicles: [vehicle(1, 42000, 0.55), vehicle(2, 18000, 0.7)],
    placeables: [{ uniqueId: 'plc_00001', value: 25000 }],
    animals: [], storage: { WHEAT: { amount: 3000, capacity: 20000 } },
    drift: { income: 900, expense: 1100 },
  },
  'wohlhabender-hof': {
    description: 'Hohe Liquidität, großer Maschinenpark, volle Silos; mit Buchungsjournal, Stall-, Feld- und Wetterdaten '
      + '(E2E-Tests und Screenshots des Hof-Tablets).',
    balance: 2400000, vanillaLoan: 0, ownedFarmlands: [1, 2, 4, 5, 7, 9],
    vehicles: [vehicle(1, 385000, 0.05), vehicle(2, 285000, 0.12), vehicle(3, 160000, 0.08), vehicle(4, 95000, 0.2)],
    placeables: [{ uniqueId: 'plc_00001', value: 220000 }, { uniqueId: 'plc_00002', value: 120000 }],
    animals: [{ husbandryUniqueId: 'hus_00001', type: 'COW', count: 60, estimatedValue: 240000 }],
    storage: { WHEAT: { amount: 180000, capacity: 200000 }, CANOLA: { amount: 60000, capacity: 80000 },
      BARLEY: { amount: 90000, capacity: 100000 } },
    drift: { income: 9000, expense: 5000 },
    // Hof-Tablet: the Roadmap V2 blocks of a current mod (the stable is healthy, so no vet emergency interferes)
    journal: { income: 'SOLD_PRODUCTS', expense: 'PURCHASE_FUEL' },
    husbandries: [
      { husbandryUniqueId: 'hus_00001', health: 86, productivity: 0.78, food: 0.64,
        conditions: [{ title: 'Wasser', ratio: 0.9 }, { title: 'Stroh', ratio: 0.55 }, { title: 'Milch', ratio: 0.4 }] },
    ],
    fields: [
      { farmlandId: 1, fruitType: 'WHEAT', growthState: 8, minHarvestingGrowthState: 8, maxHarvestingGrowthState: 8,
        withered: false, cut: false, fillType: 'WHEAT', litersPerSqm: 0.95,
        weedState: 0, stoneLevel: 0, sprayLevel: 2, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
      { farmlandId: 2, fruitType: 'CANOLA', growthState: 4, minHarvestingGrowthState: 7, maxHarvestingGrowthState: 7,
        withered: false, cut: false, fillType: 'CANOLA', litersPerSqm: 0.45,
        weedState: 1, stoneLevel: 0, sprayLevel: 1, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
      { farmlandId: 4, fruitType: 'BARLEY', growthState: 5, minHarvestingGrowthState: 7, maxHarvestingGrowthState: 7,
        withered: false, cut: false, fillType: 'BARLEY', litersPerSqm: 0.9,
        weedState: 0, stoneLevel: 1, sprayLevel: 1, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
      { farmlandId: 5, growthState: 0, weedState: 1, stoneLevel: 0, sprayLevel: 0, limeLevel: 0, plowLevel: 0,
        groundType: 'CULTIVATED' },
      { farmlandId: 7, fruitType: 'MAIZE', growthState: 3, minHarvestingGrowthState: 7, maxHarvestingGrowthState: 7,
        withered: false, cut: false, fillType: 'MAIZE', litersPerSqm: 1.1,
        weedState: 0, stoneLevel: 0, sprayLevel: 1, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
      { farmlandId: 9, fruitType: 'POTATO', growthState: 6, minHarvestingGrowthState: 6, maxHarvestingGrowthState: 6,
        withered: false, cut: false, fillType: 'POTATO', litersPerSqm: 4,
        weedState: 0, stoneLevel: 0, sprayLevel: 1, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
    ],
    fieldRules: { plowingRequired: true, limeRequired: true, weedsEnabled: true, stonesEnabled: true },
    weather: { raining: false, rainFallScale: 0, groundWetness: 0.25, temperature: 18 },
  },
  'leasing-hof': {
    description: 'Maschinenpark teils geleast: geleaste Fahrzeuge zählen nicht als Vermögen (TODO T-04).',
    balance: 150000, vanillaLoan: 0, ownedFarmlands: [2, 3],
    vehicles: [vehicle(1, 120000, 0.1)],
    leasedVehicles: [{ uniqueId: 'veh_00101' }, { uniqueId: 'veh_00102' }],
    placeables: [{ uniqueId: 'plc_00001', value: 60000 }],
    animals: [], storage: { WHEAT: { amount: 20000, capacity: 50000 } },
    drift: { income: 2000, expense: 1800 },
  },
  'knappe-kasse': {
    description: 'Fast leeres Konto: Abbuchungen scheitern mit INSUFFICIENT_FUNDS (TODO T-03).',
    balance: 500, vanillaLoan: 0, ownedFarmlands: [4],
    vehicles: [vehicle(1, 30000, 0.4)],
    placeables: [], animals: [], storage: {},
    drift: { income: 0, expense: 0 },
  },
  'konflikt-mods': {
    description: 'Wie wohlhabender-hof, aber mit FS25_UsedPlus und FS25_MarketDynamics aktiv (Warnung, TODO T-09).',
    balance: 500000, vanillaLoan: 0, ownedFarmlands: [1, 2],
    vehicles: [vehicle(1, 200000, 0.1)], placeables: [], animals: [], storage: { WHEAT: { amount: 50000, capacity: 80000 } },
    drift: { income: 4000, expense: 3000 },
    detectedMods: ['FS25_UsedPlus', 'FS25_MarketDynamics'],
  },
  'voller-silobestand': {
    description: 'Fokus Warenbestand: mittlere Liquidität, sehr volle Silos mit mehreren Fruchtarten.',
    balance: 60000, vanillaLoan: 0, ownedFarmlands: [2, 6],
    vehicles: [vehicle(1, 120000, 0.3)],
    placeables: [{ uniqueId: 'plc_00001', value: 90000 }],
    animals: [],
    storage: { WHEAT: { amount: 250000, capacity: 250000 }, CORN: { amount: 120000, capacity: 150000 },
      SUNFLOWER: { amount: 40000, capacity: 50000 }, SOYBEAN: { amount: 30000, capacity: 50000 } },
    drift: { income: 2500, expense: 2200 },
  },
};

// Roadmap V2 (R2-Q2): scenarios with the optional farm_facts blocks finances, workforce, husbandries, fields and
// weather (plus wohlhabender-hof above). All other scenarios leave these blocks out and stand for a mod without them
// ("not present").
// `journal` names the FS25 money types (MoneyType.*) the daily income/expense drift is booked under (R2-B1).
// All numbers are simulated: growth states, health etc. are plausible examples, not values read from FS25.
Object.assign(SCENARIOS, {
  'helfer-hof': {
    description: 'Maschinenführer fahren FS25-Helfer: laufende Helfer-Jobs, Arbeitszeit je Mitarbeiter, Buchungsjournal (R2-A).',
    balance: 180000, vanillaLoan: 0, ownedFarmlands: [1, 2, 5],
    vehicles: [vehicle(1, 240000, 0.15), vehicle(2, 310000, 0.1), vehicle(3, 85000, 0.3)],
    placeables: [{ uniqueId: 'plc_00001', value: 90000 }],
    animals: [], storage: { WHEAT: { amount: 40000, capacity: 100000 } },
    drift: { income: 3500, expense: 2600 },
    journal: { income: 'SOLD_PRODUCTS', expense: 'AI' },
    // jobId 2 runs without an employee: a vanilla helper paid with the game's wage (R2-D3)
    workforce: {
      activeJobs: [{ jobId: 1, employeeId: 1, title: 'Fendt 942 Vario' }, { jobId: 2, title: 'CLAAS LEXION 8900' }],
      workedGameMs: { 1: 0, 2: 0 },
    },
    weather: { raining: false, rainFallScale: 0, groundWetness: 0.1, temperature: 16 },
  },
  'tierhof-krank': {
    description: 'Tierhof mit schlechten Stallwerten: niedrige Gesundheit, wenig Futter und Wasser (R2-A7).',
    balance: 90000, vanillaLoan: 0, ownedFarmlands: [3],
    vehicles: [vehicle(1, 70000, 0.35)],
    placeables: [{ uniqueId: 'plc_00001', value: 150000 }],
    animals: [{ husbandryUniqueId: 'hus_00001', type: 'COW', count: 45, estimatedValue: 180000 },
      { husbandryUniqueId: 'hus_00002', type: 'PIG', count: 120, estimatedValue: 36000 }],
    storage: {},
    drift: { income: 2200, expense: 2500 },
    journal: { income: 'SOLD_PRODUCTS', expense: 'BOUGHT_MATERIALS' },
    // productivity is missing for pigs (and horses) like in PlaceableHusbandryAnimals:getConditionInfos
    husbandries: [
      { husbandryUniqueId: 'hus_00001', health: 38, productivity: 0.42, food: 0.08,
        conditions: [{ title: 'Wasser', ratio: 0.05 }, { title: 'Stroh', ratio: 0.2 }, { title: 'Gülle', ratio: 0.95 },
          { title: 'Milch', ratio: 0.6 }] },
      { husbandryUniqueId: 'hus_00002', health: 55, food: 0.3, conditions: [{ title: 'Wasser', ratio: 0.4 }] },
    ],
    weather: { raining: false, rainFallScale: 0, groundWetness: 0.2, temperature: 9 },
  },
  'ernte-herbst': {
    description: 'Herbst (September): erntereifer Mais, wachsende Kartoffeln, verdorrter Weizen, ein brachliegendes Feld, Regen (R2-C).',
    balance: 120000, vanillaLoan: 0, ownedFarmlands: [2, 4, 6, 7], startPeriod: 7,
    vehicles: [vehicle(1, 260000, 0.2), vehicle(2, 180000, 0.25)],
    placeables: [{ uniqueId: 'plc_00001', value: 80000 }],
    animals: [], storage: { WHEAT: { amount: 60000, capacity: 150000 } },
    drift: { income: 4000, expense: 3000 },
    journal: { income: 'HARVEST_INCOME', expense: 'PURCHASE_FUEL' },
    // only fields on farmlands the player owns are exported (R2-C1)
    fields: [
      { farmlandId: 2, fruitType: 'MAIZE', growthState: 7, minHarvestingGrowthState: 7, maxHarvestingGrowthState: 7,
        withered: false, cut: false, fillType: 'MAIZE', litersPerSqm: 1.1,
        weedState: 0, stoneLevel: 0, sprayLevel: 2, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
      { farmlandId: 4, fruitType: 'POTATO', growthState: 4, minHarvestingGrowthState: 6, maxHarvestingGrowthState: 6,
        withered: false, cut: false, fillType: 'POTATO', litersPerSqm: 4,
        weedState: 1, stoneLevel: 1, sprayLevel: 1, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
      { farmlandId: 6, fruitType: 'WHEAT', growthState: 10, minHarvestingGrowthState: 8, maxHarvestingGrowthState: 8,
        withered: true, cut: false, fillType: 'WHEAT', litersPerSqm: 0.95,
        weedState: 2, stoneLevel: 0, sprayLevel: 0, limeLevel: 0, plowLevel: 0, groundType: 'SOWN' },
      { farmlandId: 7, growthState: 0, weedState: 3, stoneLevel: 3, sprayLevel: 0, limeLevel: 0, plowLevel: 0,
        groundType: 'CULTIVATED' },
    ],
    // R2-C: game settings of the soil mechanics (all on, like a new career savegame with every option enabled)
    fieldRules: { plowingRequired: true, limeRequired: true, weedsEnabled: true, stonesEnabled: true },
    weather: { raining: true, rainFallScale: 0.6, groundWetness: 0.7, temperature: 12 },
  },
});

// Roadmap V3 (R3-Q2): nachbarhandel exports the optional blocks npcFields and tradeStorage and the shop vehicle catalog
// storeVehicles; duerre-sommer is a dry summer (no rain, growing crops) and exports only the Roadmap V2 blocks fields,
// fieldRules and weather. All other scenarios leave the Roadmap V3 blocks out and stand for a mod without them.
// All numbers are simulated examples, not values read from FS25.
Object.assign(SCENARIOS, {
  nachbarhandel: {
    description: 'Handel mit den Nachbarn: Nachbarfelder in verschiedenen Phasen, eigene Silos mit freier Kapazität, '
      + 'Fahrzeug-Katalog des Shops (R3-H, R3-V).',
    balance: 150000, vanillaLoan: 0, ownedFarmlands: [1, 2],
    vehicles: [vehicle(1, 180000, 0.15), vehicle(2, 60000, 0.3)],
    placeables: [{ uniqueId: 'plc_00001', value: 110000 }],
    animals: [],
    // assets.storage = everything the farm stores; tradeStorage = only the own silos and silo extensions
    storage: { WHEAT: { amount: 40000, capacity: 100000 }, BARLEY: { amount: 12000, capacity: 50000 } },
    tradeStorage: { WHEAT: { amount: 40000, capacity: 100000 }, BARLEY: { amount: 12000, capacity: 50000 },
      // an own silo accepts straw but holds none
      STRAW: { amount: 0, capacity: 25000 } },
    drift: { income: 2500, expense: 2000 },
    // fields of farmlands without an owner (the game's NPCs farm them); exported only while no farm owns the farmland
    npcFields: [
      // harvested barley, not plowed yet (candidate for a plowing contract)
      { farmlandId: 3, fruitType: 'BARLEY', growthState: 10, minHarvestingGrowthState: 9, maxHarvestingGrowthState: 9,
        withered: false, cut: true, fillType: 'BARLEY', litersPerSqm: 0.97,
        weedState: 0, stoneLevel: 1, sprayLevel: 1, limeLevel: 1, plowLevel: 0, groundType: 'HARVEST_READY' },
      // growing wheat
      { farmlandId: 5, fruitType: 'WHEAT', growthState: 4, minHarvestingGrowthState: 8, maxHarvestingGrowthState: 8,
        withered: false, cut: false, fillType: 'WHEAT', litersPerSqm: 0.95,
        weedState: 1, stoneLevel: 0, sprayLevel: 1, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
      // empty field with many stones (candidate for a stone picking contract)
      { farmlandId: 6, growthState: 0, weedState: 0, stoneLevel: 3, sprayLevel: 0, limeLevel: 1, plowLevel: 1,
        groundType: 'CULTIVATED' },
      // harvestable canola
      { farmlandId: 8, fruitType: 'CANOLA', growthState: 7, minHarvestingGrowthState: 7, maxHarvestingGrowthState: 7,
        withered: false, cut: false, fillType: 'CANOLA', litersPerSqm: 0.45,
        weedState: 0, stoneLevel: 0, sprayLevel: 2, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
    ],
    // shop vehicle catalog (motorized is missing when the mod could not read the specs, fallback of R3-V1)
    storeVehicles: [
      { xmlFilename: 'data/vehicles/fendt/vario700/vario700.xml', name: 'Fendt 700 Vario', price: 245000,
        lifetime: 600, categoryName: 'TRACTORSL', isMod: false, motorized: true },
      { xmlFilename: 'data/vehicles/deutzFahr/series5/series5.xml', name: 'Deutz-Fahr Serie 5', price: 98000,
        lifetime: 600, categoryName: 'TRACTORSM', isMod: false, motorized: true },
      { xmlFilename: 'data/vehicles/claas/lexion8000/lexion8000.xml', name: 'CLAAS LEXION 8900', price: 780000,
        lifetime: 600, categoryName: 'HARVESTERS', isMod: false, motorized: true },
      { xmlFilename: 'data/vehicles/amazone/catros/catros.xml', name: 'Amazone Catros', price: 32000,
        lifetime: 600, categoryName: 'CULTIVATORS', isMod: false, motorized: false },
      { xmlFilename: 'data/vehicles/krampe/bandit750/bandit750.xml', name: 'Krampe Bandit 750', price: 41000,
        lifetime: 600, categoryName: 'TRAILERS', isMod: false },
    ],
  },
  'duerre-sommer': {
    description: 'Trockener Sommer (Juni): kein Regen, trockener Boden, eigene Kulturen noch im Wachstum (R3-W).',
    balance: 110000, vanillaLoan: 0, ownedFarmlands: [2, 4, 7], startPeriod: 4,
    vehicles: [vehicle(1, 210000, 0.2)],
    placeables: [{ uniqueId: 'plc_00001', value: 70000 }],
    animals: [], storage: { WHEAT: { amount: 15000, capacity: 80000 } },
    drift: { income: 2000, expense: 2400 },
    fields: [
      { farmlandId: 2, fruitType: 'WHEAT', growthState: 5, minHarvestingGrowthState: 8, maxHarvestingGrowthState: 8,
        withered: false, cut: false, fillType: 'WHEAT', litersPerSqm: 0.95,
        weedState: 0, stoneLevel: 0, sprayLevel: 1, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
      { farmlandId: 4, fruitType: 'MAIZE', growthState: 3, minHarvestingGrowthState: 7, maxHarvestingGrowthState: 7,
        withered: false, cut: false, fillType: 'MAIZE', litersPerSqm: 1.1,
        weedState: 1, stoneLevel: 0, sprayLevel: 1, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
      { farmlandId: 7, fruitType: 'SUNFLOWER', growthState: 2, minHarvestingGrowthState: 7, maxHarvestingGrowthState: 7,
        withered: false, cut: false, fillType: 'SUNFLOWER', litersPerSqm: 0.4,
        weedState: 0, stoneLevel: 0, sprayLevel: 1, limeLevel: 1, plowLevel: 1, groundType: 'SOWN' },
    ],
    fieldRules: { plowingRequired: true, limeRequired: true, weedsEnabled: true, stonesEnabled: true },
    // no rain during the whole run: the weather of a scenario only changes through the control API
    weather: { raining: false, rainFallScale: 0, groundWetness: 0.05, temperature: 29 },
  },
});

// Simulated vanilla contracts (TODO T-22); the mod reads them from g_missionManager:getMissions().
export const MISSIONS = [
  { uniqueId: 'mission_001', title: 'Ernte', typeName: 'harvestMission', field: '7', npcIndex: 3, npcTitle: 'Otto Wendler',
    reward: 5200, status: 'AVAILABLE' },
  { uniqueId: 'mission_002', title: 'Mähen', typeName: 'mowMission', field: '9', npcIndex: 2, npcTitle: 'Greta Lindner',
    reward: 1800, status: 'AVAILABLE' },
];

export { MAP };
