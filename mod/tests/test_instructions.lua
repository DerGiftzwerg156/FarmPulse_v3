local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestInstructions = {}

local SG = "map_erlengrund_1_20260101"

local function money(id, amount, reason)
    return { instructionId = id, type = "MONEY_TRANSACTION", amount = amount, reason = reason or "SALARY_PAYMENT",
        note = "Gehalt Klaus, Mai" }
end

function T.TestInstructions:testAllMoneyReasonsAccepted()
    for _, r in ipairs({ "CREDIT_DISBURSEMENT", "CREDIT_INSTALLMENT", "CREDIT_PENALTY", "CREDIT_CALLBACK",
        "CREDIT_SPECIAL_REPAYMENT", "CREDIT_PREPAYMENT_FEE",
        "SALARY_PAYMENT", "EMPLOYEE_EFFECT", "SUBSIDY", "STARTING_CAPITAL_ADJUSTMENT", "FARMLAND_PURCHASE",
        "FARMLAND_SALE", "OTHER", "TAX_PAYMENT", "TAX_REFUND", "FINE", "FAMILY", "SPONSORING", "COMPENSATION" }) do
        lu.assertTrue(RPSimInstructions.validate(money("i", 1, r)), r)
    end
    local ok, why = RPSimInstructions.validate(money("i", 1, "FREE_MONEY"))
    lu.assertFalse(ok)
    lu.assertStrContains(why, "unknown reason")
end

function T.TestInstructions:testValidationRejectsBrokenEnvelopes()
    lu.assertFalse(RPSimInstructions.validate({ type = "MONEY_TRANSACTION", amount = 1, reason = "OTHER" }))
    lu.assertFalse(RPSimInstructions.validate({ instructionId = "x", type = "NOPE" }))
    lu.assertFalse(RPSimInstructions.validate({ instructionId = "x", type = "MONEY_TRANSACTION",
        amount = "1", reason = "OTHER" }))
    lu.assertFalse(RPSimInstructions.validate({ instructionId = "x", type = "PRICE_EVENT", priceMode = "MULTIPLIER",
        fillType = "WHEAT", sellPoint = "MillNorth", peakMultiplier = 1.18, rampUpHours = -1, holdHours = 1, decayHours = 1 }))
    lu.assertFalse(RPSimInstructions.validate({ instructionId = "x", type = "PRICE_EVENT", priceMode = "FIXED",
        fillType = "WHEAT", sellPoint = "MillNorth", fixedPrice = 250, maxQuantity = 0, deadlineGameTime = 5 }))
    lu.assertFalse(RPSimInstructions.validate("not a table"))
end

function T.TestInstructions:testParseDocument()
    local doc = RPSimInstructions.parseDocument('{"savegameId":"a","instructions":[{"instructionId":"ins_1"}]}')
    lu.assertEquals(doc.savegameId, "a")
    lu.assertEquals(#doc.instructions, 1)
    local empty = RPSimInstructions.parseDocument('{"savegameId":"a"}')
    lu.assertEquals(#empty.instructions, 0)
    lu.assertNil(RPSimInstructions.parseDocument('{"savegameId":"a","instructions":5}'))
end

function T.TestInstructions:testMoneyIsAppliedAndAcked()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { money("ins_0231", -1800) } })
    local res = bridge:pollInstructions()
    lu.assertEquals(res.applied, 1)
    lu.assertEquals(adapter.balance, 245000 - 1800)
    local ack = RPSimJson.decode(fs.files[paths.instructionsAck])
    lu.assertEquals(ack.savegameId, SG)
    lu.assertEquals(ack.acks[1].instructionId, "ins_0231")
    lu.assertEquals(ack.acks[1].status, "APPLIED")
    lu.assertEquals(ack.acks[1].appliedAtGameTime, 1000)
end

