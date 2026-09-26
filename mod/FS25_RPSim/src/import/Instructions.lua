-- Parser/validator for the common instruction envelope and its three types
-- (technical concept "Import-Schema").
RPSimInstructions = {}

RPSimInstructions.TYPES = { MONEY_TRANSACTION = true, PRICE_EVENT = true, FARMLAND_TRANSFER = true,
    NOTIFICATION = true } -- NOTIFICATION: TODO T-21

RPSimInstructions.MONEY_REASONS = {
    CREDIT_DISBURSEMENT = true, CREDIT_INSTALLMENT = true, CREDIT_PENALTY = true, CREDIT_CALLBACK = true,
    SALARY_PAYMENT = true, EMPLOYEE_EFFECT = true, SUBSIDY = true, STARTING_CAPITAL_ADJUSTMENT = true,
    FARMLAND_PURCHASE = true, FARMLAND_SALE = true, OTHER = true,
    -- TODO T-20 / T-22
    INSURANCE_PREMIUM = true, INSURANCE_PAYOUT = true, DAMAGE = true, WILDLIFE_COMPENSATION = true,
    VET_INVOICE = true, LIVESTOCK_PREMIUM = true, LEASE_PAYMENT = true, MAINTENANCE_FEE = true,
}

RPSimInstructions.PRICE_MODES = { MULTIPLIER = true, FIXED = true }
RPSimInstructions.DIRECTIONS = { TO_PLAYER = true, FROM_PLAYER = true }
-- FSBaseMission.INGAME_NOTIFICATION_* used by FS25_MarketDynamics (INFO, OK, CRITICAL)
RPSimInstructions.NOTIFICATION_LEVELS = { INFO = true, OK = true, CRITICAL = true }

local function isNumber(v) return type(v) == "number" and v == v end
local function isNonEmptyString(v) return type(v) == "string" and v ~= "" end

--- Validates one instruction. Returns true or false, reason.
function RPSimInstructions.validate(ins)
    if type(ins) ~= "table" then
        return false, "instruction is not an object"
    end
    if not isNonEmptyString(ins.instructionId) then
        return false, "missing instructionId"
    end
    if not RPSimInstructions.TYPES[ins.type] then
        return false, "unknown type " .. tostring(ins.type)
    end
    if ins.gameTimeEarliest ~= nil and not isNumber(ins.gameTimeEarliest) then
        return false, "gameTimeEarliest must be a number"
    end
    if ins.type == "MONEY_TRANSACTION" then
        if not isNumber(ins.amount) then
            return false, "amount must be a number"
        end
        if not RPSimInstructions.MONEY_REASONS[ins.reason] then
            return false, "unknown reason " .. tostring(ins.reason)
        end
    elseif ins.type == "PRICE_EVENT" then
        if not RPSimInstructions.PRICE_MODES[ins.priceMode] then
            return false, "unknown priceMode " .. tostring(ins.priceMode)
        end
        if not isNonEmptyString(ins.fillType) or not isNonEmptyString(ins.sellPoint) then
            return false, "fillType and sellPoint are required"
        end
        if ins.priceMode == "MULTIPLIER" then
            if not isNumber(ins.peakMultiplier) or ins.peakMultiplier <= 0 then
                return false, "peakMultiplier must be > 0"
            end
            for _, f in ipairs({ "rampUpHours", "holdHours", "decayHours" }) do
                if not isNumber(ins[f]) or ins[f] < 0 then
                    return false, f .. " must be >= 0"
                end
            end
        else
            if not isNumber(ins.fixedPrice) or ins.fixedPrice <= 0 then
                return false, "fixedPrice must be > 0"
            end
            if not isNumber(ins.maxQuantity) or ins.maxQuantity <= 0 then
                return false, "maxQuantity must be > 0"
            end
            if not isNumber(ins.deadlineGameTime) then
                return false, "deadlineGameTime must be a number"
            end
        end
    elseif ins.type == "FARMLAND_TRANSFER" then
        if not isNumber(ins.farmlandId) then
            return false, "farmlandId must be a number"
        end
        if not RPSimInstructions.DIRECTIONS[ins.direction] then
            return false, "unknown direction " .. tostring(ins.direction)
        end
        if ins.price ~= nil and not isNumber(ins.price) then
            return false, "price must be a number"
        end
    elseif ins.type == "NOTIFICATION" then
        if not isNonEmptyString(ins.text) then
            return false, "text is required"
        end
        if ins.level ~= nil and not RPSimInstructions.NOTIFICATION_LEVELS[ins.level] then
            return false, "unknown level " .. tostring(ins.level)
        end
        if ins.expiresAtGameTime ~= nil and not isNumber(ins.expiresAtGameTime) then
            return false, "expiresAtGameTime must be a number"
        end
    end
    return true
end

--- Funds check for a batch (T-03): the net money change of the batch must not push the balance below 0.
-- Credits in the same batch (e.g. a farmland sale) count against its debits. Returns ok, err.
function RPSimInstructions.checkFunds(balance, items)
    local net = 0
    for _, ins in ipairs(items or {}) do
        if type(ins) == "table" and ins.type == "MONEY_TRANSACTION" and isNumber(ins.amount) then
            net = net + ins.amount
        end
    end
    if net < 0 and (balance or 0) + net < 0 then
        return false, "INSUFFICIENT_FUNDS"
    end
    return true
end

--- Parses the instructions.json document text. Returns doc or nil, err (never raises).
-- Document: { savegameId = "...", instructions = [ envelope... ] }
function RPSimInstructions.parseDocument(text)
    local doc, err = RPSimJson.tryDecode(text)
    if doc == nil then
        return nil, err
    end
    local mt = type(doc) == "table" and getmetatable(doc) or nil
    if type(doc) ~= "table" or (mt ~= nil and mt.__jsontype == "array") then
        return nil, "document is not an object"
    end
    if doc.instructions == nil then
        doc.instructions = {}
    end
    if type(doc.instructions) ~= "table" then
        return nil, "instructions is not an array"
    end
    return doc, nil
end

--- Groups instructions into batches (same batchId => processed together, all-or-nothing).
-- Order of first appearance is preserved.
function RPSimInstructions.groupBatches(instructions)
    local batches, index = {}, {}
    for _, ins in ipairs(instructions or {}) do
        local key = (type(ins) == "table" and (ins.batchId or ins.instructionId)) or tostring(#batches + 1)
        local b = index[key]
        if b == nil then
            b = { key = key, items = {} }
            index[key] = b
            batches[#batches + 1] = b
        end
        b.items[#b.items + 1] = ins
    end
    return batches
end
