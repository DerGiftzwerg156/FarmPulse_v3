-- Roadmap V4 against the fake engine. Section D (R4-D1) is built: the consumption counter per own field with the hooks
-- on SowingMachine / Sprayer.onEndWorkAreaProcessing, its savegame persistence and export, and the purchase prices of
-- market_context.fillTypeCosts. Still contract only (Q): the food values of the husbandries, the feed stock and the
-- food groups (R4-F1) and the sowing / harvest windows of the crops (R4-A2) - the adapter does not read them yet, so the
-- blocks are missing ("not collected"), never empty.
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

--- One own cow husbandry with a feeding trough (fill type 3 = SILAGE) and straw, and one crop of the map.
local function feedGame()
    local stable = { spec_husbandryAnimals = { animalType = { name = "cow", subTypes = {} } },
        spec_husbandryFood = { litersPerHour = 41.7, capacity = 20000, fillLevels = { [3] = 6000 } },
        spec_husbandryStraw = { inputLitersPerHour = 8.3 } }
    function stable.getUniqueId() return "hus_00001" end
    function stable.getOwnerFarmId() return 1 end
    function stable.getSellPrice() return 90000 end
    function stable.getClusters() return {} end
    function stable.getNumOfFreeAnimalSlots() return 0 end
    function stable.getTotalFood() return 6000 end
    function stable.getFoodCapacity() return 20000 end
    function stable.getFoodLitersPerHour() return 41.7 end
    function stable.getHusbandryFillLevel() return 0 end
    function stable.getHusbandryCapacity() return 0 end
    local game = helpers.fakeGame({ fillTypes = { [1] = "WHEAT", [2] = "SEEDS", [3] = "SILAGE" },
        placeables = { stable } })
    g_currentMission.animalSystem = { getSubTypeByIndex = function() return nil end }
    FruitType = { UNKNOWN = 0 }
    local wheat = { index = 3, name = "WHEAT", regrows = false, seedUsagePerSqm = 0.1, literPerSqm = 0.9,
        fillType = { title = "Weizen" } }
    g_fruitTypeManager = {
        getFruitTypeByIndex = function(_, i) return i == 3 and wheat or nil end,
        getFruitTypeNameByIndex = function(_, i) return i == 3 and "WHEAT" or nil end,
        getFillTypeNameByFruitTypeIndex = function(_, i) return i == 3 and "WHEAT" or nil end,
        getFruitTypes = function() return { wheat } end,
    }
    return game
end

function T.TestRoadmapV4:testFarmFactsCarryNoFeedValuesYet()
    feedGame()
    local doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts(), RPSimConfig.new())
    lu.assertNil(doc.feedStock)
    local h = doc.husbandries[1]
    lu.assertEquals(h.husbandryUniqueId, "hus_00001")
    for _, key in ipairs({ "foodLitersPerHour", "foodCapacity", "trough", "strawLitersPerHour", "straw",
        "feedConsumed" }) do
        lu.assertNil(h[key], key)
    end
end

function T.TestRoadmapV4:testMarketContextCarriesNoFoodGroupsOrCropWindowsYet()
    feedGame()
    local doc = RPSimMarketContext.build(RPSimGameAdapter.new():collectMarketContext({}), RPSimConfig.new())
    lu.assertNil(doc.animalFood)
    lu.assertEquals(doc.fruitTypes[1].name, "WHEAT")
    for _, key in ipairs({ "seedUsagePerSqm", "litersPerSqm", "plantablePeriods", "harvestablePeriods" }) do
        lu.assertNil(doc.fruitTypes[1][key], key)
    end
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
