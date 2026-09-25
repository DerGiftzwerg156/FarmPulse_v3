local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestMarketContext = {}

function T.TestMarketContext:testBuildSchema()
    local adapter = helpers.fakeAdapter()
    local raw = adapter:collectMarketContext()
    raw.savegameId = "sg"
    local doc = RPSimMarketContext.build(raw)
    lu.assertEquals(doc.mapName, "Erlengrund")
    lu.assertEquals(doc.savegameId, "sg")
    lu.assertEquals(doc.sellPoints[1].id, "MillNorth")
    lu.assertEquals(doc.sellPoints[1].name, "Mühle Nord")
    lu.assertEquals(doc.sellPoints[1].acceptedFillTypes, { "BARLEY", "WHEAT" })
    lu.assertEquals(doc.fillTypes, { "BARLEY", "WHEAT" })
    lu.assertEquals(doc.farmlands[1], { farmlandId = 12, hectares = 4.5, price = 54000, ownerFarmId = 1 })
    lu.assertEquals(doc.farmlands[2].ownerFarmId, 0)
end

function T.TestMarketContext:testExportedOnSavegameLoad()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:onSavegameLoaded()
    lu.assertNotNil(fs.files[paths.marketContext])
    local doc = RPSimJson.decode(fs.files[paths.marketContext])
    lu.assertEquals(#doc.farmlands, 2)
end

function T.TestMarketContext:testNotReExportedOnRegularCycle()
    local bridge, fs, _, paths = helpers.newBridge({ config = { exportIntervalMs = 10, importIntervalMs = 10 } })
    bridge:onSavegameLoaded()
    fs.files[paths.marketContext] = "SENTINEL"
    bridge:update(20)
    lu.assertEquals(fs.files[paths.marketContext], "SENTINEL")
end

return T
