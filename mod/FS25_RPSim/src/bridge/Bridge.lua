-- Orchestrates export and import cycles. Pure with respect to FS25: all game access goes through the
-- injected adapter, all file access through RPSimFileIO. This keeps the full cycle testable.
--
-- adapter interface:
--   getGameTime() -> number (in-game ms since savegame start, pauses with the game)
--   collectFarmFacts() -> raw table for RPSimFarmFacts.build (without savegameId/gameTime)
--   collectMarketContext() -> raw table for RPSimMarketContext.build (without savegameId)
--   addMoney(amount, reason, note) -> ok, err
--   checkBatchFunds(instructions) -> ok, err   (optional: refuses a batch whose debits exceed the balance)
--   transferFarmland(farmlandId, direction) -> ok, err
--   canShowPrompt(inVehicleAllowed) -> bool      (optional, R2-F2: no menu or dialog open, vehicle rule)
--   showYesNo(text, title, callback(yes)) -> ok, err (optional, R2-F2)
--   notify(text, level) -> ok, err                (optional)
--   collectNpcFields() -> list | nil              (optional, R3-H1)
--   transferStorage(fillType, amount, direction) -> ok, err          (optional, R3-H3/H4)
--   createMission(missionType, farmlandId) -> ok, err, {missionId}   (optional, R3-H5)
RPSimBridge = {}
RPSimBridge.__index = RPSimBridge

function RPSimBridge.new(cfg, paths, adapter, state)
    local self = setmetatable({}, RPSimBridge)
    self.cfg = cfg
    self.paths = paths
    self.adapter = adapter
    self.state = state or RPSimProcessor.newState(cfg)
    self.exportTimer = 0
    self.importTimer = 0
    self.bootstrapped = false
    -- The bridge only starts exporting/importing once the mission has started (T-01): during loadMap the
    -- farms, vehicles, placeables and selling stations of the savegame are not loaded yet.
    self.started = false
    self.lastMarketContextJson = nil
    -- Roadmap V2 R2-C1: fields are sampled every fieldExportIntervalMs and carried into every farm_facts export
    self.fieldCache = nil
    self.fieldTimer = 0
    return self
end

--- Creates the bridge folders on first start (missing folders must never crash the mod).
function RPSimBridge:bootstrap()
    for _, dir in ipairs({ self.paths.base, self.paths.exportDir, self.paths.importDir }) do
        local ok, err = RPSimFileIO.ensureDir(dir)
        if not ok then
            RPSimLog.warning("Could not create folder %s: %s", dir, tostring(err))
        end
    end
    self.bootstrapped = true
end

--- Encodes a document; returns the JSON text or nil.
function RPSimBridge:encode(path, doc)
    local ok, encoded = pcall(RPSimJson.encode, doc)
    if not ok then
        RPSimLog.warning("Could not encode %s: %s", path, tostring(encoded))
        return nil
    end
    return encoded
end

function RPSimBridge:writeJson(path, doc)
    local encoded = self:encode(path, doc)
    if encoded == nil then
        return false
    end
    return self:writeText(path, encoded)
end

function RPSimBridge:writeText(path, encoded)
    local wok, info = RPSimFileIO.write(path, encoded, self.cfg.atomicWriteMode)
    if not wok then
        RPSimLog.warning("Could not write %s: %s", path, tostring(info))
        -- Folder may have been deleted while the game runs: recreate and retry next cycle.
        self:bootstrap()
        return false
    end
    return true
end

function RPSimBridge:exportFarmFacts()
    local ok, raw = pcall(self.adapter.collectFarmFacts, self.adapter)
    if not ok or raw == nil then
        RPSimLog.warning("Collecting farm facts failed: %s", tostring(raw))
        return false
    end
    raw.savegameId = self.state.savegameId
    raw.gameTime = self.adapter:getGameTime()
    -- Roadmap V2 R2-B1: only exported while the booking hook runs; otherwise the block stays missing ("not present")
    if self.financeJournalEnabled and self.state.financeJournal ~= nil then
        raw.finances = RPSimFinanceJournal.toRaw(self.state.financeJournal)
    end
    if self.workforceEnabled then
        raw.workforce = self:sampleWorkforce(raw.gameTime)
    end
    local fields = self:sampleFields()
    if fields ~= nil then
        raw.fields, raw.fieldRules = fields.fields, fields.rules
        raw.npcFields = fields.npcFields -- Roadmap V3 R3-H1 (nil when switched off)
    end
    return self:writeJson(self.paths.farmFacts, RPSimFarmFacts.build(raw, self.cfg))
