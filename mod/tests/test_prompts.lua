-- Roadmap V2 R2-F1 / R2-F2 / R2-F3: yes/no questions in the game and the answers back to the backend.
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestPrompts = {}

local SG = "map_erlengrund_1_20260101"

local function prompt(id, over)
    local ins = { instructionId = "ins_" .. id, type = "PROMPT", promptId = id, title = "Anruf",
        text = "Hanna Vogt ruft an. Gespräch annehmen?", yesLabel = "Annehmen", noLabel = "Ablehnen",
        expiresGameTime = 50000 }
    for k, v in pairs(over or {}) do ins[k] = v end
    return ins
end

--- Bridge with a fake dialog: shown = list of { text, title, callback }.
local function setup(adapterOverrides)
    local bridge, fs, adapter, paths = helpers.newBridge({ adapter = adapterOverrides })
    adapter.canShow = true
    adapter.inVehicle = false
    adapter.dialogs = {}
    function adapter:canShowPrompt(inVehicleAllowed)
        return self.canShow and (inVehicleAllowed or not self.inVehicle)
    end
    function adapter:showYesNo(text, title, callback)
        self.dialogs[#self.dialogs + 1] = { text = text, title = title, callback = callback }
        return true
    end
    bridge:onSavegameLoaded()
    return bridge, fs, adapter, paths
end

local function responses(fs, paths)
    return RPSimJson.decode(fs.files[paths.playerResponses])
end

function T.TestPrompts:testQuestionIsShownAndTheAnswerIsWrittenAtOnce()
    local bridge, fs, adapter, paths = setup()
    lu.assertEquals(responses(fs, paths).responses, {})
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { prompt("prm_1") } })
    bridge:pollInstructions()
    bridge:update(16)
    lu.assertEquals(#adapter.dialogs, 1)
    lu.assertEquals(adapter.dialogs[1].title, "Anruf")
    -- owner decision: the game's yes / no buttons, their meaning in the text
    lu.assertEquals(adapter.dialogs[1].text, "Hanna Vogt ruft an. Gespräch annehmen?\n\nJa = Annehmen · Nein = Ablehnen")
    bridge:update(16)
    lu.assertEquals(#adapter.dialogs, 1) -- one dialog at a time
    adapter.gameTime = 2000
    adapter.dialogs[1].callback(true)
    local doc = responses(fs, paths)
    lu.assertEquals(doc.savegameId, SG)
    lu.assertEquals(doc.responses, { { responseId = "rsp_prm_1", promptId = "prm_1", answer = "YES", gameTime = 2000 } })
    lu.assertEquals(#bridge.state.prompts.queue, 0)
end

function T.TestPrompts:testTheBackendAcknowledgesAnswersAndWithdrawsQuestions()
    local bridge, fs, adapter, paths = setup()
    adapter.canShow = false
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { prompt("prm_1"), prompt("prm_2") } })
    bridge:pollInstructions()
    adapter.canShow = true
    bridge:update(16)
    adapter.dialogs[1].callback(false)
    lu.assertEquals(responses(fs, paths).responses[1].answer, "NO")
    -- processed answer acknowledged, prm_2 decided in the browser meanwhile
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {}, ackedResponses = { "rsp_prm_1" },
        withdrawnPrompts = { "prm_2" } })
    bridge:pollInstructions()
    lu.assertEquals(responses(fs, paths).responses, {})
    bridge:update(16)
    lu.assertEquals(#adapter.dialogs, 1)
    -- the same PROMPT again (e.g. resent after a rewind) is not queued a second time
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { prompt("prm_2", { instructionId = "x2" }) } })
    bridge:pollInstructions()
    lu.assertEquals(#bridge.state.prompts.queue, 0)
end

function T.TestPrompts:testAnOpenDialogStaysWhenItIsWithdrawn()
    local bridge, fs, adapter, paths = setup()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { prompt("prm_1") } })
    bridge:pollInstructions()
    bridge:update(16)
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = {}, withdrawnPrompts = { "prm_1" } })
    bridge:pollInstructions()
    adapter.dialogs[1].callback(true)
    -- the answer goes out anyway; the backend ignores it
    lu.assertEquals(responses(fs, paths).responses[1].promptId, "prm_1")
