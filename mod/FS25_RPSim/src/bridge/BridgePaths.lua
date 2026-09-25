-- Folder layout of the file bridge (technical concept "Ordnerstruktur").
RPSimBridgePaths = {}

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
