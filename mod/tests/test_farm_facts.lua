local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestFarmFacts = {}

function T.TestFarmFacts:testBuildMatchesSchema()
    local adapter = helpers.fakeAdapter()
    local raw = adapter:collectFarmFacts()
    raw.savegameId = "map_erlengrund_20260101"
    raw.gameTime = 48300000
    local doc = RPSimFarmFacts.build(raw, RPSimConfig.new())
    lu.assertEquals(doc.schemaVersion, 1)
    lu.assertEquals(doc.gameTime, 48300000)
    lu.assertEquals(doc.savegameId, "map_erlengrund_20260101")
    lu.assertEquals(doc.liquidity.balance, 245000)
    lu.assertEquals(doc.assets.vehicles[1], { uniqueId = "veh_00042", value = 285000, condition = 82 })
    lu.assertEquals(doc.assets.placeables[1], { uniqueId = "plc_00011", value = 120000 })
    lu.assertEquals(doc.assets.farmland[1], { farmlandId = 12, hectares = 4.5, price = 54000 })
    lu.assertEquals(doc.assets.animals[1], { husbandryUniqueId = "hus_00003", type = "COW", count = 24, estimatedValue = 96000 })
    lu.assertEquals(doc.assets.storage[1], { fillType = "WHEAT", amount = 42000, capacity = 50000 })
    lu.assertEquals(doc.liabilities.vanillaLoan, { active = true, remainingAmount = 80000 })
    lu.assertEquals(doc.liabilities.leasing, { { uniqueId = "veh_00077" } })
    lu.assertEquals(doc.prices[1], { sellPoint = "MillNorth", fillType = "WHEAT", currentPrice = 215 })
end

function T.TestFarmFacts:testEmptyFarmProducesEmptyArrays()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0 }, RPSimConfig.new())
    local json = RPSimJson.encode(doc)
    lu.assertStrContains(json, '"vehicles":[]')
    lu.assertStrContains(json, '"storage":[]')
    lu.assertStrContains(json, '"prices":[]')
    lu.assertStrContains(json, '"vanillaLoan":{"active":false,"remainingAmount":0}')
    lu.assertStrContains(json, '"leasing":[]')
end

function T.TestFarmFacts:testConditionFromDamageClamps()
    lu.assertEquals(RPSimFarmFacts.conditionFromDamage(0), 100)
    lu.assertEquals(RPSimFarmFacts.conditionFromDamage(1), 0)
    lu.assertEquals(RPSimFarmFacts.conditionFromDamage(1.5), 0)
    lu.assertEquals(RPSimFarmFacts.conditionFromDamage(-0.2), 100)
    lu.assertEquals(RPSimFarmFacts.conditionFromDamage(nil), 100)
end

function T.TestFarmFacts:testExportWritesDirectlyWithoutHelperFiles()
    -- T-07: the FS25 sandbox has no `os`, so the default mode writes the file directly.
    local bridge, fs, _, paths = helpers.newBridge({ renameAvailable = false })
    lu.assertEquals(bridge.cfg.atomicWriteMode, "direct")
    bridge:bootstrap()
    lu.assertTrue(bridge:exportFarmFacts())
    lu.assertNotNil(fs.files[paths.farmFacts])
    lu.assertNil(fs.files[paths.farmFacts .. ".tmp"])
    lu.assertNil(fs.files[paths.farmFacts .. ".ready"])
    local doc = RPSimJson.decode(fs.files[paths.farmFacts])
    lu.assertEquals(doc.savegameId, "map_erlengrund_1_20260101")
end

function T.TestFarmFacts:testRenameModeStillAvailableOutsideTheGame()
    local bridge, fs, _, paths = helpers.newBridge({ config = { atomicWriteMode = "rename" } })
    bridge:bootstrap()
    lu.assertTrue(bridge:exportFarmFacts())
    lu.assertNotNil(fs.files[paths.farmFacts])
    lu.assertNil(fs.files[paths.farmFacts .. ".tmp"])
end

