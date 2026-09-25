local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestFarmFacts = {}

function T.TestFarmFacts:testBuildMatchesSchema()
    local adapter = helpers.fakeAdapter()
    local raw = adapter:collectFarmFacts()
    raw.savegameId = "map_erlengrund_20260101"
    raw.gameTime = 48300000
    local doc = RPSimFarmFacts.build(raw, RPSimConfig.new())
    lu.assertEquals(doc.schemaVersion, 1)
    lu.assertEquals(doc.gameTime, 48300000)
    lu.assertEquals(doc.savegameId, "map_erlengrund_20260101")
    lu.assertEquals(doc.liquidity.balance, 245000)
    lu.assertEquals(doc.assets.vehicles[1], { uniqueId = "veh_00042", value = 285000, condition = 82 })
    lu.assertEquals(doc.assets.placeables[1], { uniqueId = "plc_00011", value = 120000 })
    lu.assertEquals(doc.assets.farmland[1], { farmlandId = 12, hectares = 4.5, price = 54000 })
    lu.assertEquals(doc.assets.animals[1], { husbandryUniqueId = "hus_00003", type = "COW", count = 24, estimatedValue = 96000 })
    lu.assertEquals(doc.assets.storage[1], { fillType = "WHEAT", amount = 42000, capacity = 50000 })
    lu.assertEquals(doc.liabilities.vanillaLoan, { active = true, remainingAmount = 80000 })
    lu.assertEquals(doc.prices[1], { sellPoint = "MillNorth", fillType = "WHEAT", currentPrice = 215 })
end

function T.TestFarmFacts:testEmptyFarmProducesEmptyArrays()
    local doc = RPSimFarmFacts.build({ savegameId = "s", gameTime = 0, balance = 0 }, RPSimConfig.new())
    local json = RPSimJson.encode(doc)
    lu.assertStrContains(json, '"vehicles":[]')
    lu.assertStrContains(json, '"storage":[]')
    lu.assertStrContains(json, '"prices":[]')
    lu.assertStrContains(json, '"vanillaLoan":{"active":false,"remainingAmount":0}')
end

function T.TestFarmFacts:testConditionFromDamageClamps()
    lu.assertEquals(RPSimFarmFacts.conditionFromDamage(0), 100)
    lu.assertEquals(RPSimFarmFacts.conditionFromDamage(1), 0)
    lu.assertEquals(RPSimFarmFacts.conditionFromDamage(1.5), 0)
    lu.assertEquals(RPSimFarmFacts.conditionFromDamage(-0.2), 100)
    lu.assertEquals(RPSimFarmFacts.conditionFromDamage(nil), 100)
end

function T.TestFarmFacts:testExportWritesAtomicallyViaRename()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:bootstrap()
    lu.assertTrue(bridge:exportFarmFacts())
    lu.assertNotNil(fs.files[paths.farmFacts])
    lu.assertNil(fs.files[paths.farmFacts .. ".tmp"])
    lu.assertNil(fs.files[paths.farmFacts .. ".ready"])
    local doc = RPSimJson.decode(fs.files[paths.farmFacts])
    lu.assertEquals(doc.savegameId, "map_erlengrund_1_20260101")
end

function T.TestFarmFacts:testMarkerFallbackWhenRenameUnavailable()
    local bridge, fs, _, paths = helpers.newBridge({ renameAvailable = false })
    bridge:bootstrap()
    lu.assertTrue(bridge:exportFarmFacts())
    lu.assertNotNil(fs.files[paths.farmFacts])
    lu.assertEquals(fs.files[paths.farmFacts .. ".ready"], "")
end

function T.TestFarmFacts:testExportCycleUsesConfiguredInterval()
    local bridge, fs, _, paths = helpers.newBridge({ config = { exportIntervalMs = 1000, importIntervalMs = 999999 } })
    bridge:bootstrap()
    bridge:update(500)
    lu.assertNil(fs.files[paths.farmFacts])
    bridge:update(500)
    lu.assertNotNil(fs.files[paths.farmFacts])
end

return T
