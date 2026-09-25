-- Loads all mod sources in dependency order (FS25 has no `require`; files are loaded via `source`).
-- luacheck: globals source g_currentModDirectory fileExists
local dir = g_currentModDirectory .. "src/"
local files = {
    "util/Json.lua", "util/Log.lua", "util/FileIO.lua",
    "bridge/Config.lua", "bridge/BridgePaths.lua",
    "export/Storage.lua", "export/FarmFacts.lua", "export/MarketContext.lua",
    "import/Instructions.lua", "import/PriceEventMath.lua", "import/PriceEvents.lua",
    "import/Processor.lua", "import/Persistence.lua",
    "bridge/Bridge.lua", "game/GameAdapter.lua", "RPSim.lua",
}
for _, f in ipairs(files) do
    local path = dir .. f
    if fileExists == nil or fileExists(path) then
        source(path)
    else
        print("[FS25_RPSim] WARNING missing source file " .. path)
    end
end
