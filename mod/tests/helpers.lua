-- Test helpers: loads the pure mod modules without FS25 and provides fakes.
local helpers = {}

local SRC = (os.getenv("RPSIM_SRC") or "FS25_RPSim/src/")

function helpers.loadModules()
    for _, f in ipairs({
        "util/Json.lua", "util/Log.lua", "util/FileIO.lua",
        "bridge/Config.lua", "bridge/BridgePaths.lua",
        "export/Storage.lua", "export/FarmFacts.lua", "export/MarketContext.lua",
        "import/Instructions.lua", "import/PriceEventMath.lua", "import/PriceEvents.lua",
        "import/Processor.lua", "import/Persistence.lua",
        "bridge/Bridge.lua",
    }) do
        local fh = io.open(SRC .. f, "r")
        if fh ~= nil then
            fh:close()
            dofile(SRC .. f)
        end
    end
    helpers.logs = {}
    RPSimLog.sink = function(level, msg)
        helpers.logs[#helpers.logs + 1] = { level = level, msg = msg }
    end
end

function helpers.countLogs(level, pattern)
    local n = 0
    for _, l in ipairs(helpers.logs) do
        if l.level == level and (pattern == nil or l.msg:find(pattern, 1, true)) then
            n = n + 1
        end
    end
    return n
end

--- In-memory file system replacing RPSimFileIO.backend.
function helpers.fakeFs(opts)
    opts = opts or {}
    local fs = { files = {}, dirs = {}, renameAvailable = opts.renameAvailable ~= false }
    local function parentOf(path)
        return path:match("^(.*/)[^/]*$")
    end
    local backend = {}
    function backend.open(path, mode)
        if mode == "r" then
            local content = fs.files[path]
            if content == nil then
                return nil, "no such file"
            end
            return { read = function() return content end, close = function() end }
        end
        local parent = parentOf(path)
        if parent ~= nil and not fs.dirs[parent] then
            return nil, "no such directory " .. parent
        end
        local buf = {}
        return {
            write = function(_, s) buf[#buf + 1] = s end,
            close = function() fs.files[path] = table.concat(buf) end,
        }
    end
    function backend.rename(from, to)
        if not fs.renameAvailable then
            error("attempt to call field 'rename' (a nil value)")
        end
        if fs.files[from] == nil then
            return nil, "missing source"
        end
        fs.files[to] = fs.files[from]
        fs.files[from] = nil
        return true
    end
    function backend.remove(path)
        fs.files[path] = nil
        return true
    end
    function backend.mkdir(path)
        fs.dirs[path] = true
        return true
    end
    function backend.exists(path)
        return fs.files[path] ~= nil or fs.dirs[path] == true
    end
    fs.backend = backend
    RPSimFileIO.backend = backend
    return fs
end

--- Fake game adapter with mutable state.
function helpers.fakeAdapter(overrides)
    local a = {
        gameTime = 1000,
        balance = 245000,
        moneyLog = {},
        transfers = {},
        failMoney = false,
        failFacts = false,
        farmland = { { farmlandId = 12, hectares = 4.5, price = 54000 } },
        allFarmlands = {
            { farmlandId = 12, hectares = 4.5, price = 54000, ownerFarmId = 1 },
            { farmlandId = 13, hectares = 6, price = 72000, ownerFarmId = 0 },
        },
    }
    for k, v in pairs(overrides or {}) do a[k] = v end
    function a:getGameTime() return self.gameTime end
    function a:collectFarmFacts()
        if self.failFacts then error("engine exploded") end
        return {
            balance = self.balance,
            vehicles = { { uniqueId = "veh_00042", value = 285000, damage = 0.18 } },
            leasedVehicles = { { uniqueId = "veh_00077" } },
            placeables = { { uniqueId = "plc_00011", value = 120000 } },
            farmland = self.farmland,
            animals = { { husbandryUniqueId = "hus_00003", type = "COW", count = 24, estimatedValue = 96000 } },
            silos = { { descriptor = { hasSiloSpec = true, categoryName = "SILOS" },
                storages = { { capacity = 50000, fillLevels = { WHEAT = 42000 } } } } },
            vanillaLoan = 80000,
            prices = { { sellPoint = "MillNorth", fillType = "WHEAT", pricePerLiter = 0.215 } },
        }
    end
    function a:collectMarketContext()
        return { mapName = "Erlengrund",
            sellPoints = { { id = "MillNorth", name = "Mühle Nord", acceptedFillTypes = { "WHEAT", "BARLEY" } } },
            fillTypes = { "WHEAT", "BARLEY" }, farmlands = self.allFarmlands }
    end
    function a:addMoney(amount, reason, note)
        if self.failMoney then return false, "money failed" end
        self.balance = self.balance + amount
        self.moneyLog[#self.moneyLog + 1] = { amount = amount, reason = reason, note = note }
        return true
    end
    a.notifications = {}
    function a:notify(text, level)
        self.notifications[#self.notifications + 1] = { text = text, level = level }
        return true
    end
    function a:transferFarmland(id, direction)
        self.transfers[#self.transfers + 1] = { farmlandId = id, direction = direction }
        for _, f in ipairs(self.allFarmlands) do
            if f.farmlandId == id then
                f.ownerFarmId = direction == "TO_PLAYER" and 1 or 0
            end
        end
        return true
    end
    return a
end

function helpers.newBridge(opts)
    opts = opts or {}
    local fs = helpers.fakeFs(opts)
    local cfg = RPSimConfig.new(opts.config)
    local paths = RPSimBridgePaths.new("/ms/")
    local adapter = helpers.fakeAdapter(opts.adapter)
    local bridge = RPSimBridge.new(cfg, paths, adapter)
    bridge.state.savegameId = opts.savegameId or "map_erlengrund_1_20260101"
    return bridge, fs, adapter, paths
end

function helpers.writeInstructions(fs, paths, doc)
    fs.files[paths.instructions] = RPSimJson.encode(doc)
end

--- Loads the FS25-facing files (GameAdapter.lua, RPSim.lua) against the fake engine globals of fakeGame().
function helpers.loadGameModules()
    dofile(SRC .. "game/GameAdapter.lua")
    dofile(SRC .. "RPSim.lua")
end

--- Minimal fake of the FS25 engine globals used by RPSimGameAdapter. Only structure that is documented in the
-- FS25 sources is modelled (see the references in GameAdapter.lua). opts overrides the defaults.
function helpers.fakeGame(opts)
    opts = opts or {}
    local game = { moneyLog = {}, ownership = {}, notifications = {} }
    local fillTypes = opts.fillTypes or { [1] = "WHEAT", [2] = "BARLEY", [3] = "MILK" }
    VehiclePropertyState = { NONE = 0, OWNED = 1, LEASED = 2, MISSION = 3, SHOP_CONFIG = 4 }
    MoneyType = { OTHER = { id = 1, statistic = "other" } }
    FarmlandManager = { NO_OWNER_FARM_ID = 0 }
    g_fillTypeManager = {
        getFillTypeNameByIndex = function(_, i) return fillTypes[i] end,
    }
    local farm = { farmId = 1, money = opts.money or 100000, loan = opts.loan or 0 }
    game.farm = farm
    g_farmManager = { getFarmById = function(_, id) if id == farm.farmId then return farm end end }
    local function vehicle(v)
        return setmetatable(v, { __index = {
            getOwnerFarmId = function(self) return self.ownerFarmId or 1 end,
            getUniqueId = function(self) return self.uniqueId end,
            getSellPrice = function(self) return self.sellPrice or 0 end,
            getDamageAmount = function(self) return self.damage or 0 end,
            setDamageAmount = function(self, amount) self.damage = amount end,
        } })
    end
    local vehicles = {}
    for _, v in ipairs(opts.vehicles or {
        { uniqueId = "veh_owned", propertyState = VehiclePropertyState.OWNED, sellPrice = 50000, damage = 0.1 },
        { uniqueId = "veh_leased", propertyState = VehiclePropertyState.LEASED, sellPrice = 90000 },
        { uniqueId = "veh_mission", propertyState = VehiclePropertyState.MISSION, sellPrice = 70000 },
    }) do
        vehicles[#vehicles + 1] = vehicle(v)
    end
    game.vehicles = vehicles
    local farmlands = opts.farmlands or {
        { id = 1, areaInHa = 2.5, price = 30000, npcIndex = 1, showOnFarmlandsScreen = true,
            defaultFarmProperty = false },
        { id = 2, areaInHa = 0.4, price = 5000, npcIndex = 2, showOnFarmlandsScreen = false,
            defaultFarmProperty = false },
    }
    for _, fl in ipairs(farmlands) do
        game.ownership[fl.id] = fl.ownerFarmId or 0
    end
    local npcs = opts.npcs or { { index = 1, name = "npc_anna", title = "Anna Berger" } }
    g_npcManager = {
        getNPCByIndex = function(_, i)
            for _, n in ipairs(npcs) do
                if n.index == i then return n end
            end
            return nil
        end,
    }
    g_farmlandManager = {
        getFarmlands = function() return farmlands end,
        getFarmlandOwner = function(_, id) return game.ownership[id] end,
        setLandOwnership = function(_, id, farmId) game.ownership[id] = farmId end,
    }
    game.stations = opts.stations or {}
    g_currentMission = {
        getFarmId = function() return 1 end,
        getIsServer = function() return true end,
        environment = opts.environment or { currentMonotonicDay = 3, dayTime = 3600000, currentPeriod = 8,
            currentDayInPeriod = 2, daysPerPeriod = 3, currentYear = 2, plannedDaysPerPeriod = 3 },
        missionInfo = { mapTitle = "Riverbend Springs", savegameIndex = 1, savegameDirectory = "/sg1" },
        vehicleSystem = { vehicles = vehicles },
        placeableSystem = { placeables = opts.placeables or {} },
        storageSystem = { getUnloadingStations = function() return game.stations end },
        addMoney = function(_, amount, farmId, moneyType)
            game.moneyLog[#game.moneyLog + 1] = { amount = amount, farmId = farmId, moneyType = moneyType }
            farm.money = farm.money + amount
        end,
        addIngameNotification = function(_, kind, text)
            game.notifications[#game.notifications + 1] = { kind = kind, text = text }
        end,
    }
    return game
end

return helpers
