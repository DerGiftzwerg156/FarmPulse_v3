-- Mod configuration. Every tunable value lives here (no magic numbers in the modules).
-- Defaults can be overridden by modSettings/FS25_RPSim/rpsim_config.json (same keys).
RPSimConfig = {}

RPSimConfig.DEFAULTS = {
    -- Technical concept "Datei-Bridge": farm_facts.json is overwritten roughly every 60 s.
    exportIntervalMs = 60000,
    -- How often instructions.json is polled (real time, ms).
    importIntervalMs = 5000,
    -- Technical concept "Ack & Idempotenz": prune processedInstructions after e.g. 30 game days.
    processedRetentionGameDays = 30,
    -- Atomic write strategy: "rename" (tmp file + os.rename) or "marker" (tmp file + .ready marker).
    -- TODO(offene-frage): availability of os.rename in the FS25 Lua sandbox is unverified; "auto" tries
    -- rename first and falls back to the marker strategy (see docs/dev/offene-technische-punkte.md).
    atomicWriteMode = "auto",
    -- Current bridge schema version written into farm_facts.json.
    schemaVersion = 1,
    -- Price unit used for exported prices: FS25 stores prices per liter; exports use price per 1000 l.
    pricePerLiters = 1000,
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
