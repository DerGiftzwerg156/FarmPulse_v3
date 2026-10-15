-- Roadmap vehicle dealer, section Q (contract): validation of VEHICLE_DELIVERY (RFH-Q3) and FAILED / NOT_SUPPORTED
-- until RFH-L5, the new money reasons (RFH-Q4), the normalisation of store_catalog.json (RFH-Q1),
-- farm_facts.deliveryPoints and market_context.storePlaces (RFH-Q2), and the catalog file written by the bridge.
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestMachineDealer = {}

local SG = "map_erlengrund_1_20260101"

local function delivery(extra)
    local ins = { instructionId = "ins_vd", type = "VEHICLE_DELIVERY", deliveryId = "dlv_7",
        items = { { storeXmlFilename = "data/vehicles/fendt/vario700/vario700.xml",
            configurations = { baseColor = { saveId = "RED", index = 3 }, wheel = { index = 2 } } },
            { storeXmlFilename = "data/vehicles/poettinger/terradisc/terradisc.xml" } },
        place = { x = 12.5, z = -340, ry = 1.57 }, fallbackToShop = true,
        bookings = { { amount = 98000, moneyReason = "VEHICLE_NEW_PURCHASE", note = "Fendt 724 Vario" },
            { amount = 850, moneyReason = "VEHICLE_DELIVERY_FEE" },
            { amount = 500, moneyReason = "VEHICLE_DELIVERY_FEE", note = "Neuer Lieferversuch" },
            { amount = 0, moneyReason = "VEHICLE_EXPRESS_FEE" } },
        minBalance = -20000, tradeIns = { "veh_00042" } }
    for k, v in pairs(extra or {}) do ins[k] = v end
    return ins
end

function T.TestMachineDealer:testValidDeliveryAndMinimalForm()
    lu.assertTrue(RPSimInstructions.validate(delivery()))
    -- shop places, no trade-in, nothing to book (e.g. everything paid by deposit and trade-in)
    lu.assertTrue(RPSimInstructions.validate({ instructionId = "x", type = "VEHICLE_DELIVERY", deliveryId = "d",
        items = { { storeXmlFilename = "a.xml" } }, bookings = RPSimJson.array({}) }))
end

function T.TestMachineDealer:testInvalidDeliveries()
    local cases = {
        { deliveryId = "" },
        { items = RPSimJson.array({}) },
        { items = { { storeXmlFilename = "" } } },
        { items = { { storeXmlFilename = "a.xml", configurations = { "RED" } } } },
        { items = { { storeXmlFilename = "a.xml", configurations = { baseColor = {} } } } },
        { items = { { storeXmlFilename = "a.xml", configurations = { baseColor = { index = 0 } } } } },
        { items = { { storeXmlFilename = "a.xml", configurations = { baseColor = { index = 1.5 } } } } },
        { items = { { storeXmlFilename = "a.xml", configurations = { baseColor = { saveId = "" } } } } },
        { place = { x = 1, z = 2 } },
        { place = { x = "1", z = 2, ry = 0 } },
        { fallbackToShop = "yes" },
        { bookings = { amount = 5 } },
        { bookings = { { amount = -5, moneyReason = "VEHICLE_PURCHASE" } } },
        { bookings = { { amount = 5, moneyReason = "NEW_TRACTOR" } } },
        { bookings = { { amount = 5, moneyReason = "VEHICLE_PURCHASE", note = 7 } } },
        { tradeIns = { "" } },
        { tradeIns = "veh_00042" },
        { minBalance = 100 },
    }
    for i, c in ipairs(cases) do
        local ok, why = RPSimInstructions.validate(delivery(c))
        lu.assertFalse(ok, "case " .. i)
        lu.assertNotNil(why, "case " .. i)
    end
end

function T.TestMachineDealer:testNewMoneyReasonsAreKnown()
    for _, r in ipairs({ "VEHICLE_DEPOSIT", "VEHICLE_NEW_PURCHASE", "VEHICLE_DELIVERY_FEE", "VEHICLE_EXPRESS_FEE",
        "VEHICLE_REFUND", "VEHICLE_CANCELLATION_FEE" }) do
        lu.assertTrue(RPSimInstructions.MONEY_REASONS[r], r)
        lu.assertTrue(RPSimInstructions.validate({ instructionId = "m", type = "MONEY_TRANSACTION", amount = -1,
            reason = r }), r)
    end
end

