-- Thin logging wrapper: uses FS25 `Logging` when available, `print` otherwise (tests).
RPSimLog = { prefix = "[FS25_RPSim] ", sink = nil }

local function emit(level, fmt, ...)
    local msg = RPSimLog.prefix .. string.format(fmt, ...)
    if RPSimLog.sink ~= nil then
        RPSimLog.sink(level, msg)
        return
    end
    -- luacheck: globals Logging
    if Logging ~= nil then
        if level == "warning" and Logging.warning then
            Logging.warning(msg)
            return
        elseif level == "error" and Logging.error then
            Logging.error(msg)
            return
        elseif Logging.info then
            Logging.info(msg)
            return
        end
    end
    print(level:upper() .. " " .. msg)
end

function RPSimLog.info(fmt, ...) emit("info", fmt, ...) end
function RPSimLog.warning(fmt, ...) emit("warning", fmt, ...) end
function RPSimLog.error(fmt, ...) emit("error", fmt, ...) end
