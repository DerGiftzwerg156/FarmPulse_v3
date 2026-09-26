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
        owningPlaceable = opts.placeable,
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

-- T-21: FS25 NPC of the farmland (unknown index -> no npc)
function T.TestGameAdapter:testFarmlandNpcIsExported()
    helpers.fakeGame()
    local ctx = RPSimGameAdapter.new():collectMarketContext({})
    lu.assertEquals(ctx.farmlands[1].npc, { index = 1, name = "npc_anna", title = "Anna Berger" })
    lu.assertNil(ctx.farmlands[2].npc)
    local doc = RPSimMarketContext.build({ savegameId = "sg", mapName = "m", farmlands = ctx.farmlands })
    lu.assertEquals(doc.farmlands[1].npc, { index = 1, name = "npc_anna", title = "Anna Berger" })
    lu.assertNil(doc.farmlands[2].npc)
end

-- T-21: addIngameNotification with the FSBaseMission level constant
function T.TestGameAdapter:testNotifyUsesTheIngameNotification()
    local game = helpers.fakeGame()
    FSBaseMission = { INGAME_NOTIFICATION_INFO = 11, INGAME_NOTIFICATION_OK = 12 }
    local a = RPSimGameAdapter.new()
    lu.assertTrue(a:notify("Hallo", "OK"))
    lu.assertTrue(a:notify("Hallo2", "WHATEVER"))
    lu.assertEquals(game.notifications[1], { kind = 12, text = "Hallo" })
    lu.assertEquals(game.notifications[2].kind, 11)
    FSBaseMission = nil
end

-- T-21: own booking titles via MoneyType.register(statistic, titleKey)
function T.TestGameAdapter:testBookingsGetTheirOwnMoneyType()
    local game = helpers.fakeGame()
    local calls = {}
    MoneyType.register = function(statistic, title)
        calls[#calls + 1] = statistic .. "|" .. title
        return { statistic = statistic, title = title }
    end
    local a = RPSimGameAdapter.new()
    a.config = RPSimConfig.new({ moneyTypeStatistics = { SALARY_PAYMENT = "wagePayment" } })
    lu.assertTrue(a:addMoney(-100, "CREDIT_INSTALLMENT", "Rate"))
    lu.assertTrue(a:addMoney(-100, "CREDIT_INSTALLMENT", "Rate"))
    lu.assertTrue(a:addMoney(-50, "SALARY_PAYMENT", "Gehalt"))
    lu.assertEquals(calls, { "other|rpsim_money_CREDIT_INSTALLMENT", "wagePayment|rpsim_money_SALARY_PAYMENT" })
    lu.assertEquals(game.moneyLog[1].moneyType.title, "rpsim_money_CREDIT_INSTALLMENT")
    lu.assertEquals(game.moneyLog[3].moneyType.statistic, "wagePayment")
end

function T.TestGameAdapter:testMoneyTypeFallsBackToOther()
    local game = helpers.fakeGame()
    MoneyType.register = function() error("unknown statistic") end
    local a = RPSimGameAdapter.new()
    lu.assertTrue(a:addMoney(10, "SUBSIDY"))
    lu.assertIs(game.moneyLog[1].moneyType, MoneyType.OTHER)
    local b = RPSimGameAdapter.new()
    b.config = RPSimConfig.new({ moneyTypeTitles = false })
    MoneyType.register = function() error("must not be called") end
    lu.assertTrue(b:addMoney(10, "SUBSIDY"))
    lu.assertIs(game.moneyLog[2].moneyType, MoneyType.OTHER)
end

function T.TestGameAdapter:testEveryMoneyReasonHasATitleInModDesc()
    local f = assert(io.open((os.getenv("RPSIM_SRC") or "FS25_RPSim/src/") .. "../modDesc.xml", "r"))
    local xml = f:read("*a")
    f:close()
    for reason in pairs(RPSimInstructions.MONEY_REASONS) do
        lu.assertStrContains(xml, 'name="rpsim_money_' .. reason .. '"', false)
    end
end

-- T-21: season name looked up in the game's Season table
function T.TestGameAdapter:testSeasonNameComesFromTheSeasonTable()
    Season = { SPRING = 0, SUMMER = 1, AUTUMN = 2, WINTER = 3 }
    lu.assertEquals(RPSimGameAdapter.seasonName(3), "WINTER")
    lu.assertNil(RPSimGameAdapter.seasonName(9))
    lu.assertNil(RPSimGameAdapter.seasonName(nil))
    helpers.fakeGame({ environment = { currentMonotonicDay = 3, dayTime = 0, currentPeriod = 8, currentDayInPeriod = 1,
        daysPerPeriod = 1, currentYear = 1, currentSeason = 2 } })
    lu.assertEquals(RPSimGameAdapter.new():collectCalendar().season, "AUTUMN")
    Season = nil
    lu.assertNil(RPSimGameAdapter.seasonName(3))
end

-- T-22: repair of an own vehicle via Wearable:setDamageAmount(0, true)
function T.TestGameAdapter:testRepairVehicleSetsTheDamageToZero()
    local game = helpers.fakeGame({ vehicles = {
        { uniqueId = "veh_owned", propertyState = VehiclePropertyState.OWNED, sellPrice = 50000, damage = 0.4 },
        { uniqueId = "veh_foreign", propertyState = VehiclePropertyState.OWNED, sellPrice = 1, damage = 0.4, ownerFarmId = 2 },
    } })
    local a = RPSimGameAdapter.new()
    lu.assertTrue(a:repairVehicle("veh_owned"))
    lu.assertEquals(game.vehicles[1].damage, 0)
    local ok, err = a:repairVehicle("veh_unknown")
    lu.assertFalse(ok)
    lu.assertEquals(err, "VEHICLE_NOT_FOUND")
    ok, err = a:repairVehicle("veh_foreign")
    lu.assertFalse(ok)
    lu.assertEquals(err, "NOT_OWN_VEHICLE")
    lu.assertEquals(game.vehicles[2].damage, 0.4)
end

-- T-22: production points as buyers are marked (spec_productionPoint of the owning placeable)
function T.TestGameAdapter:testProductionSellPointsAreMarked()
    SellingStation = { PRICE_CLIMBING = 1, PRICE_FALLING = 2 }
    Utils = { isBitSet = function(v, bit) return v % (2 * bit) >= bit end }
    local function placeable(id, owner)
        return { spec_productionPoint = {}, getUniqueId = function() return id end, getOwnerFarmId = function() return owner end }
    end
    helpers.fakeGame({ stations = { station("Mill"), station("Bakery", { placeable = placeable("plc_bakery", 0) }),
        station("OwnDairy", { placeable = placeable("plc_dairy", 1) }) } })
    local ctx = RPSimGameAdapter.new():collectMarketContext({})
    lu.assertFalse(ctx.sellPoints[1].production)
    lu.assertTrue(ctx.sellPoints[2].production)
    lu.assertFalse(ctx.sellPoints[2].ownedByPlayer)
    lu.assertTrue(ctx.sellPoints[3].ownedByPlayer)
    local doc = RPSimMarketContext.build({ savegameId = "sg", mapName = "m", sellPoints = ctx.sellPoints })
    local byId = {}
    for _, sp in ipairs(doc.sellPoints) do byId[sp.id] = sp end
    lu.assertNil(byId.Mill.production)
    lu.assertTrue(byId.plc_bakery.production)
    lu.assertFalse(byId.plc_bakery.ownedByPlayer)
    lu.assertTrue(byId.plc_dairy.ownedByPlayer)
    SellingStation, Utils = nil, nil
end

return T
