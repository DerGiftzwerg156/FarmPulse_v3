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

-- Roadmap V3.1 (R31-Q1 / R31-K1): field outlines and map size, optional
function T.TestMarketContext:testFieldShapesAreOptionalAndNormalized()
    local doc = RPSimMarketContext.build({ savegameId = "sg", mapName = "Erlengrund" })
    lu.assertNil(doc.fieldShapes)
    doc = RPSimMarketContext.build({ savegameId = "sg", mapName = "Erlengrund", fieldShapes = {
        mapSize = 2048, fields = {
            { farmlandId = 4, name = "4", points = { { x = 10.04, z = -20.06 }, { x = 30, z = -20 }, { x = 30, z = 5 } } },
            { farmlandId = 2, name = "2", points = { { x = 1, z = 1 }, { x = 2, z = 2 } } },
            { farmlandId = 3, points = { { x = 1, z = 1 }, { x = 2, z = 2 }, { x = 3, z = 1 } } } } } })
    lu.assertEquals(doc.fieldShapes, { mapSize = 2048, fields = {
        { farmlandId = 4, name = "4", points = { { x = 10, z = -20.1 }, { x = 30, z = -20 }, { x = 30, z = 5 } } } } })
    -- no usable map size: left out
    doc = RPSimMarketContext.build({ savegameId = "sg", mapName = "m", fieldShapes = { mapSize = 0, fields = {} } })
    lu.assertNil(doc.fieldShapes)
    -- an empty list is a real answer
    doc = RPSimMarketContext.build({ savegameId = "sg", mapName = "m", fieldShapes = { mapSize = 4096 } })
    lu.assertStrContains(RPSimJson.encode(doc), '"fieldShapes":{"fields":[],"mapSize":4096}')
end

function T.TestMarketContext:testFieldShapesAreThinnedOutToTheConfiguredMaximum()
    local points = {}
    for i = 1, 200 do
        points[i] = { x = i, z = 0 }
    end
    local raw = { savegameId = "sg", mapName = "m", fieldShapes = { mapSize = 2048,
        fields = { { farmlandId = 1, name = "1", points = points } } } }
    local doc = RPSimMarketContext.build(raw)
    local kept = doc.fieldShapes.fields[1].points
    lu.assertEquals(#kept, 64)
    lu.assertEquals(kept[1], { x = 1, z = 0 })
    lu.assertTrue(kept[64].x > 190)
    doc = RPSimMarketContext.build(raw, RPSimConfig.new({ fieldShapeMaxPoints = 8 }))
    lu.assertEquals(#doc.fieldShapes.fields[1].points, 8)
end

return T
