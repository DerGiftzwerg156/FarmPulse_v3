-- Applies instructions idempotently and builds the ack document
-- (technical concept "Ack & Idempotenz" + "savegameId-Schutz").
--
-- The ONLY source of truth for "already executed" is state.processed (persisted in the savegame
-- via XMLFile, never in the bridge). The ack file is pure backend bookkeeping.
RPSimProcessor = {}

--- Creates the mutable mod state that is persisted in the savegame.
function RPSimProcessor.newState(cfg)
    return {
        savegameId = nil,
        processed = {},        -- [instructionId] = { gameTime, status, message }
        contractReports = {},  -- list of ended FIXED contract reports (kept until retention)
        priceEvents = RPSimPriceEvents.new(cfg),
    }
end

local function markAll(state, items, gameTime, status, message)
    for _, ins in ipairs(items) do
        if type(ins) == "table" and type(ins.instructionId) == "string" then
            state.processed[ins.instructionId] = { gameTime = gameTime, status = status, message = message }
        end
    end
end

--- Processes a parsed instructions document.
-- ctx = { savegameId, gameTime, actions = { money = fn(ins) -> ok, err ; farmlandTransfer = fn(ins) -> ok, err } }
-- Returns result { discarded, applied, rejected, deferred, marketContextDirty }.
function RPSimProcessor.process(state, doc, ctx)
    local result = { discarded = false, applied = 0, rejected = 0, deferred = 0, duplicates = 0,
        marketContextDirty = false }
    if doc.savegameId ~= ctx.savegameId then
        RPSimLog.warning("Discarding instructions.json: savegameId '%s' does not match active savegame '%s'",
            tostring(doc.savegameId), tostring(ctx.savegameId))
        result.discarded = true
        return result
    end
    local seen = {}
    for _, batch in ipairs(RPSimInstructions.groupBatches(doc.instructions)) do
        local pending = {}
        for _, ins in ipairs(batch.items) do
            local id = type(ins) == "table" and ins.instructionId or nil
            if type(id) == "string" and (state.processed[id] or seen[id]) then
                result.duplicates = result.duplicates + 1
            else
                if type(id) == "string" then
                    seen[id] = true
                end
                pending[#pending + 1] = ins
            end
        end
        if #pending > 0 then
            -- 1) validate the complete batch (all-or-nothing)
            local invalid
            for _, ins in ipairs(pending) do
                local ok, why = RPSimInstructions.validate(ins)
                if ok and ins.savegameId ~= nil and ins.savegameId ~= ctx.savegameId then
                    ok, why = false, "savegameId mismatch"
                end
                if not ok then
                    invalid = string.format("%s: %s", tostring(type(ins) == "table" and ins.instructionId), why)
                    break
                end
            end
            -- 2) respect gameTimeEarliest (whole batch waits for its latest member)
            local notYet = false
            if invalid == nil then
                for _, ins in ipairs(pending) do
                    if ins.gameTimeEarliest ~= nil and ins.gameTimeEarliest > ctx.gameTime then
                        notYet = true
                    end
                end
            end
            if invalid ~= nil then
                RPSimLog.warning("Rejecting batch %s: %s", tostring(batch.key), invalid)
                markAll(state, pending, ctx.gameTime, "REJECTED", invalid)
                result.rejected = result.rejected + #pending
            elseif notYet then
                result.deferred = result.deferred + #pending
            else
                -- 3) apply every member of the batch in the same cycle
                for _, ins in ipairs(pending) do
                    local ok, err = RPSimProcessor.applyOne(state, ins, ctx)
                    if ok then
                        state.processed[ins.instructionId] = { gameTime = ctx.gameTime, status = "APPLIED" }
                        result.applied = result.applied + 1
                        if ins.type == "FARMLAND_TRANSFER" then
                            result.marketContextDirty = true
                        end
                    else
                        RPSimLog.warning("Instruction %s failed: %s", ins.instructionId, tostring(err))
                        state.processed[ins.instructionId] = { gameTime = ctx.gameTime, status = "FAILED",
                            message = tostring(err) }
                        result.rejected = result.rejected + 1
                    end
                end
            end
        end
    end
    return result
end

function RPSimProcessor.applyOne(state, ins, ctx)
    if ins.type == "MONEY_TRANSACTION" then
        return ctx.actions.money(ins)
    elseif ins.type == "FARMLAND_TRANSFER" then
        return ctx.actions.farmlandTransfer(ins)
    elseif ins.type == "PRICE_EVENT" then
        local start = ins.gameTimeEarliest or ctx.gameTime
        state.priceEvents:addFromInstruction(ins, start)
        return true
    end
    return false, "unsupported type"
end

--- Moves ended FIXED contracts into the report list (reverse channel for delivered quantities).
function RPSimProcessor.collectContractReports(state, gameTime)
    for _, r in ipairs(state.priceEvents:collectEnded(gameTime)) do
        state.contractReports[#state.contractReports + 1] = r
    end
end

--- Removes processed entries and contract reports older than the retention window.
function RPSimProcessor.prune(state, gameTime, retentionGameDays)
    local limit = gameTime - retentionGameDays * RPSimConfig.MS_PER_GAME_DAY
    for id, entry in pairs(state.processed) do
        if entry.gameTime < limit then
            state.processed[id] = nil
        end
    end
    local keep = {}
    for _, r in ipairs(state.contractReports) do
        if (r.endedAtGameTime or gameTime) >= limit then
            keep[#keep + 1] = r
        end
    end
    state.contractReports = keep
end

--- Builds instructions_ack.json. Contains every instruction still in the retention window, so a
-- missed ack file version never loses information.
function RPSimProcessor.buildAckDocument(state)
    local acks = RPSimJson.array({})
    for id, entry in pairs(state.processed) do
        local a = { instructionId = id, appliedAtGameTime = entry.gameTime, status = entry.status }
        if entry.message ~= nil then
            a.message = entry.message
        end
        acks[#acks + 1] = a
    end
    table.sort(acks, function(a, b) return a.instructionId < b.instructionId end)
    local reports = RPSimJson.array({})
    for _, r in ipairs(state.contractReports) do
        reports[#reports + 1] = { instructionId = r.instructionId, deliveredQuantity = r.deliveredQuantity,
            maxQuantity = r.maxQuantity, endReason = r.endReason }
    end
    return { savegameId = state.savegameId, acks = acks, contractReports = reports }
end