function T.TestFarmFacts:testMarkerFallbackWhenRenameUnavailable()
    local bridge, fs, _, paths = helpers.newBridge({ renameAvailable = false, config = { atomicWriteMode = "auto" } })
    bridge:bootstrap()
    lu.assertTrue(bridge:exportFarmFacts())
    lu.assertNotNil(fs.files[paths.farmFacts])
    lu.assertEquals(fs.files[paths.farmFacts .. ".ready"], "")
end

function T.TestFarmFacts:testExportCycleUsesConfiguredInterval()
    local bridge, fs, _, paths = helpers.newBridge({ config = { exportIntervalMs = 1000, importIntervalMs = 999999 } })
    bridge:bootstrap()
    bridge.started = true
    bridge:update(500)
    lu.assertNil(fs.files[paths.farmFacts])
    bridge:update(500)
    lu.assertNotNil(fs.files[paths.farmFacts])
end

function T.TestFarmFacts:testLeasingCostIsExportedOnlyWhenKnown()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0,
        leasedVehicles = { { uniqueId = "b" }, { uniqueId = "a", costPerPeriod = 1234.4 } } }, RPSimConfig.new())
    lu.assertEquals(doc.liabilities.leasing, { { uniqueId = "a", costPerPeriod = 1234 }, { uniqueId = "b" } })
end

function T.TestFarmFacts:testCalendarAndTrendAreExported()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0,
        prices = { { sellPoint = "A", fillType = "WHEAT", pricePerLiter = 0.2, trend = "CLIMBING" },
            { sellPoint = "B", fillType = "WHEAT", pricePerLiter = 0.2 } },
        calendar = { period = 8, dayInPeriod = 2, daysPerPeriod = 3, year = 2, monotonicDay = 42, periodName = "Oktober",
            season = "AUTUMN" } },
        RPSimConfig.new())
    lu.assertEquals(doc.calendar, { period = 8, dayInPeriod = 2, daysPerPeriod = 3, year = 2, monotonicDay = 42,
        periodName = "Oktober", season = "AUTUMN" })
    lu.assertEquals(doc.prices[1].trend, "CLIMBING")
    lu.assertNil(doc.prices[2].trend)
end

function T.TestFarmFacts:testNoCalendarWithoutEnvironment()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0 }, RPSimConfig.new())
    lu.assertNil(doc.calendar)
end

-- Roadmap V2 (R2-Q1): optional blocks are left out when the adapter does not collect them
function T.TestFarmFacts:testRoadmapV2BlocksAreAbsentWhenNotCollected()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0 }, RPSimConfig.new())
    for _, block in ipairs({ "finances", "workforce", "husbandries", "fields", "weather" }) do
        lu.assertNil(doc[block], block)
    end
end

function T.TestFarmFacts:testRoadmapV2EmptyBlocksStayEmptyNotMissing()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, finances = {}, workforce = {},
        husbandries = {}, fields = {} }, RPSimConfig.new())
    local json = RPSimJson.encode(doc)
    lu.assertStrContains(json, '"finances":{"periods":[]}')
    lu.assertStrContains(json, '"workforce":{"activeJobs":[],"workedGameMs":{}}')
    lu.assertStrContains(json, '"husbandries":[]')
    lu.assertStrContains(json, '"fields":[]')
end

-- Roadmap V3 (R3-Q1): npcFields and tradeStorage are optional like the V2 blocks
function T.TestFarmFacts:testRoadmapV3BlocksAreAbsentWhenNotCollected()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0 }, RPSimConfig.new())
    lu.assertNil(doc.npcFields)
    lu.assertNil(doc.tradeStorage)
    local json = RPSimJson.encode(RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, npcFields = {},
        tradeStorage = {} }, RPSimConfig.new()))
    lu.assertStrContains(json, '"npcFields":[]')
    lu.assertStrContains(json, '"tradeStorage":[]')
end

