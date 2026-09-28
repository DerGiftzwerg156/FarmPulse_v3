local lu = require("luaunit")
local T = {}
T.TestStorage = {}

local function silo(desc, storages)
    return { descriptor = desc, storages = storages }
end
local CLASSIC = { hasSiloSpec = true, categoryName = "SILOS" }

function T.TestStorage:testClassicSiloClassification()
    lu.assertTrue(RPSimStorage.isClassicSilo(CLASSIC))
    lu.assertTrue(RPSimStorage.isClassicSilo({ hasSiloSpec = true }))
    lu.assertFalse(RPSimStorage.isClassicSilo({ hasSiloSpec = true, hasBunkerSiloSpec = true }))
    lu.assertFalse(RPSimStorage.isClassicSilo({ hasSiloSpec = true, hasObjectStorageSpec = true }))
    lu.assertFalse(RPSimStorage.isClassicSilo({ hasSiloSpec = true, hasHusbandrySpec = true }))
    lu.assertFalse(RPSimStorage.isClassicSilo({ hasSiloSpec = true, hasProductionSpec = true }))
    lu.assertFalse(RPSimStorage.isClassicSilo({ hasSiloSpec = true, categoryName = "SHEDS" }))
    lu.assertFalse(RPSimStorage.isClassicSilo({ hasSiloSpec = false }))
    lu.assertFalse(RPSimStorage.isClassicSilo(nil))
end

function T.TestStorage:testMultipleSilosSameFillTypeAreSummed()
    local out = RPSimStorage.aggregate({
        silo(CLASSIC, { { capacity = 50000, fillLevels = { WHEAT = 42000 } } }),
        silo(CLASSIC, { { capacity = 30000, fillLevels = { WHEAT = 10000, BARLEY = 5000 } } }),
    })
    lu.assertEquals(out, {
        { fillType = "BARLEY", amount = 5000, capacity = 30000 },
        { fillType = "WHEAT", amount = 52000, capacity = 80000 },
    })
end

function T.TestStorage:testBunkerSiloAndHallAreExcluded()
    local out = RPSimStorage.aggregate({
        silo(CLASSIC, { { capacity = 50000, fillLevels = { WHEAT = 1000 } } }),
        silo({ hasSiloSpec = true, hasBunkerSiloSpec = true }, { { capacity = 99999, fillLevels = { SILAGE = 90000 } } }),
        silo({ hasSiloSpec = true, hasObjectStorageSpec = true }, { { capacity = 99999, fillLevels = { WHEAT = 90000 } } }),
    })
    lu.assertEquals(out, { { fillType = "WHEAT", amount = 1000, capacity = 50000 } })
end

function T.TestStorage:testEmptyFillTypesAreOmitted()
    local out = RPSimStorage.aggregate({ silo(CLASSIC, { { capacity = 100, fillLevels = { WHEAT = 0 } } }) })
    lu.assertEquals(#out, 0)
end

-- Live test 1.5.1 (empty storage): a category other than "SILOS" dropped the silo - only pure sheds are excluded now.
function T.TestStorage:testStoreCategoryOnlyExcludesPureSheds()
    lu.assertTrue(RPSimStorage.isClassicSilo({ hasSiloSpec = true, categoryName = "PLACEABLEMISC" }))
    lu.assertTrue(RPSimStorage.isClassicSilo({ hasSiloSpec = true, categoryNames = { "silos" } }))
    lu.assertTrue(RPSimStorage.isClassicSilo({ hasSiloSpec = true, categoryNames = { "SHEDS", "SILOS" } }))
    lu.assertFalse(RPSimStorage.isClassicSilo({ hasSiloSpec = true, categoryNames = { "sheds" } }))
    lu.assertEquals(RPSimStorage.exclusionReason({ hasSiloSpec = true, categoryNames = { "SHEDS" } }),
        "hall category SHEDS")
end

function T.TestStorage:testSiloExtensionsCount()
    local ext = { hasSiloExtensionSpec = true }
    lu.assertTrue(RPSimStorage.isClassicSilo(ext))
    lu.assertFalse(RPSimStorage.isClassicSilo({ hasSiloExtensionSpec = true, hasHusbandrySpec = true }))
    local out = RPSimStorage.aggregate({
        silo(CLASSIC, { { capacity = 50000, fillLevels = { WHEAT = 1000 } } }),
        silo(ext, { { capacity = 200000, fillLevels = { WHEAT = 3000 } } }),
    })
    lu.assertEquals(out, { { fillType = "WHEAT", amount = 4000, capacity = 250000 } })
end

function T.TestStorage:testDescribeExplainsEachSilo()
    local lines = RPSimStorage.describe({
        { uniqueId = "silo_1", descriptor = CLASSIC, storages = { { capacity = 10, fillLevels = { WHEAT = 4.6, OAT = 0 } } } },
        { uniqueId = "bunker", descriptor = { hasSiloSpec = true, hasBunkerSiloSpec = true }, storages = {} },
    })
    lu.assertEquals(lines, {
        "silo_1 [silo, categories=SILOS]: counted, 1 storages, fill levels: WHEAT=5",
        "bunker [silo, categories=]: ignored (bunker silo), 0 storages, fill levels: empty",
    })
end

function T.TestStorage:testPerFillTypeCapacity()
    local out = RPSimStorage.aggregate({ silo(CLASSIC, { { capacity = 100,
        capacityPerFillType = { WHEAT = 60 }, fillLevels = { WHEAT = 10 } } }) })
    lu.assertEquals(out[1].capacity, 60)
end

return T