function T.TestInstructions:testSameInstructionTwiceIsAppliedOnce()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    local doc = { savegameId = SG, instructions = { money("ins_0231", -1800) } }
    helpers.writeInstructions(fs, paths, doc)
    bridge:pollInstructions()
    adapter.gameTime = 5000
    helpers.writeInstructions(fs, paths, doc)
    local res = bridge:pollInstructions()
    lu.assertEquals(res.applied, 0)
    lu.assertEquals(res.duplicates, 1)
    lu.assertEquals(#adapter.moneyLog, 1)
    lu.assertEquals(adapter.balance, 245000 - 1800)
end

function T.TestInstructions:testDuplicateInsideOneDocumentAppliedOnce()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { money("dup", 10), money("dup", 10) } })
    bridge:pollInstructions()
    lu.assertEquals(#adapter.moneyLog, 1)
end

function T.TestInstructions:testGameTimeEarliestDefersInstruction()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    local ins = money("later", 500)
    ins.gameTimeEarliest = 10000
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { ins } })
    local res = bridge:pollInstructions()
    lu.assertEquals(res.deferred, 1)
    lu.assertEquals(#adapter.moneyLog, 0)
    adapter.gameTime = 10000
    res = bridge:pollInstructions()
    lu.assertEquals(res.applied, 1)
end

function T.TestInstructions:testInvalidInstructionIsRejectedAndAcked()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { money("bad", 5, "HACK") } })
    local res = bridge:pollInstructions()
    lu.assertEquals(res.rejected, 1)
    lu.assertEquals(#adapter.moneyLog, 0)
    local ack = RPSimJson.decode(fs.files[paths.instructionsAck])
    lu.assertEquals(ack.acks[1].status, "REJECTED")
end

function T.TestInstructions:testFailedApplicationIsAckedAsFailed()
    local bridge, fs, adapter, paths = helpers.newBridge({ adapter = { failMoney = true } })
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { money("m", 5) } })
    bridge:pollInstructions()
    local ack = RPSimJson.decode(fs.files[paths.instructionsAck])
    lu.assertEquals(ack.acks[1].status, "FAILED")
    lu.assertEquals(adapter.balance, 245000)
end

function T.TestInstructions:testProcessedEntriesPrunedAfterRetention()
    local bridge, fs, adapter, paths = helpers.newBridge({ config = { processedRetentionGameDays = 30 } })
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { money("old", 1) } })
    bridge:pollInstructions()
    lu.assertNotNil(bridge.state.processed.old)
    fs.files[paths.instructions] = nil
    adapter.gameTime = 1000 + 30 * RPSimConfig.MS_PER_GAME_DAY
    bridge:pollInstructions()
    lu.assertNotNil(bridge.state.processed.old)
    adapter.gameTime = 1001 + 30 * RPSimConfig.MS_PER_GAME_DAY
    bridge:pollInstructions()
    lu.assertNil(bridge.state.processed.old)
end

-- TODO T-21: in-game notifications
function T.TestInstructions:testNotificationIsShownOnceAndAcked()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    local note = { instructionId = "ins_n1", type = "NOTIFICATION", text = "FarmPulse: Neue Mail von Frau Berger",
        level = "INFO", expiresAtGameTime = 5000 }
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { note } })
    lu.assertEquals(bridge:pollInstructions().applied, 1)
    bridge:pollInstructions()
    lu.assertEquals(#adapter.notifications, 1)
    lu.assertEquals(adapter.notifications[1].text, "FarmPulse: Neue Mail von Frau Berger")
    local ack = RPSimJson.decode(fs.files[paths.instructionsAck])
    lu.assertEquals(ack.acks[1].status, "APPLIED")
    lu.assertNil(ack.acks[1].message)
end

function T.TestInstructions:testExpiredNotificationIsNotShown()
    local bridge, fs, adapter, paths = helpers.newBridge({ adapter = { gameTime = 9000 } })
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "ins_n2", type = "NOTIFICATION", text = "alt", expiresAtGameTime = 5000 } } })
    lu.assertEquals(bridge:pollInstructions().applied, 1)
    lu.assertEquals(#adapter.notifications, 0)
    local ack = RPSimJson.decode(fs.files[paths.instructionsAck])
    lu.assertEquals(ack.acks[1].message, "EXPIRED")
end

function T.TestInstructions:testNotificationValidation()
    lu.assertFalse(RPSimInstructions.validate({ instructionId = "x", type = "NOTIFICATION", text = "" }))
    lu.assertFalse(RPSimInstructions.validate({ instructionId = "x", type = "NOTIFICATION", text = "a", level = "LOUD" }))
    lu.assertTrue(RPSimInstructions.validate({ instructionId = "x", type = "NOTIFICATION", text = "a", level = "OK" }))
end

-- TODO T-22: repairs of the maintenance contract
function T.TestInstructions:testRepairVehicleIsAppliedOrFailed()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "ins_r1", type = "REPAIR_VEHICLE", vehicleId = "veh_00042" },
        { instructionId = "ins_r2", type = "REPAIR_VEHICLE", vehicleId = "veh_gone" } } })
    local res = bridge:pollInstructions()
    lu.assertEquals(res.applied, 1)
    lu.assertEquals(adapter.repairs, { "veh_00042" })
    local acks = {}
    for _, a in ipairs(RPSimJson.decode(fs.files[paths.instructionsAck]).acks) do acks[a.instructionId] = a end
    lu.assertEquals(acks.ins_r2.status, "FAILED")
    lu.assertEquals(acks.ins_r2.message, "VEHICLE_NOT_FOUND")
    lu.assertFalse(RPSimInstructions.validate({ instructionId = "x", type = "REPAIR_VEHICLE" }))
