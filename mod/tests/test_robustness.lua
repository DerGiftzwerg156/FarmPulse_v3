local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestRobustness = {}

function T.TestRobustness:testTruncatedInstructionsFileIsSkippedAndRetried()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.logs = {}
    fs.files[paths.instructions] = helpers.xmlPayload(
        '{"savegameId":"map_erlengrund_1_20260101","instructions":[{"instructionId":"a","ty')
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
    fs.files[paths.instructions] = helpers.xmlPayload("[1,2,3]")
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
    lu.assertFalse(RPSimFileIO.write("/x", "y", "auto"))
    lu.assertFalse(RPSimFileIO.write("/x", "y", "direct"))
end

-- FS25 log: "io.open, only write mode ('w') is allowed" + "attempt to call missing method 'read' of table"
-- in FileIO.lua, which aborted loadMap and left the savegame loading screen hanging.
function T.TestRobustness:testFs25WriteOnlyStandInForReadModeDoesNotRaise()
    helpers.fakeFs()
    local closed = false
    RPSimFileIO.backend.open = function() return { close = function() closed = true end } end
    local content, err = RPSimFileIO.read("/x")
    lu.assertNil(content)
    lu.assertEquals(err, "read mode not supported")
    lu.assertTrue(closed)
end

function T.TestRobustness:testPayloadReadErrorsNeverRaise()
    helpers.fakeFs()
    RPSimFileIO.backend.readXmlText = function() error("xml says no") end
    local content, err = RPSimFileIO.readPayload("/x.xml")
    lu.assertNil(content)
    lu.assertStrContains(err, "xml says no")
end

function T.TestRobustness:testPollNeverUsesPlainReadMode()
    local bridge, fs = helpers.newBridge()
    bridge:bootstrap()
    local open = fs.backend.open
    fs.backend.open = function(path, mode)
        lu.assertNotEquals(mode, "r", "FS25 blocks io.open in read mode: " .. path)
        return open(path, mode)
    end
    bridge:onSavegameLoaded()
    bridge:pollInstructions()
end

function T.TestRobustness:testDefaultBackendReadsPayloadWithTheEngineXmlApi()
    local saved = RPSimFileIO
    dofile((os.getenv("RPSIM_SRC") or "FS25_RPSim/src/") .. "util/FileIO.lua")
    local deleted = 0
    XMLFile = { loadIfExists = function(_, path)
        if path ~= "/ms/import/instructions.xml" then
            return nil
        end
        return {
            getString = function(_, key) return key == "rpsim.json" and '{"a":1}' or nil end,
            delete = function() deleted = deleted + 1 end,
        }
    end }
    lu.assertEquals(RPSimFileIO.readPayload("/ms/import/instructions.xml"), '{"a":1}')
    lu.assertEquals(deleted, 1)
    local text, err = RPSimFileIO.readPayload("/ms/missing.xml")
    lu.assertNil(text)
    lu.assertEquals(err, "no such file")
    XMLFile = nil
    RPSimFileIO = saved
end

return T
