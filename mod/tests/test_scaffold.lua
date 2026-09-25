local lu = require("luaunit")
local T = {}
T.TestScaffold = {}

function T.TestScaffold:testConfigDefaultsAndOverride()
    local cfg = RPSimConfig.new({ exportIntervalMs = 30000, unknownKey = 1, importIntervalMs = "bad" })
    lu.assertEquals(cfg.exportIntervalMs, 30000)
    lu.assertNil(cfg.unknownKey)
    lu.assertEquals(cfg.importIntervalMs, RPSimConfig.DEFAULTS.importIntervalMs)
    lu.assertEquals(cfg.processedRetentionGameDays, 30)
end

function T.TestScaffold:testBridgePaths()
    local p = RPSimBridgePaths.new("/docs/modSettings")
    lu.assertEquals(p.farmFacts, "/docs/modSettings/FS25_RPSim/export/farm_facts.json")
    lu.assertEquals(p.marketContext, "/docs/modSettings/FS25_RPSim/export/market_context.json")
    lu.assertEquals(p.instructions, "/docs/modSettings/FS25_RPSim/import/instructions.json")
    lu.assertEquals(p.instructionsAck, "/docs/modSettings/FS25_RPSim/import/instructions_ack.json")
end

function T.TestScaffold:testModDescVersionIsSemVerLike()
    local f = io.open("FS25_RPSim/modDesc.xml", "r")
    local xml = f:read("*a")
    f:close()
    local v = xml:match("<version>([^<]+)</version>")
    lu.assertNotNil(v:match("^%d+%.%d+%.%d+%.%d+$"))
end

return T
