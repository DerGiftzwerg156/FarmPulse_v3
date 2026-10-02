-- Parser/validator for the common instruction envelope and its three types
-- (technical concept "Import-Schema").
RPSimInstructions = {}

RPSimInstructions.TYPES = { MONEY_TRANSACTION = true, PRICE_EVENT = true, FARMLAND_TRANSFER = true,
    NOTIFICATION = true, REPAIR_VEHICLE = true, -- NOTIFICATION: TODO T-21, REPAIR_VEHICLE: TODO T-22
    -- Roadmap V2 (R2-Q1): validated now, executed with R2-A0 (EMPLOYEE_ROSTER) and R2-F2 (PROMPT)
    EMPLOYEE_ROSTER = true, PROMPT = true,
    -- Roadmap V3 (R3-Q1): validated now, executed with R3-H3/H4/M3 (STORAGE_TRANSFER), R3-H5 (MISSION_CREATE),
    -- R3-V2 (VEHICLE_SPAWN) and R3-V3 (VEHICLE_REMOVE); until then acknowledged FAILED / NOT_SUPPORTED
    STORAGE_TRANSFER = true, MISSION_CREATE = true, VEHICLE_SPAWN = true, VEHICLE_REMOVE = true,
    -- Roadmap V3.1 (R31-Q1): validated now, executed with R31-A1 (FIELD_WORK), R31-A3 (ANIMAL_TRANSFER) and R31-D8
    -- (VEHICLE_FUEL); until then acknowledged FAILED / NOT_SUPPORTED
    FIELD_WORK = true, ANIMAL_TRANSFER = true, VEHICLE_FUEL = true }

RPSimInstructions.MONEY_REASONS = {
    CREDIT_DISBURSEMENT = true, CREDIT_INSTALLMENT = true, CREDIT_PENALTY = true, CREDIT_CALLBACK = true,
    -- Sondertilgung and its fee above the yearly free limit
    CREDIT_SPECIAL_REPAYMENT = true, CREDIT_PREPAYMENT_FEE = true,
    SALARY_PAYMENT = true, EMPLOYEE_EFFECT = true, SUBSIDY = true, STARTING_CAPITAL_ADJUSTMENT = true,
    FARMLAND_PURCHASE = true, FARMLAND_SALE = true, OTHER = true,
    -- TODO T-20 / T-22
    INSURANCE_PREMIUM = true, INSURANCE_PAYOUT = true, DAMAGE = true, WILDLIFE_COMPENSATION = true,
    VET_INVOICE = true, LIVESTOCK_PREMIUM = true, LEASE_PAYMENT = true, MAINTENANCE_FEE = true,
    -- Roadmap V2 (R2-Q1): tax office (E1), authority (E2), family (E3), clubs (E4), vanilla field purchase (D2)
    TAX_PAYMENT = true, TAX_REFUND = true, FINE = true, FAMILY = true, SPONSORING = true, COMPENSATION = true,
    -- "Schulungen": training of a machine operator
    TRAINING = true,
    -- Roadmap V3 (R3-Q1): lease income (L), goods trade with neighbours and the farm shop (H, M3), used vehicles (V),
    -- contract penalty of a forward contract (M2)
    LEASE_INCOME = true, GOODS_PURCHASE = true, GOODS_SALE = true, VEHICLE_PURCHASE = true, VEHICLE_SALE = true,
    CONTRACT_PENALTY = true,
    -- Roadmap V3.1 (R31-Q1): contractor (A1), machine rent (A2), livestock trade (A3), winter service (A4), area
    -- payment (B1), investment grant (B2), social insurance (B5), farm holidays (D6), cooperative shares (D7)
    CONTRACTOR_FEE = true, MACHINE_RENT = true, LIVESTOCK_PURCHASE = true, LIVESTOCK_SALE = true,
    WINTER_SERVICE = true, DIRECT_PAYMENT = true, INVESTMENT_GRANT = true, SOCIAL_INSURANCE = true,
    GUEST_INCOME = true, COOP_SHARES = true, COOP_DIVIDEND = true,
}

