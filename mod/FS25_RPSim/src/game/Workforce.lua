-- Employees as FS25 helpers (Roadmap V2, R2-A0..A5). Pure logic, testable without FS25; the game hooks live in
-- RPSim.lua and RPSimGameAdapter.
--
-- state = {
--   roster = { employees = { {employeeId, name, role, status} }, helperWageMode, strictHelperLimit } | nil,
--   assignments = { [jobId] = employeeId },   -- not persisted: job ids are new after loading, jobs are re-assigned
--   workedGameMs = { [employeeId] = ms },     -- cumulative, persisted in the savegame
--   lastSampleGameTime = number | nil,
-- }
RPSimWorkforce = {}

--- Only machine operators drive helpers (R2-A2).
RPSimWorkforce.DRIVER_ROLE = "MACHINE_OPERATOR"

function RPSimWorkforce.new()
    return { roster = nil, assignments = {}, workedGameMs = {}, lastSampleGameTime = nil }
end

--- R2-A0: replaces the list completely (idempotent). The list order is the assignment priority (the backend sorts by
-- skill). Returns the job ids whose employee is no longer allowed to drive: strike (R2-A5; the assignment stays until
-- the caller stopped the job, so the stop message still names the employee) and released (dismissed, on leave).
function RPSimWorkforce.setRoster(state, ins)
    local employees = {}
    for _, e in ipairs(ins.employees or {}) do
        employees[#employees + 1] = { employeeId = e.employeeId, name = e.name, role = e.role, status = e.status }
    end
    state.roster = { employees = employees, helperWageMode = ins.helperWageMode,
        strictHelperLimit = ins.strictHelperLimit == true }
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

--- R2-A2: assigns the first free ACTIVE machine operator in list order; nil when none is free (vanilla helper).
function RPSimWorkforce.assign(state, jobId)
    if jobId == nil or state.roster == nil then
        return nil
    end
    if state.assignments[jobId] ~= nil then
        return state.assignments[jobId]
    end
    local busy = {}
    for _, id in pairs(state.assignments) do
        busy[id] = true
    end
    for _, e in ipairs(state.roster.employees) do
        if e.role == RPSimWorkforce.DRIVER_ROLE and e.status == "ACTIVE" and not busy[e.employeeId] then
            state.assignments[jobId] = e.employeeId
            return e.employeeId
        end
    end
    return nil
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

--- R2-A3: helper limit in the strict mode = min(original, active machine operators); otherwise the original value.
function RPSimWorkforce.helperLimit(state, original)
    if state.roster == nil or not state.roster.strictHelperLimit then
        return original
    end
    local n = 0
    for _, e in ipairs(state.roster.employees) do
        if e.role == RPSimWorkforce.DRIVER_ROLE and e.status == "ACTIVE" then
            n = n + 1
        end
    end
    return math.min(original, n)
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
