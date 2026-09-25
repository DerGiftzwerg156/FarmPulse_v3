local lu = require("luaunit")
local T = {}
T.TestPersistence = {}

local function fakeXml()
    local x = { data = {} }
    function x:setString(k, v) self.data[k] = v end
    function x:setInt(k, v) self.data[k] = v end
    function x:setFloat(k, v) self.data[k] = v end
    function x:getString(k) local v = self.data[k]; return v ~= nil and tostring(v) or nil end
    function x:getInt(k) return self.data[k] end
    function x:getFloat(k) return self.data[k] end
    return x
end

function T.TestPersistence:testRoundTrip()
    local state = RPSimProcessor.newState(RPSimConfig.new())
    state.savegameId = "sg1"
    state.processed.ins_0231 = { gameTime = 48214000, status = "APPLIED" }
    state.processed.ins_bad = { gameTime = 5, status = "REJECTED", message = "why" }
    state.priceEvents:add({ id = "ins_0232", priceMode = "MULTIPLIER", fillType = "WHEAT", sellPoint = "MillNorth",
        gameTimeStart = 48213000, peakMultiplier = 1.18, rampUpHours = 24, holdHours = 120, decayHours = 96 })
    state.priceEvents:add({ id = "ins_0298", priceMode = "FIXED", fillType = "WHEAT", sellPoint = "MillNorth",
        gameTimeStart = 1, fixedPrice = 250, maxQuantity = 10000, deadlineGameTime = 99, deliveredQuantity = 8200 })
    state.contractReports[1] = { instructionId = "old", deliveredQuantity = 1, maxQuantity = 2,
        endReason = "DEADLINE_REACHED", endedAtGameTime = 3 }
    local xml = fakeXml()
    RPSimPersistence.save(xml, state)
    lu.assertEquals(xml.data["FS25_RPSim.processedInstructions.entry(0)#id"], "ins_0231")

    local loaded = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load(xml, loaded)
    lu.assertEquals(loaded.savegameId, "sg1")
    lu.assertEquals(loaded.processed.ins_0231, { gameTime = 48214000, status = "APPLIED" })
    lu.assertEquals(loaded.processed.ins_bad.message, "why")
    lu.assertEquals(#loaded.priceEvents.events, 2)
    lu.assertEquals(loaded.priceEvents.events[1].peakMultiplier, 1.18)
    lu.assertEquals(loaded.priceEvents.events[2].deliveredQuantity, 8200)
    lu.assertEquals(loaded.contractReports[1].endReason, "DEADLINE_REACHED")
end

function T.TestPersistence:testEmptyXmlLeavesStateEmpty()
    local loaded = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load(fakeXml(), loaded)
    lu.assertNil(loaded.savegameId)
    lu.assertEquals(#loaded.priceEvents.events, 0)
end

return T
