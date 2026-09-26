std = "lua51"
self = false
max_line_length = 130
-- Globals defined by this mod (FS25 loads files via `source`, modules are global tables).
globals = {
    "RPSim", "RPSimJson", "RPSimLog", "RPSimFileIO", "RPSimConfig", "RPSimBridgePaths", "RPSimStorage",
    "RPSimFarmFacts", "RPSimMarketContext", "RPSimInstructions", "RPSimPriceEventMath", "RPSimPriceEvents",
    "RPSimProcessor", "RPSimPersistence", "RPSimBridge", "RPSimGameAdapter",
}
-- FS25 engine globals (read-only).
read_globals = {
    "g_currentModName", "g_currentModDirectory", "g_fillTypeManager",
}
files["tests/"] = { std = "+busted", globals = { "TestJson", "lu" } }
files["tests/**/*.lua"] = { ignore = { "111", "112", "113", "121", "212" } }
