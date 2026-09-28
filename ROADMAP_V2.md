# FarmPulse – Roadmap V2

Ausbauplan nach der Projekt-Analyse vom 28.09.2026. Ziel: Das Rollenspiel soll stärker mit dem echten Spielgeschehen
in Farming Simulator 25 verbunden werden. Heute sind viele Folgen reine Geldbuchungen (Mitarbeiter, Unwetter,
Wildschaden). Künftig sollen echte Spielwerte (Helfer, Buchungen, Felder, Tiere, Wetter) die Geschichten auslösen und
beeinflussen.

Diese Datei enthält **nur Punkte, die mit der FS25-Schnittstelle machbar sind**. Jede Idee wurde gegen den
FS25-Quellcode-Dump und die Community-LUADOC geprüft (Quellen am Ende). Was nicht machbar ist, steht mit Begründung in
[Bewusst nicht aufgenommen](#bewusst-nicht-aufgenommen), damit die Entscheidung später nachvollziehbar bleibt.

**Grundsätze (unverändert aus V1)**

- Der Mod bleibt **Sensor und Aktuator**: Er liest Spielwerte und führt Anweisungen aus. Alle Entscheidungen und
  Zahlen berechnet das Backend nach festen, konfigurierbaren Formeln (`rpsim.formulas.*`). Die KI formuliert nur.
- Jeder Zugriff auf FS25 läuft in `RPSimGameAdapter` und ist mit `pcall` abgesichert. Fehlt eine API, fällt die Funktion
  still auf das V1-Verhalten zurück.
- Neue Felder in den Bridge-Dateien sind **optional**. Ein älterer Mod oder der Bridge-Simulator ohne diese Felder
  darf das Backend nicht stören.
- Beträge gibt der Spieler nie im Freitext ein. Ja/Nein-Entscheidungen im Spiel (Punkt F) sind nur für Angebote
  erlaubt, deren Zahlen das Backend festgelegt hat.

## Legende

| Zeichen | Bedeutung |
| --- | --- |
| ✅ **Belegt** | Die genutzte Funktion bzw. das Feld steht so im FS25-Code; Fundstelle ist angegeben. |
| 🟡 **Im Spiel prüfen** | Die API existiert, aber ein Detail (Aktualität, Anzeige, Einheit) lässt sich nur im laufenden Spiel klären. Zu jedem 🟡 steht ein Fallback. |
| **Beleg** | Datei im FS25-Code (Dump `Dukefarming/FS25-lua-scripting`) oder Seite der Community-LUADOC. |

Punkt-IDs: `R2-<Bereich><Nummer>`, z. B. `R2-A1`. Alle Pfade sind relativ zum Repo-Root.

## Übersicht und empfohlene Reihenfolge

| Phase | Bereich | Inhalt | Hängt ab von |
| --- | --- | --- | --- |
| 0 | [Q – Querschnitt](#q--querschnitt-vor-dem-ersten-feature) | Bridge-Erweiterung, Simulator, Testplan, Doku | – |
| 1 | [B – Echte Hof-Finanzen](#b--echte-hof-finanzen) | Buchungsjournal aus dem Spiel, echter Cashflow, Leasingkosten | Q |
| 2 | [A – Mitarbeiter ↔ FS25-Helfer](#a--mitarbeiter--fs25-helfer) | Helferlohn, Namen, Arbeitszeit, Streik, Mechaniker, Tierpfleger | Q (B hilft) |
| 3 | [C – Felder, Kulturen, Wetter](#c--felder-kulturen-und-wetter) | Feldzustand und Wetter lesen, Unwetter/Wildschaden plausibel machen | Q |
| 4 | [D – Umgehung des Grundspiels](#d--umgehung-des-grundspiels-ins-rollenspiel-holen) | Vanilla-Kredit und Vanilla-Feldkauf als RP-Ereignis | – |
| 5 | [E – Neue RP-Bereiche](#e--neue-rollenspiel-bereiche) | Finanzamt, Amt/Kontrollen, Familie, Vereine | E1 ← B, E2 ← C + A7 |
| 6 | [F – Entscheidungen im Spiel](#f--entscheidungen-direkt-im-spiel) | Rückkanal, Ja/Nein-Dialog im Spiel | Q |

B zuerst, weil der Cashflow die Grundlage für Bank, Steuern (E1) und die Mitarbeiter-Auswertung ist und nur einen
kleinen Mod-Eingriff braucht. D ist unabhängig und kann jederzeit dazwischen umgesetzt werden.

---

## Q – Querschnitt (vor dem ersten Feature)

Arbeiten, die jeder Bereich braucht. Einmal sauber anlegen, dann bei jedem Punkt nur ergänzen.

**Stand 28.09.2026: umgesetzt.** Q legt den Vertrag an (Schemas, DTOs, Validator, Simulator, Doku). Das Auslesen der
Spielwerte im Mod folgt mit B1, A4, A7, C1 und C2; bis dahin fehlen die Blöcke in `farm_facts.json`. Entscheidungen
(siehe `QUESTIONS.md`): `finances = { periods: [{ year, period, byType }] }`, Einträge in `husbandries[]` tragen die
`husbandryUniqueId` aus `assets.animals`, nur die neuen Simulator-Szenarien liefern die Blöcke.

### R2-Q1 Bridge-Schema erweitern, ohne alte Stände zu brechen

- [x] Neue Blöcke in `farm_facts.json` als optionale Felder anlegen (wie `calendar` und `missions` in V1):
  `finances` (B), `workforce` (A), `husbandries` (A7), `fields` und `weather` (C).
  Mod: `RPSimFarmFacts.build` normalisiert die Blöcke, sobald der Adapter sie liefert.
- [x] `schemaVersion` bleibt `1`, solange alle neuen Felder optional sind. `BridgeDtos` und `BridgeValidator` lesen
  fehlende Blöcke als „nicht vorhanden“ (nicht als leer), damit das Backend zwischen „Mod zu alt“ und „nichts da“
  unterscheiden kann.
- [x] Neue Anweisungstypen im Mod registrieren (`RPSimInstructions.TYPES`) und validieren:
  `EMPLOYEE_ROSTER` (A), `PROMPT` (F). `REPAIR_VEHICLE` bekommt ein optionales Feld `targetDamage` (A6).
  `EMPLOYEE_ROSTER` und `PROMPT` quittiert der Mod bis A0 bzw. F2 mit `FAILED` / `NOT_SUPPORTED`; `targetDamage`
  wirkt schon (eine Reparatur erhöht den Schaden nie).
- [x] Neue `MoneyReason`-Werte in Mod (`RPSimInstructions.MONEY_REASONS`), Backend (`domain/MoneyReason.java`) und
  Buchungstiteln (`modDesc.xml`, `rpsim_money_<REASON>`): `TAX_PAYMENT`, `TAX_REFUND`, `FINE`, `FAMILY`,
  `SPONSORING`, `COMPENSATION`.
- [x] `docs/dev/bridge-protocol.md` je Feld mit Quelle im FS25-Code ergänzen.

**Warum:** V1 hat gezeigt, dass optionale Felder (Kalender, Aufträge) problemlos nachrüstbar sind. Ein harter
Versionssprung würde bestehende Spielstände und den Simulator gleichzeitig brechen.

### R2-Q2 Bridge-Simulator und Tests

- [x] `tools/bridge-simulator` erzeugt jeden neuen Block (Szenarien z. B. `helfer-hof`, `tierhof-krank`,
  `ernte-herbst`) und versteht die neuen Anweisungstypen.
- [x] JSON-Schemas des Simulators erweitern (Validierung in CI: `npm test` des Simulators läuft jetzt im
  Backend-Workflow).
- [x] Mod-Tests (`mod/tests/`) für jede neue Adapter-Funktion mit gemockten FS25-Globals, wie in
  `test_game_adapter.lua`. In Q: Teilreparatur (`repairVehicle` mit `targetDamage`), Normalisierung der Blöcke,
  Validierung der neuen Anweisungen. Gilt weiter für jede Adapter-Funktion der Features.
- [x] Backend: Grenzwert-Tests für jede neue Formel (wie `CreditFormulaTest`), End-to-End-Test gegen den Simulator.
  Q bringt keine Formel; `BridgeValidatorTest` und `SimulatorScenariosEndToEndTest` prüfen die neuen Blöcke.

### R2-Q3 Konfiguration und Doku

- [x] Alle neuen Werte unter `rpsim.formulas.*` in `backend/src/main/resources/application.yml` und
  `config/RpsimProperties.java`. **Achtung:** `ConfigurationReferenceDocTest` schlägt fehl, wenn ein Schlüssel in
  `docs/dev/configuration-reference.md` fehlt. Q bringt keine neuen Werte; die Regel gilt für jedes Feature.
- [x] Neue Mod-Schalter in `RPSimConfig.DEFAULTS` (überschreibbar über `rpsim_config.xml`). Q bringt keine neuen
  Schalter (`financeJournalPeriods`, `fieldExportIntervalMs` kommen mit B1 bzw. C1).
- [x] Spieler-Doku `docs/user-guide/funktionen.md` je Feature, Eintrag in `CHANGELOG.md`. Q ist für Spieler nicht
  sichtbar, daher nur der `CHANGELOG`-Eintrag.

### R2-Q4 Prüfliste für den Spieltest erweitern

- [x] In `docs/dev/manual-test-plan.md` einen neuen Abschnitt **„10. Roadmap V2 im echten FS25“** anlegen. Jeder
  🟡-Punkt dieser Roadmap bekommt dort eine Zeile mit „Wie prüfen“ und „Erwartet“.

---

## B – Echte Hof-Finanzen

**Problem heute:** Die Bank berechnet den Cashflow aus der Differenz zweier Kontostände abzüglich der eigenen
Tool-Buchungen (`credit/CreditScoringService.java`, `monthlyCashflow`). Ein Traktorkauf und ein schlechtes Erntejahr
sehen darin gleich aus. Leasingkosten pro Fahrzeug sind unbekannt (offener Punkt aus T-04). Das Tool weiß nicht, womit
der Hof sein Geld verdient.

**Idee:** Der Mod protokolliert jede Buchung des Spiels nach FS25-Kategorie (`MoneyType`). Das Backend bekommt damit
eine echte Einnahmen-/Ausgabenrechnung.

**Stand 28.09.2026: umgesetzt.** Entscheidungen (siehe `QUESTIONS.md`): Als Investition zählen nur im FS25-Code
belegte Namen (`SHOP_PROPERTY_BUY`, Desinvestition `SHOP_VEHICLE_SELL`). Der Name des Fahrzeugkaufs ist nicht belegt;
bis er im Spiel geprüft ist (Testplan 10.9), zählt ein Fahrzeugkauf operativ. Die Akzeptanz „Maschinenkauf senkt den
Cashflow nicht“ gilt deshalb erst, wenn der Name in `rpsim.formulas.finance.categories` steht. Der Cashflow ist der
Durchschnitt der abgeschlossenen Monate im Fenster, die Frühwarnung kommt nur bei laufendem Bankkredit, das
Monatsergebnis der Oberfläche ist das operative Ergebnis. Der Name eines `moneyType` wird per Rückwärts-Abgleich mit
der Tabelle `MoneyType` bestimmt; nicht gefundene Buchungen heißen `UNKNOWN`.

### R2-B1 Buchungsjournal im Mod

- [x] Hook auf `Farm.changeBalance` (`Utils.appendedFunction`): Betrag und `moneyType` jeder Buchung der
  Spieler-Farm erfassen.
- [x] Summen je FS25-Periode (Monat) und Kategorie bilden, z. B.
  `{ year: 2, period: 8, byType: { HARVEST_INCOME: 48200, PURCHASE_FUEL: -3100, AI: -1250 } }`.
- [x] Die letzten N Perioden (Konfig `financeJournalPeriods`, Vorschlag 13) im Savegame-XML speichern
  (`import/Persistence.lua`) und als `farm_facts.finances` exportieren.
- [x] Eigene RPSim-Buchungen kennzeichnen: In `RPSimGameAdapter:addMoney` ein Flag um den Aufruf setzen, damit diese
  Beträge unter `RPSIM_<REASON>` statt unter der FS25-Kategorie landen.
- [x] Die Zähler sind kumulativ je Periode. Nach einem Neuladen ohne Speichern springen sie zurück, das Backend
  übernimmt einfach den neuen Stand (kein Delta-Protokoll nötig).

**Beleg:** ✅ `Farm:changeBalance(amount, moneyType)` ist in der LUADOC dokumentiert (`script/Farms/Farm.md`, „Add or
remove money from the farm“), ebenso `Farm:getId()`. ✅ Die Kategorien sind im Code belegt, u. a. `MoneyType.HARVEST_INCOME`,
`SOLD_PRODUCTS`, `MISSIONS`, `PROPERTY_INCOME`, `PURCHASE_FUEL`, `PURCHASE_SEEDS`, `PURCHASE_FERTILIZER`,
`PURCHASE_WATER`, `BOUGHT_MATERIALS`, `VEHICLE_RUNNING_COSTS`, `VEHICLE_REPAIR`, `LEASING_COSTS`, `AI`,
`SHOP_VEHICLE_SELL` (Aufrufe `g_currentMission:addMoney(..., MoneyType.X, ...)` in `AIJob.lua`, `Wearable`,
`FillTrigger`, `PlaceableTrainSystem`, `Combine` u. a.).

**🟡 Im Spiel prüfen:**

- Laufen wirklich alle Buchungen über `Farm:changeBalance`? **Fallback:** Hook auf `FSBaseMission.addMoney` (Signatur
  aus den Aufrufen belegt: `addMoney(amount, farmId, moneyType, addChange, forceShowChange)`).
- Wie heißt das Namensfeld eines `moneyType`-Objekts (Statistik-Name, z. B. `harvestIncome`)? **Fallback:** Kategorie
  über einen Rückwärts-Abgleich mit der globalen Tabelle `MoneyType` bestimmen (gleiches Muster wie
  `RPSimGameAdapter.seasonName` mit der Tabelle `Season`).

### R2-B2 Cashflow der Bank aus dem Journal

- [x] Neue Konfiguration `rpsim.formulas.finance.categories`: jede Kategorie gehört zu genau einer Klasse
  `OPERATING_INCOME`, `OPERATING_EXPENSE`, `INVESTMENT`, `DIVESTMENT`, `FINANCING` oder `IGNORE`.
  Vorschlag: `HARVEST_INCOME`, `SOLD_PRODUCTS`, `MISSIONS`, `PROPERTY_INCOME` → Einnahmen; `PURCHASE_*`, `AI`,
  `VEHICLE_RUNNING_COSTS`, `VEHICLE_REPAIR`, `LEASING_COSTS`, `BOUGHT_MATERIALS` → Ausgaben; Fahrzeug- und
  Gebäudekäufe → Investition; `RPSIM_CREDIT_*` → Finanzierung.
- [x] Unbekannte Kategorien (Mods, künftige FS25-Versionen) nach Vorzeichen als operativ werten und einmal loggen.
- [x] `CreditScoringService`: Liegt ein Journal vor, ist der operative Cashflow = Einnahmen + Ausgaben der letzten
  `cashflow-window-days`. Sonst weiter das V1-Verfahren (Kontostand-Differenz).
- [x] Investitionen gehen **nicht** in den Cashflow ein, sondern verändern nur das Vermögen. So senkt ein
  Maschinenkauf die Bonität nicht mehr fälschlich wie ein Verlust.

### R2-B3 Echte Leasingkosten

- [x] `LEASING_COSTS` je Periode ersetzt die Schätzung `FactsService.leasingCostPerMonth` in der Bonitätsprüfung.
- [x] Die Kosten pro Fahrzeug bleiben unbekannt. Für die Bank reicht die Summe.
- [x] Offenen Punkt T-04 in `docs/dev/offene-technische-punkte.md` als gelöst markieren.

### R2-B4 Hofbuchhaltung in der Oberfläche

- [x] Neue Karte bzw. Tab unter **Bank & Finanzen**: Einnahmen und Ausgaben je Monat nach Kategorie (gestapeltes
  Balkendiagramm, mit *Tabelle* als Zahlen wie beim Preisverlauf) und ein Monatsergebnis.
- [x] Deutsche Namen der Kategorien in `frontend/src/app/core/i18n/de.json`; unbekannte zeigen den Rohnamen.

### R2-B5 Charaktere reagieren auf echte Zahlen

- [x] **Bank-Frühwarnung:** Ist der operative Cashflow zwei Perioden in Folge negativ, meldet sich die Bank, bevor
  eine Rate platzt (Ton je nach Weltmodus).
- [x] **Genossenschaft:** Rekord-Ernteerlös in einer Periode (höchster `HARVEST_INCOME + SOLD_PRODUCTS` seit
  Spielbeginn) → Glückwunsch, kleiner Vertrauensbonus.
- [x] Prompt-Fakten (`NarrationFacts`) um echte Kennzahlen ergänzen, damit Texte konkrete Beträge nennen können
  („Ihre Verkaufserlöse im Oktober lagen bei …“). Die Zahlen kommen aus dem Backend, nie aus der KI.

**Akzeptanz B:** Ein Maschinenkauf senkt den Cashflow in der Bonitätsprüfung nicht. Eine Ernte, die im Spiel verkauft
wird, taucht als Einnahme im richtigen Monat auf. Die Leasingkosten eines geleasten Traktors erscheinen als Ausgabe.

---

## A – Mitarbeiter ↔ FS25-Helfer

**Problem heute:** Mitarbeiter existieren nur im Tool. Ein Maschinenführer erhält Gehalt und erzeugt eine monatliche
Geldbuchung (`EMPLOYEE_EFFECT`, `employee/SatisfactionService.java`), fährt aber nie im Spiel. Gleichzeitig zahlt der
Spieler für jeden FS25-Helfer den Stundenlohn des Spiels, also **doppelt**. Die Arbeitsbelastung sinkt nur simuliert.

**Idee:** Angestellte Maschinenführer *sind* die FS25-Helfer. Ihr Lohn läuft über das Tool, ihre Namen erscheinen im
Spiel, ihre echte Arbeitszeit bestimmt die Arbeitsbelastung, und wer sehr unzufrieden ist, legt die Arbeit nieder.

**Stand 28.09.2026: umgesetzt** (bis auf den Verweis auf E2). Entscheidungen (siehe `QUESTIONS.md`): Die Soll-Stunden
gelten je Spieltag (`workload.target-hours-per-day`, Platzhalter 8; das Backend zählt die Stunden je Spieltag). Das
Gehalt läuft während eines Streiks weiter, der positive Leistungseffekt entfällt. Der positive `EMPLOYEE_EFFECT` von
Maschinenführern skaliert mit den gefahrenen Stunden des Monats. Die Reihenfolge der Liste ist die Priorität der
Zuordnung: Das Backend sortiert nach Skill, die Liste selbst trägt kein Feld `skill`. `helperWageMode` ist immer
`EMPLOYEES` voreingestellt; ohne Maschinenführer wird kein Job zugeordnet, es gilt also der Spiellohn. Die Zuordnung
`jobId → employeeId` wird nicht gespeichert (Job-IDs sind nach dem Laden neu), laufende Jobs werden beim nächsten
Export neu zugeordnet. Wasser erkennt das Backend am Titel der Bedingung (`livestock.water-condition-titles`,
Testplan 10.12). Alle Zahlen sind Platzhalter aus der Konfiguration.

### R2-A0 Mitarbeiterliste an den Mod senden (`EMPLOYEE_ROSTER`)

- [x] Neuer Anweisungstyp `EMPLOYEE_ROSTER`: vollständige Liste
  `[{ employeeId, name, role, status }]` mit `status` = `ACTIVE`, `ON_LEAVE` (freier Tag), `STRIKE` (A5).
  Enthält außerdem die Schalter `helperWageMode` (A1) und `strictHelperLimit` (A3).
- [x] Das Backend sendet die Liste bei jeder Änderung (Einstellung, Kündigung, freier Tag, Streik) und nach jedem
  Neuladen des Spielstands.
- [x] Der Mod ersetzt seine Liste vollständig (idempotent) und speichert sie im Savegame-XML.

### R2-A1 Helferlohn über das Tool-Gehalt statt doppelt

- [x] `Utils.overwrittenFunction` auf `AIJob.getPricePerMs`, `AIJobFieldWork.getPricePerMs` und
  `AIJobConveyor.getPricePerMs` (alle drei definieren die Funktion selbst; `AIJobDeliver`, `AIJobGoTo` und
  `AIJobLoadAndDeliver` erben von `AIJob`).
- [x] Ist dem Job ein Mitarbeiter zugeordnet (A2) und `helperWageMode = "EMPLOYEES"`, gibt der Hook `0` zurück. Sonst
  `superFunc` (normaler Spiellohn).
- [x] Standard: `helperWageMode = "EMPLOYEES"`, sobald mindestens ein Maschinenführer angestellt ist. Mit `"VANILLA"`
  bleibt alles wie im Grundspiel.
- [x] Hinweis in der Oberfläche (Personal-Seite): „Deine Maschinenführer fahren die Helfer im Spiel. Helfer ohne
  freien Maschinenführer kosten den normalen Spiellohn.“

**Beleg:** ✅ `ai/jobs/AIJob.lua`: `AIJob:updateCost(dt)` ruft `self:getPricePerMs()`, rechnet
`price * dt * EconomyManager.getCostMultiplier()` und bucht über `g_currentMission:addMoney(..., MoneyType.AI, true)`.
Bei `price == 0` wird nichts gebucht (Bedingung `if price > 0`). ✅ Werte: `AIJob` 0.0004, `AIJobFieldWork` 0.0005,
`AIJobConveyor` 0.00005.

**Hinweis:** Bei Lohn 0 entfällt auch die Prüfung „Geld reicht nicht → Helfer stoppt“ aus `updateCost`. Das ist
gewollt: Das Gehalt läuft monatlich über das Tool, und dort greift bei zu wenig Geld schon die Logik für
Gehaltsverzug (T-03).

### R2-A2 Helfer bekommen den Namen des Mitarbeiters

- [x] `Utils.appendedFunction` auf `AIJob.start(farmId)`: Nur für die Spieler-Farm dem Job den ersten freien
  Maschinenführer mit Status `ACTIVE` zuordnen (höchster Skill zuerst) und die Zuordnung `jobId → employeeId` im
  Mod-Zustand merken. Laufende Jobs nach dem Laden bei der ersten Abfrage nachträglich zuordnen.
- [x] `Utils.overwrittenFunction` auf `AIJob.getHelperName`: Name des zugeordneten Mitarbeiters, sonst `superFunc`.
- [x] Zuordnung beim Job-Ende freigeben (Abo auf `MessageType.AI_JOB_STOPPED` oder Hook auf `AIJob.stop`).

**Beleg:** ✅ `AIJob:start` wählt `g_helperManager:getRandomHelper()` und setzt `self.helperIndex` und
`self.startedFarmId`. ✅ `AIJob:getHelperName()` gibt `helper.title` zurück. ✅ `ai/errors/AIMessage.lua` baut die
Helfer-Meldungen im Spiel mit `job:getHelperName()` („%s hat die Arbeit beendet“ usw.). ✅ `AISystem:startJobInternal`
und `stopJobInternal` veröffentlichen `MessageType.AI_JOB_STARTED` und `AI_JOB_STOPPED`.

**🟡 Im Spiel prüfen:** Die Meldungen im Spiel zeigen sicher den neuen Namen. Ob auch HUD und Karte den Namen
übernehmen oder dort weiter `helper.title` direkt lesen, zeigt nur der Test. **Fallback:** Dann heißen die Helfer nur
in den Meldungen wie der Mitarbeiter; das reicht für die Immersion.

### R2-A3 Helfer-Limit = Anzahl Maschinenführer (optionaler „strenger Modus“)

- [x] Schalter `strictHelperLimit` (Standard **aus**, einstellbar auf der Einstellungsseite).
- [x] Ist er an, setzt der Mod `g_currentMission.maxNumHirables` auf
  `min(Originalwert, Anzahl Maschinenführer mit Status ACTIVE)`. Den Originalwert merken und beim Ausschalten bzw. in
  `deleteMap` zurückschreiben.
- [x] Kein Maschinenführer + strenger Modus = keine Helfer. Deshalb im Onboarding erklären.

**Beleg:** ✅ `ai/AISystem.lua`: `getAILimitedReached()` = `#self.activeJobVehicles >= g_currentMission.maxNumHirables`.

**🟡 Im Spiel prüfen:** Setzt das Spiel `maxNumHirables` bei bestimmten Ereignissen (Einstellungen, Laden) zurück?
Welche Meldung sieht der Spieler beim Limit? **Fallback:** Den Wert bei jedem Export erneut setzen.

### R2-A4 Echte Arbeitszeit → Arbeitsbelastung

- [x] Bei jedem Export über `g_currentMission.aiSystem:getActiveJobs()` laufen und für jeden zugeordneten Job die
  seit dem letzten Export vergangene **Spielzeit** (`RPSimGameAdapter:getGameTime()`) dem Mitarbeiter gutschreiben.
  Spielzeit statt `dt`, damit Zeitraffer korrekt zählt und die Einheit eindeutig ist.
- [x] Kumulative Zähler `workedGameMs` je Mitarbeiter im Savegame-XML speichern und als
  `farm_facts.workforce = { activeJobs: [{ jobId, employeeId, title }], workedGameMs: { "<employeeId>": 123 } }`
  exportieren. `title` kommt aus `job:getTitle()` (bei Feldarbeit der Fahrzeugname).
- [x] Backend: Die Arbeitsbelastung von Maschinenführern folgt den echten Stunden statt dem simulierten Zerfall.
  Konfig `rpsim.formulas.satisfaction.workload.*`: Soll-Stunden je Spielmonat, Abzug je Überstunde, leichte Erholung
  bei weniger Stunden. Freie Tage (`ON_LEAVE`) zählen weiter als Entlastung.
- [x] Optional: Die monatliche Leistungsbuchung `EMPLOYEE_EFFECT` von Maschinenführern skaliert mit den gearbeiteten
  Stunden (wer nie fährt, bringt auch keinen Bonus).
- [x] Oberfläche: Stunden des Monats je Mitarbeiter auf der Personal-Seite.

**Beleg:** ✅ `AISystem:getActiveJobs()`, `AISystem:getNumActiveJobs()`; ✅ `AIJobFieldWork:getTitle()` =
Fahrzeugname. ✅ Zähler im Savegame speichern wie die bestehende Idempotenz-Liste (`import/Persistence.lua`).

### R2-A5 Streik bei starker Unzufriedenheit

- [x] Backend: Neue Stufe zwischen Warnung und Kündigung in `SatisfactionService`. Liegt die Zufriedenheit
  `strike-after-days` unter `strike-threshold`, geht der Mitarbeiter in `STRIKE`. Er meldet sich per Mail oder Anruf.
  Der Streik endet, wenn die Zufriedenheit über die Schwelle steigt (z. B. nach Gehaltserhöhung), sonst folgt wie
  bisher die Kündigung.
- [x] Mod: Wird einem Job ein streikender Mitarbeiter zugeordnet oder beginnt der Streik während eines Jobs, stoppt
  der Mod den Job mit `g_currentMission.aiSystem:stopJob(job, message)`.
- [x] Meldung: eigene Klasse `RPSimAIMessageStrike` (abgeleitet von `AIMessage`, `getI18NText` → Text aus
  `modDesc.xml`, z. B. „%s legt die Arbeit nieder“) über `AIMessageManager:registerMessage` registrieren.

**Beleg:** ✅ `AISystem:stopJob(job, aiMessage)`; ✅ `ai/errors/AIMessageManager.lua` registriert alle Meldungen mit
`registerMessage(name, class)`; ✅ `AIMessageErrorUnknown` zeigt das Muster einer Meldungsklasse (`Class(..., AIMessage)`
mit `getI18NText`).

**🟡 Im Spiel prüfen:** Wie erreicht der Mod den `AIMessageManager` zum Registrieren, und nimmt das Stopp-Event eine
eigene Meldung an? **Fallback:** mit `AIMessageErrorUnknown.new()` stoppen und den Grund über die vorhandene
Anweisung `NOTIFICATION` einblenden.

### R2-A6 Angestellter Mechaniker repariert teilweise

- [x] `REPAIR_VEHICLE` um ein optionales Feld `targetDamage` (0–1) erweitern. Standard `0` = V1-Verhalten des
  Wartungsvertrags.
- [x] Backend `MechanicService`: Zu jedem Monatsbeginn verteilt jeder angestellte Mechaniker eine
  **Reparaturleistung** (Konfig: Schadenspunkte je Monat × Skill × `effectMultiplier` aus der Zufriedenheit) auf die
  am stärksten abgenutzten eigenen Fahrzeuge (`assets.vehicles[].condition`).
- [x] Reihenfolge: zuerst der Wartungsvertrag (`MaintenanceService`), dann der Mechaniker für die übrigen Fahrzeuge.
  Kein Fahrzeug wird doppelt bearbeitet.
- [x] Kurze Mail des Mechanikers („Hab den Frontlader wieder hinbekommen, der Rest muss warten“). Zu viele kaputte
  Maschinen senken seine Arbeitsbelastung.

**Beleg:** ✅ Bereits in V1 genutzt: `Wearable:setDamageAmount(amount, true)` in `RPSimGameAdapter:repairVehicle`,
`getDamageAmount()` im Export.

### R2-A7 Tierpfleger und Tierarzt reagieren auf echte Stallwerte

- [x] Export je Stall als `farm_facts.husbandries[]`:
  - `health`: Durchschnitt von `cluster.health` über alle Gruppen (Berechnung wie im Spiel selbst),
  - `productivity`: `getGlobalProductionFactor() × getProductionFactor()` (entfällt bei Pferd und Schwein wie im
    Spiel),
  - `food`: `getTotalFood()` / `getFoodCapacity()`,
  - `conditions`: generische Liste aus `getConditionInfos()` mit `title` und `ratio` (Wasser, Stroh, Gülle, Milch …).
- [x] **Tierarzt** (`LivestockService`): Liegt `health` unter `vet.emergency-health-threshold`, kommt ein
  Notfallbesuch mit höherer Rechnung. Die Routinebesuche bleiben.
- [x] **Tierpfleger** (angestellt, `JobRole.ANIMAL_KEEPER`): Er warnt per Mail, wenn Futter oder Wasser unter einen
  Schwellwert fallen. Seine Arbeitsbelastung hängt an der Tierzahl je Pfleger, seine Zufriedenheit leidet bei
  dauerhaft schlechten Werten („Ich kann so nicht arbeiten“). Sein monatlicher Leistungseffekt skaliert mit der
  `productivity` der Ställe.
- [x] **Zuchtberatung:** Kommentare nutzen `productivity` und die Entwicklung der Tierzahl.
- [ ] Die Werte fließen auch in E2 (Kontrollen). *(folgt mit E2)*

**Beleg:** ✅ `animals/husbandry/placeables/PlaceableHusbandryAnimals.lua` (`updateInfo`: Mittelwert von
`cluster.health`; `getConditionInfos`: `getGlobalProductionFactor() * getProductionFactor()`, nicht für
`AnimalType.HORSE`/`PIG`). ✅ `PlaceableHusbandryFood.lua`: `getTotalFood()`, `getFoodCapacity()`, `getFoodInfos()`.
✅ `getConditionInfos` wird von `PlaceableHusbandryWater`, `…Straw`, `…LiquidManure`, `…Milk` ergänzt.

**Akzeptanz A:** Ein angestellter Maschinenführer fährt einen Helfer. Im Spiel erscheint sein Name in der Meldung,
es wird kein Helferlohn gebucht, seine Stunden stehen auf der Personal-Seite. Ein streikender Mitarbeiter stoppt
seinen Helfer.

---

## C – Felder, Kulturen und Wetter

**Problem heute:** Das Tool weiß nur, *welche* Felder dem Spieler gehören, aber nicht, was darauf wächst. Hagel trifft
ein zufälliges Feld, auch wenn dort nichts steht. Der Wildschaden richtet sich nicht nach den Kulturen. Die Charaktere
können nicht über den Zustand der Felder sprechen.

**Idee:** Der Mod exportiert Kultur, Wachstum und Pflegezustand der eigenen Felder sowie das aktuelle Wetter. Die
simulierten Ereignisse werden damit plausibel, und das Dorf reagiert auf die Feldarbeit.

**Stand 28.09.2026: umgesetzt.** Entscheidungen (siehe `QUESTIONS.md`): Der Mod exportiert zusätzlich je Feld
`withered` / `cut` (`getIsWithered` / `getIsCut`), `fillType` und `litersPerSqm` sowie den Block `fieldRules`
(Pflügen/Kalk verlangt, Unkraut/Steine aktiv). Die Phase kennt deshalb neben `VERDORRT` auch `ABGEERNTET`; die
Roadmap-Regel „über max = verdorrt“ gilt nur für Mods ohne die Flags. Unkraut, Steine, Kalk und Pflug wertet das
Backend nur mit `fieldRules` aus. Der Ertrag kommt aus dem Spiel, die Konfig-Tabelle ist nur Rückfall. Die Hinweise
schickt die Genossenschaft, abschaltbar auf der Einstellungsseite; die Feldseite zeigt Kultur und Phase. Die
„Rekordernte“ aus C4 ist der Rekordmonat der Genossenschaft aus B5. Der Mod liest die Felder nur alle
`fieldExportIntervalMs` (und nach einer Feldübertragung) neu; jeder Export dazwischen trägt den letzten Stand, denn ein
fehlender Block hieße „nicht vorhanden“. Alle Zahlen sind Platzhalter.

### R2-C1 Feldzustand exportieren

- [x] Über `g_fieldManager.fields` laufen und nur Felder mit `field.farmland` im Besitz des Spielers exportieren (bei
  Bedarf zusätzlich Felder der Tool-NPCs für Vergleiche – derzeit nicht nötig, nicht exportiert).
- [x] Je Feld `farm_facts.fields[]`: `farmlandId`, `name` (`field:getName()`), `hectares` (`field.areaHa`),
  `fruitType` (`g_fruitTypeManager:getFruitTypeNameByIndex(state.fruitTypeIndex)`), `growthState`,
  `minHarvestingGrowthState` / `maxHarvestingGrowthState` (aus `getFruitTypeByIndex`), `weedState`, `stoneLevel`,
  `sprayLevel`, `limeLevel`, `plowLevel`, `groundType`.
- [x] Nur bei Änderung und in größerem Abstand exportieren (Konfig `fieldExportIntervalMs`, Vorschlag 5 min
  Echtzeit), damit `farm_facts.json` klein bleibt.
- [x] Backend: Wachstumsphase ableiten (`LEER`, `WÄCHST`, `ERNTEREIF` zwischen min und max, `VERDORRT` über max) und
  je Feld eine kurze Historie führen (Kultur je Erntejahr, nötig für E2).

**Beleg:** ✅ `field/FieldState.lua` (Felder `isValid`, `fruitTypeIndex`, `growthState`, `weedState`, `stoneLevel`,
`groundType`, `sprayLevel`, `limeLevel`, `plowLevel`, `waterLevel`, `farmlandId`). ✅ `field/Field.lua`: jedes Feld hat
`self.fieldState`, `farmland`, `areaHa`, `posX`/`posZ`. ✅ Missionen lesen den Zustand mit `self.field:getFieldState()`
(`AbstractFieldMission`, `PlowMission`, `StonePickMission`). ✅ `field/FieldManager.lua`: Liste `self.fields`.
✅ `fruits/FruitTypeManager.lua`: `getFruitTypeByIndex`, `getFruitTypeNameByIndex`; `minHarvestingGrowthState` /
`maxHarvestingGrowthState` werden im Spielcode so abgefragt.

**🟡 Im Spiel prüfen:** Wird `field:getFieldState()` nach Feldarbeit zeitnah aktualisiert? Der `FieldManager` arbeitet
die Felder reihum ab (`fieldStateUpdateIndex`). **Fallback:** Eigenen Zustand mit `FieldState.new()` anlegen und mit
`fieldState:update(field.posX, field.posZ)` an der Feldmitte abfragen (Muster aus `FieldManager.lua`,
Debug-Ansicht). Das ist eine Stichprobe an einem Punkt und reicht für die Einordnung.

### R2-C2 Wetter exportieren

- [x] `farm_facts.weather = { raining, rainFallScale, groundWetness }` aus
  `g_currentMission.environment.weather:getIsRaining()`, `getRainFallScale()`, `getGroundWetness()`.
- [x] Backend: Regenstunden je Periode aus den Exporten hochrechnen (Stichprobe alle ~60 s reicht).

**Beleg:** ✅ Alle drei Aufrufe im Spielcode (u. a. Sprayer, Mähwerke, Solaranlagen, `BeehiveSystem`).

### R2-C3 Unwetter und Wildschaden plausibel machen

- [x] **Hagel** (`InsuranceService`) trifft nur Felder mit einer Kultur im Wachstum oder erntereif. Schaden =
  Fläche × typischer Ertrag (Konfig je Fruchtart) × aktueller Preis aus `prices` × Schadensquote. In regnerischen
  Perioden steigt die Wahrscheinlichkeit.
- [x] **Wildschaden** (`HuntingService`) nur auf Feldern mit Kulturen aus einer Konfig-Liste (Vorschlag: `MAIZE`,
  `WHEAT`, `BARLEY`, `OAT`, `POTATO`), Schaden nach Fläche und Wachstum.
- [x] Sturm auf Gebäude bleibt wie in V1.
- [x] Die Schadensmeldung nennt Feld und Kultur („Hagel auf Feld 12, Ihr Weizen …“).
- [x] Ohne Feld-Export (älterer Mod) bleibt das V1-Verhalten.

**Hinweis:** Der Schaden bleibt eine Geldbuchung (`DAMAGE`). Echte Ernteverluste im Spiel sind nicht belegt, siehe
[Bewusst nicht aufgenommen](#bewusst-nicht-aufgenommen).

### R2-C4 Dorf und Nachbarn reagieren auf die Felder

- [x] **Nachbar:** Hoher `weedState` auf einem eigenen Feld über mehrere Perioden → freundliche, später genervte
  Nachricht; kleiner Vertrauensverlust, wenn nichts passiert. Dasselbe für viele Steine (`stoneLevel`).
- [x] **Dorfklatsch:** Eigene Felder, die lange brach liegen („Da wächst ja gar nichts mehr“), oder verdorrte
  Bestände (`VERDORRT`).
- [x] **Glückwunsch:** Alle Felder rechtzeitig geerntet, Rekordernte (zusammen mit B).
- [x] Alle Schwellen und Häufigkeiten in `rpsim.formulas.fields.*`, Obergrenze je Monat wie bei den anderen Spawnern.

### R2-C5 Bank bewertet den Aufwuchs

- [x] Stehende Kulturen erhöhen das Vermögen in der Bonitätsprüfung: Fläche × Ertrag × Preis × Wachstumsfortschritt
  × Abschlag (Konfig `credit.standing-crop-discount`, Vorschlag 0.5).
- [x] Die Bankberaterin erwähnt es („Ihr Weizen steht gut, das berücksichtigen wir“).

### R2-C6 Hinweise zur Feldarbeit

- [x] Genossenschaft oder Lohnunternehmer geben saisonale Hinweise aus echten Werten: „Feld 7 ist erntereif“, „Auf
  Feld 3 fehlt Kalk“ (`limeLevel`), „Feld 9 sollte gepflügt werden“ (`plowLevel`). Höchstens einer pro Woche,
  abschaltbar.

**Akzeptanz C:** Hagel trifft nie ein leeres Feld. Ein verunkrautetes Feld führt nach einiger Zeit zu einer Nachricht
des Nachbarn. Die Bank zählt einen guten Bestand als Vermögen.

---

## D – Umgehung des Grundspiels ins Rollenspiel holen

**Problem heute:** Der Vanilla-Kredit im Finanzmenü und der Feldkauf im Feldmenü sind nicht gesperrt. Das Backend
gleicht beides still ab (`negotiation/FarmlandOwnershipService.java`, Kommentar „silently reconciles vanilla
purchases“). Der Spieler kann Bank und Verhandlungen so ohne Folgen umgehen.

**Idee:** Nicht sperren (Entscheidung aus V1: „erkennen und warnen, nichts abschalten“), sondern die Charaktere
reagieren lassen. Die Umgehung wird Teil der Geschichte.

**Stand 28.09.2026: umgesetzt.** Entscheidungen (siehe `QUESTIONS.md`): Die Verkaufsgrenze eines NPC liegt laut
`NegotiationFormula` immer unter dem Spielpreis, eine „Differenz zum Verhandlungspreis“ wäre nie positiv. Die
Ausgleichsforderung ist deshalb ein Anteil des Spielpreises (`compensation-share`, Platzhalter 10 %); keine Antwort
gilt als Ablehnung. Die Bank reagiert auf jede Tilgung ab `loan-min-repayment`. Als Wiederholung zählt die zweite
Aufnahme bei offenem Vanilla-Kredit, die volle Tilgung setzt Zähler und Aufschlag zurück. Die Reaktionen sind je
Spielstand auf der Einstellungsseite abschaltbar (plus `vanilla-bypass.enabled`). Erhöhungen und Tilgungen werden je
Spieltag gesammelt und einmal beantwortet; ein Neuladen ohne Speichern verschiebt nur den Bezugspunkt. Die Nachricht zu
D3 schickt die Genossenschaft einmal je Spielstand. Alle Zahlen sind Platzhalter.

### R2-D1 Vanilla-Kredit erkennen

- [x] Backend: `liabilities.vanillaLoan.remainingAmount` mit dem vorherigen Snapshot vergleichen. Steigt der Betrag
  über `vanilla-bypass.loan-min-increase`, entsteht das Ereignis `VANILLA_LOAN_TAKEN` mit dem Betrag.
- [x] Die Bankberaterin meldet sich („Sie haben sich woanders Geld geliehen?“). Vertrauensverlust skaliert mit dem
  Betrag, gedeckelt. Wiederholt sich das, gibt es einen Zinsaufschlag für neue Anträge
  (`vanilla-bypass.loan-interest-surcharge`), bis der Vanilla-Kredit getilgt ist.
- [x] Tilgung des Vanilla-Kredits → neutrale bis leicht positive Reaktion.
- [x] Tagebucheintrag; Hinweis in der Spieler-Doku.

**Beleg:** ✅ Kein Mod-Eingriff nötig. `farm.loan` wird schon exportiert (`RPSimGameAdapter:collectFarmFacts`,
`raw.vanillaLoan`), ✅ `Farm:getLoan()` ist dokumentiert.

### R2-D2 Feldkauf und -verkauf über das Spielmenü

- [x] `FarmlandOwnershipService.reconcile` erzeugt ein Ereignis statt still abzugleichen:
  - Das Feld gehörte im Tool einem **Charakter** (FS25-NPC oder Dorfbewohner): Er reagiert verärgert („über meinen
    Kopf hinweg gekauft“), Vertrauensverlust, kleiner Abzug beim Dorf-Ansehen.
  - Optional **Ausgleichsforderung:** Der frühere Besitzer verlangt die Differenz zwischen seinem Verhandlungspreis
    (Formel aus `NegotiationEngine`) und dem Spielpreis. Formular „zahlen / ablehnen“; Zahlung als
    `MONEY_TRANSACTION` mit `COMPENSATION`, Ablehnung kostet mehr Vertrauen.
  - Das Feld war **frei** (`UNCLAIMED`): nur Tagebucheintrag.
- [x] Verkauf eines eigenen Feldes über das Spielmenü → Tagebucheintrag, Klatsch im Dorf.
- [x] Alle Werte unter `rpsim.formulas.vanilla-bypass.*`; die Reaktion ist abschaltbar.

**Beleg:** ✅ Die Erkennung existiert bereits (`reconcile`, ausgelöst durch `FactsIngested` und
`MarketContextUpdated`). Es fehlt nur die Reaktion.

### R2-D3 Helfer ohne Mitarbeiter

- [x] Mit A1 zahlt der Spieler für Helfer ohne freien Maschinenführer den Spiellohn. Einmalige Nachricht der
  Genossenschaft oder eines Bewerbers: „Sie haben ja ständig Leute von außen auf dem Hof, wollen Sie nicht jemanden
  fest einstellen?“ mit Link auf **Stelle ausschreiben**.
- [x] Voraussetzung: `farm_facts.workforce.activeJobs` (A4) enthält Jobs ohne `employeeId`.

**Akzeptanz D:** Wer den Vanilla-Kredit nimmt oder ein NPC-Feld im Spielmenü kauft, bekommt innerhalb eines Spieltags
eine Reaktion des betroffenen Charakters. Nichts wird gesperrt.

---

## E – Neue Rollenspiel-Bereiche

Diese Bereiche brauchen **keine neue Spiel-API**: Sie nutzen die vorhandenen Anweisungen (`MONEY_TRANSACTION`,
`NOTIFICATION`) und die Daten aus B, C und A7. Jeder Bereich folgt dem V1-Muster: Rollen-Definition,
Persönlichkeitsvorlagen, Fallback-Texte ohne KI (Deutsch), Formeln als Konfiguration, Tagebucheinträge,
Frontend-Darstellung unter **Verträge & Vorgänge**.

### R2-E1 Finanzamt und Steuerberater (braucht B)

- [ ] Neue Rollen `TAX_OFFICE` (Pflichtrolle, erscheint mit dem ersten Steuerbescheid) und `TAX_ADVISOR` (optional
  beauftragbar).
- [ ] **Steuerjahr = FS25-Jahr** (Periode 1 = März). Gewinn = operative Einnahmen − operative Ausgaben aus dem Journal
  (B2) − vereinfachte Abschreibung (Konfig: Prozent des Fahrzeug- und Gebäudewerts) − Zinsen der Tool-Kredite.
- [ ] Steuersatz und Freibetrag konfigurierbar (`rpsim.formulas.tax.*`), im harten Weltmodus strenger.
- [ ] **Vorauszahlungen** je Quartal (drei Perioden) auf Basis des Vorjahres, **Bescheid** nach Jahresende mit
  Nachzahlung (`TAX_PAYMENT`) oder Erstattung (`TAX_REFUND`).
- [ ] Nicht gezahlt → Säumniszuschlag (`FINE`), Mahnung, später Pfändungsandrohung (nur Text und Vertrauen, keine
  Sperre).
- [ ] **Steuerberater:** Monatliches Honorar, senkt die Steuer um einen konfigurierbaren Anteil, erinnert an
  Fristen und senkt die Wahrscheinlichkeit einer **Betriebsprüfung**. Die Prüfung kann zufällig kommen und bei
  auffälligen Sprüngen im Journal Nachzahlungen fordern.
- [ ] Oberfläche: Steuerübersicht (voraussichtliche Steuer, nächste Vorauszahlung) auf der Bank-Seite.

### R2-E2 Amt und Kontrollen (braucht C, A7)

Nur Regeln, die sich aus exportierten Werten messen lassen:

- [ ] **Fruchtfolge:** Dieselbe Kultur auf demselben Feld in aufeinanderfolgenden Erntejahren (Historie aus C1) →
  Hinweis, bei Wiederholung Kürzung einer Förderung. Abwechslungsreiche Fruchtfolge → **Förderprämie** (`SUBSIDY`)
  über die Rolle `AUTHORITY`.
- [ ] **Bewirtschaftungspflicht:** Eigene Felder, die mehrere Perioden ohne Kultur und ungepflegt sind (Unkraut,
  Steine) → Aufforderung des Amts, danach Bußgeld (`FINE`).
- [ ] **Tierwohl-Kontrolle:** Stall mit `health` unter Schwelle oder leerem Futter/Wasser über längere Zeit (A7) →
  Kontrolle, Auflage mit Frist, bei erneutem Verstoß Bußgeld und Ansehensverlust im Dorf.
- [ ] Kontrollen kündigt das Amt an (Mail), das Ergebnis kommt nach der Frist. Der Spieler hat also immer eine Chance
  zu reagieren.
- [ ] Werte in `rpsim.formulas.authority.*`, Häufigkeit gedeckelt.

### R2-E3 Familie und Hofnachfolge

- [ ] Neue Rolle `FAMILY` (Eltern auf dem Altenteil, optional Partner:in und Kinder), festgelegt im Onboarding
  zusammen mit der Vorgeschichte. Wer keine Familie will, wählt „alleine“.
- [ ] **Altenteil:** Monatliche Zahlung an die Eltern (`FAMILY`), Höhe aus der Vorgeschichte.
- [ ] **Anlässe aus dem Kalender:** Geburtstage, Hochzeitstag, Einschulung (Periode fest je Charakter).
- [ ] **Familienwünsche** als Geschichten mit Folgen für das Familien-Vertrauen, z. B. „Das Feld am Bach war schon beim
  Großvater in der Familie“ → Verkauf dieses Feldes kostet Vertrauen. Oder Mithilfe zur Erntezeit (nur Text und
  Vertrauen, keine Spielwirkung).
- [ ] Hofnachfolge als langfristiger Erzählbogen im Tagebuch.

### R2-E4 Vereine und Dorffeste

- [ ] Feste im FS25-Kalender (Konfig je Fest: Periode und Name, z. B. Erntedank im Oktober, Schützenfest im Juni).
- [ ] **Sponsoring-Anfragen** von Vereinen (Schützenverein, Freiwillige Feuerwehr, Sportverein) mit festen Stufen
  (Formular, Betrag vom Backend vorgegeben) → Ansehen im Dorf steigt nach der Formel des Dorf-Ansehens (`SPONSORING`).
  Ablehnen kostet wenig.
- [ ] **Einladungen mit Zusage:** Die Einladungen aus V1 (`VillageLifeService`) sind heute reine Mails. Neu: Zu- oder
  Absage per Knopf (neuer Endpunkt). Zusagen stärkt das Vertrauen der Gastgeber, Absagen ist neutral, Ignorieren
  kostet wenig. Einladungen zu Festen nennen das Fest aus dem Kalender.
- [ ] Werte in `rpsim.formulas.clubs.*`.

**Akzeptanz E:** Nach dem ersten FS25-Jahr kommt ein Steuerbescheid mit nachvollziehbarer Rechnung. Eine monotone
Fruchtfolge führt zu einem Hinweis vom Amt. Ein Sponsoring hebt das Dorf-Ansehen messbar.

---

## F – Entscheidungen direkt im Spiel

**Problem heute:** Das Spiel blendet neue Mails und Anrufe nur ein (`NOTIFICATION`). Für jede Antwort muss der
Spieler in den Browser wechseln, auch bei einfachen Ja/Nein-Fragen.

**Idee:** Einfache Entscheidungen erscheinen als Ja/Nein-Dialog im Spiel. Die Antwort geht über einen neuen Rückkanal
an das Backend. Gespräche und Formulare mit Beträgen bleiben im Browser.

### R2-F1 Rückkanal Mod → Backend

- [ ] Neue Datei `export/player_responses.json`:
  `{ savegameId, responses: [{ responseId, promptId, answer: "YES" | "NO", gameTime }] }`.
- [ ] Der Mod schreibt die Datei **sofort** nach einer Antwort (nicht erst beim 60-s-Export), im Schreibmodus
  `direct` wie alle Bridge-Dateien.
- [ ] Das Backend liest die Datei bei jedem Bridge-Zyklus (`rpsim.bridge.poll-interval-ms`, heute 2000). Es bestätigt
  verarbeitete Antworten in `instructions.json` mit `ackedResponses: [responseId]`, der Mod entfernt sie dann aus
  der Datei. Doppelte `responseId` ignoriert das Backend (idempotent).
- [ ] Nach dem Neuladen ohne Speichern gehen unbestätigte Antworten verloren. Die Frage kommt dann einfach erneut,
  weil das Backend sie noch als offen führt.

**Beleg:** ✅ Schreiben mit `io.open` im `modSettings`-Ordner ist in V1 belegt und im Einsatz (Farm Dashboard,
`util/FileIO.lua`).

### R2-F2 Anweisung `PROMPT` und Ja/Nein-Dialog

- [ ] Neuer Anweisungstyp
  `PROMPT { promptId, title, text, yesLabel?, noLabel?, expiresGameTime }`.
- [ ] Der Mod reiht Prompts ein und zeigt jeweils einen mit `YesNoDialog.show(callback, target, text, title)`. Der
  Callback schreibt die Antwort in den Rückkanal (F1). Abgelaufene Prompts verwirft der Mod ungezeigt.
- [ ] Nur bei der Spieler-Farm und nicht, wenn gerade ein anderer Dialog oder ein Menü offen ist. In dem Fall wartet
  der Prompt bis zum nächsten Frame ohne offenes Menü.
- [ ] **Erlaubte Anlässe** (nur Entscheidungen, deren Zahlen das Backend festlegt):
  - eingehender Anruf: **Annehmen / Ablehnen** (angenommen → das Gespräch ist im Browser bereit, abgelehnt → wie
    „Ablehnen“ in der Oberfläche),
  - angebotene Verträge (Pacht, Wartungsvertrag, Versicherung) annehmen / ablehnen und Pacht verlängern, jeweils zu
    den Konditionen aus dem Angebot (vorhandene Endpunkte `/api/contracts/{id}/accept|decline|renew`),
  - Entschädigungsangebot des Jagdpächters annehmen (`/api/cases/{id}/accept`); Nachverhandeln bleibt im Browser,
  - Gegenangebot der Bank annehmen / ablehnen (`/api/credit-applications/{id}/accept-counter|decline-counter`),
  - Einladungen zu- oder absagen (erst mit der Zusage-Mechanik aus E4).
- [ ] Die Antwort löst im Backend **dieselbe Service-Methode** aus wie der Klick im Browser. So gibt es keine zweite
  Geschäftslogik.
- [ ] Im Tool einstellbar: welche Anlässe im Spiel gefragt werden (Standard: nur Anrufe).

**Beleg:** ✅ `dialogs/YesNoDialog.lua`; ✅ Aufrufe `YesNoDialog.show(callback, target, text[, title])` im Spielcode
(`PlaceableBuyable`, `PlaceableTrainSystem`, `TourIconsMobile`).

**🟡 Im Spiel prüfen:** Stört der Dialog während der Fahrt, und wie erkennt der Mod zuverlässig ein offenes Menü?
**Fallback:** Prompts nur zeigen, wenn der Spieler nicht in einem Fahrzeug sitzt, oder nur nach Tastendruck (F3).

### R2-F3 Taste für offene Entscheidungen (optional)

- [ ] Eigene Aktion in `modDesc.xml` (`<actions>` / `<inputBinding>`) und Registrierung über `g_inputBinding` mit
  Anzeige in der Tastenhilfe: öffnet die nächste offene Frage.
- [ ] Die Einblendung (`NOTIFICATION`) nennt die Taste: „FarmPulse: Anruf von … – [Taste] zum Annehmen“.

**Beleg:** ✅ Aktionen werden im Spielcode mit `addActionEvent(..., InputAction.X, ...)` registriert (z. B.
`TensionBelts`, `Drivable`, `WorkMode`).

**🟡 Im Spiel prüfen:** Registrierung einer **globalen** Aktion (außerhalb eines Fahrzeugs) aus einem Mod. Das Muster
ist bei FS25-Mods üblich, im Code-Dump aber nur für Fahrzeug-Spezialisierungen belegt. **Fallback:** ohne Taste, nur
F2 mit automatischer Anzeige.

**Akzeptanz F:** Ein eingehender Anruf lässt sich im Spiel annehmen. Im Browser ist das Gespräch danach offen. Eine
Pachtverlängerung lässt sich im Spiel bestätigen, und die Anweisungen folgen wie beim Klick im Browser.

---

## Bewusst nicht aufgenommen

Geprüft und verworfen, weil es im FS25-Code **keine belegte Schnittstelle** gibt. Falls sich das ändert (neue LUADOC,
Mod-Beispiel), können die Punkte nachgezogen werden.

| Idee | Warum nicht |
| --- | --- |
| Skill des Mitarbeiters verändert Fahrweise, Tempo oder Arbeitsqualität des KI-Helfers | Keine dokumentierten Parameter in `AIJob`/`AIDriveStrategy*`, die man pro Helfer sauber setzen könnte. Die Wirkung bleibt die monatliche Geldbuchung (`EMPLOYEE_EFFECT`). |
| Hagel oder Wildschweine zerstören echten Aufwuchs auf dem Feld | Kein belegter, sicherer Schreibzugriff auf Fruchtdichte-Karten für diesen Zweck. Der Schaden bleibt eine Geldbuchung (C3 macht ihn nur plausibel). |
| Echte Tornado-Schäden aus dem Spiel auslesen | `environment/weather/Twister.lua` enthält nur Netzwerk-Funktionen (`readStream`/`writeStream`), keine Ereignisse oder Schadenslogik. |
| Vanilla-Kredit hart sperren | `Farm:updateMaxLoan()` ist dokumentiert, der Inhalt (Feldname des Maximalkredits) aber nicht. Außerdem widerspricht es der V1-Entscheidung „erkennen, nicht sperren“. Ersatz: D1. |
| Tierpfleger füttert die Tiere im Spiel | Kein belegter Weg, Futter ohne Werkzeug oder Trigger in einen Stall zu buchen. Der Pfleger warnt stattdessen (A7). |
| Rabatte beim Maschinenhändler im Shop | Kein belegter Hook auf den Kaufpreis im Shop. |

---

## Quellen

- FS25-Quellcode-Dump (`dataS`): <https://github.com/Dukefarming/FS25-lua-scripting>. Genutzt wurden `ai/jobs/AIJob.lua`,
  `ai/jobs/AIJobFieldWork.lua`, `ai/jobs/AIJobConveyor.lua`, `ai/AISystem.lua`, `ai/HelperManager.lua`,
  `ai/errors/AIMessage*.lua`, `field/Field.lua`, `field/FieldState.lua`, `field/FieldManager.lua`,
  `fruits/FruitTypeManager.lua`, `animals/husbandry/placeables/PlaceableHusbandry*.lua`, `dialogs/YesNoDialog.lua`,
  `environment/weather/Twister.lua`.
- FS25 Community LUADOC: <https://github.com/umbraprior/FS25-Community-LUADOC>. Genutzt wurden `script/Farms/Farm.md`
  (`changeBalance`, `getLoan`, `getId`, `updateMaxLoan`), `script/Jobs/AIJob.md`, `script/AI/AISystem.md`,
  `script/Field/*.md`, `script/GUI/YesNoDialog.md` sowie die `MoneyType`- und Wetter-Aufrufe in den
  Spezialisierungen.
- Bestehende Projekt-Doku: `TODO.md` (V1-Analyse), `docs/dev/bridge-protocol.md`,
  `docs/dev/offene-technische-punkte.md`, `docs/dev/manual-test-plan.md`.
