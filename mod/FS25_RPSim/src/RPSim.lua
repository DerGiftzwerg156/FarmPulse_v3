-- FS25_RPSim entry point: registers with the FS25 mod lifecycle and wires hooks.
-- Lifecycle: addModEventListener -> loadMap / update / deleteMap; the first export runs in
-- Mission00.onStartMission (farms, vehicles and placeables of the savegame exist only from then on - same
-- pattern as FS25_UsedPlus and FS25_MarketDynamics). Persistence via FSCareerMissionInfo.saveToXMLFile
-- (appended) and the savegame XML loaded in loadMap.
-- luacheck: globals g_currentMission g_modSettingsDirectory addModEventListener Utils getUserProfileAppPath
-- luacheck: globals FSCareerMissionInfo SellingStation XMLFile getDate Mission00 Farm AIJob AIJobFieldWork AIJobConveyor
-- luacheck: globals AIJobGoTo AIJobDeliver AIJobLoadAndDeliver
-- luacheck: globals PlayerInputComponent InputAction g_inputBinding g_i18n
RPSim = { modName = g_currentModName, modDirectory = g_currentModDirectory, bridge = nil, financeHook = false,
    helperHooks = false, promptKeyHook = false }

local XML_NAME = "FS25_RPSim.xml"

local function savegameXmlPath()
    local dir = RPSim.adapter:getSavegameDirectory()
    if dir == nil then
        return nil
    end
    return dir .. "/" .. XML_NAME
end

local function loadConfig(paths)
    local text = RPSimFileIO.readPayload(paths.configFile)
    local overrides = nil
    if text ~= nil and text ~= "" then
        overrides = RPSimJson.tryDecode(text)
        if overrides == nil then
            RPSimLog.warning("Ignoring unreadable %s, using defaults", paths.configFile)
        end
    end
    return RPSimConfig.new(overrides)
end

local function modSettingsDir()
    local profile = getUserProfileAppPath ~= nil and getUserProfileAppPath() or nil
    return RPSimBridgePaths.resolveModSettingsDir(g_modSettingsDirectory, profile) or "modSettings/"
end

local function loadMapImpl(self)
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
    self.bridge.financeJournalEnabled = RPSim.financeHook
    self.bridge.workforceEnabled = RPSim.helperHooks
    self.bridge.promptKeyAvailable = RPSim.promptKeyHook
    self.bridge:bootstrap()
    if Mission00 == nil or Mission00.onStartMission == nil then
        -- No start hook available: start right away (degraded, first export may be incomplete).
        RPSimLog.warning("Mission00.onStartMission not available - starting the bridge immediately")
        self.bridge:onSavegameLoaded()
    end
    RPSimLog.info("Loaded, savegameId=%s", state.savegameId)
end

--- A mod error during loadMap must never block loading the savegame: the engine aborts its load callback and
-- the loading screen hangs. On failure the mod stays inactive for this session.
function RPSim:loadMap(name)
    local ok, err = pcall(loadMapImpl, self, name)
    if not ok then
        self.bridge = nil
        RPSimLog.error("Start failed, RPSim is inactive for this session: %s", tostring(err))
    end
end

--- Mission00.onStartMission (appended): the savegame is completely loaded, run the first export.
function RPSim.onStartMission(_)
    if RPSim.bridge ~= nil and not RPSim.bridge.started then
        RPSim.bridge:onSavegameLoaded()
    end
end

function RPSim:update(dt)
    RPSim.stopHelpersOverTheLimit()
    if self.bridge ~= nil then
        self.bridge:update(dt)
    end
end

function RPSim:deleteMap()
    if self.adapter ~= nil and self.adapter.restoreHelperLimit ~= nil then
        self.adapter:restoreHelperLimit() -- R2-A3
    end
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
--- Roadmap V2 R2-B1: every booking of the game passes Farm:changeBalance(amount, moneyType) (LUADOC
-- script/Farms/Farm.md). A failure never disturbs the booking itself.
function RPSim.changeBalanceHook(farm, amount, moneyType)
    if RPSim.bridge == nil then
        return
    end
    local ok, err = pcall(function()
        RPSim.bridge:recordBooking(farm:getId(), amount, moneyType)
    end)
    if not ok and not RPSim.bookingHookWarned then
        RPSim.bookingHookWarned = true
        RPSimLog.warning("Booking journal: %s", tostring(err))
    end
end

-- ------------------------------------------------------------------ Roadmap V2 R2-A: employees as FS25 helpers

local function workforce()
    return RPSim.bridge ~= nil and RPSim.bridge.state.workforce or nil
end

--- R2-A1: AIJob:updateCost books getPricePerMs() * dt only when the price is > 0 (FS25 ai/jobs/AIJob.lua); a job driven
-- by an employee costs nothing in the game when the wage mode is EMPLOYEES - the salary runs through the tool.
function RPSim.pricePerMsHook(job, superFunc, ...)
    local wf = workforce()
    local ok, free = pcall(RPSimWorkforce.wageFree, wf or RPSimWorkforce.new(), job.jobId)
    if ok and free and wf ~= nil then
        return 0
    end
    return superFunc(job, ...)
