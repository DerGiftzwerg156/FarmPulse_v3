-- Builds market_context.json (exported on mission start, after every applied FARMLAND_TRANSFER and whenever its
-- content changed).
RPSimMarketContext = {}

--- FS25 NPC of a farmland (T-21): { index, name, title } or nil (older game data / no NPC manager).
function RPSimMarketContext.npc(npc)
    if type(npc) ~= "table" or npc.index == nil then
        return nil
    end
    return { index = npc.index, name = npc.name, title = npc.title or npc.name }
end

--- Roadmap V3 R3-V1 (contract R3-Q1): vehicle catalog of the shop (g_storeManager:getItems(), species VEHICLE, shown in
-- the shop). raw = { {xmlFilename, name, price, lifetime, categoryName, isMod, motorized?} }; motorized (engine
-- present, storeItem.specs.power ~= nil) is optional (🟡 fallback of R3-V1: left out).
function RPSimMarketContext.buildStoreVehicles(raw)
    local list = RPSimJson.array({})
    for _, v in ipairs(raw) do
        if type(v.xmlFilename) == "string" and v.xmlFilename ~= "" and type(v.price) == "number" then
            local e = { xmlFilename = v.xmlFilename, name = tostring(v.name or v.xmlFilename),
                price = math.floor(v.price + 0.5), isMod = v.isMod == true }
            if type(v.lifetime) == "number" then
                e.lifetime = v.lifetime
            end
            if type(v.categoryName) == "string" and v.categoryName ~= "" then
                e.categoryName = v.categoryName
            end
            if type(v.motorized) == "boolean" then
                e.motorized = v.motorized
            end
            list[#list + 1] = e
        end
    end
    table.sort(list, function(a, b) return a.xmlFilename < b.xmlFilename end)
    return list
end

--- Roadmap V3 R3-V1: the catalog sorted by xmlFilename and cut to maxEntries. Returns the list and the number of
-- entries left out.
function RPSimMarketContext.capStoreVehicles(raw, maxEntries)
    local list = {}
    for _, v in ipairs(raw or {}) do
        if type(v) == "table" and type(v.xmlFilename) == "string" then
            list[#list + 1] = v
        end
    end
    table.sort(list, function(a, b) return a.xmlFilename < b.xmlFilename end)
    local dropped = 0
    if maxEntries ~= nil and #list > maxEntries then
        dropped = #list - maxEntries
        for i = #list, maxEntries + 1, -1 do
            list[i] = nil
        end
    end
    return list, dropped
end

--- Roadmap V3.1 (R31-Q1, K1): outline of every field and the map size. raw = { mapSize, fields = { {farmlandId, name,
-- points = { {x, z} }} } }; x / z = world coordinates in metres (rounded to 0.1). A field keeps at most maxPoints
-- points, picked evenly along the outline (the first point stays); an outline needs at least 3 points. nil without a
-- usable mapSize (> 0). Fields sorted by farmlandId, then name.
function RPSimMarketContext.buildFieldShapes(raw, maxPoints)
    if type(raw) ~= "table" or type(raw.mapSize) ~= "number" or raw.mapSize ~= raw.mapSize or raw.mapSize <= 0 then
        return nil
    end
    local function r1(v) return math.floor(v * 10 + 0.5) / 10 end
    local fields = RPSimJson.array({})
    for _, f in ipairs(raw.fields or {}) do
        if type(f) == "table" and type(f.farmlandId) == "number" and f.name ~= nil and type(f.points) == "table" then
            local valid = {}
            for _, p in ipairs(f.points) do
                if type(p) == "table" and type(p.x) == "number" and type(p.z) == "number" and p.x == p.x
                    and p.z == p.z then
                    valid[#valid + 1] = p
                end
            end
            local n = #valid
            local keep = n
            if maxPoints ~= nil and maxPoints > 0 and n > maxPoints then
                keep = maxPoints
            end
            if keep >= 3 then
                local points = RPSimJson.array({})
                for i = 0, keep - 1 do
                    local p = valid[math.floor(i * n / keep) + 1]
                    points[#points + 1] = { x = r1(p.x), z = r1(p.z) }
                end
                fields[#fields + 1] = { farmlandId = f.farmlandId, name = tostring(f.name), points = points }
            end
        end
    end
    table.sort(fields, function(a, b)
        if a.farmlandId == b.farmlandId then return a.name < b.name end
        return a.farmlandId < b.farmlandId
    end)
    return { mapSize = math.floor(raw.mapSize + 0.5), fields = fields }
end

--- Roadmap V3.3 (R33-Q1, contract; read by the mod with R33-F): every crop of the map (g_fruitTypeManager:getFruitTypes())
-- for the crop dropdown of the field book. raw = { {name, fillType?, title?, regrows?, needsRolling?,
-- products? = { fillType names }} }:
-- name = fruit type name (required), fillType = its standard harvest product, title = display name of the game,
-- regrows = grows again after a cut (FruitTypeDesc.regrows), needsRolling = the crop is rolled after sowing
-- (FruitTypeDesc.needsRolling, R33-F4 owner decision 2026-10-09), products = further harvest products from the fruit type
-- converters (e.g. MAIZE -> CHAFF; sorted, unique, without the standard product). Optional values that are empty or of
-- the wrong type are left out; entries without a name and repeated names are dropped. Sorted by name.
function RPSimMarketContext.buildFruitTypes(raw)
    local list = RPSimJson.array({})
    local seen = {}
    for _, f in ipairs(raw) do
        if type(f) == "table" and type(f.name) == "string" and f.name ~= "" and not seen[f.name] then
            seen[f.name] = true
            local e = { name = f.name }
            if type(f.fillType) == "string" and f.fillType ~= "" then
                e.fillType = f.fillType
            end
            if type(f.title) == "string" and f.title ~= "" then
                e.title = f.title
            end
            if type(f.regrows) == "boolean" then
                e.regrows = f.regrows
            end
            if type(f.needsRolling) == "boolean" then
                e.needsRolling = f.needsRolling
            end
            if type(f.products) == "table" then
                local products = RPSimJson.array({})
                local unique = {}
                for _, p in ipairs(f.products) do
                    if type(p) == "string" and p ~= "" and p ~= e.fillType and not unique[p] then
                        unique[p] = true
                        products[#products + 1] = p
                    end
                end
                table.sort(products)
                e.products = products
            end
            list[#list + 1] = e
        end
    end
    table.sort(list, function(a, b) return a.name < b.name end)
    return list
end

--- raw: { savegameId, mapName, sellPoints = { {id, name, acceptedFillTypes = {..}} }, fillTypes = {..},
--         farmlands = { {farmlandId, hectares, price, ownerFarmId, showOnFarmlandsScreen, defaultFarmProperty,
--                      npc = {index, name, title}} },
--         detectedMods = { "FS25_..." },
--         storeVehicles = <see buildStoreVehicles> | nil (Roadmap V3, nil = not collected),
--         fieldShapes = <see buildFieldShapes> | nil (Roadmap V3.1, nil = not collected),
--         fruitTypes = <see buildFruitTypes> | nil (Roadmap V3.3, nil = not collected) }
-- cfg: RPSimConfig (fieldShapeMaxPoints), defaults when missing.
function RPSimMarketContext.build(raw, cfg)
    cfg = cfg or RPSimConfig.new()
    local sellPoints = RPSimJson.array({})
    for _, sp in ipairs(raw.sellPoints or {}) do
        local accepted = RPSimJson.array({})
        for _, ft in ipairs(sp.acceptedFillTypes or {}) do
            accepted[#accepted + 1] = ft
        end
        table.sort(accepted)
        local entry = { id = sp.id, name = sp.name, acceptedFillTypes = accepted }
        if sp.production then
            -- TODO T-22: production point as buyer (delivery contracts); ownedByPlayer = the player's own production
            entry.production = true
            entry.ownedByPlayer = sp.ownedByPlayer == true
        end
        sellPoints[#sellPoints + 1] = entry
    end
    table.sort(sellPoints, function(a, b) return a.id < b.id end)
    local fillTypes = RPSimJson.array({})
    for _, ft in ipairs(raw.fillTypes or {}) do
        fillTypes[#fillTypes + 1] = ft
    end
    table.sort(fillTypes)
    local farmlands = RPSimJson.array({})
    for _, f in ipairs(raw.farmlands or {}) do
        farmlands[#farmlands + 1] = { farmlandId = f.farmlandId,
            hectares = math.floor((f.hectares or 0) * 100 + 0.5) / 100,
            price = math.floor((f.price or 0) + 0.5), ownerFarmId = f.ownerFarmId or 0,
            showOnFarmlandsScreen = f.showOnFarmlandsScreen ~= false,
            defaultFarmProperty = f.defaultFarmProperty == true,
            npc = RPSimMarketContext.npc(f.npc) }
    end
    table.sort(farmlands, function(a, b) return a.farmlandId < b.farmlandId end)
    local mods = RPSimJson.array({})
    for _, m in ipairs(raw.detectedMods or {}) do
        mods[#mods + 1] = m
    end
    table.sort(mods)
    local doc = {
        savegameId = raw.savegameId,
        mapName = raw.mapName,
        sellPoints = sellPoints,
        fillTypes = fillTypes,
        farmlands = farmlands,
        detectedMods = mods,
    }
    if type(raw.storeVehicles) == "table" then
        doc.storeVehicles = RPSimMarketContext.buildStoreVehicles(raw.storeVehicles)
    end
    if type(raw.fieldShapes) == "table" then
        doc.fieldShapes = RPSimMarketContext.buildFieldShapes(raw.fieldShapes, cfg.fieldShapeMaxPoints)
    end
    if type(raw.fruitTypes) == "table" then
        doc.fruitTypes = RPSimMarketContext.buildFruitTypes(raw.fruitTypes)
    end
    return doc
end
