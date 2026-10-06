-- Roadmap V2 R2-A0..A5: employees as FS25 helpers (list, assignment, wage, name, limit, worked time, strike).
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestWorkforce = {}

local function roster(employees, extra)
    local ins = { employees = employees, helperWageMode = "EMPLOYEES", strictHelperLimit = false }
    for k, v in pairs(extra or {}) do ins[k] = v end
    return ins
end

local KLAUS = { employeeId = 12, name = "Klaus Berger", role = "MACHINE_OPERATOR", status = "ACTIVE" }
local ANNA = { employeeId = 7, name = "Anna Vogt", role = "MACHINE_OPERATOR", status = "ACTIVE" }
local MIA = { employeeId = 3, name = "Mia Roth", role = "MECHANIC", status = "ACTIVE" }

function T.TestWorkforce:testAssignsTheFirstFreeActiveOperatorInListOrder()
    local wf = RPSimWorkforce.new()
    RPSimWorkforce.setRoster(wf, roster({ MIA, KLAUS, ANNA }))
    lu.assertEquals(RPSimWorkforce.assign(wf, 1), 12)
    lu.assertEquals(RPSimWorkforce.assign(wf, 1), 12, "idempotent")
    lu.assertEquals(RPSimWorkforce.assign(wf, 2), 7)
    lu.assertNil(RPSimWorkforce.assign(wf, 3), "no free operator: vanilla helper")
    lu.assertEquals(RPSimWorkforce.helperName(wf, 2), "Anna Vogt")
    lu.assertNil(RPSimWorkforce.helperName(wf, 3))
    RPSimWorkforce.release(wf, 1)
    lu.assertEquals(RPSimWorkforce.assign(wf, 3), 12)
end

function T.TestWorkforce:testOnlyActiveOperatorsDrive()
    local wf = RPSimWorkforce.new()
    lu.assertNil(RPSimWorkforce.assign(wf, 1), "no list yet")
    RPSimWorkforce.setRoster(wf, roster({ MIA, { employeeId = 12, name = "Klaus Berger", role = "MACHINE_OPERATOR",
        status = "ON_LEAVE" } }))
    lu.assertNil(RPSimWorkforce.assign(wf, 1))
end

function T.TestWorkforce:testWageIsFreeOnlyForEmployeesInTheEmployeesMode()
    local wf = RPSimWorkforce.new()
    RPSimWorkforce.setRoster(wf, roster({ KLAUS }))
    RPSimWorkforce.assign(wf, 1)
    lu.assertTrue(RPSimWorkforce.wageFree(wf, 1))
    lu.assertFalse(RPSimWorkforce.wageFree(wf, 2))
    RPSimWorkforce.setRoster(wf, roster({ KLAUS }, { helperWageMode = "VANILLA" }))
    lu.assertFalse(RPSimWorkforce.wageFree(wf, 1))
end

function T.TestWorkforce:testStrictHelperLimit()
    local wf = RPSimWorkforce.new()
    lu.assertEquals(RPSimWorkforce.helperLimit(wf, 10), 10)
    RPSimWorkforce.setRoster(wf, roster({ KLAUS, ANNA, MIA }, { strictHelperLimit = true }))
    lu.assertEquals(RPSimWorkforce.helperLimit(wf, 10), 2)
    lu.assertEquals(RPSimWorkforce.helperLimit(wf, 1), 1)
    RPSimWorkforce.setRoster(wf, roster({ MIA }, { strictHelperLimit = true }))
    lu.assertEquals(RPSimWorkforce.helperLimit(wf, 10), 0, "no operator in the strict mode = no helpers")
end

function T.TestWorkforce:testTheLimitIsReachedAtTheNumberOfActiveOperators()
    local wf = RPSimWorkforce.new()
    lu.assertFalse(RPSimWorkforce.limitReached(wf, 5), "no list yet")
    RPSimWorkforce.setRoster(wf, roster({ KLAUS, ANNA, MIA }))
    lu.assertFalse(RPSimWorkforce.limitReached(wf, 5), "normal mode: no own limit")
    RPSimWorkforce.setRoster(wf, roster({ KLAUS, ANNA, MIA, { employeeId = 9, name = "Jan Ober",
        role = "MACHINE_OPERATOR", status = "ON_LEAVE" } }, { strictHelperLimit = true }))
    lu.assertEquals(RPSimWorkforce.activeOperators(wf), 2)
    lu.assertFalse(RPSimWorkforce.limitReached(wf, 1))
    lu.assertTrue(RPSimWorkforce.limitReached(wf, 2))
    RPSimWorkforce.setRoster(wf, roster({ MIA }, { strictHelperLimit = true }))
    lu.assertTrue(RPSimWorkforce.limitReached(wf, 0), "no operator in the strict mode = no helpers")
end

function T.TestWorkforce:testWorkedTimeIsCreditedSinceTheLastSample()
    local wf = RPSimWorkforce.new()
    RPSimWorkforce.setRoster(wf, roster({ KLAUS, ANNA }))
    RPSimWorkforce.assign(wf, 1)
    RPSimWorkforce.accrue(wf, { 1, 5 }, 1000)
    lu.assertEquals(wf.workedGameMs, {}, "the first sample only sets the start point")
    RPSimWorkforce.accrue(wf, { 1, 5 }, 3601000)
    lu.assertEquals(wf.workedGameMs, { ["12"] = 3600000 })
    RPSimWorkforce.accrue(wf, { 1 }, 500) -- reload: game time went back
    RPSimWorkforce.accrue(wf, { 1 }, 1500)
    lu.assertEquals(wf.workedGameMs["12"], 3601000)
    lu.assertEquals(RPSimWorkforce.toRaw(wf, { { jobId = 1, title = "Fendt 942" }, { jobId = 5 } }).activeJobs,
        { { jobId = 1, employeeId = 12, title = "Fendt 942" }, { jobId = 5 } })
end

