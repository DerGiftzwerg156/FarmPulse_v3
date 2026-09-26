-- Folder layout of the file bridge (technical concept "Ordnerstruktur").
RPSimBridgePaths = {}

--- Resolves the FS25 modSettings folder.
-- g_modSettingsDirectory is kept as first choice for compatibility, but there is no evidence that FS25 sets
-- it; FS25 mods build the path from getUserProfileAppPath() .. "modSettings/" (Farm Dashboard
-- FarmDashboardDataCollector.lua, BetterContracts RoyalMod.lua, UsedPlus AI reference hud-framework.md).
-- A relative "./modSettings/" would point into the game installation folder, where the backend never looks.
function RPSimBridgePaths.resolveModSettingsDir(modSettingsDirectory, userProfileAppPath)
    if type(modSettingsDirectory) == "string" and modSettingsDirectory ~= "" then
        return modSettingsDirectory
    end
    if type(userProfileAppPath) == "string" and userProfileAppPath ~= "" then
        local base = userProfileAppPath
        if base:sub(-1) ~= "/" and base:sub(-1) ~= "\\" then
            base = base .. "/"
        end
        return base .. "modSettings/"
    end
    return nil
end

function RPSimBridgePaths.new(modSettingsDir)
    local base = modSettingsDir
    if base:sub(-1) ~= "/" then
        base = base .. "/"
    end
    base = base .. "FS25_RPSim/"
    return {
        base = base,
        exportDir = base .. "export/",
        importDir = base .. "import/",
        farmFacts = base .. "export/farm_facts.json",
        marketContext = base .. "export/market_context.json",
        instructions = base .. "import/instructions.json",
        instructionsAck = base .. "import/instructions_ack.json",
        configFile = base .. "rpsim_config.json",
    }
end
