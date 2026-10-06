-- Booking statement (owner decisions 2026-10-06): single bookings behind the monthly journal -> farm_facts.bookings.
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestBookingLog = {}

local SINGLE = { "SHOP_VEHICLE_BUY", "SHOP_VEHICLE_SELL" }

local function booking(day, category, amount, extra)
    local b = { gameTime = day * 86400000 + 1000, year = 2, period = 8, day = 1, monotonicDay = day,
        category = category, amount = amount }
    for k, v in pairs(extra or {}) do
        b[k] = v
    end
    return b
end

function T.TestBookingLog:testRunningBookingsAreSummedPerDayAndType()
    local log = RPSimBookingLog.new()
    RPSimBookingLog.record(log, booking(40, "AI", -10), { singleTypes = SINGLE })
    RPSimBookingLog.record(log, booking(40, "PURCHASE_FUEL", -300), { singleTypes = SINGLE })
    RPSimBookingLog.record(log, booking(40, "AI", -15), { singleTypes = SINGLE })
    RPSimBookingLog.record(log, booking(41, "AI", -5), { singleTypes = SINGLE })
    lu.assertEquals(#log.entries, 3)
    lu.assertEquals(log.entries[1].seq, 1)
    lu.assertEquals(log.entries[1].amount, -25)
    lu.assertEquals(log.entries[1].count, 2)
    lu.assertEquals(log.entries[3].seq, 3)
    lu.assertEquals(log.entries[3].monotonicDay, 41)
    lu.assertEquals(log.nextSeq, 4)
end

function T.TestBookingLog:testSalesAreSummedPerFillTypeAndSellPointWithLiters()
    local log = RPSimBookingLog.new()
    local sale = function(fillType, point, amount, liters)
        return booking(40, "SOLD_PRODUCTS", amount, { fillType = fillType, sellPoint = point, liters = liters })
    end
    RPSimBookingLog.record(log, sale("WHEAT", "MillNorth", 100, 450))
    RPSimBookingLog.record(log, sale("WHEAT", "MillNorth", 120, 550))
    RPSimBookingLog.record(log, sale("BARLEY", "MillNorth", 80, 400))
    RPSimBookingLog.record(log, sale("WHEAT", "Harbor", 50, 200))
    lu.assertEquals(#log.entries, 3)
    lu.assertEquals(log.entries[1].amount, 220)
    lu.assertEquals(log.entries[1].liters, 1000)
    lu.assertEquals(log.entries[1].count, 2)
    lu.assertEquals(log.entries[2].fillType, "BARLEY")
    lu.assertEquals(log.entries[3].sellPoint, "Harbor")
end

function T.TestBookingLog:testPurchasesAndToolBookingsAreSingleEntries()
    local log = RPSimBookingLog.new()
    RPSimBookingLog.record(log, booking(40, "SHOP_VEHICLE_BUY", -90000), { singleTypes = SINGLE })
    RPSimBookingLog.record(log, booking(40, "SHOP_VEHICLE_BUY", -12000), { singleTypes = SINGLE })
    RPSimBookingLog.record(log, booking(40, "RPSIM_SALARY_PAYMENT", -2400, { note = "Gehalt" }),
        { singleTypes = SINGLE })
    RPSimBookingLog.record(log, booking(40, "RPSIM_SALARY_PAYMENT", -2000, { note = "Gehalt" }),
        { singleTypes = SINGLE })
    lu.assertEquals(#log.entries, 4)
    lu.assertTrue(log.entries[1].single)
    lu.assertEquals(log.entries[3].note, "Gehalt")
    lu.assertTrue(log.entries[4].single)
end

function T.TestBookingLog:testOnlyTheLastEntriesAreKeptButSeqContinues()
    local log = RPSimBookingLog.new()
    for i = 1, 5 do
        RPSimBookingLog.record(log, booking(40, "SHOP_VEHICLE_BUY", -i), { maxEntries = 3, singleTypes = SINGLE })
    end
    lu.assertEquals(#log.entries, 3)
    lu.assertEquals(log.entries[1].seq, 3)
    lu.assertEquals(log.nextSeq, 6)
end

function T.TestBookingLog:testInvalidBookingsAreIgnored()
    local log = RPSimBookingLog.new()
    lu.assertNil(RPSimBookingLog.record(log, booking(40, "AI", 0)))
    lu.assertNil(RPSimBookingLog.record(log, booking(40, "", -5)))
    lu.assertNil(RPSimBookingLog.record(log, booking(40, "AI", 0 / 0)))
    lu.assertNil(RPSimBookingLog.record(log, { category = "AI", amount = -5, year = 2, period = 8 }))
    lu.assertEquals(#log.entries, 0)
    lu.assertEquals(log.nextSeq, 1)
end

function T.TestBookingLog:testExportBlockIsRoundedAndSorted()
    local log = RPSimBookingLog.new()
    RPSimBookingLog.record(log, booking(40, "AI", -10.4))
    RPSimBookingLog.record(log, booking(40, "SOLD_PRODUCTS", 99.6, { fillType = "WHEAT", sellPoint = "Mill",
        liters = 450.4 }))
    local doc = RPSimFarmFacts.buildBookings(RPSimBookingLog.toRaw(log))
    lu.assertEquals(doc.nextSeq, 3)
    lu.assertEquals(#doc.entries, 2)
    lu.assertEquals(doc.entries[1].amount, -10)
    lu.assertEquals(doc.entries[1].day, 1)
    lu.assertNil(doc.entries[1].fillType)
    lu.assertEquals(doc.entries[2].amount, 100)
    lu.assertEquals(doc.entries[2].liters, 450)
    lu.assertEquals(doc.entries[2].fillType, "WHEAT")
end

function T.TestBookingLog:testBookingLogSurvivesTheSavegame()
    local state = RPSimProcessor.newState(RPSimConfig.new())
    state.savegameId = "sg"
    RPSimBookingLog.record(state.bookingLog, booking(40, "AI", -10.5))
    RPSimBookingLog.record(state.bookingLog, booking(40, "SOLD_PRODUCTS", 200, { fillType = "WHEAT",
        sellPoint = "Mill", liters = 900 }))
    RPSimBookingLog.record(state.bookingLog, booking(40, "RPSIM_FINE", -500, { note = "Bußgeld" }))
    local xml = { data = {} }
    function xml:setString(k, v) self.data[k] = v end
    function xml:setInt(k, v) self.data[k] = v end
    function xml:setFloat(k, v) self.data[k] = v end
    function xml:getString(k) local v = self.data[k]; return v ~= nil and tostring(v) or nil end
    function xml:getInt(k) return self.data[k] end
    function xml:getFloat(k) return self.data[k] end
    RPSimPersistence.save(xml, state)
    local loaded = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load(xml, loaded)
    lu.assertEquals(loaded.bookingLog, state.bookingLog)
end

-- The hook: game bookings with day and sale details, tool bookings with their note, in journal and statement
function T.TestBookingLog:testChangeBalanceHookFillsTheStatement()
    local game = helpers.fakeGame()
    MoneyType.SOLD_PRODUCTS = { id = 3, statistic = "soldProducts" }
    MoneyType.register = function(statistic, title) return { statistic = statistic, title = title } end
    local farmObject = { getId = function() return 1 end }
    Farm = { changeBalance = function() end }
    Utils = { appendedFunction = function(orig, fn)
        return function(...) orig(...); fn(...) end
    end, overwrittenFunction = function(orig, fn)
        return function(obj, ...) return fn(obj, orig, ...) end
    end }
    g_currentMission.addMoney = function(_, amount, farmId, moneyType)
        game.moneyLog[#game.moneyLog + 1] = { amount = amount, farmId = farmId, moneyType = moneyType }
        Farm.changeBalance(farmObject, amount, moneyType)
    end
    g_fillTypeManager = { getFillTypeNameByIndex = function(_, i) return i == 5 and "WHEAT" or nil end }
    -- like the game: the selling station pays the sale inside sellFillType
    SellingStation = {
        getEffectiveFillTypePrice = function() return 1 end,
        sellFillType = function(_, farmId, fillDelta)
            g_currentMission:addMoney(fillDelta * 0.2, farmId, MoneyType.SOLD_PRODUCTS)
            return fillDelta
        end,
    }
    helpers.loadGameModules()
    helpers.fakeFs()
    local adapter = RPSimGameAdapter.new()
    adapter.config = RPSimConfig.new()
    local bridge = RPSimBridge.new(RPSimConfig.new(), RPSimBridgePaths.new("/ms/"), adapter)
    bridge.financeJournalEnabled = true
    bridge.started = true
    RPSim.bridge = bridge
    local station = { getName = function() return "MillNorth" end }
    lu.assertEquals(SellingStation.sellFillType(station, 1, 1000, 5), 1000)
    lu.assertEquals(SellingStation.sellFillType(station, 1, 500, 5), 500)
    lu.assertTrue(adapter:addMoney(-2400, "SALARY_PAYMENT", "Gehalt Anna"))
    lu.assertNil(adapter.bookingNote)
    lu.assertNil(bridge.saleContext)
    local entries = bridge.state.bookingLog.entries
    lu.assertEquals(#entries, 2)
    lu.assertEquals(entries[1].category, "SOLD_PRODUCTS")
    lu.assertEquals(entries[1].amount, 300)
    lu.assertEquals(entries[1].liters, 1500)
    lu.assertEquals(entries[1].fillType, "WHEAT")
    lu.assertEquals(entries[1].day, 2)
    lu.assertEquals(entries[1].monotonicDay, 3)
    lu.assertEquals(entries[2].category, "RPSIM_SALARY_PAYMENT")
    lu.assertEquals(entries[2].note, "Gehalt Anna")
    lu.assertTrue(entries[2].single)
    RPSim.bridge = nil
    Farm = nil
    Utils = nil
    SellingStation = nil
end

return T