end

--- R2-A3: running helpers of the player farm without the given job (not yet or just started).
local function otherRunningJobs(adapter, job)
    local n = 0
    for _, j in ipairs(adapter:collectAIJobs()) do
        if job.jobId == nil or j.jobId ~= job.jobId then
            n = n + 1
        end
    end
    return n
end

--- R2-A3: helpers started over the strict limit, stopped in the next update (AISystem:startJob still runs while
-- AIJob:start is called, a stop in between would leave its bookkeeping half done).
RPSim.limitStops = {}

function RPSim.stopHelpersOverTheLimit()
    if #RPSim.limitStops == 0 then
        return
    end
    local jobs = RPSim.limitStops
    RPSim.limitStops = {}
    local wf = workforce()
    local adapter = RPSim.bridge ~= nil and RPSim.bridge.adapter or nil
    if wf == nil or adapter == nil then
        return
    end
    for _, job in ipairs(jobs) do
        adapter:stopHelperLimitJob(job, RPSimWorkforce.activeOperators(wf))
    end
end

--- R2-A2: AIJob:start(farmId) picks a random FS25 helper; for the player farm a free active machine operator is
-- assigned to the job as well - "Schulungen": one with the trainings the vehicle needs.
-- R2-A3: a helper of the player farm started over the strict limit is stopped again. The map menu and the key in the
-- vehicle already refuse it (maxNumHirables, startableHook); this catches mods that start helpers their own way
-- (Courseplay, AutoDrive), as long as their jobs run through AIJob:start.
function RPSim.jobStartHook(job, farmId)
    local wf = workforce()
    if wf == nil or RPSim.bridge.adapter == nil then
        return
    end
    pcall(function()
        local adapter = RPSim.bridge.adapter
        if farmId == adapter:getFarmId() then
            if adapter:isServer() and RPSimWorkforce.limitReached(wf, otherRunningJobs(adapter, job)) then
                RPSim.limitStops[#RPSim.limitStops + 1] = job
                return
            end
            local info = adapter:jobVehicleInfo(job)
            RPSimWorkforce.assign(wf, job.jobId, RPSimWorkforce.requiredTrainings(wf, info ~= nil and info.categories))
        end
    end)
end

--- "Schulungen" / R2-A3: own start states of AIJob*:getIsStartable, sent to the client as UInt8
-- (AIJobStartRequestEvent).
RPSim.START_ERROR_NO_TRAINING = 201
RPSim.START_ERROR_HELPER_LIMIT = 202

--- "Schulungen": in the strict mode (R2-A3) the start of a helper is refused when the vehicle needs a training and no
-- free machine operator has it. The map menu and the key in the vehicle (AIJobVehicle:toggleAIVehicle) both send an
-- AIJobStartRequestEvent, whose server side asks job:getIsStartable(connection) first.
-- R2-A3: also refused when the farm already runs as many helpers as it has active machine operators - the own check
-- does not depend on maxNumHirables, so it holds for every start that asks getIsStartable.
function RPSim.startableHook(job, superFunc, connection)
    local ok, state = superFunc(job, connection)
    local wf = workforce()
    if not ok or wf == nil or RPSim.bridge.adapter == nil then
        return ok, state
    end
    local adapter = RPSim.bridge.adapter
    local blocked, required, info, full = false, nil, nil, false
    pcall(function()
        info = adapter:jobVehicleInfo(job)
        if info ~= nil and info.farmId == adapter:getFarmId() then
            full = RPSimWorkforce.limitReached(wf, otherRunningJobs(adapter, job))
            required = RPSimWorkforce.requiredTrainings(wf, info.categories)
            blocked = RPSimWorkforce.startBlocked(wf, required)
        end
    end)
    if full then
        adapter:notify(string.format("FarmPulse: Kein freier Maschinenführer – im strengen Modus fahren höchstens %d "
            .. "Helfer.", RPSimWorkforce.activeOperators(wf)), "CRITICAL")
        return false, RPSim.START_ERROR_HELPER_LIMIT
    end
    if not blocked then
        return ok, state
    end
    local titles = {}
    for _, t in ipairs(required) do
        titles[#titles + 1] = RPSimWorkforce.trainingTitle(t)
    end
    adapter:notify(string.format("FarmPulse: Für %s ist kein Maschinenführer mit der Schulung „%s“ frei.",
        tostring(info.name or "dieses Fahrzeug"), table.concat(titles, "“, „")), "CRITICAL")
    return false, RPSim.START_ERROR_NO_TRAINING
end

--- "Schulungen" / R2-A3: text of the own start states in the game dialog. AIJob*.getIsStartErrorText(state) is defined
-- static; the calling menu is not part of the code dump, so a call on a job instance (job:getIsStartErrorText(state))
-- works too.
function RPSim.startErrorTextHook(first, superFunc, second)
    local state = type(first) == "table" and second or first
    if state == RPSim.START_ERROR_NO_TRAINING then
        local ok, text = pcall(function() return g_i18n:getText("rpsim_ai_noTraining") end)
        return ok and text or "Kein geschulter Maschinenführer frei"
    end
    if state == RPSim.START_ERROR_HELPER_LIMIT then
        local ok, text = pcall(function() return g_i18n:getText("rpsim_ai_helperLimitStart") end)
        return ok and text or "Kein freier Maschinenführer (strenger Modus)"
    end
    return superFunc(first, second)
end

--- R2-A2: the helper name of the game messages (AIMessage:getMessage uses job:getHelperName()).
function RPSim.helperNameHook(job, superFunc, ...)
    local wf = workforce()
    local ok, name = pcall(RPSimWorkforce.helperName, wf or RPSimWorkforce.new(), job.jobId)
    if ok and name ~= nil then
        return name
    end
    return superFunc(job, ...)
end

--- R2-A2: AIJob:stop shows the stop message first, then the employee is free again.
function RPSim.jobStopHook(job, _)
    local wf = workforce()
    if wf ~= nil then
        RPSimWorkforce.release(wf, job.jobId)
    end
end

if AIJob ~= nil and Utils ~= nil and AIJob.getPricePerMs ~= nil and AIJob.start ~= nil then
    -- AIJobFieldWork and AIJobConveyor define getPricePerMs themselves, the other job types inherit it from AIJob
    for _, cls in ipairs({ AIJob, AIJobFieldWork, AIJobConveyor }) do
        if cls ~= nil and rawget(cls, "getPricePerMs") ~= nil then
            cls.getPricePerMs = Utils.overwrittenFunction(cls.getPricePerMs, RPSim.pricePerMsHook)
        end
    end
    -- "Schulungen": every job type with its own getIsStartable / getIsStartErrorText (the others inherit AIJob's)
    for _, cls in ipairs({ AIJob, AIJobFieldWork, AIJobGoTo, AIJobDeliver, AIJobLoadAndDeliver, AIJobConveyor }) do
        if cls ~= nil and rawget(cls, "getIsStartable") ~= nil then
            cls.getIsStartable = Utils.overwrittenFunction(cls.getIsStartable, RPSim.startableHook)
        end
        if cls ~= nil and rawget(cls, "getIsStartErrorText") ~= nil then
            cls.getIsStartErrorText = Utils.overwrittenFunction(cls.getIsStartErrorText, RPSim.startErrorTextHook)
        end
    end
    AIJob.start = Utils.appendedFunction(AIJob.start, RPSim.jobStartHook)
    AIJob.getHelperName = Utils.overwrittenFunction(AIJob.getHelperName, RPSim.helperNameHook)
    AIJob.stop = Utils.appendedFunction(AIJob.stop, RPSim.jobStopHook)
    RPSim.helperHooks = true
end

-- ------------------------------------------------------------------ Roadmap V2 R2-F3: key for open questions

--- Key press (action RPSIM_OPEN_PROMPT of modDesc.xml): opens the next waiting question, also inside a vehicle.
function RPSim.onOpenPromptKey()
    if RPSim.bridge ~= nil then
        RPSim.bridge:openNextPrompt()
    end
end

--- Registers the action in the global player context (PlayerInputComponent:registerGlobalPlayerActionEvents runs on
-- foot and - via Enterable - in a vehicle). Arguments of registerActionEvent as in PlayerInputComponent:
-- (action, target, callback, triggerUp, triggerDown, triggerAlways, startActive, callbackState, disableConflicting).
-- 🟡 manual test plan 10.8: whether a mod can register a global action this way.
function RPSim.registerPromptAction(_)
    if RPSim.adapter == nil or InputAction == nil or InputAction.RPSIM_OPEN_PROMPT == nil or g_inputBinding == nil then
        return
    end
    local ok, err = pcall(function()
        local _, eventId = g_inputBinding:registerActionEvent(InputAction.RPSIM_OPEN_PROMPT, RPSim, RPSim.onOpenPromptKey,
            false, true, false, true, nil, true)
        if eventId ~= nil then
            g_inputBinding:setActionEventText(eventId, g_i18n:getText("input_RPSIM_OPEN_PROMPT"))
            g_inputBinding:setActionEventTextVisibility(eventId, false)
            RPSim.adapter.promptActionEventId = eventId
            RPSim.adapter.promptKeyVisible = false
        end
    end)
    if not ok and not RPSim.promptKeyWarned then
        RPSim.promptKeyWarned = true
        RPSimLog.warning("Key for open questions not available: %s", tostring(err))
    end
end

if PlayerInputComponent ~= nil and PlayerInputComponent.registerGlobalPlayerActionEvents ~= nil and Utils ~= nil then
    PlayerInputComponent.registerGlobalPlayerActionEvents = Utils.appendedFunction(
        PlayerInputComponent.registerGlobalPlayerActionEvents, RPSim.registerPromptAction)
    RPSim.promptKeyHook = true
end

if Farm ~= nil and Farm.changeBalance ~= nil and Utils ~= nil then
    Farm.changeBalance = Utils.appendedFunction(Farm.changeBalance, RPSim.changeBalanceHook)
    RPSim.financeHook = true
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
