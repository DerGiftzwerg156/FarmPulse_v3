-- Minimal JSON encoder/decoder for the FS25 Lua 5.1 runtime (FS25 ships no JSON library).
-- Encoder sorts object keys so exports are deterministic and diff-friendly.
RPSimJson = {}

local ARRAY_MT = { __jsontype = "array" }

--- Marks a table as JSON array (needed for empty arrays).
function RPSimJson.array(t)
    return setmetatable(t or {}, ARRAY_MT)
end

local function isArray(t)
    local mt = getmetatable(t)
    if mt and mt.__jsontype == "array" then
        return true
    end
    local n = 0
    for k, _ in pairs(t) do
        if type(k) ~= "number" or k < 1 or math.floor(k) ~= k then
            return false
        end
        n = n + 1
    end
    if n == 0 then
        return false
    end
    for i = 1, n do
        if t[i] == nil then
            return false
        end
    end
    return true
end

local escapes = {
    ['"'] = '\\"', ["\\"] = "\\\\", ["\b"] = "\\b", ["\f"] = "\\f",
    ["\n"] = "\\n", ["\r"] = "\\r", ["\t"] = "\\t",
}

local function encodeString(s)
    return '"' .. s:gsub('[%c"\\]', function(c)
        return escapes[c] or string.format("\\u%04x", c:byte())
    end) .. '"'
end

local function encodeNumber(n)
    if n ~= n or n == math.huge or n == -math.huge then
        error("cannot encode non-finite number")
    end
    if math.floor(n) == n and math.abs(n) < 1e15 then
        return string.format("%d", n)
    end
    return string.format("%.14g", n)
end

local encodeValue

local function encodeTable(t, stack)
    if stack[t] then
        error("circular reference in JSON encode")
    end
    stack[t] = true
    local parts = {}
    if isArray(t) then
        for i = 1, #t do
            parts[#parts + 1] = encodeValue(t[i], stack)
        end
        stack[t] = nil
        return "[" .. table.concat(parts, ",") .. "]"
    end
    local keys = {}
    for k, _ in pairs(t) do
        if type(k) ~= "string" then
            error("JSON object keys must be strings")
        end
        keys[#keys + 1] = k
    end
    table.sort(keys)
    for _, k in ipairs(keys) do
        parts[#parts + 1] = encodeString(k) .. ":" .. encodeValue(t[k], stack)
    end
    stack[t] = nil
    return "{" .. table.concat(parts, ",") .. "}"
end

encodeValue = function(v, stack)
    local tv = type(v)
    if tv == "nil" then
        return "null"
    elseif tv == "boolean" then
        return v and "true" or "false"
    elseif tv == "number" then
        return encodeNumber(v)
    elseif tv == "string" then
        return encodeString(v)
    elseif tv == "table" then
        return encodeTable(v, stack)
    end
    error("cannot encode value of type " .. tv)
end

function RPSimJson.encode(v)
    return encodeValue(v, {})
end

-- ---------------------------------------------------------------- decoder

local function decodeError(_, pos, msg)
    error(string.format("JSON decode error at %d: %s", pos, msg), 0)
end

local function skipWs(str, pos)
    local _, e = str:find("^[ \n\r\t]*", pos)
    return e + 1
end

local decodeAt

local function decodeStringAt(str, pos)
    local buf = {}
    local i = pos + 1
    while true do
        local c = str:sub(i, i)
        if c == "" then
            decodeError(str, i, "unterminated string")
        elseif c == '"' then
            return table.concat(buf), i + 1
        elseif c == "\\" then
            local n = str:sub(i + 1, i + 1)
            local map = { ['"'] = '"', ["\\"] = "\\", ["/"] = "/", b = "\b", f = "\f", n = "\n", r = "\r", t = "\t" }
            if map[n] then
                buf[#buf + 1] = map[n]
                i = i + 2
            elseif n == "u" then
                local hex = str:sub(i + 2, i + 5)
                local code = tonumber(hex, 16)
                if not code then
                    decodeError(str, i, "invalid unicode escape")
                end
                if code < 0x80 then
                    buf[#buf + 1] = string.char(code)
                elseif code < 0x800 then
                    buf[#buf + 1] = string.char(0xC0 + math.floor(code / 0x40), 0x80 + code % 0x40)
                else
                    buf[#buf + 1] = string.char(0xE0 + math.floor(code / 0x1000),
                        0x80 + math.floor(code / 0x40) % 0x40, 0x80 + code % 0x40)
                end
                i = i + 6
            else
                decodeError(str, i, "invalid escape")
            end
        else
            buf[#buf + 1] = c
            i = i + 1
        end
    end
end

decodeAt = function(str, pos)
    pos = skipWs(str, pos)
    local c = str:sub(pos, pos)
    if c == "{" then
        local obj = {}
        pos = skipWs(str, pos + 1)
        if str:sub(pos, pos) == "}" then
            return obj, pos + 1
        end
        while true do
            pos = skipWs(str, pos)
            if str:sub(pos, pos) ~= '"' then
                decodeError(str, pos, "expected string key")
            end
            local key
            key, pos = decodeStringAt(str, pos)
            pos = skipWs(str, pos)
            if str:sub(pos, pos) ~= ":" then
                decodeError(str, pos, "expected ':'")
            end
            local val
            val, pos = decodeAt(str, pos + 1)
            obj[key] = val
            pos = skipWs(str, pos)
            local d = str:sub(pos, pos)
            if d == "}" then
                return obj, pos + 1
            elseif d ~= "," then
                decodeError(str, pos, "expected ',' or '}'")
            end
            pos = pos + 1
        end
    elseif c == "[" then
        local arr = RPSimJson.array({})
        pos = skipWs(str, pos + 1)
        if str:sub(pos, pos) == "]" then
            return arr, pos + 1
        end
        while true do
            local val
            val, pos = decodeAt(str, pos)
            arr[#arr + 1] = val
            pos = skipWs(str, pos)
            local d = str:sub(pos, pos)
            if d == "]" then
                return arr, pos + 1
            elseif d ~= "," then
                decodeError(str, pos, "expected ',' or ']'")
            end
            pos = pos + 1
        end
    elseif c == '"' then
        return decodeStringAt(str, pos)
    elseif str:sub(pos, pos + 3) == "true" then
        return true, pos + 4
    elseif str:sub(pos, pos + 4) == "false" then
        return false, pos + 5
    elseif str:sub(pos, pos + 3) == "null" then
        return nil, pos + 4
    else
        local num = str:match("^-?%d+%.?%d*[eE]?[-+]?%d*", pos)
        if num and num ~= "" and tonumber(num) then
            return tonumber(num), pos + #num
        end
        decodeError(str, pos, "unexpected character '" .. c .. "'")
    end
end

--- Decodes a JSON string. Raises an error on malformed input (use RPSimJson.tryDecode for defensive parsing).
function RPSimJson.decode(str)
    if type(str) ~= "string" then
        error("JSON decode expects a string", 0)
    end
    local value, pos = decodeAt(str, 1)
    pos = skipWs(str, pos)
    if pos <= #str then
        decodeError(str, pos, "trailing garbage")
    end
    return value
end

--- Defensive decode: returns value or nil, errorMessage. Never raises.
function RPSimJson.tryDecode(str)
    if str == nil or str == "" then
        return nil, "empty input"
    end
    local ok, result = pcall(RPSimJson.decode, str)
    if ok then
        return result, nil
    end
    return nil, tostring(result)
end
