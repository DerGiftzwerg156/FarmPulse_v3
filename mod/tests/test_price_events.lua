local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestPriceEvents = {}

local H = RPSimConfig.MS_PER_GAME_HOUR
local SG = "map_erlengrund_1_20260101"

local function multiplierEvent(start)
    return { gameTimeStart = start or 0, peakMultiplier = 1.18, rampUpHours = 24, holdHours = 120, decayHours = 96 }
end

function T.TestPriceEvents:testRampBeforeStart()
    lu.assertEquals(RPSimPriceEventMath.multiplierAt(multiplierEvent(10 * H), 0), 1.0)
end

function T.TestPriceEvents:testRampUpIsLinear()
    local ev = multiplierEvent(0)
    lu.assertEquals(RPSimPriceEventMath.multiplierAt(ev, 0), 1.0)
    lu.assertAlmostEquals(RPSimPriceEventMath.multiplierAt(ev, 12 * H), 1.09, 1e-9)
end

function T.TestPriceEvents:testHold()
    local ev = multiplierEvent(0)
    lu.assertAlmostEquals(RPSimPriceEventMath.multiplierAt(ev, 24 * H), 1.18, 1e-9)
    lu.assertAlmostEquals(RPSimPriceEventMath.multiplierAt(ev, 143 * H), 1.18, 1e-9)
end

function T.TestPriceEvents:testDecay()
    local ev = multiplierEvent(0)
    lu.assertAlmostEquals(RPSimPriceEventMath.multiplierAt(ev, (144 + 48) * H), 1.09, 1e-9)
end

function T.TestPriceEvents:testAfterEnd()
    local ev = multiplierEvent(0)
    lu.assertEquals(RPSimPriceEventMath.multiplierAt(ev, 240 * H), 1.0)
    lu.assertTrue(RPSimPriceEventMath.isExpired(ev, 240 * H))
    lu.assertFalse(RPSimPriceEventMath.isExpired(ev, 239 * H))
end

function T.TestPriceEvents:testZeroRampIsImmediatePeak()
    local ev = { gameTimeStart = 0, peakMultiplier = 0.8, rampUpHours = 0, holdHours = 10, decayHours = 0 }
    lu.assertAlmostEquals(RPSimPriceEventMath.multiplierAt(ev, 0), 0.8, 1e-9)
    lu.assertEquals(RPSimPriceEventMath.multiplierAt(ev, 10 * H), 1.0)
end

local function registryWith(events)
    local reg = RPSimPriceEvents.new(RPSimConfig.new())
    for _, e in ipairs(events) do reg:add(e) end
    return reg
end

function T.TestPriceEvents:testMultiplierAppliesOnlyToItsSellPointAndFillType()
    local reg = registryWith({ { id = "m", priceMode = "MULTIPLIER", fillType = "WHEAT", sellPoint = "MillNorth",
        gameTimeStart = 0, peakMultiplier = 1.5, rampUpHours = 0, holdHours = 10, decayHours = 0 } })
    lu.assertAlmostEquals(reg:effectivePrice("MillNorth", "WHEAT", 0.2, H), 0.3, 1e-9)
    lu.assertAlmostEquals(reg:effectivePrice("MillSouth", "WHEAT", 0.2, H), 0.2, 1e-9)
    lu.assertAlmostEquals(reg:effectivePrice("MillNorth", "BARLEY", 0.2, H), 0.2, 1e-9)
end

function T.TestPriceEvents:testFixedTakesPrecedenceOverMultiplier()
    local reg = registryWith({
        { id = "m", priceMode = "MULTIPLIER", fillType = "WHEAT", sellPoint = "MillNorth", gameTimeStart = 0,
            peakMultiplier = 1.5, rampUpHours = 0, holdHours = 100, decayHours = 0 },
        { id = "f", priceMode = "FIXED", fillType = "WHEAT", sellPoint = "MillNorth", gameTimeStart = 0,
            fixedPrice = 250, maxQuantity = 10000, deadlineGameTime = 50 * H, deliveredQuantity = 0 },
    })
    lu.assertAlmostEquals(reg:effectivePrice("MillNorth", "WHEAT", 0.2, H), 0.25, 1e-9)
    -- after the deadline the multiplier is effective again
    lu.assertAlmostEquals(reg:effectivePrice("MillNorth", "WHEAT", 0.2, 60 * H), 0.3, 1e-9)
end

function T.TestPriceEvents:testFixedContractQuantityTrackingAndMaxReached()
    local reg = registryWith({ { id = "ins_0298", priceMode = "FIXED", fillType = "WHEAT", sellPoint = "MillNorth",
        gameTimeStart = 0, fixedPrice = 250, maxQuantity = 10000, deadlineGameTime = 50 * H, deliveredQuantity = 0 } })
    lu.assertEquals(reg:recordSale("MillNorth", "WHEAT", 8200, H), 8200)
    lu.assertEquals(reg:recordSale("MillSouth", "WHEAT", 500, H), 0)
    lu.assertEquals(reg:recordSale("MillNorth", "WHEAT", 5000, H), 1800)
    lu.assertAlmostEquals(reg:effectivePrice("MillNorth", "WHEAT", 0.2, H), 0.2, 1e-9)
    local reports = reg:collectEnded(H)
    lu.assertEquals(reports[1].endReason, "MAX_QUANTITY_REACHED")
    lu.assertEquals(reports[1].deliveredQuantity, 10000)
end

function T.TestPriceEvents:testFixedContractDeadlineReportedInAck()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { { instructionId = "ins_0298",
        type = "PRICE_EVENT", priceMode = "FIXED", fillType = "WHEAT", sellPoint = "MillNorth", fixedPrice = 250,
        maxQuantity = 10000, deadlineGameTime = 20 * H } } })
    bridge:pollInstructions()
    bridge:recordSale("MillNorth", "WHEAT", 8200)
    adapter.gameTime = 20 * H
    bridge:pollInstructions()
    local ack = RPSimJson.decode(fs.files[paths.instructionsAck])
    lu.assertEquals(ack.contractReports[1], { instructionId = "ins_0298", deliveredQuantity = 8200,
        maxQuantity = 10000, endReason = "DEADLINE_REACHED" })
end

function T.TestPriceEvents:testMultiplierInstructionStartsAtGameTimeEarliest()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    adapter.gameTime = 48213000
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { { instructionId = "ins_0232",
        type = "PRICE_EVENT", priceMode = "MULTIPLIER", fillType = "WHEAT", sellPoint = "MillNorth",
        gameTimeEarliest = 48213000, peakMultiplier = 1.18, rampUpHours = 24, holdHours = 120, decayHours = 96 } } })
    bridge:pollInstructions()
    local ev = bridge.state.priceEvents.events[1]
    lu.assertEquals(ev.gameTimeStart, 48213000)
    adapter.gameTime = 48213000 + 24 * H
    lu.assertAlmostEquals(bridge:effectivePrice("MillNorth", "WHEAT", 0.2), 0.236, 1e-9)
    adapter.gameTime = 48213000 + 240 * H
    bridge:pollInstructions()
    lu.assertEquals(#bridge.state.priceEvents.events, 0)
end

return T
