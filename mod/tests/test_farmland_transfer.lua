local lu = require("luaunit")
local helpers = require("helpers")
local T = {}
T.TestFarmlandTransfer = {}

local SG = "map_erlengrund_1_20260101"

local function batch(direction, failMoneyValidation)
    return {
        { instructionId = "ins_0310", batchId = "neg_7", type = "FARMLAND_TRANSFER", farmlandId = 13,
            direction = direction, price = 47500 },
        { instructionId = "ins_0311", batchId = "neg_7", type = "MONEY_TRANSACTION",
            amount = direction == "TO_PLAYER" and -47500 or 47500,
            reason = failMoneyValidation and "NOT_A_REASON"
                or (direction == "TO_PLAYER" and "FARMLAND_PURCHASE" or "FARMLAND_SALE") },
    }
end

function T.TestFarmlandTransfer:testEnvelopeParsing()
    lu.assertTrue(RPSimInstructions.validate(batch("TO_PLAYER")[1]))
    lu.assertTrue(RPSimInstructions.validate(batch("FROM_PLAYER")[1]))
    lu.assertFalse(RPSimInstructions.validate({ instructionId = "x", type = "FARMLAND_TRANSFER", farmlandId = 1,
        direction = "SIDEWAYS" }))
    lu.assertFalse(RPSimInstructions.validate({ instructionId = "x", type = "FARMLAND_TRANSFER", farmlandId = "1",
        direction = "TO_PLAYER" }))
end

function T.TestFarmlandTransfer:testPurchaseAppliesTransferAndMoneyTogetherAndReExportsContext()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:onSavegameLoaded()
    fs.files[paths.marketContext] = "OLD"
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = batch("TO_PLAYER") })
    local res = bridge:pollInstructions()
    lu.assertEquals(res.applied, 2)
    lu.assertTrue(res.marketContextDirty)
    lu.assertEquals(adapter.transfers[1], { farmlandId = 13, direction = "TO_PLAYER" })
    lu.assertEquals(adapter.moneyLog[1].reason, "FARMLAND_PURCHASE")
    local ctx = RPSimJson.decode(fs.files[paths.marketContext])
    lu.assertEquals(ctx.farmlands[2].ownerFarmId, 1)
end

function T.TestFarmlandTransfer:testSale()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = batch("FROM_PLAYER") })
    bridge:pollInstructions()
    lu.assertEquals(adapter.transfers[1].direction, "FROM_PLAYER")
    lu.assertEquals(adapter.balance, 245000 + 47500)
end

function T.TestFarmlandTransfer:testBatchIsAllOrNothing()
    local bridge, fs, adapter, paths = helpers.newBridge()
    bridge:bootstrap()
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = batch("TO_PLAYER", true) })
    local res = bridge:pollInstructions()
    lu.assertEquals(res.applied, 0)
    lu.assertEquals(res.rejected, 2)
    lu.assertEquals(#adapter.transfers, 0)
    lu.assertEquals(#adapter.moneyLog, 0)
end

function T.TestFarmlandTransfer:testNoReExportWithoutTransfer()
    local bridge, fs, _, paths = helpers.newBridge()
    bridge:onSavegameLoaded()
    fs.files[paths.marketContext] = "OLD"
    helpers.writeInstructions(fs, paths, { savegameId = SG, instructions = { { instructionId = "m", type = "MONEY_TRANSACTION",
        amount = 1, reason = "OTHER" } } })
    bridge:pollInstructions()
    lu.assertEquals(fs.files[paths.marketContext], "OLD")
end

return T
