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

-- Roadmap V2 R2-F2: menus and dialogs block a question, a vehicle only when not allowed
function T.TestGameAdapter:testPromptOnlyWithoutMenuAndVehicleRule()
    helpers.fakeGame()
    local guiVisible, inVehicle = false, false
    g_gui = { getIsGuiVisible = function() return guiVisible end }
    g_localPlayer = { getIsInVehicle = function() return inVehicle end }
    local shown = {}
    YesNoDialog = { show = function(callback, target, text, title)
        shown[#shown + 1] = { callback = callback, target = target, text = text, title = title }
    end }
    local a = RPSimGameAdapter.new()
    lu.assertTrue(a:canShowPrompt(false))
    inVehicle = true
    lu.assertFalse(a:canShowPrompt(false))
    lu.assertTrue(a:canShowPrompt(true))
    guiVisible = true
    lu.assertFalse(a:canShowPrompt(true))
    local answer
    lu.assertTrue(a:showYesNo("Text", "Titel", function(yes) answer = yes end))
    lu.assertNil(shown[1].target)
    lu.assertEquals(shown[1].title, "Titel")
    shown[1].callback(true) -- target nil: the game passes the answer as the only argument
    lu.assertTrue(answer)
    YesNoDialog = nil
    lu.assertFalse(a:canShowPrompt(true))
    lu.assertFalse(a:showYesNo("Text", "Titel", function() end))
    g_gui, g_localPlayer = nil, nil
end

-- Roadmap V2 R2-F3: the key help shows the action only while a question waits
function T.TestGameAdapter:testPromptKeyVisibilityFollowsTheQueue()
    local calls = {}
    g_inputBinding = { setActionEventTextVisibility = function(_, id, visible) calls[#calls + 1] = { id, visible } end }
    local a = RPSimGameAdapter.new()
    a:setPromptKeyVisible(true) -- no action registered: nothing to do
    lu.assertEquals(#calls, 0)
    a.promptActionEventId = 7
    a.promptKeyVisible = false
    a:setPromptKeyVisible(true)
    a:setPromptKeyVisible(true)
    a:setPromptKeyVisible(false)
    lu.assertEquals(calls, { { 7, true }, { 7, false } })
    g_inputBinding = nil
end

function T.TestGameAdapter:testPromptActionIsDeclaredInModDesc()
    local fh = io.open((os.getenv("RPSIM_SRC") or "FS25_RPSim/src/"):gsub("src/$", "") .. "modDesc.xml", "r")
    local xml = fh:read("*a")
    fh:close()
    lu.assertStrContains(xml, '<action name="RPSIM_OPEN_PROMPT"')
    lu.assertStrContains(xml, '<actionBinding action="RPSIM_OPEN_PROMPT">')
    lu.assertStrContains(xml, 'name="input_RPSIM_OPEN_PROMPT"')
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

-- modDesc l10n texts live in the mod i18n only; the money popup looks them up in the global g_i18n
function T.TestGameAdapter:testModTextsAreSharedWithTheGlobalI18n()
    local modI18n = { texts = { rpsim_money_TRAINING = "Mitarbeiterschulung", finance_other = "Mod-Text" } }
    local globalI18n = { texts = { finance_other = "Sonstiges" } }
    lu.assertEquals(RPSimGameAdapter.shareModTexts(modI18n, globalI18n), 1)
    lu.assertEquals(globalI18n.texts.rpsim_money_TRAINING, "Mitarbeiterschulung")
    lu.assertEquals(globalI18n.texts.finance_other, "Sonstiges")
    lu.assertEquals(RPSimGameAdapter.shareModTexts(modI18n, globalI18n), 0)
    lu.assertEquals(RPSimGameAdapter.shareModTexts(modI18n, modI18n), 0)
    lu.assertEquals(RPSimGameAdapter.shareModTexts(nil, globalI18n), 0)
    lu.assertEquals(RPSimGameAdapter.shareModTexts(modI18n, nil), 0)
end

function T.TestGameAdapter:testModTextsGoToTheGlobalEnvironmentOfTheModSandbox()
    local globalI18n = { texts = {} }
    local oldI18n, oldMt = g_i18n, getmetatable(_G)
    g_i18n = { texts = { rpsim_money_SUBSIDY = "Förderung" } }
    setmetatable(_G, { __index = { g_i18n = globalI18n } })
    local copied = RPSimGameAdapter.shareModTextsGlobally()
    setmetatable(_G, oldMt)
    g_i18n = oldI18n
    lu.assertEquals(copied, 1)
    lu.assertEquals(globalI18n.texts.rpsim_money_SUBSIDY, "Förderung")
    lu.assertEquals(RPSimGameAdapter.shareModTextsGlobally(), 0)
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

-- Roadmap V2 R2-A6: partial repair down to targetDamage, never raising the damage
function T.TestGameAdapter:testRepairVehicleToTargetDamage()
    local game = helpers.fakeGame({ vehicles = {
        { uniqueId = "veh_worn", propertyState = VehiclePropertyState.OWNED, sellPrice = 50000, damage = 0.6 },
        { uniqueId = "veh_fine", propertyState = VehiclePropertyState.OWNED, sellPrice = 50000, damage = 0.1 },
    } })
    local a = RPSimGameAdapter.new()
    lu.assertTrue(a:repairVehicle("veh_worn", 0.25))
    lu.assertEquals(game.vehicles[1].damage, 0.25)
    lu.assertTrue(a:repairVehicle("veh_fine", 0.25))
    lu.assertEquals(game.vehicles[2].damage, 0.1)
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

-- T-22: vanilla contracts - available ones and the player's own
function T.TestGameAdapter:testMissionsAreExported()
    helpers.fakeGame()
    MissionStatus = { CREATED = 0, PREPARING = 1, RUNNING = 2, FINISHED = 3, DISMISSED = 4 }
    MissionFinishState = { NONE = 0, SUCCESS = 1, FAILED = 2 }
    local function mission(id, status, farmId, finishState)
        return { status = status, farmId = farmId, finishState = finishState, type = { name = "harvestMission" },
            field = { getName = function() return 12 end },
            getUniqueId = function() return id end, getTitle = function() return "Ernte" end,
            getReward = function() return 4500.4 end,
            getNPC = function() return { index = 3, title = "Otto Wendler" } end }
    end
    g_missionManager = { getMissions = function() return {
        mission("m1", MissionStatus.CREATED), mission("m2", MissionStatus.RUNNING, 1),
        mission("m3", MissionStatus.RUNNING, 2), mission("m4", MissionStatus.FINISHED, 1, MissionFinishState.SUCCESS),
        mission("m5", MissionStatus.FINISHED, 1, MissionFinishState.FAILED) } end }
    local list = RPSimGameAdapter.new():collectMissions(50)
    local byId = {}
    for _, m in ipairs(list) do byId[m.uniqueId] = m end
    lu.assertEquals(byId.m1.status, "AVAILABLE")
    lu.assertEquals(byId.m1.field, 12)
    lu.assertEquals(byId.m1.npcTitle, "Otto Wendler")
    lu.assertEquals(byId.m2.status, "RUNNING")
    lu.assertNil(byId.m3) -- another farm's contract
    lu.assertTrue(byId.m4.success)
    lu.assertFalse(byId.m5.success)
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0, missions = list }, RPSimConfig.new())
    lu.assertEquals(doc.missions[1], { uniqueId = "m1", status = "AVAILABLE", title = "Ernte", typeName = "harvestMission",
        field = "12", npcIndex = 3, npcTitle = "Otto Wendler", reward = 4500 })
    lu.assertEquals(#doc.missions, 4)
    g_missionManager, MissionStatus, MissionFinishState = nil, nil, nil
end

-- Roadmap V2 R2-C1 / R2-C2
local function fakeFields(game)
    game.ownership[1] = 1
    FruitType = { UNKNOWN = 0 }
    FieldGroundType = { NONE = 0, SOWN = 5, getValueByType = function() return 9 end }
    local wheat = { minHarvestingGrowthState = 8, maxHarvestingGrowthState = 8, literPerSqm = 0.99,
        getIsWithered = function(_, gs) return gs == 10 end, getIsCut = function(_, gs) return gs == 9 end }
    g_fruitTypeManager = {
        getFruitTypeByIndex = function(_, i) return i == 3 and wheat or nil end,
        getFruitTypeNameByIndex = function(_, i) return i == 3 and "WHEAT" or nil end,
        getFillTypeNameByFruitTypeIndex = function(_, i) return i == 3 and "WHEAT" or nil end,
    }
    local function field(farmland, name, state)
        return { farmland = farmland, areaHa = 4.5, getName = function() return name end,
            getFieldState = function() return state end }
    end
    local base = { isValid = true, weedState = 1, stoneLevel = 0, sprayLevel = 1, limeLevel = 0, plowLevel = 1 }
    local function state(t)
        local s = {}
        for k, v in pairs(base) do s[k] = v end
        for k, v in pairs(t) do s[k] = v end
        return s
    end
    g_fieldManager = { fields = {
        field({ id = 1 }, "1", state({ fruitTypeIndex = 3, growthState = 10, groundType = 5 })),
        field({ id = 2 }, "2", state({ fruitTypeIndex = 3, growthState = 5 })), -- not owned
        field({ id = 1 }, "1b", state({ isValid = false, fruitTypeIndex = 0, growthState = 0 })),
        field(nil, "x", state({ fruitTypeIndex = 0, growthState = 0 })),
    } }
end

function T.TestGameAdapter:testOnlyValidFieldsOfOwnedFarmlandsWithCropDetails()
    local game = helpers.fakeGame()
    fakeFields(game)
    local fields = RPSimGameAdapter.new():collectFields()
    lu.assertEquals(#fields, 1)
    local f = fields[1]
    lu.assertEquals({ f.farmlandId, f.name, f.fruitType, f.growthState, f.groundType }, { 1, "1", "WHEAT", 10, "SOWN" })
    lu.assertTrue(f.withered)
    lu.assertFalse(f.cut)
    lu.assertEquals({ f.fillType, f.litersPerSqm, f.minHarvestingGrowthState }, { "WHEAT", 0.99, 8 })
    g_fieldManager = nil
    lu.assertNil(RPSimGameAdapter.new():collectFields())
end

function T.TestGameAdapter:testFieldRulesFollowTheGameSettings()
    helpers.fakeGame()
    Platform = { gameplay = { usePlowCounter = true, useLimeCounter = false } }
    g_currentMission.missionInfo.plowingRequiredEnabled = true
    g_currentMission.missionInfo.limeRequired = true
    g_currentMission.missionInfo.weedsEnabled = true
    g_currentMission.weedSystem = { getMapHasWeed = function() return true end }
    lu.assertEquals(RPSimGameAdapter.new():collectFieldRules(), { plowingRequired = true, limeRequired = false,
        weedsEnabled = true, stonesEnabled = false })
    Platform = nil
end

function T.TestGameAdapter:testWeatherCarriesTheTemperatureWhenTheGameReportsIt()
    local game = helpers.fakeGame()
    g_currentMission.environment.weather = { getIsRaining = function() return false end,
        getRainFallScale = function() return 0 end, getGroundWetness = function() return 0.3 end,
        getCurrentTemperature = function() return 12.34 end }
    local bridge, fs, paths = realBridge(game)
    bridge:exportFarmFacts()
    local doc = RPSimJson.decode(fs.files[paths.farmFacts])
    lu.assertEquals(doc.weather, { raining = false, rainFallScale = 0, groundWetness = 0.3, temperature = 12.3 })
    -- a failing temperature call keeps the rest of the weather
    g_currentMission.environment.weather.getCurrentTemperature = function() error("no temperature") end
    lu.assertEquals(RPSimGameAdapter.new():collectWeather(), { raining = false, rainFallScale = 0, groundWetness = 0.3 })
end

function T.TestGameAdapter:testWeatherAndFieldsReachFarmFactsFieldsOnlyEveryInterval()
    local game = helpers.fakeGame()
    fakeFields(game)
    g_currentMission.environment.weather = { getIsRaining = function() return true end,
        getRainFallScale = function() return 0.5 end, getGroundWetness = function() return 0.25 end }
    local bridge, fs, paths = realBridge(game)
    bridge:exportFarmFacts()
    local doc = RPSimJson.decode(fs.files[paths.farmFacts])
    lu.assertEquals(doc.weather, { raining = true, rainFallScale = 0.5, groundWetness = 0.25 })
    lu.assertEquals(doc.fields[1].fruitType, "WHEAT")
    -- the next export carries the cached sample until the interval has passed
    g_fieldManager.fields = {}
    bridge:exportFarmFacts()
    lu.assertEquals(#RPSimJson.decode(fs.files[paths.farmFacts]).fields, 1)
    bridge.fieldTimer = bridge.cfg.fieldExportIntervalMs
    bridge:exportFarmFacts()
    lu.assertEquals(#RPSimJson.decode(fs.files[paths.farmFacts]).fields, 0)
    g_fieldManager = nil
end

-- Stock export: silos (also per-farm silos of the map), silo extensions, productions, bunker silos.
local function placeable(id, specs)
    specs.getUniqueId = function() return id end
    specs.getOwnerFarmId = function(self) return self.ownerFarmId or 1 end
    specs.getSellPrice = function() return 0 end
    return specs
end

function T.TestGameAdapter:testAllStoragePlacesAreExportedAsStock()
    helpers.fakeGame({ fillTypes = { [1] = "WHEAT", [2] = "BARLEY", [3] = "CHAFF", [4] = "SILAGE", [5] = "SUGAR" },
        placeables = {
        placeable("silo", { spec_silo = { storages = {
            { ownerFarmId = 1, capacity = 100000, capacities = {}, fillLevels = { [1] = 40000, [2] = 0 } },
            { ownerFarmId = 1, capacity = 50000, capacities = { [2] = 20000 }, fillLevels = { [2] = 7000 } } } } }),
        placeable("ext", { spec_siloExtension = { storage = {
            ownerFarmId = 1, capacity = 60000, fillLevels = { [1] = 5000 } } } }),
        -- per-farm silo of the map: the placeable is not the farm's, storage 1 is
        placeable("mapSilo", { ownerFarmId = 0, spec_silo = { storages = {
            { ownerFarmId = 1, capacity = 10000, fillLevels = { [1] = 1000 } },
            { ownerFarmId = 2, capacity = 10000, fillLevels = { [1] = 9999 } } } } }),
        placeable("sugar", { spec_productionPoint = { productionPoint = { storage = {
            ownerFarmId = 1, capacity = 30000, capacities = { [5] = 10000 }, fillLevels = { [5] = 2500 } } } } }),
        placeable("bunkerFull", { spec_bunkerSilo = { bunkerSilo = {
            state = 2, inputFillType = 3, outputFillType = 4, fillLevel = 80000 } } }),
        placeable("bunkerFilling", { spec_bunkerSilo = { bunkerSilo = {
            state = 0, inputFillType = 3, outputFillType = 4, fillLevel = 12000 } } }),
        placeable("foreignFactory", { ownerFarmId = 2, spec_productionPoint = { productionPoint = { storage = {
            capacity = 1, fillLevels = { [5] = 7 } } } } }),
    } })
    BunkerSilo = { STATE_FILL = 0, STATE_CLOSED = 1, STATE_FERMENTED = 2, STATE_DRAIN = 3 }
    local raw = RPSimGameAdapter.new():collectFarmFacts()
    lu.assertEquals(#raw.silos, 6)
    local doc = RPSimFarmFacts.build(raw)
    lu.assertEquals(doc.assets.storage, {
        { fillType = "BARLEY", amount = 7000, capacity = 120000 },
        { fillType = "CHAFF", amount = 12000, capacity = 0 },
        { fillType = "SILAGE", amount = 80000, capacity = 0 },
        { fillType = "SUGAR", amount = 2500, capacity = 10000 },
        { fillType = "WHEAT", amount = 46000, capacity = 170000 },
    })
    BunkerSilo = nil
end

function T.TestGameAdapter:testFirstExportLogsTheStoragePlaces()
    local game = helpers.fakeGame({ placeables = {
        placeable("silo", { spec_silo = { storages = { { capacity = 100, fillLevels = { [1] = 40 } } } } }) } })
    local bridge = realBridge(game)
    helpers.logs = {}
    bridge:logFirstExport()
    lu.assertEquals(helpers.countLogs("info",
        "Stock: 1 storage places (silos, silo extensions, productions, bunker silos), 1 fill types"), 1)
    lu.assertEquals(helpers.countLogs("info", "Storage silo [SILO]: counted, 1 storages, fill levels: WHEAT=40"), 1)
end

return T
