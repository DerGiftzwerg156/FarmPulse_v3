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
--   silos = <see RPSimStorage.aggregate>, vanillaLoan = number, prices = { {sellPoint, fillType, pricePerLiter} } }
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
        prices[#prices + 1] = { sellPoint = p.sellPoint, fillType = p.fillType,
            currentPrice = round((p.pricePerLiter or 0) * cfg.pricePerLiters) }
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
    local loan = raw.vanillaLoan or 0
    return {
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
    }
end
