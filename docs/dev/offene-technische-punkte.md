# Open technical points (`TODO(offene-frage)`)

Consolidated list of every place where the implementation relies on an assumption that must be verified on
the FS25 prototype (technical concept, chapter "Offene technische Punkte"). Each entry names the chosen
best-effort solution, the fallback, and where it lives in the code. Search the code for `TODO(offene-frage)`.

| # | Question (technical concept) | Chosen solution / fallback | Code location |
| --- | --- | --- | --- |
| 1 | Is `os.rename` available in the FS25 Lua sandbox (atomic bridge writes)? | `atomicWriteMode = "auto"`: write `<file>.tmp` + `os.rename`; if rename fails, fall back to the marker strategy (write file, then create `<file>.ready`). Mode switchable via `rpsim_config.json`. The backend additionally validates every JSON document and retries on partial files. | `mod/FS25_RPSim/src/util/FileIO.lua`, `mod/FS25_RPSim/src/bridge/Config.lua` |
| 2 | Which stable identifier does FS25 provide for `SellingStation` objects? | `uniqueId` of the owning placeable, else the station name. | `mod/FS25_RPSim/src/game/GameAdapter.lua` (`sellPointId`) |
| 3 | Can a hard `CREDIT_PENALTY` / `CREDIT_CALLBACK` booking trigger FS25's bankruptcy/warning logic? | Booked via `g_currentMission:addMoney(..., MoneyType.OTHER)`; behaviour must be observed in the prototype. | `mod/FS25_RPSim/src/game/GameAdapter.lua` (`addMoney`) |
| 4 | Which FS25 field provides period / season reliably (calendar invitations, yearly rotation budget)? | Backend fallback: fixed game-day counter since savegame start (`rpsim.time.*`). | backend `GameCalendar` (see Phase 4) |
| 5 | Do independent event spawners (market events, story hooks, village life, auctions) overload each other? | No coordination layer by design; every spawner has its own configurable cadence/cap. | backend spawner services |
| 6 | Which Lua API performs `FARMLAND_TRANSFER`? | `g_farmlandManager:setLandOwnership(farmlandId, farmId / NO_OWNER_FARM_ID)` (same API as vanilla buy/sell and the "Farmland Marketplace" mod). | `mod/FS25_RPSim/src/game/GameAdapter.lua` (`transferFarmland`) |
| 7 | Must the vanilla farmland buy menu be locked? | Not locked (like the vanilla loan); the backend `FarmlandOwnershipService` reconciles ownership after the fact. | backend `FarmlandOwnershipService` |
| 8 | Which Lua API provides silo fill levels and how are classic silos distinguished from bunker silos / halls? | Placeables with `spec_silo`, excluding `spec_bunkerSilo`, `spec_objectStorage`, husbandry and production points; if the store category is known it must be `SILOS`. | `mod/FS25_RPSim/src/export/Storage.lua` (`isClassicSilo`), `GameAdapter.lua` |
| 9 | Does FS25's own price simulation change fast enough for a ~60 s export to produce meaningful curves? | `prices` stay in the regular export cycle (`exportIntervalMs`); the backend history endpoint aggregates/down-samples. | `Config.lua`, backend `PriceQueryService` |
| 10 | Exact FS25 lifecycle hook names (`loadMap`, `update`, `deleteMap`, `FSCareerMissionInfo.saveToXMLFile`, `SellingStation.getEffectiveFillTypePrice`, `SellingStation.sellFillType`). | Taken from established FS22/FS25 community mods; verify against the current FS25 scripting documentation. | `mod/FS25_RPSim/src/RPSim.lua` |
| 11 | `prices[].currentPrice`: base price or effective price incl. RPSim events? | Effective price the player currently gets at the station (incl. active RPSim events), price per 1000 l. | `GameAdapter.lua` (`collectFarmFacts`) |
| 12 | Game-time source for monotonic in-game milliseconds. | `environment.currentMonotonicDay * 86 400 000 + environment.dayTime` (stops while paused). | `GameAdapter.lua` (`getGameTime`) |
| 13 | Is FS25's native `io.open` usable for writing in `modSettings`? | Used like established bridge mods (FarmMonitor, livemap); all file access is wrapped in `pcall`. | `mod/FS25_RPSim/src/util/FileIO.lua` |
