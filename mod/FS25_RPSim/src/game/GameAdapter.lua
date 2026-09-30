-- FS25-specific adapter: the only file that touches FS25 globals. Every API call is wrapped in pcall
-- so an engine change never crashes the savegame; failures degrade to empty/partial exports.
-- luacheck: globals g_currentMission g_farmManager g_farmlandManager g_fillTypeManager g_npcManager
-- luacheck: globals MoneyType FarmManager FarmlandManager VehiclePropertyState SellingStation Utils g_modIsLoaded
-- luacheck: globals FSBaseMission Season g_missionManager MissionStatus MissionFinishState
-- luacheck: globals AnimalType Class AIMessage AIMessageErrorUnknown
-- luacheck: globals g_i18n g_fieldManager g_fruitTypeManager FruitType FieldGroundType Platform
-- luacheck: globals g_gui g_localPlayer YesNoDialog g_inputBinding BunkerSilo g_storeManager
RPSimGameAdapter = {}
RPSimGameAdapter.__index = RPSimGameAdapter

function RPSimGameAdapter.new()
    return setmetatable({}, RPSimGameAdapter)
end

local function safe(fn, default)
    local ok, v = pcall(fn)
    if ok and v ~= nil then
        return v
    end
    return default
end

function RPSimGameAdapter:getFarmId()
    return safe(function() return g_currentMission:getFarmId() end, 1)
end

--- In-game milliseconds since savegame start. Uses the monotonic day counter of the environment,
-- therefore it stands still while the game is paused (needed for game-time based timeouts).
function RPSimGameAdapter:getGameTime()
    return safe(function()
        local env = g_currentMission.environment
        local day = env.currentMonotonicDay or env.currentDay or 0
        return day * RPSimConfig.MS_PER_GAME_DAY + (env.dayTime or 0)
    end, 0)
end

function RPSimGameAdapter:getMapName()
    return safe(function() return g_currentMission.missionInfo.mapTitle end, "unknown")
end

function RPSimGameAdapter:getSavegameIndex()
    return safe(function() return g_currentMission.missionInfo.savegameIndex end, 0)
end

function RPSimGameAdapter:getSavegameDirectory()
    return safe(function() return g_currentMission.missionInfo.savegameDirectory end, nil)
end

local function fillTypeName(index)
    return safe(function() return g_fillTypeManager:getFillTypeNameByIndex(index) end, tostring(index))
end

local function vehicleList()
    return safe(function()
        if g_currentMission.vehicleSystem ~= nil then
            return g_currentMission.vehicleSystem.vehicles
        end
        return g_currentMission.vehicles
    end, {})
end

--- Owned (bought) vehicle? Leased, mission and shop-config vehicles are not farm assets (T-04).
-- VehiclePropertyState.OWNED / LEASED: FS25 Vehicle.lua (addOwnedItem / addLeasedItem); UsedPlus CreditSystem.lua
-- filters collateral the same way.
function RPSimGameAdapter.propertyState(v)
    if VehiclePropertyState == nil then
        return "OWNED"
    end
    local state = v.propertyState
    if state == nil and v.getPropertyState ~= nil then
        state = v:getPropertyState()
    end
    if state == VehiclePropertyState.OWNED then
        return "OWNED"
    elseif state == VehiclePropertyState.LEASED then
        return "LEASED"
    end
    return "OTHER"
end

local function placeableList()
    return safe(function() return g_currentMission.placeableSystem.placeables end, {})
end

--- Stable sell point identifier.
-- TODO(offene-frage): FS25 offers no documented stable id for SellingStation objects. Best effort: the
-- uniqueId of the owning placeable, else the station name. Whether it survives save + reload is checked in the
-- manual test plan (docs/dev/manual-test-plan.md, "Erster Test im echten FS25"), see offene-technische-punkte.md #2.
function RPSimGameAdapter.sellPointId(station)
    return safe(function()
        if station.owningPlaceable ~= nil and station.owningPlaceable.getUniqueId ~= nil then
            return station.owningPlaceable:getUniqueId()
        end
        return station:getName()
    end, tostring(station))
end

--- Production point as buyer (TODO T-22): the station belongs to a placeable with spec_productionPoint (FS25
-- PlaceableProductionPoint.lua: productionPoint.owningPlaceable = self, its unloadingStation can be hidden from the
-- prices menu - so visible ones are sell points). Returns { production, ownedByPlayer }.
function RPSimGameAdapter.productionInfo(station, farmId)
    return safe(function()
        local p = station.owningPlaceable
        if p == nil or p.spec_productionPoint == nil then
            return { production = false, ownedByPlayer = false }
        end
        local owner = p.getOwnerFarmId ~= nil and p:getOwnerFarmId() or nil
        return { production = true, ownedByPlayer = owner == farmId }
    end, { production = false, ownedByPlayer = false })
end

--- Selling stations the player can see in the prices menu (T-10). `isa(SellingStation)` and
-- `hideFromPricesMenu` as in FS25_ProductionDirectSell (PDS_Manager.lua); husbandries set hideFromPricesMenu
-- (FS25 PlaceableHusbandry.lua).
function RPSimGameAdapter.isVisibleSellingStation(station)
    if station == nil or station.isDeleted then
        return false
    end
    local isSelling
    if SellingStation ~= nil and station.isa ~= nil then
        isSelling = station:isa(SellingStation)
    else
        isSelling = station.isSellingPoint == true
    end
    return isSelling and not station.hideFromPricesMenu
end

