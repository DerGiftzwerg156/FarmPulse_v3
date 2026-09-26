-- FS25-specific adapter: the only file that touches FS25 globals. Every API call is wrapped in pcall
-- so an engine change never crashes the savegame; failures degrade to empty/partial exports.
-- luacheck: globals g_currentMission g_farmManager g_farmlandManager g_fillTypeManager g_npcManager
-- luacheck: globals MoneyType FarmManager FarmlandManager VehiclePropertyState SellingStation Utils g_modIsLoaded
-- luacheck: globals FSBaseMission Season
-- luacheck: globals g_i18n
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

--- Name of the current season (T-21): environment.currentSeason compared with the values of the global Season
-- table (FS25 BeehiveSystem / StonePickMission: environment.currentSeason == Season.WINTER). The name is looked up
-- instead of assumed, so only names that really exist in the game are exported.
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

function RPSimGameAdapter:collectFarmFacts()
    local farmId = self:getFarmId()
    local raw = { vehicles = {}, leasedVehicles = {}, placeables = {}, farmland = {}, animals = {}, silos = {},
        prices = {}, calendar = self:collectCalendar() }
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
            end
            -- silos (classic-silo classification happens in RPSimStorage)
            if p.spec_silo ~= nil then
                local storages = {}
                for _, storage in pairs(p.spec_silo.storages or {}) do
                    local levels = {}
                    for ftIndex, level in pairs(storage.fillLevels or {}) do
                        levels[fillTypeName(ftIndex)] = level
                    end
                    storages[#storages + 1] = { capacity = storage.capacity or 0, fillLevels = levels,
                        capacityPerFillType = nil }
                end
                local categoryName = safe(function() return p.storeItem.categoryName end, nil)
                raw.silos[#raw.silos + 1] = {
                    descriptor = {
                        hasSiloSpec = true,
                        hasBunkerSiloSpec = p.spec_bunkerSilo ~= nil,
                        hasObjectStorageSpec = p.spec_objectStorage ~= nil,
                        hasHusbandrySpec = p.spec_husbandryAnimals ~= nil,
                        hasProductionSpec = p.spec_productionPoint ~= nil,
                        categoryName = categoryName,
                    },
                    storages = storages,
                }
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
            raw.sellPoints[#raw.sellPoints + 1] = { id = RPSimGameAdapter.sellPointId(station),
                name = station:getName(), acceptedFillTypes = accepted }
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

function RPSimGameAdapter:addMoney(amount, reason, note)
    local ok, err = pcall(function()
        g_currentMission:addMoney(amount, self:getFarmId(), self:moneyTypeFor(reason), true, true)
    end)
    if not ok then
        return false, tostring(err)
    end
    RPSimLog.info("Money %s %d (%s)", reason, amount, tostring(note or ""))
    return true
end

--- Repair of an own vehicle paid by the maintenance contract (TODO T-22): Wearable:setDamageAmount(0, true) - the
-- part of FS25 Wearable:repairVehicle() that removes the damage; repairVehicle() itself would also book the repair
-- price (addMoney(-getRepairPrice(), ..., MoneyType.VEHICLE_REPAIR)), which the contract already covers.
function RPSimGameAdapter:repairVehicle(uniqueId)
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
        target:setDamageAmount(0, true)
    end)
    if not ok then
        local msg = tostring(err)
        return false, msg:match("NOT_OWN_VEHICLE") and "NOT_OWN_VEHICLE" or msg:match("NOT_WEARABLE") and "NOT_WEARABLE" or msg
    end
    RPSimLog.info("Vehicle %s repaired (maintenance contract)", tostring(uniqueId))
    return true
end

--- In-game notification (TODO T-21): g_currentMission:addIngameNotification(FSBaseMission.INGAME_NOTIFICATION_*, text),
-- the pattern of FS25_MarketDynamics (MarketDynamics.lua, FuturesMarket.lua).
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