end

-- Roadmap V2 R2-A6: targetDamage is handed to the adapter
function T.TestInstructions:testRepairVehiclePassesTheTargetDamage()
    local bridge, fs, adapter, paths = helpers.newBridge()
    local seen
    function adapter:repairVehicle(id, targetDamage)
        seen = { id, targetDamage }
        return true
    end
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        { instructionId = "ins_r3", type = "REPAIR_VEHICLE", vehicleId = "veh_00042", targetDamage = 0.3 } } })
    lu.assertEquals(bridge:pollInstructions().applied, 1)
    lu.assertEquals(seen, { "veh_00042", 0.3 })
end

-- Roadmap V2 R2-A6: partial repair
function T.TestInstructions:testRepairVehicleTargetDamageIsValidated()
    local function repair(target)
        return RPSimInstructions.validate({ instructionId = "x", type = "REPAIR_VEHICLE", vehicleId = "veh_1",
            targetDamage = target })
    end
    lu.assertTrue(repair(nil))
    lu.assertTrue(repair(0))
    lu.assertTrue(repair(0.35))
    lu.assertTrue(repair(1))
    lu.assertFalse(repair(-0.1))
    lu.assertFalse(repair(1.2))
    lu.assertFalse(repair("0.5"))
end

local function roster(employees, extra)
    local ins = { instructionId = "ins_ro", type = "EMPLOYEE_ROSTER", employees = employees,
        helperWageMode = "EMPLOYEES", strictHelperLimit = false }
    for k, v in pairs(extra or {}) do ins[k] = v end
    return ins
end

-- Roadmap V2 R2-A0
function T.TestInstructions:testEmployeeRosterValidation()
    local klaus = { employeeId = 12, name = "Klaus Berger", role = "MACHINE_OPERATOR", status = "ACTIVE" }
    lu.assertTrue(RPSimInstructions.validate(roster({ klaus })))
    lu.assertTrue(RPSimInstructions.validate(roster(RPSimJson.array({}))))
    for _, status in ipairs({ "ON_LEAVE", "STRIKE" }) do
        lu.assertTrue(RPSimInstructions.validate(roster({ { employeeId = 1, name = "A", role = "MECHANIC",
            status = status } })), status)
    end
    -- "Schulungen": optional trainings per employee and categories per training
    lu.assertTrue(RPSimInstructions.validate(roster({ { employeeId = 1, name = "A", role = "MACHINE_OPERATOR",
        status = "ACTIVE", trainings = { "COMBINE" } } }, { trainingCategories = { COMBINE = { "HARVESTERS" } } })))
    lu.assertTrue(RPSimInstructions.validate(roster({ { employeeId = 1, name = "A", role = "MACHINE_OPERATOR",
        status = "ACTIVE", trainings = RPSimJson.array({}) } }, { trainingCategories = {} })))
    local cases = {
        roster({ { employeeId = 1, name = "A", role = "MACHINE_OPERATOR", status = "ACTIVE", trainings = "COMBINE" } }),
        roster({ klaus }, { trainingCategories = "COMBINE" }),
        roster({ klaus }, { trainingCategories = { COMBINE = "HARVESTERS" } }),
        roster(nil),
        roster({ { employeeId = "12", name = "A", role = "MECHANIC", status = "ACTIVE" } }),
        roster({ { employeeId = 12, name = "", role = "MECHANIC", status = "ACTIVE" } }),
        roster({ { employeeId = 12, name = "A", role = "MECHANIC", status = "SICK" } }),
        roster({ klaus }, { helperWageMode = "FREE" }),
        roster({ klaus }, { strictHelperLimit = "yes" }),
    }
    for i, ins in ipairs(cases) do
        lu.assertFalse(RPSimInstructions.validate(ins), "case " .. i)
    end