local function sellingStations()
    local out = {}
    safe(function()
        for _, station in pairs(g_currentMission.storageSystem:getUnloadingStations()) do
            if RPSimGameAdapter.isVisibleSellingStation(station) then
                out[#out + 1] = station
            end
        end
        return true
    end)
    return out
end

--- Price trend of a station (T-10): bit flags SellingStation.PRICE_CLIMBING / PRICE_FALLING, read with
-- Utils.isBitSet as in FS25_ProductionDirectSell (PDS_SellingDialog.lua). Returns CLIMBING, FALLING, STABLE or nil.
function RPSimGameAdapter.pricingTrend(station, fillTypeIndex)
    return safe(function()
        if station.getCurrentPricingTrend == nil or Utils == nil or SellingStation == nil then
            return nil
        end
        local trend = station:getCurrentPricingTrend(fillTypeIndex) or 0
        if SellingStation.PRICE_CLIMBING ~= nil and Utils.isBitSet(trend, SellingStation.PRICE_CLIMBING) then
            return "CLIMBING"
        elseif SellingStation.PRICE_FALLING ~= nil and Utils.isBitSet(trend, SellingStation.PRICE_FALLING) then
            return "FALLING"
        end
        return "STABLE"
    end, nil)
end

--- FS25 calendar (T-08): the game month is the FS25 period. Fields as used by FS25_UsedPlus (CreditSystem.lua,
-- FarmExtension.lua) and the FS25 Farm Dashboard (FarmDashboardDataCollector.lua); dayInPeriod is 1-based
-- (AbstractMission: endDay = currentMonotonicDay + (daysPerPeriod - dayInPeriod)). periodName is the localized
-- name of the current period from g_i18n:formatPeriod() (used this way in SowingMachine / TreePlanter).
function RPSimGameAdapter:collectCalendar()
    return safe(function()
        local env = g_currentMission.environment
        if env == nil or env.currentPeriod == nil then
            return nil
        end
        local name = safe(function() return g_i18n:formatPeriod() end, nil)
        return {
            season = RPSimGameAdapter.seasonName(env.currentSeason),
            period = env.currentPeriod,
            dayInPeriod = env.currentDayInPeriod or 1,
            daysPerPeriod = env.daysPerPeriod or 1,
            year = env.currentYear or 1,
            monotonicDay = env.currentMonotonicDay or env.currentDay or 0,
            periodName = name,
        }
    end, nil)
end

--- Vanilla contracts (TODO T-22): g_missionManager:getMissions() with mission.status (MissionStatus.CREATED /
-- PREPARING / RUNNING / FINISHED / DISMISSED), mission.farmId, mission.finishState == MissionFinishState.SUCCESS,
-- getUniqueId(), getTitle(), getReward() - FS25 AbstractMission.lua / MissionManager.lua; mission.field:getName() and
-- mission:getNPC().title as used by FS25_BetterContracts (scripts/gui.lua). Only missions the player can take
-- (CREATED) or that belong to the player farm; at most maxMissions entries.
function RPSimGameAdapter:collectMissions(maxMissions)
    local out = {}
    safe(function()
        if g_missionManager == nil or MissionStatus == nil then
            return true
        end
        local farmId = self:getFarmId()
        for _, m in ipairs(g_missionManager:getMissions() or {}) do
            if #out >= (maxMissions or 50) then
                break
            end
            safe(function()
                local status
                if m.status == MissionStatus.CREATED then
                    status = "AVAILABLE"
                elseif m.farmId == farmId and (m.status == MissionStatus.PREPARING or m.status == MissionStatus.RUNNING) then
                    status = "RUNNING"
                elseif m.farmId == farmId and m.status == MissionStatus.FINISHED then
                    status = "FINISHED"
                end
                if status == nil then
                    return true
                end
                local npc = safe(function() return m:getNPC() end, nil)
                local success
                if status == "FINISHED" then
                    success = MissionFinishState ~= nil and m.finishState == MissionFinishState.SUCCESS
                end
                out[#out + 1] = {
                    uniqueId = m:getUniqueId(),
                    title = safe(function() return m:getTitle() end, nil),
                    typeName = safe(function() return m.type.name end, nil),
                    field = safe(function() return m.field:getName() end, nil),
                    npcIndex = npc ~= nil and npc.index or nil,
                    npcTitle = npc ~= nil and npc.title or nil,
                    reward = safe(function() return m:getReward() end, nil),
                    status = status,
                    success = success,
                }
                return true
            end)
        end
        return true
    end)
    return out
end

--- Name of the current season (T-21): environment.currentSeason compared with the values of the global Season
-- table (FS25 BeehiveSystem / StonePickMission: environment.currentSeason == Season.WINTER). The name is looked up
-- instead of assumed, so only names that really exist in the game are exported.
--- FS25 loads the l10n texts of modDesc.xml only into the i18n object of the mod environment. Engine code that runs in
-- the global environment (money popup of the HUD, finances page, AI messages) looks titles up in the global g_i18n
-- and shows "Missing 'rpsim_money_TRAINING' in l10n_de.xml". The mod texts are therefore copied into the global
-- text table (the common FS mod pattern: getmetatable(_G).__index.g_i18n.texts[key] = text). Keys the game already
-- knows are never overwritten. Returns the number of copied texts.
function RPSimGameAdapter.shareModTexts(modI18n, globalI18n)
    if type(modI18n) ~= "table" or type(globalI18n) ~= "table" or modI18n == globalI18n
            or type(modI18n.texts) ~= "table" or type(globalI18n.texts) ~= "table" or modI18n.texts == globalI18n.texts then
        return 0
    end
    local copied = 0
    for key, text in pairs(modI18n.texts) do
        if type(key) == "string" and type(text) == "string" and rawget(globalI18n.texts, key) == nil then
            globalI18n.texts[key] = text
            copied = copied + 1
        end
    end
    return copied
end

--- The global g_i18n as seen from the mod environment (whose metatable __index is the global environment).
function RPSimGameAdapter.globalI18n()
    return safe(function()
        local mt = getmetatable(_G)
        local env = mt ~= nil and mt.__index or nil
        return type(env) == "table" and rawget(env, "g_i18n") or nil
    end, nil)
end

function RPSimGameAdapter.shareModTextsGlobally()
    local copied = safe(function() return RPSimGameAdapter.shareModTexts(g_i18n, RPSimGameAdapter.globalI18n()) end, 0)
    if copied > 0 then
        RPSimLog.info("Shared %d mod texts with the global l10n", copied)
    end
    return copied
end

--- Roadmap V2 R2-B1: name of a money type by reverse lookup in the global MoneyType table (the documented fallback of
-- the roadmap; the name field of a money type object is not verified). nil when the object is not in the table, e.g.
-- money types registered at runtime with MoneyType.register (FillTrigger: "finance_purchaseFuel").
function RPSimGameAdapter.moneyTypeName(moneyType)
    if moneyType == nil or MoneyType == nil or type(MoneyType) ~= "table" then
        return nil
    end
    for name, value in pairs(MoneyType) do
        if value == moneyType and type(name) == "string" then
            return name
        end
    end
    return nil
end

--- Roadmap V2 R2-B1: year and period of the current FS25 month (cheap - called for every booking), nil when unknown.
function RPSimGameAdapter:currentPeriod()
    local env = safe(function() return g_currentMission.environment end, nil)
    if env == nil or type(env.currentPeriod) ~= "number" then
        return nil
    end
    return env.currentYear or 1, env.currentPeriod
end

function RPSimGameAdapter.seasonName(current)
    if current == nil or Season == nil or type(Season) ~= "table" then
        return nil
    end
    for name, value in pairs(Season) do
        if value == current and type(name) == "string" then
            return name
        end
    end
    return nil
end

--- Known mods that overlap with RPSim (T-09). Detected via g_modIsLoaded (the mod sandbox hides other mods'
-- globals; FS25_UsedPlus ModCompatibility.lua uses the same check). Nothing is disabled - the backend only warns.
function RPSimGameAdapter.detectMods(names)
    local found = {}
    safe(function()
        if g_modIsLoaded == nil then
            return true
        end
        for _, name in ipairs(names or {}) do
            if g_modIsLoaded[name] then
                found[#found + 1] = name
            end
        end
        return true
    end)
    return found
end

--- Storage object (FS25 Storage.lua) -> { capacity, capacityPerFillType, fillLevels } keyed by fill type name.
-- fillLevels/capacities are keyed by fill type index; `capacities` holds only the per-fill-type limits.
local function storageInfo(storage)
    local levels = {}
    for ftIndex, level in pairs(storage.fillLevels or {}) do
        levels[fillTypeName(ftIndex)] = level
    end
    local perType = {}
    for ftIndex, cap in pairs(storage.capacities or {}) do
        perType[fillTypeName(ftIndex)] = cap
    end
    return { capacity = storage.capacity or 0, capacityPerFillType = perType, fillLevels = levels }
end

--- Bunker silo (FS25 BunkerSilo.lua) as storage: fillLevel is the heap in liters; the game labels it with the
-- input fill type while filling (CHAFF) and with the output fill type once closed (SILAGE), see BunkerSilo:update.
local function bunkerSiloInfo(bunker)
    local ftIndex = bunker.inputFillType
    if BunkerSilo ~= nil and bunker.state ~= BunkerSilo.STATE_FILL then
        ftIndex = bunker.outputFillType
    end
    return { capacity = 0, fillLevels = { [fillTypeName(ftIndex)] = bunker.fillLevel or 0 } }
end

--- Storage places of a placeable that hold goods of farm `farmId`, as raw entries for RPSimStorage.
-- FS25 sources: PlaceableSilo (spec_silo.storages; with storages#perFarm the silo belongs to the map but each
-- storage to one farm, storage.ownerFarmId), PlaceableSiloExtension (spec_siloExtension.storage),
-- PlaceableProductionPoint (spec_productionPoint.productionPoint.storage, inputs and outputs) and
-- PlaceableBunkerSilo (spec_bunkerSilo.bunkerSilo).
function RPSimGameAdapter.storageSources(p, farmId)
    local owner = safe(function() return p:getOwnerFarmId() end, nil)
    local out = {}
    local function add(kind, storages)
        if #storages > 0 then
            out[#out + 1] = {
                uniqueId = safe(function() return p:getUniqueId() end, nil),
                descriptor = { kind = kind, hasHusbandrySpec = p.spec_husbandryAnimals ~= nil,
                    hasObjectStorageSpec = p.spec_objectStorage ~= nil },
                storages = storages,
            }
        end
    end
    local function ownStorage(storage)
        return storage ~= nil and (storage.ownerFarmId or owner) == farmId
    end
    if p.spec_silo ~= nil then
        local storages = {}
        for _, storage in ipairs(p.spec_silo.storages or {}) do
            if ownStorage(storage) then
                storages[#storages + 1] = storageInfo(storage)
            end
        end
        add("SILO", storages)
    end
    if p.spec_siloExtension ~= nil and ownStorage(p.spec_siloExtension.storage) then
        add("SILO_EXTENSION", { storageInfo(p.spec_siloExtension.storage) })
    end
    if owner ~= farmId then
        return out
    end
    local pp = p.spec_productionPoint ~= nil and p.spec_productionPoint.productionPoint or nil
    if pp ~= nil and pp.storage ~= nil then
        add("PRODUCTION", { storageInfo(pp.storage) })
    end
    if p.spec_bunkerSilo ~= nil and p.spec_bunkerSilo.bunkerSilo ~= nil then
        add("BUNKER_SILO", { bunkerSiloInfo(p.spec_bunkerSilo.bunkerSilo) })
    end
    return out
end

function RPSimGameAdapter:collectFarmFacts()
    local farmId = self:getFarmId()
    local raw = { vehicles = {}, leasedVehicles = {}, placeables = {}, farmland = {}, animals = {}, husbandries = {},
        silos = {},
        prices = {}, calendar = self:collectCalendar(), missions = self:collectMissions(50),
        weather = self:collectWeather() }
    local farm = safe(function() return g_farmManager:getFarmById(farmId) end, nil)
    raw.balance = safe(function() return farm.money end, 0)
    raw.vanillaLoan = safe(function() return farm.loan end, 0)

    for _, v in pairs(vehicleList()) do
        safe(function()
            if v:getOwnerFarmId() == farmId and v.getSellPrice ~= nil then
                local state = RPSimGameAdapter.propertyState(v)
                if state == "OWNED" then
                    local damage = v.getDamageAmount ~= nil and v:getDamageAmount() or 0
                    raw.vehicles[#raw.vehicles + 1] = { uniqueId = v:getUniqueId(), value = v:getSellPrice(),
                        damage = damage }
                elseif state == "LEASED" then
                    -- Leasing costs per vehicle have no documented API yet (manual test plan) - list only.
                    raw.leasedVehicles[#raw.leasedVehicles + 1] = { uniqueId = v:getUniqueId() }
                end
            end
            return true
        end)
    end

    for _, p in pairs(placeableList()) do
        -- storages first: a per-farm silo of the map is not the farm's placeable, its storage is
        safe(function()
            for _, entry in ipairs(RPSimGameAdapter.storageSources(p, farmId)) do
                raw.silos[#raw.silos + 1] = entry
            end
            return true
        end)
        safe(function()
            if p:getOwnerFarmId() ~= farmId then
                return true
            end
            raw.placeables[#raw.placeables + 1] = { uniqueId = p:getUniqueId(), value = p:getSellPrice() }
            -- animals: husbandries
            if p.spec_husbandryAnimals ~= nil then
                local count, value, typeName = 0, 0, "UNKNOWN"
                for _, cluster in pairs(p:getClusters() or {}) do
                    local n = cluster:getNumAnimals()
                    count = count + n
                    local sub = g_currentMission.animalSystem:getSubTypeByIndex(cluster:getSubTypeIndex())
                    typeName = sub ~= nil and sub.name or typeName
                    local unit = cluster.getSellPrice ~= nil and cluster:getSellPrice() or 0
                    value = value + unit * n
                end
                if p.spec_husbandryAnimals.animalType ~= nil then
                    typeName = p.spec_husbandryAnimals.animalType.name or typeName
                end
                raw.animals[#raw.animals + 1] = { husbandryUniqueId = p:getUniqueId(), type = string.upper(typeName),
                    count = count, estimatedValue = value }
                local state = RPSimGameAdapter.husbandryState(p)
                if state ~= nil then
                    raw.husbandries[#raw.husbandries + 1] = state
                end
            end
            return true
        end)
    end

    safe(function()
        for _, fl in pairs(g_farmlandManager:getFarmlands()) do
            if g_farmlandManager:getFarmlandOwner(fl.id) == farmId then
                raw.farmland[#raw.farmland + 1] = { farmlandId = fl.id, hectares = fl.areaInHa or 0, price = fl.price or 0 }
            end
        end
        return true
    end)

    for _, station in ipairs(sellingStations()) do
        safe(function()
            local id = RPSimGameAdapter.sellPointId(station)
            for ftIndex, accepted in pairs(station.acceptedFillTypes or {}) do
                if accepted then
                    -- currentPrice = effective price the player gets right now (incl. active RPSim events).
                    local price = station:getEffectiveFillTypePrice(ftIndex)
                    raw.prices[#raw.prices + 1] = { sellPoint = id, fillType = fillTypeName(ftIndex), pricePerLiter = price,
                        trend = RPSimGameAdapter.pricingTrend(station, ftIndex) }
                end
            end
            return true
        end)
    end
    return raw
end

--- FS25 NPC of a farmland (T-21). Farmland.lua sets self.npcIndex (g_npcManager:getRandomIndex() or the NPC named
-- in the map XML); BetterContracts (scripts/options.lua) resolves it with g_npcManager:getNPCByIndex(npcIndex) and
-- shows npc.title. npc.name is the key of getNPCByName.
function RPSimGameAdapter.farmlandNpc(fl)
    if fl.npcIndex == nil or g_npcManager == nil or g_npcManager.getNPCByIndex == nil then
        return nil
    end
    local npc = g_npcManager:getNPCByIndex(fl.npcIndex)
    if npc == nil then
        return nil
    end
    return { index = npc.index or fl.npcIndex, name = npc.name, title = npc.title }
end

function RPSimGameAdapter:collectMarketContext(conflictMods)
    local raw = { mapName = self:getMapName(), sellPoints = {}, fillTypes = {}, farmlands = {},
        detectedMods = RPSimGameAdapter.detectMods(conflictMods) }
    local seenFillTypes = {}
    for _, station in ipairs(sellingStations()) do
        safe(function()
            local accepted = {}
            for ftIndex, ok in pairs(station.acceptedFillTypes or {}) do
                if ok then
                    local name = fillTypeName(ftIndex)
                    accepted[#accepted + 1] = name
                    if not seenFillTypes[name] then
                        seenFillTypes[name] = true
                        raw.fillTypes[#raw.fillTypes + 1] = name
                    end
                end
            end
            local info = RPSimGameAdapter.productionInfo(station, self:getFarmId())
            raw.sellPoints[#raw.sellPoints + 1] = { id = RPSimGameAdapter.sellPointId(station),
                name = station:getName(), acceptedFillTypes = accepted, production = info.production,
                ownedByPlayer = info.ownedByPlayer }
            return true
        end)
    end
    safe(function()
        for _, fl in pairs(g_farmlandManager:getFarmlands()) do
            -- T-11: showOnFarmlandsScreen / defaultFarmProperty from FS25 Farmland.lua (hidden = village, roads ...)
            raw.farmlands[#raw.farmlands + 1] = { farmlandId = fl.id, hectares = fl.areaInHa or 0, price = fl.price or 0,
                ownerFarmId = g_farmlandManager:getFarmlandOwner(fl.id) or 0,
                showOnFarmlandsScreen = fl.showOnFarmlandsScreen ~= false,
                defaultFarmProperty = fl.defaultFarmProperty == true,
                npc = safe(function() return RPSimGameAdapter.farmlandNpc(fl) end, nil) }
        end
        return true
    end)
    return raw
end

--- Current balance of the player farm (farm.money, as read in collectFarmFacts and by FS25_UsedPlus).
function RPSimGameAdapter:getBalance()
    local farmId = self:getFarmId()
    return safe(function() return g_farmManager:getFarmById(farmId).money end, nil)
end

--- Batch pre-check (T-03): debits are refused when the balance does not cover them, so the farm never goes
-- into the red through a tool booking. Unknown balance => refuse debits (never book blindly).
function RPSimGameAdapter:checkBatchFunds(items)
    return RPSimInstructions.checkFunds(self:getBalance() or 0, items)
end

--- Money type of a booking reason (T-21): MoneyType.register(statistic, "rpsim_money_<REASON>") - FS25
-- FillTrigger.lua registers MoneyType.register("other", "finance_purchaseFuel") the same way. The statistic defaults
-- to "other" (the only name verified in the FS25 code); cfg.moneyTypeStatistics may name another one. Registered
-- once per reason; any failure falls back to MoneyType.OTHER.
function RPSimGameAdapter:moneyTypeFor(reason)
    self.moneyTypes = self.moneyTypes or {}
    if self.moneyTypes[reason] ~= nil then
        return self.moneyTypes[reason]
    end
    local moneyType = MoneyType ~= nil and MoneyType.OTHER or nil
    local cfg = self.config or {}
    if cfg.moneyTypeTitles ~= false and type(reason) == "string" and MoneyType ~= nil and MoneyType.register ~= nil then
        local statistic = (cfg.moneyTypeStatistics or {})[reason] or "other"
        local ok, registered = pcall(MoneyType.register, statistic, "rpsim_money_" .. reason)
        if ok and registered ~= nil then
            moneyType = registered
        else
            RPSimLog.warning("MoneyType.register(%s, rpsim_money_%s) failed - booking as OTHER", statistic, reason)
        end
    end
    if moneyType ~= nil then
        self.moneyTypes[reason] = moneyType
    end
    return moneyType
end

--- Roadmap V2 R2-B1: while the tool books, bookingReason is set so the Farm.changeBalance hook records the amount as
-- RPSIM_<REASON> instead of the FS25 money type.
function RPSimGameAdapter:addMoney(amount, reason, note)
    self.bookingReason = reason
    local ok, err = pcall(function()
        g_currentMission:addMoney(amount, self:getFarmId(), self:moneyTypeFor(reason), true, true)
    end)
    self.bookingReason = nil
    if not ok then
        return false, tostring(err)
    end
    RPSimLog.info("Money %s %d (%s)", reason, amount, tostring(note or ""))
    return true
end

--- Repair of an own vehicle paid by the maintenance contract (TODO T-22): Wearable:setDamageAmount(0, true) - the
-- part of FS25 Wearable:repairVehicle() that removes the damage; repairVehicle() itself would also book the repair
-- price (addMoney(-getRepairPrice(), ..., MoneyType.VEHICLE_REPAIR)), which the contract already covers.
-- Roadmap V2 R2-A6: targetDamage (0..1, default 0) repairs only down to that damage; a repair never raises the damage
-- (getDamageAmount() as in the export).
function RPSimGameAdapter:repairVehicle(uniqueId, targetDamage)
    local farmId = self:getFarmId()
    local target
    for _, v in pairs(vehicleList()) do
        local ok, id = pcall(function() return v:getUniqueId() end)
        if ok and id == uniqueId then
            target = v
            break
        end
    end
    if target == nil then
        return false, "VEHICLE_NOT_FOUND"
    end
    local ok, err = pcall(function()
        if target:getOwnerFarmId() ~= farmId then
            error("NOT_OWN_VEHICLE")
        end
        if target.setDamageAmount == nil then
            error("NOT_WEARABLE")
        end
        local damage = targetDamage or 0
        if target.getDamageAmount ~= nil then
            damage = math.min(damage, target:getDamageAmount())
        end
        target:setDamageAmount(damage, true)
    end)
    if not ok then
        local msg = tostring(err)
        return false, msg:match("NOT_OWN_VEHICLE") and "NOT_OWN_VEHICLE" or msg:match("NOT_WEARABLE") and "NOT_WEARABLE" or msg
    end
    RPSimLog.info("Vehicle %s repaired (target damage %.2f)", tostring(uniqueId), targetDamage or 0)
    return true
end

--- In-game notification (TODO T-21): g_currentMission:addIngameNotification(FSBaseMission.INGAME_NOTIFICATION_*, text),
-- the pattern of FS25_MarketDynamics (MarketDynamics.lua, FuturesMarket.lua).
--- Roadmap V2 R2-A7: state of one husbandry, the way the game computes it itself (all functions are placeable functions
-- registered by the husbandry specializations, FS25 animals/husbandry/placeables):
--   health       mean of cluster.health over all clusters (PlaceableHusbandryAnimals:updateInfo, shown as "%d %")
--   productivity getGlobalProductionFactor() * getProductionFactor(), not for AnimalType.HORSE / PIG
--                (PlaceableHusbandryAnimals:getConditionInfos)
--   food         getTotalFood() / getFoodCapacity() (PlaceableHusbandryFood)
--   conditions   title + ratio of every getConditionInfos() entry (water, straw, slurry, milk, productivity ...)
-- nil when the husbandry has no animals specialization; a missing part is left out.
function RPSimGameAdapter.husbandryState(p)
    return safe(function()
        if p.spec_husbandryAnimals == nil then
            return nil
        end
        local clusters = p:getClusters() or {}
        local health, n = 0, 0
        for _, cluster in pairs(clusters) do
            health = health + (cluster.health or 0)
            n = n + 1
        end
        local state = { husbandryUniqueId = p:getUniqueId(), health = n > 0 and health / n or 0, conditions = {} }
        local typeIndex = p.getAnimalTypeIndex ~= nil and p:getAnimalTypeIndex() or nil
        local noProductivity = AnimalType ~= nil and (typeIndex == AnimalType.HORSE or typeIndex == AnimalType.PIG)
        if not noProductivity and p.getGlobalProductionFactor ~= nil and p.getProductionFactor ~= nil then
            state.productivity = p:getGlobalProductionFactor() * p:getProductionFactor()
        end
        if p.getTotalFood ~= nil and p.getFoodCapacity ~= nil then
            local capacity = p:getFoodCapacity()
            state.food = capacity ~= nil and capacity > 0 and p:getTotalFood() / capacity or 0
        else
            state.food = 0
        end
        if p.getConditionInfos ~= nil then
            for _, info in ipairs(p:getConditionInfos() or {}) do
                if type(info.title) == "string" and type(info.ratio) == "number" then
                    state.conditions[#state.conditions + 1] = { title = info.title, ratio = info.ratio }
                end
            end
        end
        return state
    end, nil)
end

-- ------------------------------------------------------------------------ Roadmap V2 R2-A: employees as helpers

--- "Schulungen": the vehicle a helper job drives and its FS25 shop categories. Every vehicle job type (AIJobFieldWork,
-- AIJobGoTo, AIJobDeliver, AIJobLoadAndDeliver, AIJobConveyor; dump ai/jobs/*.lua) keeps it in
-- job.vehicleParameter:getVehicle() (AIParameterVehicle); the store item of vehicle.configFileName
-- (g_storeManager:getItemByXMLFilename, LUADOC script/Shop/StoreManager.md) carries categoryNames and categoryName (the
-- first entry, upper case). Returns { farmId, categories = { ... }, name } or nil without a vehicle.
function RPSimGameAdapter:jobVehicleInfo(job)
    return safe(function()
        local vehicle = job.vehicleParameter ~= nil and job.vehicleParameter:getVehicle() or nil
        if vehicle == nil then
            return nil
        end
        local categories = {}
        local item = g_storeManager:getItemByXMLFilename(vehicle.configFileName)
        if item ~= nil then
            for _, c in ipairs(item.categoryNames or {}) do
                categories[#categories + 1] = string.upper(c)
            end
            if #categories == 0 and item.categoryName ~= nil then
                categories[1] = string.upper(item.categoryName)
            end
        end
        return { farmId = safe(function() return vehicle:getOwnerFarmId() end, nil), categories = categories,
            name = safe(function() return vehicle:getFullName() end, nil) }
    end, nil)
end

--- Running helper jobs of the player farm: AISystem:getActiveJobs() (FS25 ai/AISystem.lua), job.jobId (set by
-- AISystem:startJob via AIJob:setId), job.startedFarmId (AIJob:start), job:getTitle() (vehicle name for field work),
-- "Schulungen": the shop categories of the vehicle (jobVehicleInfo).
function RPSimGameAdapter:collectAIJobs()
    local farmId = self:getFarmId()
    local jobs = {}
    local list = safe(function() return g_currentMission.aiSystem:getActiveJobs() end, {})
    for _, job in ipairs(list) do
        safe(function()
            if job.jobId ~= nil and job.startedFarmId == farmId then
                local title = safe(function() return job:getTitle() end, nil)
                local info = self:jobVehicleInfo(job)
                jobs[#jobs + 1] = { jobId = job.jobId, title = title,
                    categories = info ~= nil and info.categories or {} }
            end
            return true
        end)
    end
    table.sort(jobs, function(a, b) return a.jobId < b.jobId end)
    return jobs
end

--- Roadmap V2 R2-C1: state of the fields on farmlands of the player farm. Sources (FS25 dump / LUADOC):
-- g_fieldManager.fields (FieldManager.lua), field.farmland (Farmland, set by FieldManager:loadMapData),
-- field:getName() / field:getFieldState() (AbstractFieldMission.lua, PlowMission.lua), FieldState fields
-- (FieldState.lua), g_fruitTypeManager:getFruitTypeNameByIndex / getFruitTypeByIndex /
-- getFillTypeNameByFruitTypeIndex (FruitTypeManager), FruitTypeDesc min/maxHarvestingGrowthState, literPerSqm,
-- getIsWithered / getIsCut (FruitTypeDesc). Fields without a valid state are left out. nil = no field manager.
function RPSimGameAdapter:collectFields()
    local farmId = self:getFarmId()
    local fields = safe(function() return g_fieldManager.fields end, nil)
    if fields == nil then
        return nil
    end
    local list = {}
    for _, field in pairs(fields) do
        safe(function()
            local farmland = field.farmland
            if farmland == nil or g_farmlandManager:getFarmlandOwner(farmland.id) ~= farmId then
                return true
            end
            local state = field:getFieldState()
            if state == nil or not state.isValid then
                return true
            end
            local e = { farmlandId = farmland.id, name = field:getName(), hectares = field.areaHa,
                growthState = state.growthState, weedState = state.weedState, stoneLevel = state.stoneLevel,
                sprayLevel = state.sprayLevel, limeLevel = state.limeLevel, plowLevel = state.plowLevel,
                groundType = RPSimGameAdapter.groundTypeName(state.groundType) }
            local index = state.fruitTypeIndex
            if index ~= nil and (FruitType == nil or index ~= FruitType.UNKNOWN) then
                local desc = g_fruitTypeManager:getFruitTypeByIndex(index)
                if desc ~= nil then
                    e.fruitType = g_fruitTypeManager:getFruitTypeNameByIndex(index)
                    e.minHarvestingGrowthState = desc.minHarvestingGrowthState
                    e.maxHarvestingGrowthState = desc.maxHarvestingGrowthState
                    e.withered = safe(function() return desc:getIsWithered(state.growthState) == true end, nil)
                    e.cut = safe(function() return desc:getIsCut(state.growthState) == true end, nil)
                    e.fillType = safe(function() return g_fruitTypeManager:getFillTypeNameByFruitTypeIndex(index) end, nil)
                    e.litersPerSqm = desc.literPerSqm
                end
            end
            list[#list + 1] = e
            return true
        end)
    end
    return list
end

--- Name of a field ground type in the global FieldGroundType table (reverse lookup, numbers only), nil if unknown.
function RPSimGameAdapter.groundTypeName(value)
    if value == nil or FieldGroundType == nil or type(FieldGroundType) ~= "table" then
        return nil
    end
    for name, v in pairs(FieldGroundType) do
        if v == value and type(name) == "string" and type(v) == "number" then
            return name
        end
    end
    return nil
end

--- R2-C: game settings of the soil mechanics. The game shows "needs plowing" / "needs lime" only with
-- Platform.gameplay.usePlowCounter / useLimeCounter and missionInfo.plowingRequiredEnabled / limeRequired, weeds and
-- stones only when the map has them and missionInfo.weedsEnabled / stonesEnabled (MapOverlayGenerator.lua).
function RPSimGameAdapter:collectFieldRules()
    return safe(function()
        local info = g_currentMission.missionInfo
        local gameplay = Platform ~= nil and Platform.gameplay or {}
        local weeds = g_currentMission.weedSystem
        local stones = g_currentMission.stoneSystem
        return {
            plowingRequired = gameplay.usePlowCounter ~= false and info.plowingRequiredEnabled == true,
            limeRequired = gameplay.useLimeCounter ~= false and info.limeRequired == true,
            weedsEnabled = weeds ~= nil and weeds:getMapHasWeed() == true and info.weedsEnabled == true,
            stonesEnabled = stones ~= nil and stones:getMapHasStones() == true and info.stonesEnabled == true,
        }
    end, nil)
end

--- R2-C2: current weather (environment.weather:getIsRaining / getRainFallScale / getGroundWetness, used e.g. by
-- PlaceableSolarPanels, Wipers and Wheels). nil when the weather is not available.
-- Hof-Tablet: temperature in °C (weather:getCurrentTemperature, used by VehicleSystem, Washable and the Enterable
-- outside temperature display); left out when the call fails, the rest of the weather stays.
function RPSimGameAdapter:collectWeather()
    return safe(function()
        local weather = g_currentMission.environment.weather
        return { raining = weather:getIsRaining() == true, rainFallScale = weather:getRainFallScale(),
            groundWetness = weather:getGroundWetness(),
            temperature = safe(function() return weather:getCurrentTemperature() end, nil) }
    end, nil)
end

--- R2-A3: g_currentMission.maxNumHirables limits the helpers (AISystem:getAILimitedReached). The original value is
-- remembered the first time and written back when the strict mode is off or the map is unloaded.
function RPSimGameAdapter:applyHelperLimit(workforce)
    return safe(function()
        if self.originalMaxHirables == nil then
            self.originalMaxHirables = g_currentMission.maxNumHirables
        end
        if self.originalMaxHirables == nil then
            return false
        end
        g_currentMission.maxNumHirables = RPSimWorkforce.helperLimit(workforce, self.originalMaxHirables)
        return true
    end, false)
end

function RPSimGameAdapter:restoreHelperLimit()
    if self.originalMaxHirables ~= nil then
        safe(function()
            g_currentMission.maxNumHirables = self.originalMaxHirables
            return true
        end)
    end
end

--- Own AI message (class pattern of AIMessageErrorUnknown: Class(x, AIMessage) with getI18NText), kept in
-- RPSimGameAdapter[field] and registered with g_currentMission.aiMessageManager:registerMessage (the manager
-- AIJobStopEvent uses). Returns true when registered.
local function registerOwnMessage(field, name, textKey)
    return pcall(function()
        if RPSimGameAdapter[field] == nil then
            local cls = {}
            local mt = Class(cls, AIMessage)
            function cls.new(customMt)
                return AIMessage.new(customMt or mt)
            end
            function cls:getI18NText()
                return g_i18n:getText(textKey)
            end
            RPSimGameAdapter[field] = cls
        end
        local registered = g_currentMission.aiMessageManager:registerMessage(name, RPSimGameAdapter[field])
        if registered == nil then
            error("registerMessage refused " .. name)
        end
    end)
end

--- R2-A5: own AI message "%s legt die Arbeit nieder".
function RPSimGameAdapter:registerStrikeMessage()
    local ok = registerOwnMessage("StrikeMessage", "RPSIM_STRIKE", "rpsim_ai_strike")
    self.strikeMessageRegistered = ok
    if not ok then
        RPSimLog.warning("Strike message not registered - strikes stop helpers with the generic message")
    end
    return ok
end

--- R2-A3: own AI message "Kein freier Maschinenführer (strenger Modus)" for a helper stopped over the limit.
function RPSimGameAdapter:registerHelperLimitMessage()
    local ok = registerOwnMessage("HelperLimitMessage", "RPSIM_HELPER_LIMIT", "rpsim_ai_helperLimit")
    self.helperLimitMessageRegistered = ok
    if not ok then
        RPSimLog.warning("Helper limit message not registered - the limit stops helpers with the generic message")
    end
    return ok
end

--- Helpers are started and stopped by the server (AISystem:startJob / stopJob); a client only mirrors them.
function RPSimGameAdapter:isServer()
    return safe(function() return g_currentMission:getIsServer() == true end, false)
end

--- R2-A3: stops a helper started over the strict limit by a mod that bypasses maxNumHirables (Courseplay, AutoDrive).
-- AISystem:stopJob(job, aiMessage) as for the strike; without the own message the generic one plus a notification.
function RPSimGameAdapter:stopHelperLimitJob(job, limit)
    local ok, err = pcall(function()
        if job.jobId ~= nil and g_currentMission.aiSystem:getJobById(job.jobId) == nil then
            return -- already stopped
        end
        if self.helperLimitMessageRegistered and RPSimGameAdapter.HelperLimitMessage ~= nil then
            g_currentMission.aiSystem:stopJob(job, RPSimGameAdapter.HelperLimitMessage.new())
        else
            g_currentMission.aiSystem:stopJob(job, AIMessageErrorUnknown.new())
        end
        self:notify(string.format("FarmPulse: Kein freier Maschinenführer – im strengen Modus fahren höchstens %d "
            .. "Helfer. Der zuletzt gestartete Helfer wurde angehalten.", limit or 0), "CRITICAL")
    end)
    if not ok then
        RPSimLog.warning("Could not stop the helper over the limit: %s", tostring(err))
    end
    return ok
end

--- R2-A5: stops the helper of a striking employee with AISystem:stopJob(job, aiMessage). Without the own message the
-- job stops with AIMessageErrorUnknown and a notification names the reason.
function RPSimGameAdapter:stopStrikingJob(jobId, name)
    local ok, err = pcall(function()
        local job = g_currentMission.aiSystem:getJobById(jobId)
        if job == nil then
            return
        end
        if self.strikeMessageRegistered and RPSimGameAdapter.StrikeMessage ~= nil then
            g_currentMission.aiSystem:stopJob(job, RPSimGameAdapter.StrikeMessage.new())
        else
            g_currentMission.aiSystem:stopJob(job, AIMessageErrorUnknown.new())
            self:notify(string.format("FarmPulse: %s streikt und hat die Arbeit niedergelegt.", tostring(name)),
                "CRITICAL")
        end
    end)
    if not ok then
        RPSimLog.warning("Could not stop the helper of %s: %s", tostring(name), tostring(err))
    end
    return ok
end

function RPSimGameAdapter:notify(text, level)
    local ok, err = pcall(function()
        local kind = FSBaseMission ~= nil
            and (FSBaseMission["INGAME_NOTIFICATION_" .. tostring(level or "INFO")] or FSBaseMission.INGAME_NOTIFICATION_INFO)
            or nil
        g_currentMission:addIngameNotification(kind, text)
    end)
    if not ok then
        return false, tostring(err)
    end
    return true
end

-- ------------------------------------------------------------------ Roadmap V2 R2-F: questions in the game

--- R2-F2: a question is only shown while no menu or dialog is open (Gui:getIsGuiVisible = current screen or any
-- dialog, LUADOC script/GUI/Gui.md) and - unless allowed - not while the player sits in a vehicle
-- (g_localPlayer:getIsInVehicle, used in the FS25 code).
function RPSimGameAdapter:canShowPrompt(inVehicleAllowed)
    local ok, can = pcall(function()
        if g_gui == nil or YesNoDialog == nil or YesNoDialog.show == nil then
            return false
        end
        if g_gui:getIsGuiVisible() then
            return false
        end
        if not inVehicleAllowed and g_localPlayer ~= nil and g_localPlayer:getIsInVehicle() then
            return false
        end
        return true
    end)
    return ok and can == true
end

--- R2-F2: YesNoDialog.show(callback, target, text, title) as in PalletFiller / PlaceableBuyable; with target nil the
-- callback gets the answer as its only argument.
function RPSimGameAdapter:showYesNo(text, title, callback)
    local ok, err = pcall(function()
        YesNoDialog.show(function(yes)
            callback(yes == true)
        end, nil, text, title)
    end)
    if not ok then
        return false, tostring(err)
    end
    return true
end

--- R2-F3: the key help shows the action only while a question waits (setActionEventTextVisibility is used throughout
-- the FS25 code). The event id comes from RPSim.registerPromptAction.
function RPSimGameAdapter:setPromptKeyVisible(visible)
    if self.promptActionEventId == nil or self.promptKeyVisible == visible then
        return
    end
    self.promptKeyVisible = visible
    pcall(function()
        g_inputBinding:setActionEventTextVisibility(self.promptActionEventId, visible)
    end)
end

--- Farmland ownership transfer between the player farm and "no owner" (NPC owners only exist in the tool).
-- FarmlandManager:setLandOwnership(farmlandId, farmId) - FS25 FarmlandManager.lua:447: returns false for an
-- invalid farmland id or NOT_BUYABLE_FARM_ID, otherwise sets the owner and publishes FARMLAND_OWNER_CHANGED.
-- Whether missions and the field menu follow the change is part of the manual test plan.
function RPSimGameAdapter:transferFarmland(farmlandId, direction)
    local ok, res = pcall(function()
        local noOwner = (FarmlandManager ~= nil and FarmlandManager.NO_OWNER_FARM_ID) or 0
        local target = direction == "TO_PLAYER" and self:getFarmId() or noOwner
        return g_farmlandManager:setLandOwnership(farmlandId, target)
    end)
    if not ok then
        return false, tostring(res)
    end
    if res == false then
        return false, "setLandOwnership refused farmland " .. tostring(farmlandId)
    end
    return true
end
