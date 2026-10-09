-- Roadmap V3.3 section Q against the fake engine. Q fixes the contract only (owner decision 2026-10-08): the adapter
-- does not read the rolling / mulching levels, the harvest counter or the crops of the map yet (R33-F2 / F3 / F4), so
-- a mod with the R33-Q1 contract exports none of them. The normalisation is tested in test_farm_facts and
-- test_market_context.
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestRoadmapV33 = {}

function T.TestRoadmapV33:setUp()
    helpers.loadGameModules()
end

function T.TestRoadmapV33:tearDown()
    g_fieldManager, g_fruitTypeManager, FruitType = nil, nil, nil
end

--- One own field (farmland 4) with wheat whose FieldState also has the rolling and mulching levels (FieldState.new).
local function fieldGame()
    local game = helpers.fakeGame({ farmlands = { { id = 4, areaInHa = 3, price = 30000, ownerFarmId = 1 } } })
    FruitType = { UNKNOWN = 0 }
    local wheat = { index = 3, minHarvestingGrowthState = 7, maxHarvestingGrowthState = 8, literPerSqm = 0.9,
        name = "WHEAT", regrows = false }
    g_fruitTypeManager = {
        getFruitTypeByIndex = function(_, i) return i == 3 and wheat or nil end,
        getFruitTypeNameByIndex = function(_, i) return i == 3 and "WHEAT" or nil end,
        getFillTypeNameByFruitTypeIndex = function(_, i) return i == 3 and "WHEAT" or nil end,
        getFruitTypes = function() return { wheat } end,
    }
    local state = { isValid = true, fruitTypeIndex = 3, growthState = 3, weedState = 0, stoneLevel = 0,
        sprayLevel = 1, limeLevel = 1, plowLevel = 1, rollerLevel = 1, stubbleShredLevel = 1 }
    local field = { farmland = { id = 4 }, areaHa = 3 }
    function field.getFieldState() return state end
    function field.getName() return "4" end
    g_fieldManager = { fields = { field } }
    return game
end

function T.TestRoadmapV33:testTheAdapterReadsNoneOfTheNewValuesYet()
    fieldGame()
    local adapter = RPSimGameAdapter.new()
    -- the bridge samples the fields with collectFields (Bridge:sampleFields) and puts them into the raw export
    local raw = adapter:collectFarmFacts()
    raw.fields = adapter:collectFields()
    local facts = RPSimFarmFacts.build(raw)
    lu.assertEquals(#facts.fields, 1)
    lu.assertEquals(facts.fields[1].sprayLevel, 1)
    lu.assertNil(facts.fields[1].rollerLevel)
    lu.assertNil(facts.fields[1].stubbleShredLevel)
    lu.assertNil(facts.harvests)
    lu.assertNil(RPSimMarketContext.build(adapter:collectMarketContext({}), RPSimConfig.new()).fruitTypes)
end

return T
