-- Roadmap V4 R4-F1: cumulative feed counter per husbandry and fill type - the litres the animals ate out of the feeding
-- trough (the return of PlaceableHusbandryFood:removeFood, called by updateFeeding with the eaten food of each fill
-- type). Kept in the mod savegame like the harvest and input counters (loading an older savegame brings back its older
-- values; the backend takes a falling counter back). Plus the feed stock of the own silos (farm_facts.feedStock) and the
-- fill type names of the animal food groups. Pure module, testable without FS25.
RPSimFeedCounter = {}

local function key(husbandryUniqueId, fillType)
    return string.format("%s|%s", husbandryUniqueId, fillType)
end

function RPSimFeedCounter.new()
    return { entries = {} } -- [key] = { husbandryUniqueId, fillType, liters }
end

--- Adds eaten litres to the counter of husbandry and fill type; ignores incomplete values and litres <= 0.
function RPSimFeedCounter.add(counter, husbandryUniqueId, fillType, liters)
    if counter == nil or type(husbandryUniqueId) ~= "string" or husbandryUniqueId == "" or type(fillType) ~= "string"
        or fillType == "" or type(liters) ~= "number" or liters ~= liters or liters <= 0 then
        return false
    end
    local k = key(husbandryUniqueId, fillType)
    local e = counter.entries[k]
    if e == nil then
        e = { husbandryUniqueId = husbandryUniqueId, fillType = fillType, liters = 0 }
        counter.entries[k] = e
    end
    e.liters = e.liters + liters
    return true
end

--- The counters of one husbandry as { {fillType, liters} } (for farm_facts.husbandries[].feedConsumed), nil when the
-- husbandry has none.
function RPSimFeedCounter.forHusbandry(counter, husbandryUniqueId)
    local list = nil
    for _, e in pairs(counter ~= nil and counter.entries or {}) do
        if e.husbandryUniqueId == husbandryUniqueId then
            list = list or {}
            list[#list + 1] = { fillType = e.fillType, liters = e.liters }
        end
    end
    return list
end

--- Puts the counters on the raw husbandries of collectFarmFacts (by husbandryUniqueId). A husbandry without counter
-- gets an empty list: the hook runs, the animals have not eaten yet.
function RPSimFeedCounter.attach(counter, husbandries)
    for _, h in ipairs(husbandries or {}) do
        if h.husbandryUniqueId ~= nil then
            h.feedConsumed = RPSimFeedCounter.forHusbandry(counter, tostring(h.husbandryUniqueId)) or {}
        end
    end
end

--- Raw list for the savegame, sorted by husbandry and fill type.
function RPSimFeedCounter.toRaw(counter)
    local list = {}
    for _, e in pairs(counter ~= nil and counter.entries or {}) do
        list[#list + 1] = { husbandryUniqueId = e.husbandryUniqueId, fillType = e.fillType, liters = e.liters }
    end
    table.sort(list, function(a, b)
        if a.husbandryUniqueId ~= b.husbandryUniqueId then return a.husbandryUniqueId < b.husbandryUniqueId end
        return a.fillType < b.fillType
    end)
    return list
end

--- Fill type names the feed balance needs: every fill type of a food group, every mixture (raw animalFood of
-- collectAnimalFood) and STRAW (bedding). Sorted, unique.
function RPSimFeedCounter.feedNames(animalFood)
    local seen, list = { STRAW = true }, { "STRAW" }
    local function add(name)
        if type(name) == "string" and name ~= "" and not seen[name] then
            seen[name] = true
            list[#list + 1] = name
        end
    end
    for _, a in ipairs(animalFood or {}) do
        for _, g in ipairs(type(a.groups) == "table" and a.groups or {}) do
            for _, name in ipairs(type(g.fillTypes) == "table" and g.fillTypes or {}) do
                add(name)
            end
        end
        for _, name in ipairs(type(a.mixtures) == "table" and a.mixtures or {}) do
            add(name)
        end
    end
    table.sort(list)
    return list
end

--- Feed stock (farm_facts.feedStock) out of the storage places of collectFarmFacts (raw.silos of storageSources): own
-- silos, silo extensions and bunker silos - the places the stock inventory counts (RPSimStorage.counts) without the
-- production points (their input is reserved for the production). Only the fill types in `names`.
-- Returns { {fillType, amount} } (summed and rounded by RPSimFarmFacts.buildFeedStock).
function RPSimFeedCounter.stockFromSilos(silos, names)
    local wanted = {}
    for _, name in ipairs(names or {}) do
        wanted[name] = true
    end
    local list = {}
    for _, silo in ipairs(silos or {}) do
        local d = silo.descriptor
        if RPSimStorage.counts(d) and d.kind ~= "PRODUCTION" then
            for _, storage in ipairs(silo.storages or {}) do
                for fillType, amount in pairs(storage.fillLevels or {}) do
                    if wanted[fillType] then
                        list[#list + 1] = { fillType = fillType, amount = amount }
                    end
                end
            end
        end
    end
    return list
end
