-- T-01 (first export after the mission start, periodic market_context), T-05 (sale hook order),
-- T-06 (modSettings path).
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestStartup = {}

local SG = "map_erlengrund_1_20260101"

function T.TestStartup:testNothingHappensBeforeTheMissionStarted()
    local bridge, fs, adapter, paths = helpers.newBridge({ config = { exportIntervalMs = 10, importIntervalMs = 10 } })
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "a", type = "MONEY_TRANSACTION", amount = 5, reason = "OTHER" } } })
    bridge:update(1000)
    lu.assertNil(fs.files[paths.farmFacts])
    lu.assertNil(fs.files[paths.marketContext])
    lu.assertEquals(#adapter.moneyLog, 0)
end

function T.TestStartup:testMissionStartTriggersTheFirstExport()
    helpers.loadGameModules()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:bootstrap()
    RPSim.bridge = bridge
    helpers.logs = {}
    RPSim.onStartMission(nil)
    lu.assertTrue(bridge.started)
    lu.assertNotNil(fs.files[paths.farmFacts])
    lu.assertNotNil(fs.files[paths.marketContext])
    lu.assertNotNil(fs.files[paths.instructionsAck])
    lu.assertEquals(helpers.countLogs("info", "First export: 1 sell points, 2 farmlands"), 1)
    RPSim.bridge = nil
end

function T.TestStartup:testFallbackStartsTheBridgeWhenTheStartHookNeverFires()
    local bridge, fs, _, paths = helpers.newBridge({ config = { startFallbackMs = 100 } })
    bridge:bootstrap()
    bridge:update(60)
    lu.assertNil(fs.files[paths.farmFacts])
    bridge:update(60)
    lu.assertTrue(bridge.started)
    lu.assertNotNil(fs.files[paths.farmFacts])
end

function T.TestStartup:testMarketContextIsRefreshedWithFarmFactsWhenChanged()
    local bridge, fs, adapter, paths = helpers.newBridge({ config = { exportIntervalMs = 10, importIntervalMs = 999999 } })
    bridge:onSavegameLoaded()
    fs.files[paths.marketContext] = "SENTINEL"
    bridge:update(20)
    lu.assertEquals(fs.files[paths.marketContext], "SENTINEL") -- unchanged content is not rewritten
    adapter.allFarmlands[#adapter.allFarmlands + 1] = { farmlandId = 99, hectares = 1, price = 1, ownerFarmId = 0 }
    bridge:update(20)
    local doc = RPSimJson.decode(fs.files[paths.marketContext])
    lu.assertEquals(#doc.farmlands, 3)
end

function T.TestStartup:testSaleIsCountedAfterPricingSoTheLastDeliveryGetsTheContractPrice()
    helpers.fakeGame()
    helpers.loadGameModules()
    local bridge = helpers.newBridge()
    bridge.state.priceEvents:add({ id = "c1", priceMode = "FIXED", fillType = "WHEAT", sellPoint = "station",
        gameTimeStart = 0, fixedPrice = 400, maxQuantity = 1000, deadlineGameTime = 10 ^ 12, deliveredQuantity = 0 })
    RPSim.bridge = bridge
    local station = { getName = function() return "station" end }
    local paidPerLiter
    local function superFunc(st, _, fillDelta, fillTypeIndex)
        -- the game prices the delivery inside sellFillType
        paidPerLiter = bridge:effectivePrice(RPSimGameAdapter.sellPointId(st), "WHEAT", 0.2)
        lu.assertEquals(fillTypeIndex, 1)
        return fillDelta
    end
    -- one pallet that fills the contract completely
    local result = RPSim.sellFillTypeHook(station, superFunc, 1, 1000, 1, nil, nil, nil)
    lu.assertEquals(result, 1000)
    lu.assertAlmostEquals(paidPerLiter, 0.4, 1e-9)
    lu.assertEquals(bridge.state.priceEvents.events[1].deliveredQuantity, 1000)
    -- the next delivery is back at the market price
    RPSim.sellFillTypeHook(station, superFunc, 1, 500, 1)
    lu.assertAlmostEquals(paidPerLiter, 0.2, 1e-9)
    RPSim.bridge = nil
end

function T.TestStartup:testModSettingsDirFromUserProfile()
    lu.assertEquals(RPSimBridgePaths.resolveModSettingsDir(nil, "C:/Users/k/Documents/My Games/FarmingSimulator2025/"),
        "C:/Users/k/Documents/My Games/FarmingSimulator2025/modSettings/")
    lu.assertEquals(RPSimBridgePaths.resolveModSettingsDir(nil, "/home/k/fs25"), "/home/k/fs25/modSettings/")
    lu.assertEquals(RPSimBridgePaths.resolveModSettingsDir("/explicit/", "/home/k/fs25/"), "/explicit/")
    lu.assertNil(RPSimBridgePaths.resolveModSettingsDir(nil, nil))
end

function T.TestStartup:testLoadMapUsesUserProfilePathAndLogsIt()
    helpers.fakeGame()
    helpers.fakeFs()
    helpers.loadGameModules()
    getUserProfileAppPath = function() return "/profile/" end
    Mission00 = { onStartMission = function() end }
    helpers.logs = {}
    RPSim:loadMap(nil)
    lu.assertEquals(RPSim.bridge.paths.base, "/profile/modSettings/FS25_RPSim/")
    lu.assertEquals(helpers.countLogs("info", "Bridge folder: /profile/modSettings/FS25_RPSim/"), 1)
    lu.assertFalse(RPSim.bridge.started)
    lu.assertStrContains(RPSim.bridge.state.savegameId, "map_riverbend_springs_1_")
    getUserProfileAppPath = nil
    Mission00 = nil
    RPSim.bridge = nil
end

return T
