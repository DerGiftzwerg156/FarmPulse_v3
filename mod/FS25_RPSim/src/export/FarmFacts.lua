-- Builds the farm_facts.json document from raw game state (technical concept "Export-Schema").
-- Only raw states - derived figures (cash flow etc.) are computed by the backend.
RPSimFarmFacts = {}

local function round(v)
    return math.floor((v or 0) + 0.5)
end

--- FS wear/damage (0..1, 1 = broken) to condition 0..100 (100 = perfect).
function RPSimFarmFacts.conditionFromDamage(damage)
    local d = damage or 0
    if d < 0 then d = 0 end
    if d > 1 then d = 1 end
    return round((1 - d) * 100)
end

--- raw: {
--   savegameId, gameTime, balance,
--   vehicles = { {uniqueId, value, damage} }, placeables = { {uniqueId, value} },
--   leasedVehicles = { {uniqueId, costPerPeriod?} },
--   farmland = { {farmlandId, hectares, price} }, animals = { {husbandryUniqueId, type, count, estimatedValue} },
--   silos = <see RPSimStorage.aggregate>, vanillaLoan = number,
--   prices = { {sellPoint, fillType, pricePerLiter, trend?} },
--   calendar = { period, dayInPeriod, daysPerPeriod, year, monotonicDay, periodName?, season? } | nil }
function RPSimFarmFacts.build(raw, cfg)
    cfg = cfg or RPSimConfig.new()
    local vehicles = RPSimJson.array({})
    for _, v in ipairs(raw.vehicles or {}) do
        vehicles[#vehicles + 1] = { uniqueId = tostring(v.uniqueId), value = round(v.value),
            condition = RPSimFarmFacts.conditionFromDamage(v.damage) }
    end
    local placeables = RPSimJson.array({})
    for _, p in ipairs(raw.placeables or {}) do
        placeables[#placeables + 1] = { uniqueId = tostring(p.uniqueId), value = round(p.value) }
    end
    local farmland = RPSimJson.array({})
    for _, f in ipairs(raw.farmland or {}) do
        farmland[#farmland + 1] = { farmlandId = f.farmlandId, hectares = math.floor(f.hectares * 100 + 0.5) / 100,
            price = round(f.price) }
    end
    table.sort(farmland, function(a, b) return a.farmlandId < b.farmlandId end)
    local animals = RPSimJson.array({})
    for _, a in ipairs(raw.animals or {}) do
        animals[#animals + 1] = { husbandryUniqueId = tostring(a.husbandryUniqueId), type = a.type,
            count = round(a.count), estimatedValue = round(a.estimatedValue) }
    end
    local prices = RPSimJson.array({})
    for _, p in ipairs(raw.prices or {}) do
        local e = { sellPoint = p.sellPoint, fillType = p.fillType,
            currentPrice = round((p.pricePerLiter or 0) * cfg.pricePerLiters) }
        if p.trend ~= nil then
            e.trend = p.trend
        end
        prices[#prices + 1] = e
    end
    table.sort(prices, function(a, b)
        if a.sellPoint == b.sellPoint then return a.fillType < b.fillType end
        return a.sellPoint < b.sellPoint
    end)
    -- Leased vehicles are no assets but an obligation (T-04). costPerPeriod stays absent until the game API
    -- for leasing costs is verified (manual test plan).
    local leasing = RPSimJson.array({})
    for _, v in ipairs(raw.leasedVehicles or {}) do
        local e = { uniqueId = tostring(v.uniqueId) }
        if type(v.costPerPeriod) == "number" then
            e.costPerPeriod = round(v.costPerPeriod)
        end
        leasing[#leasing + 1] = e
    end
    table.sort(leasing, function(a, b) return a.uniqueId < b.uniqueId end)
    -- TODO T-22: vanilla contracts (available ones and the player's own)
    local missions = RPSimJson.array({})
    for _, m in ipairs(raw.missions or {}) do
        if m.uniqueId ~= nil and m.status ~= nil then
            local e = { uniqueId = tostring(m.uniqueId), status = m.status }
            if type(m.title) == "string" then e.title = m.title end
            if type(m.typeName) == "string" then e.typeName = m.typeName end
            if m.field ~= nil then e.field = tostring(m.field) end
            if type(m.npcIndex) == "number" then e.npcIndex = m.npcIndex end
            if type(m.npcTitle) == "string" then e.npcTitle = m.npcTitle end
            if type(m.reward) == "number" then e.reward = round(m.reward) end
            if m.success ~= nil then e.success = m.success == true end
            missions[#missions + 1] = e
        end
    end
    table.sort(missions, function(a, b) return a.uniqueId < b.uniqueId end)
    local loan = raw.vanillaLoan or 0
    local doc = {
        schemaVersion = cfg.schemaVersion,
        gameTime = round(raw.gameTime),
        savegameId = raw.savegameId,
        liquidity = { balance = round(raw.balance) },
        assets = {
            vehicles = vehicles,
            placeables = placeables,
            farmland = farmland,
            animals = animals,
            storage = RPSimStorage.aggregate(raw.silos),
        },
        liabilities = { vanillaLoan = { active = loan > 0, remainingAmount = round(loan) }, leasing = leasing },
        prices = prices,
        missions = missions,
    }
    local c = raw.calendar
    if type(c) == "table" and type(c.period) == "number" then
        -- T-08: the game month of the backend is the FS25 period
        doc.calendar = { period = c.period, dayInPeriod = c.dayInPeriod or 1, daysPerPeriod = c.daysPerPeriod or 1,
            year = c.year or 1, monotonicDay = c.monotonicDay or 0 }
        if type(c.periodName) == "string" and c.periodName ~= "" then
            doc.calendar.periodName = c.periodName
        end
        if type(c.season) == "string" and c.season ~= "" then
            doc.calendar.season = c.season -- T-21: name from the game's Season table, e.g. "WINTER"
        end
    end
    return doc
end
