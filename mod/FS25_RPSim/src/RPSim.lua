-- FS25_RPSim entry point: registers with the FS25 mod lifecycle and wires hooks.
-- Lifecycle: addModEventListener -> loadMap / update / deleteMap; the first export runs in
-- Mission00.onStartMission (farms, vehicles and placeables of the savegame exist only from then on - same
-- pattern as FS25_UsedPlus and FS25_MarketDynamics). Persistence via FSCareerMissionInfo.saveToXMLFile
-- (appended) and the savegame XML loaded in loadMap.
-- luacheck: globals g_currentMission g_modSettingsDirectory addModEventListener Utils getUserProfileAppPath
-- luacheck: globals FSCareerMissionInfo SellingStation XMLFile getDate Mission00 Farm AIJob AIJobFieldWork AIJobConveyor
-- luacheck: globals AIJobGoTo AIJobDeliver AIJobLoadAndDeliver AISystem
-- luacheck: globals PlayerInputComponent InputAction g_inputBinding g_i18n Combine Cutter
RPSim = { modName = g_currentModName, modDirectory = g_currentModDirectory, bridge = nil, financeHook = false,
    helperHooks = false, promptKeyHook = false, harvestHook = false, harvestCounted = false }

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
    -- again here in case the mod texts were not loaded yet when the sources ran (no-op for keys already shared)
    RPSimGameAdapter.shareModTextsGlobally()
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
    self.bridge.harvestCounterEnabled = RPSim.harvestHook
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
    RPSim.hookRegisteredJobTypes()
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
-- Booking statement: while the game sells, bridge.saleContext names fill type, sell point and litres, so a booking
-- the sale makes through Farm:changeBalance gets them (whether the game books inside sellFillType is checked in the
-- manual test plan; without it the booking stays without fill type).
function RPSim.sellFillTypeHook(station, superFunc, farmId, fillDelta, fillTypeIndex, ...)
    local bridge = RPSim.bridge
    if bridge ~= nil and fillDelta ~= nil and fillDelta > 0 then
        local okCtx, ctx = pcall(function()
            return { fillType = g_fillTypeManager:getFillTypeNameByIndex(fillTypeIndex),
                sellPoint = RPSimGameAdapter.sellPointId(station), liters = fillDelta }
        end)
        bridge.saleContext = okCtx and ctx or nil
    end
    local ok, result = pcall(superFunc, station, farmId, fillDelta, fillTypeIndex, ...)
    if bridge ~= nil then
        bridge.saleContext = nil
    end
    if not ok then
        error(result, 0)
    end
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

--- R2-A3: running helpers of the player farm (AIJobs and AutoDrive drives) without the given job (not yet or just
-- started).
local function otherRunningJobs(job)
    local n = 0
    for _, j in ipairs(RPSim.bridge:helperJobs()) do
        if job.jobId == nil or j.jobId ~= job.jobId then
            n = n + 1
        end
    end
    return n
end

--- R2-A3: helpers started over the strict limit, stopped in the next update (the start hook runs inside
-- AISystem:startJob / AIJobStartRequestEvent:run, a stop in between would leave their bookkeeping half done).
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

