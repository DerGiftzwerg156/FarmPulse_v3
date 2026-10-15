-- Builds store_catalog.json (Roadmap vehicle dealer RFH-Q1, owner decision 2026-10-15): the vehicle catalog of the
-- shop with every configuration option, written once per mission start. market_context.storeVehicles (R3-V1) stays.
RPSimStoreCatalog = {}

local function isNumber(v) return type(v) == "number" and v == v end
local function isNonEmptyString(v) return type(v) == "string" and v ~= "" end
local function round(v) return math.floor(v + 0.5) end
local function r3(v) return math.floor(v * 1000 + 0.5) / 1000 end

--- { r, g, b } (0..1, 3 decimals) of a colour option, nil when incomplete.
local function buildColor(c)
    if type(c) ~= "table" then
        return nil
    end
    local r, g, b = c.r or c[1], c.g or c[2], c.b or c[3]
    if not isNumber(r) or not isNumber(g) or not isNumber(b) then
        return nil
    end
    return { r = r3(r), g = r3(g), b = r3(b) }
end

--- Options another option sets with it (VehicleLoadingData:getConfigurations, dependentConfigurations).
local function buildDependent(raw)
    local list = RPSimJson.array({})
    for _, d in ipairs(type(raw) == "table" and raw or {}) do
        if type(d) == "table" and isNonEmptyString(d.name) and isNumber(d.index) and d.index >= 1 then
            list[#list + 1] = { name = d.name, index = round(d.index) }
        end
    end
    return list
end

--- One option of a configuration type (VehicleConfigurationItem: name, desc, price, dailyUpkeep, isDefault,
-- isSelectable, saveId, isYesNoOption; colours: getColor(); motor options: power = #hp of
-- VehicleConfigurationItemMotor, RFH-K1). index = position in storeItem.configurations[name].
local function buildOption(o)
    if type(o) ~= "table" or not isNumber(o.index) or o.index < 1 then
        return nil
    end
    local e = { index = round(o.index), name = tostring(o.name or o.index),
        price = isNumber(o.price) and round(o.price) or 0,
        dailyUpkeep = isNumber(o.dailyUpkeep) and round(o.dailyUpkeep) or 0,
        isDefault = o.isDefault == true, isSelectable = o.isSelectable ~= false,
        isYesNoOption = o.isYesNoOption == true,
        dependent = buildDependent(o.dependent) }
    if isNonEmptyString(o.saveId) then
        e.saveId = o.saveId
    end
    if isNonEmptyString(o.desc) then
        e.desc = o.desc
    end
    local color = buildColor(o.color)
    if color ~= nil then
        e.color = color
    end
    if isNumber(o.power) and o.power > 0 then
        e.power = round(o.power)
    end
    return e
end

--- Configuration types of one item, sorted by name; options sorted by index. Types without options are dropped.
local function buildConfigurations(raw)
    local list = RPSimJson.array({})
    for _, c in ipairs(type(raw) == "table" and raw or {}) do
        if type(c) == "table" and isNonEmptyString(c.name) then
            local options = RPSimJson.array({})
            for _, o in ipairs(type(c.options) == "table" and c.options or {}) do
                local e = buildOption(o)
                if e ~= nil then
                    options[#options + 1] = e
                end
            end
            table.sort(options, function(a, b) return a.index < b.index end)
            if #options > 0 then
                local e = { name = c.name, options = options }
                if isNonEmptyString(c.title) then
                    e.title = c.title
                end
                list[#list + 1] = e
            end
        end
    end
    table.sort(list, function(a, b) return a.name < b.name end)
    return list
end

--- name -> index (whole numbers >= 1); other entries are dropped.
local function buildIndexMap(raw)
    local map = {}
    for name, index in pairs(type(raw) == "table" and raw or {}) do
        if isNonEmptyString(name) and isNumber(index) and index >= 1 then
            map[name] = round(index)
        end
    end
    return map
end

--- Packages of the manufacturer (ConfigurationUtil.getConfigurationSetsFromXML: name, isDefault, configurations).
local function buildSets(raw)
    local list = RPSimJson.array({})
    for _, s in ipairs(type(raw) == "table" and raw or {}) do
        if type(s) == "table" and isNonEmptyString(s.name) then
            list[#list + 1] = { name = s.name, isDefault = s.isDefault == true,
                configurations = buildIndexMap(s.configurations) }
        end
    end
    return list
end

--- One catalog entry; nil without xmlFilename or price.
function RPSimStoreCatalog.buildItem(v)
    if type(v) ~= "table" or not isNonEmptyString(v.xmlFilename) or not isNumber(v.price) then
        return nil
    end
    local e = { xmlFilename = v.xmlFilename, name = tostring(v.name or v.xmlFilename), price = round(v.price),
        dailyUpkeep = isNumber(v.dailyUpkeep) and round(v.dailyUpkeep) or 0, isMod = v.isMod == true,
        isBundle = v.isBundle == true, multipleItemPurchase = v.multipleItemPurchase == true,
        configurations = buildConfigurations(v.configurations),
        defaultConfigurations = buildIndexMap(v.defaultConfigurations),
        configurationSets = buildSets(v.configurationSets) }
    if isNonEmptyString(v.brand) then
        e.brand = v.brand
    end
    if isNonEmptyString(v.categoryName) then
        e.categoryName = v.categoryName
    end
    -- RFH-K1 (owner decision 2026-10-15): display names of the game and the power range in hp
    if isNonEmptyString(v.brandTitle) then
        e.brandTitle = v.brandTitle
    end
    if isNonEmptyString(v.categoryTitle) then
        e.categoryTitle = v.categoryTitle
    end
    if isNumber(v.powerMin) and isNumber(v.powerMax) and v.powerMin > 0 and v.powerMax >= v.powerMin then
        e.powerMin = round(v.powerMin)
        e.powerMax = round(v.powerMax)
    end
    if isNumber(v.lifetime) then
        e.lifetime = v.lifetime
    end
    if type(v.motorized) == "boolean" then
        e.motorized = v.motorized
    end
    return e
end

--- raw = { savegameId, items = { <see buildItem> } }. Items sorted by xmlFilename.
function RPSimStoreCatalog.build(raw)
    local items = RPSimJson.array({})
    for _, v in ipairs(type(raw) == "table" and type(raw.items) == "table" and raw.items or {}) do
        local e = RPSimStoreCatalog.buildItem(v)
        if e ~= nil then
            items[#items + 1] = e
        end
    end
    table.sort(items, function(a, b) return a.xmlFilename < b.xmlFilename end)
    return { savegameId = raw ~= nil and raw.savegameId or nil, items = items }
end
