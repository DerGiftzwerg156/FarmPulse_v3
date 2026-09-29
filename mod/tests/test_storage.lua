local lu = require("luaunit")
local T = {}
T.TestStorage = {}

local function silo(desc, storages)
    return { descriptor = desc, storages = storages }
end
local SILO = { kind = "SILO" }

function T.TestStorage:testWhatCountsAsStock()
    lu.assertTrue(RPSimStorage.counts(SILO))
    lu.assertTrue(RPSimStorage.counts({ kind = "SILO_EXTENSION" }))
    lu.assertTrue(RPSimStorage.counts({ kind = "PRODUCTION" }))
    lu.assertTrue(RPSimStorage.counts({ kind = "BUNKER_SILO" }))
    lu.assertEquals(RPSimStorage.exclusionReason({ kind = "SILO", hasHusbandrySpec = true }), "husbandry")
    lu.assertEquals(RPSimStorage.exclusionReason({ kind = "SILO", hasObjectStorageSpec = true }), "object storage")
    lu.assertEquals(RPSimStorage.exclusionReason({ kind = "SHED" }), "no storage")
    lu.assertFalse(RPSimStorage.counts(nil))
end

function T.TestStorage:testMultipleSilosSameFillTypeAreSummed()
    local out = RPSimStorage.aggregate({
        silo(SILO, { { capacity = 50000, fillLevels = { WHEAT = 42000 } } }),
        silo(SILO, { { capacity = 30000, fillLevels = { WHEAT = 10000, BARLEY = 5000 } } }),
    })
    lu.assertEquals(out, {
        { fillType = "BARLEY", amount = 5000, capacity = 30000 },
        { fillType = "WHEAT", amount = 52000, capacity = 80000 },
    })
end

function T.TestStorage:testProductionsAndBunkerSilosCountHallsAndHusbandriesNot()
    local out = RPSimStorage.aggregate({
        silo(SILO, { { capacity = 50000, fillLevels = { WHEAT = 1000 } } }),
        silo({ kind = "BUNKER_SILO" }, { { capacity = 0, fillLevels = { SILAGE = 90000 } } }),
        silo({ kind = "PRODUCTION" }, { { capacity = 20000, fillLevels = { SUGARBEET = 3000, SUGAR = 500 } } }),
        silo({ kind = "SILO", hasObjectStorageSpec = true }, { { capacity = 99999, fillLevels = { WHEAT = 90000 } } }),
        silo({ kind = "SILO", hasHusbandrySpec = true }, { { capacity = 99999, fillLevels = { WHEAT = 90000 } } }),
    })
    lu.assertEquals(out, {
        { fillType = "SILAGE", amount = 90000, capacity = 0 },
        { fillType = "SUGAR", amount = 500, capacity = 20000 },
        { fillType = "SUGARBEET", amount = 3000, capacity = 20000 },
        { fillType = "WHEAT", amount = 1000, capacity = 50000 },
    })
end

function T.TestStorage:testEmptyFillTypesAreOmitted()
    local out = RPSimStorage.aggregate({ silo(SILO, { { capacity = 100, fillLevels = { WHEAT = 0 } } }) })
    lu.assertEquals(#out, 0)
end

function T.TestStorage:testSiloExtensionsCount()
    local out = RPSimStorage.aggregate({
        silo(SILO, { { capacity = 50000, fillLevels = { WHEAT = 1000 } } }),
        silo({ kind = "SILO_EXTENSION" }, { { capacity = 200000, fillLevels = { WHEAT = 3000 } } }),
    })
    lu.assertEquals(out, { { fillType = "WHEAT", amount = 4000, capacity = 250000 } })
end

function T.TestStorage:testDescribeExplainsEachStoragePlace()
    local lines = RPSimStorage.describe({
        { uniqueId = "silo_1", descriptor = SILO, storages = { { capacity = 10, fillLevels = { WHEAT = 4.6, OAT = 0 } } } },
        { uniqueId = "cow", descriptor = { kind = "SILO", hasHusbandrySpec = true }, storages = {} },
    })
    lu.assertEquals(lines, {
        "silo_1 [SILO]: counted, 1 storages, fill levels: WHEAT=5",
        "cow [SILO]: ignored (husbandry), 0 storages, fill levels: empty",
    })
end

function T.TestStorage:testPerFillTypeCapacity()
    local out = RPSimStorage.aggregate({ silo(SILO, { { capacity = 100,
        capacityPerFillType = { WHEAT = 60 }, fillLevels = { WHEAT = 10 } } }) })
    lu.assertEquals(out[1].capacity, 60)
end

return T