--- R2-A2: every helper job starts in AISystem:startJobInternal(job, startFarmId) (FS25 ai/AISystem.lua: job:start, then
-- addJob; AISystem:startJob sets job.jobId first). For the player farm a free active machine operator is assigned -
-- "Schulungen": one with the trainings the vehicle needs. The hook sits on AISystem and not on AIJob:start because
-- Courseplay's jobs (CpAIJob:start, Courseplay_FS25 scripts/ai/jobs/CpAIJob.lua) replace AIJob:start without calling it.
-- R2-A3: a helper of the player farm started over the strict limit is stopped again. The map menu and the key in the
-- vehicle already refuse it (maxNumHirables, startableHook); this catches starts that never ask getIsStartable
-- (AutoDrive hands a vehicle over with AISystem:startJob).
function RPSim.jobStartHook(_, job, farmId)
    local wf = workforce()
    if wf == nil or RPSim.bridge.adapter == nil then
        return
    end
    pcall(function()
        local adapter = RPSim.bridge.adapter
        if farmId == adapter:getFarmId() then
            RPSim.bridge:pruneAutoDrive()
            if adapter:isServer() and RPSimWorkforce.limitReached(wf, otherRunningJobs(job)) then
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
            full = RPSimWorkforce.limitReached(wf, otherRunningJobs(job))
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

--- R2-A2: AISystem:stopJobInternal(job, aiMessage) calls job:stop, which shows the stop message (with the employee's
-- name); then the employee is free again. Like the start hook on AISystem, so it holds for every job class.
function RPSim.jobStopHook(_, job, _)
    local wf = workforce()
    if wf ~= nil then
        RPSimWorkforce.release(wf, job.jobId)
    end
end

--- Methods of a job class that get the hooks above. Only methods the class defines itself (rawget): game subclasses
-- inherit the rest from AIJob (Class() looks them up in the parent), which is hooked itself.
RPSim.JOB_CLASS_HOOKS = {
    { name = "getPricePerMs", hook = function(...) return RPSim.pricePerMsHook(...) end },
    { name = "getIsStartable", hook = function(...) return RPSim.startableHook(...) end },
    { name = "getIsStartErrorText", hook = function(...) return RPSim.startErrorTextHook(...) end },
    { name = "getHelperName", hook = function(...) return RPSim.helperNameHook(...) end },
}
--- Functions this mod put on a job class: a class copied from a hooked one (Courseplay's CpObject makes a shallow copy
-- of its base class, scripts/CpObject.lua) already carries them and must not be hooked twice.
RPSim.ownJobFunctions = setmetatable({}, { __mode = "k" })

--- Hooks one job class. Returns true when the class is hooked (now or before).
function RPSim.hookJobClass(cls)
    if type(cls) ~= "table" or Utils == nil then
        return false
    end
    for _, h in ipairs(RPSim.JOB_CLASS_HOOKS) do
        local fn = rawget(cls, h.name)
        if type(fn) == "function" and not RPSim.ownJobFunctions[fn] then
            local wrapped = Utils.overwrittenFunction(fn, h.hook)
            RPSim.ownJobFunctions[wrapped] = true
            cls[h.name] = wrapped
        end
    end
    return true
end

--- Hooks every job type registered with g_currentMission.aiJobTypeManager (FS25 ai/AIJobTypeManager.lua: jobTypes[i].
-- classObject). Courseplay registers its jobs in its loadMap (CpAIJob.registerJob), each a shallow copy of AIJob and
-- CpAIJob (CpObject) - the hooks on AIJob never reach them, whatever the load order of the mods. Runs once the mission
-- has started, when every mod has registered its job types.
function RPSim.hookRegisteredJobTypes()
    if not RPSim.helperHooks then
        return 0
    end
    local n = 0
    local ok, err = pcall(function()
        for _, jobType in ipairs(g_currentMission.aiJobTypeManager.jobTypes or {}) do
            if RPSim.hookJobClass(jobType.classObject) then
                n = n + 1
            end
        end
    end)
    if not ok then
        RPSimLog.warning("Helper hooks for the job types of other mods failed: %s", tostring(err))
    end
    return n
end

if AIJob ~= nil and Utils ~= nil and AIJob.getPricePerMs ~= nil and AIJob.start ~= nil then
    -- the game's job classes; AIJobFieldWork and AIJobConveyor define getPricePerMs themselves, every class but AIJob
    -- inherits getHelperName
    for _, cls in ipairs({ AIJob, AIJobFieldWork, AIJobGoTo, AIJobDeliver, AIJobLoadAndDeliver, AIJobConveyor }) do
        RPSim.hookJobClass(cls)
    end
    if AISystem ~= nil and AISystem.startJobInternal ~= nil and AISystem.stopJobInternal ~= nil then
        AISystem.startJobInternal = Utils.appendedFunction(AISystem.startJobInternal, RPSim.jobStartHook)
        AISystem.stopJobInternal = Utils.appendedFunction(AISystem.stopJobInternal, RPSim.jobStopHook)
        RPSim.jobHooksOnAISystem = true
    else
        -- fallback: the game's own job classes only (they call AIJob:start / AIJob:stop as their superclass)
        AIJob.start = Utils.appendedFunction(AIJob.start, function(job, farmId) RPSim.jobStartHook(nil, job, farmId) end)
        AIJob.stop = Utils.appendedFunction(AIJob.stop, function(job, msg) RPSim.jobStopHook(nil, job, msg) end)
        RPSimLog.warning("AISystem.startJobInternal not found - helpers of Courseplay get no machine operator")
    end
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

--- Roadmap V3.3 R33-F3: litres a harvesting machine got into its tank (the return value of Combine:addCutterArea,
-- after the rain and damage reduction, less when the tank is full; LUADOC Specializations/Combine.md) are added to the
-- harvest counter of the own field it stands on. A failure never disturbs the harvest.
function RPSim.countHarvest(combine, liters, inputFruitType, outputFillType)
    local bridge = RPSim.bridge
    if bridge == nil or type(liters) ~= "number" or liters <= 0 then
        return
    end
    local ok, entry = pcall(bridge.adapter.harvestEntry, bridge.adapter, combine, inputFruitType, outputFillType)
    if ok and entry ~= nil then
        RPSimHarvestCounter.add(bridge.state.harvests, entry.farmlandId, entry.fruitType, entry.fillType, liters)
    end
end

--- Main way (owner decision 2026-10-08: both ways): hook on Combine.addCutterArea, called by the cutter with the
-- litres of the cut area (Cutter:onEndWorkAreaProcessing, LUADOC Specializations/Cutter.md). harvestCounted tells the
-- fallback below that this call was counted already.
function RPSim.addCutterAreaHook(combine, superFunc, area, liters, inputFruitType, outputFillType, ...)
    local applied = superFunc(combine, area, liters, inputFruitType, outputFillType, ...)
    RPSim.harvestCounted = true
    pcall(RPSim.countHarvest, combine, applied, inputFruitType, outputFillType)
    return applied
end

--- Fallback (🟡 manual test plan 29.2): a vehicle type may keep the original Combine.addCutterArea when it was
-- registered before the hook above (SpecializationUtil.registerFunction). Around Cutter.onEndWorkAreaProcessing the
-- addCutterArea of the combine of this cutter (spec_cutter.workAreaParameters.combineVehicle) is wrapped; it counts
-- only when the main way did not count the same call, so nothing is counted twice.
function RPSim.cutterEndHook(cutter, superFunc, ...)
    local combine = cutter ~= nil and cutter.spec_cutter ~= nil and cutter.spec_cutter.workAreaParameters ~= nil
        and cutter.spec_cutter.workAreaParameters.combineVehicle or nil
    if type(combine) ~= "table" or type(combine.addCutterArea) ~= "function" then
        return superFunc(cutter, ...)
    end
    local own = rawget(combine, "addCutterArea")
    local original = combine.addCutterArea
    combine.addCutterArea = function(c, area, liters, inputFruitType, outputFillType, ...)
        RPSim.harvestCounted = false
        local applied = original(c, area, liters, inputFruitType, outputFillType, ...)
        if not RPSim.harvestCounted then
            pcall(RPSim.countHarvest, c, applied, inputFruitType, outputFillType)
        end
        RPSim.harvestCounted = false
        return applied
    end
    local ok, a, b, c = pcall(superFunc, cutter, ...)
    combine.addCutterArea = own
    if not ok then
        error(a, 0)
    end
    return a, b, c
end

if Combine ~= nil and Combine.addCutterArea ~= nil and Utils ~= nil then
    Combine.addCutterArea = Utils.overwrittenFunction(Combine.addCutterArea, RPSim.addCutterAreaHook)
    RPSim.harvestHook = true
end
if Cutter ~= nil and Cutter.onEndWorkAreaProcessing ~= nil and Utils ~= nil then
    Cutter.onEndWorkAreaProcessing = Utils.overwrittenFunction(Cutter.onEndWorkAreaProcessing, RPSim.cutterEndHook)
    RPSim.harvestHook = true
end

-- booking titles (rpsim_money_*) and AI texts are looked up by engine code in the global g_i18n
RPSimGameAdapter.shareModTextsGlobally()
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
