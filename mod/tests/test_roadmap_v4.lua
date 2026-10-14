-- Roadmap V4 against the fake engine. Section D (R4-D1): the consumption counter per own field with the hooks on
-- SowingMachine / Sprayer.onEndWorkAreaProcessing, its savegame persistence and export, and the purchase prices of
-- market_context.fillTypeCosts. Section F (R4-F1): the food values of the husbandries, the feed counter with the hook on
-- PlaceableHusbandryFood.removeFood, the feed stock of the own silos and the food groups of the map. Section A (R4-A2):
-- seed use, base yield and the sowing / harvest windows of the crops.
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestRoadmapV4 = {}

function T.TestRoadmapV4:setUp()
    helpers.loadGameModules()
end

function T.TestRoadmapV4:tearDown()
    g_fruitTypeManager, FruitType, MathUtil, getWorldTranslation, SowingMachine = nil, nil, nil, nil, nil
    RPSim.bridge = nil
end

--- One own cow husbandry with a feeding trough (fill type 3 = SILAGE) and straw (4 = STRAW), an own silo, a bunker
-- silo and a production point, a foreign husbandry, and one crop of the map. The animal food system knows COW with the
-- groups Futtermischung (TMR 6 or SILAGE) and Heu (HAY 5).
local function feedGame()
    local function husbandry(id, owner)
        local h = { isServer = true, spec_husbandryAnimals = { animalType = { name = "cow", subTypes = {} } },
            spec_husbandryFood = { litersPerHour = 41.7, capacity = 20000, fillLevels = { [3] = 6000, [5] = 0.4 } },
            spec_husbandryStraw = { inputLitersPerHour = 8.3, inputFillType = 4 } }
        function h.getUniqueId() return id end
        function h.getOwnerFarmId() return owner end
        function h.getSellPrice() return 90000 end
        function h.getClusters() return {} end
        function h.getNumOfFreeAnimalSlots() return 0 end
        function h.getTotalFood() return 6000 end
        function h.getFoodCapacity() return 20000 end
        function h.getFoodLitersPerHour() return 41.7 end
        function h.getHusbandryFillLevel(_, index) return index == 4 and 1234.6 or 0 end
        function h.getHusbandryCapacity() return 0 end
        return h
    end
    local function placeable(id, spec)
        local p = spec
        function p.getUniqueId() return id end
        function p.getOwnerFarmId() return 1 end
        function p.getSellPrice() return 1000 end
        return p
    end
    local silo = placeable("silo_1", { spec_silo = { storages = {
        { capacity = 200000, fillLevels = { [3] = 50000, [1] = 9000, [4] = 0 } } } } })
    local bunker = placeable("bunker_1", { spec_bunkerSilo = { bunkerSilo = { inputFillType = 3, fillLevel = 80000 } } })
    local production = placeable("prod_1", { spec_productionPoint = { productionPoint = { storage = {
        capacity = 10000, fillLevels = { [3] = 999 } } } } })
    local game = helpers.fakeGame({ fillTypes = { [1] = "WHEAT", [2] = "SEEDS", [3] = "SILAGE", [4] = "STRAW",
        [5] = "HAY", [6] = "FORAGE" }, placeables = { husbandry("hus_00001", 1), husbandry("hus_00009", 2), silo, bunker,
        production } })
    game.stable = game.placeables
    g_currentMission.animalSystem = { getSubTypeByIndex = function() return nil end,
        getTypes = function() return { { name = "COW", typeIndex = 1 }, { name = "HORSE", typeIndex = 3 } } end }
    game.foodCalls = 0
    g_currentMission.animalFoodSystem = {
        getAnimalFood = function(_, typeIndex)
            game.foodCalls = game.foodCalls + 1
            if typeIndex == 1 then
                return { groups = { { title = "Futtermischung", productionWeight = 0.4, fillTypes = { 6, 3 } },
                    { title = "Heu", productionWeight = 0.3, fillTypes = { 5 } } } }
            end
            return { groups = { { title = "Heu", productionWeight = 1, fillTypes = { 5 } } } }
        end,
        getMixturesByAnimalTypeIndex = function(_, typeIndex) return typeIndex == 1 and { 6 } or {} end,
    }
    FruitType = { UNKNOWN = 0 }
    -- winter wheat: sown in periods 6..7 (August, September), harvested in 4..5 with seasonal growth (growth mode 1);
    -- without seasons (growth mode 2) any time
    local wheat = { index = 3, name = "WHEAT", regrows = false, seedUsagePerSqm = 0.1, literPerSqm = 0.9,
        fillType = { title = "Weizen" } }
    function wheat.getIsPlantableInPeriod(_, mode, period) return mode ~= 1 or period == 6 or period == 7 end
    function wheat.getIsHarvestableInPeriod(_, mode, period) return mode ~= 1 or period == 4 or period == 5 end
    g_fruitTypeManager = {
        getFruitTypeByIndex = function(_, i) return i == 3 and wheat or nil end,
        getFruitTypeNameByIndex = function(_, i) return i == 3 and "WHEAT" or nil end,
        getFillTypeNameByFruitTypeIndex = function(_, i) return i == 3 and "WHEAT" or nil end,
        getFruitTypes = function() return { wheat } end,
    }
    g_currentMission.missionInfo.growthMode = 1
    game.wheat = wheat
    game.own = g_currentMission.placeableSystem.placeables[1]
    game.foreign = g_currentMission.placeableSystem.placeables[2]
    return game