function T.TestWorkforce:testANewListReleasesOrStrikes()
    local wf = RPSimWorkforce.new()
    RPSimWorkforce.setRoster(wf, roster({ KLAUS, ANNA }))
    RPSimWorkforce.assign(wf, 1)
    RPSimWorkforce.assign(wf, 2)
    local strike, released = RPSimWorkforce.setRoster(wf, roster({ { employeeId = 12, name = "Klaus Berger",
        role = "MACHINE_OPERATOR", status = "STRIKE" } }))
    lu.assertEquals(strike, { 1 })
    lu.assertEquals(released, { 2 })
    lu.assertEquals(RPSimWorkforce.helperName(wf, 1), "Klaus Berger", "kept until the job is stopped")
    lu.assertNil(wf.assignments[2])
end

function T.TestWorkforce:testRosterAndWorkedTimeSurviveTheSavegame()
    local x = { data = {} }
    function x:setString(k, v) self.data[k] = v end
    function x:setInt(k, v) self.data[k] = v end
    function x:setFloat(k, v) self.data[k] = v end
    function x:getString(k) local v = self.data[k]; return v ~= nil and tostring(v) or nil end
    function x:getInt(k) return self.data[k] end
    function x:getFloat(k) return self.data[k] end
    local state = RPSimProcessor.newState(RPSimConfig.new())
    RPSimWorkforce.setRoster(state.workforce, roster({ KLAUS, MIA }, { strictHelperLimit = true }))
    RPSimWorkforce.assign(state.workforce, 1)
    state.workforce.workedGameMs["12"] = 7200000
    RPSimPersistence.save(x, state)
    local loaded = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load(x, loaded)
    lu.assertEquals(loaded.workforce.roster, state.workforce.roster)
    lu.assertEquals(loaded.workforce.workedGameMs, { ["12"] = 7200000 })
    lu.assertEquals(loaded.workforce.assignments, {}, "jobs are re-assigned after loading")
end

