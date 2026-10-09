-- Roadmap V3.1 section A against the fake engine: FIELD_WORK (R31-A1), ANIMAL_TRANSFER and the subtype export of the
-- husbandries (R31-A3), snow height and vehicle category (R31-A4), borrowed machines with price 0 (R31-A2); section D:
-- time of day (R31-D4), positions of driven vehicles (R31-D5), diesel and VEHICLE_FUEL (R31-D8); section K: the field
-- outlines (R31-K1).
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestRoadmapV31 = {}

local SG = "map_erlengrund_1_20260101"

function T.TestRoadmapV31:setUp()
    helpers.loadGameModules()
end

function T.TestRoadmapV31:tearDown()
    g_fieldManager, g_fruitTypeManager, FruitType, FieldGroundType, FieldSprayType = nil, nil, nil, nil, nil
    g_storeManager = nil
    FillType, ToolType, FieldState, getWorldTranslation = nil, nil, nil, nil
end

-- ------------------------------------------------------------------------------------------ R31-A1 FIELD_WORK

--- One own field (farmland 4) with wheat; the update tasks the adapter hands to the field manager are recorded.
local function fieldGame(opts)
    opts = opts or {}
    local game = helpers.fakeGame({ farmlands = { { id = 4, areaInHa = 3, price = 30000, ownerFarmId = 1 },
        { id = 5, areaInHa = 2, price = 20000 } } })
    FruitType = { UNKNOWN = 0 }
    FieldGroundType = { NONE = 0, PLOWED = 1, CULTIVATED = 2, SOWN = 5 }
    FieldSprayType = { NONE = 0, LIQUID_MANURE = 1, MANURE = 2, LIME = 3, FERTILIZER = 4 }
    local wheat = { index = 3, cutState = 9 }
    local barley = { index = 4, cutState = 8 }
    g_fruitTypeManager = {
        getFruitTypeByIndex = function(_, i) return ({ [3] = wheat, [4] = barley })[i] end,
        getFruitTypeByName = function(_, name) return ({ WHEAT = wheat, BARLEY = barley })[name] end,
    }
    game.tasks = {}
    local function newField(farmlandId, state)
        local field = { farmland = { id = farmlandId }, currentMission = opts.mission }
        function field.getFieldState() return state end
        return field
    end
    local function newState()
        local state = { isValid = true, fruitTypeIndex = 3, growthState = 8, groundType = 5, sprayType = 0,
            sprayLevel = 1, limeLevel = 0, plowLevel = 0 }
        function state.createFieldUpdateTask(s)
            local task = { values = { fruitTypeIndex = s.fruitTypeIndex, growthState = s.growthState,
                groundType = s.groundType, sprayType = s.sprayType, sprayLevel = s.sprayLevel, limeLevel = s.limeLevel,
                plowLevel = s.plowLevel },
                calls = {} }
            function task:setFruit(i, gs) self.calls[#self.calls + 1] = { "setFruit", i, gs } end
            function task:setGroundType(g) self.calls[#self.calls + 1] = { "setGroundType", g } end
            function task:setSprayType(t) self.calls[#self.calls + 1] = { "setSprayType", t } end
            function task:setSprayLevel(l) self.calls[#self.calls + 1] = { "setSprayLevel", l } end
            function task:setLimeLevel(l) self.calls[#self.calls + 1] = { "setLimeLevel", l } end
            function task:setPlowLevel(l) self.calls[#self.calls + 1] = { "setPlowLevel", l } end
            function task:setField(f) self.field = f end
            return task
        end
        return state
    end
    game.state = newState()
    game.field = newField(4, game.state)
    g_fieldManager = { plowLevelMaxValue = 1, limeLevelMaxValue = 2, sprayLevelMaxValue = 2,
        fields = { game.field, newField(5, newState()) },
        addFieldUpdateTask = function(_, task) game.tasks[#game.tasks + 1] = task end }
    return game
end

local function work(w, extra)
    local ins = { instructionId = "fw", type = "FIELD_WORK", farmlandId = 4, work = w }
    for k, v in pairs(extra or {}) do ins[k] = v end
    return ins
end

function T.TestRoadmapV31:testPlowSetsTheEndStateLikeAFinishedPlowContract()
    local game = fieldGame()
    lu.assertTrue(RPSimGameAdapter.new():fieldWork(work("PLOW")))
    local task = game.tasks[1]
    lu.assertIs(task.field, game.field)
    lu.assertEquals(task.values.fruitTypeIndex, FruitType.UNKNOWN)
    lu.assertEquals(task.values.groundType, FieldGroundType.PLOWED)
    lu.assertEquals(task.values.plowLevel, 1)
    -- the fallback setters carry the same values (manual test plan 21.1)
    lu.assertEquals(task.calls, { { "setFruit", 0, 0 }, { "setGroundType", 1 }, { "setPlowLevel", 1 } })
end

function T.TestRoadmapV31:testCultivateLimeSowAndHarvest()
    local game = fieldGame()
    local adapter = RPSimGameAdapter.new()
    lu.assertTrue(adapter:fieldWork(work("CULTIVATE")))
    lu.assertEquals(game.tasks[1].values.groundType, FieldGroundType.CULTIVATED)
    lu.assertEquals(game.tasks[1].values.fruitTypeIndex, 0)
    lu.assertTrue(adapter:fieldWork(work("LIME")))
    lu.assertEquals({ game.tasks[2].values.limeLevel, game.tasks[2].values.sprayType }, { 2, FieldSprayType.LIME })
    lu.assertTrue(adapter:fieldWork(work("SOW", { fruitType = "BARLEY" })))
    lu.assertEquals({ game.tasks[3].values.fruitTypeIndex, game.tasks[3].values.growthState,
        game.tasks[3].values.groundType }, { 4, 1, FieldGroundType.SOWN })
    lu.assertTrue(adapter:fieldWork(work("HARVEST")))
    lu.assertEquals({ game.tasks[4].values.fruitTypeIndex, game.tasks[4].values.growthState }, { 4, 8 },
        "barley on its cut state")
end

function T.TestRoadmapV31:testCultivateSowAndFertiliseOnOneDayBuildOnEachOther()
    -- owner decisions 2026-10-06: several works of one order run one after the other on the same field state
    local game = fieldGame()
    local adapter = RPSimGameAdapter.new()
    lu.assertTrue(adapter:fieldWork(work("CULTIVATE")))
    lu.assertTrue(adapter:fieldWork(work("SOW", { fruitType = "WHEAT" })))
    lu.assertTrue(adapter:fieldWork(work("FERTILIZE")))
    local last = game.tasks[3].values
    lu.assertEquals({ last.fruitTypeIndex, last.growthState, last.groundType }, { 3, 1, FieldGroundType.SOWN },
        "the fertilising keeps the sown crop")
    lu.assertEquals({ last.sprayLevel, last.sprayType }, { 2, FieldSprayType.FERTILIZER })
    lu.assertEquals(game.tasks[3].calls, { { "setSprayLevel", 2 }, { "setSprayType", FieldSprayType.FERTILIZER } })
    -- at most sprayLevelMaxValue
    lu.assertTrue(adapter:fieldWork(work("FERTILIZE")))
    lu.assertEquals(game.tasks[4].values.sprayLevel, 2)
end

function T.TestRoadmapV31:testFieldWorkRefusals()
    fieldGame()
    local adapter = RPSimGameAdapter.new()
    lu.assertEquals(select(2, adapter:fieldWork(work("PLOW", { farmlandId = 9 }))), "FIELD_NOT_FOUND")
    lu.assertEquals(select(2, adapter:fieldWork(work("PLOW", { farmlandId = 5 }))), "NOT_OWN_FIELD")
    lu.assertEquals(select(2, adapter:fieldWork(work("SOW", { fruitType = "DRAGONFRUIT" }))), "UNKNOWN_FRUIT_TYPE")
    local game = fieldGame({ mission = {} })
    lu.assertEquals(select(2, RPSimGameAdapter.new():fieldWork(work("PLOW"))), "MISSION_RUNNING")
    lu.assertEquals(#game.tasks, 0)
end

function T.TestRoadmapV31:testContractorBatchRunsThroughTheBridge()
    local game = fieldGame()
    local fs = helpers.fakeFs()
    local paths = RPSimBridgePaths.new("/ms/")
    local bridge = RPSimBridge.new(RPSimConfig.new(), paths, RPSimGameAdapter.new())
    bridge.state.savegameId = SG
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        work("PLOW", { instructionId = "fw1", batchId = "b1" }),
        { instructionId = "m1", batchId = "b1", type = "MONEY_TRANSACTION", amount = -330, reason = "CONTRACTOR_FEE" } } })
    bridge:pollInstructions()
    lu.assertEquals(#game.tasks, 1)
    lu.assertEquals(game.farm.money, 100000 - 330)
end

-- ------------------------------------------------------------------------------------------ R31-A3 animals

--- One own cow husbandry with two clusters (Holstein 10 + 5, Angus 4), 6 free places.
local function animalGame()
    local subTypes = { [1] = { name = "COW_HOLSTEIN", subTypeIndex = 1, typeIndex = 1 },
        [2] = { name = "COW_ANGUS", subTypeIndex = 2, typeIndex = 1 },
        [7] = { name = "PIG_LANDRACE", subTypeIndex = 7, typeIndex = 2 } }
    local function cluster(sub, n)
        local c = { subTypeIndex = sub, numAnimals = n, health = 80 }
        function c:getSubTypeIndex() return self.subTypeIndex end
        function c:getNumAnimals() return self.numAnimals end
        function c:getSellPrice() return 1500 end
        function c:changeNumAnimals(delta)
            local taken = math.min(self.numAnimals, -delta)
            self.numAnimals = self.numAnimals - taken
            return delta + taken
        end
        return c
    end
    local stable = { spec_husbandryAnimals = { animalType = { name = "cow", subTypes = { 1, 2 } } }, maxAnimals = 25,
        clusters = { cluster(1, 10), cluster(2, 4), cluster(1, 5) }, added = {} }
    function stable.getUniqueId() return "hus_00001" end
    function stable.getOwnerFarmId() return 1 end
    function stable.getSellPrice() return 90000 end
    function stable:getClusters() return self.clusters end
    function stable:getNumOfFreeAnimalSlots()
        local n = 0
        for _, c in ipairs(self.clusters) do n = n + c.numAnimals end
        return math.max(self.maxAnimals - n, 0)
    end
    function stable.getSupportsAnimalSubType(_, index) return subTypes[index].typeIndex == 1 end
    function stable:addAnimals(index, n, age)
        self.added[#self.added + 1] = { index, n, age }
        self.clusters[#self.clusters + 1] = cluster(index, n)
    end
    local game = helpers.fakeGame({ placeables = { stable } })
    g_currentMission.animalSystem = {
        getSubTypeByIndex = function(_, i) return subTypes[i] end,
        getSubTypeByName = function(_, name)
            for _, s in pairs(subTypes) do
                if s.name == name then return s end
            end
            return nil
        end,
    }
    game.stable = stable
    return game
end

local function transfer(extra)
    local ins = { instructionId = "at", type = "ANIMAL_TRANSFER", husbandryUniqueId = "hus_00001",
        subType = "COW_ANGUS", count = 3, age = 12, direction = "IN" }
    for k, v in pairs(extra or {}) do ins[k] = v end
    return ins
end

function T.TestRoadmapV31:testHusbandriesExportSubtypesSupportedSubtypesAndFreePlaces()
    animalGame()
    local raw = RPSimGameAdapter.new():collectFarmFacts()
    local doc = RPSimFarmFacts.build(raw)
    local h = doc.husbandries[1]
    lu.assertEquals(h.subTypes, { { name = "COW_ANGUS", count = 4 }, { name = "COW_HOLSTEIN", count = 15 } })
    lu.assertEquals(h.supportedSubTypes, { "COW_ANGUS", "COW_HOLSTEIN" })
    lu.assertEquals(h.freeSlots, 6)
    lu.assertEquals(doc.assets.animals[1].type, "COW")
end

function T.TestRoadmapV31:testAnimalsInNeedFreePlacesAndTheRightType()
    local game = animalGame()
    local adapter = RPSimGameAdapter.new()
    lu.assertTrue(adapter:animalTransfer(transfer()))
    lu.assertEquals(game.stable.added, { { 2, 3, 12 } })
    lu.assertEquals(select(2, adapter:animalTransfer(transfer({ count = 4 }))), "NO_ANIMAL_SPACE")
    lu.assertEquals(select(2, adapter:animalTransfer(transfer({ subType = "PIG_LANDRACE" }))), "WRONG_ANIMAL_TYPE")
    lu.assertEquals(select(2, adapter:animalTransfer(transfer({ subType = "UNICORN" }))), "UNKNOWN_SUB_TYPE")
    lu.assertEquals(select(2, adapter:animalTransfer(transfer({ husbandryUniqueId = "hus_x" }))), "HUSBANDRY_NOT_FOUND")
end

function T.TestRoadmapV31:testAnimalsOutComeFromTheClustersOfTheSubtype()
    local game = animalGame()
    local adapter = RPSimGameAdapter.new()
    lu.assertTrue(adapter:animalTransfer(transfer({ subType = "COW_HOLSTEIN", count = 12, direction = "OUT" })))
    lu.assertEquals({ game.stable.clusters[1].numAnimals, game.stable.clusters[2].numAnimals,
        game.stable.clusters[3].numAnimals }, { 0, 4, 3 })
    lu.assertEquals(select(2, adapter:animalTransfer(transfer({ count = 5, direction = "OUT" }))), "NOT_ENOUGH_ANIMALS")
    lu.assertEquals(game.stable.clusters[2].numAnimals, 4, "nothing taken when there are too few")
end

-- ------------------------------------------------------------------------------------------ R31-A4 / R31-A2

function T.TestRoadmapV31:testSnowHeightAndVehicleCategoryAreRead()
    helpers.fakeGame({ vehicles = { { uniqueId = "veh_1", propertyState = 1, sellPrice = 40000, damage = 0,
        configFileName = "data/vehicles/fendt/vario700.xml" } } })
    g_storeManager = { getItemByXMLFilename = function(_, xml)
        return xml == "data/vehicles/fendt/vario700.xml" and { categoryName = "tractorsL" } or nil
    end }
    g_currentMission.environment.weather = { getIsRaining = function() return false end,
        getRainFallScale = function() return 0 end, getGroundWetness = function() return 0.3 end }
    g_currentMission.snowSystem = { height = 0.123 }
    local doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts())
    lu.assertEquals(doc.assets.vehicles[1].category, "TRACTORSL")
    lu.assertEquals(doc.weather.snowHeight, 0.12)
    -- no snow system: the field is left out, the rest of the weather stays
    g_currentMission.snowSystem = nil
    doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts())
    lu.assertNil(doc.weather.snowHeight)
    lu.assertEquals(doc.weather.groundWetness, 0.3)
end

function T.TestRoadmapV31:testBorrowedMachineWithPriceZeroBooksNothing()
    local game = helpers.fakeGame({ money = 100 })
    StoreSpecies = { VEHICLE = 1 }
    g_storeManager = { getItemByXMLFilename = function() return { xmlFilename = "data/vehicles/claas/lexion.xml" } end }
    VehicleLoadingState = { OK = 1, NO_SPACE = 3 }
    local pending
    VehicleLoadingData = { new = function()
        local d = {}
        function d.setFilename() end
        function d.setLoadingPlace() return true end
        function d.setPropertyState() end
        function d.setOwnerFarmId() end
        function d:load(callback, target) pending = { callback, target } end
        return d
    end }
    g_currentMission.storeSpawnPlaces, g_currentMission.usedStorePlaces = {}, {}
    local outcome
    local ins = { instructionId = "ln", type = "VEHICLE_SPAWN", storeXmlFilename = "data/vehicles/claas/lexion.xml",
        ageMonths = 48, operatingHours = 900, damage = 0.1, wear = 0.1, price = 0, moneyReason = "MACHINE_RENT" }
    lu.assertTrue(RPSimGameAdapter.new():spawnVehicle(ins, function(ok, err, res) outcome = { ok, err, res } end))
    local v = { getUniqueId = function() return "veh_loan" end }
    pending[1](pending[2], { v }, VehicleLoadingState.OK)
    lu.assertEquals(outcome, { true, nil, { vehicleId = "veh_loan" } })
    lu.assertEquals(#game.moneyLog, 0)
    StoreSpecies, VehicleLoadingState, VehicleLoadingData = nil, nil, nil
end

-- ------------------------------------------------------------------------------------------ R31-D

--- An own tractor with a diesel tank (fill unit 1, 400 of 500 l) and a trailer without one; `opts` set the state.
local function fuelGame(opts)
    opts = opts or {}
    FillType = { DIESEL = 32, ELECTRICCHARGE = 33 }
    ToolType = { UNDEFINED = 0 }
    local tractor = { uniqueId = "veh_1", propertyState = 1, sellPrice = 80000, damage = 0, rootNode = 11,
        level = opts.level or 400, controlled = opts.controlled, ai = opts.ai, ownerFarmId = opts.ownerFarmId }
    function tractor.getConsumerFillUnitIndex(_, fillType) return fillType == FillType.DIESEL and 1 or nil end
    function tractor:getFillUnitFillLevel() return self.level end
    function tractor.getFillUnitCapacity() return 500 end
    function tractor:addFillUnitFillLevel(farmId, index, delta, fillType, toolType)
        self.added = { farmId, index, delta, fillType, toolType }
        self.level = math.max(0, self.level + delta)
    end
    function tractor:getIsControlled() return self.controlled == true end
    function tractor:getIsAIActive() return self.ai == true end
    local trailer = { uniqueId = "veh_2", propertyState = 1, sellPrice = 9000, damage = 0, rootNode = 12 }
    local game = helpers.fakeGame({ vehicles = { tractor, trailer } })
    g_currentMission.vehicleSystem.getVehicleByUniqueId = function(_, id)
        for _, v in ipairs(game.vehicles) do
            if v.uniqueId == id then return v end
        end
        return nil
    end
    game.tractor = game.vehicles[1]
    return game
end

function T.TestRoadmapV31:testTimeOfDayAndDieselAreExported()
    fuelGame()
    local doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts())
    lu.assertEquals(doc.calendar.dayTimeMs, 3600000)
    lu.assertEquals(doc.assets.vehicles[1].fuel, { liters = 400, capacity = 500 })
    lu.assertNil(doc.assets.vehicles[2].fuel) -- no diesel tank
end

function T.TestRoadmapV31:testOnlyDrivenVehiclesArePositionedWithFarmlandAndCrop()
    fuelGame({ controlled = true })
    getWorldTranslation = function(node) return node * 10.04, 0, -node * 3 end
    g_farmlandManager.getFarmlandIdAtWorldPosition = function(_, x) return x > 100 and 7 or 0 end
    FruitType = { UNKNOWN = 0 }
    g_fruitTypeManager = { getFruitTypeByIndex = function()
        return { getIsCut = function(_, growthState) return growthState >= 10 end }
    end }
    local growth = 4
    FieldState = { new = function()
        local st = {}
        function st:update() self.isValid, self.fruitTypeIndex, self.growthState = true, 3, growth end
        return st
    end }
    local doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts())
    lu.assertEquals(doc.vehiclePositions, { { uniqueId = "veh_1", x = 110.4, z = -33, farmlandId = 7, onCrop = true } })
    growth = 10 -- stubble after the harvest
    doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts())
    lu.assertFalse(doc.vehiclePositions[1].onCrop)
    fuelGame() -- parked
    getWorldTranslation = function() return 0, 0, 0 end
    doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts())
    lu.assertEquals(doc.vehiclePositions, {})
end

function T.TestRoadmapV31:testDieselIsTakenOnlyFromAParkedOwnVehicleWithATank()
    local game = fuelGame()
    local adapter = RPSimGameAdapter.new()
    local ok, err, res = adapter:vehicleFuel({ vehicleId = "veh_1", delta = -150 })
    lu.assertTrue(ok)
    lu.assertNil(err)
    lu.assertEquals(res, { liters = 150 })
    lu.assertEquals(game.tractor.added, { 1, 1, -150, FillType.DIESEL, ToolType.UNDEFINED })
    lu.assertEquals(game.tractor.level, 250)
    -- at most the level in the tank
    lu.assertEquals(select(3, adapter:vehicleFuel({ vehicleId = "veh_1", delta = -300 })), { liters = 250 })
    lu.assertEquals(game.tractor.level, 0)
    lu.assertEquals(select(2, adapter:vehicleFuel({ vehicleId = "veh_2", delta = -10 })), "NO_DIESEL_TANK")
    lu.assertEquals(select(2, adapter:vehicleFuel({ vehicleId = "veh_9", delta = -10 })), "VEHICLE_NOT_FOUND")
    fuelGame({ controlled = true })
    lu.assertEquals(select(2, RPSimGameAdapter.new():vehicleFuel({ vehicleId = "veh_1", delta = -10 })), "VEHICLE_IN_USE")
    fuelGame({ ai = true })
    lu.assertEquals(select(2, RPSimGameAdapter.new():vehicleFuel({ vehicleId = "veh_1", delta = -10 })), "VEHICLE_IN_USE")
    fuelGame({ ownerFarmId = 2 })
    lu.assertEquals(select(2, RPSimGameAdapter.new():vehicleFuel({ vehicleId = "veh_1", delta = -10 })), "NOT_OWN_VEHICLE")
end

function T.TestRoadmapV31:testVehicleFuelRunsThroughTheBridgeAndReportsTheLitres()
    local game = fuelGame()
    local fs = helpers.fakeFs()
    local paths = RPSimBridgePaths.new("/ms/")
    local bridge = RPSimBridge.new(RPSimConfig.new(), paths, RPSimGameAdapter.new())
    bridge.state.savegameId = SG
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "vf1", type = "VEHICLE_FUEL", vehicleId = "veh_1", delta = -120 } } })
    bridge:pollInstructions()
    lu.assertEquals(game.tractor.level, 280)
    lu.assertEquals(bridge.state.processed.vf1.status, "APPLIED")
    lu.assertEquals(bridge.state.processed.vf1.result, { liters = 120 })
