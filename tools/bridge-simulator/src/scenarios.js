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
    description: 'Hohe Liquidität, großer Maschinenpark, volle Silos.',
    balance: 2400000, vanillaLoan: 0, ownedFarmlands: [1, 2, 4, 5, 7, 9],
    vehicles: [vehicle(1, 385000, 0.05), vehicle(2, 285000, 0.12), vehicle(3, 160000, 0.08), vehicle(4, 95000, 0.2)],
    placeables: [{ uniqueId: 'plc_00001', value: 220000 }, { uniqueId: 'plc_00002', value: 120000 }],
    animals: [{ husbandryUniqueId: 'hus_00001', type: 'COW', count: 60, estimatedValue: 240000 }],
    storage: { WHEAT: { amount: 180000, capacity: 200000 }, CANOLA: { amount: 60000, capacity: 80000 },
      BARLEY: { amount: 90000, capacity: 100000 } },
    drift: { income: 9000, expense: 5000 },
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

export { MAP };
