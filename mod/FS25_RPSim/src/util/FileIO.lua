-- File access with injectable backend (real io in FS25, fakes in tests).
-- Every bridge file has exactly one writer. The FS25 Lua sandbox has no `os` module (no os.rename/os.remove,
-- see FS25_UsedPlus AI reference pitfalls/what-doesnt-work.md), so the default mode writes the file directly;
-- readers (backend) validate every JSON document and retry partially written files on the next cycle.
-- The sandbox also refuses io.open in read mode ("io.open, only write mode ('w') is allowed" - it returns an
-- object without :read). Files the mod has to READ (instructions, config) are therefore XML files that wrap the
-- JSON text in one element and are read with the engine's XML API, see RPSimFileIO.readPayload.
RPSimFileIO = {}

-- Root element and payload element of the XML wrapper files: <rpsim><json>{...}</json></rpsim>.
RPSimFileIO.PAYLOAD_KEY = "rpsim.json"

-- luacheck: globals createFolder fileExists XMLFile loadXMLFile getXMLString delete
RPSimFileIO.backend = {
    open = function(path, mode) return io.open(path, mode) end,
    rename = function(from, to)
        if os == nil or os.rename == nil then
            return nil, "os.rename unavailable"
        end
        return os.rename(from, to)
    end,
    remove = function(path)
        if os == nil or os.remove == nil then
            return nil, "os.remove unavailable"
        end
        return os.remove(path)
    end,
    mkdir = function(path)
        if createFolder ~= nil then
            createFolder(path)
            return true
        end
        return nil, "createFolder unavailable"
    end,
    exists = function(path)
        if fileExists ~= nil then
            return fileExists(path)
        end
        local f = io.open(path, "r")
        if f then
            f:close()
            return true
        end
        return false
    end,
    -- Returns the text of element `key` of the XML file at `path`, or nil, err (missing file/element).
    readXmlText = function(path, key)
        if XMLFile ~= nil and XMLFile.loadIfExists ~= nil then
            local xml = XMLFile.loadIfExists("RPSimPayload", path)
            if xml == nil then
                return nil, "no such file"
            end
            local text = xml:getString(key)
            xml:delete()
            return text, nil
        end
        if loadXMLFile ~= nil and getXMLString ~= nil then
            if fileExists ~= nil and not fileExists(path) then
                return nil, "no such file"
            end
            local id = loadXMLFile("RPSimPayload", path)
            if id == nil or id == 0 then
                return nil, "cannot load XML"
            end
            local text = getXMLString(id, key)
            delete(id)
            return text, nil
        end
        return nil, "XML API unavailable"
    end,
}

--- Reads the JSON payload of an XML wrapper file (<rpsim><json>...</json></rpsim>).
-- Returns the text or nil, err. Never raises. This is the only way the mod reads files inside FS25.
function RPSimFileIO.readPayload(path)
    local ok, text, err = pcall(RPSimFileIO.backend.readXmlText, path, RPSimFileIO.PAYLOAD_KEY)
    if not ok then
        return nil, tostring(text)
    end
    if text == nil then
        return nil, err or "no payload"
    end
    return text, nil
end

--- Reads a whole plain-text file; returns content or nil, err. Never raises.
-- Not usable inside FS25 (read mode is blocked, see header) - tooling/tests only; the mod uses readPayload.
function RPSimFileIO.read(path)
    local ok, content, err = pcall(function()
        local f, openErr = RPSimFileIO.backend.open(path, "r")
        if f == nil then
            return nil, openErr or "cannot open"
        end
        if type(f.read) ~= "function" then
            -- FS25 hands out a write-only stand-in object for read mode.
            if type(f.close) == "function" then
                f:close()
            end
            return nil, "read mode not supported"
        end
        local text = f:read("*a")
        f:close()
        return text, nil
    end)
    if not ok then
        return nil, tostring(content)
    end
    return content, err
end

local function writePlain(path, content)
    local ok, f, err = pcall(RPSimFileIO.backend.open, path, "w")
    if not ok then
        return false, tostring(f)
    end
    if f == nil then
        return false, err or "cannot open for writing"
    end
    f:write(content)
    f:close()
    return true, nil
end

--- Creates the directory (and parents are expected to exist, as with FS25 createFolder).
function RPSimFileIO.ensureDir(path)
    local ok, res, err = pcall(RPSimFileIO.backend.mkdir, path)
    if not ok then
        return false, tostring(res)
    end
    if res == nil and err ~= nil then
        return false, err
    end
    return true, nil
end

--- Writes a bridge file. mode: "direct" (default) | "rename" | "marker" | "auto". Returns ok, usedMode|err.
-- direct: write <path> directly (io.open, as Farm Dashboard does in FS25). No helper files.
-- rename: write <path>.tmp, then rename over <path> (needs os.rename - only outside the FS25 sandbox).
-- marker: remove <path>.ready, write <path> directly, then create <path>.ready.
-- auto:   rename, falling back to marker.
function RPSimFileIO.write(path, content, mode)
    mode = mode or "direct"
    if mode == "direct" then
        local ok, err = writePlain(path, content)
        if not ok then
            return false, err
        end
        return true, "direct"
    end
    local tmp = path .. ".tmp"
    if mode == "rename" or mode == "auto" then
        local ok, err = writePlain(tmp, content)
        if not ok then
            return false, err
        end
        -- Windows rename does not overwrite existing files, so remove the target first.
        pcall(RPSimFileIO.backend.remove, path)
        local rok, renamed, rerr = pcall(RPSimFileIO.backend.rename, tmp, path)
        if rok and renamed then
            return true, "rename"
        end
        if mode == "rename" then
            return false, tostring(rerr or renamed)
        end
        pcall(RPSimFileIO.backend.remove, tmp)
    end
    local marker = path .. ".ready"
    pcall(RPSimFileIO.backend.remove, marker)
    local ok, err = writePlain(path, content)
    if not ok then
        return false, err
    end
    local mok, merr = writePlain(marker, "")
    if not mok then
        return false, merr
    end
    return true, "marker"
end
