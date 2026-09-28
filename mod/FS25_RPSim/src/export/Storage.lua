-- Classic-silo storage inventory (functional concept "Silo-Warenbestand": ONLY classic silos and their silo
-- extensions, explicitly NOT bunker silos / horizontal silos and NOT halls/sheds).
RPSimStorage = {}

-- Store categories of halls/sheds. A placeable listed only there is no classic silo even with spec_silo.
RPSimStorage.HALL_CATEGORIES = { SHEDS = true, SHED = true }

local function categoryList(d)
    local out = {}
    for _, c in ipairs(d.categoryNames or {}) do
        if type(c) == "string" and c ~= "" then
            out[#out + 1] = string.upper(c)
        end
    end
    if #out == 0 and type(d.categoryName) == "string" and d.categoryName ~= "" then
        out[1] = string.upper(d.categoryName)
    end
    return out
end

--- Why a storage placeable is not a classic silo, or nil when it is one.
-- descriptor: { hasSiloSpec, hasSiloExtensionSpec, hasBunkerSiloSpec, hasObjectStorageSpec, hasHusbandrySpec,
--               hasProductionSpec, categoryName?, categoryNames? }
-- TODO(offene-frage): FS25 offers no documented "classic silo" flag. Best effort: a placeable with the
-- silo specialization (spec_silo) or a silo extension (spec_siloExtension) that is not a bunker silo, not an
-- object storage (hall), not part of a husbandry or production point. The store category only excludes
-- placeables listed purely as sheds: FS25 stores several categories per item (storeItem.categoryNames) and mod
-- silos use arbitrary ones; requiring "SILOS" is the suspected cause of the empty export in the 1.5.1 live test.
-- See offene-technische-punkte.md #8.
function RPSimStorage.exclusionReason(d)
    if d == nil or not (d.hasSiloSpec or d.hasSiloExtensionSpec) then
        return "no silo"
    end
    if d.hasBunkerSiloSpec then
        return "bunker silo"
    end
    if d.hasObjectStorageSpec then
        return "object storage"
    end
    if d.hasHusbandrySpec then
        return "husbandry"
    end
    if d.hasProductionSpec then
        return "production point"
    end
    local cats = categoryList(d)
    if #cats == 0 then
        return nil
    end
    for _, c in ipairs(cats) do
        if not RPSimStorage.HALL_CATEGORIES[c] then
            return nil
        end
    end
    return "hall category " .. table.concat(cats, ",")
end

--- Decides whether a storage placeable is a classic silo (see exclusionReason).
function RPSimStorage.isClassicSilo(d)
    return RPSimStorage.exclusionReason(d) == nil
end

--- One log line per storage placeable for the manual test (open point #8): what was found and why it counts or not.
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
        lines[#lines + 1] = string.format("%s [%s, categories=%s]: %s, %d storages, fill levels: %s",
            tostring(silo.uniqueId or "?"), d.hasSiloExtensionSpec and "silo extension" or "silo",
            table.concat(categoryList(d), ","), reason == nil and "counted" or ("ignored (" .. reason .. ")"),
            #(silo.storages or {}), #levels > 0 and table.concat(levels, " ") or "empty")
    end
    return lines
end

--- Aggregates fill levels per fill type over all classic silos of the farm.
-- silos: list of { uniqueId?, descriptor = {...},
--                  storages = { { capacity = n, capacityPerFillType = { [fillTypeName] = n }?,
--                                 fillLevels = { [fillTypeName] = amount } } } }
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
