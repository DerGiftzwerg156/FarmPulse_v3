-- FS25-specific adapter: the only file that touches FS25 globals. Every API call is wrapped in pcall
-- so an engine change never crashes the savegame; failures degrade to empty/partial exports.
-- luacheck: globals g_currentMission g_farmManager g_farmlandManager g_fillTypeManager
-- luacheck: globals MoneyType FarmManager FarmlandManager VehiclePropertyState SellingStation Utils g_modIsLoaded
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
-- uniqueId of the owning placeable, else the station name. See docs/dev/offene-technische-punkte.md.
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
            period = env.currentPeriod,
            dayInPeriod = env.currentDayInPeriod or 1,
            daysPerPeriod = env.daysPerPeriod or 1,
            year = env.currentYear or 1,
            monotonicDay = env.currentMonotonicDay or env.currentDay or 0,
            periodName = name,
        }
    end, nil)
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
                defaultFarmProperty = fl.defaultFarmProperty == true }
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

function RPSimGameAdapter:addMoney(amount, reason, note)
    local ok, err = pcall(function()
        local moneyType = MoneyType ~= nil and MoneyType.OTHER or nil
        g_currentMission:addMoney(amount, self:getFarmId(), moneyType, true, true)
    end)
    if not ok then
        return false, tostring(err)
    end
    RPSimLog.info("Money %s %d (%s)", reason, amount, tostring(note or ""))
    return true
end

--- Farmland ownership transfer between the player farm and "no owner" (NPC owners only exist in the tool).
-- TODO(offene-frage): FarmlandManager:setLandOwnership is the API used by vanilla buy/sell and by the
-- "Farmland Marketplace" community mod; the player<->NPC case must be verified on the prototype.
function RPSimGameAdapter:transferFarmland(farmlandId, direction)
    local ok, err = pcall(function()
        local noOwner = (FarmlandManager ~= nil and FarmlandManager.NO_OWNER_FARM_ID) or 0
        local target = direction == "TO_PLAYER" and self:getFarmId() or noOwner
        g_farmlandManager:setLandOwnership(farmlandId, target)
    end)
    if not ok then
        return false, tostring(err)
    end
    return true
end
