# FarmPulse – Roadmap V3.3

Ergänzung zu [Roadmap V3](ROADMAP_V3.md), [Roadmap V3.1](ROADMAP_V3.1.md) und [Roadmap V3.2](ROADMAP_V3.2.md), Stand
08.10.2026. Ziel: Der Hof bekommt ein **Feldbuch**, also eine Ackerschlagkartei. Für jedes Feld und jedes Erntejahr
steht dort, was angebaut war, ob zweimal gedüngt, gekalkt, gewalzt, Unkraut bekämpft und gemulcht wurde und wie viele
Liter geerntet wurden. Eine **Auswertung** zeigt die Erträge je Feld über die letzten Jahre. So sieht der Betriebsleiter,
welches Feld mit welchen Maßnahmen welchen Ertrag gebracht hat.

Der Projektinhaber hat die Idee am 08.10.2026 beschrieben. Seine Entscheidungen zu Datenquelle, Ort, Jahr, Saison,
Maßnahmen, Kulturen, Korrekturen, Abschluss, Auswertung und Altdaten stehen in `QUESTIONS.md` (08.10.2026). Was noch
nicht entschieden ist, steht als Vorschlag unter [Offene Punkte](#offene-punkte-vorschläge-zur-bestätigung). Wie in V2
bis V3.2 enthält die Datei **nur Punkte, die mit der FS25-Schnittstelle machbar sind**. Jeder Punkt mit Mod-Eingriff
wurde am 08.10.2026 gegen den FS25-Quellcode-Dump und die Community-LUADOC geprüft (Quellen am Ende).

**Warum das zum Spiel passt:** Der Mähdrescher rechnet den Ertrag aus genau diesen Maßnahmen.
`Cutter:processCutterArea` ruft `g_currentMission:getHarvestScaleMultiplier(fruitTypeIndex, sprayFactor, plowFactor,
limeFactor, weedFactor, stubbleFactor, rollerFactor, beeYieldBonusPerc)` auf (LUADOC `Specializations/Cutter.md`).
Düngung, Kalk, Unkraut, Mulchen (Stoppel) und Walzen wirken also im Spiel wirklich auf den Ertrag. Die Auswertung
zeigt damit echte Zusammenhänge und keine erfundenen.

**Grundsätze (unverändert aus V1–V3.2)**

- Der Mod bleibt **Sensor und Aktuator**. Das Backend erkennt Maßnahmen und Ernte aus den Spielwerten und rechnet alle
  Kennzahlen. Die KI kommt im Feldbuch nicht vor.
- Jeder Zugriff auf FS25 läuft in `RPSimGameAdapter` und ist mit `pcall` abgesichert. Fehlt eine API, fällt die Funktion
  still auf das bisherige Verhalten zurück. Für das Feldbuch heißt das: Der Spieler trägt den Wert selbst ein.
- Neue Felder in den Bridge-Dateien sind **optional** (`schemaVersion` bleibt `1`).
- Werte gibt der Spieler nur über Formulare ein (Auswahl, Haken, Zahl).
- Spielmonat = FS25-Periode, Periode 1 = März (`docs/dev/bridge-protocol.md`, Block `calendar`). Ein **FS25-Jahr**
  beginnt also im März.

**Hängt ab von V2 / V3 / V3.1:**
- Feldwerte `fields[]` und Bodenregeln `fieldRules` (R2-C1), Wachstumsphase und Ernte-Erkennung in
  `field/FieldService` (R2-C),
- `field_crop_history` mit der geschätzten Erntemenge `yieldLiters` (R3-K3), Hofbericht (R3-K3), Chronik (R3-T2),
  Jahresgespräch der Bank (`credit/AnnualReviewService`),
- Lohnunternehmer `FIELD_WORK` samt Ernte-Liter (`farmwork/ContractorWorkService.harvestLiters`, R31-A1),
- Düngerart `fields[].sprayType` (R31-B3),
- Pacht (`contract/LeaseService`) und Verpachtung (R3-L), beide über `FARMLAND_TRANSFER`.

## Legende

| Zeichen | Bedeutung |
| --- | --- |
| ✅ **Belegt** | Die genutzte Funktion bzw. das Feld steht so im FS25-Code; Fundstelle ist angegeben. |
| 🟡 **Im Spiel prüfen** | Die API existiert, aber ein Detail lässt sich nur im laufenden Spiel klären. Zu jedem 🟡 steht ein Fallback. |
| – | Kein Mod-Eingriff: nur Backend und Oberfläche mit Daten und Anweisungen, die es seit V1–V3.2 gibt. |

Punkt-IDs: `R33-<Bereich><Nummer>`, z. B. `R33-F2`. Alle Pfade sind relativ zum Repo-Root.

## Übersicht und empfohlene Reihenfolge

| Phase | Bereich | Inhalt | Mod-Eingriff | Hängt ab von |
| --- | --- | --- | --- | --- |
| 0 | [Q – Querschnitt](#q--querschnitt) | Vertrag für Walz- und Mulchstufe, Kulturen-Katalog und Ernte-Zähler, Simulator, Testplan | ja (nur Normalisierung) | R2-C1 |
| 1 | [F – Feldbuch](#f--feldbuch) | Einträge und Saison, automatische Erfassung, Korrektur, Jahr abschließen, App „Feldbuch“ | F3: ja (über Q1) | Q, R2-C, R31-A1 |
| 2 | [E – Auswertung](#e--auswertung) | Zeitraum, Tabelle Feld × Jahr, Liter je Hektar, Diagramm, Vergleich mit/ohne Maßnahme | nein | F |
| 3 | [W – Wirkung auf Hofbericht, Chronik und Bank](#w--wirkung-auf-hofbericht-chronik-und-bank) | Gemessene Liter ersetzen die Schätzung | nein | F, R3-K3, R3-T2 |

F zuerst: Ohne Einträge gibt es nichts auszuwerten. F1, F2, F4 und F5 funktionieren auch mit einem älteren Mod (ohne
Q1); dann fehlen nur Walzen, Mulchen und die gemessenen Liter, und der Spieler trägt sie selbst ein.

---

## Q – Querschnitt

**Stand 09.10.2026: umgesetzt.** Q legt wie R31-Q **nur den Vertrag** an (Entscheidung 08.10.2026): Schemas, DTOs,
Validator, Normalisierung im Mod (`RPSimFarmFacts.build`, `RPSimMarketContext.build`), Simulator und Doku. Das Auslesen
im Spiel folgt mit F2 (Walz- und Mulchstufe), F3 (Ernte-Zähler samt Speichern im Mod-Spielstand) und F4
(`fruitTypes[]`); bis dahin fehlen die Werte. Weitere Entscheidungen vom 08.10.2026 (`QUESTIONS.md`):
- Ernte-Liter: **fortlaufender Zähler** je Feld, Kultur und Ernteprodukt im Mod-Spielstand (Vorschlag aus den offenen
  Punkten); das Backend rechnet mit F3 die Differenz.
- Tabellen kommen **mit F**, der Konfigwert `field-book.default-years` **mit E** (wie in V3.2: erst mit den
  Fach-Bereichen).
- Hook: **beide Wege** werden mit F3 gebaut, `Combine.addCutterArea` und der Plan B `Cutter.onEndWorkAreaProcessing`;
  der Mod nimmt den, der greift, und zählt nie doppelt.

Ohne die neuen Werte hat das Feldbuch keine automatische Erfassung.

### R33-Q1 Bridge erweitern

- [x] `farm_facts.json`, neue optionale Felder an `fields[]` (gleiche Normalisierung wie die vorhandenen Stufen, ganze
  Zahlen):
  - `rollerLevel`: Walzstufe, `1` = „muss gewalzt werden“ (für F2, Walzen),
  - `stubbleShredLevel`: Mulchstufe, `1` = „gemulcht“ (für F2, Mulchen).

  Umgesetzt (Vertrag): auch in `npcFields` (gleiche Normalisierung), fehlt, wenn kein Wert ≥ 0 da ist.
- [x] `market_context.json`, neuer optionaler Block `fruitTypes[]` mit allen Kulturen der Karte, auch denen von
  Map-Mods (Entscheidung 08.10.2026: Dropdown = alle Kulturen der Karte). Je Eintrag:
  - `name` (z. B. `WHEAT`), `fillType` (Standard-Ernteprodukt),
  - `title` (Anzeigename des Spiels, für Kulturen ohne deutsches Label in FarmPulse),
  - `regrows` (wächst nach dem Schnitt nach, z. B. Gras; für F1 „Schnitte summieren“),
  - `needsRolling` (wird nach der Saat gewalzt; mit F4 ergänzt, Entscheidung 09.10.2026),
  - optional `products[]`: weitere Ernteprodukte der Kultur aus den Fruchtumwandlungen des Spiels (z. B. Mais →
    Häckselgut `CHAFF`).

  Der Block wird beim Laden der Karte einmal gebaut, weil sich die Kulturen im laufenden Spiel nicht ändern (mit F4).
  Umgesetzt (Vertrag): sortiert nach `name`, doppelte Namen und leere Werte fallen weg, `products[]` ohne das
  Standardprodukt.
- [x] `farm_facts.json`, neuer optionaler Block `harvests[]` = `{ farmlandId, fruitType, fillType, liters }`. Das ist ein
  **kumulativer Zähler** je eigenem Feld, Kultur und Ernteprodukt: die Liter, die Erntemaschinen auf diesem Feld in
  ihren Tank bekommen haben (für F3). Der Mod speichert die Zähler im eigenen Spielstand (`FS25_RPSim.xml`,
  `RPSimPersistence`, wie `contractReports`). Lädt der Spieler einen älteren Spielstand, sind die Zähler auf dem
  Stand dieses Spielstands. Umgesetzt (Vertrag): ganze Liter, sortiert nach Feld, Kultur und Produkt; Zählen und
  Speichern kommen mit F3.
- [x] `docs/dev/bridge-protocol.md` je Feld und Block mit Quelle im FS25-Code.

**Beleg (Walzen, Mulchen):**
- ✅ `Field/FieldState.md` (LUADOC) und `field/FieldState.lua` (Dump): `FieldState.new` hat `rollerLevel` und
  `stubbleShredLevel` neben `sprayLevel`, `limeLevel`, `plowLevel` und `weedState`, die der Mod seit R2-C1 über
  `field:getFieldState()` liest.
- ✅ `GUI/MapOverlayGenerator.md`: Die Bodenkarte färbt `FieldDensityMap.ROLLER_LEVEL` Zustand `1` als „Walzen
  nötig“ (`NEEDS_ROLLING`) und `FieldDensityMap.STUBBLE_SHRED_LEVEL` Zustand `1` als „gemulcht“ (`MULCHED`). „Kalk
  nötig“ ist dort `LIME_LEVEL` Zustand `0`, wie in R2-C1 dokumentiert.
- ✅ `Specializations/Roller.md` (`FSDensityMapUtil.updateRollerArea`), `Specializations/Mulcher.md`
  (`FSDensityMapUtil.updateMulcherArea`): Walze und Mulcher schreiben diese Schichten.

**Beleg (Kulturen-Katalog):**
- ✅ `Fruits/FruitTypeManager.md`: `getFruitTypes()` gibt alle Kulturen zurück. `getFillTypeNameByFruitTypeIndex`
  liefert das Ernteprodukt (wie `fields[].fillType`). `addFruitTypeConverter` / `addFruitTypeConversion` /
  `getConverterDataByName` führen Umwandlungen Kultur → Ernteprodukt mit Faktor (`fruitTypeConverters`).
- ✅ `Specializations/Cutter.md` (geprüft 08.10.2026): Ein Umwandler ist eine Tabelle mit dem Fruchtsorten-Index als
  Schlüssel und `{ fillTypeIndex, conversionFactor }` als Wert; das Schneidwerk liest sie genau so
  (`spec.fruitTypeConverters[fruitTypeIndex].fillTypeIndex`). Damit ist `products[]` belegt.
- ✅ `Fruits/FruitTypeDesc.md`: `name`, `regrows` / `firstRegrowthState` (Zustand `regrowthStart` in der Foliage-XML),
  `needsRolling`, `resetsSpray`, `allowsSeeding`.
- ✅ `FillTypes/FillTypeDesc.md`: `title` (Anzeigename aus der XML).

**Beleg (Ernte-Zähler):**
- ✅ `Specializations/Cutter.md`, `Cutter:onEndWorkAreaProcessing`: Das Schneidwerk rechnet die Liter aus Fläche und
  Ertragsfaktor (`lastMultiplierArea`, `getFruitTypeAreaLiters`) samt Umwandlungsfaktor. Dann ruft es
  `combineVehicle:addCutterArea(lastArea, liters, inputFruitType, outputFillType, strawRatio, farmId, cutterLoad)`
  auf. `farmId` ist der Besitzer des zuletzt bearbeiteten Flurstücks (`getLastTouchedFarmlandFarmId`).
- ✅ `Specializations/Combine.md`, `Combine:addCutterArea`: Die Funktion zieht Regen- und Schadensabzug ab
  (`RAIN_YIELD_REDUCTION`, `DAMAGED_YIELD_REDUCTION`) und gibt die tatsächlich in den Tank gebuchte Menge zurück.
  `addFillUnitFillLevel` liefert sie, beim Ladeverzug (`loadingDelay`) ist es `deltaFillLevel`. Bei vollem Tank ist sie
  kleiner.
- ✅ `Economy/FarmlandManager.md`: `getFarmlandIdAtWorldPosition(x, z)`; `Specializations/WorkArea.md`
  (`getIsAccessibleAtWorldPosition`): `getFarmlandOwner(farmlandId)` nennt den Besitzer.

**Mod-Ablauf Ernte-Zähler (mit F3):** Ein Hook auf `Combine.addCutterArea` (`Utils.overwrittenFunction`) ruft das
Original auf und nimmt dessen Rückgabe als Liter. Dazu kommt der Plan B auf `Cutter.onEndWorkAreaProcessing`
(Entscheidung 08.10.2026: beide Wege); der Mod nimmt den, der greift, und zählt nie doppelt. Gezählt wird nur auf dem Server (`isServer`, wie `Cutter`) und nur, wenn
`getFarmlandIdAtWorldPosition` an der Position der Erntemaschine (`getWorldTranslation(self.rootNode)`) ein Flurstück
der Spieler-Farm liefert. Gepachtete Felder gehören im Spiel der Spieler-Farm (`FARMLAND_TRANSFER TO_PLAYER`).
Missionsfelder anderer Farmen zählen nicht. Fruchtsorte: `inputFruitType`. Fehlt sie, gilt
`getFruitTypeIndexByFillTypeIndex(outputFillType)` (dieselbe Rückfalllogik nutzt `Combine:addCutterArea` für das Stroh).

**🟡 Im Spiel prüfen:**
- Füllt `field:getFieldState()` auch `rollerLevel` und `stubbleShredLevel`? Die LUADOC zeigt nur `FieldState.new`.
  **Fallback:** eigener `FieldState.new()` mit `fieldState:update(posX, posZ)` in der Feldmitte (wie R2-C1, Testplan
  10.6). Fehlen die Werte trotzdem, entfällt die automatische Erkennung von Walzen und Mulchen, und der Spieler hakt
  sie selbst ab.
- Wirkt der Hook auf `Combine.addCutterArea` auf alle Fahrzeugtypen? Die Funktion wird per
  `SpecializationUtil.registerFunction` am Fahrzeugtyp eingetragen, der Hook muss also vor dem Eintragen sitzen.
  **Fallback:** Hook auf `Cutter.onEndWorkAreaProcessing`, die Liter aus `spec.workAreaParameters` vor dem Aufruf
  (wird mit F3 gleich mitgebaut, Entscheidung 08.10.2026). Klappt keiner der beiden, bleibt `harvests[]` leer, und der
  Spieler trägt die Liter selbst ein.
- Laufen Feldhäcksler, Kartoffel- und Rübenroder sowie die Schwad-Aufnahme (Gras) auch über
  `Cutter` → `Combine:addCutterArea`? **Fallback:** Was nicht gezählt wird, trägt der Spieler selbst ein
  (Entscheidung 08.10.2026, „Liter manuell oder leer“).
- Fahrzeugmitte statt Schneidwerk: Am Feldrand kann eine Schwade dem Nachbar-Flurstück zugeordnet werden.
  **Fallback:** Position des Schneidwerks (`spec.workAreaParameters`, Arbeitsbereich des Cutters). Der Rest ist
  korrigierbar.
- ~~Lassen sich die Umwandlungen je Kultur (`products[]`) aus `fruitTypeConverters` lesen?~~ Belegt (siehe Beleg
  Kulturen-Katalog, `Cutter.md`). Fehlt `products[]` trotzdem (älterer Mod), zeigt die Auswahl das Standard-Ernteprodukt
  und alle Produkte, die der Ernte-Zähler für diese Kultur schon gemeldet hat.

### R33-Q2 Domäne, Simulator, Tests, Konfiguration, Doku, Testplan

Stand 09.10.2026: Simulator, Mod- und Backend-Tests des Vertrags, Testplan und Doku umgesetzt. Tabellen kommen mit F,
der Konfigwert mit E (Entscheidung 08.10.2026).

- [x] Neue Tabellen (Migration) – **mit F** (V43, dazu `field_book_counter` für den zuletzt gesehenen Zählerstand):
  - `field_book_entry`: je Feld und Saison ein Eintrag mit Feldname und Fläche, Status
    (`RUNNING`, `HARVESTED`, `NO_HARVEST`) und Erntejahr (leer, solange `RUNNING`). Dazu Kultur, Ernteprodukt, Liter,
    die sechs Maßnahmen und die gesehenen Düngerarten. Für die Erkennung kommen die Ausgangsstufen beim Start dazu und
    für die Korrektur je Wert die Quelle (`AUTO` / `MANUAL`).
  - `field_book_year`: abgeschlossene Erntejahre je Spielstand.
  - `field_book_notice`: Hinweise auf späte Erkennungen in abgeschlossenen Jahren (F5).
- [x] Bridge-Simulator: Szenario `feldbuch` mit Weizenfeld (Walz- und Mulchstufe), Grasfeld (`regrows`), Maisfeld mit
  `products[]` und steigendem `harvests[]`-Zähler. Dazu ein Steuer-Endpunkt für Feldstufen und Ernte-Liter.
  Umgesetzt: Feldstufen über das vorhandene `POST /field`, Ernte-Liter über `POST /harvest` (nur eigene Felder); der
  Zähler gehört zum simulierten Spielstand.
- [x] Mod-Tests mit gemockten FS25-Globals: Normalisierung von `rollerLevel`, `stubbleShredLevel`, `fruitTypes[]` und
  `harvests[]` (`test_farm_facts.lua`, `test_market_context.lua`); `test_roadmap_v33.lua` hält fest, dass der Adapter
  die Werte noch nicht liest. Die Tests des Hooks (nur eigene Flurstücke, Rückfall auf die Fruchtsorte aus dem
  Produkt, Speichern und Laden der Zähler) kommen mit F3.
- [x] Backend: `BridgeValidatorTest` und `SimulatorScenariosEndToEndTest`. Die Tests je Erkennungsregel (F2, F3), je
  Saisonregel (F1), Korrektur-Vorrang, Abschluss und Wiederöffnen (F5) sowie Auswertung (E) kommen mit F und E.
- [ ] Neue Werte unter `rpsim.formulas.field-book.*` samt `docs/dev/configuration-reference.md`
  (`ConfigurationReferenceDocTest`), z. B. `default-years` (**5**) – **mit E**.
- [x] Spieler-Doku `docs/user-guide/funktionen.md`, `CHANGELOG.md`. Q ist für Spieler nicht sichtbar, daher nur der
  `CHANGELOG`-Eintrag.
- [x] `docs/dev/manual-test-plan.md`: Abschnitt **„29. Roadmap V3.3 im echten FS25“**, eine Zeile je 🟡 und je
  Akzeptanzkriterium.

---

## F – Feldbuch

**Idee:** Eine neue App **„Feldbuch“** im Hof-Tablet. Im Tab **„Dokumentation“** stehen alle Felder, je Feld die
laufende Saison und die Einträge der Erntejahre. Fast alles füllt sich von selbst aus dem Spiel. Der Spieler kann jeden
Wert korrigieren und ein Jahr abschließen.

**Stand 09.10.2026: umgesetzt** (`field/FieldBookService`, `api/FieldBookController`, App „Feldbuch“, Migration V43,
Mod: Walz- und Mulchstufe, Kulturen der Karte, Ernte-Zähler mit beiden Hooks). Entscheidungen vom 09.10.2026 zu den
offenen Punkten (`QUESTIONS.md`): Eine Ernte schlägt „ohne Ernte“, von zwei Einträgen ohne Ernte bleibt der erste. Die
laufende Saison eines verkauften oder verpachteten Feldes wird verworfen. Die Fläche wird beim Ende festgehalten und ist
nicht änderbar. Die Liter-Zuordnung ist wie vorgeschlagen. Es gibt einen Knopf „automatisch“ (↺) je Wert. `needsRolling`
wird exportiert (Erweiterung von Q1), Kulturen ohne Walzbedarf zeigen „nicht nötig“. Die App steht nach der Flurkarte
mit eigenem Symbol (Klemmbrett) und hat nach F nur den Tab „Dokumentation“. Beim Zurückspulen werden Einträge, die nach
dem geladenen Zeitpunkt endeten, wieder laufend, und danach begonnene Saisons fallen weg. Im ersten Eintrag eines Feldes
zählen die vorgefundenen Stufen als erledigt. Ohne `fruitTypes` bietet das Dropdown die bekannten Kulturen an, ohne
`fieldRules` werden Kalk und Unkraut angezeigt.

**Entscheidungen 08.10.2026 (`QUESTIONS.md`):**
- Datenquelle: **alles automatisch, korrigierbar**. Kultur und Maßnahmen kommen aus den Feldwerten, die Liter vom
  Ernte-Zähler.
- Ort: **neue App „Feldbuch“** mit den Tabs „Dokumentation“ und „Auswertung“.
- Jahr = **Erntejahr**: das FS25-Jahr, in dem geerntet wird. **Genau ein Eintrag je Feld und Erntejahr.**
- Saison: **Mit der Ernte endet der Eintrag.** Alles danach (Mulchen und Kalken auf der Stoppel, Aussaat, Düngen) zählt
  schon zur nächsten Kultur.
- Ein laufender Eintrag heißt **„laufende Saison“**, ohne Jahr. Das Jahr kommt mit der Ernte. Ohne Ernte (verdorrt,
  untergepflügt, Brache) endet er mit der nächsten Aussaat einer anderen Kultur. Er zählt dann zum FS25-Jahr dieses
  Endes und ist als „ohne Ernte“ markiert.
- Mehrschnitt: Bleibt dieselbe Kultur stehen und wächst nach, kommen **alle Schnitte eines Erntejahres in einen
  Eintrag** (Liter addiert).
- Zweite Ernte einer **anderen** Kultur im selben Erntejahr: **nur die Hauptkultur zählt**. Die zweite Ernte wird
  nicht als eigener Eintrag geführt.
- Ernteprodukt: Jeder Eintrag speichert **Kultur und Ernteprodukt** (z. B. Mais als Körner oder als Häckselgut).
- Maßnahmen: **1. Düngung, 2. Düngung, gekalkt, gewalzt, Unkraut bekämpft, gemulcht.** Ist Kalk oder Unkraut in den
  Bodenregeln des Spielstands aus (`fieldRules`), wird die Spalte **ausgeblendet**.
- Düngung: **Stufe 1 erreicht = 1. Düngung, Stufe 2 erreicht = 2. Düngung**, egal womit. Die Düngerart wird zusätzlich
  angezeigt.
- Korrektur: **Die Korrektur gewinnt.** Ein geänderter Wert wird nie mehr automatisch überschrieben und ist als
  „manuell“ markiert.
- Wird eine Ernte nicht erkannt, schließt der Knopf **„Ernte eintragen“** den laufenden Eintrag ab (Fallback).
- Kulturen ohne gezählte Liter (z. B. Gras über Schwad und Ballen): **Liter selbst eintragen oder leer lassen.**
- Bearbeiten: **Jahr abschließen** per Knopf, danach gesperrt, auch für die automatische Erfassung. Späte Erkennungen
  erscheinen nur als Hinweis. Ein Jahr kann wieder geöffnet werden.
- Felder: **eigene und gepachtete**. Verkaufte oder verpachtete Felder bleiben mit ihren Jahren erhalten (markiert).
- Altdaten: **Das Feldbuch startet leer.** Die bisherige Kultur-Historie (`field_crop_history`) wird nicht übernommen.

### R33-F1 Einträge und Saison

- [x] Für jedes Feld aus `fields[]` (eigen oder gepachtet) gibt es immer genau einen Eintrag `RUNNING`, die
  „laufende Saison“. Er beginnt beim ersten Export nach dem Update. Danach beginnt ein neuer nach jedem Ende des
  vorigen.
- [x] **Ende durch Ernte** (Status `HARVESTED`, Erntejahr = `calendar.year` in diesem Moment). Erkannt wird die Ernte so:
  - wie heute in `FieldService` (Phase „erntereif“ gesehen, danach abgeerntet bzw. `cut`),
  - bei nachwachsenden Kulturen (`fruitTypes[].regrows`): `growthState` fällt nach „erntereif“ unter
    `minHarvestingGrowthState`, die Kultur bleibt gleich,
  - durch eine Lohnunternehmer-Ernte (`FIELD_WORK HARVEST` mit `APPLIED`, R31-A1),
  - durch den Knopf „Ernte eintragen“ (Fallback, siehe F4).
- [x] **Ende ohne Ernte** (Status `NO_HARVEST`): Auf dem Feld steht eine andere Kultur als im laufenden Eintrag. Das
  Erntejahr ist das FS25-Jahr dieses Moments. Der neue laufende Eintrag bekommt die neue Kultur.
- [x] **Schnitte summieren:** Endet ein Eintrag durch Ernte und gibt es für das Feld schon einen Eintrag `HARVESTED`
  mit derselben Kultur im selben Erntejahr, wird der neue in diesen eingerechnet. Die Liter werden addiert, die
  Maßnahmen zwischen den Schnitten mit „oder“ verknüpft.
- [x] **Nur Hauptkultur:** Endet ein Eintrag im selben Erntejahr mit einer **anderen** Kultur als der schon vorhandene
  Eintrag des Jahres, wird er verworfen. Er erscheint nicht im Feldbuch.
- [x] **Verkauf, Verpachtung, Pachtende:** Die abgeschlossenen Einträge bleiben (Entscheidung 08.10.2026). In der App
  ist das Feld als „verkauft“ bzw. „verpachtet“ markiert (`FarmlandOwnershipService`, `LeaseService`, R3-L). Was mit
  dem laufenden Eintrag passiert, steht in [Offene Punkte](#offene-punkte-vorschläge-zur-bestätigung). Kommt das Feld
  zurück, beginnt ein neuer laufender Eintrag.
- [x] Ein Eintrag kennt Feld (`farmlandId` und Name), Fläche (aus `fields[].hectares`; wann sie festgehalten wird,
  siehe [Offene Punkte](#offene-punkte-vorschläge-zur-bestätigung)), Kultur, Ernteprodukt, Liter, die sechs Maßnahmen
  und die Düngerarten.

**Beleg:** – für die Ernte-Erkennung über Phasen (`fields[]`, `withered` / `cut`, R2-C1). ✅ `regrows` und
`minHarvestingGrowthState` (`Fruits/FruitTypeDesc.md`, Q1). 🟡 Ob ein Grasschnitt in `getFieldState()` als Abfall von
`growthState` sichtbar wird (Feldmitte, `FieldManager` geht die Felder reihum durch). **Fallback:** Knopf „Ernte
eintragen“.

### R33-F2 Automatische Erfassung von Kultur und Maßnahmen

Das Backend vergleicht bei jedem Feld-Export (Mod-Einstellung `fieldExportIntervalMs`, Standard 10 s Echtzeit) die
Werte mit dem Stand beim Start des laufenden Eintrags. Ein gesetzter Haken bleibt gesetzt.

| Wert | Regel | Quelle | Beleg |
| --- | --- | --- | --- |
| Kultur | `fields[].fruitType`, sobald eine Kultur steht | seit R2-C1 | ✅ R2-C1 |
| Ernteprodukt | aus den gezählten Litern (F3). Ohne Liter: `fields[].fillType` (Standardprodukt) | Q1, R2-C | ✅ Q1 |
| 1. Düngung | `sprayLevel` erreicht im Eintrag `1` | seit R2-C1 | ✅ R2-C1, 🟡 unten |
| 2. Düngung | `sprayLevel` erreicht im Eintrag `2` | seit R2-C1 | ✅ R2-C1, 🟡 unten |
| Düngerart | jede im Eintrag gesehene `sprayType` außer `NONE` und `LIME` | seit R31-B3 | ✅ R31-B3 |
| Gekalkt | `limeLevel` steigt im Eintrag | seit R2-C1 | ✅ R2-C1 |
| Gewalzt | `rollerLevel` wechselt im Eintrag von `1` auf `0` | Q1 | ✅ Q1, 🟡 Q1 |
| Unkraut bekämpft | `weedState` sinkt im Eintrag | seit R2-C1 | ✅ R2-C1, 🟡 unten |
| Gemulcht | `stubbleShredLevel` wechselt im Eintrag auf `1` | Q1 | ✅ Q1, 🟡 Q1 |

- [x] Ein Lohnunternehmer-Auftrag (R31-A1) setzt die Werte zusätzlich direkt mit dem `APPLIED` seiner `FIELD_WORK`:
  `LIME` → gekalkt, `FERTILIZE` → nächste Düngung, `SOW` → Kultur. Umgesetzt: `LIME` über die Quittung (ein Haken
  doppelt schadet nicht). `FERTILIZE` und `SOW` ändern den Feldzustand im Spiel, den der nächste Export zeigt. Sie
  zählen deshalb nur über den Feldzustand, sonst würde eine Düngung doppelt gezählt.
- [x] Die Düngerart wird bei jeder Erhöhung der Düngestufe aus `sprayType` übernommen (nicht bei einem bloß gesehenen
  `sprayType`, der von der vorigen Saison stehen geblieben sein kann).
- [x] Kalk und Unkraut werden nur ausgewertet und angezeigt, wenn `fieldRules.limeRequired` bzw.
  `fieldRules.weedsEnabled` an ist (Entscheidung 08.10.2026, „ausblenden“).

**🟡 Im Spiel prüfen:**
- Setzt die Ernte bzw. das Wachstum die Düngestufe auf `0` zurück? Der Dump nennt `resetsSpray` (Standard `true`) und
  `startSprayLevel` je Kultur, den Ablauf aber nicht. **Fallback:** Ist die Stufe beim Start des Eintrags schon über
  `0`, zählt jede **Erhöhung** im Eintrag als nächste Düngung.
- Heben Gülle, Mist und Gärrest die Düngestufe wie Mineraldünger? `Sprayer` trennt Güllefass, Miststreuer und
  Düngerspritze (`isSlurryTanker`, `isManureSpreader`, `isFertilizerSprayer`, `Specializations/Sprayer.md`), alle
  schreiben über `FSDensityMapUtil.updateSprayArea`. Die Wirkung auf `SPRAY_LEVEL` ist nicht dokumentiert.
  **Fallback:** Der Spieler hakt die Düngung selbst ab.
- Welche `weedState`-Werte setzen Herbizid (`weedSystem:getHerbicideReplacements()`, `Sprayer.md`) und Hacke
  (`FSDensityMapUtil.updateWeederArea`, `Weeder.md`)? „Sinkt“ ist die Annahme für beide. **Fallback:** Der Spieler
  hakt selbst ab.
- Der Feldzustand ist ein Wert je Feld (Feldmitte, `FieldManager` reihum, Testplan 10.6). Eine nur halb gedüngte
  Fläche kann als gedüngt oder ungedüngt erscheinen. **Fallback:** Korrektur durch den Spieler.

### R33-F3 Automatische Erntemenge

- [x] Das Backend liest `harvests[]` bei jedem Export. Die Differenz zum zuletzt gesehenen Zählerstand je Feld,
  Kultur und Produkt bucht es in einen Eintrag. Die Regel für die Zuordnung steht in
  [Offene Punkte](#offene-punkte-vorschläge-zur-bestätigung):
  - in den laufenden Eintrag, wenn er diese Kultur hat,
  - sonst in den Eintrag `HARVESTED` desselben Feldes, derselben Kultur und des laufenden Erntejahres. Das deckt späte
    Liter, halb geerntete Felder und weitere Schnitte ab.
- [x] Eine Lohnunternehmer-Ernte bucht die Liter ihres `STORAGE_TRANSFER IN` (`ContractorWorkService.harvestLiters`)
  mit dem `APPLIED` der `FIELD_WORK HARVEST` und mit dem Standardprodukt der Kultur.
- [x] Sinkt ein Zähler (Spielstand neu geladen, Zurückspulen), zieht das Backend die Differenz wieder ab, höchstens bis
  `0`. Manuell korrigierte Liter bleiben unberührt (Korrektur gewinnt).
- [x] Ohne `harvests[]` (älterer Mod oder 🟡 aus Q1) bleibt die Liter-Spalte leer, bis der Spieler sie füllt.
- [x] Liter ohne passenden Eintrag (z. B. Kultur auf dem Feld nicht erkannt) gehen in den laufenden Eintrag. Ist dort
  keine Kultur gesetzt, übernimmt er die Kultur aus dem Zähler.

**Beleg:** ✅ Q1 (Ernte-Zähler). 🟡 siehe Q1.

### R33-F4 App „Feldbuch“ – Tab „Dokumentation“

- [x] Neue App `fieldbook` in `frontend/src/app/layout/apps.ts` (Gruppe „farm“, nach der Flurkarte, Symbol
  „clipboard“), Route `/feldbuch`, Tab `dokumentation` (`app-tabs.ts`; `auswertung` kommt mit E, Entscheidung
  09.10.2026), Erst-Hinweis in `app-hints.ts`.
- [x] Liste aller Felder (aktuelle zuerst, dann verkaufte und verpachtete, markiert). Je Feld:
  - die **laufende Saison**: Kultur, Haken, bisher gezählte Liter, Knopf **„Ernte eintragen“**,
  - darunter die Einträge je **Erntejahr**, die „ohne Ernte“ markiert.
- [x] Jeder Wert ist im Eintrag änderbar:
  - **Kultur** als Dropdown aller Kulturen der Karte (`fruitTypes[]`; Name aus den FarmPulse-Labels, sonst `title`
    des Spiels),
  - **Ernteprodukt** als Dropdown (Standardprodukt, `products[]` und gemeldete Produkte),
  - **sechs Haken** für die Maßnahmen (Kalk und Unkraut nur bei aktiver Bodenregel),
  - **Liter** als Zahl ≥ 0 (ein leeres Feld ändert nichts; „leer“ heißt: keine Liter gezählt und keine eingetragen).

  Ein geänderter Wert bekommt die Quelle `MANUAL` und das Zeichen „manuell“. Die Erkennung lässt ihn danach in Ruhe.
- [x] Knopf **„Ernte eintragen“** am laufenden Eintrag: beendet ihn als `HARVESTED` mit dem aktuellen FS25-Jahr. Die
  Regeln aus F1 (Schnitte summieren, nur Hauptkultur) gelten auch hier.
- [x] Die Düngerart erscheint als eigene Spalte (z. B. „Dünger, Gülle“; nur die im Code belegten Namen `FERTILIZER`,
  `LIQUID_MANURE` und `MANURE` sind übersetzt, andere stehen mit dem Namen des Spiels da).
- [x] Ohne Feld-Export (Mod ohne `fields[]`) gibt es keine Einträge. Die App sagt das in einem Hinweis.

**Beleg:** – (Oberfläche).

### R33-F5 Jahr abschließen

- [x] Im Tab „Dokumentation“ wählt der Spieler ein Erntejahr und drückt **„Jahr abschließen“**. Alle Einträge dieses
  Erntejahres sind dann gesperrt: keine manuelle Änderung und keine automatische Erfassung.
- [x] Erkennt das Spiel danach noch etwas für einen gesperrten Eintrag (späte Liter, ein Haken, ein weiterer Schnitt),
  wird es nicht gebucht. Es erscheint als **Hinweis** am Eintrag (`field_book_notice`), z. B. „Spiel meldet
  +12.000 l Weizen nach dem Abschluss“.
- [x] **„Jahr wieder öffnen“** hebt die Sperre auf. Die gesammelten Hinweise werden dann übernommen, außer bei manuell
  korrigierten Werten (Korrektur gewinnt).
- [x] Laufende Einträge haben kein Jahr und sind nie gesperrt.

**Beleg:** – (Backend und Oberfläche).

**Akzeptanz F:** Nach dem Update zeigt das Feldbuch für jedes eigene Feld eine laufende Saison. Ein Weizenfeld wird
zweimal gedüngt, gekalkt und gewalzt, dann geerntet. Der Eintrag zeigt das Erntejahr, alle Haken und die vom Drescher
gezählten Liter. Das Mulchen danach erscheint schon in der nächsten laufenden Saison. Drei Grasschnitte eines Jahres
stehen in einem Eintrag. Ein korrigierter Liter-Wert bleibt, auch wenn noch Liter gezählt werden. Ein abgeschlossenes
Jahr nimmt keine Änderung mehr an und zeigt späte Erkennungen nur als Hinweis. Bei einem Spielstand ohne Kalk fehlt
die Kalk-Spalte.

---

## E – Auswertung

**Idee:** Im Tab **„Auswertung“** sieht der Betriebsleiter die Erträge je Feld über die letzten Jahre und vergleicht, was
die Maßnahmen gebracht haben.

**Entscheidungen 08.10.2026 (`QUESTIONS.md`):**
- Zeitraum: **Standard die letzten 5 Erntejahre, frei wählbar von 1 bis „alle Jahre“.**
- Inhalt: **Tabelle Feld × Jahr, Ertrag je Hektar, Diagramm, Vergleich mit/ohne Maßnahme.**
- Diagramm: **Balken je Feld**. Ein Feld auswählen, dann Balken l/ha je Erntejahr, gefärbt nach Kultur.
- Vergleich: **je Kultur über alle Felder**. Ein Durchschnitt erscheint **immer**, mit der Anzahl `n`.

### R33-E1 Zeitraum und Tabelle Feld × Jahr

- [ ] Auswahl „letzte x Erntejahre“: Standard `field-book.default-years` (**5**), wählbar 1 bis „alle Jahre“. Gemeint
  sind die x Erntejahre bis einschließlich dem laufenden FS25-Jahr.
- [ ] Tabelle: Zeilen = Felder (auch verkaufte und verpachtete, wenn sie im Zeitraum Einträge haben), Spalten =
  Erntejahre. Jede Zelle zeigt Kultur und Produkt, Liter, l/ha und die Maßnahmen als kleine Zeichen. „Ohne Ernte“ ist
  markiert, leere Jahre bleiben leer.
- [ ] Laufende Einträge kommen in der Auswertung nicht vor (sie haben kein Erntejahr).

### R33-E2 Ertrag je Hektar und Diagramm

- [ ] l/ha = Liter ÷ Fläche des Eintrags (F1). Einträge ohne Liter haben kein l/ha und zählen in keinem Durchschnitt
  (Entscheidung „leer zählt nicht“).
- [ ] Diagramm „Balken je Feld“: Ein Feld wählen, dann ein Balken l/ha je Erntejahr im Zeitraum, gefärbt nach Kultur
  und Produkt, mit Legende. Inline-SVG wie die vorhandenen Diagramme (`features/bank/finance-card.html`), keine
  Diagramm-Bibliothek.

### R33-E3 Vergleich mit/ohne Maßnahme

- [ ] Je Kultur und Ernteprodukt (z. B. „Weizen“, „Mais (Häckselgut)“) über alle Felder im Zeitraum. Je Maßnahme:
  Ø l/ha **mit** und Ø l/ha **ohne**, jeweils mit `n`. Beispiel: „Weizen – gekalkt: Ø 8.900 l/ha (n = 6), nicht
  gekalkt: Ø 7.700 l/ha (n = 2)“.
- [ ] Maßnahmen, deren Bodenregel im Spielstand aus ist, fehlen auch hier (wie F4).

**Beleg:** – (Backend und Oberfläche).

**Akzeptanz E:** Die Auswertung zeigt beim Öffnen die letzten 5 Erntejahre. Auf „alle Jahre“ umgestellt, erscheinen
auch ältere Einträge. Für Feld 12 zeigt das Diagramm einen Balken je Jahr, gefärbt nach Kultur. Der Vergleich für
Weizen nennt Ø l/ha mit und ohne Kalk samt `n`. Körnermais und Häckselgut stehen getrennt. Einträge ohne Liter zählen
nicht mit.

---

## W – Wirkung auf Hofbericht, Chronik und Bank

**Entscheidung 08.10.2026 (`QUESTIONS.md`):** **Ja, ersetzen.** Wo das Feldbuch Liter hat, nutzen Hofbericht, Chronik
und Bank-Jahresgespräch diesen Wert statt der Schätzung.

### R33-W1 Gemessene Liter statt Schätzung

- [ ] Heute ist `field_crop_history.yieldLiters` = Fläche × `litersPerSqm` der letzten Sichtung „erntereif“ (R3-K3,
  Migration V26). Das ist der Grundertrag ohne Düngung, Kalk und die anderen Faktoren.
- [ ] `FarmReportService`, `ChronicleService` und `AnnualReviewService` nehmen für ein Feld und FS25-Jahr die Liter des
  Feldbuch-Eintrags `HARVESTED` mit diesem Erntejahr und derselben Kultur, wenn dort Liter stehen. Sonst bleibt die
  Schätzung. Weil das Erntejahr das FS25-Jahr der Ernte ist, passt es zum Jahr der Ernte in `field_crop_history`.
- [ ] Ob die Berichte zeigen, woher die Zahl kommt, steht in [Offene Punkte](#offene-punkte-vorschläge-zur-bestätigung).

**Beleg:** – (Backend).

**Akzeptanz W:** Nach einer gezählten Ernte nennt der Hofbericht des Jahres die Liter aus dem Feldbuch. Die Chronik
zeigt dieselbe Zahl. Für ein Feld ohne Feldbuch-Liter bleibt die Schätzung.

---

## Offene Punkte (Vorschläge zur Bestätigung)

Nicht durch Belege oder Entscheidungen gedeckt. Bis zur Entscheidung gilt der Vorschlag nur als Platzhalter. Eingetragen
in `QUESTIONS.md` mit Status `open`.

| Punkt | Frage | Vorschlag |
| --- | --- | --- |
| Q1 ✅ 08.10.2026 wie vorgeschlagen | Wie kommen die Ernte-Liter ins Backend, und was passiert nach dem Zurückspulen? | Kumulativer Zähler je Feld, Kultur und Produkt, gespeichert im Mod-Spielstand (`FS25_RPSim.xml`). Das Backend bucht die Differenz. Sinkt der Zähler, zieht es die Differenz wieder ab (nicht unter 0, nie aus manuellen Werten). |
| F1 ✅ 09.10.2026 wie vorgeschlagen | Erst beendeter, dann geernteter Eintrag im selben Jahr: Steht zuerst ein Eintrag „ohne Ernte“ (z. B. verdorrt) und wird im selben FS25-Jahr eine andere Kultur geerntet, welcher gilt als Hauptkultur? | Ein Eintrag mit Ernte schlägt einen ohne Ernte: Der Eintrag „ohne Ernte“ wird verworfen. Zwei Einträge ohne Ernte im selben Jahr: Der erste bleibt. |
| F1 ✅ 09.10.2026: verwerfen | Laufender Eintrag eines Feldes, das verkauft, verpachtet oder zurückgegeben wird | Wird verworfen (auf dem eigenen Hof gibt es keine Ernte mehr). Alternative: als „ohne Ernte“ im aktuellen Jahr behalten. |
| F1 ✅ 09.10.2026 wie vorgeschlagen | Fläche für l/ha | Fläche aus `fields[].hectares` beim Ende des Eintrags, nicht änderbar. |
| F3 ✅ 09.10.2026 wie vorgeschlagen | Zuordnung der Liter zu einem Eintrag | Wie in F3: laufender Eintrag mit dieser Kultur, sonst `HARVESTED`-Eintrag gleicher Kultur im laufenden Erntejahr, sonst der laufende Eintrag (übernimmt die Kultur). |
| F4 ✅ 09.10.2026 wie vorgeschlagen | Kann der Spieler einen „manuell“-Wert wieder an die Erkennung zurückgeben? | Ja, kleiner Knopf „automatisch“ je Wert. Danach gilt wieder der erkannte Wert. |
| F4 ✅ 09.10.2026: `needsRolling` exportieren, „nicht nötig“ statt Haken | Walzen bei Kulturen, die kein Walzen brauchen (`needsRolling = false`) | Haken bleibt sichtbar, wird aber nicht automatisch gesetzt (`rollerLevel` bleibt dort `0`). |
| E1 | Wird die Auswahl des Zeitraums gemerkt? | Ja, je Spielstand in den Einstellungen des Feldbuchs, bis der Spieler sie ändert. |
| W1 | Zeigen Hofbericht, Chronik und Bank, woher die Liter kommen? | Ja: „gemessen“ (Ernte-Zähler oder Lohnunternehmer), „eingetragen“ (manuell) oder „geschätzt“ (wie bisher). |
| F4 ✅ 09.10.2026: nach der Flurkarte, neues Symbol „clipboard“ | Platz der App auf dem Startbildschirm und Symbol | Gleich nach „Flurkarte“, Gruppe „farm“, Symbol „book“ bzw. „clipboard“. |

---

## Bewusst nicht aufgenommen

| Idee | Warum nicht |
| --- | --- |
| Pflügen als Maßnahme | Entscheidung 08.10.2026: nicht gewählt. `plowLevel` wird weiter nur für die Hinweise der Genossenschaft genutzt (R2-C6). |
| Übernahme der bisherigen Kultur-Historie | Entscheidung 08.10.2026: Das Feldbuch startet leer. Die alten Einträge sind nach Kalenderjahr gezählt und ihre Liter nur geschätzt. |
| Zweite Kultur im selben Erntejahr als eigener Eintrag (Zwischenfrucht, Nachkultur) | Entscheidung 08.10.2026: nur die Hauptkultur. |
| Ballen zählen (Heu, Silage, Stroh) | Entscheidung 08.10.2026: Liter selbst eintragen oder leer. Ballen sind Objekte und kein Füllstand. Objektlager sind seit V3 ausgeklammert. |
| Ertragskarte des Precision-Farming-DLC (`PrecisionFarmingStatistic.getPFYieldMap`) | Gehört zu einem DLC und nicht zum Grundspiel. Der Ernte-Zähler aus Q1 braucht es nicht. |
| Teilflächen (halb gedüngt, halb gekalkt) | Der Mod liest einen Feldzustand je Feld (`getFieldState`, R2-C1). Schon das Ablaufen aller Felder kostet Zeit und läuft deshalb nur alle `fieldExportIntervalMs` (`docs/dev/bridge-protocol.md`). Ein Abtasten von Teilflächen ist nicht vorgesehen. |
| Kosten und Erlös je Feld in € | Nicht Teil der Beschreibung vom 08.10.2026. Kann eine spätere Roadmap ergänzen. |

---

## Quellen

- FS25-Quellcode-Dump (`dataS`): <https://github.com/Dukefarming/FS25-lua-scripting>. Genutzt wurde
  `field/FieldState.lua` (`rollerLevel`, `stubbleShredLevel`, `sprayLevel`, `limeLevel`, `weedState`).
- FS25 Community LUADOC: <https://github.com/umbraprior/FS25-Community-LUADOC> (Stand 16.08.2026). Genutzt wurden:
  - `script/Specializations/Cutter.md` (`onEndWorkAreaProcessing`, Aufruf von `addCutterArea`,
    `getHarvestScaleMultiplier` mit Spritz-, Pflug-, Kalk-, Unkraut-, Stoppel- und Walzfaktor)
  - `script/Specializations/Combine.md` (`addCutterArea`: Liter, Regen- und Schadensabzug, Rückgabe der gebuchten
    Menge, Rückfall auf die Fruchtsorte aus dem Produkt)
  - `script/Specializations/WorkArea.md` (`getLastTouchedFarmlandFarmId`, `getIsAccessibleAtWorldPosition`)
  - `script/Economy/FarmlandManager.md` (`getFarmlandIdAtWorldPosition`)
  - `script/Field/FieldState.md` (`FieldState.new`)
  - `script/GUI/MapOverlayGenerator.md` (`NEEDS_ROLLING`, `MULCHED`, `NEEDS_LIME`, `FERTILIZED` und ihre Zustände)
  - `script/Fruits/FruitTypeManager.md` (`getFruitTypes`, `getFillTypeNameByFruitTypeIndex`,
    `getFruitTypeIndexByFillTypeIndex`, `addFruitTypeConverter`, `addFruitTypeConversion`, `getConverterDataByName`)
  - `script/Fruits/FruitTypeDesc.md` (`regrows`, `firstRegrowthState`, `resetsSpray`, `startSprayLevel`,
    `needsRolling`, `allowsSeeding`)
  - `script/FillTypes/FillTypeDesc.md` (`title`)
  - `script/Specializations/Sprayer.md` (Güllefass, Miststreuer, Düngerspritze, `updateSprayArea`,
    `getHerbicideReplacements`), `Roller.md` (`updateRollerArea`), `Mulcher.md` (`updateMulcherArea`), `Weeder.md`
    (`updateWeederArea`)
  - `script/Specializations/PrecisionFarmingStatistic.md` (DLC-Ertragskarte, nicht genutzt)
- Bestehende Projekt-Doku und Code: [`ROADMAP_V2.md`](ROADMAP_V2.md) (C1–C6), [`ROADMAP_V3.md`](ROADMAP_V3.md) (K3, L,
  T2), [`ROADMAP_V3.1.md`](ROADMAP_V3.1.md) (A1, B3, K1), `docs/dev/bridge-protocol.md` (`fields`, `fieldRules`,
  `calendar`, `FIELD_WORK`, `STORAGE_TRANSFER`), `docs/dev/manual-test-plan.md` (10.6, 10.15, 21),
  `mod/FS25_RPSim/src/export/FarmFacts.lua`, `mod/FS25_RPSim/src/game/GameAdapter.lua`,
  `mod/FS25_RPSim/src/import/Persistence.lua`, `backend/.../field/FieldService.java`,
  `backend/.../farmwork/ContractorWorkService.java`, `backend/.../finance/FarmReportService.java`,
  `backend/.../diary/ChronicleService.java`, `backend/.../credit/AnnualReviewService.java`,
  `backend/src/main/resources/db/migration/V18__fields_crops_weather.sql` und `V26__credit_planning.sql`,
  `frontend/src/app/layout/apps.ts`, `app-tabs.ts`, `app-hints.ts`.
