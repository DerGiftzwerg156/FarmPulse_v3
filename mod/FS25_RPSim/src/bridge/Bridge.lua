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
    return self:writeJson(self.paths.farmFacts, RPSimFarmFacts.build(raw, self.cfg))
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
    if raw.detectedMods ~= nil and #raw.detectedMods > 0 and not self.conflictsLogged then
        self.conflictsLogged = true
        RPSimLog.warning("Mods with overlapping features detected: %s", table.concat(raw.detectedMods, ", "))
    end
    local encoded = self:encode(self.paths.marketContext, RPSimMarketContext.build(raw))
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
    self:exportMarketContext(true)
    self:exportFarmFacts()
    self:writeAck()
    self:logFirstExport()
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
end

function RPSimBridge:writeAck()
    return self:writeJson(self.paths.instructionsAck, RPSimProcessor.buildAckDocument(self.state))
end

--- Reads and applies instructions.json. Malformed/partial files are skipped and retried next cycle.
function RPSimBridge:pollInstructions()
    local gameTime = self.adapter:getGameTime()
    local text = RPSimFileIO.read(self.paths.instructions)
    local result
    if text ~= nil and text ~= "" then
        local doc, err = RPSimInstructions.parseDocument(text)
        if doc == nil then
            RPSimLog.warning("Skipping unreadable instructions.json (retry next cycle): %s", tostring(err))
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
                },
            })
            if result.marketContextDirty then
                -- Re-export right after every applied FARMLAND_TRANSFER.
                self:exportMarketContext(true)
            end
        end
    end
    RPSimProcessor.collectContractReports(self.state, gameTime)
    RPSimProcessor.prune(self.state, gameTime, self.cfg.processedRetentionGameDays)
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
