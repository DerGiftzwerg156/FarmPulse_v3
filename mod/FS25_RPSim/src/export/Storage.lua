-- Farm stock inventory ("Warenbestand"): bulk goods the farm stores in silos, silo extensions, production points
-- and bunker silos. Not counted: husbandries (animal food and products) and object storages (pallets/bales in halls).
RPSimStorage = {}

-- Storage kinds the adapter reports (GameAdapter.storageSources).
RPSimStorage.KINDS = { SILO = true, SILO_EXTENSION = true, PRODUCTION = true, BUNKER_SILO = true }

--- Why a storage place does not count for the stock, or nil when it counts.
-- descriptor: { kind, hasHusbandrySpec, hasObjectStorageSpec }
function RPSimStorage.exclusionReason(d)
    if d == nil or not RPSimStorage.KINDS[d.kind] then
        return "no storage"
    end
    if d.hasHusbandrySpec then
        return "husbandry"
    end
    if d.hasObjectStorageSpec then
        return "object storage"
    end
    return nil
end

function RPSimStorage.counts(d)
    return RPSimStorage.exclusionReason(d) == nil
end

--- One log line per storage place for the manual test: what was found and why it counts or not.
function RPSimStorage.describe(silos)
    local lines = {}
    for _, silo in ipairs(silos or {}) do
        local d = silo.descriptor or {}
        local reason = RPSimStorage.exclusionReason(d)
        local levels = {}
        for _, storage in ipairs(silo.storages or {}) do
            for fillType, amount in pairs(storage.fillLevels or {}) do
                if (amount or 0) > 0 then
                    levels[#levels + 1] = string.format("%s=%d", fillType, math.floor(amount + 0.5))
                end
            end
        end
        table.sort(levels)
        lines[#lines + 1] = string.format("%s [%s]: %s, %d storages, fill levels: %s", tostring(silo.uniqueId or "?"),
            tostring(d.kind), reason == nil and "counted" or ("ignored (" .. reason .. ")"), #(silo.storages or {}),
            #levels > 0 and table.concat(levels, " ") or "empty")
    end
    return lines
end

--- Aggregates fill levels per fill type over all counted storage places of the farm.
-- silos: list of { uniqueId?, descriptor = {...},
--                  storages = { { capacity = n, capacityPerFillType = { [fillTypeName] = n }?,
--                                 fillLevels = { [fillTypeName] = amount } } } }
-- Returns a sorted JSON array of { fillType, amount, capacity }.
-- Capacity: a storage's capacity is attributed to every fill type it currently holds or accepts
-- (fillLevels entry present, even 0). It is exported as raw value only; V1 derives no formula from it.
-- Bunker silos have no capacity (heap on the ground) and add 0.
function RPSimStorage.aggregate(silos)
    local byType = {}
    for _, silo in ipairs(silos or {}) do
        if RPSimStorage.counts(silo.descriptor) then
            for _, storage in ipairs(silo.storages or {}) do
                local cap = storage.capacity or 0
                local capPerType = storage.capacityPerFillType
                for fillType, amount in pairs(storage.fillLevels or {}) do
                    local e = byType[fillType]
                    if e == nil then
                        e = { fillType = fillType, amount = 0, capacity = 0 }
                        byType[fillType] = e
                    end
                    e.amount = e.amount + (amount or 0)
                    if capPerType ~= nil and capPerType[fillType] ~= nil then
                        e.capacity = e.capacity + capPerType[fillType]
                    else
                        e.capacity = e.capacity + cap
                    end
                end
            end
        end
    end
    local out = RPSimJson.array({})
    for _, e in pairs(byType) do
        if e.amount > 0 then
            e.amount = math.floor(e.amount + 0.5)
            e.capacity = math.floor(e.capacity + 0.5)
            out[#out + 1] = e
        end
    end
    table.sort(out, function(a, b) return a.fillType < b.fillType end)
    return out
end
