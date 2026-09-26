-- Mod configuration. Every tunable value lives here (no magic numbers in the modules).
-- Defaults can be overridden by modSettings/FS25_RPSim/rpsim_config.json (same keys).
RPSimConfig = {}

RPSimConfig.DEFAULTS = {
    -- Technical concept "Datei-Bridge": farm_facts.json is overwritten roughly every 60 s.
    exportIntervalMs = 60000,
    -- Safety net for T-01: if Mission00.onStartMission never reaches the bridge, it starts after this many ms
    -- of frame updates (Farm Dashboard uses the same "ready after a delay" pattern).
    startFallbackMs = 30000,
    -- How often instructions.json is polled (real time, ms).
    importIntervalMs = 5000,
    -- Technical concept "Ack & Idempotenz": prune processedInstructions after e.g. 30 game days.
    processedRetentionGameDays = 30,
    -- Write strategy: "direct" writes the file in place. The FS25 sandbox has no `os` module, so the
    -- tmp+rename ("rename"/"auto") and marker ("marker") strategies cannot work in the game; they are kept for
    -- tests and tooling only. The backend discards incomplete JSON and re-reads it next cycle.
    atomicWriteMode = "direct",
    -- Current bridge schema version written into farm_facts.json.
    schemaVersion = 1,
    -- Price unit used for exported prices: FS25 stores prices per liter; exports use price per 1000 l.
    pricePerLiters = 1000,
    -- T-09: mods whose features overlap with RPSim (mod folder / zip names). Detected ones are reported in
    -- market_context.json; the backend shows a warning. Nothing is disabled.
    conflictMods = { "FS25_MarketDynamics", "FS25_UsedPlus", "FS25_EnhancedLoanSystem", "FS25_BetterContracts" },
    -- T-21: bookings get their own title (modDesc l10n "rpsim_money_<REASON>") via MoneyType.register(statistic,
    -- titleKey), the pattern of FS25 FillTrigger.lua (MoneyType.register("other", "finance_purchaseFuel")).
    -- false = everything is booked as MoneyType.OTHER like before.
    moneyTypeTitles = true,
    -- T-21: finance statistic per reason, e.g. { "SALARY_PAYMENT": "wagePayment" }. Only "other" is verified in
    -- the FS25 code; other names must be checked in the game first (manual test plan). Empty = "other".
    moneyTypeStatistics = {},
}

function RPSimConfig.new(overrides)
    local cfg = {}
    for k, v in pairs(RPSimConfig.DEFAULTS) do
        cfg[k] = v
    end
    if type(overrides) == "table" then
        for k, v in pairs(overrides) do
            if RPSimConfig.DEFAULTS[k] ~= nil and type(v) == type(RPSimConfig.DEFAULTS[k]) then
                cfg[k] = v
            end
        end
    end
    return cfg
end

-- Game time is expressed in in-game milliseconds since savegame start (see RPSimGameAdapter.getGameTime).
RPSimConfig.MS_PER_GAME_DAY = 24 * 60 * 60 * 1000
RPSimConfig.MS_PER_GAME_HOUR = 60 * 60 * 1000
