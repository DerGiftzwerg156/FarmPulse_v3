-- FS25_RPSim entry point: registers with the FS25 mod lifecycle and wires hooks.
-- Lifecycle: addModEventListener -> loadMap / update / deleteMap; the first export runs in
-- Mission00.onStartMission (farms, vehicles and placeables of the savegame exist only from then on - same
-- pattern as FS25_UsedPlus and FS25_MarketDynamics). Persistence via FSCareerMissionInfo.saveToXMLFile
-- (appended) and the savegame XML loaded in loadMap.
-- luacheck: globals g_currentMission g_modSettingsDirectory addModEventListener Utils getUserProfileAppPath
-- luacheck: globals FSCareerMissionInfo SellingStation XMLFile getDate Mission00
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

local function modSettingsDir()
    local profile = getUserProfileAppPath ~= nil and getUserProfileAppPath() or nil
    return RPSimBridgePaths.resolveModSettingsDir(g_modSettingsDirectory, profile) or "modSettings/"
end

function RPSim:loadMap(_)
    self.adapter = RPSimGameAdapter.new()
    local paths = RPSimBridgePaths.new(modSettingsDir())
    RPSimFileIO.ensureDir(paths.base)
    RPSimLog.info("Bridge folder: %s", paths.base)
    local cfg = loadConfig(paths)
    self.adapter.config = cfg
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
        -- getDate exists in FS25 (PlayerSystem, BetterContracts); os.time does not (no `os` in the sandbox).
        local stamp = getDate ~= nil and getDate("%Y%m%d%H%M%S") or "0"
        state.savegameId = RPSimBridge.generateSavegameId(self.adapter:getMapName(),
            self.adapter:getSavegameIndex(), stamp)
    end

    self.bridge = RPSimBridge.new(cfg, paths, self.adapter, state)
    self.bridge:bootstrap()
    if Mission00 == nil or Mission00.onStartMission == nil then
        -- No start hook available: start right away (degraded, first export may be incomplete).
        RPSimLog.warning("Mission00.onStartMission not available - starting the bridge immediately")
        self.bridge:onSavegameLoaded()
    end
    RPSimLog.info("Loaded, savegameId=%s", state.savegameId)
end

--- Mission00.onStartMission (appended): the savegame is completely loaded, run the first export.
function RPSim.onStartMission(_)
    if RPSim.bridge ~= nil and not RPSim.bridge.started then
        RPSim.bridge:onSavegameLoaded()
    end
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

--- Quantity tracking for FIXED contracts (T-05): the sale is counted AFTER the game has priced and paid it,
-- so the delivery that fills the contract is still paid at the contract price. The quantity is the requested
-- fillDelta, not the return value (unreliable in FS25, see FS25_MarketDynamics PriceHook.lua).
-- FS25 signature: sellFillType(farmId, fillDelta, fillTypeIndex, fillPositionData, toolType, extraAttributes).
function RPSim.sellFillTypeHook(station, superFunc, farmId, fillDelta, fillTypeIndex, ...)
    local result = superFunc(station, farmId, fillDelta, fillTypeIndex, ...)
    if RPSim.bridge ~= nil and fillDelta ~= nil and fillDelta > 0 then
        local name = g_fillTypeManager:getFillTypeNameByIndex(fillTypeIndex)
        RPSim.bridge:recordSale(RPSimGameAdapter.sellPointId(station), name, fillDelta)
    end
    return result
end

if SellingStation ~= nil and Utils ~= nil then
    SellingStation.getEffectiveFillTypePrice = Utils.overwrittenFunction(SellingStation.getEffectiveFillTypePrice,
        effectivePriceHook)
    SellingStation.sellFillType = Utils.overwrittenFunction(SellingStation.sellFillType, RPSim.sellFillTypeHook)
end
if Mission00 ~= nil and Utils ~= nil then
    Mission00.onStartMission = Utils.appendedFunction(Mission00.onStartMission, RPSim.onStartMission)
end
if FSCareerMissionInfo ~= nil and Utils ~= nil then
    FSCareerMissionInfo.saveToXMLFile = Utils.appendedFunction(FSCareerMissionInfo.saveToXMLFile, RPSim.saveSavegame)
end
if addModEventListener ~= nil then
    addModEventListener(RPSim)
end
