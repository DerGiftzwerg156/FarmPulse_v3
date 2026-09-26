-- Classic-silo storage inventory (functional concept "Silo-Warenbestand": ONLY classic silos,
-- explicitly NOT bunker silos / horizontal silos and NOT halls/sheds).
RPSimStorage = {}

--- Decides whether a storage placeable is a classic silo.
-- descriptor: { hasSiloSpec, hasBunkerSiloSpec, hasObjectStorageSpec, hasHusbandrySpec,
--               hasProductionSpec, categoryName }
-- TODO(offene-frage): FS25 offers no documented "classic silo" flag. Best effort: a placeable with the
-- silo specialization (spec_silo) that is not a bunker silo, not an object storage (hall), not part of a
-- husbandry or production point, and - when the store category is known - is listed in the SILOS
-- category. Verified in the first real FS25 test (docs/dev/manual-test-plan.md), see offene-technische-punkte.md #8.
function RPSimStorage.isClassicSilo(d)
    if d == nil or not d.hasSiloSpec then
        return false
    end
    if d.hasBunkerSiloSpec or d.hasObjectStorageSpec or d.hasHusbandrySpec or d.hasProductionSpec then
        return false
    end
    if d.categoryName ~= nil and d.categoryName ~= "" then
        local cat = string.upper(d.categoryName)
        return cat == "SILOS" or cat == "SILO"
    end
    return true
end

--- Aggregates fill levels per fill type over all classic silos of the farm.
-- silos: list of { descriptor = {...}, storages = { { capacity = n, fillLevels = { [fillTypeName] = amount } } } }
-- Returns a sorted JSON array of { fillType, amount, capacity }.
-- Capacity: a storage's capacity is attributed to every fill type it currently holds or accepts
-- (fillLevels entry present, even 0). It is exported as raw value only; V1 derives no formula from it.
function RPSimStorage.aggregate(silos)
    local byType = {}
    for _, silo in ipairs(silos or {}) do
        if RPSimStorage.isClassicSilo(silo.descriptor) then
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
