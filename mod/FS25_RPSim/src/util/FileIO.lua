-- File access with injectable backend (real io/os in FS25, fakes in tests).
-- Every bridge file has exactly one writer; writes are atomic via tmp+rename or tmp+marker.
RPSimFileIO = {}

-- luacheck: globals createFolder fileExists
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
}

--- Reads a whole file; returns content or nil, err. Never raises.
function RPSimFileIO.read(path)
    local ok, f, err = pcall(RPSimFileIO.backend.open, path, "r")
    if not ok then
        return nil, tostring(f)
    end
    if f == nil then
        return nil, err or "cannot open"
    end
    local content = f:read("*a")
    f:close()
    return content, nil
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

--- Writes atomically. mode: "rename" | "marker" | "auto". Returns ok, usedMode|err.
-- rename: write <path>.tmp, then rename over <path>. Reader never sees a half-written file.
-- marker: remove <path>.ready, write <path> directly, then create <path>.ready; readers must only
--         read <path> while <path>.ready exists (fallback when os.rename is unavailable).
function RPSimFileIO.writeAtomic(path, content, mode)
    mode = mode or "auto"
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
