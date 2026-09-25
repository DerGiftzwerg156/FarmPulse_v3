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
        "SALARY_PAYMENT", "EMPLOYEE_EFFECT", "SUBSIDY", "STARTING_CAPITAL_ADJUSTMENT", "FARMLAND_PURCHASE",
        "FARMLAND_SALE", "OTHER" }) do
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

return T
