-- Roadmap V2 R2-B1: booking journal (Farm.changeBalance hook -> sums per FS25 period -> farm_facts.finances).
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestFinanceJournal = {}

function T.TestFinanceJournal:testRecordSumsPerPeriodAndType()
    local j = RPSimFinanceJournal.new()
    lu.assertTrue(RPSimFinanceJournal.record(j, 2, 8, "HARVEST_INCOME", 48000, 13))
    RPSimFinanceJournal.record(j, 2, 8, "HARVEST_INCOME", 200, 13)
    RPSimFinanceJournal.record(j, 2, 8, "PURCHASE_FUEL", -3100, 13)
    RPSimFinanceJournal.record(j, 2, 7, "AI", -1250, 13)
    lu.assertEquals(j.periods, {
        { year = 2, period = 7, byType = { AI = -1250 } },
        { year = 2, period = 8, byType = { HARVEST_INCOME = 48200, PURCHASE_FUEL = -3100 } } })
end

function T.TestFinanceJournal:testInvalidBookingsAreIgnored()
    local j = RPSimFinanceJournal.new()
    lu.assertFalse(RPSimFinanceJournal.record(j, 2, 8, "AI", 0, 13))
    lu.assertFalse(RPSimFinanceJournal.record(j, nil, 8, "AI", -5, 13))
    lu.assertFalse(RPSimFinanceJournal.record(j, 2, 8, "", -5, 13))
    lu.assertFalse(RPSimFinanceJournal.record(j, 2, 8, "AI", 0 / 0, 13))
    lu.assertEquals(#j.periods, 0)
end

function T.TestFinanceJournal:testOnlyTheLastPeriodsAreKept()
    local j = RPSimFinanceJournal.new()
    for i = 1, 15 do
        RPSimFinanceJournal.record(j, 1 + math.floor((i - 1) / 12), (i - 1) % 12 + 1, "AI", -i, 13)
    end
    lu.assertEquals(#j.periods, 13)
    lu.assertEquals(j.periods[1], { year = 1, period = 3, byType = { AI = -3 } })
    lu.assertEquals(j.periods[13], { year = 2, period = 3, byType = { AI = -15 } })
end

function T.TestFinanceJournal:testNameOfBooking()
    local lookup = function(mt) return mt == "fuel" and "PURCHASE_FUEL" or nil end
    lu.assertEquals(RPSimFinanceJournal.nameOf("SALARY_PAYMENT", "fuel", lookup), "RPSIM_SALARY_PAYMENT")
    lu.assertEquals(RPSimFinanceJournal.nameOf(nil, "fuel", lookup), "PURCHASE_FUEL")
    lu.assertEquals(RPSimFinanceJournal.nameOf(nil, "registered at runtime", lookup), "UNKNOWN")
end

function T.TestFinanceJournal:testJournalSurvivesTheSavegame()
    local x = { data = {} }
    function x:setString(k, v) self.data[k] = v end
    function x:setInt(k, v) self.data[k] = v end
    function x:setFloat(k, v) self.data[k] = v end
    function x:getString(k) local v = self.data[k]; return v ~= nil and tostring(v) or nil end
    function x:getInt(k) return self.data[k] end
    function x:getFloat(k) return self.data[k] end
    local state = RPSimProcessor.newState(RPSimConfig.new())
    RPSimFinanceJournal.record(state.financeJournal, 2, 7, "AI", -1250.5, 13)
    RPSimFinanceJournal.record(state.financeJournal, 2, 8, "HARVEST_INCOME", 48000, 13)
    RPSimFinanceJournal.record(state.financeJournal, 2, 8, "RPSIM_SALARY_PAYMENT", -2400, 13)
    RPSimPersistence.save(x, state)
    local loaded = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load(x, loaded)
    lu.assertEquals(loaded.financeJournal, state.financeJournal)
end

function T.TestFinanceJournal:testExportOnlyWhileTheHookRuns()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:onSavegameLoaded()
    lu.assertNil(RPSimJson.decode(fs.files[paths.farmFacts]).finances)
    bridge.financeJournalEnabled = true
    lu.assertTrue(bridge:recordBooking(1, -3100.4, "fuel"))
    lu.assertFalse(bridge:recordBooking(2, 999, "fuel"), "other farms are not recorded")
    bridge:exportFarmFacts()
    local finances = RPSimJson.decode(fs.files[paths.farmFacts]).finances
    lu.assertEquals(#finances.periods, 1)
    lu.assertEquals(finances.periods[1].year, 2)
    lu.assertEquals(finances.periods[1].period, 8)
    lu.assertEquals(finances.periods[1].byType, { UNKNOWN = -3100 })
end

function T.TestFinanceJournal:testNothingIsRecordedBeforeTheMissionStarted()
    local bridge = helpers.newBridge()
    bridge.financeJournalEnabled = true
    lu.assertFalse(bridge:recordBooking(1, 100, "x"))
    lu.assertEquals(#bridge.state.financeJournal.periods, 0)
end

-- The hook on Farm.changeBalance: game bookings by money type, the tool's own bookings as RPSIM_<REASON>
function T.TestFinanceJournal:testChangeBalanceHookRecordsGameAndToolBookings()
    local game = helpers.fakeGame()
    MoneyType.PURCHASE_FUEL = { id = 7, statistic = "purchaseFuel" }
    MoneyType.register = function(statistic, title) return { statistic = statistic, title = title } end
    local farmObject = { getId = function() return 1 end }
    Farm = { changeBalance = function() end }
    Utils = { appendedFunction = function(orig, fn)
        return function(...) orig(...); fn(...) end
    end, overwrittenFunction = function(orig) return orig end }
    -- like FSBaseMission.addMoney: the farm balance changes through Farm:changeBalance
    g_currentMission.addMoney = function(_, amount, farmId, moneyType)
        game.moneyLog[#game.moneyLog + 1] = { amount = amount, farmId = farmId, moneyType = moneyType }
        Farm.changeBalance(farmObject, amount, moneyType)
    end
    helpers.loadGameModules()
    lu.assertTrue(RPSim.financeHook)
    local fs = helpers.fakeFs()
    local adapter = RPSimGameAdapter.new()
    adapter.config = RPSimConfig.new()
    local bridge = RPSimBridge.new(RPSimConfig.new(), RPSimBridgePaths.new("/ms/"), adapter)
    bridge.financeJournalEnabled = RPSim.financeHook
    bridge.started = true
    RPSim.bridge = bridge
    Farm.changeBalance(farmObject, -120, MoneyType.PURCHASE_FUEL)
    Farm.changeBalance({ getId = function() return 2 end }, -999, MoneyType.PURCHASE_FUEL)
    lu.assertTrue(adapter:addMoney(-2400, "SALARY_PAYMENT", "Gehalt"))
    lu.assertNil(adapter.bookingReason)
    lu.assertEquals(bridge.state.financeJournal.periods, {
        { year = 2, period = 8, byType = { PURCHASE_FUEL = -120, RPSIM_SALARY_PAYMENT = -2400 } } })
    lu.assertNotNil(fs)
    RPSim.bridge = nil
    Farm = nil
    Utils = nil
end

return T
