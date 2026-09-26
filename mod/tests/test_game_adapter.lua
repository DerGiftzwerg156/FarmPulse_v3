-- Tests of the FS25-facing adapter against a fake engine (tests/helpers.lua fakeGame).
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestGameAdapter = {}

local SG = "map_erlengrund_1_20260101"

local function realBridge(game)
    local fs = helpers.fakeFs()
    local cfg = RPSimConfig.new()
    local paths = RPSimBridgePaths.new("/ms/")
    local bridge = RPSimBridge.new(cfg, paths, RPSimGameAdapter.new())
    bridge.state.savegameId = SG
    bridge:bootstrap()
    return bridge, fs, paths, game
end

function T.TestGameAdapter:setUp()
    helpers.loadGameModules()
end

function T.TestGameAdapter:testOnlyOwnedVehiclesAreAssetsLeasedAreListedSeparately()
    helpers.fakeGame()
    local raw = RPSimGameAdapter.new():collectFarmFacts()
    lu.assertEquals(#raw.vehicles, 1)
    lu.assertEquals(raw.vehicles[1].uniqueId, "veh_owned")
    lu.assertEquals(raw.leasedVehicles, { { uniqueId = "veh_leased" } })
end

function T.TestGameAdapter:testBalanceIsReadFromTheFarm()
    helpers.fakeGame({ money = 4321 })
    lu.assertEquals(RPSimGameAdapter.new():getBalance(), 4321)
end

function T.TestGameAdapter:testDebitAboveBalanceIsRefused()
    helpers.fakeGame({ money = 1000 })
    local a = RPSimGameAdapter.new()
    local ok, err = a:checkBatchFunds({ { type = "MONEY_TRANSACTION", amount = -1500, reason = "CREDIT_INSTALLMENT" } })
    lu.assertFalse(ok)
    lu.assertEquals(err, "INSUFFICIENT_FUNDS")
    lu.assertTrue(a:checkBatchFunds({ { type = "MONEY_TRANSACTION", amount = -1000, reason = "CREDIT_INSTALLMENT" } }))
    lu.assertTrue(a:checkBatchFunds({ { type = "MONEY_TRANSACTION", amount = 5000, reason = "SUBSIDY" } }))
end

function T.TestGameAdapter:testInsufficientFundsYieldsFailedAckAndNoBooking()
    local game = helpers.fakeGame({ money = 1000 })
    local bridge, fs, paths = realBridge(game)
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "rate", type = "MONEY_TRANSACTION", amount = -2500, reason = "CREDIT_INSTALLMENT" },
        { instructionId = "pay", type = "MONEY_TRANSACTION", amount = 300, reason = "SUBSIDY" } } })
    bridge:pollInstructions()
    lu.assertEquals(game.farm.money, 1300)
    local ack = RPSimJson.decode(fs.files[paths.instructionsAck])
    local byId = {}
    for _, a in ipairs(ack.acks) do byId[a.instructionId] = a end
    lu.assertEquals(byId.rate.status, "FAILED")
    lu.assertEquals(byId.rate.message, "INSUFFICIENT_FUNDS")
    lu.assertEquals(byId.pay.status, "APPLIED")
end

function T.TestGameAdapter:testFarmlandPurchaseWithoutMoneyChangesNothing()
    local game = helpers.fakeGame({ money = 1000 })
    local bridge, fs, paths = realBridge(game)
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "t", batchId = "b1", type = "FARMLAND_TRANSFER", farmlandId = 1, direction = "TO_PLAYER" },
        { instructionId = "m", batchId = "b1", type = "MONEY_TRANSACTION", amount = -30000,
            reason = "FARMLAND_PURCHASE" } } })
    bridge:pollInstructions()
    lu.assertEquals(game.ownership[1], 0)
    lu.assertEquals(game.farm.money, 1000)
    lu.assertEquals(bridge.state.processed.t.status, "FAILED")
    lu.assertEquals(bridge.state.processed.m.status, "FAILED")
end

function T.TestGameAdapter:testFarmlandSaleCreditCoversNothingButIsAllowed()
    local game = helpers.fakeGame({ money = 0 })
    game.ownership[1] = 1
    local bridge, fs, paths = realBridge(game)
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "t", batchId = "b1", type = "FARMLAND_TRANSFER", farmlandId = 1, direction = "FROM_PLAYER" },
        { instructionId = "m", batchId = "b1", type = "MONEY_TRANSACTION", amount = 30000, reason = "FARMLAND_SALE" } } })
    bridge:pollInstructions()
    lu.assertEquals(game.ownership[1], 0)
    lu.assertEquals(game.farm.money, 30000)
end

return T
