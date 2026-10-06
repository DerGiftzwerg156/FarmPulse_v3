-- Booking statement of the player farm ("Kontoauszug"): the single bookings behind the monthly journal
-- (RPSimFinanceJournal). Filled by the Farm.changeBalance hook (RPSim.lua) together with the journal, stored in the
-- savegame XML (RPSimPersistence) and exported as farm_facts.bookings. Pure logic, testable without FS25.
--
-- Owner decisions 2026-10-06 (docs/architecture/QUESTIONS.md):
--   * running bookings are summed per game day and money type (with their count); sales additionally per fill type
--     and sell point (with the litres of the sellFillType hook);
--   * purchases and sales of vehicles, buildings and fields (cfg.bookingLogSingleTypes) and every booking of the tool
--     (RPSIM_<REASON>, with its note) always get an own entry;
--   * the mod only keeps the last cfg.bookingLogEntries entries - the backend stores them permanently. Every entry has
--     a running number (seq); an entry that is summed up keeps its seq, so the backend updates it in place.
RPSimBookingLog = {}

--- log = { nextSeq = n, entries = { { seq, gameTime, year, period, day, monotonicDay, category, amount, count,
--   liters?, fillType?, sellPoint?, note? } } } (sorted by seq)
function RPSimBookingLog.new()
    return { nextSeq = 1, entries = {} }
end

local function isSingle(category, singleTypes)
    if category:sub(1, #RPSimFinanceJournal.RPSIM_PREFIX) == RPSimFinanceJournal.RPSIM_PREFIX then
        return true
    end
    for _, name in ipairs(singleTypes or {}) do
        if name == category then
            return true
        end
    end
    return false
end

local function sameGroup(e, b)
    return e.monotonicDay == b.monotonicDay and e.category == b.category and e.fillType == b.fillType
        and e.sellPoint == b.sellPoint
end

--- Records one booking. b = { gameTime, year, period, day, monotonicDay, category, amount, note?, fillType?,
-- sellPoint?, liters? }; opts = { maxEntries, singleTypes }. Returns the entry, nil for an invalid booking.
function RPSimBookingLog.record(log, b, opts)
    opts = opts or {}
    if type(b) ~= "table" or type(b.amount) ~= "number" or b.amount ~= b.amount or b.amount == 0
        or type(b.category) ~= "string" or b.category == "" or type(b.monotonicDay) ~= "number"
        or type(b.year) ~= "number" or type(b.period) ~= "number" then
        return nil
    end
    local liters = (type(b.liters) == "number" and b.liters == b.liters and b.liters > 0) and b.liters or nil
    if not isSingle(b.category, opts.singleTypes) then
        for i = #log.entries, 1, -1 do
            local e = log.entries[i]
            if e.monotonicDay < b.monotonicDay then
                break
            end
            if not e.single and sameGroup(e, b) then
                e.amount = e.amount + b.amount
                e.count = e.count + 1
                if liters ~= nil then
                    e.liters = (e.liters or 0) + liters
                end
                return e
            end
        end
    end
    local e = { seq = log.nextSeq, gameTime = b.gameTime or 0, year = b.year, period = b.period, day = b.day,
        monotonicDay = b.monotonicDay, category = b.category, amount = b.amount, count = 1, liters = liters,
        fillType = b.fillType, sellPoint = b.sellPoint, note = b.note }
    if isSingle(b.category, opts.singleTypes) then
        e.single = true
    end
    log.nextSeq = log.nextSeq + 1
    log.entries[#log.entries + 1] = e
    local keep = opts.maxEntries or #log.entries
    while #log.entries > keep do
        table.remove(log.entries, 1)
    end
    return e
end

--- Raw block for RPSimFarmFacts.build (rounding happens there).
function RPSimBookingLog.toRaw(log)
    local entries = {}
    for _, e in ipairs(log.entries) do
        entries[#entries + 1] = { seq = e.seq, gameTime = e.gameTime, year = e.year, period = e.period, day = e.day,
            category = e.category, amount = e.amount, count = e.count, single = e.single == true, liters = e.liters,
            fillType = e.fillType, sellPoint = e.sellPoint, note = e.note }
    end
    return { nextSeq = log.nextSeq, entries = entries }
end
