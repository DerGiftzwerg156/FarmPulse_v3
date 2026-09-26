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

--- raw: { savegameId, mapName, sellPoints = { {id, name, acceptedFillTypes = {..}} }, fillTypes = {..},
--         farmlands = { {farmlandId, hectares, price, ownerFarmId, showOnFarmlandsScreen, defaultFarmProperty,
--                      npc = {index, name, title}} },
--         detectedMods = { "FS25_..." } }
function RPSimMarketContext.build(raw)
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
    return {
        savegameId = raw.savegameId,
        mapName = raw.mapName,
        sellPoints = sellPoints,
        fillTypes = fillTypes,
        farmlands = farmlands,
        detectedMods = mods,
    }
end
