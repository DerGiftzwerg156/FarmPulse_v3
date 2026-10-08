-- Roadmap V3.2 section Q against the fake engine: the milk sorts in the storage of a husbandry
-- (husbandries[].storage[]) and HUSBANDRY_TRANSFER (R32-Q1) with its failure codes and the booking back of a partly
-- taken amount.
local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestRoadmapV32 = {}

function T.TestRoadmapV32:setUp()
    helpers.loadGameModules()
end

--- One own cow husbandry with milk (fill type 3 = MILK) and goat milk (4 = GOATMILK, empty, no capacity) in its
-- storage. The loading station takes at most `loadable` litres (nil = everything), like a station that reaches only
-- part of the storage; removeHusbandryFillLevel returns the amount NOT taken (PlaceableHusbandry.lua).
local function milkGame(opts)
    opts = opts or {}
    local stable = { spec_husbandryAnimals = { animalType = { name = "cow", subTypes = {} } },
        levels = { [3] = opts.milk or 12000.4, [4] = 0 }, capacity = { [3] = 50000, [4] = 0 }, calls = {} }
    if not opts.noMilk then
        stable.spec_husbandryMilk = { fillTypes = { 3, 4 } }
    end
    function stable.getUniqueId() return "hus_00001" end
    function stable.getOwnerFarmId() return 1 end
    function stable.getSellPrice() return 90000 end
    function stable.getClusters() return {} end
    function stable.getNumOfFreeAnimalSlots() return 0 end
    function stable:getHusbandryFillLevel(index, farmId)
        self.calls[#self.calls + 1] = { "level", index, farmId }
        return self.levels[index] or 0
    end
    function stable:getHusbandryCapacity(index) return self.capacity[index] or 0 end
    function stable:removeHusbandryFillLevel(farmId, delta, index)
        self.calls[#self.calls + 1] = { "remove", farmId, delta, index }
        local taken = math.min(delta, self.levels[index] or 0, opts.loadable or math.huge)
        self.levels[index] = self.levels[index] - taken
        return delta - taken
    end
    function stable:addHusbandryFillLevelFromTool(farmId, delta, index)
        self.calls[#self.calls + 1] = { "add", farmId, delta, index }
        self.levels[index] = self.levels[index] + delta
        return delta
    end
    local game = helpers.fakeGame({ fillTypes = { [1] = "WHEAT", [2] = "BARLEY", [3] = "MILK", [4] = "GOATMILK" },
        placeables = { stable } })
    g_currentMission.animalSystem = { getSubTypeByIndex = function() return nil end }
    game.stable = stable
    return game
end

local function transfer(extra)
    local ins = { instructionId = "ht", type = "HUSBANDRY_TRANSFER", husbandryUniqueId = "hus_00001",
        fillType = "MILK", amount = 5000 }
    for k, v in pairs(extra or {}) do ins[k] = v end
    return ins
end

function T.TestRoadmapV32:testMilkStorageIsExportedLikeTradeStorage()
    milkGame()
    local doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts())
    -- whole litres, the empty goat milk without capacity is left out
    lu.assertEquals(doc.husbandries[1].storage, { { fillType = "MILK", amount = 12000, capacity = 50000 } })
end

function T.TestRoadmapV32:testHusbandryWithoutMilkHasNoStorage()
    milkGame({ noMilk = true })
    local doc = RPSimFarmFacts.build(RPSimGameAdapter.new():collectFarmFacts())
    lu.assertNil(doc.husbandries[1].storage)
end

function T.TestRoadmapV32:testStorageNormalisation()
    local list = RPSimFarmFacts.buildHusbandries({ { husbandryUniqueId = "hus_1", health = 80, food = 0.5,
        storage = { { fillType = "MILK", amount = 99.6, capacity = 1000.2 }, { fillType = "", amount = 1, capacity = 1 },
            { fillType = "GOATMILK", amount = -3, capacity = 0 }, { fillType = "BUFFALOMILK", amount = "1", capacity = 2 },
            { fillType = "COWMILK2", amount = 0 / 0, capacity = 5 }, { fillType = "ALPHAMILK", amount = 0, capacity = 10 } } } })
    lu.assertEquals(list[1].storage, { { fillType = "ALPHAMILK", amount = 0, capacity = 10 },
        { fillType = "MILK", amount = 100, capacity = 1000 } })
    list = RPSimFarmFacts.buildHusbandries({ { husbandryUniqueId = "hus_1", health = 80, food = 0.5, storage = "x" } })
    lu.assertNil(list[1].storage)
end

function T.TestRoadmapV32:testMilkIsTakenOutOfTheHusbandry()
    local game = milkGame()
    lu.assertTrue(RPSimGameAdapter.new():husbandryTransfer(transfer()))
    lu.assertAlmostEquals(game.stable.levels[3], 7000.4, 0.001)
    lu.assertEquals(game.stable.calls[#game.stable.calls], { "remove", 1, 5000, 3 })
end

function T.TestRoadmapV32:testFailureCodes()
    local game = milkGame()
    local adapter = RPSimGameAdapter.new()
    lu.assertEquals(select(2, adapter:husbandryTransfer(transfer({ husbandryUniqueId = "hus_x" }))),
        "HUSBANDRY_NOT_FOUND")
    lu.assertEquals(select(2, adapter:husbandryTransfer(transfer({ fillType = "UNICORNMILK" }))), "UNKNOWN_FILLTYPE")
    lu.assertEquals(select(2, adapter:husbandryTransfer(transfer({ fillType = "WHEAT" }))), "WRONG_FILLTYPE")
    lu.assertEquals(select(2, adapter:husbandryTransfer(transfer({ amount = 12001 }))), "INSUFFICIENT_STOCK")
    lu.assertAlmostEquals(game.stable.levels[3], 12000.4, 0.001, "nothing taken")
    for _, c in ipairs(game.stable.calls) do
        lu.assertNotEquals(c[1], "remove")
    end
end

function T.TestRoadmapV32:testHusbandryWithoutMilkSpecializationIsTheWrongFillType()
    milkGame({ noMilk = true })
    lu.assertEquals(select(2, RPSimGameAdapter.new():husbandryTransfer(transfer())), "WRONG_FILLTYPE")
end

function T.TestRoadmapV32:testPartlyTakenAmountIsBookedBack()
    -- the loading station only reaches 3000 l: the rest 2000 l comes back, the 3000 l taken are added again
    local game = milkGame({ loadable = 3000 })
    lu.assertEquals(select(2, RPSimGameAdapter.new():husbandryTransfer(transfer())), "INSUFFICIENT_STOCK")
    lu.assertAlmostEquals(game.stable.levels[3], 12000.4, 0.001)
    lu.assertEquals(game.stable.calls[#game.stable.calls], { "add", 1, 3000, 3 })
end

function T.TestRoadmapV32:testNoLoadingStationTakesNothingAndBooksNothingBack()
    -- without a loading station the game returns the whole delta (PlaceableHusbandry:removeHusbandryFillLevel)
    local game = milkGame({ loadable = 0 })
    lu.assertEquals(select(2, RPSimGameAdapter.new():husbandryTransfer(transfer())), "INSUFFICIENT_STOCK")
    lu.assertEquals(game.stable.calls[#game.stable.calls][1], "remove")
end

function T.TestRoadmapV32:testProcessorRunsTheBridgeAction()
    local game = milkGame()
    local state = RPSimProcessor.newState(RPSimConfig.new())
    local adapter = RPSimGameAdapter.new()
    RPSimProcessor.process(state, { savegameId = "sg", instructions = { transfer() } },
        { savegameId = "sg", gameTime = 1000,
            actions = { husbandryTransfer = function(ins) return adapter:husbandryTransfer(ins) end } })
    lu.assertEquals(state.processed.ht.status, "APPLIED")
    lu.assertAlmostEquals(game.stable.levels[3], 7000.4, 0.001)
end

return T
