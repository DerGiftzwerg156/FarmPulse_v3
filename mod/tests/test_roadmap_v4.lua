-- Roadmap V4 section Q against the fake engine: Q fixes the contract only (like R31-Q / R33-Q). The game adapter does
-- not read the new values yet - the consumption counter per field (R4-D1), the purchase prices (R4-D1), the food values
-- of the husbandries, the feed stock and the food groups (R4-F1) and the sowing / harvest windows of the crops (R4-A2)
-- follow with their sections. Until then the blocks are missing ("not collected"), never empty.
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestRoadmapV4 = {}

function T.TestRoadmapV4:setUp()
    helpers.loadGameModules()
end

function T.TestRoadmapV4:tearDown()
    g_fruitTypeManager, FruitType = nil, nil
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

function T.TestRoadmapV4:testFarmFactsCarryNoV4BlocksYet()
    feedGame()
    local doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts(), RPSimConfig.new())
    lu.assertNil(doc.inputs)
    lu.assertNil(doc.feedStock)
    local h = doc.husbandries[1]
    lu.assertEquals(h.husbandryUniqueId, "hus_00001")
    for _, key in ipairs({ "foodLitersPerHour", "foodCapacity", "trough", "strawLitersPerHour", "straw",
        "feedConsumed" }) do
        lu.assertNil(h[key], key)
    end
end

function T.TestRoadmapV4:testMarketContextCarriesNoV4BlocksYet()
    feedGame()
    local doc = RPSimMarketContext.build(RPSimGameAdapter.new():collectMarketContext({}), RPSimConfig.new())
    lu.assertNil(doc.fillTypeCosts)
    lu.assertNil(doc.animalFood)
    lu.assertEquals(doc.fruitTypes[1].name, "WHEAT")
    for _, key in ipairs({ "seedUsagePerSqm", "litersPerSqm", "plantablePeriods", "harvestablePeriods" }) do
        lu.assertNil(doc.fruitTypes[1][key], key)
    end
end

return T
