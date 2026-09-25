local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestRobustness = {}

function T.TestRobustness:testTruncatedInstructionsFileIsSkippedAndRetried()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.logs = {}
    fs.files[paths.instructions] = '{"savegameId":"map_erlengrund_1_20260101","instructions":[{"instructionId":"a","ty'
    lu.assertNil(bridge:pollInstructions())
    lu.assertEquals(helpers.countLogs("warning", "Skipping unreadable"), 1)
    helpers.writeInstructions(fs, paths, { savegameId = "map_erlengrund_1_20260101", instructions = {
        { instructionId = "a", type = "MONEY_TRANSACTION", amount = 1, reason = "OTHER" } } })
    lu.assertEquals(bridge:pollInstructions().applied, 1)
    lu.assertEquals(#adapter.moneyLog, 1)
end

function T.TestRobustness:testNonObjectDocumentIsSkipped()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:bootstrap()
    fs.files[paths.instructions] = "[1,2,3]"
    lu.assertNil(bridge:pollInstructions())
end

function T.TestRobustness:testMissingInstructionsFileIsFine()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:bootstrap()
    lu.assertNil(bridge:pollInstructions())
    lu.assertNotNil(fs.files[paths.instructionsAck])
end

function T.TestRobustness:testFirstStartCreatesBridgeFolders()
    local bridge, fs, _, paths = helpers.newBridge()
    lu.assertNil(fs.dirs[paths.exportDir])
    bridge:onSavegameLoaded()
    lu.assertTrue(fs.dirs[paths.base])
    lu.assertTrue(fs.dirs[paths.exportDir])
    lu.assertTrue(fs.dirs[paths.importDir])
    lu.assertNotNil(fs.files[paths.farmFacts])
end

function T.TestRobustness:testFolderDeletedWhileRunningIsRecreated()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:bootstrap()
    fs.dirs[paths.exportDir] = nil
    lu.assertFalse(bridge:exportFarmFacts())
    lu.assertTrue(fs.dirs[paths.exportDir])
    lu.assertTrue(bridge:exportFarmFacts())
end

function T.TestRobustness:testAdapterErrorDoesNotCrash()
    local bridge, fs, _, paths = helpers.newBridge({ adapter = { failFacts = true } })
    bridge:bootstrap()
    lu.assertFalse(bridge:exportFarmFacts())
    lu.assertNil(fs.files[paths.farmFacts])
end

function T.TestRobustness:testFileReadErrorsNeverRaise()
    helpers.fakeFs()
    RPSimFileIO.backend.open = function() error("sandbox says no") end
    local content, err = RPSimFileIO.read("/x")
    lu.assertNil(content)
    lu.assertStrContains(err, "sandbox says no")
    local ok = RPSimFileIO.writeAtomic("/x", "y", "auto")
    lu.assertFalse(ok)
end

return T
