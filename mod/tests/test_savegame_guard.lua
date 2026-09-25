local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestSavegameGuard = {}

local SG = "map_erlengrund_1_20260101"
local function money(id)
    return { instructionId = id, type = "MONEY_TRANSACTION", amount = 100, reason = "OTHER" }
end

function T.TestSavegameGuard:testMismatchingDocumentIsDiscardedAndLogged()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.logs = {}
    helpers.writeInstructions(fs, paths, { savegameId = "other_save", instructions = { money("a") } })
    local res = bridge:pollInstructions()
    lu.assertTrue(res.discarded)
    lu.assertEquals(#adapter.moneyLog, 0)
    lu.assertEquals(helpers.countLogs("warning", "does not match"), 1)
    lu.assertNil(bridge.state.processed.a)
end

function T.TestSavegameGuard:testMatchingDocumentIsApplied()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { money("a") } })
    local res = bridge:pollInstructions()
    lu.assertFalse(res.discarded)
    lu.assertEquals(#adapter.moneyLog, 1)
end

function T.TestSavegameGuard:testMismatchingInstructionLevelIdIsRejected()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    local ins = money("a")
    ins.savegameId = "other"
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { ins } })
    bridge:pollInstructions()
    lu.assertEquals(#adapter.moneyLog, 0)
    lu.assertEquals(bridge.state.processed.a.status, "REJECTED")
end

function T.TestSavegameGuard:testEveryBridgeFileCarriesSavegameId()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:onSavegameLoaded()
    for _, p in ipairs({ paths.farmFacts, paths.marketContext, paths.instructionsAck }) do
        lu.assertEquals(RPSimJson.decode(fs.files[p]).savegameId, SG, p)
    end
end

function T.TestSavegameGuard:testFreshExportImmediatelyOnLoad()
    local bridge, fs, _, paths = helpers.newBridge({ config = { exportIntervalMs = 999999 } })
    lu.assertNil(fs.files[paths.farmFacts])
    bridge:onSavegameLoaded()
    lu.assertNotNil(fs.files[paths.farmFacts])
end

function T.TestSavegameGuard:testGenerateSavegameId()
    lu.assertEquals(RPSimBridge.generateSavegameId("Erlengrund", 3, "20260101"), "map_erlengrund_3_20260101")
    lu.assertEquals(RPSimBridge.generateSavegameId("Riverbend Springs!", 1, "x"), "map_riverbend_springs_1_x")
end

return T
