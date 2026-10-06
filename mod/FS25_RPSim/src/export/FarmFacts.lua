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

local function round3(v)
    return math.floor(v * 1000 + 0.5) / 1000
end

local function round1(v)
    return math.floor(v * 10 + 0.5) / 10
end

local function round2(v)
    return math.floor(v * 100 + 0.5) / 100
end

local function optNumber(t, key, fn)
    if type(t[key]) == "number" then
        return (fn or round)(t[key])
    end
    return nil
end

--- Roadmap V2 (R2-Q1): optional blocks. A block the adapter does not deliver (nil) is left out of the export, so the
-- backend can tell "mod too old / not collected" (missing) from "nothing there" (empty).

--- R2-B1: sums per FS25 period and money type. raw = { periods = { {year, period, byType = { [name] = amount }} } }
function RPSimFarmFacts.buildFinances(raw)
    local periods = RPSimJson.array({})
    for _, p in ipairs(raw.periods or {}) do
        if type(p.year) == "number" and type(p.period) == "number" then
            local byType = {}
            for name, amount in pairs(p.byType or {}) do
                if type(amount) == "number" then
                    byType[tostring(name)] = round(amount)
                end
            end
            periods[#periods + 1] = { year = p.year, period = p.period, byType = byType }
        end
    end
    table.sort(periods, function(a, b)
        if a.year == b.year then return a.period < b.period end
        return a.year < b.year
    end)
    return { periods = periods }
end

--- Booking statement: raw = { nextSeq, entries = { {seq, gameTime, year, period, day, category, amount, count, single,
-- liters?, fillType?, sellPoint?, note?} } } -> amounts and litres rounded, entries sorted by seq, incomplete dropped.
function RPSimFarmFacts.buildBookings(raw)
    local entries = RPSimJson.array({})
    for _, e in ipairs(raw.entries or {}) do
        if type(e.seq) == "number" and type(e.year) == "number" and type(e.period) == "number"
            and type(e.category) == "string" and type(e.amount) == "number" then
            local out = { seq = e.seq, gameTime = round(e.gameTime or 0), year = e.year, period = e.period,
                category = e.category, amount = round(e.amount), count = e.count or 1, single = e.single == true }
            if type(e.day) == "number" then out.day = e.day end
            if type(e.liters) == "number" then out.liters = round(e.liters) end
            if type(e.fillType) == "string" then out.fillType = e.fillType end
            if type(e.sellPoint) == "string" then out.sellPoint = e.sellPoint end
            if type(e.note) == "string" and e.note ~= "" then out.note = e.note end
            entries[#entries + 1] = out
        end
    end
    table.sort(entries, function(a, b) return a.seq < b.seq end)
    return { nextSeq = type(raw.nextSeq) == "number" and raw.nextSeq or 1, entries = entries }
end

--- R2-A4: raw = { activeJobs = { {jobId, employeeId?, title?} }, workedGameMs = { [employeeId] = ms } }
function RPSimFarmFacts.buildWorkforce(raw)
    local jobs = RPSimJson.array({})
    for _, j in ipairs(raw.activeJobs or {}) do
        if type(j.jobId) == "number" then
            local e = { jobId = j.jobId }
            if type(j.employeeId) == "number" then e.employeeId = j.employeeId end
            if type(j.title) == "string" and j.title ~= "" then e.title = j.title end
            jobs[#jobs + 1] = e
        end
    end
    table.sort(jobs, function(a, b) return a.jobId < b.jobId end)
    -- keys as strings: numeric keys would be encoded as a JSON array
    local worked = {}
    for id, ms in pairs(raw.workedGameMs or {}) do
        if type(ms) == "number" then
            worked[tostring(id)] = round(ms)
        end
    end
    return { activeJobs = jobs, workedGameMs = worked }
end

--- Roadmap V3.1 R31-A3: animals per subtype of a husbandry (raw = { {name, count} }), merged by name, without empty
-- entries, sorted by name; nil when not a table.
local function buildSubTypes(raw)
    if type(raw) ~= "table" then
        return nil
    end
    local byName = {}
    for _, s in ipairs(raw) do
        if type(s) == "table" and type(s.name) == "string" and s.name ~= "" and type(s.count) == "number" then
            byName[s.name] = (byName[s.name] or 0) + round(s.count)
        end
    end
    local list = RPSimJson.array({})
    for name, count in pairs(byName) do
        if count > 0 then
            list[#list + 1] = { name = name, count = count }
        end
    end
    table.sort(list, function(a, b) return a.name < b.name end)
    return list
end

--- Roadmap V3.1 R31-A3: names of the subtypes a husbandry accepts, unique and sorted; nil when not a table.
local function buildNames(raw)
    if type(raw) ~= "table" then
        return nil
    end
    local seen, list = {}, RPSimJson.array({})
    for _, name in ipairs(raw) do
        if type(name) == "string" and name ~= "" and not seen[name] then
            seen[name] = true
            list[#list + 1] = name
        end
    end
    table.sort(list)
    return list
end

--- R2-A7: raw = { {husbandryUniqueId, health, productivity?, food, conditions = { {title, ratio} }} }
-- Roadmap V3.1 R31-A3, each optional: subTypes = { {name, count} }, supportedSubTypes = { name }, freeSlots.
function RPSimFarmFacts.buildHusbandries(raw)
    local list = RPSimJson.array({})
    for _, h in ipairs(raw) do
        if h.husbandryUniqueId ~= nil and type(h.health) == "number" and type(h.food) == "number" then
            local conditions = RPSimJson.array({})
            for _, c in ipairs(h.conditions or {}) do
                if type(c.title) == "string" and type(c.ratio) == "number" then
                    conditions[#conditions + 1] = { title = c.title, ratio = round3(c.ratio) }
                end
            end
            local e = { husbandryUniqueId = tostring(h.husbandryUniqueId), health = round3(h.health),
                productivity = optNumber(h, "productivity", round3), food = round3(h.food), conditions = conditions }
            e.subTypes = buildSubTypes(h.subTypes)
            e.supportedSubTypes = buildNames(h.supportedSubTypes)
            if type(h.freeSlots) == "number" and h.freeSlots == h.freeSlots then
                e.freeSlots = math.max(0, round(h.freeSlots))
            end
            list[#list + 1] = e
        end
    end
    table.sort(list, function(a, b) return a.husbandryUniqueId < b.husbandryUniqueId end)
    return list
end

local FIELD_LEVELS = { "growthState", "weedState", "stoneLevel", "sprayLevel", "limeLevel", "plowLevel" }

--- R2-C1: raw = { {farmlandId, name, hectares, fruitType?, minHarvestingGrowthState?, maxHarvestingGrowthState?,
--   withered?, cut?, fillType?, litersPerSqm?, groundType?, growthState, weedState, stoneLevel, sprayLevel, limeLevel,
--   plowLevel, sprayType?} }. withered / cut / fillType / litersPerSqm only with a crop (Roadmap V2 R2-C, owner
--   decision). Roadmap V3.1 (R31-Q1, B3): sprayType = name from the game's FieldSprayType table incl. "NONE".
function RPSimFarmFacts.buildFields(raw)
    local list = RPSimJson.array({})
    for _, f in ipairs(raw) do
        local complete = type(f.farmlandId) == "number" and f.name ~= nil and type(f.hectares) == "number"
        for _, key in ipairs(FIELD_LEVELS) do
            complete = complete and type(f[key]) == "number"
        end
        if complete then
            local e = { farmlandId = f.farmlandId, name = tostring(f.name),
                hectares = math.floor(f.hectares * 100 + 0.5) / 100 }
            for _, key in ipairs(FIELD_LEVELS) do
                e[key] = round(f[key])
            end
            if type(f.fruitType) == "string" and f.fruitType ~= "" then
                e.fruitType = f.fruitType
                e.minHarvestingGrowthState = optNumber(f, "minHarvestingGrowthState")
                e.maxHarvestingGrowthState = optNumber(f, "maxHarvestingGrowthState")
                if type(f.withered) == "boolean" then
                    e.withered = f.withered
                end
                if type(f.cut) == "boolean" then
                    e.cut = f.cut
                end
                if type(f.fillType) == "string" and f.fillType ~= "" then
                    e.fillType = f.fillType
                end
                if type(f.litersPerSqm) == "number" and f.litersPerSqm >= 0 then
                    e.litersPerSqm = math.floor(f.litersPerSqm * 10000 + 0.5) / 10000
                end
            end
            if type(f.groundType) == "string" and f.groundType ~= "" then
                e.groundType = f.groundType
            end
            if type(f.sprayType) == "string" and f.sprayType ~= "" then
                e.sprayType = f.sprayType
            end
            list[#list + 1] = e
        end
    end
    table.sort(list, function(a, b)
        if a.farmlandId == b.farmlandId then return a.name < b.name end
        return a.farmlandId < b.farmlandId
    end)
    return list
end

--- Roadmap V3 R3-H2 (contract R3-Q1): tradeable goods of the player = fill level and free capacity per fill type of the
-- own silos and silo extensions only (the adapter sums them). raw = { {fillType, amount, freeCapacity} }. An entry
-- counts when an own silo accepts the fill type (freeCapacity + amount > 0).
function RPSimFarmFacts.buildTradeStorage(raw)
    local list = RPSimJson.array({})
    for _, e in ipairs(raw) do
        if type(e.fillType) == "string" and e.fillType ~= "" and type(e.amount) == "number"
            and type(e.freeCapacity) == "number" then
            local amount = math.max(0, round(e.amount))
            local free = math.max(0, round(e.freeCapacity))
            if amount + free > 0 then
                list[#list + 1] = { fillType = e.fillType, amount = amount, freeCapacity = free }
            end
        end
    end
    table.sort(list, function(a, b) return a.fillType < b.fillType end)
    return list
end

local FIELD_RULES = { "plowingRequired", "limeRequired", "weedsEnabled", "stonesEnabled" }

--- R2-C: game settings that decide whether plowing, lime, weeds and stones matter at all (the game's soil map shows
-- them only when active). raw = { plowingRequired, limeRequired, weedsEnabled, stonesEnabled }; incomplete = nil.
function RPSimFarmFacts.buildFieldRules(raw)
    local rules = {}
    for _, key in ipairs(FIELD_RULES) do
        if type(raw[key]) ~= "boolean" then
            return nil
        end
        rules[key] = raw[key]
    end
    return rules
end

--- R2-C2: raw = { raining, rainFallScale, groundWetness, temperature?, snowHeight? }. Incomplete weather is left out;
-- the temperature (°C, Hof-Tablet status bar) is optional and rounded to one decimal. Roadmap V3.1 (R31-Q1, A4):
-- snowHeight = snow height of the world in metres (snowSystem.height, ≥ 0), optional, rounded to 0.01.
function RPSimFarmFacts.buildWeather(raw)
    if type(raw.raining) ~= "boolean" or type(raw.rainFallScale) ~= "number" or type(raw.groundWetness) ~= "number" then
        return nil
    end
    local weather = { raining = raw.raining, rainFallScale = round3(raw.rainFallScale),
        groundWetness = round3(raw.groundWetness) }
    if type(raw.temperature) == "number" and raw.temperature == raw.temperature then
        weather.temperature = math.floor(raw.temperature * 10 + 0.5) / 10
    end
    if type(raw.snowHeight) == "number" and raw.snowHeight == raw.snowHeight then
        weather.snowHeight = round2(math.max(0, raw.snowHeight))
    end
    return weather
end

--- Roadmap V3.1 (R31-Q1, D5): sample of the own vehicles being driven right now. raw = { {uniqueId, x, z, farmlandId?,
-- onCrop?} }; x / z = world position in metres (rounded to 0.1), farmlandId left out for 0 (no farmland), onCrop = a
-- field with a crop stands at the position. Sorted by uniqueId.
function RPSimFarmFacts.buildVehiclePositions(raw)
    local list = RPSimJson.array({})
    for _, p in ipairs(raw) do
        if type(p) == "table" and p.uniqueId ~= nil and type(p.x) == "number" and type(p.z) == "number"
            and p.x == p.x and p.z == p.z then
            local e = { uniqueId = tostring(p.uniqueId), x = round1(p.x), z = round1(p.z) }
            if type(p.farmlandId) == "number" and p.farmlandId > 0 then
                e.farmlandId = p.farmlandId
            end
            if type(p.onCrop) == "boolean" then
                e.onCrop = p.onCrop
            end
            list[#list + 1] = e
        end
    end
    table.sort(list, function(a, b) return a.uniqueId < b.uniqueId end)
    return list
end

--- Roadmap V3.1 (R31-Q1, D8): diesel of a vehicle with a diesel tank. raw = { liters, capacity } in litres; nil when
-- incomplete or without a tank (capacity 0). liters is kept within 0..capacity.
function RPSimFarmFacts.buildFuel(raw)
    if type(raw) ~= "table" or type(raw.liters) ~= "number" or type(raw.capacity) ~= "number"
        or raw.liters ~= raw.liters or raw.capacity ~= raw.capacity then
        return nil
    end
    local capacity = round(raw.capacity)
    if capacity <= 0 then
        return nil
    end
    return { liters = math.min(capacity, math.max(0, round(raw.liters))), capacity = capacity }
end

--- raw: {
--   savegameId, gameTime, balance,
--   vehicles = { {uniqueId, value, damage, name?, xmlFilename?, category?, fuel?} }, placeables = { {uniqueId, value} },
--   leasedVehicles = { {uniqueId, costPerPeriod?} },
--   farmland = { {farmlandId, hectares, price} }, animals = { {husbandryUniqueId, type, count, estimatedValue} },
--   silos = <see RPSimStorage.aggregate>, vanillaLoan = number,
--   prices = { {sellPoint, fillType, pricePerLiter, trend?} },
--   calendar = { period, dayInPeriod, daysPerPeriod, year, monotonicDay, periodName?, season?, dayTimeMs? } | nil,
--   Roadmap V2, each optional (nil = not collected): finances, bookings (statement), workforce, husbandries, fields,
--   fieldRules, weather
--   (see the build* functions above),
--   Roadmap V3 (R3-Q1), each optional: npcFields (R3-H1, same entries as fields), tradeStorage (R3-H2),
--   Roadmap V3.1 (R31-Q1), optional: vehiclePositions (D5, see buildVehiclePositions) }
function RPSimFarmFacts.build(raw, cfg)
    cfg = cfg or RPSimConfig.new()
    local vehicles = RPSimJson.array({})
    for _, v in ipairs(raw.vehicles or {}) do
        local e = { uniqueId = tostring(v.uniqueId), value = round(v.value),
            condition = RPSimFarmFacts.conditionFromDamage(v.damage) }
        -- Roadmap V3 R3-V3: optional name and shop XML of the vehicle
        if type(v.name) == "string" and v.name ~= "" then
            e.name = v.name
        end
        if type(v.xmlFilename) == "string" and v.xmlFilename ~= "" then
            e.xmlFilename = v.xmlFilename
        end
        -- Roadmap V3.1 (R31-Q1): shop category in upper case like the trainings (A4, D8) and the diesel (D8)
        if type(v.category) == "string" and v.category ~= "" then
            e.category = string.upper(v.category)
        end
        e.fuel = RPSimFarmFacts.buildFuel(v.fuel)
        vehicles[#vehicles + 1] = e
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
        -- Roadmap V3.1 (R31-Q1, D4): time of day in in-game ms since midnight
        if type(c.dayTimeMs) == "number" and c.dayTimeMs >= 0 and c.dayTimeMs < RPSimConfig.MS_PER_GAME_DAY then
            doc.calendar.dayTimeMs = math.floor(c.dayTimeMs)
        end
    end
    if type(raw.finances) == "table" then
        doc.finances = RPSimFarmFacts.buildFinances(raw.finances)
    end
    if type(raw.bookings) == "table" then
        doc.bookings = RPSimFarmFacts.buildBookings(raw.bookings)
    end
    if type(raw.workforce) == "table" then
        doc.workforce = RPSimFarmFacts.buildWorkforce(raw.workforce)
    end
    if type(raw.husbandries) == "table" then
        doc.husbandries = RPSimFarmFacts.buildHusbandries(raw.husbandries)
    end
    if type(raw.fields) == "table" then
        doc.fields = RPSimFarmFacts.buildFields(raw.fields)
    end
    if type(raw.fieldRules) == "table" then
        doc.fieldRules = RPSimFarmFacts.buildFieldRules(raw.fieldRules)
    end
    if type(raw.weather) == "table" then
        doc.weather = RPSimFarmFacts.buildWeather(raw.weather)
    end
    -- Roadmap V3 (R3-Q1): fields without an owner that the game's NPCs farm (R3-H1) and the own silo goods (R3-H2)
    if type(raw.npcFields) == "table" then
        doc.npcFields = RPSimFarmFacts.buildFields(raw.npcFields)
    end
    if type(raw.tradeStorage) == "table" then
        doc.tradeStorage = RPSimFarmFacts.buildTradeStorage(raw.tradeStorage)
    end
    -- Roadmap V3 R3-H5: the game's contract limit of the player farm (hasFarmReachedMissionLimit)
    if type(raw.missionLimitReached) == "boolean" then
        doc.missionLimitReached = raw.missionLimitReached
    end
    -- Roadmap V3.1 (R31-Q1, D5): positions of the own vehicles being driven
    if type(raw.vehiclePositions) == "table" then
        doc.vehiclePositions = RPSimFarmFacts.buildVehiclePositions(raw.vehiclePositions)
    end
    return doc
end