function T.TestFarmFacts:testNpcFieldsUseTheFieldEntries()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, npcFields = {
        { farmlandId = 9, name = "9", hectares = 3.456, fruitType = "BARLEY", growthState = 9,
            minHarvestingGrowthState = 9, maxHarvestingGrowthState = 9, withered = false, cut = true,
            fillType = "BARLEY", litersPerSqm = 0.97, weedState = 0, stoneLevel = 1, sprayLevel = 0, limeLevel = 1,
            plowLevel = 0, groundType = "HARVEST_READY" },
        { farmlandId = 8, name = "8", hectares = 2, growthState = 0, weedState = 0, stoneLevel = 0, sprayLevel = 0,
            limeLevel = 0, plowLevel = 0 },
        { farmlandId = 10, name = "incomplete" } } }, RPSimConfig.new())
    lu.assertEquals(#doc.npcFields, 2)
    lu.assertEquals(doc.npcFields[1].farmlandId, 8)
    lu.assertNil(doc.npcFields[1].fruitType)
    lu.assertEquals(doc.npcFields[2].hectares, 3.46)
    lu.assertEquals(doc.npcFields[2].fillType, "BARLEY")
    lu.assertTrue(doc.npcFields[2].cut)
    lu.assertEquals(doc.npcFields[2].litersPerSqm, 0.97)
end

function T.TestFarmFacts:testTradeStorageKeepsOnlyGoodsAnOwnSiloAccepts()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, tradeStorage = {
        { fillType = "WHEAT", amount = 40000.4, freeCapacity = 60000 },
        { fillType = "STRAW", amount = 0, freeCapacity = 25000 },
        { fillType = "CANOLA", amount = 0, freeCapacity = 0 },
        { fillType = "BARLEY", amount = 12000 },
        { amount = 5, freeCapacity = 5 } } }, RPSimConfig.new())
    lu.assertEquals(doc.tradeStorage, {
        { fillType = "STRAW", amount = 0, freeCapacity = 25000 },
        { fillType = "WHEAT", amount = 40000, freeCapacity = 60000 } })
end

function T.TestFarmFacts:testFinancesAreRoundedAndSortedByPeriod()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, finances = { periods = {
        { year = 2, period = 8, byType = { HARVEST_INCOME = 48200.4, PURCHASE_FUEL = -3100.6, AI = "x" } },
        { year = 2, period = 7, byType = {} },
        { period = 9, byType = { AI = 1 } } } } }, RPSimConfig.new())
    lu.assertEquals(doc.finances.periods, {
        { year = 2, period = 7, byType = {} },
        { year = 2, period = 8, byType = { HARVEST_INCOME = 48200, PURCHASE_FUEL = -3101 } } })
end

function T.TestFarmFacts:testWorkforceUsesStringKeysForEmployees()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, workforce = {
        activeJobs = { { jobId = 7, employeeId = 12, title = "Fendt 942" }, { jobId = 3 } },
        workedGameMs = { [12] = 3600000.4, [1] = 0 } } }, RPSimConfig.new())
    lu.assertEquals(doc.workforce.activeJobs, { { jobId = 3 }, { jobId = 7, employeeId = 12, title = "Fendt 942" } })
    lu.assertStrContains(RPSimJson.encode(doc), '"workedGameMs":{"1":0,"12":3600000}')
end

