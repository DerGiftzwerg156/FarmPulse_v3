-- Roadmap V4 R4-D1: cumulative consumption counter per own field and fill type - the litres sowing machines and sprayers
-- used on that field (seed, fertiliser, lime, herbicide, slurry ...), with the part a helper bought while working
-- (helperLiters; the game books it at once with a x 1.5 surcharge). Kept in the mod savegame like the harvest counter
-- (loading an older savegame brings back its older values). The litres come from the hooks on
-- SowingMachine / Sprayer.onEndWorkAreaProcessing (RPSim.lua). Pure module, testable without FS25.
RPSimInputCounter = {}

local function key(farmlandId, fillType)
    return string.format("%d|%s", farmlandId, fillType)
end

function RPSimInputCounter.new()
    return { entries = {} } -- [key] = { farmlandId, fillType, liters, helperLiters }
end

--- Adds litres to the counter of field and fill type (helper = a helper bought them); ignores incomplete values and
-- litres <= 0.
function RPSimInputCounter.add(counter, farmlandId, fillType, liters, helper)
    if counter == nil or type(farmlandId) ~= "number" or type(fillType) ~= "string" or fillType == ""
        or type(liters) ~= "number" or liters ~= liters or liters <= 0 then
        return false
    end
    local k = key(farmlandId, fillType)
    local e = counter.entries[k]
    if e == nil then
        e = { farmlandId = farmlandId, fillType = fillType, liters = 0, helperLiters = 0 }
        counter.entries[k] = e
    end
    e.liters = e.liters + liters
    if helper == true then
        e.helperLiters = e.helperLiters + liters
    end
    return true
end

--- Puts a saved counter back (savegame load); helperLiters is capped at liters.
function RPSimInputCounter.restore(counter, farmlandId, fillType, liters, helperLiters)
    if not RPSimInputCounter.add(counter, farmlandId, fillType, liters, false) then
        return false
    end
    local e = counter.entries[key(farmlandId, fillType)]
    if type(helperLiters) == "number" and helperLiters == helperLiters and helperLiters > 0 then
        e.helperLiters = math.min(e.liters, e.helperLiters + helperLiters)
    end
    return true
end

--- Fill type names in the counter (for the purchase prices of market_context.fillTypeCosts).
function RPSimInputCounter.fillTypes(counter)
    local seen, list = {}, {}
    for _, e in pairs(counter ~= nil and counter.entries or {}) do
        if not seen[e.fillType] then
            seen[e.fillType] = true
            list[#list + 1] = e.fillType
        end
    end
    table.sort(list)
    return list
end

--- Raw list for RPSimFarmFacts.buildInputs (which rounds and sorts).
function RPSimInputCounter.toRaw(counter)
    local list = {}
    for _, e in pairs(counter ~= nil and counter.entries or {}) do
        list[#list + 1] = { farmlandId = e.farmlandId, fillType = e.fillType, liters = e.liters,
            helperLiters = e.helperLiters }
    end
    return list
end