function T.TestMachineDealer:testDeliveryIsNotSupportedUntilRfhL5()
    local state = RPSimProcessor.newState(RPSimConfig.new())
    local booked = 0
    local res = RPSimProcessor.process(state, { savegameId = SG, instructions = { delivery() } },
        { savegameId = SG, gameTime = 1000, actions = { money = function() booked = booked + 1; return true end } })
    lu.assertEquals(res.applied, 0)
    lu.assertEquals(booked, 0)
    lu.assertEquals(state.processed.ins_vd.status, "FAILED")
    lu.assertEquals(state.processed.ins_vd.message, "NOT_SUPPORTED")
end

function T.TestMachineDealer:testDeliveryRunsTheAdapterActionOnceItExists()
    local state = RPSimProcessor.newState(RPSimConfig.new())
    local seen
    RPSimProcessor.process(state, { savegameId = SG, instructions = { delivery() } },
        { savegameId = SG, gameTime = 1000, actions = { vehicleDelivery = function(ins)
            seen = ins.deliveryId
            return true, nil, { placeUsed = "DELIVERY_POINT", vehicleCount = 2, vehicleId1 = "veh_1",
                vehicleId2 = "veh_2", tradeInsRemoved = 1, dropped = { "lists", "are", "no", "plain", "values" } }
        end } })
    lu.assertEquals(seen, "dlv_7")
    lu.assertEquals(state.processed.ins_vd.status, "APPLIED")
    -- the ack result keeps plain values only: one key per loaded vehicle
    lu.assertEquals(state.processed.ins_vd.result, { placeUsed = "DELIVERY_POINT", vehicleCount = 2,
        vehicleId1 = "veh_1", vehicleId2 = "veh_2", tradeInsRemoved = 1 })
end

