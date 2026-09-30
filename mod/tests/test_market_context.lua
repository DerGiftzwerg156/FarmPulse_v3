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
    lu.assertEquals(doc.farmlands[1], { farmlandId = 12, hectares = 4.5, price = 54000, ownerFarmId = 1,
        showOnFarmlandsScreen = true, defaultFarmProperty = false })
    lu.assertEquals(doc.detectedMods, {})
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

function T.TestMarketContext:testFarmlandFlagsAndDetectedModsAreExported()
    local doc = RPSimMarketContext.build({ savegameId = "sg", mapName = "m",
        detectedMods = { "FS25_UsedPlus", "FS25_BetterContracts" },
        farmlands = { { farmlandId = 3, hectares = 1, price = 1, ownerFarmId = 0, showOnFarmlandsScreen = false,
            defaultFarmProperty = true } } })
    lu.assertEquals(doc.detectedMods, { "FS25_BetterContracts", "FS25_UsedPlus" })
    lu.assertFalse(doc.farmlands[1].showOnFarmlandsScreen)
    lu.assertTrue(doc.farmlands[1].defaultFarmProperty)
    lu.assertStrContains(RPSimJson.encode(doc), '"detectedMods":["FS25_BetterContracts","FS25_UsedPlus"]')
end

-- Roadmap V3 (R3-Q1 / R3-V1): shop vehicle catalog, optional
function T.TestMarketContext:testStoreVehiclesAreOptionalAndNormalized()
    local doc = RPSimMarketContext.build({ savegameId = "sg", mapName = "Erlengrund" })
    lu.assertNil(doc.storeVehicles)
    doc = RPSimMarketContext.build({ savegameId = "sg", mapName = "Erlengrund", storeVehicles = {
        { xmlFilename = "data/vehicles/fendt/vario700/vario700.xml", name = "Fendt 700 Vario", price = 245000.4,
            lifetime = 600, categoryName = "TRACTORSL", isMod = false, motorized = true },
        { xmlFilename = "data/vehicles/amazone/catros/catros.xml", name = "Catros", price = 32000, lifetime = 600,
            categoryName = "CULTIVATORS" },
        { name = "no file", price = 1 } } })
    lu.assertEquals(doc.storeVehicles, {
        { xmlFilename = "data/vehicles/amazone/catros/catros.xml", name = "Catros", price = 32000, lifetime = 600,
            categoryName = "CULTIVATORS", isMod = false },
        { xmlFilename = "data/vehicles/fendt/vario700/vario700.xml", name = "Fendt 700 Vario", price = 245000,
            lifetime = 600, categoryName = "TRACTORSL", isMod = false, motorized = true } })
end

return T
