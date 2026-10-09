-- Roadmap V3.3 section F against the fake engine: the rolling / mulching levels of the fields (R33-F2), the crops of
-- the map with needsRolling and the products of the fruit type converters (R33-F4), the harvest counter with both hooks
-- (R33-F3, owner decision 2026-10-08: Combine.addCutterArea and the fallback around Cutter.onEndWorkAreaProcessing,
-- never counted twice), only on own farmland, its savegame persistence and its export.
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestRoadmapV33 = {}

function T.TestRoadmapV33:setUp()
    helpers.loadGameModules()
end

function T.TestRoadmapV33:tearDown()
    g_fieldManager, g_fruitTypeManager, FruitType, getWorldTranslation = nil, nil, nil, nil
    RPSim.bridge = nil
end

--- Own farmland 4 (wheat with rolling / mulching levels) and foreign farmland 5; fill types WHEAT 1, MAIZE 2, CHAFF 3,
-- GRASS_WINDROW 4. Machines stand at x = farmland id (z ignored).
local function fieldGame()
    local game = helpers.fakeGame({ fillTypes = { [1] = "WHEAT", [2] = "MAIZE", [3] = "CHAFF", [4] = "GRASS_WINDROW" },
        farmlands = { { id = 4, areaInHa = 3, price = 30000, ownerFarmId = 1 }, { id = 5, areaInHa = 2, price = 20000 } } })
    FruitType = { UNKNOWN = 0 }
    local wheat = { index = 3, name = "WHEAT", minHarvestingGrowthState = 7, maxHarvestingGrowthState = 8,
        literPerSqm = 0.9, regrows = false, fillType = { title = "Weizen" } }
    local maize = { index = 6, name = "MAIZE", regrows = false, fillType = { title = "Mais" } }
    local grass = { index = 7, name = "GRASS", regrows = true, needsRolling = false, fillType = { title = "Gras" } }
    local byIndex = { [3] = wheat, [6] = maize, [7] = grass }
    local fillOf = { [3] = "WHEAT", [6] = "MAIZE", [7] = "GRASS_WINDROW" }
    g_fruitTypeManager = {
        getFruitTypeByIndex = function(_, i) return byIndex[i] end,
        getFruitTypeNameByIndex = function(_, i) return byIndex[i] and byIndex[i].name or nil end,
        getFillTypeNameByFruitTypeIndex = function(_, i) return fillOf[i] end,
        getFruitTypeIndexByFillTypeIndex = function(_, fill) return ({ [1] = 3, [2] = 6, [4] = 7 })[fill] end,
        getFruitTypes = function() return { wheat, maize, grass } end,
        -- converter FORAGEHARVESTER: maize -> chaff (keyed by the fruit type index, as the cutter reads it)
        fruitTypeConverters = { { [6] = { fillTypeIndex = 3, conversionFactor = 3 } } },
    }
    local state = { isValid = true, fruitTypeIndex = 3, growthState = 3, weedState = 0, stoneLevel = 0,
        sprayLevel = 1, limeLevel = 1, plowLevel = 1, rollerLevel = 1, stubbleShredLevel = 0 }
    local field = { farmland = { id = 4 }, areaHa = 3 }
    function field.getFieldState() return state end
    function field.getName() return "4" end
    g_fieldManager = { fields = { field } }
    getWorldTranslation = function(node) return node.x, 0, 0 end
    g_farmlandManager.getFarmlandIdAtWorldPosition = function(_, x) return x end
    game.state = state
    return game
end

local function bridge()
    local adapter = RPSimGameAdapter.new()
    local b = RPSimBridge.new(RPSimConfig.new(), RPSimBridgePaths.new("/ms/"), adapter)
    RPSim.bridge = b
    return b
end

--- A combine at farmland x whose original addCutterArea books `applied` litres into its tank.
local function combineAt(x, applied)
    local c = { rootNode = { x = x }, calls = 0 }
    function c.addCutterArea(self) self.calls = self.calls + 1; return applied end
    return c
end

function T.TestRoadmapV33:testFieldLevelsAreRead()
    fieldGame()
    local fields = RPSimFarmFacts.buildFields(RPSimGameAdapter.new():collectFields())
    lu.assertEquals({ fields[1].rollerLevel, fields[1].stubbleShredLevel }, { 1, 0 })
end

