-- FS25_RPSim entry point: registers with the FS25 mod lifecycle and wires hooks.
-- Lifecycle: addModEventListener -> loadMap / update / deleteMap; persistence via
-- FSCareerMissionInfo.saveToXMLFile (appended) and the savegame XML loaded in loadMap.
-- TODO(offene-frage): hook names verified against FS22/FS25 community mods, not against an official FS25
-- scripting reference - see docs/dev/offene-technische-punkte.md.
-- luacheck: globals g_currentMission g_modSettingsDirectory addModEventListener Utils
-- luacheck: globals FSCareerMissionInfo SellingStation XMLFile getDate
RPSim = { modName = g_currentModName, modDirectory = g_currentModDirectory, bridge = nil }

local XML_NAME = "FS25_RPSim.xml"

local function savegameXmlPath()
    local dir = RPSim.adapter:getSavegameDirectory()
    if dir == nil then
        return nil
    end
    return dir .. "/" .. XML_NAME
end

local function loadConfig(paths)
    local text = RPSimFileIO.read(paths.configFile)
    local overrides = text ~= nil and RPSimJson.tryDecode(text) or nil
    return RPSimConfig.new(overrides)
end

function RPSim:loadMap(_)
    self.adapter = RPSimGameAdapter.new()
    local paths = RPSimBridgePaths.new(g_modSettingsDirectory or "./modSettings/")
    RPSimFileIO.ensureDir(paths.base)
    local cfg = loadConfig(paths)
    local state = RPSimProcessor.newState(cfg)

    local xmlPath = savegameXmlPath()
    if xmlPath ~= nil and XMLFile ~= nil then
        local xml = XMLFile.loadIfExists("RPSimState", xmlPath)
        if xml ~= nil then
            local ok, err = pcall(RPSimPersistence.load, xml, state)
            if not ok then
                RPSimLog.warning("Could not load savegame state: %s", tostring(err))
            end
            xml:delete()
        end
    end
    if state.savegameId == nil then
        local stamp = getDate ~= nil and getDate("%Y%m%d%H%M%S") or tostring(os.time())
        state.savegameId = RPSimBridge.generateSavegameId(self.adapter:getMapName(),
            self.adapter:getSavegameIndex(), stamp)
    end

    self.bridge = RPSimBridge.new(cfg, paths, self.adapter, state)
    self.bridge:bootstrap()
    self.bridge:onSavegameLoaded()
    RPSimLog.info("Loaded, savegameId=%s", state.savegameId)
end

function RPSim:update(dt)
    if self.bridge ~= nil then
        self.bridge:update(dt)
    end
end

function RPSim:deleteMap()
    self.bridge = nil
end

function RPSim.saveSavegame(_)
    if RPSim.bridge == nil or XMLFile == nil then
        return
    end
    local path = savegameXmlPath()
    if path == nil then
        return
    end
    local xml = XMLFile.create("RPSimState", path, "FS25_RPSim")
    if xml == nil then
        RPSimLog.warning("Could not create %s", path)
        return
    end
    RPSimPersistence.save(xml, RPSim.bridge.state)
    xml:save()
    xml:delete()
end

-- Price override: FIXED contracts / MULTIPLIER events at a single sell point.
local function effectivePriceHook(station, superFunc, fillTypeIndex, ...)
    local base = superFunc(station, fillTypeIndex, ...)
    if RPSim.bridge == nil or base == nil then
        return base
    end
    local name = g_fillTypeManager:getFillTypeNameByIndex(fillTypeIndex)
    return RPSim.bridge:effectivePrice(RPSimGameAdapter.sellPointId(station), name, base)
end

-- Quantity tracking for FIXED contracts.
local function sellFillTypeHook(station, farmId, deltaFillLevel, fillTypeIndex, ...)
    if RPSim.bridge ~= nil and deltaFillLevel ~= nil and deltaFillLevel > 0 then
        local name = g_fillTypeManager:getFillTypeNameByIndex(fillTypeIndex)
        RPSim.bridge:recordSale(RPSimGameAdapter.sellPointId(station), name, deltaFillLevel)
    end
    return farmId, deltaFillLevel, fillTypeIndex, ...
end

if SellingStation ~= nil and Utils ~= nil then
    SellingStation.getEffectiveFillTypePrice = Utils.overwrittenFunction(SellingStation.getEffectiveFillTypePrice,
        effectivePriceHook)
    SellingStation.sellFillType = Utils.prependedFunction(SellingStation.sellFillType,
        function(station, farmId, deltaFillLevel, fillTypeIndex, ...)
            sellFillTypeHook(station, farmId, deltaFillLevel, fillTypeIndex, ...)
        end)
end
if FSCareerMissionInfo ~= nil and Utils ~= nil then
    FSCareerMissionInfo.saveToXMLFile = Utils.appendedFunction(FSCareerMissionInfo.saveToXMLFile, RPSim.saveSavegame)
end
if addModEventListener ~= nil then
    addModEventListener(RPSim)
end