RPSimInstructions.PRICE_MODES = { MULTIPLIER = true, FIXED = true }
RPSimInstructions.DIRECTIONS = { TO_PLAYER = true, FROM_PLAYER = true }
-- FSBaseMission.INGAME_NOTIFICATION_* used by FS25_MarketDynamics (INFO, OK, CRITICAL)
RPSimInstructions.NOTIFICATION_LEVELS = { INFO = true, OK = true, CRITICAL = true }
-- Roadmap V2 R2-A0: status of an employee in EMPLOYEE_ROSTER (STRIKE: R2-A5) and the helper wage mode (R2-A1)
RPSimInstructions.EMPLOYEE_STATUSES = { ACTIVE = true, ON_LEAVE = true, STRIKE = true }
RPSimInstructions.HELPER_WAGE_MODES = { EMPLOYEES = true, VANILLA = true }
-- Roadmap V3 R3-H3/H4: IN = into the own silos (purchase), OUT = out of the own silos (sale)
RPSimInstructions.STORAGE_DIRECTIONS = { IN = true, OUT = true }
-- Roadmap V3.1 R31-A1: the works of the contractor (plow, cultivate, lime, sow, harvest)
RPSimInstructions.FIELD_WORKS = { PLOW = true, CULTIVATE = true, LIME = true, SOW = true, HARVEST = true }
-- Roadmap V3.1 R31-A3: IN = into the own husbandry (purchase), OUT = out of it (sale)
RPSimInstructions.ANIMAL_DIRECTIONS = { IN = true, OUT = true }

local function isNumber(v) return type(v) == "number" and v == v end
local function isNonEmptyString(v) return type(v) == "string" and v ~= "" end
local function isList(v)
    if type(v) ~= "table" then
        return false
    end
    local mt = getmetatable(v)
    if mt ~= nil and mt.__jsontype == "array" then
        return true
    end
    local n = 0
    for k, _ in pairs(v) do
        if type(k) ~= "number" then
            return false
        end
        n = n + 1
    end
    return n == #v
end

--- Roadmap V2 R2-A0: the complete employee list with the switches helperWageMode (R2-A1) and strictHelperLimit (R2-A3).
local function validateRoster(ins)
    if not isList(ins.employees) then
        return false, "employees must be an array"
    end
    for i, e in ipairs(ins.employees) do
        if type(e) ~= "table" or not isNumber(e.employeeId) then
            return false, string.format("employees[%d].employeeId must be a number", i)
        end
        if not isNonEmptyString(e.name) or not isNonEmptyString(e.role) then
            return false, string.format("employees[%d]: name and role are required", i)
        end
        if not RPSimInstructions.EMPLOYEE_STATUSES[e.status] then
            return false, string.format("employees[%d]: unknown status %s", i, tostring(e.status))
        end
        -- "Schulungen": optional list of finished trainings
        if e.trainings ~= nil and not isList(e.trainings) then
            return false, string.format("employees[%d].trainings must be an array", i)
        end
    end
    -- "Schulungen": optional object training -> array of FS25 shop categories
    if ins.trainingCategories ~= nil then
        if type(ins.trainingCategories) ~= "table" then
            return false, "trainingCategories must be an object"
        end
        for code, cats in pairs(ins.trainingCategories) do
            if type(code) ~= "string" or not isList(cats) then
                return false, "trainingCategories." .. tostring(code) .. " must be an array"
            end
        end
    end
    if not RPSimInstructions.HELPER_WAGE_MODES[ins.helperWageMode] then
        return false, "unknown helperWageMode " .. tostring(ins.helperWageMode)
    end
    if type(ins.strictHelperLimit) ~= "boolean" then
        return false, "strictHelperLimit must be a boolean"
    end
    return true
end

--- Roadmap V2 R2-F2: yes/no question shown in the game.
local function validatePrompt(ins)
    if not isNonEmptyString(ins.promptId) then
        return false, "promptId is required"
    end
    if not isNonEmptyString(ins.title) or not isNonEmptyString(ins.text) then
        return false, "title and text are required"
    end
    for _, f in ipairs({ "yesLabel", "noLabel" }) do
        if ins[f] ~= nil and not isNonEmptyString(ins[f]) then
            return false, f .. " must be a non-empty string"
        end
    end
    if not isNumber(ins.expiresGameTime) then
        return false, "expiresGameTime must be a number"
    end
    return true
end

--- Roadmap V3 R3-V2: used vehicle from the shop catalog. price > 0 is booked by the mod as -price with moneyReason.
-- Roadmap V3.1 R31-A2: price 0 = borrowed or demo machine, nothing is booked (the rent runs as MACHINE_RENT).
local function validateVehicleSpawn(ins)
    if not isNonEmptyString(ins.storeXmlFilename) then
        return false, "storeXmlFilename is required"
    end
    for _, f in ipairs({ "ageMonths", "operatingHours" }) do
        if not isNumber(ins[f]) or ins[f] < 0 then
            return false, f .. " must be >= 0"
        end
    end
    for _, f in ipairs({ "damage", "wear" }) do
        if not isNumber(ins[f]) or ins[f] < 0 or ins[f] > 1 then
            return false, f .. " must be between 0 and 1"
        end
    end
    if not isNumber(ins.price) or ins.price < 0 then
        return false, "price must be >= 0"
    end
    if not RPSimInstructions.MONEY_REASONS[ins.moneyReason] then
        return false, "unknown moneyReason " .. tostring(ins.moneyReason)
    end
    return true