end

--- Roadmap V2 R2-C1: walking all fields is not free, so they are sampled only every fieldExportIntervalMs (real
-- time) or after a farmland transfer; every export in between carries the last sample. nil = no field manager.
function RPSimBridge:sampleFields()
    if self.fieldCache == nil or self.fieldTimer >= self.cfg.fieldExportIntervalMs then
        self.fieldTimer = 0
        local okFields, fields = pcall(self.adapter.collectFields, self.adapter)
        if not okFields or fields == nil then
            self.fieldCache = nil
            return nil
        end
        local okRules, rules = pcall(self.adapter.collectFieldRules, self.adapter)
        self.fieldCache = { fields = fields, rules = okRules and rules or nil }
        -- Roadmap V3 R3-H1: fields of the game's NPCs, same sampling (mod switch npcFieldExport)
        if self.cfg.npcFieldExport and self.adapter.collectNpcFields ~= nil then
            local okNpc, npc = pcall(self.adapter.collectNpcFields, self.adapter)
            self.fieldCache.npcFields = okNpc and npc or nil
        end
    end
    return self.fieldCache
end

--- Roadmap V2 R2-A2 / R2-A3 / R2-A4: running helper jobs of the player farm. Jobs that run after loading are assigned
-- now (job ids are new after a reload), the worked time since the last export is credited, and the helper limit of the
-- strict mode is set again (in case the game reset it).
function RPSimBridge:sampleWorkforce(gameTime)
    local wf = self.state.workforce
    local ok, jobs = pcall(self.adapter.collectAIJobs, self.adapter)
    if not ok or jobs == nil then
        jobs = {}
    end
    local ids = {}
    for _, j in ipairs(jobs) do
        RPSimWorkforce.assign(wf, j.jobId, RPSimWorkforce.requiredTrainings(wf, j.categories))
        ids[#ids + 1] = j.jobId
    end
    RPSimWorkforce.accrue(wf, ids, gameTime)
    if wf.roster ~= nil and self.adapter.applyHelperLimit ~= nil then
        self.adapter:applyHelperLimit(wf)
    end
    return RPSimWorkforce.toRaw(wf, jobs)
end

--- Roadmap V2 R2-A0: the complete employee list from the backend. Helpers of striking employees are stopped (R2-A5),
-- the helper limit follows the new list (R2-A3).
function RPSimBridge:applyRoster(ins)
    local wf = self.state.workforce
    local strike = RPSimWorkforce.setRoster(wf, ins)
    for _, jobId in ipairs(strike) do
        local name = RPSimWorkforce.helperName(wf, jobId)
        if self.adapter.stopStrikingJob ~= nil then
            self.adapter:stopStrikingJob(jobId, name)
        end
        RPSimWorkforce.release(wf, jobId)
    end
    if self.adapter.applyHelperLimit ~= nil then
        self.adapter:applyHelperLimit(wf)
    end
    RPSimLog.info("Employee list: %d employees, helper wage %s, strict limit %s", #wf.roster.employees,
        tostring(wf.roster.helperWageMode), tostring(wf.roster.strictHelperLimit))
    return true
end

--- Roadmap V2 R2-B1: one booking of the game (Farm.changeBalance). Only the player farm is recorded; bookings of the
-- tool itself (adapter.bookingReason set in addMoney) land under RPSIM_<REASON>.
function RPSimBridge:recordBooking(farmId, amount, moneyType)
    if not self.started or farmId ~= self.adapter:getFarmId() then
        return false
    end
    local year, period = self.adapter:currentPeriod()
    if year == nil then
        return false
    end
    local name = RPSimFinanceJournal.nameOf(self.adapter.bookingReason, moneyType, RPSimGameAdapter ~= nil
        and RPSimGameAdapter.moneyTypeName or nil)
    return RPSimFinanceJournal.record(self.state.financeJournal, year, period, name, amount,
        self.cfg.financeJournalPeriods)
end

--- Writes market_context.json, but only when its content changed since the last successful write (the
-- context is re-checked on every farm_facts export, T-01). force = true writes unconditionally.
-- Returns true when the file was written, false when unchanged or on error.
function RPSimBridge:exportMarketContext(force)
    local ok, raw = pcall(self.adapter.collectMarketContext, self.adapter, self.cfg.conflictMods)
    if not ok or raw == nil then
        RPSimLog.warning("Collecting market context failed: %s", tostring(raw))
        return false
    end
    raw.savegameId = self.state.savegameId
    raw.storeVehicles = self.storeVehicles -- R3-V1: read once at the mission start
    if raw.detectedMods ~= nil and #raw.detectedMods > 0 and not self.conflictsLogged then
        self.conflictsLogged = true
        RPSimLog.warning("Mods with overlapping features detected: %s", table.concat(raw.detectedMods, ", "))
    end
    local encoded = self:encode(self.paths.marketContext, RPSimMarketContext.build(raw, self.cfg))
    if encoded == nil then
        return false
    end
    if not force and encoded == self.lastMarketContextJson then
        return false
    end
    if not self:writeText(self.paths.marketContext, encoded) then
        return false
    end
    self.lastMarketContextJson = encoded
    return true
end

--- Called once the mission has started (Mission00.onStartMission, see RPSim.lua): immediate fresh export
-- instead of waiting for the next cycle (technical concept "savegameId-Schutz"), then the regular cycles run.
function RPSimBridge:onSavegameLoaded()
    if not self.bootstrapped then
        self:bootstrap()
    end
    self.started = true
    self.exportTimer = 0
    self.importTimer = 0
    if self.workforceEnabled and self.adapter.registerStrikeMessage ~= nil then
        self.adapter:registerStrikeMessage() -- R2-A5: the AI message manager exists once the mission runs
    end
    if self.workforceEnabled and self.adapter.registerHelperLimitMessage ~= nil then
        self.adapter:registerHelperLimitMessage() -- R2-A3
    end
    self:collectStoreCatalog()
    self:exportMarketContext(true)
    self:exportFarmFacts()
    self:writeAck()
    -- R2-F1: the file follows the loaded savegame (answers given after the last save are gone, the backend asks again)
    self:writeResponses()
    self:logFirstExport()
end

--- Roadmap V3 R3-V1: the vehicle catalog of the shop, read once per mission start (mod switch storeCatalogExport),
-- sorted by xmlFilename and cut to storeCatalogMaxEntries.
function RPSimBridge:collectStoreCatalog()
    self.storeVehicles = nil
    if not self.cfg.storeCatalogExport or self.adapter.collectStoreVehicles == nil then
        return
    end
    local ok, raw = pcall(self.adapter.collectStoreVehicles, self.adapter)
    if not ok or raw == nil then
        RPSimLog.warning("Reading the shop catalog failed: %s", tostring(raw))
        return
    end
    local list, dropped = RPSimMarketContext.capStoreVehicles(raw, self.cfg.storeCatalogMaxEntries)
    if dropped > 0 then
        RPSimLog.warning("Shop catalog: %d vehicles, %d left out (storeCatalogMaxEntries = %d)", #list + dropped,
            dropped, self.cfg.storeCatalogMaxEntries)
    end
    self.storeVehicles = list
end

--- Roadmap V3 R3-V2: outcome of an asynchronous instruction (VEHICLE_SPAWN) - recorded and acknowledged at once.
function RPSimBridge:completeInstruction(instructionId, ok, err, res)
    if RPSimProcessor.complete(self.state, instructionId, self.adapter:getGameTime(), ok, err, res) then
        self:writeAck()
    end
end

--- Logs what the first export saw (manual test plan: verifies that the savegame was fully loaded).
function RPSimBridge:logFirstExport()
    local ok, facts = pcall(self.adapter.collectFarmFacts, self.adapter)
    local okCtx, ctx = pcall(self.adapter.collectMarketContext, self.adapter, self.cfg.conflictMods)
    local vehicles = ok and facts ~= nil and #(facts.vehicles or {}) or -1
    local fields = ok and facts ~= nil and #(facts.farmland or {}) or -1
    local sellPoints = okCtx and ctx ~= nil and #(ctx.sellPoints or {}) or -1
    local farmlands = okCtx and ctx ~= nil and #(ctx.farmlands or {}) or -1
    RPSimLog.info("First export: %d sell points, %d farmlands on the map, %d own vehicles, %d own fields",
        sellPoints, farmlands, vehicles, fields)
    -- Stock (assets.storage): which storage places were found and whether they count
    local silos = ok and facts ~= nil and facts.silos or {}
    RPSimLog.info("Stock: %d storage places (silos, silo extensions, productions, bunker silos), %d fill types",
        #silos, #RPSimStorage.aggregate(silos))
    for _, line in ipairs(RPSimStorage.describe(silos)) do
        RPSimLog.info("  Storage %s", line)
    end
end

function RPSimBridge:writeAck()
    return self:writeJson(self.paths.instructionsAck, RPSimProcessor.buildAckDocument(self.state))
end

--- Reads and applies the instructions (instructions.xml wrapping the instructions.json document).
-- Malformed/partial files are skipped and retried next cycle.
function RPSimBridge:pollInstructions()
    local gameTime = self.adapter:getGameTime()
    local text = RPSimFileIO.readPayload(self.paths.instructions)
    local result
    if text ~= nil and text ~= "" then
        local doc, err = RPSimInstructions.parseDocument(text)
        if doc == nil then
            RPSimLog.warning("Skipping unreadable instructions (retry next cycle): %s", tostring(err))
        else
            local adapter = self.adapter
            result = RPSimProcessor.process(self.state, doc, {
                savegameId = self.state.savegameId,
                gameTime = gameTime,
                actions = {
                    checkBatch = adapter.checkBatchFunds ~= nil
                        and function(items) return adapter:checkBatchFunds(items) end or nil,
                    money = function(ins) return adapter:addMoney(ins.amount, ins.reason, ins.note) end,
                    farmlandTransfer = function(ins) return adapter:transferFarmland(ins.farmlandId, ins.direction) end,
                    notify = adapter.notify ~= nil and function(ins) return adapter:notify(ins.text, ins.level) end or nil,
                    employeeRoster = function(ins) return self:applyRoster(ins) end,
                    prompt = function(ins) return self:queuePrompt(ins, gameTime) end,
                    repairVehicle = adapter.repairVehicle ~= nil
                        and function(ins) return adapter:repairVehicle(ins.vehicleId, ins.targetDamage) end or nil,
                    -- Roadmap V3 R3-H3/H4 and R3-H5
                    storageTransfer = adapter.transferStorage ~= nil
                        and function(ins) return adapter:transferStorage(ins.fillType, ins.amount, ins.direction) end or nil,
                    missionCreate = adapter.createMission ~= nil
                        and function(ins) return adapter:createMission(ins.missionType, ins.farmlandId) end or nil,
                    -- Roadmap V3 R3-V2 (asynchronous: the loading callback books and acknowledges) and R3-V3
                    vehicleSpawn = adapter.spawnVehicle ~= nil and function(ins)
                        local id = ins.instructionId
                        local started, why = adapter:spawnVehicle(ins, function(done, why2, res)
                            self:completeInstruction(id, done, why2, res)
                        end)
                        if not started then
                            return false, why
                        end
                        return true, RPSimProcessor.PENDING
                    end or nil,
                    vehicleRemove = adapter.removeVehicle ~= nil
                        and function(ins) return adapter:removeVehicle(ins.vehicleId) end or nil,
                    -- Roadmap V3.1 R31-A1, R31-A3 and R31-D8
                    fieldWork = adapter.fieldWork ~= nil and function(ins) return adapter:fieldWork(ins) end or nil,
                    animalTransfer = adapter.animalTransfer ~= nil
                        and function(ins) return adapter:animalTransfer(ins) end or nil,
                    vehicleFuel = adapter.vehicleFuel ~= nil
                        and function(ins) return adapter:vehicleFuel(ins) end or nil,
                },
            })
            -- R2-F1 / R2-F2: processed answers and questions decided in the browser
            if doc.savegameId == self.state.savegameId
                    and RPSimPrompts.applyDocument(self.state.prompts, doc, gameTime) then
                self:writeResponses()
            end
            if result.marketContextDirty then
                self.fieldCache = nil -- R2-C1: the owned fields changed
                -- Re-export right after every applied FARMLAND_TRANSFER.
                self:exportMarketContext(true)
            end
        end
    end
    RPSimProcessor.collectContractReports(self.state, gameTime)
    RPSimProcessor.prune(self.state, gameTime, self.cfg.processedRetentionGameDays)
    RPSimPrompts.prune(self.state.prompts, gameTime)
    self:writeAck()
    return result
end

--- Frame update with real-time delta in ms. Does nothing until the mission has started.
function RPSimBridge:update(dtMs)
    if not self.started then
        self.startWaitMs = (self.startWaitMs or 0) + dtMs
        if self.startWaitMs >= self.cfg.startFallbackMs then
            RPSimLog.warning("Mission start was not signalled after %d ms - starting the bridge", self.startWaitMs)
            self:onSavegameLoaded()
        end
        return
    end
    self.exportTimer = self.exportTimer + dtMs
    self.importTimer = self.importTimer + dtMs
    self.fieldTimer = self.fieldTimer + dtMs
    if self.importTimer >= self.cfg.importIntervalMs then
        self.importTimer = 0
        self:pollInstructions()
    end
    if self.exportTimer >= self.cfg.exportIntervalMs then
        self.exportTimer = 0
        self:exportFarmFacts()
        -- Keeps sell points/farmlands current (e.g. placeables bought later); written only when changed.
        self:exportMarketContext()
    end
    self:updatePrompts(false)
end

-- ------------------------------------------------------------------ Roadmap V2 R2-F: questions in the game

--- PROMPT instruction: queued; an expired one is dropped without being shown. When the question cannot be shown right
-- now and the key of R2-F3 exists, a short notification names the key.
function RPSimBridge:queuePrompt(ins, gameTime)
    local ok, note = RPSimPrompts.enqueue(self.state.prompts, ins, gameTime)
    if note == nil and self.promptKeyAvailable and not self:promptCanShow(false)
            and self.adapter.notify ~= nil then
        self.adapter:notify(string.format("FarmPulse: %s – Taste „FarmPulse: offene Frage“ öffnet sie", ins.title),
            "INFO")
    end
    return ok, note
end

function RPSimBridge:promptCanShow(byKey)
    if self.promptsUnsupported or self.adapter.canShowPrompt == nil or self.adapter.showYesNo == nil then
        return false
    end
    local ok, can = pcall(self.adapter.canShowPrompt, self.adapter, byKey or self.cfg.promptsInVehicle)
    return ok and can == true
end

--- Frame update (automatic display, R2-F2) and key press (R2-F3, byKey = true: also inside a vehicle).
-- Returns true when a dialog was opened.
function RPSimBridge:updatePrompts(byKey)
    local prompts = self.state.prompts
    if prompts.shown ~= nil then
        return false
    end
    local nextPrompt = RPSimPrompts.nextPrompt(prompts, self.adapter:getGameTime())
    if self.adapter.setPromptKeyVisible ~= nil then
        self.adapter:setPromptKeyVisible(nextPrompt ~= nil)
    end
    if nextPrompt == nil or not self:promptCanShow(byKey) then
        return false
    end
    local promptId = nextPrompt.promptId
    prompts.shown = promptId
    local ok, err = self.adapter:showYesNo(RPSimPrompts.dialogText(nextPrompt), nextPrompt.title, function(yes)
        self:onPromptAnswer(promptId, yes)
    end)
    if not ok then
        prompts.shown = nil
        self.promptsUnsupported = true -- no dialog in this game version: the questions stay in the browser
        RPSimLog.warning("Yes/no dialog not available, questions stay in the browser: %s", tostring(err))
        return false
    end
    return true
end

function RPSimBridge:openNextPrompt()
    return self:updatePrompts(true)
end

--- Dialog callback: the answer goes to the backend at once (not with the next regular export).
function RPSimBridge:onPromptAnswer(promptId, yes)
    local r = RPSimPrompts.answer(self.state.prompts, promptId, yes, self.adapter:getGameTime())
    if r ~= nil then
        RPSimLog.info("Answer %s to question %s", r.answer, promptId)
        self:writeResponses()
    end
end

function RPSimBridge:writeResponses()
    return self:writeJson(self.paths.playerResponses,
        RPSimPrompts.toDocument(self.state.prompts, self.state.savegameId))
end

--- Price hook entry point (called from the SellingStation override).
function RPSimBridge:effectivePrice(sellPointId, fillTypeName, basePricePerLiter)
    return self.state.priceEvents:effectivePrice(sellPointId, fillTypeName, basePricePerLiter,
        self.adapter:getGameTime())
end

--- Sale hook entry point for FIXED contract quantity tracking.
function RPSimBridge:recordSale(sellPointId, fillTypeName, liters)
    return self.state.priceEvents:recordSale(sellPointId, fillTypeName, liters, self.adapter:getGameTime())
end

--- Stable savegame id: kept from the savegame XML, otherwise generated once.
function RPSimBridge.generateSavegameId(mapName, savegameIndex, timestamp)
    local slug = string.lower(tostring(mapName or "map")):gsub("[^%w]+", "_"):gsub("^_+", ""):gsub("_+$", "")
    return string.format("map_%s_%s_%s", slug, tostring(savegameIndex or 0), tostring(timestamp or "0"))
end
