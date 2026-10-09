-- Roadmap V3.3 R33-F3: cumulative harvest counter per own field, crop and harvest product (owner decision 2026-10-08:
-- a counter that only grows, kept in the mod savegame; loading an older savegame brings back its older value). The
-- litres come from the hook on Combine:addCutterArea (RPSim.lua). Pure module, testable without FS25.
RPSimHarvestCounter = {}

local function key(farmlandId, fruitType, fillType)
    return string.format("%d|%s|%s", farmlandId, fruitType, fillType)
end

function RPSimHarvestCounter.new()
    return { entries = {} } -- [key] = { farmlandId, fruitType, fillType, liters }
end

--- Adds litres to the counter of field, crop and product; ignores incomplete values and litres <= 0.
function RPSimHarvestCounter.add(counter, farmlandId, fruitType, fillType, liters)
    if counter == nil or type(farmlandId) ~= "number" or type(fruitType) ~= "string" or fruitType == ""
        or type(fillType) ~= "string" or fillType == "" or type(liters) ~= "number" or liters ~= liters
        or liters <= 0 then
        return false
    end
    local k = key(farmlandId, fruitType, fillType)
    local e = counter.entries[k]
    if e == nil then
        e = { farmlandId = farmlandId, fruitType = fruitType, fillType = fillType, liters = 0 }
        counter.entries[k] = e
    end
    e.liters = e.liters + liters
    return true
end

--- Raw list for RPSimFarmFacts.buildHarvests (which rounds and sorts).
function RPSimHarvestCounter.toRaw(counter)
    local list = {}
    for _, e in pairs(counter ~= nil and counter.entries or {}) do
        list[#list + 1] = { farmlandId = e.farmlandId, fruitType = e.fruitType, fillType = e.fillType, liters = e.liters }
    end
    return list
end
