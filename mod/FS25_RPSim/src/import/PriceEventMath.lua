-- Deterministic price-event curve: ramp-up -> hold -> decay (technical concept "Persistenz im Mod":
-- mod and backend compute the same curve from the same parameters + gameTime).
RPSimPriceEventMath = {}

local HOUR = 60 * 60 * 1000

--- Multiplier at gameTime for a MULTIPLIER event { gameTimeStart, peakMultiplier, rampUpHours, holdHours, decayHours }.
function RPSimPriceEventMath.multiplierAt(ev, gameTime)
    local t = (gameTime - ev.gameTimeStart) / HOUR
    local peak = ev.peakMultiplier
    local ramp, hold, decay = ev.rampUpHours, ev.holdHours, ev.decayHours
    if t < 0 then
        return 1.0
    end
    if t < ramp then
        return 1.0 + (peak - 1.0) * (t / ramp)
    end
    if t < ramp + hold then
        return peak
    end
    if t < ramp + hold + decay then
        return peak - (peak - 1.0) * ((t - ramp - hold) / decay)
    end
    return 1.0
end

--- End of a MULTIPLIER event in game time.
function RPSimPriceEventMath.endTime(ev)
    return ev.gameTimeStart + (ev.rampUpHours + ev.holdHours + ev.decayHours) * HOUR
end

function RPSimPriceEventMath.isExpired(ev, gameTime)
    return gameTime >= RPSimPriceEventMath.endTime(ev)
end