end

function T.TestPrompts:testExpiredQuestionsAreDroppedWithoutBeingShown()
    local bridge, fs, adapter, paths = setup()
    adapter.gameTime = 60000
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { prompt("prm_old") } })
    bridge:pollInstructions()
    local ack = RPSimJson.decode(fs.files[paths.instructionsAck]).acks[1]
    lu.assertEquals(ack.status, "APPLIED")
    lu.assertEquals(ack.message, "EXPIRED")
    -- queued in time, expired while a menu was open
    adapter.gameTime = 1000
    adapter.canShow = false
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { prompt("prm_2") } })
    bridge:pollInstructions()
    adapter.gameTime = 60000
    adapter.canShow = true
    bridge:update(16)
    lu.assertEquals(#adapter.dialogs, 0)
    lu.assertEquals(#bridge.state.prompts.queue, 0)
end

function T.TestPrompts:testInAVehicleOnlyWhenAllowedTheKeyOpensItAnywhere()
    local bridge, fs, adapter, paths = setup()
    bridge.cfg.promptsInVehicle = false
    bridge.promptKeyAvailable = true
    adapter.inVehicle = true
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { prompt("prm_1") } })
    bridge:pollInstructions()
    -- the question waits; a notification names the key (R2-F3)
    lu.assertStrContains(adapter.notifications[#adapter.notifications].text, "FarmPulse: offene Frage")
    bridge:update(16)
    lu.assertEquals(#adapter.dialogs, 0)
    lu.assertTrue(bridge:openNextPrompt())
    lu.assertEquals(#adapter.dialogs, 1)
    -- a menu blocks the key as well
    adapter.dialogs[1].callback(true)
    adapter.canShow = false
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { prompt("prm_2") } })
    bridge:pollInstructions()
    lu.assertFalse(bridge:openNextPrompt())
end

function T.TestPrompts:testWithoutDialogTheQuestionsStayInTheBrowser()
    local bridge, fs, adapter, paths = setup()
    function adapter:showYesNo() return false, "YesNoDialog missing" end
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { prompt("prm_1") } })
    bridge:pollInstructions()
    bridge:update(16)
    bridge:update(16)
    lu.assertEquals(helpers.countLogs("warning", "Yes/no dialog not available"), 1)
    lu.assertTrue(bridge.promptsUnsupported)
end

function T.TestPrompts:testQueueAndAnswersSurviveSavingButNotAReloadWithoutSaving()
    local bridge, fs, adapter, paths = setup()
    adapter.canShow = false
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { prompt("prm_1"), prompt("prm_2") } })
    bridge:pollInstructions()
    adapter.canShow = true
    bridge:update(16)
    adapter.dialogs[1].callback(true)
    local xml = { data = {} }
    function xml:setString(k, v) self.data[k] = v end
    function xml:setInt(k, v) self.data[k] = v end
    function xml:setFloat(k, v) self.data[k] = v end
    function xml:getString(k) local v = self.data[k]; return v ~= nil and tostring(v) or nil end
    function xml:getInt(k) return self.data[k] end
    function xml:getFloat(k) return self.data[k] end
    RPSimPersistence.save(xml, bridge.state)
    local loaded = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load(xml, loaded)
    lu.assertEquals(loaded.prompts.queue[1].promptId, "prm_2")
    lu.assertEquals(loaded.prompts.queue[1].yesLabel, "Annehmen")
    lu.assertEquals(loaded.prompts.responses[1].responseId, "rsp_prm_1")
    lu.assertEquals(loaded.prompts.handled.prm_1, 50000)
    -- reload of an older save (without the answer): the file follows the savegame, the answer is gone
    local older = RPSimProcessor.newState(RPSimConfig.new())
    older.savegameId = SG
    local b2 = RPSimBridge.new(bridge.cfg, paths, adapter, older)
    b2:onSavegameLoaded()
    lu.assertEquals(responses(fs, paths).responses, {})
end

return T