-- Bridge: EMPLOYEE_ROSTER stops the helpers of striking employees and sets the helper limit (R2-A0, R2-A3, R2-A5)
function T.TestWorkforce:testRosterInstructionStopsStrikingHelpers()
    local bridge, _, adapter = helpers.newBridge()
    local stopped, limits = {}, {}
    function adapter:stopStrikingJob(jobId, name) stopped[#stopped + 1] = { jobId, name } return true end
    function adapter:applyHelperLimit(wf) limits[#limits + 1] = RPSimWorkforce.helperLimit(wf, 10) return true end
    bridge:applyRoster(roster({ KLAUS }, { strictHelperLimit = true }))
    RPSimWorkforce.assign(bridge.state.workforce, 4)
    bridge:applyRoster(roster({ { employeeId = 12, name = "Klaus Berger", role = "MACHINE_OPERATOR", status = "STRIKE" } },
        { strictHelperLimit = true }))
    lu.assertEquals(stopped, { { 4, "Klaus Berger" } })
    lu.assertNil(bridge.state.workforce.assignments[4])
    lu.assertEquals(limits, { 1, 0 })
end

-- Bridge export: jobs running after loading are assigned, the worked time is credited (R2-A2, R2-A4)
function T.TestWorkforce:testExportSamplesTheRunningJobs()
    local bridge, fs, adapter, paths = helpers.newBridge()
    function adapter:collectAIJobs() return { { jobId = 9, title = "Fendt 942 Vario" }, { jobId = 11 } } end
    bridge.workforceEnabled = true
    bridge:applyRoster(roster({ KLAUS }))
    bridge:onSavegameLoaded()
    adapter.gameTime = 1000 + 3600000
    bridge:exportFarmFacts()
    local wf = RPSimJson.decode(fs.files[paths.farmFacts]).workforce
    lu.assertEquals(wf.activeJobs, { { jobId = 9, employeeId = 12, title = "Fendt 942 Vario" }, { jobId = 11 } })
    lu.assertEquals(wf.workedGameMs["12"], 3600000)
end

function T.TestWorkforce:testNoWorkforceBlockWithoutTheHooks()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:onSavegameLoaded()
    lu.assertNil(RPSimJson.decode(fs.files[paths.farmFacts]).workforce)
end

-- ------------------------------------------------------------------ game hooks (RPSim.lua) with fake FS25 classes
local function fakeAIGame()
    local game = helpers.fakeGame()
    local jobs = {}
    AIJob = { getPricePerMs = function() return 0.0004 end, start = function(job, farmId) job.startedFarmId = farmId end,
        getHelperName = function() return "Helfer Paul" end, stop = function(job, msg) job.stoppedWith = msg end }
    AIJobFieldWork = setmetatable({ getPricePerMs = function() return 0.0005 end,
        -- FS25 AIJobFieldWork:getIsStartable / .getIsStartErrorText (reduced to the helper limit)
        getIsStartable = function() return true, 0 end,
        getIsStartErrorText = function(state) return "vanilla " .. tostring(state) end }, { __index = AIJob })
    AIJobConveyor = setmetatable({ getPricePerMs = function() return 0.00005 end }, { __index = AIJob })
    Utils = {
        appendedFunction = function(orig, fn) return function(...) orig(...); fn(...) end end,
        overwrittenFunction = function(orig, fn) return function(self, ...) return fn(self, orig, ...) end end,
    }
    g_currentMission.maxNumHirables = 10
    -- FS25 ai/AISystem.lua: startJobInternal = job:start, then addJob; stopJobInternal = job:stop, then removeJob
    AISystem = {
        startJobInternal = function(_, job, farmId)
            job:start(farmId)
            for _, j in ipairs(jobs) do if j == job then return end end
            jobs[#jobs + 1] = job
        end,
        stopJobInternal = function(_, job, msg)
            job:stop(msg)
            for i, j in ipairs(jobs) do if j == job then table.remove(jobs, i) return end end
        end,
        getActiveJobs = function() return jobs end,
        getJobById = function(_, id) for _, j in ipairs(jobs) do if j.jobId == id then return j end end end,
        stopJob = function(self, job, msg) self:stopJobInternal(job, msg) end,
    }
    g_currentMission.aiSystem = setmetatable({}, { __index = AISystem })
    helpers.loadGameModules()
    local adapter = RPSimGameAdapter.new()
    adapter.config = RPSimConfig.new()
    local bridge = RPSimBridge.new(RPSimConfig.new(), RPSimBridgePaths.new("/ms/"), adapter)
    bridge.workforceEnabled = RPSim.helperHooks
    bridge.started = true
    RPSim.bridge = bridge
    local function newJob(cls, id)
        local job = setmetatable({ jobId = id }, { __index = cls })
        jobs[#jobs + 1] = job
        return job
    end
    return game, bridge, adapter, newJob
end

local function cleanup()
    RPSim.bridge, RPSim.limitStops = nil, {}
    AIJob, AIJobFieldWork, AIJobConveyor, AISystem, Utils, g_storeManager = nil, nil, nil, nil, nil, nil
end

--- A helper start / stop the way the game runs it (AISystem:startJob -> startJobInternal, AISystem:stopJob).
local function start(job, farmId)
    g_currentMission.aiSystem:startJobInternal(job, farmId)
    return job
end

local function stop(job, msg)
    g_currentMission.aiSystem:stopJob(job, msg)
end

function T.TestWorkforce:testHooksDropTheWageAndNameTheHelper()
    local _, bridge, _, newJob = fakeAIGame()
    lu.assertTrue(RPSim.helperHooks)
    bridge:applyRoster(roster({ KLAUS }))
    local field = newJob(AIJobFieldWork, 1)
    start(field, 1)
    local conveyor = newJob(AIJobConveyor, 2)
    start(conveyor, 1)
    local foreign = newJob(AIJob, 3)
    start(foreign, 2)
    lu.assertEquals(field:getPricePerMs(), 0, "an employee drives: no game wage")
    lu.assertEquals(field:getHelperName(), "Klaus Berger")
    lu.assertEquals(conveyor:getPricePerMs(), 0.00005, "no free operator: normal wage")
    lu.assertEquals(conveyor:getHelperName(), "Helfer Paul")
    lu.assertEquals(foreign:getPricePerMs(), 0.0004, "other farms are never assigned")
    stop(field, nil)
    lu.assertNil(bridge.state.workforce.assignments[1], "released when the job stops")
    cleanup()
end

function T.TestWorkforce:testVanillaWageModeKeepsTheGameWage()
    local _, bridge, _, newJob = fakeAIGame()
    bridge:applyRoster(roster({ KLAUS }, { helperWageMode = "VANILLA" }))
    local job = newJob(AIJobFieldWork, 1)
    start(job, 1)
    lu.assertEquals(job:getPricePerMs(), 0.0005)
    lu.assertEquals(job:getHelperName(), "Klaus Berger")
    cleanup()
end

function T.TestWorkforce:testStrictLimitAndStrikeInTheGame()
    local game, bridge, adapter, newJob = fakeAIGame()
    bridge:applyRoster(roster({ KLAUS, ANNA }, { strictHelperLimit = true }))
    lu.assertEquals(g_currentMission.maxNumHirables, 2)
    local job = newJob(AIJobFieldWork, 1)
    start(job, 1)
    -- without a registered strike message the job stops with the generic message and a notification
    AIMessageErrorUnknown = { new = function() return { generic = true } end }
    FSBaseMission = { INGAME_NOTIFICATION_CRITICAL = 3, INGAME_NOTIFICATION_INFO = 1 }
    bridge:applyRoster(roster({ { employeeId = 12, name = "Klaus Berger", role = "MACHINE_OPERATOR", status = "STRIKE" },
        ANNA }, { strictHelperLimit = true }))
    lu.assertTrue(job.stoppedWith.generic)
    lu.assertStrContains(game.notifications[1].text, "Klaus Berger streikt")
    lu.assertEquals(g_currentMission.maxNumHirables, 1)
    bridge:applyRoster(roster({ ANNA }))
    lu.assertEquals(g_currentMission.maxNumHirables, 10, "strict mode off: original value")
    adapter:applyHelperLimit(bridge.state.workforce)
    RPSim.adapter = adapter
    bridge:applyRoster(roster({ ANNA }, { strictHelperLimit = true }))
    RPSim:deleteMap()
    lu.assertEquals(g_currentMission.maxNumHirables, 10, "restored when the map is unloaded")
    AIMessageErrorUnknown, FSBaseMission, RPSim.adapter = nil, nil, nil
    cleanup()
end

function T.TestWorkforce:testOwnStrikeMessageIsRegisteredAndUsed()
    local _, bridge, adapter, newJob = fakeAIGame()
    local registered = {}
    AIMessage = { new = function(mt) return setmetatable({}, mt) end }
    Class = function(members) return { __index = members } end
    g_i18n = { getText = function(_, key) return key == "rpsim_ai_strike" and "%s legt die Arbeit nieder" or key end }
    g_currentMission.aiMessageManager = { registerMessage = function(_, name, cls)
        registered[#registered + 1] = name
        return { name = name, classObject = cls }
    end }
    lu.assertTrue(adapter:registerStrikeMessage())
    lu.assertEquals(registered, { "RPSIM_STRIKE" })
    bridge:applyRoster(roster({ KLAUS }))
    local job = newJob(AIJobFieldWork, 1)
    start(job, 1)
    bridge:applyRoster(roster({ { employeeId = 12, name = "Klaus Berger", role = "MACHINE_OPERATOR", status = "STRIKE" } }))
    lu.assertEquals(string.format(job.stoppedWith:getI18NText(), "Klaus Berger"), "Klaus Berger legt die Arbeit nieder")
    AIMessage, Class, g_i18n = nil, nil, nil
    RPSimGameAdapter.StrikeMessage = nil
    cleanup()
end

--- Courseplay / AutoDrive: a helper started without asking the limit (no getIsStartable, maxNumHirables ignored).
function T.TestWorkforce:testAHelperStartedOverTheLimitIsStoppedInTheNextUpdate()
    local game, bridge, adapter, newJob = fakeAIGame()
    AIMessageErrorUnknown = { new = function() return { generic = true } end }
    FSBaseMission = { INGAME_NOTIFICATION_CRITICAL = 3, INGAME_NOTIFICATION_INFO = 1 }
    bridge:applyRoster(roster({ KLAUS, MIA }, { strictHelperLimit = true }))
    local first = newJob(AIJobFieldWork, 1)
    start(first, 1)
    local second = newJob(AIJobFieldWork, 2)
    start(second, 1)
    lu.assertEquals(first:getHelperName(), "Klaus Berger")
    lu.assertNil(second.stoppedWith, "not stopped inside AISystem:startJob")
    lu.assertNil(bridge.state.workforce.assignments[2])
    RPSim:update(0)
    lu.assertTrue(second.stoppedWith.generic)
    lu.assertNil(first.stoppedWith)
    lu.assertStrContains(game.notifications[1].text, "höchstens 1 Helfer")
    lu.assertEquals(RPSim.limitStops, {})
    -- only the server stops helpers; the normal mode has no own limit
    g_currentMission.getIsServer = function() return false end
    local third = newJob(AIJobFieldWork, 3)
    start(third, 1)
    RPSim:update(0)
    lu.assertNil(third.stoppedWith, "client")
    g_currentMission.getIsServer = function() return true end
    bridge:applyRoster(roster({ KLAUS, MIA }))
    local fourth = newJob(AIJobFieldWork, 4)
    start(fourth, 1)
    RPSim:update(0)
    lu.assertNil(fourth.stoppedWith, "normal mode")
    lu.assertEquals(adapter:collectAIJobs()[1].jobId, 1)
    AIMessageErrorUnknown, FSBaseMission = nil, nil
    cleanup()
end

function T.TestWorkforce:testOwnHelperLimitMessageIsRegisteredAndUsed()
    local _, bridge, adapter, newJob = fakeAIGame()
    local registered = {}
    AIMessage = { new = function(mt) return setmetatable({}, mt) end }
    Class = function(members) return { __index = members } end
    g_i18n = { getText = function(_, key)
        return key == "rpsim_ai_helperLimit" and "%s hält an: kein freier Maschinenführer (strenger Modus)" or key
    end }
    FSBaseMission = { INGAME_NOTIFICATION_CRITICAL = 3, INGAME_NOTIFICATION_INFO = 1 }
    g_currentMission.aiMessageManager = { registerMessage = function(_, name, cls)
        registered[#registered + 1] = name
        return { name = name, classObject = cls }
    end }
    bridge.workforceEnabled = true
    bridge:onSavegameLoaded()
    lu.assertEquals(registered, { "RPSIM_STRIKE", "RPSIM_HELPER_LIMIT" })
    lu.assertTrue(adapter.helperLimitMessageRegistered)
    bridge:applyRoster(roster({ MIA }, { strictHelperLimit = true }))
    local job = newJob(AIJobFieldWork, 1)
    start(job, 1)
    RPSim:update(0)
    lu.assertEquals(string.format(job.stoppedWith:getI18NText(), "Helfer Paul"),
        "Helfer Paul hält an: kein freier Maschinenführer (strenger Modus)")
    AIMessage, Class, g_i18n, FSBaseMission = nil, nil, nil, nil
    RPSimGameAdapter.StrikeMessage, RPSimGameAdapter.HelperLimitMessage = nil, nil
    cleanup()
end

function T.TestWorkforce:testCollectAIJobsOfThePlayerFarm()
    local _, _, adapter, newJob = fakeAIGame()
    local mine = newJob(AIJobFieldWork, 5)
    start(mine, 1)
    function mine.getTitle() return "Fendt 942 Vario" end
    start(newJob(AIJob, 6), 2)
    lu.assertEquals(adapter:collectAIJobs(), { { jobId = 5, title = "Fendt 942 Vario", categories = {} } })
    cleanup()
end

-- ------------------------------------------------------------------ R2-A7 husbandry state
function T.TestWorkforce:testHusbandryStateLikeTheGame()
    helpers.fakeGame()
    helpers.loadGameModules()
    AnimalType = { COW = 1, PIG = 2, HORSE = 3 }
    local function husbandry(typeIndex, clusters)
        return { spec_husbandryAnimals = {}, getUniqueId = function() return "hus_" .. typeIndex end,
            getClusters = function() return clusters end, getAnimalTypeIndex = function() return typeIndex end,
            getGlobalProductionFactor = function() return 0.8 end, getProductionFactor = function() return 0.5 end,
            getTotalFood = function() return 300 end, getFoodCapacity = function() return 1000 end,
            getConditionInfos = function() return { { title = "Wasser", ratio = 0.25 }, { title = "kaputt" } } end }
    end
    local cows = RPSimGameAdapter.husbandryState(husbandry(1, { { health = 80 }, { health = 40 } }))
    lu.assertEquals(cows, { husbandryUniqueId = "hus_1", health = 60, productivity = 0.4, food = 0.3,
        conditions = { { title = "Wasser", ratio = 0.25 } } })
    local pigs = RPSimGameAdapter.husbandryState(husbandry(2, {}))
    lu.assertNil(pigs.productivity)
    lu.assertEquals(pigs.health, 0)
    lu.assertNil(RPSimGameAdapter.husbandryState({ getUniqueId = function() return "plc" end }))
    AnimalType = nil
end

-- ------------------------------------------------------------------ "Schulungen": trainings of machine operators
local CATEGORIES = { LARGE_TRACTOR = { "TRACTORSL" }, COMBINE = { "HARVESTERS" }, TRUCK = { "trucks" } }
local PETER = { employeeId = 20, name = "Peter Mahler", role = "MACHINE_OPERATOR", status = "ACTIVE",
    trainings = { "COMBINE", "TRUCK" } }
local JONAS = { employeeId = 21, name = "Jonas Kurz", role = "MACHINE_OPERATOR", status = "ACTIVE",
    trainings = { "COMBINE" } }

function T.TestWorkforce:testRequiredTrainingsFollowTheShopCategories()
    local wf = RPSimWorkforce.new()
    lu.assertEquals(RPSimWorkforce.requiredTrainings(wf, { "HARVESTERS" }), {}, "no list yet")
    RPSimWorkforce.setRoster(wf, roster({ KLAUS }, { trainingCategories = CATEGORIES }))
    lu.assertEquals(RPSimWorkforce.requiredTrainings(wf, { "HARVESTERS" }), { "COMBINE" })
    lu.assertEquals(RPSimWorkforce.requiredTrainings(wf, { "Trucks" }), { "TRUCK" }, "case does not matter")
    lu.assertEquals(RPSimWorkforce.requiredTrainings(wf, { "TRACTORSM" }), {}, "medium tractors need none")
    lu.assertEquals(RPSimWorkforce.requiredTrainings(wf, { "MY_MOD_CATEGORY" }), {}, "unknown categories need none")
    lu.assertEquals(RPSimWorkforce.requiredTrainings(wf, { "TRUCKS", "HARVESTERS" }), { "COMBINE", "TRUCK" })
    lu.assertEquals(RPSimWorkforce.requiredTrainings(wf, nil), {})
end

function T.TestWorkforce:testOnlyTrainedOperatorsDriveMachinesThatNeedATraining()
    local wf = RPSimWorkforce.new()
    RPSimWorkforce.setRoster(wf, roster({ KLAUS, PETER }, { trainingCategories = CATEGORIES }))
    lu.assertEquals(RPSimWorkforce.assign(wf, 1, { "COMBINE" }), 20, "Klaus has no combine training")
    lu.assertNil(RPSimWorkforce.assign(wf, 2, { "COMBINE" }), "Peter is busy: vanilla helper")
    lu.assertEquals(RPSimWorkforce.assign(wf, 3, {}), 12)
    lu.assertNil(RPSimWorkforce.assign(wf, 4, { "LARGE_TRACTOR" }))
end

function T.TestWorkforce:testSimpleJobsLeaveTheSpecialistsFree()
    local wf = RPSimWorkforce.new()
    RPSimWorkforce.setRoster(wf, roster({ PETER, JONAS, KLAUS }, { trainingCategories = CATEGORIES }))
    lu.assertEquals(RPSimWorkforce.assign(wf, 1, {}), 12, "the operator without trainings takes the tractor")
    lu.assertEquals(RPSimWorkforce.assign(wf, 2, { "COMBINE" }), 21, "fewest trainings first")
    lu.assertEquals(RPSimWorkforce.assign(wf, 3, { "TRUCK" }), 20)
end

function T.TestWorkforce:testTheStartIsOnlyBlockedInTheStrictMode()
    local wf = RPSimWorkforce.new()
    RPSimWorkforce.setRoster(wf, roster({ KLAUS }, { trainingCategories = CATEGORIES }))
    lu.assertFalse(RPSimWorkforce.startBlocked(wf, { "COMBINE" }), "normal mode: vanilla helper drives")
    RPSimWorkforce.setRoster(wf, roster({ KLAUS, JONAS }, { trainingCategories = CATEGORIES, strictHelperLimit = true }))
    lu.assertTrue(RPSimWorkforce.startBlocked(wf, { "TRUCK" }))
    lu.assertFalse(RPSimWorkforce.startBlocked(wf, { "COMBINE" }))
    lu.assertFalse(RPSimWorkforce.startBlocked(wf, {}), "no training needed: the helper limit decides")
    RPSimWorkforce.assign(wf, 1, { "COMBINE" })
    lu.assertTrue(RPSimWorkforce.startBlocked(wf, { "COMBINE" }), "the trained operator is busy")
end

function T.TestWorkforce:testTrainingsSurviveTheSavegame()
    local x = { data = {} }
    function x:setString(k, v) self.data[k] = v end
    function x:setInt(k, v) self.data[k] = v end
    function x:setFloat(k, v) self.data[k] = v end
    function x:getString(k) local v = self.data[k]; return v ~= nil and tostring(v) or nil end
    function x:getInt(k) return self.data[k] end
    function x:getFloat(k) return self.data[k] end
    local state = RPSimProcessor.newState(RPSimConfig.new())
    RPSimWorkforce.setRoster(state.workforce, roster({ KLAUS, PETER }, { trainingCategories = CATEGORIES }))
    RPSimPersistence.save(x, state)
    local loaded = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load(x, loaded)
    lu.assertEquals(loaded.workforce.roster, state.workforce.roster)
    lu.assertEquals(loaded.workforce.roster.employees[2].trainings, { COMBINE = true, TRUCK = true })
    lu.assertEquals(RPSimWorkforce.requiredTrainings(loaded.workforce, { "TRUCKS" }), { "TRUCK" })
end

--- Fake vehicle of a helper job: job.vehicleParameter:getVehicle() and the store item of its xml file.
local function withVehicle(job, category, farmId)
    local vehicle = { configFileName = "data/vehicles/" .. category .. ".xml",
        getOwnerFarmId = function() return farmId or 1 end, getFullName = function() return "Test " .. category end }
    job.vehicleParameter = { getVehicle = function() return vehicle end }
    return job
end

local function storeManager()
    g_storeManager = { getItemByXMLFilename = function(_, file)
        local category = string.match(file, "([%w_]+)%.xml$")
        return { categoryName = string.upper(category), categoryNames = { string.upper(category) } }
    end }
end

function T.TestWorkforce:testTheStartHookAssignsATrainedOperator()
    local _, bridge, adapter, newJob = fakeAIGame()
    storeManager()
    bridge:applyRoster(roster({ KLAUS, PETER }, { trainingCategories = CATEGORIES }))
    lu.assertEquals(adapter:jobVehicleInfo(withVehicle(newJob(AIJobFieldWork, 1), "harvesters")).categories,
        { "HARVESTERS" })
    local combine = withVehicle(newJob(AIJobFieldWork, 1), "harvesters")
    start(combine, 1)
    lu.assertEquals(combine:getHelperName(), "Peter Mahler")
    local truck = withVehicle(newJob(AIJobFieldWork, 2), "trucks")
    lu.assertEquals({ truck:getIsStartable(nil) }, { true, 0 }, "normal mode: never blocked")
    start(truck, 1)
    lu.assertEquals(truck:getHelperName(), "Helfer Paul", "no trained operator free: vanilla helper")
    cleanup()
end

function T.TestWorkforce:testTheStrictModeRefusesTheStartWithAMessage()
    local game, bridge, _, newJob = fakeAIGame()
    storeManager()
    bridge:applyRoster(roster({ KLAUS }, { trainingCategories = CATEGORIES, strictHelperLimit = true }))
    local combine = withVehicle(newJob(AIJobFieldWork, 1), "harvesters")
    local ok, state = combine:getIsStartable(nil)
    lu.assertFalse(ok)
    lu.assertEquals(state, RPSim.START_ERROR_NO_TRAINING)
    lu.assertStrContains(game.notifications[1].text, "Mähdrescher")
    lu.assertStrContains(game.notifications[1].text, "Test harvesters")
    lu.assertEquals(AIJobFieldWork.getIsStartErrorText(state), "Kein geschulter Maschinenführer frei")
    lu.assertEquals(combine:getIsStartErrorText(state), "Kein geschulter Maschinenführer frei", "instance call")
    lu.assertEquals(AIJobFieldWork.getIsStartErrorText(1), "vanilla 1")
    local tractor = withVehicle(newJob(AIJobFieldWork, 2), "tractorsM")
    lu.assertEquals({ tractor:getIsStartable(nil) }, { true, 0 })
    local foreign = withVehicle(newJob(AIJobFieldWork, 3), "harvesters", 2)
    lu.assertEquals({ foreign:getIsStartable(nil) }, { true, 0 }, "other farms are not checked")
    cleanup()
end

function T.TestWorkforce:testTheStrictModeRefusesAStartOverTheLimit()
    local game, bridge, _, newJob = fakeAIGame()
    storeManager()
    bridge:applyRoster(roster({ KLAUS }, { strictHelperLimit = true }))
    start(newJob(AIJob, 7), 2) -- other farms do not count
    local first = withVehicle(newJob(AIJobFieldWork, 1), "tractorsM")
    lu.assertEquals({ first:getIsStartable(nil) }, { true, 0 })
    start(first, 1)
    local second = withVehicle(newJob(AIJobFieldWork, nil), "tractorsM")
    local ok, state = second:getIsStartable(nil)
    lu.assertFalse(ok)
    lu.assertEquals(state, RPSim.START_ERROR_HELPER_LIMIT)
    lu.assertStrContains(game.notifications[1].text, "höchstens 1 Helfer")
    lu.assertEquals(AIJobFieldWork.getIsStartErrorText(state), "Kein freier Maschinenführer (strenger Modus)")
    bridge:applyRoster(roster({ KLAUS }))
    lu.assertEquals({ second:getIsStartable(nil) }, { true, 0 }, "normal mode: no own limit")
    cleanup()
end

-- ------------------------------------------------------------------ Courseplay (Courseplay_FS25, scripts/ai/jobs)
--- CpObject(base): a shallow copy of the base class (scripts/CpObject.lua). CpAIJob replaces start / getIsStartable
-- without calling AIJob's and applies its wage modifier on AIJob.getPricePerMs (CpAIJob.lua).
local function cpObject(base)
    local c = {}
    for k, v in pairs(base) do c[k] = v end
    return c
end

local function courseplayJobClasses()
    local CpAIJob = cpObject(AIJob)
    function CpAIJob.start(job, farmId)
        job.startedFarmId = farmId
        job.cpStarted = true
    end
    function CpAIJob.getIsStartable() return true, 0 end
    function CpAIJob.getIsStartErrorText(state) return "cp " .. tostring(state) end
    function CpAIJob.getPricePerMs(job) return AIJob.getPricePerMs(job) * 0.5 end
    local CpAIJobFieldWork = cpObject(CpAIJob)
    g_currentMission.aiJobTypeManager = { jobTypes = {
        { name = "FIELDWORK", classObject = AIJobFieldWork },
        { name = "FIELDWORK_CP", classObject = CpAIJobFieldWork },
    } }
    return CpAIJobFieldWork
end

local function cpJob(cls, id, category)
    local job = setmetatable({ jobId = id }, { __index = cls })
    return withVehicle(job, category or "tractorsM")
end

function T.TestWorkforce:testCourseplayHelpersGetAnOperatorNameAndNoWage()
    local _, bridge = fakeAIGame()
    lu.assertTrue(RPSim.jobHooksOnAISystem, "start / stop hooks sit on AISystem")
    storeManager()
    local cls = courseplayJobClasses()
    RPSim.onStartMission()
    bridge:applyRoster(roster({ KLAUS }))
    local job = start(cpJob(cls, 1), 1)
    lu.assertTrue(job.cpStarted, "CpAIJob:start never calls AIJob:start")
    lu.assertEquals(bridge.state.workforce.assignments[1], 12)
    lu.assertEquals(job:getHelperName(), "Klaus Berger")
    lu.assertEquals(job:getPricePerMs(), 0, "an employee drives: no game wage")
    stop(job, nil)
    lu.assertNil(bridge.state.workforce.assignments[1], "released when the job stops")
    lu.assertEquals(job:getPricePerMs(), 0.0002, "Courseplay's wage modifier stays for helpers without operator")
    lu.assertEquals(job:getHelperName(), "Helfer Paul")
    cleanup()
end

function T.TestWorkforce:testTheStrictModeRefusesCourseplayStarts()
    local game, bridge = fakeAIGame()
    storeManager()
    local cls = courseplayJobClasses()
    RPSim.onStartMission()
    RPSim.hookRegisteredJobTypes() -- twice: no double hooks
    bridge:applyRoster(roster({ KLAUS }, { trainingCategories = CATEGORIES, strictHelperLimit = true }))
    local combine = cpJob(cls, 1, "harvesters")
    local ok, state = combine:getIsStartable(nil)
    lu.assertFalse(ok)
    lu.assertEquals(state, RPSim.START_ERROR_NO_TRAINING)
    lu.assertEquals(#game.notifications, 1)
    -- Courseplay's frame asks the class statically (CpCourseGeneratorFrame: classObject.getIsStartErrorText(state))
    lu.assertEquals(cls.getIsStartErrorText(state), "Kein geschulter Maschinenführer frei")
    lu.assertEquals(cls.getIsStartErrorText(1), "cp 1")
    start(cpJob(cls, 2), 1)
    local second = cpJob(cls, nil)
    ok, state = second:getIsStartable(nil)
    lu.assertFalse(ok)
    lu.assertEquals(state, RPSim.START_ERROR_HELPER_LIMIT)
    cleanup()
end

function T.TestWorkforce:testAClassCopiedFromHookedAIJobIsNotHookedTwice()
    fakeAIGame()
    local copied = cpObject(AIJob) -- Courseplay loaded after FarmPulse: the copy already carries the hooked methods
    local name = copied.getHelperName
    lu.assertTrue(RPSim.hookJobClass(copied))
    lu.assertIs(copied.getHelperName, name)
    cleanup()
end

-- ------------------------------------------------------------------ AutoDrive (FS25_AutoDrive, no AIJob)
--- Fake AutoDrive vehicle: vehicle.ad.stateModule:isActive() (ADStateModule), vehicle:stopAutoDrive() (registered
-- function of the AutoDrive specialization).
local function adVehicle(game, category, farmId)
    local v = { configFileName = "data/vehicles/" .. category .. ".xml", stops = 0,
        getOwnerFarmId = function() return farmId or 1 end, getFullName = function() return "AD " .. category end }
    v.ad = { isStoppingWithError = false, stateModule = { active = false } }
    function v.ad.stateModule.isActive(sm) return sm.active end
    function v.ad.stateModule.setLoopsDone(sm, n) sm.loopsDone = n end
    function v.stopAutoDrive(self)
        self.stops = self.stops + 1
        self.ad.stateModule.active = false
    end
    game.vehicles[#game.vehicles + 1] = v
    return v
end

function T.TestWorkforce:testAutoDriveDrivesAreHelpersWithAnOperatorAndWorkedTime()
    local game, bridge = fakeAIGame()
    storeManager()
    bridge:applyRoster(roster({ KLAUS }))
    local v = adVehicle(game, "tractorsM")
    local foreign = adVehicle(game, "tractorsM", 2)
    v.ad.stateModule.active, foreign.ad.stateModule.active = true, true
    for k in pairs({ exportIntervalMs = 1, importIntervalMs = 1, marketContextIntervalMs = 1, fieldExportIntervalMs = 1 }) do
        bridge.cfg[k] = 1e12
    end
    bridge.exportTimer, bridge.importTimer, bridge.fieldTimer, bridge.marketContextTimer = 0, 0, 0, 0
    bridge:update(999)
    lu.assertNil(bridge.state.workforce.assignments[-1], "checked every second")
    bridge:update(1)
    lu.assertEquals(bridge.state.workforce.assignments[-1], 12)
    lu.assertEquals(v.stops, 0)
    local raw = bridge:sampleWorkforce(1000)
    lu.assertEquals(raw.activeJobs, { { jobId = -1, employeeId = 12, title = "AD tractorsM" } },
        "other farms are not tracked")
    bridge:sampleWorkforce(1000 + 3600000)
    lu.assertEquals(bridge.state.workforce.workedGameMs["12"], 3600000)
    lu.assertEquals(RPSimFarmFacts.buildWorkforce(raw).activeJobs[1].jobId, -1)
    v.ad.stateModule.active = false
    bridge:trackAutoDrive()
    lu.assertNil(bridge.state.workforce.assignments[-1], "released when AutoDrive stops")
    v.ad.stateModule.active = true
    bridge:trackAutoDrive()
    lu.assertEquals(bridge.state.workforce.assignments[-2], 12, "a new drive gets a new id")
    cleanup()
end

function T.TestWorkforce:testTheStrictModeStopsAutoDriveOverTheLimitOrWithoutTraining()
    local game, bridge, _, newJob = fakeAIGame()
    storeManager()
    FSBaseMission = { INGAME_NOTIFICATION_CRITICAL = 3, INGAME_NOTIFICATION_INFO = 1 }
    bridge:applyRoster(roster({ KLAUS, ANNA }, { trainingCategories = CATEGORIES, strictHelperLimit = true }))
    local combine = adVehicle(game, "harvesters")
    combine.ad.stateModule.active = true
    bridge:trackAutoDrive()
    lu.assertEquals(combine.stops, 1, "no operator with the combine training")
    lu.assertTrue(combine.ad.isStoppingWithError, "no hand-over to Courseplay or a game helper")
    lu.assertStrContains(game.notifications[1].text, "Mähdrescher")
    lu.assertNil(bridge.state.workforce.assignments[-1])
    start(withVehicle(newJob(AIJobFieldWork, 1), "tractorsM"), 1)
    local first = adVehicle(game, "tractorsM")
    first.ad.stateModule.active = true
    bridge:trackAutoDrive()
    lu.assertEquals(first.stops, 0, "second helper of two operators")
    local third = adVehicle(game, "tractorsM")
    third.stopAutoDrive = function(veh) veh.stops = veh.stops + 1 end -- AutoDrive does not stop
    third.ad.stateModule.active = true
    bridge:trackAutoDrive()
    bridge:trackAutoDrive()
    lu.assertEquals(third.stops, 1, "stopped once, not again every second")
    lu.assertStrContains(game.notifications[#game.notifications].text, "höchstens 2 Helfer")
    -- a running AutoDrive drive counts against the limit of the game helper and Courseplay starts too
    local ok, state = withVehicle(newJob(AIJobFieldWork, nil), "tractorsM"):getIsStartable(nil)
    lu.assertFalse(ok)
    lu.assertEquals(state, RPSim.START_ERROR_HELPER_LIMIT)
    FSBaseMission = nil
    cleanup()
end

function T.TestWorkforce:testAutoDriveHandsItsOperatorOverToTheFollowUpJob()
    local game, bridge, _, newJob = fakeAIGame()
    storeManager()
    bridge:applyRoster(roster({ KLAUS }, { strictHelperLimit = true }))
    local v = adVehicle(game, "tractorsM")
    v.ad.stateModule.active = true
    bridge:trackAutoDrive()
    lu.assertEquals(bridge.state.workforce.assignments[-1], 12)
    -- AutoDrive:stopAutoDrive deactivates, then hands over (AutoDrive.passToExternalMod_AI: AISystem:startJob) before
    -- the next AutoDrive check
    v.ad.stateModule.active = false
    local job = withVehicle(newJob(AIJobFieldWork, 5), "tractorsM")
    lu.assertEquals({ job:getIsStartable(nil) }, { true, 0 })
    start(job, 1)
    RPSim:update(0)
    lu.assertNil(job.stoppedWith)
    lu.assertEquals(job:getHelperName(), "Klaus Berger")
    cleanup()
end

function T.TestWorkforce:testAStrikeStopsTheAutoDriveOfTheEmployee()
    local game, bridge = fakeAIGame()
    storeManager()
    FSBaseMission = { INGAME_NOTIFICATION_CRITICAL = 3, INGAME_NOTIFICATION_INFO = 1 }
    bridge:applyRoster(roster({ KLAUS }))
    local v = adVehicle(game, "tractorsM")
    v.ad.stateModule.active = true
    bridge:trackAutoDrive()
    bridge:applyRoster(roster({ { employeeId = 12, name = "Klaus Berger", role = "MACHINE_OPERATOR", status = "STRIKE" } }))
    lu.assertEquals(v.stops, 1)
    lu.assertStrContains(game.notifications[1].text, "Klaus Berger streikt")
    lu.assertNil(bridge.state.workforce.assignments[-1])
    FSBaseMission = nil
    cleanup()
end

-- Roadmap V3 R3-P2: apprentices drive like a machine operator without trainings, operators first
local TIM = { employeeId = 30, name = "Tim Lehrling", role = "APPRENTICE", status = "ACTIVE", trainings = { "COMBINE" } }

function T.TestWorkforce:testApprenticesDriveOnlyWhatNeedsNoTrainingAndAfterTheOperators()
    local wf = RPSimWorkforce.new()
    RPSimWorkforce.setRoster(wf, roster({ TIM, KLAUS }, { trainingCategories = CATEGORIES }))
    lu.assertEquals(wf.roster.employees[1].trainings, {}, "trainings of an apprentice are ignored")
    lu.assertEquals(RPSimWorkforce.assign(wf, 1, {}), 12, "the machine operator first")
    lu.assertEquals(RPSimWorkforce.assign(wf, 2, {}), 30, "then the apprentice: medium tractor")
    RPSimWorkforce.release(wf, 2)
    lu.assertNil(RPSimWorkforce.assign(wf, 3, { "COMBINE" }), "no combine for the apprentice: vanilla helper")
    lu.assertEquals(RPSimWorkforce.helperName(wf, 1), "Klaus Berger")
    -- the strict helper limit counts apprentices as drivers
    RPSimWorkforce.setRoster(wf, roster({ TIM, KLAUS, MIA }, { strictHelperLimit = true }))
    lu.assertEquals(RPSimWorkforce.helperLimit(wf, 10), 2)
end

-- Roadmap V3.1 R31-A5: seasonal workers drive without trainings, after the machine operators, before the apprentices
local SVEN = { employeeId = 40, name = "Sven Saison", role = "SEASONAL_WORKER", status = "ACTIVE",
    trainings = { "COMBINE" } }

function T.TestWorkforce:testSeasonalWorkersDriveAfterOperatorsAndBeforeApprentices()
    local wf = RPSimWorkforce.new()
    RPSimWorkforce.setRoster(wf, roster({ TIM, SVEN, KLAUS }, { trainingCategories = CATEGORIES }))
    lu.assertEquals(wf.roster.employees[2].trainings, {}, "trainings of a seasonal worker are ignored")
    lu.assertEquals(RPSimWorkforce.assign(wf, 1, {}), 12, "the machine operator first")
    lu.assertEquals(RPSimWorkforce.assign(wf, 2, {}), 40, "then the seasonal worker")
    lu.assertEquals(RPSimWorkforce.assign(wf, 3, {}), 30, "then the apprentice")
    RPSimWorkforce.release(wf, 2)
    lu.assertNil(RPSimWorkforce.assign(wf, 4, { "COMBINE" }), "no combine for the seasonal worker")
    RPSimWorkforce.setRoster(wf, roster({ TIM, SVEN, KLAUS }, { strictHelperLimit = true }))
    lu.assertEquals(RPSimWorkforce.helperLimit(wf, 10), 3, "the strict limit counts seasonal workers as drivers")
end

return T