end

-- ------------------------------------------------------------------------------------------ R31-K1

function T.TestRoadmapV31:testFieldOutlinesAreReadOnceWithTheMapSize()
    helpers.fakeGame()
    g_currentMission.terrainSize = 2048
    local nodes = { [1] = { 10.04, 20 }, [2] = { 50, 20 }, [3] = { 50, 60.06 }, [4] = { 10, 60 } }
    getWorldTranslation = function(node) return nodes[node][1], 0, nodes[node][2] end
    local function field(id, name, points)
        local f = { farmland = { id = id }, polygonPoints = points }
        function f.getName() return name end
        return f
    end
    g_fieldManager = { fields = { field(7, "7", { 1, 2, 3, 4 }), field(8, "8a", { 1, 2 }), { polygonPoints = { 1, 2, 3 } } } }
    local adapter = RPSimGameAdapter.new()
    local doc = RPSimMarketContext.build(adapter:collectMarketContext({}), RPSimConfig.new())
    lu.assertEquals(doc.fieldShapes.mapSize, 2048)
    -- field 8 has only two points, the last one no farmland: both left out
    lu.assertEquals(doc.fieldShapes.fields, { { farmlandId = 7, name = "7", points = {
        { x = 10, z = 20 }, { x = 50, z = 20 }, { x = 50, z = 60.1 }, { x = 10, z = 60 } } } })
    -- read once per mission: a later export keeps the outlines without reading the nodes again
    getWorldTranslation = function() error("not read again") end
    doc = RPSimMarketContext.build(adapter:collectMarketContext({}), RPSimConfig.new())
    lu.assertEquals(#doc.fieldShapes.fields, 1)
    -- without a map size nothing is exported
    g_currentMission.terrainSize = nil
    lu.assertNil(RPSimMarketContext.build(RPSimGameAdapter.new():collectMarketContext({}), RPSimConfig.new()).fieldShapes)
end

return T