function T.TestRoadmapV33:testCropsOfTheMapAreReadOnce()
    fieldGame()
    local adapter = RPSimGameAdapter.new()
    local doc = RPSimMarketContext.build(adapter:collectMarketContext({}), RPSimConfig.new())
    lu.assertEquals(doc.fruitTypes, {
        { name = "GRASS", fillType = "GRASS_WINDROW", title = "Gras", regrows = true, needsRolling = false, products = {} },
        { name = "MAIZE", fillType = "MAIZE", title = "Mais", regrows = false, needsRolling = true,
            products = { "CHAFF" } },
        { name = "WHEAT", fillType = "WHEAT", title = "Weizen", regrows = false, needsRolling = true, products = {} } })
    -- read once per mission: a later export keeps the list
    g_fruitTypeManager.getFruitTypes = function() error("not read again") end
    lu.assertEquals(#RPSimMarketContext.build(adapter:collectMarketContext({}), RPSimConfig.new()).fruitTypes, 3)
end

function T.TestRoadmapV33:testHarvestEntryOnlyOnOwnFarmland()
    fieldGame()
    local adapter = RPSimGameAdapter.new()
    lu.assertEquals(adapter:harvestEntry(combineAt(4), 6, 3), { farmlandId = 4, fruitType = "MAIZE", fillType = "CHAFF" })
    -- no input fruit type: the fruit type of the product
    lu.assertEquals(adapter:harvestEntry(combineAt(4), nil, 4).fruitType, "GRASS")
    lu.assertEquals(adapter:harvestEntry(combineAt(4), 0, 1).fruitType, "WHEAT")
    lu.assertNil(adapter:harvestEntry(combineAt(5), 3, 1)) -- foreign farmland (missions)
    lu.assertNil(adapter:harvestEntry(combineAt(0), 3, 1)) -- no farmland
end

function T.TestRoadmapV33:testMainHookCountsTheLitresBookedIntoTheTank()
    fieldGame()
    local b = bridge()
    local c = combineAt(4, 0)
    local applied = RPSim.addCutterAreaHook(c, function() return 812.5 end, 1, 900, 3, 1)
    lu.assertEquals(applied, 812.5)
    RPSim.addCutterAreaHook(c, function() return 0 end, 1, 900, 3, 1) -- full tank: nothing booked, nothing counted
    RPSim.addCutterAreaHook(combineAt(5), function() return 500 end, 1, 500, 3, 1)
    lu.assertEquals(RPSimHarvestCounter.toRaw(b.state.harvests),
        { { farmlandId = 4, fruitType = "WHEAT", fillType = "WHEAT", liters = 812.5 } })
end

function T.TestRoadmapV33:testFallbackCountsWhenTheMainWayDoesNotAndNeverTwice()
    fieldGame()
    local b = bridge()
    -- the vehicle kept the original addCutterArea (main hook not reached): the fallback counts
    local c = combineAt(4, 300)
    local original = c.addCutterArea
    local cutter = { spec_cutter = { workAreaParameters = { combineVehicle = c } } }
    RPSim.cutterEndHook(cutter, function(ct) return ct.spec_cutter.workAreaParameters.combineVehicle:addCutterArea(
        1, 300, 6, 3) end)
    lu.assertEquals(c.calls, 1)
    lu.assertEquals(c.addCutterArea, original) -- restored after the cutter call
    lu.assertEquals(RPSimHarvestCounter.toRaw(b.state.harvests)[1].liters, 300)
    -- the vehicle uses the hooked function: the main way counts, the fallback does not count again
    local hooked = combineAt(4, 0)
    function hooked.addCutterArea(hc, ...)
        return RPSim.addCutterAreaHook(hc, function() return 200 end, ...)
    end
    cutter.spec_cutter.workAreaParameters.combineVehicle = hooked
    RPSim.cutterEndHook(cutter, function(ct) return ct.spec_cutter.workAreaParameters.combineVehicle:addCutterArea(
        1, 200, 6, 3) end)
    lu.assertEquals(RPSimHarvestCounter.toRaw(b.state.harvests)[1].liters, 500)
    -- an error of the game's function is passed on and the combine is restored
    local broken = combineAt(4, 0)
    local before = broken.addCutterArea
    cutter.spec_cutter.workAreaParameters.combineVehicle = broken
    lu.assertErrorMsgContains("boom", RPSim.cutterEndHook, cutter, function() error("boom") end)
    lu.assertEquals(broken.addCutterArea, before)
end

function T.TestRoadmapV33:testCounterIsSavedLoadedAndExportedWithTheHook()
    fieldGame()
    local b = bridge()
    RPSimHarvestCounter.add(b.state.harvests, 4, "MAIZE", "CHAFF", 52000.6)
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
    lu.assertEquals(RPSimHarvestCounter.toRaw(loaded.harvests),
        { { farmlandId = 4, fruitType = "MAIZE", fillType = "CHAFF", liters = 52000.6 } })
    -- exported only while a hook is installed (older mod / no hook: the block stays missing)
    local fs = helpers.fakeFs()
    b:bootstrap()
    b.state.savegameId = "sg"
    b:exportFarmFacts()
    lu.assertNil(RPSimJson.decode(fs.files[b.paths.farmFacts]).harvests)
    b.harvestCounterEnabled = true
    b:exportFarmFacts()
    lu.assertEquals(RPSimJson.decode(fs.files[b.paths.farmFacts]).harvests,
        { { farmlandId = 4, fruitType = "MAIZE", fillType = "CHAFF", liters = 52001 } })
end

return T
