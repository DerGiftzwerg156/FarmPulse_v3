-- Booking journal of the player farm (Roadmap V2, R2-B1): cumulative sums per FS25 period (game month) and money type.
-- Filled by the Farm.changeBalance hook (RPSim.lua), stored in the savegame XML (RPSimPersistence) and exported as
-- farm_facts.finances. Pure logic, testable without FS25.
RPSimFinanceJournal = {}

--- Prefix of the tool's own bookings (RPSimGameAdapter:addMoney), so they never count as an FS25 money type.
RPSimFinanceJournal.RPSIM_PREFIX = "RPSIM_"
--- Money type the name of which could not be determined (neither an RPSim booking nor in the global MoneyType table).
RPSimFinanceJournal.UNKNOWN = "UNKNOWN"

--- journal = { periods = { { year, period, byType = { [name] = amount } } } } (sorted by year, period)
function RPSimFinanceJournal.new()
    return { periods = {} }
end

local function before(a, b)
    if a.year == b.year then
        return a.period < b.period
    end
    return a.year < b.year
end

--- Adds a booking to the sum of its period. Only the last maxPeriods periods are kept. Returns true when recorded.
function RPSimFinanceJournal.record(journal, year, period, name, amount, maxPeriods)
    if type(year) ~= "number" or type(period) ~= "number" or type(amount) ~= "number" or amount ~= amount
        or amount == 0 or type(name) ~= "string" or name == "" then
        return false
    end
    local entry
    for _, p in ipairs(journal.periods) do
        if p.year == year and p.period == period then
            entry = p
            break
        end
    end
    if entry == nil then
        entry = { year = year, period = period, byType = {} }
        journal.periods[#journal.periods + 1] = entry
        table.sort(journal.periods, before)
        local keep = maxPeriods or #journal.periods
        while #journal.periods > keep do
            table.remove(journal.periods, 1)
        end
    end
    entry.byType[name] = (entry.byType[name] or 0) + amount
    return true
end

--- Raw block for RPSimFarmFacts.build (rounding happens there).
function RPSimFinanceJournal.toRaw(journal)
    local periods = {}
    for _, p in ipairs(journal.periods) do
        local byType = {}
        for name, amount in pairs(p.byType) do
            byType[name] = amount
        end
        periods[#periods + 1] = { year = p.year, period = p.period, byType = byType }
    end
    return { periods = periods }
end

--- Name of a booking: RPSIM_<REASON> for the tool's own bookings, otherwise the name from the lookup function
-- (R2-B1 fallback: reverse lookup in the global MoneyType table), UNKNOWN when nothing matches.
function RPSimFinanceJournal.nameOf(rpsimReason, moneyType, lookup)
    if type(rpsimReason) == "string" and rpsimReason ~= "" then
        return RPSimFinanceJournal.RPSIM_PREFIX .. rpsimReason
    end
    local name = lookup ~= nil and lookup(moneyType) or nil
    if type(name) == "string" and name ~= "" then
        return name
    end
    return RPSimFinanceJournal.UNKNOWN
end