end

--- Roadmap V3.1 R31-A1: end state of a field work on an own field; fruitType (FS25 fruit type name) only for SOW.
local function validateFieldWork(ins)
    if not isNumber(ins.farmlandId) then
        return false, "farmlandId must be a number"
    end
    if not RPSimInstructions.FIELD_WORKS[ins.work] then
        return false, "unknown work " .. tostring(ins.work)
    end
    if ins.work == "SOW" then
        if not isNonEmptyString(ins.fruitType) then
            return false, "fruitType is required for SOW"
        end
    elseif ins.fruitType ~= nil then
        return false, "fruitType is only allowed for SOW"
    end
    return true
end

--- Roadmap V3.1 R31-A3: animals of one subtype into / out of an own husbandry; age (months) optional.
local function validateAnimalTransfer(ins)
    if not isNonEmptyString(ins.husbandryUniqueId) then
        return false, "husbandryUniqueId is required"
    end
    if not isNonEmptyString(ins.subType) then
        return false, "subType is required"
    end
    if not isNumber(ins.count) or ins.count < 1 or ins.count ~= math.floor(ins.count) then
        return false, "count must be a whole number > 0"
    end
    if ins.age ~= nil and (not isNumber(ins.age) or ins.age < 0) then
        return false, "age must be >= 0"
    end
    if not RPSimInstructions.ANIMAL_DIRECTIONS[ins.direction] then
        return false, "unknown direction " .. tostring(ins.direction)
    end
    return true
end

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
    elseif ins.type == "REPAIR_VEHICLE" then
        if not isNonEmptyString(ins.vehicleId) then
            return false, "vehicleId is required"
        end
        -- Roadmap V2 R2-A6: partial repair down to this damage (0..1); missing = full repair
        if ins.targetDamage ~= nil and (not isNumber(ins.targetDamage) or ins.targetDamage < 0 or ins.targetDamage > 1) then
            return false, "targetDamage must be between 0 and 1"
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
    elseif ins.type == "EMPLOYEE_ROSTER" then
        return validateRoster(ins)
    elseif ins.type == "PROMPT" then
        return validatePrompt(ins)
    elseif ins.type == "STORAGE_TRANSFER" then
        -- Roadmap V3 R3-H3/H4/M3: amount in liters, moved into / out of the own silos
        if not RPSimInstructions.STORAGE_DIRECTIONS[ins.direction] then
            return false, "unknown direction " .. tostring(ins.direction)
        end
        if not isNonEmptyString(ins.fillType) then
            return false, "fillType is required"
        end
        if not isNumber(ins.amount) or ins.amount <= 0 then
            return false, "amount must be > 0"
        end
    elseif ins.type == "MISSION_CREATE" then
        -- Roadmap V3 R3-H5: real contract of the game on the field of an NPC farmland
        if not isNonEmptyString(ins.missionType) then
            return false, "missionType is required"
        end
        if not isNumber(ins.farmlandId) then
            return false, "farmlandId must be a number"
        end
    elseif ins.type == "VEHICLE_SPAWN" then
        return validateVehicleSpawn(ins)
    elseif ins.type == "VEHICLE_REMOVE" then
        -- Roadmap V3 R3-V3: own vehicle by its uniqueId (as in assets.vehicles)
        if not isNonEmptyString(ins.vehicleId) then
            return false, "vehicleId is required"
        end
    elseif ins.type == "FIELD_WORK" then
        return validateFieldWork(ins)
    elseif ins.type == "ANIMAL_TRANSFER" then
        return validateAnimalTransfer(ins)
    elseif ins.type == "VEHICLE_FUEL" then
        -- Roadmap V3.1 R31-D8: diesel taken out of an own vehicle (uniqueId as in assets.vehicles), only negative
        if not isNonEmptyString(ins.vehicleId) then
            return false, "vehicleId is required"
        end
        if not isNumber(ins.delta) or ins.delta >= 0 then
            return false, "delta must be < 0"
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
