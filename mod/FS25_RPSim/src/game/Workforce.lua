-- Employees as FS25 helpers (Roadmap V2, R2-A0..A5). Pure logic, testable without FS25; the game hooks live in
-- RPSim.lua and RPSimGameAdapter.
--
-- state = {
--   roster = { employees = { {employeeId, name, role, status, trainings = { [TRAINING] = true }} }, helperWageMode,
--              strictHelperLimit, trainingCategories = { [TRAINING] = { CATEGORY, ... } },
--              categoryTrainings = { [CATEGORY] = { TRAINING, ... } } } | nil,
--   assignments = { [jobId] = employeeId },   -- not persisted: job ids are new after loading, jobs are re-assigned
--   workedGameMs = { [employeeId] = ms },     -- cumulative, persisted in the savegame
--   lastSampleGameTime = number | nil,
-- }
RPSimWorkforce = {}

--- Only machine operators drive helpers (R2-A2).
RPSimWorkforce.DRIVER_ROLE = "MACHINE_OPERATOR"
--- Roadmap V3 R3-P2: roles that drive helpers, by assignment priority (machine operators first, then apprentices -
-- apprentices drive like an operator without trainings). Roadmap V3.1 R31-A5: seasonal workers drive without trainings
-- like apprentices, after the machine operators and before the apprentices (owner decision).
RPSimWorkforce.DRIVER_ROLES = { MACHINE_OPERATOR = 1, SEASONAL_WORKER = 2, APPRENTICE = 3 }
RPSimWorkforce.APPRENTICE_ROLE = "APPRENTICE"
--- Roles that never have a training (R3-P2 apprentice, R31-A5 seasonal worker).
RPSimWorkforce.UNTRAINED_ROLES = { APPRENTICE = true, SEASONAL_WORKER = true }

--- "Schulungen": titles of the trainings for the game messages (the backend sends the codes).
RPSimWorkforce.TRAINING_TITLES = { LARGE_TRACTOR = "Große Traktoren", COMBINE = "Mähdrescher",
    FORAGE_HARVESTER = "Feldhäcksler", SPECIAL_HARVESTER = "Spezialernter", TRUCK = "LKW",
    SELF_PROPELLED = "Selbstfahrer & Lader" }

function RPSimWorkforce.trainingTitle(code)
    return RPSimWorkforce.TRAINING_TITLES[code] or tostring(code)
end

