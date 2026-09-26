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

function T.TestGameAdapter:testRefusedOwnershipChangeAbortsTheBatch()
    local game = helpers.fakeGame({ money = 100000 })
    g_farmlandManager.setLandOwnership = function() return false end -- e.g. NOT_BUYABLE_FARM_ID
    local bridge, fs, paths = realBridge(game)
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "t", batchId = "b1", type = "FARMLAND_TRANSFER", farmlandId = 2, direction = "TO_PLAYER" },
        { instructionId = "m", batchId = "b1", type = "MONEY_TRANSACTION", amount = -5000, reason = "FARMLAND_PURCHASE" } } })
    bridge:pollInstructions()
    lu.assertEquals(game.farm.money, 100000)
    lu.assertEquals(bridge.state.processed.t.status, "FAILED")
    lu.assertStrContains(bridge.state.processed.t.message, "setLandOwnership refused")
    lu.assertEquals(bridge.state.processed.m.status, "FAILED")
    lu.assertStrContains(bridge.state.processed.m.message, "BATCH_ABORTED")
end

-- T-08
function T.TestGameAdapter:testCalendarFromEnvironment()
    helpers.fakeGame()
    g_i18n = { formatPeriod = function() return "Oktober" end }
    lu.assertEquals(RPSimGameAdapter.new():collectCalendar(), { period = 8, dayInPeriod = 2, daysPerPeriod = 3, year = 2,
        monotonicDay = 3, periodName = "Oktober" })
    g_i18n = nil
    lu.assertNil(RPSimGameAdapter.new():collectCalendar().periodName)
end

-- T-09
function T.TestGameAdapter:testConflictModsAreDetectedViaModIsLoaded()
    helpers.fakeGame()
    g_modIsLoaded = { FS25_UsedPlus = true, FS25_Other = true }
    local raw = RPSimGameAdapter.new():collectMarketContext(RPSimConfig.new().conflictMods)
    lu.assertEquals(raw.detectedMods, { "FS25_UsedPlus" })
    g_modIsLoaded = nil
    lu.assertEquals(RPSimGameAdapter.new():collectMarketContext(RPSimConfig.new().conflictMods).detectedMods, {})
end

-- T-10
local function station(name, opts)
    opts = opts or {}
    return {
        isa = function(_, class) return class == SellingStation and not opts.unloadingOnly end,
        hideFromPricesMenu = opts.hidden,
        acceptedFillTypes = { [1] = true },
        getName = function() return name end,
        getEffectiveFillTypePrice = function() return 0.25 end,
        getCurrentPricingTrend = function() return opts.trend or 0 end,
    }
end

function T.TestGameAdapter:testOnlyVisibleSellingStationsAreExported()
    SellingStation = { PRICE_CLIMBING = 1, PRICE_FALLING = 2 }
    Utils = { isBitSet = function(v, bit) return v % (2 * bit) >= bit end }
    helpers.fakeGame({ stations = { station("Mill", { trend = 1 }), station("Husbandry", { hidden = true }),
        station("Silo", { unloadingOnly = true }), station("Dairy", { trend = 2 }) } })
    local a = RPSimGameAdapter.new()
    local ctx = a:collectMarketContext({})
    local names = {}
    for _, sp in ipairs(ctx.sellPoints) do names[#names + 1] = sp.name end
    lu.assertEquals(names, { "Mill", "Dairy" })
    local facts = a:collectFarmFacts()
    lu.assertEquals(facts.prices[1].trend, "CLIMBING")
    lu.assertEquals(facts.prices[2].trend, "FALLING")
    SellingStation, Utils = nil, nil
end

-- T-11
function T.TestGameAdapter:testFarmlandVisibilityFlags()
    helpers.fakeGame()
    local ctx = RPSimGameAdapter.new():collectMarketContext({})
    lu.assertTrue(ctx.farmlands[1].showOnFarmlandsScreen)
    lu.assertFalse(ctx.farmlands[2].showOnFarmlandsScreen)
    lu.assertFalse(ctx.farmlands[2].defaultFarmProperty)
end

return T
