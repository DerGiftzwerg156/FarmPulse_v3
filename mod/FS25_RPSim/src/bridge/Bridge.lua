-- Orchestrates export and import cycles. Pure with respect to FS25: all game access goes through the
-- injected adapter, all file access through RPSimFileIO. This keeps the full cycle testable.
--
-- adapter interface:
--   getGameTime() -> number (in-game ms since savegame start, pauses with the game)
--   collectFarmFacts() -> raw table for RPSimFarmFacts.build (without savegameId/gameTime)
--   collectMarketContext() -> raw table for RPSimMarketContext.build (without savegameId)
--   addMoney(amount, reason, note) -> ok, err
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

function RPSimBridge:writeJson(path, doc)
    local ok, encoded = pcall(RPSimJson.encode, doc)
    if not ok then
        RPSimLog.warning("Could not encode %s: %s", path, tostring(encoded))
        return false
    end
    local wok, info = RPSimFileIO.writeAtomic(path, encoded, self.cfg.atomicWriteMode)
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

function RPSimBridge:exportMarketContext()
    local ok, raw = pcall(self.adapter.collectMarketContext, self.adapter)
    if not ok or raw == nil then
        RPSimLog.warning("Collecting market context failed: %s", tostring(raw))
        return false
    end
    raw.savegameId = self.state.savegameId
    return self:writeJson(self.paths.marketContext, RPSimMarketContext.build(raw))
end

--- Called when a savegame has been loaded: immediate fresh export instead of waiting for the next cycle
-- (technical concept "savegameId-Schutz").
function RPSimBridge:onSavegameLoaded()
    if not self.bootstrapped then
        self:bootstrap()
    end
    self:exportMarketContext()
    self:exportFarmFacts()
    self:writeAck()
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
                    money = function(ins) return adapter:addMoney(ins.amount, ins.reason, ins.note) end,
                    farmlandTransfer = function(ins) return adapter:transferFarmland(ins.farmlandId, ins.direction) end,
                },
            })
            if result.marketContextDirty then
                -- Re-export right after every applied FARMLAND_TRANSFER.
                self:exportMarketContext()
            end
        end
    end
    RPSimProcessor.collectContractReports(self.state, gameTime)
    RPSimProcessor.prune(self.state, gameTime, self.cfg.processedRetentionGameDays)
    self:writeAck()
    return result
end

--- Frame update with real-time delta in ms.
function RPSimBridge:update(dtMs)
    self.exportTimer = self.exportTimer + dtMs
    self.importTimer = self.importTimer + dtMs
    if self.importTimer >= self.cfg.importIntervalMs then
        self.importTimer = 0
        self:pollInstructions()
    end
    if self.exportTimer >= self.cfg.exportIntervalMs then
        self.exportTimer = 0
        self:exportFarmFacts()
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
