-- Applies instructions idempotently and builds the ack document
-- (technical concept "Ack & Idempotenz" + "savegameId-Schutz").
--
-- The ONLY source of truth for "already executed" is state.processed (persisted in the savegame
-- via XMLFile, never in the bridge). The ack file is pure backend bookkeeping.
RPSimProcessor = {}

--- Roadmap V3 R3-V2: second return value of an action whose result follows later (VEHICLE_SPAWN loads
-- asynchronously). The instruction stays PENDING - skipped as duplicate, left out of the ack and the savegame - until
-- RPSimProcessor.complete records the outcome.
RPSimProcessor.PENDING = "PENDING"

--- Creates the mutable mod state that is persisted in the savegame.
function RPSimProcessor.newState(cfg)
    return {
        savegameId = nil,
        processed = {},        -- [instructionId] = { gameTime, status, message }
        contractReports = {},  -- list of ended FIXED contract reports (kept until retention)
        priceEvents = RPSimPriceEvents.new(cfg),
        financeJournal = RPSimFinanceJournal.new(), -- Roadmap V2 R2-B1
        workforce = RPSimWorkforce.new(), -- Roadmap V2 R2-A0..A5
        prompts = RPSimPrompts.new(), -- Roadmap V2 R2-F1 / R2-F2
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
-- ctx = { savegameId, gameTime, actions = { money = fn(ins) -> ok, err ; farmlandTransfer = fn(ins) -> ok, err ;
--         checkBatch = optional fn(items) -> ok, err (e.g. INSUFFICIENT_FUNDS) } }
-- Returns result { discarded, applied, rejected, deferred, marketContextDirty }.
function RPSimProcessor.process(state, doc, ctx)
    local result = { discarded = false, applied = 0, rejected = 0, deferred = 0, duplicates = 0, pending = 0,
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
            -- 3) optional adapter pre-check (funds) for the whole batch
            local applicable, why = true, nil
            if invalid == nil and not notYet then
                applicable, why = RPSimProcessor.batchApplicable(pending, ctx)
            end
            if invalid ~= nil then
                RPSimLog.warning("Rejecting batch %s: %s", tostring(batch.key), invalid)
                markAll(state, pending, ctx.gameTime, "REJECTED", invalid)
                result.rejected = result.rejected + #pending
            elseif notYet then
                result.deferred = result.deferred + #pending
            elseif not applicable then
                -- e.g. not enough money for the debits of the batch: nothing of the batch is executed, so
                -- ownership and money never diverge. The backend treats it like a missed payment (T-03).
                RPSimLog.warning("Batch %s not executed: %s", tostring(batch.key), tostring(why))
                markAll(state, pending, ctx.gameTime, "FAILED", tostring(why))
                result.rejected = result.rejected + #pending
            else
                -- 4) apply every member of the batch in the same cycle; after a failure the remaining members are
                --    not executed (the backend always puts the FARMLAND_TRANSFER before its MONEY_TRANSACTION)
                local aborted = nil
                for _, ins in ipairs(pending) do
                    local ok, err, res
                    if aborted ~= nil then
                        ok, err = false, "BATCH_ABORTED: " .. aborted
                    else
                        ok, err, res = RPSimProcessor.applyOne(state, ins, ctx)
                        if not ok and #pending > 1 then
                            aborted = tostring(ins.instructionId)
                        end
                    end
                    if ok and err == RPSimProcessor.PENDING then
                        -- R3-V2: completed later; the outcome may already be there when the callback ran at once
                        if state.processed[ins.instructionId] == nil then
                            state.processed[ins.instructionId] = { gameTime = ctx.gameTime, status = "PENDING" }
                        end
                        result.pending = result.pending + 1
                    elseif ok then
                        -- err doubles as an optional note on success (e.g. NOTIFICATION "EXPIRED")
                        -- Roadmap V3 (R3-Q1): optional result of the action (e.g. vehicleId, missionId) for the ack
                        state.processed[ins.instructionId] = { gameTime = ctx.gameTime, status = "APPLIED",
                            message = err, result = RPSimProcessor.normalizeResult(res) }
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