end

function T.TestRoadmapV4:testFarmFactsCarryTheFoodValuesOfTheHusbandry()
    feedGame()
    local doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts(), RPSimConfig.new())
    local h = doc.husbandries[1]
    lu.assertEquals(h.husbandryUniqueId, "hus_00001")
    lu.assertEquals(h.foodLitersPerHour, 41.7)
    lu.assertEquals(h.foodCapacity, 20000)
    lu.assertEquals(h.trough, { { fillType = "SILAGE", amount = 6000 } }) -- HAY 0.4 l is below 1 l
    lu.assertEquals(h.strawLitersPerHour, 8.3)
    lu.assertEquals(h.straw, 1235)
    -- the adapter alone knows no counter and no stock: missing, not empty
    lu.assertNil(h.feedConsumed)
    lu.assertNil(doc.feedStock)
end

function T.TestRoadmapV4:testFoodValuesFallBackToTheSpecAndStayMissingWithoutIt()
    local game = feedGame()
    game.own.getFoodLitersPerHour = nil
    game.own.spec_husbandryFood.litersPerHour = 12.345
    game.own.spec_husbandryStraw = nil
    local h = RPSimGameAdapter.husbandryState(game.own)
    lu.assertEquals(h.foodLitersPerHour, 12.345)
    lu.assertNil(h.strawLitersPerHour)
    lu.assertNil(h.straw)
    game.own.spec_husbandryFood = nil
    h = RPSimGameAdapter.husbandryState(game.own)
    lu.assertNil(h.foodLitersPerHour)
    lu.assertNil(h.trough)
end

function T.TestRoadmapV4:testMarketContextCarriesTheFoodGroupsOnce()
    local game = feedGame()
    local adapter = RPSimGameAdapter.new()
    local doc = RPSimMarketContext.build(adapter:collectMarketContext({}), RPSimConfig.new())
    lu.assertEquals(doc.animalFood, {
        { animalType = "COW", groups = {
            { title = "Futtermischung", productionWeight = 0.4, fillTypes = { "FORAGE", "SILAGE" } },
            { title = "Heu", productionWeight = 0.3, fillTypes = { "HAY" } } }, mixtures = { "FORAGE" } },
        { animalType = "HORSE", groups = { { title = "Heu", productionWeight = 1, fillTypes = { "HAY" } } },
            mixtures = {} } })
    adapter:collectMarketContext({})
    lu.assertEquals(game.foodCalls, 2) -- read once per mission