function T.TestMachineDealer:testStoreCatalogNormalisation()
    local doc = RPSimStoreCatalog.build({ savegameId = SG, items = {
        { xmlFilename = "b.xml", name = "Terradisc", price = 25999.6, isMod = false, categoryName = "DISCHARROWS",
            brand = "POETTINGER", lifetime = 600, motorized = false, multipleItemPurchase = false },
        { xmlFilename = "a.xml", name = "724 Vario", price = 215000, dailyUpkeep = 120.4, brand = "FENDT",
            motorized = true, isBundle = false,
            configurations = {
                { name = "wheel", options = {
                    { index = 2, name = "Michelin", price = 4000, saveId = "MICH",
                        dependent = { { name = "frontloader", index = 2 }, { name = "", index = 1 } } },
                    { index = 1, name = "Trelleborg", price = 0, isDefault = true },
                    { index = 0, name = "broken" } } },
                { name = "baseColor", title = "Farbe", options = {
                    { index = 1, name = "Grün", isDefault = true, color = { 0.1234, 0.5, 0.2 } },
                    { index = 2, name = "Schwarz", price = 1500.4, color = { r = 0, g = 0, b = 0 },
                        isSelectable = false, desc = "Sonderlack" } } },
                { name = "empty", options = {} } },
            defaultConfigurations = { wheel = 1, baseColor = 1, bad = 0 },
            configurationSets = { { name = "Profi", isDefault = true, configurations = { wheel = 2 } }, { name = "" } } },
        { xmlFilename = "", price = 1 }, { xmlFilename = "c.xml" } } })
    lu.assertEquals(doc.savegameId, SG)
    lu.assertEquals(#doc.items, 2)
    local a, b = doc.items[1], doc.items[2]
    lu.assertEquals(a.xmlFilename, "a.xml")
    lu.assertEquals(b.price, 26000)
    lu.assertEquals(b.configurations, {})
    lu.assertEquals(a.dailyUpkeep, 120)
    lu.assertEquals(a.brand, "FENDT")
    lu.assertTrue(a.motorized)
    -- types sorted by name, empty types dropped, options sorted by index, invalid options dropped
    lu.assertEquals(#a.configurations, 2)
    lu.assertEquals(a.configurations[1].name, "baseColor")
    lu.assertEquals(a.configurations[1].title, "Farbe")
    local green, black = a.configurations[1].options[1], a.configurations[1].options[2]
    lu.assertEquals(green.color, { r = 0.123, g = 0.5, b = 0.2 })
    lu.assertTrue(green.isDefault)
    lu.assertTrue(green.isSelectable)
    lu.assertFalse(black.isSelectable)
    lu.assertEquals(black.price, 1500)
    lu.assertEquals(black.desc, "Sonderlack")
    local wheels = a.configurations[2].options
    lu.assertEquals(#wheels, 2)
    lu.assertEquals(wheels[1].name, "Trelleborg")
    lu.assertEquals(wheels[2].saveId, "MICH")
    lu.assertEquals(wheels[2].dependent, { { name = "frontloader", index = 2 } })
    lu.assertEquals(a.defaultConfigurations, { wheel = 1, baseColor = 1 })
    lu.assertEquals(a.configurationSets, { { name = "Profi", isDefault = true, configurations = { wheel = 2 } } })
    -- JSON: empty lists stay arrays, empty maps objects
    local json = RPSimJson.encode(doc)
    lu.assertStrContains(json, '"configurations":[]')
    lu.assertStrContains(json, '"defaultConfigurations":{}')
end

function T.TestMachineDealer:testDeliveryPointsNormalisation()
    local list = RPSimFarmFacts.buildDeliveryPoints({
        { seq = 3, x = 10.126, y = 101.5, z = -20, ry = 1.23456, farmlandId = 12, day = 40, dayTimeMs = 36000000.4 },
        { seq = 1, x = 1, y = 2, z = 3, ry = 0 },
        { seq = 0, x = 1, y = 2, z = 3, ry = 0 }, { seq = 2, x = 1, y = 2, z = "3", ry = 0 }, { x = 1, y = 2, z = 3, ry = 0 } })
    lu.assertEquals(list, {
        { seq = 1, x = 1, y = 2, z = 3, ry = 0 },
        { seq = 3, x = 10.13, y = 101.5, z = -20, ry = 1.235, farmlandId = 12, day = 40, dayTimeMs = 36000000 } })
    local doc = RPSimFarmFacts.build({ savegameId = SG, gameTime = 1, balance = 0, deliveryPoints = {} })
    lu.assertEquals(RPSimJson.encode(doc.deliveryPoints), "[]")
    lu.assertNil(RPSimFarmFacts.build({ savegameId = SG, gameTime = 1, balance = 0 }).deliveryPoints)
end

function T.TestMachineDealer:testStorePlacesNormalisation()
    lu.assertEquals(RPSimMarketContext.buildStorePlaces({ { x = 100.04, z = -5.06 }, { x = 1 }, { x = 0 / 0, z = 1 } }),
        { { x = 100, z = -5.1 } })
    local doc = RPSimMarketContext.build({ savegameId = SG, mapName = "M", storePlaces = { { x = 1, z = 2 } } })
    lu.assertEquals(doc.storePlaces, { { x = 1, z = 2 } })
    lu.assertNil(RPSimMarketContext.build({ savegameId = SG, mapName = "M" }).storePlaces)
end

function T.TestMachineDealer:testCatalogFileIsWrittenOnlyWithTheAdapterReader()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge.adapter.collectStoreVehicles = function() return { { xmlFilename = "a.xml", name = "A", price = 1 } } end
    bridge:collectStoreCatalog()
    lu.assertNil(fs.files[paths.storeCatalog])
    lu.assertEquals(paths.storeCatalog, "/ms/FS25_RPSim/export/store_catalog.json")

    local bridge2, fs2, _, paths2 = helpers.newBridge({ config = { storeCatalogMaxEntries = 1 } })
    bridge2.adapter.collectStoreVehicles = function() return {} end
    bridge2.adapter.collectStoreCatalogDetails = function()
        return { { xmlFilename = "b.xml", name = "B", price = 2 }, { xmlFilename = "a.xml", name = "A", price = 1,
            configurations = { { name = "baseColor", options = { { index = 1, name = "Rot", isDefault = true } } } } } }
    end
    fs2.dirs["/ms/FS25_RPSim/export/"] = true
    bridge2:collectStoreCatalog()
    local doc = RPSimJson.decode(fs2.files[paths2.storeCatalog])
    lu.assertEquals(doc.savegameId, SG)
    -- no cap (owner decision 2026-10-15: the whole catalog), sorted by xmlFilename; storeCatalogMaxEntries = 1 only
    -- cuts market_context.storeVehicles of R3-V1
    lu.assertEquals(#doc.items, 2)
    lu.assertEquals(doc.items[1].xmlFilename, "a.xml")
    lu.assertEquals(doc.items[2].xmlFilename, "b.xml")
    lu.assertEquals(doc.items[1].configurations[1].options[1].name, "Rot")
end

function T.TestMachineDealer:testCatalogSwitchAlsoStopsTheConfigurationFile()
    local bridge, fs, _, paths = helpers.newBridge({ config = { storeCatalogExport = false } })
    bridge.adapter.collectStoreVehicles = function() return {} end
    bridge.adapter.collectStoreCatalogDetails = function() return { { xmlFilename = "a.xml", price = 1 } } end
    fs.dirs["/ms/FS25_RPSim/export/"] = true
    bridge:collectStoreCatalog()
    lu.assertNil(fs.files[paths.storeCatalog])
end

function T.TestMachineDealer:testFailingReaderWritesNoFile()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge.adapter.collectStoreVehicles = function() return {} end
    bridge.adapter.collectStoreCatalogDetails = function() error("engine exploded") end
    fs.dirs["/ms/FS25_RPSim/export/"] = true
    bridge:collectStoreCatalog()
    lu.assertNil(fs.files[paths.storeCatalog])
    lu.assertEquals(helpers.countLogs("warning", "shop configurations"), 1)
end

-- Roadmap vehicle dealer RFH-O1 (owner decisions 2026-10-15): key "Lieferort hier festlegen" - only on own or leased
-- land, the last 20 points, exported and kept in the mod savegame
local function placeAdapter(places)
    local i = 0
    return { readPlayerPlace = function()
        i = i + 1
        return places[i] or places[#places]
    end, getText = function(_, key) return "txt:" .. key end, gameTime = 3 * 86400000 + 36000000 }
end

function T.TestMachineDealer:testTheKeySetsAPointOnOwnLandOnly()
    local bridge, fs, adapter, paths = helpers.newBridge({ adapter = placeAdapter({
        { x = -412.519, y = 98.2, z = 233.75, ry = 1.5708, farmlandId = 3, owned = true },
        { x = 1, y = 1, z = 1, ry = 0, farmlandId = 9, owned = false } }) })
    bridge:bootstrap()
    bridge.deliveryPointKeyEnabled = true
    lu.assertTrue(bridge:setDeliveryPointHere())
    lu.assertEquals(adapter.notifications[1], { text = "txt:rpsim_delivery_point_saved", level = "OK" })
    local doc = RPSimJson.decode(fs.files[paths.farmFacts])
    lu.assertEquals(doc.deliveryPoints, { { seq = 1, x = -412.52, y = 98.2, z = 233.75, ry = 1.571, farmlandId = 3,
        day = 3, dayTimeMs = 36000000 } })
    -- foreign land: nothing stored, hint
    local ok, err = bridge:setDeliveryPointHere()
    lu.assertFalse(ok)
    lu.assertEquals(err, "NOT_OWN_LAND")
    lu.assertEquals(adapter.notifications[2], { text = "txt:rpsim_delivery_point_foreign", level = "CRITICAL" })
    lu.assertEquals(#bridge.state.deliveryPoints.points, 1)
end

function T.TestMachineDealer:testOnlyTheLastPointsAreKeptAndTheBlockNeedsTheKey()
    local bridge, fs, _, paths = helpers.newBridge({ config = { deliveryPointsMax = 3 },
        adapter = placeAdapter({ { x = 1, y = 2, z = 3, ry = 0, farmlandId = 1, owned = true } }) })
    bridge:bootstrap()
    bridge:exportFarmFacts()
    lu.assertNil(RPSimJson.decode(fs.files[paths.farmFacts]).deliveryPoints) -- key not registered: block missing
    bridge.deliveryPointKeyEnabled = true
    for _ = 1, 5 do
        bridge:setDeliveryPointHere()
    end
    local seqs = {}
    for _, p in ipairs(RPSimJson.decode(fs.files[paths.farmFacts]).deliveryPoints) do
        seqs[#seqs + 1] = p.seq
    end
    lu.assertEquals(seqs, { 3, 4, 5 })
    -- without an adapter function nothing happens
    local b2 = helpers.newBridge()
    lu.assertEquals(select(2, b2:setDeliveryPointHere()), "NOT_SUPPORTED")
end

function T.TestMachineDealer:testDeliveryPointsSurviveTheSavegame()
    local state = RPSimProcessor.newState(RPSimConfig.new())
    state.deliveryPoints = { seq = 7, points = { { seq = 6, x = 1.5, y = 2, z = 3, ry = 0.5, farmlandId = 4, day = 2,
        dayTimeMs = 100 }, { seq = 7, x = 4, y = 5, z = 6, ry = 1, day = 3, dayTimeMs = 0 } } }
    local data = {}
    local xml = {}
    function xml:setString(k, v) data[k] = v end
    function xml:setInt(k, v) data[k] = v end
    function xml:setFloat(k, v) data[k] = v end
    function xml:getString(k) return data[k] ~= nil and tostring(data[k]) or nil end
    function xml:getInt(k) return data[k] end
    function xml:getFloat(k) return data[k] end
    RPSimPersistence.save(xml, state)
    local loaded = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load(xml, loaded)
    lu.assertEquals(loaded.deliveryPoints, state.deliveryPoints)
    -- an older savegame has no points
    local old = RPSimProcessor.newState(RPSimConfig.new())
    RPSimPersistence.load({ getString = function() return nil end, getInt = function() return nil end,
        getFloat = function() return nil end }, old)
    lu.assertEquals(old.deliveryPoints, { seq = 0, points = {} })
end

return T
