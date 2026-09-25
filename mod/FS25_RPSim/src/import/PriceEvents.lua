-- Registry of active price events. FIXED contracts take precedence over MULTIPLIER events for the same
-- sell point / fill type (technical concept "Import-Schema").
RPSimPriceEvents = {}
RPSimPriceEvents.__index = RPSimPriceEvents

function RPSimPriceEvents.new(cfg)
    local self = setmetatable({}, RPSimPriceEvents)
    self.cfg = cfg or RPSimConfig.new()
    self.events = {} -- ordered list
    return self
end

local function key(sellPoint, fillType)
    return tostring(sellPoint) .. "|" .. tostring(fillType)
end

--- Adds an event from a validated PRICE_EVENT instruction, starting at gameTimeStart.
function RPSimPriceEvents:addFromInstruction(ins, gameTimeStart)
    local ev = {
        id = ins.instructionId, priceMode = ins.priceMode, fillType = ins.fillType, sellPoint = ins.sellPoint,
        gameTimeStart = gameTimeStart,
    }
    if ins.priceMode == "MULTIPLIER" then
        ev.peakMultiplier = ins.peakMultiplier
        ev.rampUpHours = ins.rampUpHours
        ev.holdHours = ins.holdHours
        ev.decayHours = ins.decayHours
    else
        ev.fixedPrice = ins.fixedPrice
        ev.maxQuantity = ins.maxQuantity
        ev.deadlineGameTime = ins.deadlineGameTime
        ev.deliveredQuantity = 0
    end
    self:add(ev)
    return ev
end

function RPSimPriceEvents:add(ev)
    for _, e in ipairs(self.events) do
        if e.id == ev.id then
            return
        end
    end
    self.events[#self.events + 1] = ev
end

local function fixedActive(ev, gameTime)
    return ev.priceMode == "FIXED" and gameTime >= ev.gameTimeStart and gameTime < ev.deadlineGameTime
        and ev.deliveredQuantity < ev.maxQuantity
end

--- Returns the active FIXED contract for sellPoint/fillType or nil.
function RPSimPriceEvents:activeContract(sellPoint, fillType, gameTime)
    local k = key(sellPoint, fillType)
    for _, ev in ipairs(self.events) do
        if key(ev.sellPoint, ev.fillType) == k and fixedActive(ev, gameTime) then
            return ev
        end
    end
    return nil
end

--- Effective price per liter for a sell point given the FS base price per liter.
function RPSimPriceEvents:effectivePrice(sellPoint, fillType, basePricePerLiter, gameTime)
    local contract = self:activeContract(sellPoint, fillType, gameTime)
    if contract ~= nil then
        -- FIXED wins immediately and in full height.
        return contract.fixedPrice / self.cfg.pricePerLiters
    end
    local k = key(sellPoint, fillType)
    local factor = 1.0
    for _, ev in ipairs(self.events) do
        if ev.priceMode == "MULTIPLIER" and key(ev.sellPoint, ev.fillType) == k then
            factor = factor * RPSimPriceEventMath.multiplierAt(ev, gameTime)
        end
    end
    return basePricePerLiter * factor
end

--- Records a sale for quantity tracking of FIXED contracts. Returns liters counted towards a contract.
function RPSimPriceEvents:recordSale(sellPoint, fillType, liters, gameTime)
    local contract = self:activeContract(sellPoint, fillType, gameTime)
    if contract == nil or liters <= 0 then
        return 0
    end
    local remaining = contract.maxQuantity - contract.deliveredQuantity
    local counted = math.min(liters, remaining)
    contract.deliveredQuantity = contract.deliveredQuantity + counted
    return counted
end

--- Removes ended events. Returns contract reports for ended FIXED contracts:
-- { instructionId, deliveredQuantity, maxQuantity, endReason = DEADLINE_REACHED | MAX_QUANTITY_REACHED }
function RPSimPriceEvents:collectEnded(gameTime)
    local reports, keep = {}, {}
    for _, ev in ipairs(self.events) do
        if ev.priceMode == "FIXED" then
            local reason
            if ev.deliveredQuantity >= ev.maxQuantity then
                reason = "MAX_QUANTITY_REACHED"
            elseif gameTime >= ev.deadlineGameTime then
                reason = "DEADLINE_REACHED"
            end
            if reason ~= nil then
                reports[#reports + 1] = { instructionId = ev.id, deliveredQuantity = math.floor(ev.deliveredQuantity + 0.5),
                    maxQuantity = ev.maxQuantity, endReason = reason, endedAtGameTime = gameTime }
            else
                keep[#keep + 1] = ev
            end
        elseif not RPSimPriceEventMath.isExpired(ev, gameTime) then
            keep[#keep + 1] = ev
        end
    end
    self.events = keep
    return reports
end