end

-- ------------------------------------------------------------------------------------------ R4-A2

function T.TestRoadmapV4:testCropsCarrySeedUseYieldAndTheirWindows()
    feedGame()
    local doc = RPSimMarketContext.build(RPSimGameAdapter.new():collectMarketContext({}), RPSimConfig.new())
    local wheat = doc.fruitTypes[1]
    lu.assertEquals(wheat.name, "WHEAT")
    lu.assertEquals(wheat.seedUsagePerSqm, 0.1)
    lu.assertEquals(wheat.litersPerSqm, 0.9)
    lu.assertEquals(wheat.plantablePeriods, { 6, 7 })
    lu.assertEquals(wheat.harvestablePeriods, { 4, 5 })
end

function T.TestRoadmapV4:testWindowsFollowTheGrowthModeAndStayMissingWithoutIt()
    local game = feedGame()
    g_currentMission.missionInfo.growthMode = 2
    local doc = RPSimMarketContext.build(RPSimGameAdapter.new():collectMarketContext({}), RPSimConfig.new())
    lu.assertEquals(#doc.fruitTypes[1].plantablePeriods, 12)
    g_currentMission.missionInfo.growthMode = nil
    doc = RPSimMarketContext.build(RPSimGameAdapter.new():collectMarketContext({}), RPSimConfig.new())
    lu.assertNil(doc.fruitTypes[1].plantablePeriods)
    lu.assertEquals(doc.fruitTypes[1].seedUsagePerSqm, 0.1)
    -- a crop without the methods (older game version) or a failing call: no windows, the rest stays
    g_currentMission.missionInfo.growthMode = 1
    game.wheat.getIsHarvestableInPeriod = function() error("boom") end
    game.wheat.getIsPlantableInPeriod = nil
    doc = RPSimMarketContext.build(RPSimGameAdapter.new():collectMarketContext({}), RPSimConfig.new())
    lu.assertNil(doc.fruitTypes[1].plantablePeriods)
    lu.assertNil(doc.fruitTypes[1].harvestablePeriods)
    lu.assertEquals(doc.fruitTypes[1].litersPerSqm, 0.9)
end

function T.TestRoadmapV4:testNoAnimalFoodSystemNoFoodGroups()
    feedGame()
    g_currentMission.animalFoodSystem = nil
    lu.assertNil(RPSimGameAdapter.new():collectAnimalFood())
    lu.assertNil(RPSimMarketContext.build(RPSimGameAdapter.new():collectMarketContext({}), RPSimConfig.new()).animalFood)
end

local function feedBridge()
    local b = RPSimBridge.new(RPSimConfig.new(), RPSimBridgePaths.new("/ms/"), RPSimGameAdapter.new())
    RPSim.bridge = b
    return b
end

--- The game's removeFood: takes at most the trough level out and returns what it took.
local function removeFood(h, delta, index)
    local level = h.spec_husbandryFood.fillLevels[index] or 0
    local taken = math.min(delta, level)
    h.spec_husbandryFood.fillLevels[index] = level - taken
    return taken
end

function T.TestRoadmapV4:testRemoveFoodHookCountsWhatTheAnimalsOfOwnHusbandriesAte()
    local game = feedGame()
    local b = feedBridge()
    lu.assertEquals(RPSim.removeFoodHook(game.own, removeFood, 250.5, 3), 250.5)
    RPSim.removeFoodHook(game.own, removeFood, 100, 3)
    RPSim.removeFoodHook(game.own, removeFood, 5, 5) -- only 0.4 l hay in the trough
    -- a foreign husbandry, a client, nothing taken: not counted
    RPSim.removeFoodHook(game.foreign, removeFood, 100, 3)
    game.own.isServer = false
    RPSim.removeFoodHook(game.own, removeFood, 100, 3)
    game.own.isServer = true
    RPSim.removeFoodHook(game.own, removeFood, 100, 2)
    lu.assertEquals(RPSimFeedCounter.toRaw(b.state.feed), {
        { husbandryUniqueId = "hus_00001", fillType = "HAY", liters = 0.4 },
        { husbandryUniqueId = "hus_00001", fillType = "SILAGE", liters = 350.5 } })
    lu.assertEquals(game.own.spec_husbandryFood.fillLevels[3], 5549.5) -- the client call still fed
end

function T.TestRoadmapV4:testRemoveFoodHookNeverDisturbsTheFeeding()
    local game = feedGame()
    local b = feedBridge()
    b.adapter.feedEntry = function() error("boom") end
    lu.assertEquals(RPSim.removeFoodHook(game.own, function() return 7 end, 7, 3), 7)
    RPSim.bridge = nil
    lu.assertEquals(RPSim.removeFoodHook(game.own, function() return 3 end, 3, 3), 3)
end

function T.TestRoadmapV4:testFeedCounterIsSavedLoadedAndExportedWithTheHook()
    feedGame()
    local b = feedBridge()
    RPSimFeedCounter.add(b.state.feed, "hus_00001", "SILAGE", 812.6)
    RPSimFeedCounter.add(b.state.feed, "hus_00001", "HAY", 40)
    RPSimFeedCounter.add(b.state.feed, "hus_gone", "HAY", 10)
    local xml = { data = {} }
    function xml:setString(k, v) self.data[k] = v end
    function xml:setInt(k, v) self.data[k] = v end
    function xml:setFloat(k, v) self.data[k] = v end
    function xml:getString(k) local v = self.data[k]; return v ~= nil and tostring(v) or nil end
    function xml:getInt(k) return self.data[k] end
    function xml:getFloat(k) return self.data[k] end
    RPSimPersistence.save(xml, b.state)
    local loaded = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load(xml, loaded)
    lu.assertEquals(RPSimFeedCounter.toRaw(loaded.feed), RPSimFeedCounter.toRaw(b.state.feed))
    local fs = helpers.fakeFs()
    b:bootstrap()
    b.state.savegameId = "sg"
    b:exportFarmFacts()
    local doc = RPSimJson.decode(fs.files[b.paths.farmFacts])
    lu.assertNil(doc.husbandries[1].feedConsumed) -- no hook installed: missing
    b.feedCounterEnabled = true
    b:exportFarmFacts()
    doc = RPSimJson.decode(fs.files[b.paths.farmFacts])
    lu.assertEquals(doc.husbandries[1].feedConsumed,
        { { fillType = "HAY", liters = 40 }, { fillType = "SILAGE", liters = 813 } })
    -- feed stock: the silo and the bunker silo, not the production point, not the wheat; STRAW 0 is left out
    lu.assertEquals(doc.feedStock, { { fillType = "SILAGE", amount = 130000 } })
end

function T.TestRoadmapV4:testAHusbandryWithoutCounterGetsAnEmptyListWhileTheHookRuns()
    feedGame()
    local b = feedBridge()
    local fs = helpers.fakeFs()
    b:bootstrap()
    b.state.savegameId = "sg"
    b.feedCounterEnabled = true
    b:exportFarmFacts()
    lu.assertEquals(RPSimJson.decode(fs.files[b.paths.farmFacts]).husbandries[1].feedConsumed, {})
end

function T.TestRoadmapV4:testNoFeedStockWithoutFoodGroups()
    feedGame()
    g_currentMission.animalFoodSystem = nil
    local b = feedBridge()
    local fs = helpers.fakeFs()
    b:bootstrap()
    b.state.savegameId = "sg"
    b:exportFarmFacts()
    lu.assertNil(RPSimJson.decode(fs.files[b.paths.farmFacts]).feedStock)
end

function T.TestRoadmapV4:testPurchasePricesOfTheFeed()
    feedGame()
    local b = feedBridge()
    local prices = { [3] = 0.1, [4] = 0.05, [5] = 0.12, [6] = 0.2 }
    g_currentMission.economyManager = { getCostPerLiter = function(_, index, _) return prices[index] end }
    local fs = helpers.fakeFs()
    b:bootstrap()
    b.state.savegameId = "sg"
    b:exportMarketContext(true)
    local costs = RPSimJson.decode(fs.files[b.paths.marketContext]).fillTypeCosts
    local byName = {}
    for _, c in ipairs(costs) do
        byName[c.fillType] = c.costPerLiter
    end
    lu.assertEquals(byName.SILAGE, 0.1)
    lu.assertEquals(byName.STRAW, 0.05)
    lu.assertEquals(byName.HAY, 0.12)
    lu.assertEquals(byName.FORAGE, 0.2)
end

function T.TestRoadmapV4:testFeedNamesAndStockArePure()
    lu.assertEquals(RPSimFeedCounter.feedNames({ { groups = { { fillTypes = { "SILAGE", "HAY" } } },
        mixtures = { "FORAGE", "HAY" } }, { groups = "broken" } }), { "FORAGE", "HAY", "SILAGE", "STRAW" })
    lu.assertEquals(RPSimFeedCounter.feedNames(nil), { "STRAW" })
    lu.assertEquals(RPSimFeedCounter.stockFromSilos({
        { descriptor = { kind = "SILO" }, storages = { { fillLevels = { HAY = 10, WHEAT = 5 } } } },
        { descriptor = { kind = "SILO", hasHusbandrySpec = true }, storages = { { fillLevels = { HAY = 99 } } } },
        { descriptor = { kind = "PRODUCTION" }, storages = { { fillLevels = { HAY = 99 } } } },
    }, { "HAY" }), { { fillType = "HAY", amount = 10 } })
    lu.assertFalse(RPSimFeedCounter.add(RPSimFeedCounter.new(), "", "HAY", 1))
    lu.assertFalse(RPSimFeedCounter.add(RPSimFeedCounter.new(), "h", "HAY", 0 / 0))
end

-- ------------------------------------------------------------------------------------------ R4-D1

--- Own farmland 4, foreign farmland 5; fill types WHEAT 1, SEEDS 2, FERTILIZER 3, LIME 4, HERBICIDE 5. Machines stand at
-- x = farmland id. 1 m² per fruit pixel (MathUtil.areaToHa = area / 10000), wheat seed 0.1 l/m².
local function inputGame()
    local game = helpers.fakeGame({ fillTypes = { [1] = "WHEAT", [2] = "SEEDS", [3] = "FERTILIZER", [4] = "LIME",
        [5] = "HERBICIDE" }, farmlands = { { id = 4, areaInHa = 3, price = 30000, ownerFarmId = 1 },
        { id = 5, areaInHa = 2, price = 20000 } } })
    getWorldTranslation = function(node) return node.x, 0, 0 end
    g_farmlandManager.getFarmlandIdAtWorldPosition = function(_, x) return x end
    MathUtil = { areaToHa = function(area, pixelsToSqm) return area * pixelsToSqm / 10000 end }
    g_currentMission.getFruitPixelsToSqm = function() return 1 end
    g_fruitTypeManager = { getFruitTypeByIndex = function(_, i) return i == 3 and { seedUsagePerSqm = 0.1 } or nil end }
    SowingMachine = { DAMAGED_USAGE_INCREASE = 0.3 }
    return game
end

local function bridge()
    local b = RPSimBridge.new(RPSimConfig.new(), RPSimBridgePaths.new("/ms/"), RPSimGameAdapter.new())
    RPSim.bridge = b
    return b
end

--- A sowing machine at farmland x that sowed `area` m² of wheat from its own seed tank (fill type 2 = SEEDS).
local function sowerAt(x, area, opts)
    opts = opts or {}
    local tank = { getFillUnitFillType = function() return 2 end }
    local m = { rootNode = { x = x }, isServer = opts.server ~= false,
        spec_sowingMachine = { seedUsageScale = 1, seedFillType = 2,
            workAreaParameters = { lastChangedArea = area, seedsFruitType = 3, seedsVehicle = tank,
                seedsVehicleFillUnitIndex = 1 } } }
    function m.getIsAIActive() return opts.ai == true end
    function m.getVehicleDamage() return opts.damage or 0 end
    return m
end

--- A sprayer at farmland x that used `usage` litres of `fillType` this tick (from its own tank unless helperBought).
local function sprayerAt(x, fillType, usage, opts)
    opts = opts or {}
    local s = { rootNode = { x = x }, isServer = true }
    s.spec_sprayer = { workAreaParameters = { isActive = opts.active ~= false, usage = usage, sprayFillType = fillType,
        sprayVehicle = not opts.helperBought and s or nil } }
    function s.getIsAIActive() return opts.ai == true end
    return s
end

local function counted(b)
    local list = RPSimInputCounter.toRaw(b.state.inputs)
    table.sort(list, function(a, c)
        if a.farmlandId ~= c.farmlandId then return a.farmlandId < c.farmlandId end
        return a.fillType < c.fillType
    end)
    return list
end

function T.TestRoadmapV4:testInputEntryOnlyOnOwnFarmland()
    inputGame()
    local adapter = RPSimGameAdapter.new()
    lu.assertEquals(adapter:inputEntry(sowerAt(4, 1), 2), { farmlandId = 4, fillType = "SEEDS" })
    lu.assertNil(adapter:inputEntry(sowerAt(5, 1), 2)) -- foreign farmland (missions)
    lu.assertNil(adapter:inputEntry(sowerAt(0, 1), 2)) -- no farmland
    lu.assertNil(adapter:inputEntry(sowerAt(4, 1), 99)) -- unknown fill type
end

function T.TestRoadmapV4:testSowingHookCountsTheSeedOfTheSownArea()
    inputGame()
    local b = bridge()
    local calls = 0
    local function original() calls = calls + 1; return "done" end
    lu.assertEquals(RPSim.sowingEndHook(sowerAt(4, 20000), original, 16, true), "done")
    -- damage raises the use like in the game: 2000 l x (1 + 0.5 x 0.3)
    RPSim.sowingEndHook(sowerAt(4, 20000, { damage = 0.5 }), original, 16, true)
    -- a helper with "helper buys seeds" bought it: counted as helper litres of the machine's seed fill type
    g_currentMission.missionInfo.helperBuySeeds = true
    RPSim.sowingEndHook(sowerAt(4, 10000, { ai = true }), original, 16, true)
    -- nothing sown, a client, foreign farmland: not counted
    RPSim.sowingEndHook(sowerAt(4, 0), original, 16, true)
    RPSim.sowingEndHook(sowerAt(4, 10000, { server = false }), original, 16, true)
    RPSim.sowingEndHook(sowerAt(5, 10000), original, 16, true)
    lu.assertEquals(calls, 6)
    lu.assertEquals(counted(b), { { farmlandId = 4, fillType = "SEEDS", liters = 5300, helperLiters = 1000 } })
end

function T.TestRoadmapV4:testSowingHookNeverDisturbsTheWork()
    inputGame()
    bridge()
    g_fruitTypeManager.getFruitTypeByIndex = function() error("boom") end
    lu.assertEquals(RPSim.sowingEndHook(sowerAt(4, 1000), function() return 7 end, 16, true), 7)
end

function T.TestRoadmapV4:testSprayerHookCountsTheLitresOfTheTick()
    inputGame()
    local b = bridge()
    local function original() return nil end
    RPSim.sprayerEndHook(sprayerAt(4, 3, 12.5), original, 16, true)
    RPSim.sprayerEndHook(sprayerAt(4, 3, 7.5), original, 16, true)
    RPSim.sprayerEndHook(sprayerAt(4, 4, 300), original, 16, true)
    -- a helper without a tank bought the herbicide
    RPSim.sprayerEndHook(sprayerAt(4, 5, 40, { ai = true, helperBought = true }), original, 16, true)
    -- inactive, foreign farmland: not counted
    RPSim.sprayerEndHook(sprayerAt(4, 3, 10, { active = false }), original, 16, true)
    RPSim.sprayerEndHook(sprayerAt(5, 3, 10), original, 16, true)
    lu.assertEquals(counted(b), {
        { farmlandId = 4, fillType = "FERTILIZER", liters = 20, helperLiters = 0 },
        { farmlandId = 4, fillType = "HERBICIDE", liters = 40, helperLiters = 40 },
        { farmlandId = 4, fillType = "LIME", liters = 300, helperLiters = 0 } })
end

function T.TestRoadmapV4:testInputCounterIsSavedLoadedAndExportedWithTheHook()
    inputGame()
    local b = bridge()
    RPSimInputCounter.add(b.state.inputs, 4, "SEEDS", 812.6, false)
    RPSimInputCounter.add(b.state.inputs, 4, "SEEDS", 400, true)
    local xml = { data = {} }
    function xml:setString(k, v) self.data[k] = v end
    function xml:setInt(k, v) self.data[k] = v end
    function xml:setFloat(k, v) self.data[k] = v end
    function xml:getString(k) local v = self.data[k]; return v ~= nil and tostring(v) or nil end
    function xml:getInt(k) return self.data[k] end
    function xml:getFloat(k) return self.data[k] end
    RPSimPersistence.save(xml, b.state)
    local loaded = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load(xml, loaded)
    lu.assertEquals(RPSimInputCounter.toRaw(loaded.inputs),
        { { farmlandId = 4, fillType = "SEEDS", liters = 1212.6, helperLiters = 400 } })
    -- exported only while a hook is installed (older mod / no hook: the block stays missing)
    local fs = helpers.fakeFs()
    b:bootstrap()
    b.state.savegameId = "sg"
    b:exportFarmFacts()
    lu.assertNil(RPSimJson.decode(fs.files[b.paths.farmFacts]).inputs)
    b.inputCounterEnabled = true
    b:exportFarmFacts()
    lu.assertEquals(RPSimJson.decode(fs.files[b.paths.farmFacts]).inputs,
        { { farmlandId = 4, fillType = "SEEDS", liters = 1213, helperLiters = 400 } })
end

function T.TestRoadmapV4:testPurchasePricesOfTheConfiguredAndCountedFillTypes()
    inputGame()
    local b = bridge()
    b.cfg.costFillTypes = { "SEEDS", "LIME", "NOT_A_FILLTYPE" }
    RPSimInputCounter.add(b.state.inputs, 4, "HERBICIDE", 10, false)
    local prices = { [2] = 0.9, [5] = 1.2 }
    g_currentMission.economyManager = { getCostPerLiter = function(_, index, _) return prices[index] end }
    -- without a price of the economy manager the FillTypeDesc price
    rawset(g_fillTypeManager, "getFillTypeByIndex",
        function(_, index) return { pricePerLiter = index == 4 and 0.225 or nil } end)
    local fs = helpers.fakeFs()
    b:bootstrap()
    b.state.savegameId = "sg"
    b:exportMarketContext(true)
    lu.assertEquals(RPSimJson.decode(fs.files[b.paths.marketContext]).fillTypeCosts, {
        { fillType = "HERBICIDE", costPerLiter = 1.2 },
        { fillType = "LIME", costPerLiter = 0.225 },
        { fillType = "SEEDS", costPerLiter = 0.9 } })
end

return T
