-- Roadmap V2 R2-F: yes/no questions of the backend (PROMPT) shown in the game one at a time, and the answers written
-- back to the backend (export/player_responses.json). Pure state and rules; showing the dialog is the adapter's job.
--
-- state = {
--   queue = { prompt, ... },            -- waiting questions in arrival order
--   responses = { response, ... },      -- answers the backend has not acknowledged yet (ackedResponses)
--   handled = { [promptId] = expires }, -- answered or withdrawn: a PROMPT sent again is not queued again
--   shown = promptId | nil,             -- the question whose dialog is open right now
-- }
RPSimPrompts = {}

function RPSimPrompts.new()
    return { queue = {}, responses = {}, handled = {}, shown = nil }
end

local function indexOf(queue, promptId)
    for i, p in ipairs(queue) do
        if p.promptId == promptId then
            return i
        end
    end
    return nil
end

--- Queues a PROMPT instruction. Returns true, note: "EXPIRED" = dropped without being shown (R2-F2), "DUPLICATE" =
-- already queued or already answered / withdrawn.
function RPSimPrompts.enqueue(state, ins, gameTime)
    if ins.expiresGameTime ~= nil and gameTime > ins.expiresGameTime then
        return true, "EXPIRED"
    end
    if state.handled[ins.promptId] ~= nil or indexOf(state.queue, ins.promptId) ~= nil then
        return true, "DUPLICATE"
    end
    state.queue[#state.queue + 1] = { promptId = ins.promptId, title = ins.title, text = ins.text,
        yesLabel = ins.yesLabel, noLabel = ins.noLabel, expiresGameTime = ins.expiresGameTime }
    return true
end

--- Drops expired questions; returns the next one to show or nil.
function RPSimPrompts.nextPrompt(state, gameTime)
    local keep = {}
    for _, p in ipairs(state.queue) do
        if p.expiresGameTime == nil or gameTime <= p.expiresGameTime or p.promptId == state.shown then
            keep[#keep + 1] = p
        end
    end
    state.queue = keep
    for _, p in ipairs(state.queue) do
        if p.promptId ~= state.shown then
            return p
        end
    end
    return nil
end

function RPSimPrompts.hasWaiting(state)
    for _, p in ipairs(state.queue) do
        if p.promptId ~= state.shown then
            return true
        end
    end
    return false
end

--- Owner decision: the dialog shows the game's own yes / no buttons (custom button texts are not evidenced in the
-- FS25 code); the meaning of the buttons is appended to the question.
function RPSimPrompts.dialogText(prompt)
    if prompt.yesLabel == nil and prompt.noLabel == nil then
        return prompt.text
    end
    return string.format("%s\n\nJa = %s · Nein = %s", prompt.text, prompt.yesLabel or "Ja", prompt.noLabel or "Nein")
end

--- The player answered in the dialog. Returns the new response (nil for an unknown question).
function RPSimPrompts.answer(state, promptId, yes, gameTime)
    if state.shown == promptId then
        state.shown = nil
    end
    local i = indexOf(state.queue, promptId)
    if i == nil then
        return nil
    end
    local p = table.remove(state.queue, i)
    state.handled[promptId] = p.expiresGameTime or gameTime
    local r = { responseId = "rsp_" .. promptId, promptId = promptId, answer = yes and "YES" or "NO",
        gameTime = gameTime }
    state.responses[#state.responses + 1] = r
    return r
end

--- instructions.json: ackedResponses (processed by the backend) and withdrawnPrompts (decided in the browser, owner
-- decision). Returns true when the list of open responses changed (the file must be written again).
function RPSimPrompts.applyDocument(state, doc, gameTime)
    local changed = false
    local acked = {}
    for _, id in ipairs(type(doc.ackedResponses) == "table" and doc.ackedResponses or {}) do
        acked[id] = true
    end
    if next(acked) ~= nil then
        local keep = {}
        for _, r in ipairs(state.responses) do
            if acked[r.responseId] then
                changed = true
            else
                keep[#keep + 1] = r
            end
        end
        state.responses = keep
    end
    for _, id in ipairs(type(doc.withdrawnPrompts) == "table" and doc.withdrawnPrompts or {}) do
        if type(id) == "string" then
            local i = indexOf(state.queue, id)
            -- an open dialog stays; the backend ignores its answer
            if i ~= nil and state.queue[i].promptId ~= state.shown then
                local p = table.remove(state.queue, i)
                state.handled[id] = p.expiresGameTime or gameTime
            end
        end
    end
    return changed
end

--- Forgets handled question ids once they are expired (the backend never sends them again after that).
function RPSimPrompts.prune(state, gameTime)
    for id, expires in pairs(state.handled) do
        if expires < gameTime then
            state.handled[id] = nil
        end
    end
end

function RPSimPrompts.toDocument(state, savegameId)
    local responses = RPSimJson.array({})
    for _, r in ipairs(state.responses) do
        responses[#responses + 1] = { responseId = r.responseId, promptId = r.promptId, answer = r.answer,
            gameTime = r.gameTime }
    end
    return { savegameId = savegameId, responses = responses }
end
