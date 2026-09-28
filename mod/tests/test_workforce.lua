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
    AIJobFieldWork = setmetatable({ getPricePerMs = function() return 0.0005 end }, { __index = AIJob })
    AIJobConveyor = setmetatable({ getPricePerMs = function() return 0.00005 end }, { __index = AIJob })
    Utils = {
        appendedFunction = function(orig, fn) return function(...) orig(...); fn(...) end end,
        overwrittenFunction = function(orig, fn) return function(self, ...) return fn(self, orig, ...) end end,
    }
    g_currentMission.maxNumHirables = 10
    g_currentMission.aiSystem = {
        getActiveJobs = function() return jobs end,
        getJobById = function(_, id) for _, j in ipairs(jobs) do if j.jobId == id then return j end end end,
        stopJob = function(_, job, msg) job:stop(msg) end,
    }
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
    RPSim.bridge = nil
    AIJob, AIJobFieldWork, AIJobConveyor, Utils = nil, nil, nil, nil
end

function T.TestWorkforce:testHooksDropTheWageAndNameTheHelper()
    local _, bridge, _, newJob = fakeAIGame()
    lu.assertTrue(RPSim.helperHooks)
    bridge:applyRoster(roster({ KLAUS }))
    local field = newJob(AIJobFieldWork, 1)
    field:start(1)
    local conveyor = newJob(AIJobConveyor, 2)
    conveyor:start(1)
    local foreign = newJob(AIJob, 3)
    foreign:start(2)
    lu.assertEquals(field:getPricePerMs(), 0, "an employee drives: no game wage")
    lu.assertEquals(field:getHelperName(), "Klaus Berger")
    lu.assertEquals(conveyor:getPricePerMs(), 0.00005, "no free operator: normal wage")
    lu.assertEquals(conveyor:getHelperName(), "Helfer Paul")
    lu.assertEquals(foreign:getPricePerMs(), 0.0004, "other farms are never assigned")
    field:stop(nil)
    lu.assertNil(bridge.state.workforce.assignments[1], "released when the job stops")
    cleanup()
end

function T.TestWorkforce:testVanillaWageModeKeepsTheGameWage()
    local _, bridge, _, newJob = fakeAIGame()
    bridge:applyRoster(roster({ KLAUS }, { helperWageMode = "VANILLA" }))
    local job = newJob(AIJobFieldWork, 1)
    job:start(1)
    lu.assertEquals(job:getPricePerMs(), 0.0005)
    lu.assertEquals(job:getHelperName(), "Klaus Berger")
    cleanup()
end

function T.TestWorkforce:testStrictLimitAndStrikeInTheGame()
    local game, bridge, adapter, newJob = fakeAIGame()
    bridge:applyRoster(roster({ KLAUS, ANNA }, { strictHelperLimit = true }))
    lu.assertEquals(g_currentMission.maxNumHirables, 2)
    local job = newJob(AIJobFieldWork, 1)
    job:start(1)
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
    job:start(1)
    bridge:applyRoster(roster({ { employeeId = 12, name = "Klaus Berger", role = "MACHINE_OPERATOR", status = "STRIKE" } }))
    lu.assertEquals(string.format(job.stoppedWith:getI18NText(), "Klaus Berger"), "Klaus Berger legt die Arbeit nieder")
    AIMessage, Class, g_i18n = nil, nil, nil
    RPSimGameAdapter.StrikeMessage = nil
    cleanup()
end

function T.TestWorkforce:testCollectAIJobsOfThePlayerFarm()
    local _, _, adapter, newJob = fakeAIGame()
    local mine = newJob(AIJobFieldWork, 5)
    mine:start(1)
    function mine.getTitle() return "Fendt 942 Vario" end
    newJob(AIJob, 6):start(2)
    lu.assertEquals(adapter:collectAIJobs(), { { jobId = 5, title = "Fendt 942 Vario" } })
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

return T