--- Runs the optional batch pre-check of the adapter. Returns ok, err.
function RPSimProcessor.batchApplicable(items, ctx)
    local check = ctx.actions ~= nil and ctx.actions.checkBatch or nil
    if check == nil then
        return true
    end
    local ok, res, err = pcall(check, items)
    if not ok then
        return false, tostring(res)
    end
    if res == false then
        return false, err or "batch check failed"
    end
    return true
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
    elseif ins.type == "REPAIR_VEHICLE" then
        if ctx.actions.repairVehicle == nil then
            return false, "NOT_SUPPORTED"
        end
        return ctx.actions.repairVehicle(ins)
    elseif ins.type == "NOTIFICATION" then
        -- TODO T-21: a hint that arrives too late (e.g. after loading an older savegame) is not shown
        if ins.expiresAtGameTime ~= nil and ctx.gameTime > ins.expiresAtGameTime then
            return true, "EXPIRED"
        end
        if ctx.actions.notify == nil then
            return true, "NOT_SUPPORTED"
        end
        return ctx.actions.notify(ins)
    elseif ins.type == "EMPLOYEE_ROSTER" or ins.type == "PROMPT" then
        -- Roadmap V2: EMPLOYEE_ROSTER (R2-A0) replaces the list, PROMPT (R2-F2) is queued for the dialog
        local action = ctx.actions.prompt
        if ins.type == "EMPLOYEE_ROSTER" then
            action = ctx.actions.employeeRoster
        end
        if action == nil then
            return false, "NOT_SUPPORTED"
        end
        return action(ins)
    elseif RPSimProcessor.V3_ACTIONS[ins.type] ~= nil or RPSimProcessor.V31_ACTIONS[ins.type] ~= nil then
        -- Roadmap V3 (R3-Q1) / V3.1 (R31-Q1): executed once the feature brings its adapter action; until then
        -- NOT_SUPPORTED
        local action = ctx.actions[RPSimProcessor.V3_ACTIONS[ins.type] or RPSimProcessor.V31_ACTIONS[ins.type]]
        if action == nil then
            return false, "NOT_SUPPORTED"
        end
        return action(ins)
    end
    return false, "unsupported type"
end

--- Roadmap V3 (R3-Q1): instruction type -> adapter action (ctx.actions) that executes it. An action returns
-- ok, err, result; result is an optional object for the ack (vehicleId after VEHICLE_SPAWN, missionId after
-- MISSION_CREATE).
RPSimProcessor.V3_ACTIONS = { STORAGE_TRANSFER = "storageTransfer", MISSION_CREATE = "missionCreate",
    VEHICLE_SPAWN = "vehicleSpawn", VEHICLE_REMOVE = "vehicleRemove" }

--- Roadmap V3.1 (R31-Q1): the same for FIELD_WORK (R31-A1), ANIMAL_TRANSFER (R31-A3) and VEHICLE_FUEL (R31-D8, result
-- { liters } = diesel actually taken). Without an action: acknowledged FAILED / NOT_SUPPORTED.
RPSimProcessor.V31_ACTIONS = { FIELD_WORK = "fieldWork", ANIMAL_TRANSFER = "animalTransfer",
    VEHICLE_FUEL = "vehicleFuel" }

--- Roadmap V3 R3-V2: records the outcome of an asynchronous action (see PENDING). Ignored when the instruction already
-- has a final status. Returns true when recorded.
function RPSimProcessor.complete(state, instructionId, gameTime, ok, err, res)
    local entry = state.processed[instructionId]
    if entry ~= nil and entry.status ~= "PENDING" then
        return false
    end
    if ok then
        state.processed[instructionId] = { gameTime = gameTime, status = "APPLIED", message = err,
            result = RPSimProcessor.normalizeResult(res) }
    else
        RPSimLog.warning("Instruction %s failed: %s", tostring(instructionId), tostring(err))
        state.processed[instructionId] = { gameTime = gameTime, status = "FAILED", message = tostring(err) }
    end
    return true
end

--- Keeps only the plain values (string, number, boolean) of an action result; nil when nothing is left.
function RPSimProcessor.normalizeResult(res)
    if type(res) ~= "table" then
        return nil
    end
    local out, any = {}, false
    for k, v in pairs(res) do
        local t = type(v)
        if type(k) == "string" and (t == "string" or t == "boolean" or (t == "number" and v == v)) then
            out[k] = v
            any = true
        end
    end
    if not any then
        return nil
    end
    return out
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
        -- R3-V2: no ack until the loading callback reported
        if entry.status ~= "PENDING" then
            local a = { instructionId = id, appliedAtGameTime = entry.gameTime, status = entry.status }
            if entry.message ~= nil then
                a.message = entry.message
            end
            if entry.result ~= nil then
                a.result = entry.result -- Roadmap V3 (R3-Q1)
            end
            acks[#acks + 1] = a
        end
    end
    table.sort(acks, function(a, b) return a.instructionId < b.instructionId end)
    local reports = RPSimJson.array({})
    for _, r in ipairs(state.contractReports) do
        reports[#reports + 1] = { instructionId = r.instructionId, deliveredQuantity = r.deliveredQuantity,
            maxQuantity = r.maxQuantity, endReason = r.endReason }
    end
    return { savegameId = state.savegameId, acks = acks, contractReports = reports }
end
