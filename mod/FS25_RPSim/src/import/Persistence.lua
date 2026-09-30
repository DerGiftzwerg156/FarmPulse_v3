-- Savegame persistence of the mod state via the official XMLFile save/load hooks (NOT in the bridge).
-- Works against a minimal writer/reader interface so it is testable without FS25:
--   writer: setString(key, v), setInt(key, v), setFloat(key, v)
--   reader: getString(key), getInt(key), getFloat(key), hasProperty(key)
RPSimPersistence = {}

local ROOT = "FS25_RPSim"

--- "a,b" -> { "a", "b" } (nil / "" -> {}).
local function splitList(text)
    local list = {}
    for part in string.gmatch(text or "", "[^,]+") do
        list[#list + 1] = part
    end
    return list
end

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
        if e.result ~= nil then
            -- Roadmap V3 (R3-Q1): result of the action (e.g. vehicleId) as JSON, so the ack still carries it after loading
            writer:setString(k .. "#result", RPSimJson.encode(e.result))
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
    -- Roadmap V2 R2-A0 / R2-A4: employee list and worked game time
    local wf = state.workforce
    if wf ~= nil and wf.roster ~= nil then
        local k = ROOT .. ".workforce"
        writer:setString(k .. "#helperWageMode", wf.roster.helperWageMode or "VANILLA")
        writer:setString(k .. "#strictHelperLimit", wf.roster.strictHelperLimit and "true" or "false")
        for i, e in ipairs(wf.roster.employees) do
            local ek = string.format("%s.employee(%d)", k, i - 1)
            writer:setInt(ek .. "#id", e.employeeId)
            writer:setString(ek .. "#name", e.name)
            writer:setString(ek .. "#role", e.role)
            writer:setString(ek .. "#status", e.status)
            -- "Schulungen": finished trainings, comma separated
            local codes = {}
            for code, _ in pairs(e.trainings or {}) do
                codes[#codes + 1] = code
            end
            table.sort(codes)
            writer:setString(ek .. "#trainings", table.concat(codes, ","))
        end
        local codes = {}
        for code, _ in pairs(wf.roster.trainingCategories or {}) do
            codes[#codes + 1] = code
        end
        table.sort(codes)
        for i, code in ipairs(codes) do
            local tk = string.format("%s.trainingCategory(%d)", k, i - 1)
            writer:setString(tk .. "#training", code)
            writer:setString(tk .. "#categories", table.concat(wf.roster.trainingCategories[code], ","))
        end
    end
    if wf ~= nil then
        local workedIds = {}
        for id, _ in pairs(wf.workedGameMs) do
            workedIds[#workedIds + 1] = id
        end
        table.sort(workedIds)
        for i, id in ipairs(workedIds) do
            local wk = string.format("%s.workedTime.employee(%d)", ROOT, i - 1)
            writer:setString(wk .. "#id", id)
            writer:setFloat(wk .. "#gameMs", wf.workedGameMs[id])
        end
    end
    -- Roadmap V2 R2-B1: booking journal (a reload without saving goes back to these sums)
    for i, p in ipairs(state.financeJournal ~= nil and state.financeJournal.periods or {}) do
        local k = string.format("%s.financeJournal.period(%d)", ROOT, i - 1)
        writer:setInt(k .. "#year", p.year)
        writer:setInt(k .. "#period", p.period)
        local names = {}
        for name, _ in pairs(p.byType) do
            names[#names + 1] = name
        end
        table.sort(names)
        for j, name in ipairs(names) do
            local e = string.format("%s.booking(%d)", k, j - 1)
            writer:setString(e .. "#type", name)
            writer:setFloat(e .. "#amount", p.byType[name])
        end
    end
    -- Roadmap V2 R2-F: waiting questions, unacknowledged answers and handled question ids
    local prompts = state.prompts
    if prompts ~= nil then
        for i, q in ipairs(prompts.queue) do
            local k = string.format("%s.prompts.prompt(%d)", ROOT, i - 1)
            writer:setString(k .. "#id", q.promptId)
            writer:setString(k .. "#title", q.title)
            writer:setString(k .. "#text", q.text)
            if q.yesLabel ~= nil then
                writer:setString(k .. "#yesLabel", q.yesLabel)
            end
            if q.noLabel ~= nil then
                writer:setString(k .. "#noLabel", q.noLabel)
            end
            writer:setFloat(k .. "#expiresGameTime", q.expiresGameTime or 0)
        end
        for i, r in ipairs(prompts.responses) do
            local k = string.format("%s.prompts.response(%d)", ROOT, i - 1)
            writer:setString(k .. "#id", r.responseId)
            writer:setString(k .. "#promptId", r.promptId)
            writer:setString(k .. "#answer", r.answer)
            writer:setFloat(k .. "#gameTime", r.gameTime)
        end
        local handled = {}
        for id, _ in pairs(prompts.handled) do
            handled[#handled + 1] = id
        end
        table.sort(handled)
        for i, id in ipairs(handled) do
            local k = string.format("%s.prompts.handled(%d)", ROOT, i - 1)
            writer:setString(k .. "#id", id)
            writer:setFloat(k .. "#expiresGameTime", prompts.handled[id])
        end
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
        local resultJson = reader:getString(k .. "#result")
        if resultJson ~= nil and resultJson ~= "" then
            local res = RPSimJson.tryDecode(resultJson)
            state.processed[id].result = RPSimProcessor.normalizeResult(res)
        end
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
    state.workforce = RPSimWorkforce.new()
    local mode = reader:getString(ROOT .. ".workforce#helperWageMode")
    if mode ~= nil then
        local employees = {}
        i = 0
        while true do
            local ek = string.format("%s.workforce.employee(%d)", ROOT, i)
            local id = reader:getInt(ek .. "#id")
            if id == nil then break end
            employees[#employees + 1] = { employeeId = id, name = reader:getString(ek .. "#name") or "",
                role = reader:getString(ek .. "#role") or "", status = reader:getString(ek .. "#status") or "ACTIVE",
                trainings = splitList(reader:getString(ek .. "#trainings")) }
            i = i + 1
        end
        local trainingCategories = {}
        i = 0
        while true do
            local tk = string.format("%s.workforce.trainingCategory(%d)", ROOT, i)
            local code = reader:getString(tk .. "#training")
            if code == nil then break end
            trainingCategories[code] = splitList(reader:getString(tk .. "#categories"))
            i = i + 1
        end
        -- setRoster builds the training sets and the category index exactly as for a list from the backend
        RPSimWorkforce.setRoster(state.workforce, { employees = employees, helperWageMode = mode,
            strictHelperLimit = reader:getString(ROOT .. ".workforce#strictHelperLimit") == "true",
            trainingCategories = trainingCategories })
    end
    i = 0
    while true do
        local wk = string.format("%s.workedTime.employee(%d)", ROOT, i)
        local id = reader:getString(wk .. "#id")
        if id == nil then break end
        state.workforce.workedGameMs[id] = reader:getFloat(wk .. "#gameMs") or 0
        i = i + 1
    end
    i = 0
    state.financeJournal = RPSimFinanceJournal.new()
    while true do
        local k = string.format("%s.financeJournal.period(%d)", ROOT, i)
        local year = reader:getInt(k .. "#year")
        if year == nil then break end
        local p = { year = year, period = reader:getInt(k .. "#period") or 1, byType = {} }
        local j = 0
        while true do
            local e = string.format("%s.booking(%d)", k, j)
            local name = reader:getString(e .. "#type")
            if name == nil then break end
            p.byType[name] = (p.byType[name] or 0) + (reader:getFloat(e .. "#amount") or 0)
            j = j + 1
        end
        state.financeJournal.periods[#state.financeJournal.periods + 1] = p
        i = i + 1
    end
    -- Roadmap V2 R2-F
    state.prompts = RPSimPrompts.new()
    i = 0
    while true do
        local k = string.format("%s.prompts.prompt(%d)", ROOT, i)
        local id = reader:getString(k .. "#id")
        if id == nil then break end
        state.prompts.queue[#state.prompts.queue + 1] = { promptId = id, title = reader:getString(k .. "#title") or "",
            text = reader:getString(k .. "#text") or "", yesLabel = reader:getString(k .. "#yesLabel"),
            noLabel = reader:getString(k .. "#noLabel"), expiresGameTime = reader:getFloat(k .. "#expiresGameTime") }
        i = i + 1
    end
    i = 0
    while true do
        local k = string.format("%s.prompts.response(%d)", ROOT, i)
        local id = reader:getString(k .. "#id")
        if id == nil then break end
        state.prompts.responses[#state.prompts.responses + 1] = { responseId = id,
            promptId = reader:getString(k .. "#promptId") or "", answer = reader:getString(k .. "#answer") or "NO",
            gameTime = reader:getFloat(k .. "#gameTime") or 0 }
        i = i + 1
    end
    i = 0
    while true do
        local k = string.format("%s.prompts.handled(%d)", ROOT, i)
        local id = reader:getString(k .. "#id")
        if id == nil then break end
        state.prompts.handled[id] = reader:getFloat(k .. "#expiresGameTime") or 0
        i = i + 1
    end
end
