# FarmPulse – ToDo-Liste

Ergebnis der FS25-Analyse vom 26.09.2026: Abgleich des Projekts mit Farming Simulator 25, der offiziellen
LUADOC und der Community-Dokumentation sowie mit veröffentlichten FS25-Mods, die dieselben APIs nutzen.

**Einordnung:** Die Architektur (Mod als Sensor/Aktuator, Datei-Bridge, Backend entscheidet, KI formuliert) passt
zu FS25, die genutzten APIs existieren fast alle so. Die Punkte unten sind nötig, damit das Projekt im echten Spiel
zuverlässig läuft, plus die Ideen für spätere Funktionen.

**Legende**

- **P1** = Fehler, der im echten Spiel auftritt · **P2** = Robustheit, Aufräumen, Doku · **P3** = neue Funktion
- **Beleg** = worauf sich die Lösung stützt (Spielcode, LUADOC oder funktionierender Mod-Code). Wo es keinen Beleg
  gibt, steht die mit dem Projektinhaber abgestimmte **Entscheidung** oder ein **Im Spiel prüfen**.
- Pfade sind relativ zum Repo-Root, Zeilennummern beziehen sich auf den Stand von Commit `a99ddf0` (main).

## Stand der Umsetzung

Alle Punkte sind umgesetzt (Branch `claude/inspiring-dirac-i7buk9`). Mehrspieler/Dedicated Server (früher T-23) ist
auf Wunsch des Projektinhabers **kein** Ziel und wurde aus der Liste entfernt. Was sich nur im Spiel belegen lässt,
steht als Prüfpunkt in `docs/dev/manual-test-plan.md`, Abschnitt 8:

| Punkt | Umsetzung | Im Spiel prüfen |
| --- | --- | --- |
| T-20 Versicherung, Jäger, Tierarzt/Viehhändler/Zuchtverband | Backend-Services unter `backend/.../contract/`, Seite „Verträge & Vorgänge“ | 8.7 (Tier-Export) |
| T-20 Energieversorger | Festpreis-Kontrakte/Preisschwankungen an Verkaufsstellen mit Biogas-Füllarten | 8.15 |
| T-21 NPC-Feldbesitzer | `market_context.farmlands[].npc`, `GameNpcService` | 8.16 |
| T-21 Hinweise im Spiel | Anweisung `NOTIFICATION` | 8.17 |
| T-21 Finanzkategorien | `MoneyType.register(...)`, Titel in `modDesc.xml` | 8.18 |
| T-21 Kalenderbezug | `calendar.season`, Datum im KI-Prompt | 8.19 |
| T-22 Pacht | `LeaseService`, `FARMLAND_TRANSFER` hin und zurück | 8.20 |
| T-22 Wartungsvertrag | `MaintenanceService`, Anweisung `REPAIR_VEHICLE` | 8.21 |
| T-22 Lieferverträge mit Produktionen | `sellPoints[].production`, `ProductionSupplyService` (ein Hofladen zählt nur, wenn er als Produktion/Verkaufsstelle auftaucht) | 8.22 |
| T-22 Vanilla-Aufträge | `farm_facts.missions`, `ContractorService` | 8.23 |

## Getroffene Entscheidungen