function T.TestFarmFacts:testHusbandriesKeepConditionsAndOptionalProductivity()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, husbandries = {
        { husbandryUniqueId = "hus_2", health = 61.25, food = 0.12345, conditions = {} },
        { husbandryUniqueId = "hus_1", health = 90, productivity = 0.8, food = 1,
            conditions = { { title = "Wasser", ratio = 0.5 }, { title = "Stroh" } } },
        { husbandryUniqueId = "hus_3", food = 1 } } }, RPSimConfig.new())
    lu.assertEquals(#doc.husbandries, 2)
    lu.assertEquals(doc.husbandries[1], { husbandryUniqueId = "hus_1", health = 90, productivity = 0.8, food = 1,
        conditions = { { title = "Wasser", ratio = 0.5 } } })
    lu.assertEquals(doc.husbandries[2], { husbandryUniqueId = "hus_2", health = 61.25, food = 0.123, conditions = {} })
end

function T.TestFarmFacts:testFieldsWithAndWithoutCrop()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, fields = {
        { farmlandId = 12, name = "12", hectares = 4.456, fruitType = "WHEAT", growthState = 5,
            minHarvestingGrowthState = 7, maxHarvestingGrowthState = 8, weedState = 2, stoneLevel = 1, sprayLevel = 0,
            limeLevel = 1, plowLevel = 0, groundType = "SOWN" },
        { farmlandId = 3, name = "3", hectares = 2, growthState = 0, weedState = 0, stoneLevel = 0, sprayLevel = 0,
            limeLevel = 0, plowLevel = 1 },
        { farmlandId = 4, name = "4", hectares = 2 } } }, RPSimConfig.new())
    lu.assertEquals(#doc.fields, 2)
    lu.assertEquals(doc.fields[1], { farmlandId = 3, name = "3", hectares = 2, growthState = 0, weedState = 0,
        stoneLevel = 0, sprayLevel = 0, limeLevel = 0, plowLevel = 1 })
    lu.assertEquals(doc.fields[2], { farmlandId = 12, name = "12", hectares = 4.46, fruitType = "WHEAT", growthState = 5,
        minHarvestingGrowthState = 7, maxHarvestingGrowthState = 8, weedState = 2, stoneLevel = 1, sprayLevel = 0,
        limeLevel = 1, plowLevel = 0, groundType = "SOWN" })
end

function T.TestFarmFacts:testCropFlagsYieldAndFieldRules()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, fields = {
        { farmlandId = 6, name = "6", hectares = 3, fruitType = "WHEAT", growthState = 10, minHarvestingGrowthState = 8,
            maxHarvestingGrowthState = 8, withered = true, cut = false, fillType = "WHEAT", litersPerSqm = 0.123456,
            weedState = 0, stoneLevel = 0, sprayLevel = 0, limeLevel = 0, plowLevel = 0 },
        { farmlandId = 7, name = "7", hectares = 1, withered = true, fillType = "WHEAT", growthState = 0, weedState = 0,
            stoneLevel = 0, sprayLevel = 0, limeLevel = 0, plowLevel = 0 } },
        fieldRules = { plowingRequired = true, limeRequired = false, weedsEnabled = true, stonesEnabled = false } },
        RPSimConfig.new())
    lu.assertTrue(doc.fields[1].withered)
    lu.assertFalse(doc.fields[1].cut)
    lu.assertEquals(doc.fields[1].fillType, "WHEAT")
    lu.assertEquals(doc.fields[1].litersPerSqm, 0.1235)
    -- the crop details only exist with a crop
    lu.assertNil(doc.fields[2].withered)
    lu.assertNil(doc.fields[2].fillType)
    lu.assertEquals(doc.fieldRules, { plowingRequired = true, limeRequired = false, weedsEnabled = true,
        stonesEnabled = false })
    doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, fieldRules = { plowingRequired = true } },
        RPSimConfig.new())
    lu.assertNil(doc.fieldRules)
end

function T.TestFarmFacts:testWeatherOnlyWhenComplete()
    local cfg = RPSimConfig.new()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0,
        weather = { raining = true, rainFallScale = 0.66666, groundWetness = 0.4 } }, cfg)
    lu.assertEquals(doc.weather, { raining = true, rainFallScale = 0.667, groundWetness = 0.4 })
    -- Hof-Tablet: the temperature is optional and rounded to one decimal
    doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0,
        weather = { raining = false, rainFallScale = 0, groundWetness = 0.1, temperature = 14.46 } }, cfg)
    lu.assertEquals(doc.weather, { raining = false, rainFallScale = 0, groundWetness = 0.1, temperature = 14.5 })
    doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0,
        weather = { raining = false, rainFallScale = 0, groundWetness = 0.1, temperature = 0 / 0 } }, cfg)
    lu.assertNil(doc.weather.temperature)
    doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, weather = { raining = true } }, cfg)
    lu.assertNil(doc.weather)
end

return T