local function stringList(v)
    local list = {}
    if type(v) == "table" then
        for _, x in ipairs(v) do
            if type(x) == "string" and x ~= "" then
                list[#list + 1] = x
            end
        end
    end
    return list
end

local function trainingSet(v)
    local set = {}
    for _, t in ipairs(stringList(v)) do
        set[t] = true
    end
    return set
end

--- "Schulungen": training -> categories as sent, plus the reverse index category (upper case) -> trainings.
local function categoryIndex(trainingCategories)
    local byTraining, byCategory = {}, {}
    if type(trainingCategories) == "table" then
        local codes = {}
        for code, _ in pairs(trainingCategories) do
            if type(code) == "string" then
                codes[#codes + 1] = code
            end
        end
        table.sort(codes)
        for _, code in ipairs(codes) do
            local cats = {}
            for _, c in ipairs(stringList(trainingCategories[code])) do
                local upper = string.upper(c)
                cats[#cats + 1] = upper
                byCategory[upper] = byCategory[upper] or {}
                table.insert(byCategory[upper], code)
            end
            byTraining[code] = cats
        end
    end
    return byTraining, byCategory
end

function RPSimWorkforce.new()
    return { roster = nil, assignments = {}, workedGameMs = {}, lastSampleGameTime = nil }
end

--- R2-A0: replaces the list completely (idempotent). The list order is the assignment priority (the backend sorts by
-- skill). Returns the job ids whose employee is no longer allowed to drive: strike (R2-A5; the assignment stays until
-- the caller stopped the job, so the stop message still names the employee) and released (dismissed, on leave).
function RPSimWorkforce.setRoster(state, ins)
    local employees = {}
    for _, e in ipairs(ins.employees or {}) do
        -- R3-P2 / R31-A5: apprentices and seasonal workers never have a training (trainings in the list are ignored)
        employees[#employees + 1] = { employeeId = e.employeeId, name = e.name, role = e.role, status = e.status,
            trainings = RPSimWorkforce.UNTRAINED_ROLES[e.role] and {} or trainingSet(e.trainings) }
    end
    local byTraining, byCategory = categoryIndex(ins.trainingCategories)
    state.roster = { employees = employees, helperWageMode = ins.helperWageMode,
        strictHelperLimit = ins.strictHelperLimit == true, trainingCategories = byTraining,
        categoryTrainings = byCategory }
    local strike, released = {}, {}
    for jobId, employeeId in pairs(state.assignments) do
        local e = RPSimWorkforce.employee(state, employeeId)
        if e == nil or e.status ~= "ACTIVE" then
            if e ~= nil and e.status == "STRIKE" then
                strike[#strike + 1] = jobId
            else
                released[#released + 1] = jobId
                state.assignments[jobId] = nil
            end
        end
    end
    table.sort(strike)
    table.sort(released)
    return strike, released
end

function RPSimWorkforce.employee(state, employeeId)
    for _, e in ipairs(state.roster ~= nil and state.roster.employees or {}) do
        if e.employeeId == employeeId then
            return e
        end
    end
    return nil
end

--- "Schulungen": trainings a helper needs for a vehicle with these FS25 shop categories (sorted, {} = none). Categories
-- no training lists (small / medium tractors, cars, mod categories ...) need none.
function RPSimWorkforce.requiredTrainings(state, categories)
    local index = state.roster ~= nil and state.roster.categoryTrainings or nil
    if index == nil or type(categories) ~= "table" then
        return {}
    end
    local seen, list = {}, {}
    for _, c in ipairs(categories) do
        for _, t in ipairs(type(c) == "string" and index[string.upper(c)] or {}) do
            if not seen[t] then
                seen[t] = true
                list[#list + 1] = t
            end
        end
    end
    table.sort(list)
    return list
end

--- The employee has every required training.
function RPSimWorkforce.qualified(e, required)
    for _, t in ipairs(required or {}) do
        if e.trainings == nil or not e.trainings[t] then
            return false
        end
    end
    return true
end

local function countTrainings(e)
    local n = 0
    for _ in pairs(e.trainings or {}) do
        n = n + 1
    end
    return n
end

--- Free ACTIVE driver with the required trainings: machine operators before apprentices (R3-P2), among them the one
-- with the fewest trainings (specialists stay free for their machines), ties in list order (= skill, highest first).
-- nil when there is none.
local function pickDriver(state, required)
    local busy = {}
    for _, id in pairs(state.assignments) do
        busy[id] = true
    end
    local best, bestRank, bestCount = nil, nil, nil
    for _, e in ipairs(state.roster.employees) do
        local rank = RPSimWorkforce.DRIVER_ROLES[e.role]
        if rank ~= nil and e.status == "ACTIVE" and not busy[e.employeeId] and RPSimWorkforce.qualified(e, required) then
            local n = countTrainings(e)
            if best == nil or rank < bestRank or (rank == bestRank and n < bestCount) then
                best, bestRank, bestCount = e, rank, n
            end
        end
    end
    return best
end

--- R2-A2: assigns a free ACTIVE machine operator with the required trainings ("Schulungen", nil / {} = none needed);
-- nil when none is free (vanilla helper).
function RPSimWorkforce.assign(state, jobId, required)
    if jobId == nil or state.roster == nil then
        return nil
    end
    if state.assignments[jobId] ~= nil then
        return state.assignments[jobId]
    end
    local e = pickDriver(state, required)
    if e == nil then
        return nil
    end
    state.assignments[jobId] = e.employeeId
    return e.employeeId
end

--- "Schulungen": in the strict mode (R2-A3) a helper of a vehicle that needs a training only starts when a free machine
-- operator with it exists; otherwise the vanilla helper drives as before.
function RPSimWorkforce.startBlocked(state, required)
    if state.roster == nil or not state.roster.strictHelperLimit or required == nil or #required == 0 then
        return false
    end
    return pickDriver(state, required) == nil
end

function RPSimWorkforce.release(state, jobId)
    if jobId ~= nil then
        state.assignments[jobId] = nil
    end
end

--- Name of the employee driving the job, nil for a vanilla helper.
function RPSimWorkforce.helperName(state, jobId)
    local id = jobId ~= nil and state.assignments[jobId] or nil
    local e = id ~= nil and RPSimWorkforce.employee(state, id) or nil
    return e ~= nil and e.name or nil
end

--- R2-A1: the game wage is dropped when an employee drives and the wage mode is EMPLOYEES.
function RPSimWorkforce.wageFree(state, jobId)
    return state.roster ~= nil and state.roster.helperWageMode == "EMPLOYEES" and jobId ~= nil
        and state.assignments[jobId] ~= nil
end

--- Number of ACTIVE drivers - machine operators and apprentices (R3-P2); on leave, at a training or on strike do not
-- count.
function RPSimWorkforce.activeOperators(state)
    local n = 0
    for _, e in ipairs(state.roster ~= nil and state.roster.employees or {}) do
        if RPSimWorkforce.DRIVER_ROLES[e.role] ~= nil and e.status == "ACTIVE" then
            n = n + 1
        end
    end
    return n
end

--- R2-A3: helper limit in the strict mode = min(original, active machine operators); otherwise the original value.
function RPSimWorkforce.helperLimit(state, original)
    if state.roster == nil or not state.roster.strictHelperLimit then
        return original
    end
    return math.min(original, RPSimWorkforce.activeOperators(state))
end

--- R2-A3: in the strict mode the farm already runs as many helpers as it has active machine operators. The mod's own
-- check: maxNumHirables only counts where a start asks AISystem:getAILimitedReached (map menu, key in the vehicle), mods
-- like Courseplay or AutoDrive start helpers their own way. running = helpers of the farm without the one to start.
function RPSimWorkforce.limitReached(state, running)
    if state.roster == nil or not state.roster.strictHelperLimit then
        return false
    end
    return running >= RPSimWorkforce.activeOperators(state)
end

--- R2-A4: credits the game time since the last sample to every employee driving one of the running jobs.
-- A rewound game time (reload) only resets the sample point.
function RPSimWorkforce.accrue(state, jobIds, gameTime)
    local last = state.lastSampleGameTime
    state.lastSampleGameTime = gameTime
    if last == nil or gameTime <= last then
        return
    end
    local elapsed = gameTime - last
    for _, jobId in ipairs(jobIds) do
        local id = state.assignments[jobId]
        if id ~= nil then
            local key = tostring(id)
            state.workedGameMs[key] = (state.workedGameMs[key] or 0) + elapsed
        end
    end
end

--- Raw workforce block for RPSimFarmFacts.buildWorkforce. jobs = { {jobId, title} } of the player farm.
function RPSimWorkforce.toRaw(state, jobs)
    local activeJobs = {}
    for _, j in ipairs(jobs) do
        activeJobs[#activeJobs + 1] = { jobId = j.jobId, employeeId = state.assignments[j.jobId], title = j.title }
    end
    local worked = {}
    for id, ms in pairs(state.workedGameMs) do
        worked[id] = ms
    end
    return { activeJobs = activeJobs, workedGameMs = worked }
end