| Thema | Entscheidung |
| --- | --- |
| Laden ohne Speichern | Das Backend erkennt den Zeitrücksprung und sendet verlorene Buchungen erneut ([T-02](#t-02-laden-ohne-speichern-verlorene-buchungen-erneut-senden)). |
| Rücksprung auf deutlich älteren Spielstand | Bis zu einer Schwelle automatisch nachbuchen, darüber fragt das Tool den Spieler ([T-02](#t-02-laden-ohne-speichern-verlorene-buchungen-erneut-senden)). |
| Zu wenig Geld für eine Abbuchung | Der Mod lehnt die Buchung ab (`FAILED`), das Backend behandelt sie als verpasste Zahlung ([T-03](#t-03-abbuchungen-bei-zu-wenig-geld-ablehnen-und-fehlschläge-im-backend-auswerten)). |
| Leasing-Fahrzeuge | Nicht mehr als Vermögen zählen; laufende Leasingkosten als Verpflichtung ([T-04](#t-04-leasing-fahrzeuge-aus-dem-vermögen-nehmen-leasingkosten-als-verpflichtung)). |
| Veröffentlichung | Nur GitHub/privat, kein ModHub → `io.open` bleibt ([T-07](#t-07-dateischreiben-ohne-os-modul-vereinfachen)). |
| Spielmonat | Nur die FS25-Periode; die Konfiguration `rpsim.time.*` entfällt ([T-08](#t-08-spielmonat--fs25-periode)). |
| Andere Mods mit Überschneidung | Erkennen und warnen, nichts abschalten ([T-09](#t-09-konflikt-mods-erkennen-und-warnen)). |
| Zukunftsfunktionen | Neue Charaktere haben Vorrang; ihre Anlässe werden **nur simuliert** (ohne echte Spielereignisse) ([T-20](#t-20-neue-charaktere--höchste-priorität)). |
| Sprache und Ort dieser Datei | Deutsch, im Repo-Root (bewusste Abweichung von CONTRIBUTING.md). |

---

## P1 – Fehler, die im echten Spiel auftreten

### T-01 Ersten Export erst nach vollständigem Laden ausführen und `market_context` regelmäßig erneuern

- [x] Export aus `loadMap` herauslösen
- [x] `market_context.json` regelmäßig neu schreiben
- [x] Mod-Tests anpassen

**Problem:** `RPSim:loadMap` exportiert sofort (`mod/FS25_RPSim/src/RPSim.lua:52` → `RPSimBridge:onSavegameLoaded`,
`mod/FS25_RPSim/src/bridge/Bridge.lua:75`). Zu diesem Zeitpunkt sind Farms, Fahrzeuge, Gebäude und Verkaufsstellen
aus dem Spielstand sehr wahrscheinlich noch nicht geladen. Folgen:

- `market_context.json` enthält keine Verkaufsstellen und wird nur nach einem `FARMLAND_TRANSFER` neu geschrieben.
  Das Backend erzeugt ohne Verkaufsstellen **keine Markt-Ereignisse**
  (`backend/src/main/java/de/farmpulse/rpsim/market/MarketEventEngine.java:131`).
- `farm_facts.json` kann einen leeren Hof mit Kontostand 0 melden. Die Liquidität basiert auf dem letzten
  Snapshot (`bridge/LiquidityService.java:39`), bis zum nächsten Export (60 s) wirkt der Hof zahlungsunfähig.

**Umsetzung:**

- Ersten Export per `Mission00.onStartMission = Utils.appendedFunction(...)` auslösen, oder im ersten
  `update()` nach einer kurzen Wartezeit (so macht es Farm Dashboard mit `readyAt`).
- `market_context.json` zusätzlich periodisch schreiben, z. B. bei jedem `farm_facts`-Export, aber nur bei
  geänderter Datei. Dafür Hash oder Rohtext vergleichen, wie es das Backend schon tut.

**Beleg:** FS25_UsedPlus lädt Savegame-Daten bewusst erst in `Mission00.onStartMission`, weil Farms vorher nicht
existieren (`src/main.lua`, Kommentar „farms don't exist yet“). WeezlsModLib nutzt `FSBaseMission.onStartMission`.

**Im Spiel prüfen:** Log-Ausgabe der Anzahl Verkaufsstellen, Fahrzeuge und Felder beim ersten Export.

### T-02 Laden ohne Speichern: verlorene Buchungen erneut senden

- [x] Rücksprung-Erkennung im Backend
- [x] Verlorene Anweisungen wieder auf `PENDING` setzen
- [x] Schwelle mit Rückfrage an den Spieler
- [x] Tests (Bridge-Simulator-Szenario „Neu laden ohne Speichern“)

**Problem:** Das Backend schreibt nur `PENDING`-Anweisungen in `instructions.json`
(`backend/.../bridge/BridgeSyncService.java:200-215`). Bestätigte (`APPLIED`) verschwinden daraus. Beendet der
Spieler ohne Speichern, fehlen die Buchungen nach dem Neuladen im Spiel: Das Geld der Kreditauszahlung ist weg,
der Kredit läuft im Backend weiter. Das Backend erkennt heute nur, dass die Spielzeit zurückspringt, und setzt
den Zeitanker neu (`backend/.../time/GameClockService.java:33`). Die Buchungen gleicht es nicht ab.

**Entscheidung:** Das Backend erkennt den Rücksprung und sendet verlorene Buchungen erneut. Der Mod bleibt unverändert.

**Umsetzung (Vorschlag):**

1. Beim Rücksprung auf Spielzeit `T` (Signal aus `GameClockService`) alle Anweisungen des Spielstands mit
   Status `APPLIED` und `ackedAtGameTime > T` sammeln.
2. Davon nur die nehmen, die in der nächsten `instructions_ack.json` **fehlen**. Der Mod baut die Ack-Datei aus
   dem gespeicherten Zustand neu auf; was dort fehlt, war nicht gespeichert.
3. Diese Anweisungen mit derselben `instructionId` wieder auf `PENDING` setzen. Der Mod führt sie aus, weil sie
   nicht in seiner `processed`-Liste stehen. Das gilt für `MONEY_TRANSACTION`, `PRICE_EVENT` und
   `FARMLAND_TRANSFER`. Feldbesitz gleicht `FarmlandOwnershipService.reconcile` ohnehin ab.
4. **Schwelle (Entscheidung):** Bei einem Rücksprung bis zu `rpsim.bridge.rewind-auto-resend-max` (Vorschlag:
   1 Spieltag) automatisch nachbuchen. Bei größerem Rücksprung fragt das Tool den Spieler: „nachbuchen“ oder
   „Tool-Stand beibehalten, nichts nachbuchen“. Dazu eine Karte auf dem Dashboard und ein Tagebucheintrag.
5. Bekannte Folge dokumentieren: Der übrige Tool-Zustand (Mails, Vertrauen, Verhandlungen) wird **nicht**
   zurückgedreht, nur die Buchungen im Spiel werden wiederhergestellt.

**Beleg:** Kein Referenz-Code vorhanden; Design aus der Abstimmung. Die Bausteine (Zeitrücksprung-Erkennung,
Ack-Datei aus dem gespeicherten Zustand, idempotente `instructionId`) existieren bereits.

### T-03 Abbuchungen bei zu wenig Geld ablehnen und Fehlschläge im Backend auswerten

- [x] Mod: Abbuchung ablehnen, wenn das Guthaben nicht reicht
- [x] Backend: Reaktion auf `FAILED`/`REJECTED`
- [x] Tests für jede `MoneyReason`

**Problem 1:** `RPSimGameAdapter:addMoney` bucht jeden Betrag (`mod/FS25_RPSim/src/game/GameAdapter.lua:217`). Wie FS25
auf einen großen negativen Kontostand reagiert, ist nicht belegt (offener Punkt #3). Die Liquiditätsprüfung
im Backend (`credit/LoanService.java:133`) nutzt den bis zu 60 s alten Snapshot und reicht als letzte Sicherung
nicht aus.

**Problem 2:** Für das Event `BridgeEvents.InstructionAcked` gibt es im ganzen Backend **keinen Listener**. Ein
`FAILED`/`REJECTED`-Ack wird nur geloggt (`bridge/BridgeSyncService.java:183`). Die Kreditrate gilt trotzdem als
bezahlt, das Gehalt als überwiesen, und der Vertrauensbonus „Rate pünktlich“ ist schon vergeben
(`credit/LoanService.java:163-172`).

**Entscheidung:** Der Mod lehnt ab, das Backend behandelt es wie eine verpasste Zahlung.

**Umsetzung:**

- Mod: Bei `amount < 0` vor der Buchung `farm.money` prüfen (`g_farmManager:getFarmById(farmId).money`, wird
  schon in `collectFarmFacts` gelesen). Reicht es nicht, `false, "INSUFFICIENT_FUNDS"` zurückgeben; das ergibt
  ein `FAILED`-Ack mit dieser Meldung.
- Backend: Listener auf `InstructionAcked` mit Status ≠ `APPLIED`, je nach `relatedEntityType`:
  - Kreditrate: Zahlung zurücknehmen (Restschuld, `paidInstallments`, `nextDueGameTime`, Vertrauens-Event) und
    als verpasste Rate behandeln, sodass die Mahnstufen greifen.
  - Gehalt: Gehalt bleibt fällig, bestehende Logik für Gehaltsverzug.
  - Mahngebühr und Kreditkündigung: nächste Eskalationsstufe.
  - Alle übrigen: Hinweis auf dem Dashboard und im Log.

**Beleg:** Kontostand-Zugriff wie im bestehenden Code und bei FS25_UsedPlus (`farm.money`). Die Reaktion im Backend
ist Entscheidung und Design.

### T-04 Leasing-Fahrzeuge aus dem Vermögen nehmen, Leasingkosten als Verpflichtung

- [x] Mod: nur eigene Fahrzeuge als Vermögen exportieren
- [x] Mod: Leasing-Fahrzeuge und deren laufende Kosten separat exportieren (Schema-Erweiterung)
- [x] Backend: Leasingkosten in Cashflow und Verpflichtungen der Bonitätsprüfung
- [x] Bridge-Protokoll-Doku und Simulator-Szenarien anpassen

**Problem:** `collectFarmFacts` zählt jedes Fahrzeug des Hofs mit `getSellPrice()` als Vermögen
(`mod/FS25_RPSim/src/game/GameAdapter.lua:95`). `Vehicle:getSellPrice()` ignoriert den Besitzstatus, also zählen
geleaste Fahrzeuge voll mit (`backend/.../bridge/FactsService.java:72`). Die Bonität fällt zu gut aus.

**Entscheidung:** Leasing-Fahrzeuge raus aus dem Vermögen, Leasingkosten rein als Verpflichtung.

**Umsetzung:**

- Filter `v.propertyState == VehiclePropertyState.OWNED` für `assets.vehicles`.
- Neues Feld, z. B. `liabilities.leasing = [{ uniqueId, costPerPeriod }]`.
- **Im Spiel prüfen:** Wie ermittelt man die laufenden Leasingkosten pro Fahrzeug? Kandidaten:
  `Vehicle:getDailyUpkeep()` (im FS25-Code vorhanden, `Vehicle.lua:3871`) plus der Leasing-Faktor des
  `EconomyManager`, oder die Finanzstatistik der Farm (Kategorie Leasingkosten). Bis das geklärt ist, nur den
  Vermögensfilter umsetzen.

**Beleg:** `VehiclePropertyState.OWNED`/`LEASED` im FS25-Code (`Vehicle.lua:1287-1289`). FS25_UsedPlus filtert für
Kreditsicherheiten genau so (`src/data/CreditSystem.lua`, `src/gui/TakeLoanDialog.lua`).

### T-05 Festpreis-Kontrakt: die letzte Teillieferung zum Kontraktpreis abrechnen

- [x] `sellFillType`-Hook auf `overwrittenFunction` umstellen und erst nach dem Verkauf zählen
- [x] Test: Lieferung, die den Kontrakt genau füllt bzw. überschreitet

**Problem:** Der Hook ist ein `prependedFunction` (`mod/FS25_RPSim/src/RPSim.lua:106`). Er zählt die Menge, **bevor**
der Spielcode den Preis über `getEffectiveFillTypePrice` berechnet. Die Lieferung, die den Kontrakt voll macht,
beendet ihn dadurch vorzeitig und wird zum Marktpreis bezahlt. Beim Abladen vom Anhänger, das in vielen kleinen
Portionen läuft, fällt das kaum auf. Bei Paletten oder Ballen, die auf einmal verkauft werden, trifft es die
ganze Ladung.

**Umsetzung:** `Utils.overwrittenFunction`: erst `superFunc(self, farmId, fillDelta, fillTypeIndex, ...)` aufrufen,
danach `recordSale`. Die FS25-Signatur lautet
`sellFillType(farmId, fillDelta, fillTypeIndex, fillPositionData, toolType, extraAttributes)`. Für die Menge
`fillDelta` verwenden, nicht den Rückgabewert.

**Beleg:** FS25_MarketDynamics (`src/PriceHook.lua`), ein veröffentlichter Mod mit genau diesem Muster, inklusive des
Hinweises, dass der Rückgabewert in FS25 unzuverlässig ist.

### T-06 Pfad zum `modSettings`-Ordner absichern

- [x] Pfad über `getUserProfileAppPath()` bilden
- [x] Beim Start den vollständigen Bridge-Pfad ins Log schreiben

**Problem:** `RPSimBridgePaths.new(g_modSettingsDirectory or "./modSettings/")` (`mod/FS25_RPSim/src/RPSim.lua:28`).
Für `g_modSettingsDirectory` gibt es in FS25 keinen Beleg. Der Fallback `./modSettings/` wäre relativ zum
Installationsordner des Spiels, dort sucht das Backend nicht.

**Umsetzung:** `g_modSettingsDirectory or (getUserProfileAppPath() .. "modSettings/")`.

**Beleg:** FS25-Mods verwenden durchgängig `getUserProfileAppPath() .. "modSettings/"` (Farm Dashboard
`FarmDashboardDataCollector.lua`, FS25_UsedPlus-Referenz `advanced/hud-framework.md`, WeezlsModLib).

---

## P2 – Robustheit, Kompatibilität, Doku

### T-07 Dateischreiben ohne `os`-Modul vereinfachen

- [x] Neuen Schreibmodus `direct` als Standard: Datei direkt schreiben, ohne `.tmp`/`.ready`
- [x] `os.time()`-Fallback entfernen
- [x] Bridge-Protokoll-Doku anpassen

**Problem:** In der FS25-Sandbox existiert `os` nicht (`os.time`/`os.date` fehlen, `os.rename`/`os.remove`
damit auch). Der Modus `auto` (`mod/FS25_RPSim/src/bridge/Config.lua:15`,
`mod/FS25_RPSim/src/util/FileIO.lua:83`) schreibt deshalb bei jedem Export eine `.tmp`-Datei, die nie umbenannt
wird, und fällt auf den Marker-Modus zurück. Dessen `.ready`-Datei kann nie gelöscht werden, der Marker hat also
keine Aussagekraft. Das Backend ignoriert ihn ohnehin und verlässt sich darauf, unvollständiges JSON zu verwerfen
und im nächsten Zyklus neu zu lesen (`backend/.../bridge/BridgeFiles.java:61-81`). Das funktioniert schon heute.

In `mod/FS25_RPSim/src/RPSim.lua:45` steht `tostring(os.time())` als Fallback, falls `getDate` fehlt. Dieser
Fallback würde abstürzen. `getDate` ist in FS25 vorhanden, der Fallback ist also toter Code.

**Entscheidung:** Kein ModHub → `io.open` bleibt.

**Beleg:** FS25_UsedPlus-Referenz `pitfalls/what-doesnt-work.md` („os.time() and os.date() are NOT available“),
FS25_MarketDynamics (`FuturesMarket.lua`: „os.time() is unavailable in FS25“). `io.open` zum Schreiben nutzt Farm
Dashboard produktiv. `getDate(...)` kommt im offiziellen Code vor (`PlayerSystem`) und in BetterContracts.

### T-08 Spielmonat = FS25-Periode

- [x] Mod: Kalenderdaten exportieren
- [x] Backend: `GameTime` auf die exportierte Periode umstellen, `rpsim.time.*` entfernen
- [x] Geplante Termine (Gehalt, Raten, Rotation, Einladungen) auf Perioden umrechnen
- [x] Doku, Konfigurationsreferenz, Tests, Bridge-Simulator

**Heute:** Fester Zähler `rpsim.time.game-days-per-month: 1` / `months-per-year: 12`
(`backend/src/main/resources/application.yml:36-38`, `config/RpsimProperties.java:52-54`, `time/GameTime.java:8`,
`village/VillageRotationService.java:36`, `village/VillageLifeService.java:108`).

**Entscheidung:** Nur noch die FS25-Periode, die Konfiguration entfällt.

**Umsetzung:**

- `farm_facts.json` erweitern um
  `calendar = { period, dayInPeriod, daysPerPeriod, year, monotonicDay }` aus `g_currentMission.environment`
  (`currentPeriod`, `currentDayInPeriod`, `daysPerPeriod`, `currentYear`, `currentMonotonicDay`).
- „Tage pro Periode“ kann der Spieler in FS25 jederzeit ändern. Termine deshalb in Perioden rechnen, nicht in
  festen Millisekunden, oder bei jedem Export mit dem aktuellen `daysPerPeriod` neu umrechnen.
- Achtung: In FS25 ist Periode 1 der **März**. Das ist wichtig für Anzeigen wie Monatsnamen und Geburtstage.

**Beleg:** FS25_UsedPlus nutzt `environment.currentPeriod`, `daysPerPeriod` und `currentMonotonicDay`
(`src/data/CreditSystem.lua`, `src/extensions/FarmExtension.lua`). Farm Dashboard exportiert dieselben Felder.
Damit ist der offene technische Punkt #4 gelöst.

### T-09 Konflikt-Mods erkennen und warnen

- [x] Mod: bekannte Mods beim Laden erkennen und in `market_context.json` melden (z. B. `detectedMods: [...]`)
- [x] Backend/Frontend: Hinweis auf Dashboard und Einstellungsseite
- [x] Nutzer-Doku (Fehlerbehebung) ergänzen

**Überschneidungen:**

| Mod | Überschneidung |
| --- | --- |
| `FS25_MarketDynamics` | Hookt dieselben Funktionen `getEffectiveFillTypePrice` und `sellFillType`. Die Preisfaktoren multiplizieren sich, eigene Welt-Ereignisse doppeln sich mit den RPSim-Markt-Ereignissen. |
| `FS25_UsedPlus` | Eigene Kredite, Bonität und Leasing. Erhöht `farm.loan`, das RPSim als `vanillaLoan` exportiert. |
| `FS25_EnhancedLoanSystem` | Ersetzt den Vanilla-Kredit. |
| `FS25_BetterContracts` | Ändert Aufträge und Farmland-Preise. Erst relevant für T-22. |

Die genauen Mod-Ordnernamen vor der Umsetzung noch einmal prüfen.

**Entscheidung:** Erkennen und warnen, nichts abschalten.

**Umsetzung:** `g_modIsLoaded["<ModName>"]`. Direkter Zugriff auf Globals anderer Mods geht wegen der
Mod-Sandbox nicht.

**Beleg:** FS25_UsedPlus `src/utils/ModCompatibility.lua` (Erkennung über `g_modIsLoaded`).

### T-10 Verkaufsstellen sauberer erkennen und filtern

- [x] `station:isa(SellingStation)` statt `station.isSellingPoint` (`mod/FS25_RPSim/src/game/GameAdapter.lua:79`)
- [x] Stationen mit `station.hideFromPricesMenu` nicht exportieren, sonst plant das Backend Events an Stellen, die der Spieler nicht sieht
- [x] Optional: `station:getCurrentPricingTrend(fillType)` mitexportieren (Preistrend für Gerüchte und Diagramme)

**Beleg:** FS25_ProductionDirectSell (`scripts/PDS_Manager.lua`: `isa(SellingStation)`, `hideFromPricesMenu`,
`getCurrentPricingTrend`).

### T-11 Nicht kaufbare Farmlands kennzeichnen

- [x] In `market_context.json` je Farmland `showOnFarmlandsScreen` (und `defaultFarmProperty`) mitgeben (`GameAdapter.lua:203`)
- [x] Backend: Nicht kaufbare Flächen nicht an NPCs verteilen und nicht zum Kauf oder Verkauf anbieten (`FarmlandOwnershipService`, `NegotiationEngine`)

**Hintergrund:** `setLandOwnership` lehnt nur `NOT_BUYABLE_FARM_ID` ab. Flächen, die im Vanilla-Menü ausgeblendet sind
(Ortschaft, Straßen), würden heute verhandelbar.

**Beleg:** `Farmland.lua` im FS25-Code (`showOnFarmlandsScreen`, `defaultFarmProperty`), `FarmlandManager.lua:447`
(`setLandOwnership`).

### T-12 Offene technische Punkte aktualisieren

In `docs/dev/offene-technische-punkte.md` mit dem Stand dieser Analyse nachziehen:

- [x] #1 `os.rename`: nicht verfügbar → T-07
- [x] #3 Negativer Kontostand: durch Entscheidung T-03 ersetzt
- [x] #4 Periode/Saison: gelöst → T-08
- [x] #6 `setLandOwnership`: bestätigt im FS25-Code
- [x] #10 Hook-Namen: bestätigt (UsedPlus, MarketDynamics); Ladezeitpunkt → T-01
- [x] #13 `io.open`: bestätigt (Farm Dashboard)
- [x] #2 (Station-ID) und #8 (Silo-Erkennung) bleiben offen → T-13
- [x] Die zugehörigen `TODO(offene-frage)`-Kommentare im Code anpassen oder entfernen

### T-13 Prüfliste für den ersten Test im echten FS25

Diese Punkte lassen sich ohne laufendes Spiel nicht belegen. Sie gehören in `docs/dev/manual-test-plan.md`
(übernommen in Abschnitt 8 „First test in the real FS25“; die Ausführung im Spiel steht noch aus):

- [x] Ladezeitpunkt: Was sieht der erste Export (Anzahl Verkaufsstellen, Fahrzeuge, Felder)? → T-01
- [x] Existiert `g_modSettingsDirectory`, und wo landen die Bridge-Dateien? → T-06
- [x] Preis-Event (Multiplikator): Anhänger verkaufen und prüfen, ob sich die Auszahlung wirklich ändert und ob das Preismenü im Spiel den geänderten Preis zeigt
- [x] Festpreis-Kontrakt mit Palettenverkauf → T-05
- [x] Bleibt `sellPointId` (uniqueId der Station) nach Speichern und Neuladen gleich? (offener Punkt #2)
- [x] Silo-Erkennung: Wie heißt die Shop-Kategorie der Silos in FS25 wirklich (`SILOS`?), und werden Silo-Erweiterungen (`spec_siloExtension`) erfasst? (offener Punkt #8, `mod/FS25_RPSim/src/export/Storage.lua`)
- [x] Tier-Export: Liefern `getClusters()`, `cluster:getNumAnimals()` und `cluster:getSellPrice()` in FS25 Werte?
- [x] Feld an den Spieler übertragen: Laufen Aufträge und Anzeigen im Feld-Menü danach korrekt?
- [x] Neu laden ohne Speichern → T-02
- [x] Abbuchung bei zu wenig Geld → T-03

### T-14 Tests nachziehen

- [x] Mod-Tests (`mod/tests/`) für T-01, T-03, T-04, T-05, T-07, T-08, T-10, T-11
- [x] Backend-Tests für T-02, T-03, T-04, T-08, T-11
- [x] Bridge-Simulator-Szenarien: Neuladen ohne Speichern, zu wenig Geld, Leasing-Fahrzeuge, Konflikt-Mod erkannt

---

## P3 – Neue Funktionen

Reihenfolge nach Priorität: **neue Charaktere zuerst**, danach Spiel-Integration und Vertragsarten.

### T-20 Neue Charaktere – höchste Priorität

**Entscheidung:** Die Anlässe werden **nur simuliert**. Das Backend erzeugt sie wie die bestehenden
Markt-Ereignisse (eigene Taktung und Obergrenze, alle Werte in `rpsim.formulas.*`), ohne echte Spielereignisse
auszulesen. Die vorhandenen Exportdaten dürfen zur Plausibilität dienen, z. B. „Tierarzt nur, wenn Tiere
vorhanden sind“.

Zum Hintergrund: FS25 hat eine `Twister`-Klasse, dokumentiert sind aber nur Netzwerk-Funktionen. Für Hagel und
Wildschweine gibt es keine belegte Lua-Schnittstelle.

- [x] **Versicherung:** Sturm- und Hagelschaden-Ereignisse (FS25 kennt Tornados und Hagel), Versicherungsvertrag
  mit Prämie, Schadensmeldung per Mail oder Anruf, Auszahlung als `MONEY_TRANSACTION`. Neue `MoneyReason` nötig,
  z. B. `INSURANCE_PREMIUM` und `INSURANCE_PAYOUT`, in Mod und Backend.
- [x] **Jäger / Jagdpächter:** Wildschaden (in FS25 durch Wildschweine aus dem Vredo Pack), Entschädigung
  verhandeln, gemeinsame Maßnahmen. Wirkt auf das Dorf-Ansehen.
- [x] **Tierarzt / Viehhändler / Zuchtverband:** Nur bei vorhandenen Tieren (`assets.animals`). Routinebesuche,
  Rechnungen, Kauf- und Verkaufsangebote, Hinweise zu Gesundheit und Nachwuchs (FS25-Tiere vermehren sich).
- [x] **Energieversorger:** Biogas- und Stromabnahme (Biogas-Anlagen seit dem Pumps n'Hoses Pack), Lieferverträge,
  Preisschwankungen.
- [x] Für jeden Charakter: Rollen-Definition, Persönlichkeitsvorlagen, Fallback-Texte ohne KI (Deutsch),
  Formeln als Konfiguration, Tagebucheinträge, Frontend-Darstellung.

### T-21 Spiel-Integration

- [x] **Echte NPC-Feldbesitzer:** FS25 weist jedem Farmland einen NPC zu. `farmland.npcIndex` →
  `g_npcManager:getNPCByIndex(npcIndex)` in `market_context.json` exportieren und als Besitzer-Charakter
  übernehmen, statt eigene zu erfinden. **Beleg:** `Farmland.lua` (FS25-Code), BetterContracts
  (`scripts/options.lua`).
- [x] **Hinweise im Spiel:** Neue Mails und eingehende Anrufe als Einblendung im Spiel, damit man nicht in den
  Browser wechseln muss. Umsetzung: `g_currentMission:addIngameNotification(FSBaseMission.INGAME_NOTIFICATION_INFO, text)`,
  ausgelöst über einen neuen Anweisungstyp (z. B. `NOTIFICATION`) oder eine eigene Datei. **Beleg:**
  MarketDynamics, `BeehiveSystem` (offizieller Code).
- [x] **Eigene Kategorien in der Finanzübersicht:** Buchungen statt „Sonstiges“ (`MoneyType.OTHER`) über
  `MoneyType.register(statistikName, titelKey)` eigenen Kategorien zuordnen (Kredit, Gehalt, Förderung).
  **Beleg:** `FillTrigger` im offiziellen Code. **Im Spiel prüfen:** gültige Statistik-Namen in FS25.
- [x] **Kalenderbezug in Texten:** Mit T-08 können Charaktere echte FS25-Monate und Jahreszeiten nennen
  („Ende Oktober“, „nach der Ernte“).

### T-22 Neue Vertragsarten

- [x] **Pacht:** Felder von NPC-Besitzern pachten statt kaufen (gibt es in Vanilla nicht). Pachtzins als
  wiederkehrende `MONEY_TRANSACTION`; Nutzungsrecht über `FARMLAND_TRANSFER` auf Zeit. Rückgabe bei Vertragsende
  ist neu im Protokoll.
- [x] **Wartungsverträge vom Mechaniker:** Der Fahrzeugzustand (`condition`) wird schon exportiert.
  Wartungsangebote, Pauschalen und Reparaturhinweise. Eine echte Reparatur im Spiel wäre ein neuer Anweisungstyp
  (API im Spiel prüfen).
- [x] **Lieferverträge mit Produktionen / Hofladen:** Wie `PRICE_EVENT` (`FIXED`), aber für Produktionen als
  Abnehmer. Dafür müssen Produktionen in `market_context.json` erfasst werden.
- [x] **Vanilla-Aufträge über Charaktere:** Feldarbeits-Aufträge des Spiels werden von Dorfbewohnern „vermittelt“
  (Lohnunternehmer-Rolle). Die Contracts/Missions-API ist in der LUADOC vorhanden, im Detail aber noch zu prüfen.
  Kompatibilität mit BetterContracts beachten (T-09).

---

## Quellen

- FS25 Community LUADOC: <https://github.com/umbraprior/FS25-Community-LUADOC>
- GIANTS GDN (offizielle LUADOC): <https://gdn.giants-software.com/documentation.php>
- FS25-Quellcode-Dump (dataS): <https://github.com/Dukefarming/FS25-lua-scripting>
- FS25_UsedPlus inkl. „FS25 AI Coding Reference“: <https://github.com/XelaNull/FS25_UsedPlus>
- FS25_MarketDynamics: <https://github.com/Realistic-Farming/FS25_MarketDynamics>
- FS25 Farm Dashboard: <https://github.com/WizardlyPayload/FS25-Farm-Dashboard>
- FS25_ProductionDirectSell: <https://github.com/iNotrez/FS25_ProductionDirectSell>
- FS25_BetterContracts: <https://github.com/Mmtrx/FS25_BetterContracts>
- FS25_WeezlsModLib: <https://github.com/w33zl/FS25_WeezlsModLib>
- FS25 Roadmap 2026: <https://www.farming-simulator.com/newsArticle.php?news_id=663>
