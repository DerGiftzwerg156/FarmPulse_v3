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

-- Roadmap V3 R3-H1: fields of the game's NPCs (no owner, missions allowed)
function T.TestGameAdapter:testNpcFieldsAreTheFieldsWithoutOwner()
    local game = helpers.fakeGame()
    fakeFields(game)
    local function owned(f, hasOwner, allowed)
        f.getHasOwner = function() return hasOwner end
        f.isMissionAllowed = allowed
    end
    owned(g_fieldManager.fields[1], true, true)
    owned(g_fieldManager.fields[2], false, true)
    owned(g_fieldManager.fields[3], false, true) -- invalid state
    owned(g_fieldManager.fields[4], false, true) -- no farmland
    local npc = RPSimGameAdapter.new():collectNpcFields()
    lu.assertEquals(#npc, 1)
    lu.assertEquals({ npc[1].farmlandId, npc[1].fruitType, npc[1].growthState }, { 2, "WHEAT", 5 })
    owned(g_fieldManager.fields[2], false, false) -- the map forbids missions there
    lu.assertEquals(#RPSimGameAdapter.new():collectNpcFields(), 0)
    g_fieldManager = nil
end

function T.TestGameAdapter:testNpcFieldsFollowTheSwitchAndTheFieldInterval()
    local game = helpers.fakeGame()
    fakeFields(game)
    for _, f in ipairs(g_fieldManager.fields) do
        f.getHasOwner = function() return false end
        f.isMissionAllowed = true
    end
    local bridge, fs, paths = realBridge(game)
    bridge:exportFarmFacts()
    local doc = RPSimJson.decode(fs.files[paths.farmFacts])
    lu.assertEquals(#doc.npcFields, 2)
    bridge.cfg.npcFieldExport = false
    bridge.fieldTimer = bridge.cfg.fieldExportIntervalMs
    bridge:exportFarmFacts()
    lu.assertNil(RPSimJson.decode(fs.files[paths.farmFacts]).npcFields)
    g_fieldManager = nil
end

-- Roadmap V3 R3-H2..H4: own silos and silo extensions only, fill level and free capacity per fill type
local function storage(owner, levels, free)
    local s = { ownerFarmId = owner, levels = levels, free = free, set = {} }
    function s:getFillLevels() return self.levels end
    function s:getFillLevel(i) return self.levels[i] or 0 end
    function s:getFreeCapacity(i) return self.free[i] or 0 end
    function s:setFillLevel(level, i)
        self.free[i] = (self.free[i] or 0) - (level - (self.levels[i] or 0))
        self.levels[i] = level
        self.set[#self.set + 1] = { i, level }
    end
    return s
end

local function tradeGame()
    local a = storage(1, { [1] = 40000, [2] = 0 }, { [1] = 10000, [2] = 50000 })
    local b = storage(1, { [1] = 5000 }, { [1] = 45000 })
    local foreign = storage(2, { [1] = 99999 }, { [1] = 1 })
    local ext = storage(1, { [3] = 2000 }, { [3] = 8000 })
    helpers.fakeGame({ fillTypes = { [1] = "WHEAT", [2] = "STRAW", [3] = "BARLEY", [4] = "SUGAR" }, placeables = {
        placeable("silo", { spec_silo = { storages = { a, b, foreign } } }),
        placeable("ext", { spec_siloExtension = { storage = ext } }),
        -- productions do not count for the trade
        placeable("sugar", { spec_productionPoint = { productionPoint = { storage = storage(1, { [4] = 500 }, { [4] = 1 }) } } }),
    } })
    return a, b, ext
end

function T.TestGameAdapter:testTradeStorageSumsOnlyOwnSilos()
    tradeGame()
    local doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts())
    lu.assertEquals(doc.tradeStorage, {
        { fillType = "BARLEY", amount = 2000, freeCapacity = 8000 },
        { fillType = "STRAW", amount = 0, freeCapacity = 50000 },
        { fillType = "WHEAT", amount = 45000, freeCapacity = 55000 },
    })
end

function T.TestGameAdapter:testStorageTransferInSpreadsOverFreeCapacityWithoutGameBooking()
    local a, b = tradeGame()
    local adapter = RPSimGameAdapter.new()
    local balance = adapter:getBalance()
    lu.assertTrue(adapter:transferStorage("WHEAT", 30000, "IN"))
    lu.assertEquals(a.levels[1], 50000) -- first storage filled up (10,000 free)
    lu.assertEquals(b.levels[1], 25000) -- the rest into the next one
    lu.assertEquals(adapter:getBalance(), balance)
    local ok, why = adapter:transferStorage("WHEAT", 30001, "IN")
    lu.assertFalse(ok)
    lu.assertEquals(why, "NO_CAPACITY")
    lu.assertEquals(a.levels[1], 50000) -- nothing moved
    lu.assertEquals(select(2, adapter:transferStorage("SUGAR", 1, "IN")), "NO_CAPACITY") -- no own silo for it
    lu.assertEquals(select(2, adapter:transferStorage("OATS", 1, "IN")), "UNKNOWN_FILLTYPE")
end

function T.TestGameAdapter:testStorageTransferOutTakesTheGoodsOrNothing()
    local a, b = tradeGame()
    local adapter = RPSimGameAdapter.new()
    local ok, why = adapter:transferStorage("WHEAT", 45001, "OUT")
    lu.assertFalse(ok)
    lu.assertEquals(why, "INSUFFICIENT_STOCK")
    lu.assertEquals(#a.set + #b.set, 0)
    lu.assertTrue(adapter:transferStorage("WHEAT", 42000, "OUT"))
    lu.assertEquals({ a.levels[1], b.levels[1] }, { 0, 3000 })
end

function T.TestGameAdapter:testStorageTransferIsExecutedAsBatchWithTheMoney()
    tradeGame()
    local bridge, fs, paths = realBridge()
    local state = bridge.state
    local instructions = { savegameId = SG, instructions = {
        { instructionId = "st", batchId = "b", type = "STORAGE_TRANSFER", direction = "IN", fillType = "STRAW",
            amount = 8000 },
        { instructionId = "m", batchId = "b", type = "MONEY_TRANSACTION", amount = -960, reason = "GOODS_PURCHASE" },
        { instructionId = "st2", batchId = "c", type = "STORAGE_TRANSFER", direction = "OUT", fillType = "BARLEY",
            amount = 5000 },
        { instructionId = "m2", batchId = "c", type = "MONEY_TRANSACTION", amount = 900, reason = "GOODS_SALE" } } }
    helpers.writeInstructions(fs, paths, instructions)
    local before = bridge.adapter:getBalance()
    bridge:pollInstructions()
    lu.assertEquals(state.processed.st.status, "APPLIED")
    lu.assertEquals(state.processed.m.status, "APPLIED")
    lu.assertEquals(state.processed.st2.message, "INSUFFICIENT_STOCK")
    lu.assertStrContains(state.processed.m2.message, "BATCH_ABORTED")
    lu.assertEquals(bridge.adapter:getBalance(), before - 960)
end

-- Roadmap V3 R3-H5: real contracts on the field of an NPC farmland
local function missionGame(opts)
    opts = opts or {}
    helpers.fakeGame()
    local registered = {}
    local function class(name, available)
        local c = { NAME = name }
        c.canRun = function() return opts.canRun ~= false end
        c.isAvailableForField = function(field, mission) return available and mission == nil and field ~= nil end
        c.new = function(isServer, isClient)
            local m = { isServer = isServer, isClient = isClient, ended = false }
            function m:init(field) self.field = field; return opts.initOk ~= false end
            function m:setDefaultEndDate() self.ended = true end
            function m:getUniqueId() return "mission_" .. name end
            function m:delete() self.deleted = true end
            return m
        end
        return c
    end
    PlowMission = class("plowMission", true)
    StonePickMission = class("stonePickMission", false)
    local types = { PLOWMISSION = { name = "plowMission", classObject = PlowMission },
        STONEPICKMISSION = { name = "stonePickMission", classObject = StonePickMission } }
    g_missionManager = {
        getMissionType = function(_, name) return types[string.upper(name)] end,
        registerMission = function(_, mission, missionType) registered[#registered + 1] = { mission, missionType } end,
        hasFarmReachedMissionLimit = function(_, farmId) return farmId == 1 and opts.limit == true end,
        getMissions = function() return {} end,
    }
    g_client = {}
    local field = { farmland = { id = 7 }, currentMission = opts.currentMission,
        getHasOwner = function() return opts.hasOwner == true end }
    g_fieldManager = { fields = { field } }
    return registered, field
end

local function clearMissionGame()
    PlowMission, StonePickMission, g_missionManager, g_client, g_fieldManager = nil, nil, nil, nil, nil
end

function T.TestGameAdapter:testMissionCreateRegistersAPlowContractLikeTheGame()
    local registered, field = missionGame()
    local ok, err, result = RPSimGameAdapter.new():createMission("PLOW", 7)
    lu.assertTrue(ok, err)
    lu.assertEquals(result, { missionId = "mission_plowMission" })
    lu.assertEquals(#registered, 1)
    local mission, missionType = registered[1][1], registered[1][2]
    lu.assertEquals(missionType.name, "plowMission")
    lu.assertIs(mission.field, field)
    lu.assertTrue(mission.isServer)
    lu.assertTrue(mission.isClient)
    lu.assertTrue(mission.ended)
    clearMissionGame()
end

function T.TestGameAdapter:testMissionCreateRefusesWhatTheGameWouldNotGenerate()
    local cases = {
        { opts = { hasOwner = true }, type = "PLOW" },
        { opts = { currentMission = {} }, type = "PLOW" },
        { opts = { canRun = false }, type = "PLOW" },
        { opts = {}, type = "STONE_PICK" }, -- not available for this field
        { opts = { initOk = false }, type = "PLOW" },
        { opts = {}, type = "PLOW", farmlandId = 8 }, -- no field on that farmland
    }
    for i, c in ipairs(cases) do
        local registered = missionGame(c.opts)
        local ok, why = RPSimGameAdapter.new():createMission(c.type, c.farmlandId or 7)
        lu.assertFalse(ok, "case " .. i)
        lu.assertEquals(why, "NOT_AVAILABLE", "case " .. i)
        lu.assertEquals(#registered, 0, "case " .. i)
        clearMissionGame()
    end
    missionGame()
    lu.assertEquals(select(2, RPSimGameAdapter.new():createMission("HARVEST", 7)), "UNKNOWN_MISSION_TYPE")
    -- another type by its FS25 name (activated after a playtest): classObject of getMissionType
    lu.assertTrue(RPSimGameAdapter.new():createMission("plowMission", 7))
    clearMissionGame()
end

function T.TestGameAdapter:testMissionCreateAcksTheMissionIdAndTheLimitIsExported()
    missionGame({ limit = true })
    local bridge, fs, paths = realBridge()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "mc", type = "MISSION_CREATE", missionType = "PLOW", farmlandId = 7 } } })
    bridge:pollInstructions()
    local ack = RPSimJson.decode(fs.files[paths.instructionsAck])
    lu.assertEquals(ack.acks[1].status, "APPLIED")
    lu.assertEquals(ack.acks[1].result, { missionId = "mission_plowMission" })
    bridge:exportFarmFacts()
    lu.assertTrue(RPSimJson.decode(fs.files[paths.farmFacts]).missionLimitReached)
    clearMissionGame()
    helpers.fakeGame()
    lu.assertNil(RPSimGameAdapter.new():missionLimitReached())
end

-- Roadmap V3 R3-V: shop catalog, used vehicles bought and own vehicles sold
local function storeGame(opts)
    opts = opts or {}
    local game = helpers.fakeGame(opts)
    StoreSpecies = { VEHICLE = 1, PLACEABLE = 2 }
    local items = opts.items or {
        { xmlFilename = "data/vehicles/fendt/vario700.xml", name = "Fendt 700 Vario", species = 1, showInStore = true,
            price = 245000, lifetime = 600, categoryName = "TRACTORSL", isMod = false, power = 200 },
        { xmlFilename = "data/vehicles/krone/trailer.xml", name = "Krone Trailer", species = 1, showInStore = true,
            price = 40000, lifetime = 600, categoryName = "TRAILERS", isMod = false },
        { xmlFilename = "data/vehicles/hidden.xml", name = "Hidden", species = 1, showInStore = false, price = 1 },
        { xmlFilename = "data/placeables/silo.xml", name = "Silo", species = 2, showInStore = true, price = 9 },
        { xmlFilename = "mods/FS25_Brand/tool.xml", name = "Mod Tool", species = 1, showInStore = true, price = 8000,
            lifetime = 300, categoryName = "TOOLS", isMod = true, specsFail = true },
    }
    StoreItemUtil = { loadSpecsFromXML = function(item)
        if item.specsFail then error("no specs") end
        item.specs = { power = item.power }
    end }
    g_storeManager = {
        getItems = function() return items end,
        getItemByXMLFilename = function(_, xml)
            for _, it in ipairs(items) do
                if it.xmlFilename == xml then return it end
            end
            return nil
        end,
    }
    VehicleLoadingState = { OK = 1, ERROR = 2, NO_SPACE = 3 }
    game.loads = {}
    VehicleLoadingData = { new = function()
        local d = {}
        function d:setFilename(f) self.filename = f end
        function d:setLoadingPlace() return opts.space ~= false end
        function d:setPropertyState(p) self.propertyState = p end
        function d:setOwnerFarmId(id) self.ownerFarmId = id end
        function d:load(callback, target)
            self.callback, self.target = callback, target
            game.loads[#game.loads + 1] = self
        end
        return d
    end }
    game.spawned = {}
    --- finishes a pending load like the engine does a few frames later
    function game.finishLoad(state)
        local d = table.remove(game.loads, 1)
        local v = { uniqueId = "veh_new", wear = 0, deleted = false }
        function v:getUniqueId() return self.uniqueId end
        function v:setOperatingTime(ms) self.operatingTime = ms end
        function v:setDamageAmount(a) self.damage = a end
        function v:addWearAmount(a) self.wear = self.wear + a end
        function v:delete() self.deleted = true end
        game.spawned[#game.spawned + 1] = v
        d.callback(d.target, { v }, state or VehicleLoadingState.OK)
        return v, d
    end
    g_currentMission.storeSpawnPlaces, g_currentMission.usedStorePlaces = {}, {}
    return game
end

local function clearStoreGame()
    StoreSpecies, StoreItemUtil, g_storeManager, VehicleLoadingState, VehicleLoadingData = nil, nil, nil, nil, nil
end

local SPAWN = { instructionId = "sp", type = "VEHICLE_SPAWN", storeXmlFilename = "data/vehicles/fendt/vario700.xml",
    ageMonths = 36, operatingHours = 2400, damage = 0.2, wear = 0.3, price = 52000, moneyReason = "VEHICLE_PURCHASE" }

function T.TestGameAdapter:testStoreCatalogListsShopVehiclesWithMotorFlag()
    storeGame()
    local list = RPSimGameAdapter.new():collectStoreVehicles()
    lu.assertEquals(#list, 3) -- hidden item and placeable left out
    lu.assertEquals(list[1].name, "Fendt 700 Vario")
    lu.assertTrue(list[1].motorized)
    lu.assertFalse(list[2].motorized)
    lu.assertNil(list[3].motorized) -- specs could not be read: the backend uses the motorised factor
    lu.assertTrue(list[3].isMod)
    clearStoreGame()
end

function T.TestGameAdapter:testStoreCatalogIsExportedOnceAtTheStartAndCanBeSwitchedOff()
    storeGame()
    local bridge, fs, paths = realBridge()
    bridge.cfg.storeCatalogMaxEntries = 2
    bridge:onSavegameLoaded()
    local ctx = RPSimJson.decode(fs.files[paths.marketContext])
    lu.assertEquals(#ctx.storeVehicles, 2)
    lu.assertEquals(ctx.storeVehicles[1].xmlFilename, "data/vehicles/fendt/vario700.xml") -- sorted, cut after 2
    lu.assertEquals(ctx.storeVehicles[2].xmlFilename, "data/vehicles/krone/trailer.xml")
    local bridge2, fs2, paths2 = realBridge()
    bridge2.cfg.storeCatalogExport = false
    bridge2:onSavegameLoaded()
    lu.assertNil(RPSimJson.decode(fs2.files[paths2.marketContext]).storeVehicles)
    clearStoreGame()
end

function T.TestGameAdapter:testOwnVehiclesCarryNameAndShopXml()
    helpers.fakeGame({ vehicles = { { uniqueId = "veh_1", propertyState = 1, sellPrice = 40000, damage = 0,
        configFileName = "data/vehicles/fendt/vario700.xml", getFullName = function() return "Fendt 700 Vario" end } } })
    local doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts())
    lu.assertEquals(doc.assets.vehicles[1].name, "Fendt 700 Vario")
    lu.assertEquals(doc.assets.vehicles[1].xmlFilename, "data/vehicles/fendt/vario700.xml")
end

function T.TestGameAdapter:testUsedVehicleIsBookedAndAcknowledgedOnlyAfterLoading()
    local game = storeGame({ money = 60000 })
    local bridge, fs, paths = realBridge()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { SPAWN } })
    local result = bridge:pollInstructions()
    lu.assertEquals(result.pending, 1)
    lu.assertEquals(game.farm.money, 60000) -- nothing booked while loading
    lu.assertEquals(#RPSimJson.decode(fs.files[paths.instructionsAck]).acks, 0)
    lu.assertEquals(game.loads[1].filename, "data/vehicles/fendt/vario700.xml")
    lu.assertEquals(game.loads[1].propertyState, VehiclePropertyState.OWNED)
    lu.assertEquals(game.loads[1].ownerFarmId, 1)
    -- the file is read again while loading: no second load
    bridge:pollInstructions()
    lu.assertEquals(#game.loads, 1)
    local v = game.finishLoad()
    lu.assertEquals(v.operatingTime, 2400 * 3600000)
    lu.assertEquals(v.age, 36)
    lu.assertEquals(v.damage, 0.2)
    lu.assertEquals(v.wear, 0.3)
    lu.assertEquals(game.farm.money, 8000)
    local ack = RPSimJson.decode(fs.files[paths.instructionsAck]).acks[1]
    lu.assertEquals(ack.status, "APPLIED")
    lu.assertEquals(ack.result, { vehicleId = "veh_new" })
    clearStoreGame()
end

function T.TestGameAdapter:testUsedVehicleFailuresBookNothing()
    storeGame({ money = 60000, space = false })
    local adapter = RPSimGameAdapter.new()
    local function done() error("no callback expected") end
    lu.assertEquals(select(2, adapter:spawnVehicle(SPAWN, done)), "NO_SPACE")
    local unknown = {}
    for k, val in pairs(SPAWN) do unknown[k] = val end
    unknown.storeXmlFilename = "data/vehicles/none.xml"
    lu.assertEquals(select(2, adapter:spawnVehicle(unknown, done)), "UNKNOWN_STORE_ITEM")
    clearStoreGame()
    storeGame({ money = 50000 })
    lu.assertEquals(select(2, RPSimGameAdapter.new():spawnVehicle(SPAWN, done)), "INSUFFICIENT_FUNDS")
    clearStoreGame()
    -- the engine reports no space only in the callback: the loaded parts are deleted, nothing booked
    local game = storeGame({ money = 60000 })
    local outcome
    lu.assertTrue(RPSimGameAdapter.new():spawnVehicle(SPAWN, function(ok, err) outcome = { ok, err } end))
    local v = game.finishLoad(VehicleLoadingState.NO_SPACE)
    lu.assertEquals(outcome, { false, "NO_SPACE" })
    lu.assertTrue(v.deleted)
    lu.assertEquals(game.farm.money, 60000)
    clearStoreGame()
end

local function ownVehicle(fields)
    local v = { uniqueId = "veh_7", propertyState = 1, sellPrice = 30000, deleted = false }
    for k, val in pairs(fields or {}) do v[k] = val end
    function v:getOwnerFarmId() return self.ownerFarmId or 1 end
    function v:getIsControlled() return self.controlled == true end
    function v:getIsAIActive() return self.ai == true end
    function v:getRootVehicle() return self.root or self end
    function v:getAttachedImplements() return self.implements or {} end
    function v:delete() self.deleted = true end
    return v
end

local function removeGame(v)
    helpers.fakeGame()
    g_currentMission.vehicleSystem.getVehicleByUniqueId = function(_, id)
        if v ~= nil and id == v.uniqueId then return v end
        return nil
    end
end

function T.TestGameAdapter:testOwnVehicleIsRemovedOnlyWhenFreeAndUncoupled()
    local cases = {
        { fields = { ownerFarmId = 2 }, why = "NOT_OWN_VEHICLE" },
        { fields = { propertyState = 2 }, why = "NOT_OWN_VEHICLE" }, -- leased
        { fields = { controlled = true }, why = "VEHICLE_IN_USE" },
        { fields = { ai = true }, why = "VEHICLE_IN_USE" },
        { fields = { implements = { { object = {} } } }, why = "VEHICLE_ATTACHED" },
        { fields = { root = {} }, why = "VEHICLE_ATTACHED" }, -- itself attached to a tractor
    }
    for i, c in ipairs(cases) do
        local v = ownVehicle(c.fields)
        removeGame(v)
        local ok, why = RPSimGameAdapter.new():removeVehicle("veh_7")
        lu.assertFalse(ok, "case " .. i)
        lu.assertEquals(why, c.why, "case " .. i)
        lu.assertFalse(v.deleted, "case " .. i)
    end
    removeGame(nil)
    lu.assertEquals(select(2, RPSimGameAdapter.new():removeVehicle("veh_7")), "VEHICLE_NOT_FOUND")
    local v = ownVehicle()
    removeGame(v)
    lu.assertTrue(RPSimGameAdapter.new():removeVehicle("veh_7"))
    lu.assertTrue(v.deleted)
end

function T.TestGameAdapter:testVehicleSaleRemovesTheVehicleAndBooksTheProceedsInOneBatch()
    local v = ownVehicle()
    removeGame(v)
    local bridge, fs, paths = realBridge()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "rm", batchId = "s", type = "VEHICLE_REMOVE", vehicleId = "veh_7" },
        { instructionId = "pay", batchId = "s", type = "MONEY_TRANSACTION", amount = 32000, reason = "VEHICLE_SALE" },
        { instructionId = "rm2", batchId = "t", type = "VEHICLE_REMOVE", vehicleId = "veh_8" },
        { instructionId = "pay2", batchId = "t", type = "MONEY_TRANSACTION", amount = 1, reason = "VEHICLE_SALE" } } })
    local before = bridge.adapter:getBalance()
    bridge:pollInstructions()
    lu.assertTrue(v.deleted)
    lu.assertEquals(bridge.state.processed.pay.status, "APPLIED")
    lu.assertEquals(bridge.state.processed.rm2.message, "VEHICLE_NOT_FOUND")
    lu.assertStrContains(bridge.state.processed.pay2.message, "BATCH_ABORTED")
    lu.assertEquals(bridge.adapter:getBalance(), before + 32000)
end

return T