end

-- Roadmap V2 R2-F2
function T.TestInstructions:testPromptValidation()
    local function prompt(extra)
        local ins = { instructionId = "ins_p", type = "PROMPT", promptId = "prm_1", title = "Anruf",
            text = "Greta Lindner ruft an. Annehmen?", expiresGameTime = 5000 }
        for k, v in pairs(extra or {}) do ins[k] = v end
        return ins
    end
    lu.assertTrue(RPSimInstructions.validate(prompt()))
    lu.assertTrue(RPSimInstructions.validate(prompt({ yesLabel = "Annehmen", noLabel = "Ablehnen" })))
    lu.assertFalse(RPSimInstructions.validate(prompt({ promptId = "" })))
    lu.assertFalse(RPSimInstructions.validate(prompt({ text = "" })))
    lu.assertFalse(RPSimInstructions.validate(prompt({ yesLabel = "" })))
    lu.assertFalse(RPSimInstructions.validate(prompt({ expiresGameTime = "morgen" })))
    local noExpiry = prompt()
    noExpiry.expiresGameTime = nil
    lu.assertFalse(RPSimInstructions.validate(noExpiry))
end

-- R2-A0 / R2-F2: EMPLOYEE_ROSTER replaces the list, PROMPT is queued for the dialog (both APPLIED, never twice).
function T.TestInstructions:testPromptIsQueuedAndTheRosterIsApplied()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {
        roster({ { employeeId = 12, name = "Klaus Berger", role = "MACHINE_OPERATOR", status = "ACTIVE" } }),
        { instructionId = "ins_p", type = "PROMPT", promptId = "prm_1", title = "Anruf", text = "Annehmen?",
            expiresGameTime = 5000 } } })
    local res = bridge:pollInstructions()
    lu.assertEquals(res.applied, 2)
    local acks = {}
    for _, a in ipairs(RPSimJson.decode(fs.files[paths.instructionsAck]).acks) do acks[a.instructionId] = a end
    lu.assertEquals(acks.ins_ro.status, "APPLIED")
    lu.assertEquals(bridge.state.workforce.roster.employees[1].name, "Klaus Berger")
    lu.assertEquals(acks.ins_p.status, "APPLIED")
    lu.assertEquals(bridge.state.prompts.queue[1].promptId, "prm_1")
end

function T.TestInstructions:testNewTypesUseTheirActionOnceAvailable()
    local calls = {}
    local state = RPSimProcessor.newState(RPSimConfig.new())
    local res = RPSimProcessor.process(state, { savegameId = SG, instructions = {
        roster({}),
        { instructionId = "ins_p", type = "PROMPT", promptId = "prm_1", title = "Anruf", text = "Annehmen?",
            expiresGameTime = 5000 } } },
        { savegameId = SG, gameTime = 1000, actions = {
            employeeRoster = function(ins) calls[#calls + 1] = ins.type; return true end,
            prompt = function(ins) calls[#calls + 1] = ins.promptId; return true end } })
    lu.assertEquals(res.applied, 2)
    lu.assertEquals(calls, { "EMPLOYEE_ROSTER", "prm_1" })
    -- a missing roster action never falls back to the prompt action (and vice versa)
    state = RPSimProcessor.newState(RPSimConfig.new())
    calls = {}
    res = RPSimProcessor.process(state, { savegameId = SG, instructions = { roster({}) } },
        { savegameId = SG, gameTime = 1000, actions = {
            prompt = function(ins) calls[#calls + 1] = ins.type; return true end } })
    lu.assertEquals(res.applied, 0)
    lu.assertEquals(calls, {})
    lu.assertEquals(state.processed.ins_ro.message, "NOT_SUPPORTED")
end

return T
