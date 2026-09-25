-- Savegame persistence of the mod state via the official XMLFile save/load hooks (NOT in the bridge).
-- Works against a minimal writer/reader interface so it is testable without FS25:
--   writer: setString(key, v), setInt(key, v), setFloat(key, v)
--   reader: getString(key), getInt(key), getFloat(key), hasProperty(key)
RPSimPersistence = {}

local ROOT = "FS25_RPSim"

function RPSimPersistence.save(writer, state)
    writer:setString(ROOT .. "#savegameId", state.savegameId or "")
    local ids = {}
    for id, _ in pairs(state.processed) do
        ids[#ids + 1] = id
    end
    table.sort(ids)
    for i, id in ipairs(ids) do
        local e = state.processed[id]
        local k = string.format("%s.processedInstructions.entry(%d)", ROOT, i - 1)
        writer:setString(k .. "#id", id)
        writer:setFloat(k .. "#gameTime", e.gameTime)
        writer:setString(k .. "#status", e.status or "APPLIED")
        if e.message ~= nil then
            writer:setString(k .. "#message", e.message)
        end
    end
    for i, ev in ipairs(state.priceEvents.events) do
        local k = string.format("%s.activePriceEvents.event(%d)", ROOT, i - 1)
        writer:setString(k .. "#id", ev.id)
        writer:setString(k .. "#priceMode", ev.priceMode)
        writer:setString(k .. "#fillType", ev.fillType)
        writer:setString(k .. "#sellPoint", ev.sellPoint)
        writer:setFloat(k .. "#gameTimeStart", ev.gameTimeStart)
        if ev.priceMode == "MULTIPLIER" then
            writer:setFloat(k .. "#peakMultiplier", ev.peakMultiplier)
            writer:setFloat(k .. "#rampUpHours", ev.rampUpHours)
            writer:setFloat(k .. "#holdHours", ev.holdHours)
            writer:setFloat(k .. "#decayHours", ev.decayHours)
        else
            writer:setFloat(k .. "#fixedPrice", ev.fixedPrice)
            writer:setFloat(k .. "#maxQuantity", ev.maxQuantity)
            writer:setFloat(k .. "#deadlineGameTime", ev.deadlineGameTime)
            writer:setFloat(k .. "#deliveredQuantity", ev.deliveredQuantity)
        end
    end
    for i, r in ipairs(state.contractReports) do
        local k = string.format("%s.contractReports.report(%d)", ROOT, i - 1)
        writer:setString(k .. "#id", r.instructionId)
        writer:setFloat(k .. "#deliveredQuantity", r.deliveredQuantity)
        writer:setFloat(k .. "#maxQuantity", r.maxQuantity)
        writer:setString(k .. "#endReason", r.endReason)
        writer:setFloat(k .. "#endedAtGameTime", r.endedAtGameTime or 0)
    end
end

function RPSimPersistence.load(reader, state)
    local sid = reader:getString(ROOT .. "#savegameId")
    if sid ~= nil and sid ~= "" then
        state.savegameId = sid
    end
    local i = 0
    while true do
        local k = string.format("%s.processedInstructions.entry(%d)", ROOT, i)
        local id = reader:getString(k .. "#id")
        if id == nil then break end
        state.processed[id] = { gameTime = reader:getFloat(k .. "#gameTime") or 0,
            status = reader:getString(k .. "#status") or "APPLIED", message = reader:getString(k .. "#message") }
        i = i + 1
    end
    i = 0
    while true do
        local k = string.format("%s.activePriceEvents.event(%d)", ROOT, i)
        local id = reader:getString(k .. "#id")
        if id == nil then break end
        local ev = { id = id, priceMode = reader:getString(k .. "#priceMode") or "MULTIPLIER",
            fillType = reader:getString(k .. "#fillType"), sellPoint = reader:getString(k .. "#sellPoint"),
            gameTimeStart = reader:getFloat(k .. "#gameTimeStart") or 0 }
        if ev.priceMode == "MULTIPLIER" then
            ev.peakMultiplier = reader:getFloat(k .. "#peakMultiplier") or 1
            ev.rampUpHours = reader:getFloat(k .. "#rampUpHours") or 0
            ev.holdHours = reader:getFloat(k .. "#holdHours") or 0
            ev.decayHours = reader:getFloat(k .. "#decayHours") or 0
        else
            ev.fixedPrice = reader:getFloat(k .. "#fixedPrice") or 0
            ev.maxQuantity = reader:getFloat(k .. "#maxQuantity") or 0
            ev.deadlineGameTime = reader:getFloat(k .. "#deadlineGameTime") or 0
            ev.deliveredQuantity = reader:getFloat(k .. "#deliveredQuantity") or 0
        end
        state.priceEvents:add(ev)
        i = i + 1
    end
    i = 0
    while true do
        local k = string.format("%s.contractReports.report(%d)", ROOT, i)
        local id = reader:getString(k .. "#id")
        if id == nil then break end
        state.contractReports[#state.contractReports + 1] = { instructionId = id,
            deliveredQuantity = reader:getFloat(k .. "#deliveredQuantity") or 0,
            maxQuantity = reader:getFloat(k .. "#maxQuantity") or 0,
            endReason = reader:getString(k .. "#endReason"),
            endedAtGameTime = reader:getFloat(k .. "#endedAtGameTime") or 0 }
        i = i + 1
    end
end
