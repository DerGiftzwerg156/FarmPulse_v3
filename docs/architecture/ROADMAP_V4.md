# FarmPulse – Roadmap V4

Nachfolger von [Roadmap V3](ROADMAP_V3.md) bis [Roadmap V3.3](ROADMAP_V3.3.md), Stand 13.10.2026. Ziel: Der
Betriebsleiter bekommt drei Werkzeuge, mit denen er seinen Hof wie ein echter Landwirt plant und rechnet:

1. **Deckungsbeitrag je Feld:** Für jeden Feldbuch-Eintrag (Feld × Erntejahr) steht, was die Ernte wert war, was
   Saatgut, Dünger, Kalk, Pflanzenschutz, Lohnunternehmer und Pacht gekostet haben und was am Ende übrig blieb.
2. **Anbauplaner:** Für die nächste Saison wählt der Spieler je Feld die Kultur. Das Tool prüft Fruchtfolge, Saatfenster
   und Lagerplatz und rechnet Ertrag, Kosten und Deckungsbeitrag hoch.
3. **Futterbilanz:** Je Stall und Futtergruppe steht, wie viel die Tiere am Tag fressen, wie viel Futter da ist und
   wie viele Tage es noch reicht, bis zur nächsten Ernte und über das ganze Jahr.

Der Projektinhaber hat die drei Punkte am 13.10.2026 ausgewählt. Roadmap V3.3 hat „Kosten und Erlös je Feld in €“
ausdrücklich für eine spätere Roadmap offengelassen (Abschnitt „Bewusst nicht aufgenommen“). Die offenen Punkte hat
der Projektinhaber ebenfalls am 13.10.2026 entschieden. Die Entscheidungen stehen in `QUESTIONS.md` (13.10.2026), am
Anfang jedes Bereichs und unter [Offene Punkte](#offene-punkte-vorschläge-zur-bestätigung). Wie V2 bis V3.3 enthält
die Datei **nur Punkte, die mit der FS25-Schnittstelle machbar sind**. Jeder Punkt mit Mod-Eingriff wurde am
13.10.2026 gegen den FS25-Quellcode-Dump und die Community-LUADOC geprüft (Quellen am Ende).

**Warum das zum Spiel passt:** Das Spiel rechnet die Verbräuche selbst aus und bucht sie nur für Helfer als Geld.
`SowingMachine:onEndWorkAreaProcessing` rechnet das Saatgut als `fruitDesc.seedUsagePerSqm × ha × 10000 ×
seedUsageScale`, `Sprayer:onEndWorkAreaProcessing` den Verbrauch an Dünger, Kalk und Herbizid
(`spec.workAreaParameters.usage`). Die Tiere fressen jede Spielstunde `litersPerHour`, das der Stall aus Alter und Anzahl
der Tiere bildet (`PlaceableHusbandryFood:onHusbandryAnimalsUpdate`). FarmPulse liest also echte Verbräuche und erfindet
keine.

**Grundsätze (unverändert aus V1–V3.3)**

- Der Mod bleibt **Sensor und Aktuator**. Er zählt Verbräuche und meldet Werte. Das Backend rechnet alle Beträge,
  Reichweiten und Prognosen. Die KI formuliert höchstens die Mails der Charaktere, sie rechnet nie.
- Jeder Zugriff auf FS25 läuft in `RPSimGameAdapter` und ist mit `pcall` abgesichert. Fehlt eine API, fällt die
  Funktion still auf das bisherige Verhalten zurück. In V4 heißt das: Der Wert wird **geschätzt** und so markiert,
  oder der Spieler trägt ihn selbst ein.
- Neue Felder in den Bridge-Dateien sind **optional** (`schemaVersion` bleibt `1`).
- Werte gibt der Spieler nur über Formulare ein (Auswahl, Haken, Zahl).
- Spielmonat = FS25-Periode, Periode 1 = März. Ein FS25-Jahr beginnt im März (`docs/dev/bridge-protocol.md`, Block
  `calendar`).
- Wie im Feldbuch (R33-F) gilt: **Die Korrektur gewinnt.** Ein vom Spieler geänderter Wert wird nie mehr automatisch
  überschrieben.
- Jede Zahl zeigt ihre **Herkunft**, wie die Liter seit R33-W1: „gemessen“, „eingetragen“, „geschätzt“ und neu
  „verteilt“ (Gemeinkosten nach Fläche, siehe D3).
- Planer und Bilanz sind **Hilfen, keine Pflicht.** Das Tool sperrt nichts im Spiel und bestraft keine Abweichung vom
  Plan.

**Hängt ab von V2 / V3 / V3.1 / V3.3:**
- Feldbuch mit Einträgen, Saison-Regeln, Ernte-Zähler `harvests[]` und Auswertung (R33-F, R33-E,
  `field/FieldBookService`, `field/FieldBookEvaluationService`), Kulturen der Karte `fruitTypes[]` (R33-Q1),
- Buchungsjournal `finances` und Kontoauszug `bookings` mit `fillType` / `liters` bei Verkäufen (R2-B1,
  Entscheidungen 06.10.2026, `finance/BookingStatementService`),
- Feldwerte `fields[]`, Bodenregeln `fieldRules` (R2-C1), Düngerart `sprayType` (R31-B3),
- Lohnunternehmer `FIELD_WORK` mit `CONTRACTOR_FEE` je Feld (R31-A1), Pacht `LEASE_PAYMENT` (`contract/LeaseService`),
- Flächenprämie `DIRECT_PAYMENT` und Fruchtfolge-Regeln (R2-E2, R31-B1, `authority/DirectPaymentService`),
  Düngeverordnung (R31-B3, `authority/FertilizerRulesService`),
- Vorkontrakte und Großaufträge (R3-M2, R32-G, `market/ForwardContractService`, `market/BulkOrderService`),
- Ställe `husbandries[]` mit `food`, `subTypes`, Tierpfleger-Warnungen (R2-A7, R31-A3, `contract/LivestockService`,
  `tablet/StableService`), Nachbarhandel mit Ware (R3-H3),
- eigene Silos `tradeStorage` (R3-Q1), Hofbericht (R3-K3, `finance/FarmReportService`), Kalender und Aufgaben.

## Legende

| Zeichen | Bedeutung |
| --- | --- |
| ✅ **Belegt** | Die genutzte Funktion bzw. das Feld steht so im FS25-Code; Fundstelle ist angegeben. |
| 🟡 **Im Spiel prüfen** | Die API existiert, aber ein Detail lässt sich nur im laufenden Spiel klären. Zu jedem 🟡 steht ein Fallback. |
| – | Kein Mod-Eingriff: nur Backend und Oberfläche mit Daten und Anweisungen, die es seit V1–V3.3 gibt. |

Punkt-IDs: `R4-<Bereich><Nummer>`, z. B. `R4-D2`. Alle Pfade sind relativ zum Repo-Root.

## Übersicht und empfohlene Reihenfolge

| Phase | Bereich | Inhalt | Mod-Eingriff | Hängt ab von |
| --- | --- | --- | --- | --- |
| 0 | [Q – Querschnitt](#q--querschnitt) | Verbrauchs-Zähler je Feld, Einkaufspreise, Saat- und Erntefenster, Futterwerte der Ställe, Futtergruppen, Simulator, Testplan | ja | R33-Q1, R2-A7 |
| 1 | [D – Deckungsbeitrag je Feld](#d--deckungsbeitrag-je-feld) | Kosten und Erlös je Feldbuch-Eintrag, Korrektur, Karte „Deckungsbeitrag“ in der Bank, Hofbericht (**umgesetzt 13.10.2026**) | D1: ja (über Q1) | Q, R33-F, R2-B1 |
| 2 | [F – Futterbilanz](#f--futterbilanz) | Bedarf, Vorrat, Reichweite je Futtergruppe, Ballen eintragen, Warnungen, Jahresbedarf (**umgesetzt 13.10.2026**) | F1: ja (über Q2) | Q, R2-A7 |
| 3 | [A – Anbauplaner](#a--anbauplaner) | Plan je Feld, Prüfungen, Prognose, Bedarf und Absicherung, Aufgaben, Plan und Ist (**umgesetzt 14.10.2026**) | A2: ja (über Q1) | Q, D, F, R33-E |

D zuerst: Der Planer nimmt die Kosten je Hektar aus D. F kann parallel zu D laufen. A kommt zuletzt, weil er D (Kosten)
und F (Futterbedarf fürs Jahr) zusammenführt. Alle Bereiche funktionieren mit einem älteren Mod (ohne Q). Dann sind
Verbräuche und Fenster **geschätzt** bzw. fehlen, und der Spieler trägt sie selbst ein.

---

## Q – Querschnitt

**Entscheidung 13.10.2026 (`QUESTIONS.md`):** Saatgut, Dünger und Futter werden wie der Ernte-Zähler aus V3.3 als
**fortlaufende Zähler im Mod-Spielstand** geführt; Helfer-Käufe getrennt (`helperLiters`, bewertet `× 1.5` wie im
Spiel).

Wie R31-Q und R33-Q legt Q **zuerst den Vertrag** an: Schemas, DTOs, Validator, Normalisierung im Mod
(`RPSimFarmFacts.build`, `RPSimMarketContext.build`), Simulator und Doku. Das Auslesen im Spiel folgt mit D1, F1 und A2.
Bis dahin fehlen die Werte.

**Stand 13.10.2026: Vertrag umgesetzt** (Q1, Q2, Q3 ohne die Teile, die laut Roadmap mit D, F und A kommen). Hinweise
zur Umsetzung (kein neues Verhalten, `QUESTIONS.md` 13.10.2026):
- `helperLiters` steht immer im Eintrag (`0`, wenn der Mod nichts weiß) und ist nie größer als `liters`.
- `trough[]` und `feedStock[]` fassen Einträge derselben Sorte zusammen und lassen Mengen unter 1 Liter weg;
  `feedConsumed[]` behält Zähler mit `0`.
- `animalFood[].groups[]` behält die Reihenfolge des Spiels (sie kann die Fressreihenfolge sein), Gruppen ohne Sorte
  fallen weg.
- Die Mod-Einstellung `costFillTypes` kommt mit D1, weil erst dann Preise gelesen werden.
- Der Simulator hat neben `POST /input` und `POST /feed` den Endpunkt `POST /eat` (die Tiere fressen aus dem Trog, der
  Futter-Zähler wächst), damit `feedConsumed[]` ohne Spiel steigt.

### R4-Q1 Bridge erweitern: Felder und Preise

- [x] `farm_facts.json`, neuer optionaler Block `inputs[]` = `{ farmlandId, fillType, liters, helperLiters }`. Das ist
  ein **kumulativer Verbrauchs-Zähler** je eigenem Feld und Betriebsmittel, wie der Ernte-Zähler `harvests[]` aus
  R33-Q1:
  - `liters`: alles, was Sämaschinen und Spritzen auf diesem Feld verbraucht haben (Saatgut, Dünger, Flüssigdünger,
    Kalk, Herbizid, Gülle, Mist, Gärrest),
  - `helperLiters`: der Teil davon, den ein Helper beim Arbeiten selbst gekauft hat (Spieleinstellung „Helfer kauft
    Saatgut / Dünger“). Das Spiel bucht ihn sofort als `PURCHASE_SEEDS` / `PURCHASE_FERTILIZER` mit Aufschlag `× 1.5`.
  - Der Mod speichert die Zähler im eigenen Spielstand (`FS25_RPSim.xml`, `RPSimPersistence`, wie `harvests`). Wird ein
    älterer Spielstand geladen, sind die Zähler auf dem Stand dieses Spielstands.
- [x] `market_context.json`, neuer optionaler Block `fillTypeCosts[]` = `{ fillType, costPerLiter }`: der Einkaufspreis
  des Spiels je Liter. Enthalten sind alle Sorten, die in `inputs[]` vorkommen, die Futtersorten aus `animalFood[]`
  (Q2) und die Liste der Mod-Einstellung `costFillTypes` (Standard: `SEEDS`, `FERTILIZER`, `LIQUIDFERTILIZER`, `LIME`,
  `HERBICIDE`). Neu gebaut wie der Rest von `market_context` (alle 60 s, nur bei Änderung gelesen). Umgesetzt
  (Vertrag); die Mod-Einstellung `costFillTypes` kommt mit D1.
- [x] `market_context.json`, Block `fruitTypes[]` (R33-Q1) bekommt optional:
  - `seedUsagePerSqm`: Saatgut in Litern je m² (für A3, Saatgutbedarf),
  - `litersPerSqm`: Grundertrag je m² (heute nur an `fields[]` für Felder mit Kultur; für A3 braucht der Planer ihn
    auch für Kulturen, die gerade nirgends stehen),
  - `plantablePeriods[]` und `harvestablePeriods[]`: die Perioden 1..12, in denen die Kultur gesät bzw. geerntet werden
    kann, im Wachstumsmodus des Spielstands (für A2 und A5).
- [x] `docs/dev/bridge-protocol.md` je Feld und Block mit Quelle im FS25-Code.

**Beleg (Verbrauchs-Zähler):**
- ✅ `Specializations/SowingMachine.md`, `onEndWorkAreaProcessing`: `usage = fruitDesc.seedUsagePerSqm * lastHa * 10000 *
  spec.seedUsageScale`, bei Schaden erhöht (`DAMAGED_USAGE_INCREASE`). Ohne Helper-Kauf zieht die Maschine `-usage` aus
  dem Saatgut-Tank (`addFillUnitFillLevel`). Mit `self:getIsAIActive()` und `missionInfo.helperBuySeeds` bucht sie
  `usage * economyManager:getCostPerLiter(spec.seedFillType, false) * 1.5` als `MoneyType.PURCHASE_SEEDS`.
- ✅ `Specializations/Sprayer.md`, `onEndWorkAreaProcessing`: `usage = spec.workAreaParameters.usage`, Sorte
  `spec.workAreaParameters.sprayFillType` (gesetzt in `onStartWorkAreaProcessing` über `getSprayerUsage(fillType, dt)`).
  Beim Helper-Kauf bucht `onStartWorkAreaProcessing` `usage * getCostPerLiter(fillType, false) * 1.5` als
  `MoneyType.PURCHASE_FERTILIZER`. Das gilt für Dünger, Kalk und Herbizid gleichermaßen, Gülle und Mist laufen über
  denselben Weg (`isSlurryTanker`, `isManureSpreader`).
- ✅ `Specializations/FertilizingSowingMachine.md`: Die Düngung beim Säen läuft über `spec_sprayer.workAreaParameters`
  (`sprayerParams.usage`).
- ✅ `Economy/FarmlandManager.md`: `getFarmlandIdAtWorldPosition(x, z)`; Besitzer über `getFarmlandOwner` (wie der
  Ernte-Zähler in R33-Q1).

**Beleg (Preise, Kulturen):**
- ✅ `g_currentMission.economyManager:getCostPerLiter(fillType, false)`: so ruft das Spiel den Einkaufspreis in
  `SowingMachine.md`, `Sprayer.md`, `Motorized.md` und `TreePlanter.md` auf. Der Code von `EconomyManager` selbst steht
  weder im Dump noch in der LUADOC (🟡 unten).
- ✅ `FillTypes/FillTypeDesc.md`: `pricePerLiter` (`economy#pricePerLiter` aus der XML), Grundpreis je Liter.
- ✅ `Fruits/FruitTypeDesc.md`: `seedUsagePerSqm` (`seeding#litersPerSqm`), `getIsPlantableInPeriod(growthMode,
  seasonPeriod)`, `getIsHarvestableInPeriod(growthMode, seasonPeriod)`. `SowingMachine.md` ruft die erste genau so auf:
  `fruitDesc:getIsPlantableInPeriod(g_currentMission.missionInfo.growthMode, g_currentMission.environment.currentPeriod)`.
  Die Periode ist also dieselbe wie `calendar.period`.

**Mod-Ablauf Verbrauchs-Zähler (mit D1):** Hooks auf `SowingMachine.onEndWorkAreaProcessing` und
`Sprayer.onEndWorkAreaProcessing` (`Utils.overwrittenFunction`, beim Laden des Mods wie `Cutter.onEndWorkAreaProcessing`
in R33-F3). Der Hook ruft das Original auf und zählt danach, nur auf dem Server (`isServer`) und nur bei `usage > 0`:
Feld über `getFarmlandIdAtWorldPosition` an der Position der Maschine (`getWorldTranslation(self.rootNode)`), nur
Flurstücke der Spieler-Farm (gepachtete eingeschlossen). Sorte: beim Säen `spec.seedFillType` bzw. die Sorte des
Saatgut-Tanks, beim Spritzen `sprayFillType`. `helperLiters` zählt, wenn `getIsAIActive()` und die passende
Helper-Einstellung (`helperBuySeeds` bzw. `helperBuyFertilizer`) an ist.

**🟡 Im Spiel prüfen:**
- Greifen die Hooks auf die Event-Listener? `SpecializationUtil.registerEventListener` trägt die Funktion beim
  Registrieren des Fahrzeugtyps ein. Der Hook muss also vorher sitzen, wie bei `Cutter` (Testplan 29.2).
  **Fallback:** keine `inputs[]`. D1 schätzt dann (siehe D1).
- `usage` in `SowingMachine` wird **vor** dem Helper-Zweig gerechnet, also auch bei Helfern. Ob `Sprayer` bei Helfern
  ohne Tank (`sprayVehicle = nil`) `workAreaParameters.usage` setzt, ist aus `onStartWorkAreaProcessing` nicht sicher zu
  lesen. **Fallback:** Für Helfer ohne Tank zählt die Spritze nicht, und D1 nimmt für den Monat die Buchung
  `PURCHASE_FERTILIZER` (verteilt nach gespritzter Fläche, siehe D1).
- Zählt eine Düngesämaschine (`FertilizingSowingMachine`) den Dünger einmal, oder läuft `Sprayer.onEndWorkAreaProcessing`
  zusätzlich zu ihrer eigenen Buchung? **Fallback:** Der Mod zählt je Fahrzeug und Spiel-Tick nur einmal je Sorte.
- Zweiter Parameter von `getCostPerLiter` (vermutlich ohne Schwierigkeits-Aufschlag). **Fallback:** `pricePerLiter`
  der `FillTypeDesc`, multipliziert mit dem Preisfaktor des Spielstands, wenn er lesbar ist, sonst ohne.
- Feldrand: Die Maschinenmitte kann auf dem Nachbar-Flurstück stehen (wie R33-Q1). **Fallback:** Korrektur durch den
  Spieler (D2).
- Kartoffel- und Zuckerrohr-Legemaschinen säen mit der Ernteware selbst als Saatgut. Ob sie über `SowingMachine` laufen
  und welche Sorte `seedFillType` dann hat, wird im Spiel geprüft. **Fallback:** Liter selbst eintragen.

### R4-Q2 Bridge erweitern: Ställe und Futter

- [x] `farm_facts.json`, Einträge in `husbandries[]` (R2-A7) bekommen optional:
  - `foodLitersPerHour`: Futterbedarf des Stalls je Spielstunde,
  - `foodCapacity`: Fassungsvermögen des Futtertrogs,
  - `trough[]` = `{ fillType, amount }`: Inhalt des Trogs je Futtersorte (ganze Liter, nur `amount > 0`),
  - `strawLitersPerHour` und `straw`: Einstreu-Bedarf je Spielstunde und Stroh im Stall (nur Ställe mit Einstreu),
  - `feedConsumed[]` = `{ fillType, liters }`: **kumulativer Zähler** der gefressenen Liter je Sorte, gespeichert im
    Mod-Spielstand wie `harvests`.
- [x] `farm_facts.json`, neuer optionaler Block `feedStock[]` = `{ fillType, amount }`: Futtervorrat in **eigenen
  Silos, Silo-Erweiterungen und Fahrsilos**. Anders als `assets.storage` ohne Produktionen (deren Lager ist kein Futter)
  und anders als `tradeStorage` mit Fahrsilos (Silage). Nur Sorten, die in `animalFood[]` vorkommen, plus `STRAW`.
- [x] `market_context.json`, neuer optionaler Block `animalFood[]`, einmal je Missionsstart gebaut:
  `{ animalType, groups: [{ title, productionWeight, fillTypes[] }], mixtures[] }` für jede Tierart der Karte.
- [x] Dokumentation, Validator und Normalisierung wie Q1.

**Beleg (Dump `animals/husbandry/placeables/`):**
- ✅ `PlaceableHusbandryFood.lua`, `onHusbandryAnimalsUpdate`: `litersPerHour = Σ subType.input.food:get(age) ×
  cluster:getNumAnimals() / 24`. Lesbar über die registrierte Funktion `getFoodLitersPerHour`.
- ✅ `PlaceableHusbandryFood.lua`: `spec.fillLevels[fillTypeIndex]` (Trog je Sorte), `getFoodCapacity()`,
  `getTotalFood()`.
- ✅ `PlaceableHusbandryFood.lua`, `updateFeeding`: `animalFoodSystem:consumeFood(animalTypeIndex, litersPerHour ×
  environment.timeAdjustment, self, consumedFood)`, danach `self:removeFood(delta, fillTypeIndex)` je Sorte.
  `removeFood` ist registriert und gibt die tatsächlich entnommene Menge zurück.
- ✅ `PlaceableHusbandry.lua`, `onHourChanged`: Die Tiere fressen einmal je Spielstunde (`updateFeeding`).
- ✅ `PlaceableHusbandryFood.lua`, `getFoodInfos`: `animalFoodSystem:getAnimalFood(animalTypeIndex).groups` mit `title`,
  `productionWeight` und `fillTypes`. `onPostLoad`: `animalFoodSystem:getMixturesByAnimalTypeIndex(animalTypeIndex)`
  (Mischfutter wie TMR).
- ✅ `PlaceableHusbandryStraw.lua`: `inputLitersPerHour = Σ subType.input.straw:get(age) × Anzahl / 24`; je Stunde
  `removeHusbandryFillLevel(farmId, amount, FillType.STRAW)`. Der Stroh-Vorrat im Stall ist damit
  `getHusbandryFillLevel(FillType.STRAW, farmId)` (dieselbe Funktion wie bei der Milch, R32-Q1).
- ✅ Silos, Erweiterungen und Fahrsilos liest der Mod schon für `assets.storage` (`RPSimGameAdapter:storageSources`,
  offene technische Punkte Nr. 8). `feedStock` nimmt dieselben Quellen ohne Produktionen.

**Mod-Ablauf Futter-Zähler (mit F1):** Hook auf `PlaceableHusbandryFood.removeFood` (`Utils.overwrittenFunction`). Der
Hook ruft das Original auf und zählt dessen Rückgabe, nur auf dem Server und nur für Ställe der Spieler-Farm.

**🟡 Im Spiel prüfen:**
- Greift der Hook auf `removeFood`? Die Funktion wird mit `SpecializationUtil.registerFunction` am Typ eingetragen,
  wie `Combine.addCutterArea` in R33-Q1. **Fallback:** Das Backend misst den Verbrauch aus dem **Sinken** von
  `trough[]` zwischen zwei Exporten. Steigt eine Sorte (nachgefüllt), zählt dieser Abschnitt nicht.
- Wie `consumeFood` den Bedarf auf die Futtergruppen verteilt (nacheinander oder anteilig), steht nicht im Dump
  (`AnimalFoodSystem` fehlt). **Fallback:** Der gemessene Verbrauch je Sorte (`feedConsumed`) zeigt die echte
  Verteilung. Solange noch nichts gemessen ist, wird nach `productionWeight` der Gruppen verteilt (Entscheidung
  13.10.2026).
- `environment.timeAdjustment` ist nirgends definiert. Ob `litersPerHour × 24` schon der Tagesbedarf ist, zeigt erst
  das Spiel. **Fallback:** F1 rechnet mit dem **gemessenen** Verbrauch je Spieltag und nutzt `foodLitersPerHour` nur
  für Änderungen der Herde (siehe F1).
- Futterroboter (`PlaceableHusbandryFeedingRobot.lua`, `createFoodMixture(getFoodLitersPerHour × timeAdjustment)`)
  mischen aus einem eigenen Lager. Ob es lesbar ist, wird im Spiel geprüft. **Fallback:** Das Lager des Roboters fehlt
  im Vorrat, der Spieler trägt es als „sonstiger Vorrat“ ein (F2).
- Wird die Mischration (TMR) im Trog als eigene Sorte geführt (`mixtures[]`)? **Fallback:** Mischfutter zählt nur als
  Inhalt des Trogs, nicht als Vorrat der Gruppen.

### R4-Q3 Domäne, Simulator, Tests, Konfiguration, Doku, Testplan

- [ ] Neue Tabellen kommen **mit den Bereichen**, nicht mit Q (wie R33-Q2):
  - mit D: `field_margin_item` (Kosten- und Erlöszeilen je Feldbuch-Eintrag: Art, Sorte, Liter, Preis, Betrag, Herkunft,
    Quelle `AUTO` / `MANUAL`), `field_input_counter` (zuletzt gesehener Zählerstand je Feld und Sorte),
  - mit F: `feed_manual_stock` (eingetragene Ballen und sonstiger Vorrat), `feed_consumption_day` (Verbrauch je Stall,
    Sorte und Spieltag), `feed_counter`,
  - mit A: `crop_plan` (Plan je Feld und Saison, Quelle, Abweichung).
- [x] Bridge-Simulator: Szenario `hofplanung` mit zwei Feldern (Weizen gesät und gedüngt, Gras nach dem ersten
  Schnitt, steigendes `inputs[]`), einem Milchviehstall mit `trough[]`, `feedStock[]` (Silage im Fahrsilo, Heu im Silo)
  und steigendem `feedConsumed[]`, dazu `animalFood[]`, `fillTypeCosts[]` und die neuen Werte an `fruitTypes[]`.
  Steuer-Endpunkte `POST /input` (Verbrauch auf ein Feld buchen), `POST /feed` (Futter in Trog oder Silo legen oder
  herausnehmen) und `POST /eat` (die Tiere fressen aus dem Trog).
- [ ] Mod-Tests mit gemockten FS25-Globals: Normalisierung aller neuen Felder (`test_farm_facts.lua`,
  `test_market_context.lua`) und neu `test_roadmap_v4.lua` (Hooks zählen nur eigene Flurstücke und Ställe, nur auf dem
  Server, Speichern und Laden der Zähler, Helper-Liter). Umgesetzt: Normalisierung; `test_roadmap_v4.lua` hält fest,
  dass der Adapter die Werte noch nicht liest. Die Tests der Hooks kommen mit D1 und F1.
- [x] Backend: `BridgeValidatorTest`, `SimulatorScenariosEndToEndTest`, Tests je Regel kommen mit D, F und A.
- [ ] Neue Werte unter `rpsim.formulas.field-margin.*`, `rpsim.formulas.feed-balance.*` und
  `rpsim.formulas.crop-planning.*` samt `docs/dev/configuration-reference.md` (`ConfigurationReferenceDocTest`), jeweils
  mit dem Bereich.
- [ ] Spieler-Doku `docs/user-guide/funktionen.md` mit jedem Bereich, `CHANGELOG.md`.
- [x] `docs/dev/manual-test-plan.md`: Abschnitt **„30. Roadmap V4 im echten FS25“**, eine Zeile je 🟡 und je
  Akzeptanzkriterium.

---

## D – Deckungsbeitrag je Feld

**Idee:** Eine neue Karte **„Deckungsbeitrag“** in der App „Bank“. Für jeden beendeten Feldbuch-Eintrag steht eine
kleine Rechnung, wie sie Landwirte und Berater nutzen:

| Zeile | Inhalt | Herkunft |
| --- | --- | --- |
| **Leistung** | Liter der Ernte × Preis (D2) | gemessen / eingetragen / geschätzt |
| − Direktkosten | Saatgut, Dünger, Kalk, Pflanzenschutz (D1) | gemessen / geschätzt / eingetragen |
| = **Direktkostenfreie Leistung** | | |
| − Lohnunternehmer | `CONTRACTOR_FEE` der Aufträge auf diesem Feld (R31-A1) | gemessen |
| = **Deckungsbeitrag** | | |
| + Prämien | Flächenprämie und Fruchtfolgeprämie dieses Feldes (R31-B1, R2-E2) | gemessen |
| − Pacht | `LEASE_PAYMENT` des Feldes in der Saison (nur gepachtete Felder) | gemessen |
| − Gemeinkosten (Schalter, D3) | Diesel, Reparatur, Helfer, Leasing, Gehälter der Maschinenführer, nach Fläche verteilt | verteilt |
| = **Feldergebnis** | | |

Jede Zeile steht in € und in €/ha (Fläche des Eintrags, R33-F1). Die laufende Saison zeigt die bisherigen Kosten ohne
Leistung.

**Stand 13.10.2026: umgesetzt** (Mod: Hooks auf Sämaschine und Spritze, `RPSimInputCounter`, Einkaufspreise;
Backend: `field/FieldMarginService`, `api/FieldMarginController`, Migration V45; Frontend: Karte `margin-card` unter
*Bank → Betriebsergebnis*, Abschnitt im Hofbericht). Abweichungen und Grenzen (Umsetzungshinweise in `QUESTIONS.md`):
- Die Tabelle nennt die Kultur als Text, nicht als Farbe; das Diagramm färbt nach Vorzeichen (grün / rot).
- In der Oberfläche werden Betrag und Preis (je 1000 l) korrigiert, eigene Zeilen mit Text und Betrag eingetragen.
  Liter lassen sich über die API korrigieren (`POST /api/field-margin/items/{id}`), noch nicht in der Karte.
- Der Link zwischen Rechnung und Feldbuch-Eintrag fehlt noch (die App „Feldbuch“ hat keine Sprungadresse je Eintrag).
- Die laufende Saison zeigt die bisherigen Kosten je ha; die erwartete Leistung kommt mit dem Anbauplaner (A3).
- Verbräuche gehen immer in die laufende Saison, die nie gesperrt ist. Zurückgehalten werden nur Prämien und
  Gemeinkosten, die für ein abgeschlossenes Jahr gebucht werden.

**Entscheidungen 13.10.2026 (`QUESTIONS.md`):**
- Erntepreis: **erzielter Ø-Preis** der Sorte von der Ernte bis zum Ende des folgenden FS25-Jahres; ohne Verkauf der
  beste Preis am Erntetag, ohne Verkaufsstelle der Verrechnungswert (D2).
- Eigener Wirtschaftsdünger (Gülle, Mist, Gärrest): **0 €** in einer eigenen Zeile „Wirtschaftsdünger“, Liter
  sichtbar (D1).
- Stroh zählt **nicht** automatisch als Leistung; der Spieler kann es selbst eintragen (D2, D4).
- Flächen- und Fruchtfolgeprämie: **eigene Zeile nach dem Deckungsbeitrag** (D1).
- Gemeinkosten: **Schalter, Standard an** (D3).
- Ort: **Karte „Deckungsbeitrag“ in der App „Bank“**, mit demselben gespeicherten Zeitraum wie die Feldbuch-Auswertung
  (D5).

### R4-D1 Kosten je Feld erfassen

- [x] Das Backend liest `inputs[]` bei jedem Export. Die Differenz zum zuletzt gesehenen Zählerstand je Feld und Sorte
  bucht es in den **laufenden Eintrag** dieses Feldes. Das passt zur Saison-Regel aus R33-F1: Mit der Ernte endet der
  Eintrag. Kalk auf der Stoppel und die Saat danach zählen schon zur nächsten Kultur.
- [x] Bewertet wird mit `fillTypeCosts[].costPerLiter` **zum Zeitpunkt des Verbrauchs**. `helperLiters` werden mit
  `× 1.5` bewertet, weil das Spiel sie so bucht (Beleg Q1). Später geänderte Preise ändern alte Zeilen nicht.
- [x] Zuordnung zur Kostenart über die Sorte (`rpsim.formulas.field-margin.categories`): `SEEDS` und
  Pflanz-Ware → Saatgut, `FERTILIZER` / `LIQUIDFERTILIZER` → Dünger, `LIME` → Kalk, `HERBICIDE` → Pflanzenschutz,
  `LIQUIDMANURE` / `MANURE` / `DIGESTATE` → Wirtschaftsdünger (0 €, Liter sichtbar, Entscheidung 13.10.2026).
- [x] Sinkt ein Zähler (Spielstand neu geladen), zieht das Backend die Differenz wieder ab, höchstens bis `0`, und nie
  aus manuell korrigierten Zeilen (wie R33-F3).
- [x] **Schätzung ohne `inputs[]`** (älterer Mod oder 🟡 aus Q1), als „geschätzt“ markiert:
  - Saatgut: beim Start einer Kultur im Eintrag `seedUsagePerSqm × 10000 × ha × costPerLiter(SEEDS)`; ohne
    `seedUsagePerSqm` der Konfigwert `field-margin.fallback-seed-cost-per-ha` je Kultur,
  - Dünger und Kalk: je Haken im Feldbuch (1. Düngung, 2. Düngung, gekalkt) der Konfigwert je Hektar,
  - Pflanzenschutz: je Haken „Unkraut bekämpft“ der Konfigwert je Hektar.
- [x] Lohnunternehmer: Der Auftrag kennt sein Feld (R31-A1). `CONTRACTOR_FEE` zählt im Eintrag, der beim Erledigen
  läuft. Der Preis des Lohnunternehmers enthält das Material (R31-A1, „material included“). Ein Auftrag `SOW` oder
  `FERTILIZE` bucht deshalb **kein** Saatgut und keinen Dünger zusätzlich.
- [x] Pacht: Die Monatsbeträge `LEASE_PAYMENT` des Feldes (`LeaseService`, `ContractBillingService`) zählen in dem
  Eintrag, der im Monat der Abbuchung läuft.
- [x] Prämien: Die Flächenprämie eines Jahres (`DirectPaymentService`) wird je Feld mit der erklärten Fläche
  aufgeteilt, die Fruchtfolgeprämie (R2-E2) je Feld mit anderer Kultur als im Vorjahr. Beide gehen in den Eintrag mit
  dem Erntejahr der Zahlung.

**Beleg:** ✅ Q1 (Verbrauchs-Zähler, Preise). – für Lohnunternehmer, Pacht und Prämien (Backend-Daten seit V3 / V3.1).
🟡 siehe Q1.

### R4-D2 Leistung bewerten

- [x] Die Liter kommen aus dem Feldbuch (gemessen oder eingetragen, R33-F3). Ohne Liter fehlt die Leistung, und der
  Eintrag zählt in keinem Durchschnitt (wie R33-E2).
- [x] Der **Preis je Liter** (Entscheidung 13.10.2026):
  1. der **erzielte Ø-Preis** der Sorte aus dem Kontoauszug: alle Verkäufe mit `fillType` und `liters` (`bookings`,
     `SOLD_PRODUCTS`, `HARVEST_INCOME` und die Tool-Verkäufe `GOODS_SALE`) von der Ernte bis zum Ende des folgenden
     FS25-Jahres. Vorkontrakte und Großaufträge sind darin enthalten, weil sie an der Verkaufsstelle verkauft werden,
  2. ohne Verkauf: der **beste Preis** der Sorte an einer Verkaufsstelle am Tag der Ernte (Preisverlauf,
     `PriceQueryService`),
  3. ohne Verkaufsstelle (z. B. Silage, Gras, Häckselgut für die eigenen Tiere): der **Verrechnungswert** =
     `costPerLiter` × `field-margin.internal-price-share` (**0.8**).
- [x] Der Preis ist eine eigene Zeile mit Herkunft („erzielt“, „Marktpreis“, „Verrechnungswert“, „eingetragen“).
- [x] Solange noch Ware der Sorte verkauft werden kann (Zeitraum aus 1. offen), steht am Preis „vorläufig“.
- [x] **Stroh** aus der Getreideernte zählt nicht automatisch (es fällt als Schwad und wird nicht gezählt,
  `Combine.md`). Der Spieler kann es als eigene Leistungszeile eintragen (D4).

**Beleg:** – (Backend: Kontoauszug seit 06.10.2026, Preisverlauf seit V1). 🟡 Kontoauszug `fillType` / `liters`
(Testplan 26.2). **Fallback:** Regel 2.

### R4-D3 Gemeinkosten nach Fläche verteilen (Schalter)

- [x] Schalter **„Gemeinkosten verteilen“** auf der Karte, **Standard an** (Entscheidung 13.10.2026), je Spielstand
  gemerkt. An: Die Beträge der Geldarten aus
  `field-margin.overhead-money-types` (Vorschlag `PURCHASE_FUEL`, `VEHICLE_RUNNING_COSTS`, `VEHICLE_REPAIR`, `AI`,
  `LEASING_COSTS`, `RPSIM_SALARY_PAYMENT` der Maschinenführer) eines Monats werden auf alle Felder mit laufendem
  Eintrag in diesem Monat nach Hektar verteilt.
- [x] Geldarten, die schon einem Feld zugeordnet sind (Helper-Saatgut und -Dünger aus D1), fallen nicht noch einmal in
  die Gemeinkosten. Ohne `inputs[]` gilt das auch für `PURCHASE_SEEDS` / `PURCHASE_FERTILIZER` nicht, sie bleiben dann
  bei der Schätzung aus D1.
- [x] Verteilte Zeilen sind als „verteilt“ markiert und lassen sich nicht je Feld korrigieren, nur über den Schalter
  ausblenden.

**Beleg:** – (Buchungsjournal `finances.byType` seit R2-B1).

### R4-D4 Korrigieren und ergänzen

- [x] Jede Zeile mit Herkunft „gemessen“ oder „geschätzt“ ist änderbar: Liter, Preis oder Betrag. Die Korrektur
  gewinnt und ist als „eingetragen“ markiert. Ein Knopf ↺ gibt sie an die Erfassung zurück (wie R33-F4).
- [x] **„Kosten hinzufügen“**: eigene Zeile mit Kostenart (Auswahl), Text und Betrag, z. B. zugekaufter Mist,
  Ballen-Wickelfolie, Hagelschaden-Erstattung als negative Kosten.
- [x] **„Leistung hinzufügen“**: eigene Zeile mit Text, Litern und Preis, z. B. gepresstes Stroh.
- [x] Ein abgeschlossenes Erntejahr (R33-F5) sperrt auch die Rechnung. Späte Verbräuche erscheinen als Hinweis am
  Eintrag (`field_book_notice`), wie späte Liter.

**Beleg:** – (Backend und Oberfläche).

### R4-D5 Karte „Deckungsbeitrag“ in der Bank

- [x] Neue Karte in der App „Bank“ (`frontend/src/app/features/bank/`, neben Hofbuchhaltung und Hofbericht),
  Erst-Hinweis in `app-hints.ts`. Der Zeitraum ist **derselbe gespeicherte Zeitraum** wie in der Feldbuch-Auswertung
  (R33-E1, je Spielstand). Eine Änderung an einer der beiden Stellen gilt für beide (Entscheidung 13.10.2026).
- [ ] Von der Rechnung eines Eintrags führt ein Link zum Eintrag im Feldbuch und umgekehrt.
- [x] **Tabelle Feld × Erntejahr** mit Deckungsbeitrag in €/ha je Zelle, Kultur als Farbe (Palette der Auswertung).
  Klick auf eine Zelle öffnet die Rechnung des Eintrags mit allen Zeilen.
- [x] **Rangliste** der Felder im Zeitraum: Ø Deckungsbeitrag je ha mit `n`, bestes und schwächstes Feld markiert.
- [x] **Je Kultur und Produkt:** Ø Leistung, Ø Direktkosten und Ø Deckungsbeitrag je ha über alle Felder, mit `n`.
- [x] Diagramm: Balken Deckungsbeitrag €/ha je Feld für ein gewähltes Erntejahr, negative Werte nach unten.
  Inline-SVG wie die vorhandenen Diagramme, keine Diagramm-Bibliothek.
- [ ] Die **laufende Saison** steht je Feld oben: bisherige Kosten je ha und, mit dem Plan aus A, die erwartete
  Leistung.

**Beleg:** – (Oberfläche).

### R4-D6 Wirkung auf Hofbericht und Charaktere

- [x] Der Hofbericht zum Jahreswechsel (`FarmReportService`) bekommt den Abschnitt **„Deckungsbeitrag je Kultur“**
  (Fläche, Ø €/ha, Summe). Chronik und Bank-Jahresgespräch lesen ihn mit (wie R33-W1).
- [x] Hat ein Feld **zwei Erntejahre in Folge** einen negativen Deckungsbeitrag, schreibt die Genossenschaft einmal
  einen Hinweis (Feld, Kultur, Beträge vom Backend). Die KI formuliert nur den Text. Schalter
  `field-margin.advice-enabled`.

**Beleg:** – (Backend).

**Akzeptanz D:** Ein Weizenfeld wird gesät, zweimal gedüngt, gekalkt und geerntet. Der Eintrag zeigt Saatgut, Dünger
und Kalk mit den gezählten Litern und dem Einkaufspreis zum Zeitpunkt der Arbeit, die Leistung mit dem erzielten Preis
und den Deckungsbeitrag in € und €/ha. Ein Helfer mit „Helfer kauft Dünger“ erscheint mit Aufschlag `× 1.5`. Ein
Lohnunternehmer-Auftrag erscheint als eigene Zeile. Ohne den neuen Mod sind die Direktkosten als „geschätzt“
markiert. Ein korrigierter Preis bleibt. Die Gemeinkosten erscheinen beim ersten Öffnen als Zeile „verteilt“;
ausgeschaltet ändert sich nur das Feldergebnis. Die Karte steht in der App „Bank“ und zeigt denselben Zeitraum wie die
Feldbuch-Auswertung.

---

## F – Futterbilanz

**Idee:** Ein neuer Tab **„Futter“** in der App „Stall“. Je Tierart und Futtergruppe steht, wie viel Futter am Tag
gebraucht wird, wie viel da ist (Trog, Silo, Fahrsilo, eingetragene Ballen) und **wie viele Tage es reicht**. Der
Tierpfleger warnt rechtzeitig, nicht erst, wenn der Trog leer ist (das gibt es seit R2-A7).

**Stand 13.10.2026: umgesetzt** (Mod: Futterwerte je Stall, Hook auf `PlaceableHusbandryFood.removeFood`,
`RPSimFeedCounter`, `feedStock`, `animalFood`, Futterpreise; Backend: `feed/FeedBalanceService`,
`api/FeedBalanceController`, Migration V46, Aufgaben `FEED` und Kalender `FEED_EMPTY`; Frontend: Tab `futter` in der
App „Stall“, Kachel auf dem Startbildschirm). Abweichungen und Grenzen (Umsetzungshinweise in `QUESTIONS.md`):
- „Bis zur nächsten Ernte“ nutzt die Erntefenster der Kulturen aus `harvestablePeriods` (Zuordnung Sorte → Kulturen in
  `feed-balance.harvest-crops`); ohne die Fenster (älterer Mod) fehlen nächste Ernte und Fehlmenge.
  Ob die Kultur auf eigenen Feldern steht, prüft die Bilanz nicht; den Plan nutzt sie mit A.
- Eine Sorte, die mehrere Gruppen (auch verschiedener Tierarten) fressen, wird nach deren Bedarf geteilt. Eine
  Mischung im Silo zählt für die Gruppe, die sie nennt; die Zutaten einer Mischung kennt der Vertrag nicht.
- Eingetragener Vorrat sinkt, wenn ein Trog oder das Stroh im Stall steigt, ohne dass ein Silo derselben Sorte sinkt.
  Gekauftes Futter, das direkt in den Trog geht, zählt deshalb auch als Ballen; ohne Eintrag passiert nichts.
- Die Ballengrößen in `feed-balance.bale-sizes` sind Platzhalter (🟡 Testplan 30.12); Liter direkt gehen immer.
- Der Jahresbedarf vergleicht nur mit dem Vorrat; die geplante Ernte kommt mit A4.

**Entscheidungen 13.10.2026 (`QUESTIONS.md`):**
- Ort: **Tab „Futter“ in der App „Stall“** und eine **Kachel** auf dem Startbildschirm, solange eine Gruppe gelb oder
  rot ist.
- Tagesbedarf: **gemessen, Ø der letzten 3 Spieltage**, bei Änderung der Herde hochgerechnet; ohne Messung der Bedarf
  des Spiels × 24, „geschätzt“ (F1).
- Aufteilung auf Futtergruppen vor der ersten Messung: **nach `productionWeight`** (F1).
- Eingetragene Ballen: Der Vorrat **sinkt mit dem Verbrauch**, nach 10 Spieltagen ohne Änderung kommt die Erinnerung
  „bitte prüfen“ (F2).
- Warnschwellen **in Spielmonaten**: grün ab 3 Monaten, gelb darunter, rot unter 1 Monat. Es warnt der Tierpfleger
  (ohne Tierpfleger die Genossenschaft): Mail beim Wechsel auf Rot, danach **höchstens einmal je Spielmonat** und
  Gruppe (F3, F4).

### R4-F1 Bedarf je Stall und Futtergruppe

- [x] **Tagesbedarf je Stall** (Entscheidung 13.10.2026): der **gemessene** Verbrauch aus `feedConsumed[]` (Fallback:
  Sinken von `trough[]`), gemittelt über die letzten `feed-balance.measure-days` Spieltage (**3**). Ändert sich
  die Herde, wird er mit `foodLitersPerHour jetzt / foodLitersPerHour im Messzeitraum` hochgerechnet. Ohne Messung:
  `foodLitersPerHour × 24` (🟡 Q2), markiert „geschätzt“.
- [x] **Bedarf je Futtergruppe:** der gemessene Anteil jeder Sorte am Verbrauch, den Gruppen aus `animalFood[]`
  zugeordnet. Ohne Messung: anteilig nach `productionWeight` der Gruppen (Entscheidung 13.10.2026).
- [x] **Einstreu:** `strawLitersPerHour × 24` je Stall mit Einstreu (🟡 wie oben).
- [x] Ställe derselben Tierart greifen auf **denselben Vorrat** zu. Die Bilanz rechnet deshalb je Tierart, die
  Reichweite des Trogs aber je Stall.

**Beleg:** ✅ Q2. 🟡 Q2 (`timeAdjustment`, Verteilung auf Gruppen).

### R4-F2 Vorrat

- [x] **Automatisch:** `feedStock[]` (Silos, Erweiterungen, Fahrsilos) und `trough[]` (je Stall), dazu `straw` im
  Stall.
- [x] **Ballen und Paletten selbst eintragen:** Ballen sind im Spiel Objekte und kein Füllstand (seit V3
  ausgeklammert). Der Spieler trägt je Sorte die **Anzahl Ballen** und die Ballengröße ein (Auswahl aus
  `feed-balance.bale-sizes`, z. B. Rundballen 1,25 m, Quaderballen 2,4 m, je mit Litern) oder direkt Liter.
- [x] **Sonstiger Vorrat** in Litern (z. B. Lager des Futterroboters, 🟡 Q2).
- [x] Ein eingetragener Vorrat **sinkt mit dem Verbrauch**: Der Verbrauch einer Sorte zieht zuerst vom eingetragenen
  Vorrat ab, sobald der automatische Vorrat dieser Sorte nicht sinkt (die Tiere fressen dann aus Ballen, die der Spieler
  in den Trog gelegt hat). Am Eintrag steht „eingetragen vor x Tagen“, nach `feed-balance.manual-stale-days` (**10**
  Spieltage) als Erinnerung „bitte prüfen“ (Entscheidung 13.10.2026).

**Beleg:** ✅ Q2 (`feedStock`, `trough`, `straw`). – für eingetragenen Vorrat (Backend und Oberfläche).

### R4-F3 Reichweite

- [x] Je Tierart und Futtergruppe: **Reichweite in Tagen** = (Vorrat der Sorten der Gruppe + Trog) ÷ Tagesbedarf der
  Gruppe. Dazu in Monaten (`÷ calendar.daysPerPeriod`) und als Datum „leer am“ im Spielkalender.
- [x] Je Stall: Reichweite des **Trogs** allein („Trog leer in x Stunden“), damit der Spieler weiß, wann er nachfüllen
  muss.
- [x] **Bis zur nächsten Ernte:** Für Gruppen, deren Sorten von eigenen Feldern kommen (Gras, Silage, Heu, Stroh), steht
  die nächste mögliche Ernte aus `harvestablePeriods` der passenden Kulturen (Q1) und, mit A, aus dem Plan. Reicht der
  Vorrat bis dahin nicht, steht die **Fehlmenge** in Litern.
- [x] Ampel je Gruppe in **Spielmonaten** (Entscheidung 13.10.2026): grün ab `feed-balance.ok-months` (**3**), gelb
  darunter, rot unter `feed-balance.warn-months` (**1**). So passt sie zu jeder Einstellung „Tage je Periode“.
- [x] **Kachel** auf dem Startbildschirm „Futter reicht noch x Tage“ mit der knappsten Gruppe, solange eine Gruppe gelb
  oder rot ist; Klick öffnet den Tab „Futter“.
- [x] Kosten des Verbrauchs je Monat und Stall, bewertet mit `fillTypeCosts` (Zukaufpreis), als Zusatzzeile.

**Beleg:** – (Backend). ✅ Q1 (`harvestablePeriods`).

### R4-F4 Warnungen und Rollenspiel

- [x] Wechselt eine Gruppe auf **Rot** (unter `feed-balance.warn-months`), schreibt der **Tierpfleger** (ohne
  Tierpfleger die Genossenschaft) eine Mail mit Gruppe, Reichweite und Fehlmenge. Bleibt sie rot, kommt höchstens
  **einmal je Spielmonat** und Gruppe eine weitere Mail (Entscheidung 13.10.2026). Das ergänzt die Trog-Warnung aus
  R2-A7 und ersetzt sie nicht.
- [x] In **Aufgaben** erscheint „Futter: Silage reicht noch 6 Tage“ mit Frist = „leer am“. Der **Kalender** zeigt das
  Datum.
- [x] Lässt sich die Sorte bei Nachbarn kaufen (Nachbarhandel R3-H3, nur Sorten mit eigenem Silo), führt ein Knopf
  **„Beim Nachbarn anfragen“** direkt in die App „Handel“.
- [x] Bleibt eine Gruppe rot, bis der Trog leer ist, greift wie bisher die Tierschutz-Regel des Amts (R2-E2). V4 ändert
  daran nichts.

**Beleg:** – (Backend: Mails, Aufgaben, Kalender seit V1–V3).

### R4-F5 Jahresbedarf

- [x] Je Tierart und Gruppe: **Jahresbedarf** = Tagesbedarf × 12 × `daysPerPeriod`, verglichen mit dem Vorrat und, mit
  A, der geplanten Ernte (A4). Ergebnis „gedeckt zu x %“ und Fehlmenge oder Überschuss in Litern.
- [x] Diese Zahl nutzt der Anbauplaner, um Futterflächen vorzuschlagen (A4).

**Beleg:** – (Backend).

**Akzeptanz F:** Ein Milchviehstall mit 40 Kühen zeigt nach drei Spieltagen den gemessenen Tagesbedarf je Gruppe. Die
Silage im Fahrsilo und das Heu im Silo erscheinen automatisch, 20 eingetragene Rundballen Stroh zählen mit. Die
Reichweite steht in Tagen und Monaten mit Datum. Fällt die Silage unter einen Spielmonat, wird sie rot, der
Tierpfleger schreibt einmal, in Aufgaben steht die Frist, und auf dem Startbildschirm erscheint die Kachel. Im
nächsten Spielmonat kommt höchstens eine weitere Mail. Kommen zehn Kühe dazu, sinkt die Reichweite sofort. Ohne den
neuen Mod zeigt der Tab nur den Hinweis „Mod aktualisieren“.

---

## A – Anbauplaner

**Idee:** Ein neuer Tab **„Anbauplanung“** in der App „Feldbuch“. Je Feld wählt der Spieler die Kultur für die
**nächste Saison**. Das Tool prüft den Plan, rechnet Ertrag, Kosten und Deckungsbeitrag hoch und erinnert zur Saatzeit.

**Stand 14.10.2026: umgesetzt** (Mod: Saatbedarf, Grundertrag und Saat- / Erntefenster je Kultur; Backend:
`planning/CropPlanService`, `api/CropPlanController`, Migration V47, Aufgaben `SOW`, Kalender `SOW_WINDOW_START` /
`SOW_WINDOW_END`, Zeilen „CROP_PLAN“ in der Liquiditätsplanung; Frontend: Tab `anbauplanung` im Feldbuch).
Abweichungen und Grenzen (Umsetzungshinweise in `QUESTIONS.md`):
- „Lohnunternehmer beauftragen“ und „Vorkontrakt abschließen“ öffnen die Flurkarte bzw. den Tab der Agrarbörse ohne
  vorbelegtes Feld, Kultur oder Sorte – diese Seiten nehmen solche Parameter noch nicht an.
- Die Lagerplatz-Prüfung kennt nur Sorten, die ein eigenes Silo aufnimmt (Fahrsilos meldet der Mod dort nicht), und
  rechnet offene Vorkontrakte und Großaufträge als verkauft, ohne den Liefermonat zu prüfen.
- Die Fruchtfolge-Prüfung vergleicht mit den Kulturen im Feldbuch; ob das Amt eine Wiederholung schon gezählt hat,
  liest sie nicht.
- Häckselgut zählt für die Futterdeckung als Silage (`crop-planning.feed-equivalents`).
- Kosten eines Saatfensters, das jetzt läuft, stehen im laufenden Monat, den die Liquiditätsplanung nicht zeigt.

**Entscheidungen 13.10.2026 (`QUESTIONS.md`):**
- Der Plan gilt für die **nächste Saison je Feld**; ist im laufenden Eintrag noch nichts gesät, für diesen. Je Feld
  genau ein offener Plan (A1).
- Ertrag ohne Feldbuch-Werte: **Grundertrag × 1,0** (ohne Düngung und Kalk) (A3).
- Erinnerungsmail der Genossenschaft zur Saatzeit: **Schalter, Standard an**; die Aufgabe kommt immer (A5).
- **„Plan besprechen“** einmal je Planjahr, Antwort am nächsten Spieltag (A6).

### R4-A1 Plan anlegen

- [x] Der Plan gilt je Feld (eigen und gepachtet) für die **nächste Saison**: die Saison nach dem laufenden
  Feldbuch-Eintrag. Ist im laufenden Eintrag noch keine Kultur gesät, gilt der Plan für ihn (Entscheidung 13.10.2026).
- [x] Je Feld: **Kultur** (Dropdown aller Kulturen der Karte, `fruitTypes[]`, plus „Brache“), **Ernteprodukt**
  (Standardprodukt oder `products[]`, z. B. Mais als Körner oder Häckselgut), optional **Saatmonat** (nur Perioden aus
  `plantablePeriods`) und eine Notiz.
- [x] Knöpfe **„Wie Vorjahr“** (Kultur des letzten Eintrags) und **„Fruchtfolge vorschlagen“**: eine andere Kultur als
  in den letzten `crop-planning.rotation-years` Jahren, bevorzugt die Kultur mit dem besten Ø Deckungsbeitrag je ha
  aus D (ohne D: höchster Grundertrag × Preis).
- [x] Verkaufte, verpachtete und zurückgegebene Felder fallen aus dem Plan. Ein neues Feld kommt ohne Plan dazu.

**Beleg:** – (Backend und Oberfläche). ✅ Q1 (`plantablePeriods`).

### R4-A2 Prüfungen

Prüfungen sind **Hinweise**, keine Sperren.

| Prüfung | Regel | Quelle |
| --- | --- | --- |
| Fruchtfolge | Dieselbe Kultur wie die Hauptkultur des laufenden bzw. letzten Eintrags → „Fruchtfolgeprämie entfällt für x ha (≈ y €)“; bei der zweiten Wiederholung der Hinweis auf die Kürzung der ganzen Prämie (`rotation-cut-share`) | R2-E2, R31-B1 |
| Saatfenster | Der gewählte Saatmonat liegt nicht in `plantablePeriods` → Hinweis. Endet das Erntefenster der laufenden Kultur (`harvestablePeriods`) nach dem letzten Saatmonat der Planung im selben Jahr → „zu spät für dieses Jahr“ | Q1 |
| Lagerplatz | Erwartete Erntemenge je Sorte (A3) größer als die freie Kapazität der eigenen Silos (`tradeStorage.freeCapacity`) plus erwarteter Verkauf vor der Ernte → „Lagerplatz fehlt: x l“ | R3-Q1 |
| Düngeverordnung | Kulturen mit Saat im Herbst: Hinweis auf die Sperrfrist für Gülle und Mist (`closed-periods`) | R31-B3 |
| Bodenregeln | Kalk oder Unkraut im Spielstand an (`fieldRules`) und im letzten Eintrag nicht erledigt → Hinweis „vor der Saat kalken“ | R2-C1, R33-F2 |

- [x] Ohne `plantablePeriods` (älterer Mod) entfällt die Saatfenster-Prüfung, die Auswahl zeigt alle Monate.

**Beleg:** ✅ Q1 (`getIsPlantableInPeriod`, `getIsHarvestableInPeriod`). – für die übrigen Prüfungen.

### R4-A3 Prognose je Feld

- [x] **Erwarteter Ertrag** l/ha, in dieser Reihenfolge (Herkunft wird angezeigt):
  1. Ø l/ha dieses Feldes mit dieser Kultur und diesem Produkt aus der Feldbuch-Auswertung (R33-E2, Zeitraum
     `field-book.default-years`),
  2. Ø l/ha der Kultur über alle Felder,
  3. Grundertrag `litersPerSqm × 10000 × crop-planning.base-yield-factor` (**1.0**, der Grundertrag ohne
     Düngung und Kalk, wie R33-W1 ihn beschreibt).
- [x] **Erwarteter Preis:** Preis eines offenen Vorkontrakts für diese Sorte im Erntejahr (R3-M2), sonst der beste
  aktuelle Preis an einer Verkaufsstelle, sonst der Verrechnungswert aus D2.
- [x] **Erwartete Direktkosten:** Ø Direktkosten je ha der Kultur aus D. Ohne D-Werte: Saatgut aus `seedUsagePerSqm ×
  10000 × costPerLiter(SEEDS)`, der Rest „unbekannt“.
- [x] **Erwarteter Deckungsbeitrag** je Feld und als Summe des Plans. Ein Vergleich „Plan jetzt“ gegen „wie Vorjahr“
  zeigt, was der Wechsel bringt.

**Beleg:** – (Backend). ✅ Q1 (`litersPerSqm`, `seedUsagePerSqm`, `fillTypeCosts`).

### R4-A4 Bedarf und Absicherung

- [x] **Saatgut:** Bedarf je Sorte in Litern und € (`seedUsagePerSqm × 10000 × ha`). Saatgut liegt im Spiel in
  Paletten und Big Bags (Objekte); einen Bestand zeigt das Tool deshalb nicht.
- [x] **Vermarktung:** erwartete Erntemenge je Sorte gegen offene Vorkontrakte und Großaufträge mit Liefermonat im
  Erntejahr → „x % vorab verkauft“. Ein Knopf führt zum Vorkontrakt in der Agrarbörse (R3-M2) mit vorbelegter Sorte.
- [x] **Futter:** erwartete Ernte der Futtersorten (Gras, Silage bzw. Häckselgut, Heu, Stroh) gegen den Jahresbedarf aus
  F5 → „Futter fürs Jahr gedeckt zu x %“. Fehlt Futter, schlägt der Planer die Fläche vor, die mit dem erwarteten
  Ertrag die Lücke schließt.
- [x] **Liquidität:** Die Saatgut- und erwarteten Direktkosten erscheinen als geplante Ausgaben in der
  Liquiditätsplanung (R3-K2) im Saatmonat, als „geplant“ markiert.

**Beleg:** – (Backend). ✅ Q1.

### R4-A5 Aufgaben und Kalender

- [x] Zu Beginn des Saatfensters (erste Periode aus `plantablePeriods`, bzw. der gewählte Saatmonat) erscheint in
  **Aufgaben** „Feld 12: Weizen säen“ mit Frist = Ende des Fensters. Der **Kalender** zeigt die Fenster.
- [x] Am Plan eines Feldes führt **„Lohnunternehmer beauftragen“** zur Karte des Lohnunternehmers (R31-A1) mit Arbeit
  `SOW` und vorbelegter Kultur. Der Auftrag folgt den Regeln von R31-A1 (Feld muss leer sein).
- [x] Schalter **„Erinnerung zur Saatzeit“**, **Standard an** (Entscheidung 13.10.2026): Die Genossenschaft erinnert
  per Mail an Felder, deren Saatfenster in dieser Periode endet und die noch nicht gesät sind.

**Beleg:** – (Backend: Aufgaben, Kalender, Lohnunternehmer seit V1–V3.1).

### R4-A6 Plan und Wirklichkeit

- [x] Beginnt auf einem Feld eine neue Saison mit einer **anderen Kultur** als geplant (R33-F1), wird der Plan als
  „abweichend“ markiert. Keine Strafe, kein Vertrauensverlust.
- [x] Endet die Saison, steht im Tab die Zeile **Plan / Ist**: Kultur, l/ha und Deckungsbeitrag geplant und erreicht.
- [x] **„Plan besprechen“:** einmal je Planjahr (Entscheidung 13.10.2026) schickt der Spieler den Plan an die
  Genossenschaft. Die Antwort kommt am nächsten Spieltag und nennt die Ergebnisse der Prüfungen (A2) und die Deckung
  (A4), alles vom Backend gerechnet. Die KI formuliert nur den Text. Ohne KI gibt es den Text aus
  `fallback-templates`.

**Beleg:** – (Backend).

**Akzeptanz A:** Für drei Felder wird die nächste Saison geplant. Weizen nach Weizen zeigt den Fruchtfolge-Hinweis mit
dem Betrag. Ein Saatmonat außerhalb des Fensters wird gemeldet. Die Prognose nennt für ein Feld mit Feldbuch-Werten den
Ø-Ertrag des Feldes, für ein neues Feld den Grundertrag, jeweils mit Herkunft. Der Plan zeigt den Saatgutbedarf, die
Vorab-Vermarktung und die Futterdeckung. Zu Beginn des Saatfensters steht die Aufgabe in „Aufgaben“. Wird ein Feld mit
einer anderen Kultur gesät, ist der Plan als „abweichend“ markiert, und nach der Ernte steht Plan / Ist.

---

## Offene Punkte (Vorschläge zur Bestätigung)

Alle Punkte hat der Projektinhaber am 13.10.2026 entschieden (`QUESTIONS.md`). Die Tabelle bleibt als Nachweis, was
vorgeschlagen und was entschieden wurde.

| Punkt | Frage | Vorschlag |
| --- | --- | --- |
| Q1 ✅ 13.10.2026 wie vorgeschlagen | Wie kommen die Verbräuche ins Backend? | Kumulativer Zähler je Feld und Sorte im Mod-Spielstand, wie `harvests[]` (R33-Q1). Das Backend bucht die Differenz und zieht sie beim Zurückspulen wieder ab. |
| D1 ✅ 13.10.2026 wie vorgeschlagen | Was kostet eigener Wirtschaftsdünger (Gülle, Mist, Gärrest aus dem eigenen Stall)? | `0 €` mit eigener Zeile „Wirtschaftsdünger“ (Liter sichtbar). Alternative: Verrechnungswert aus `costPerLiter`. |
| D2 ✅ 13.10.2026 wie vorgeschlagen | Welcher Preis bewertet die Ernte? | Erzielter Ø-Preis der Sorte von der Ernte bis zum Ende des folgenden FS25-Jahres. Ohne Verkauf der beste Preis am Erntetag, ohne Verkaufsstelle der Verrechnungswert (`costPerLiter × 0.8`). |
| D2 ✅ 13.10.2026 wie vorgeschlagen | Zählt Stroh aus der Ernte als Leistung? | Nein, solange es nicht gezählt wird (Stroh fällt als Schwad, nicht in den Tank, `Combine:addCutterArea`). Der Spieler kann es als eigene Zeile eintragen. |
| D3 ✅ 13.10.2026: Standard **an** | Gemeinkosten verteilen? | Schalter, Standard **aus**. An: Diesel, Reparatur, Helfer, Leasing und Gehälter der Maschinenführer je Monat nach Hektar auf alle Felder mit laufender Saison. |
| D1 ✅ 13.10.2026 wie vorgeschlagen | Gehören Prämien in den Deckungsbeitrag? | Ja, als eigene Zeile **nach** dem Deckungsbeitrag (DB ohne und mit Prämie sichtbar). |
| D5 ✅ 13.10.2026: Karte in der App „Bank“, Zeitraum wie die Feldbuch-Auswertung | Wo steht der Deckungsbeitrag? | Dritter Tab „Deckungsbeitrag“ in der App „Feldbuch“, gleicher Zeitraum wie die Auswertung. |
| F1 ✅ 13.10.2026 wie vorgeschlagen | Woher kommt der Tagesbedarf? | Gemessener Verbrauch der letzten 3 Spieltage, bei Änderung der Herde mit `foodLitersPerHour` hochgerechnet. Ohne Messung `foodLitersPerHour × 24` als „geschätzt“. |
| F1 ✅ 13.10.2026 wie vorgeschlagen | Wie verteilt sich der Bedarf auf die Futtergruppen, solange nichts gemessen ist? | Nach dem gemessenen Anteil je Sorte, sobald er da ist. Vorher anteilig nach `productionWeight` der Gruppen (die Gewichte ergeben zusammen 1). |
| F2 ✅ 13.10.2026 wie vorgeschlagen | Ballen eintragen: sinkt der Wert von selbst? | Ja, der Verbrauch einer Sorte, die im automatischen Vorrat nicht sinkt, zieht vom eingetragenen Vorrat ab. Nach 10 Spieltagen ohne Änderung erinnert der Eintrag an eine Prüfung. |
| F2 ✅ 13.10.2026 wie vorgeschlagen | Ballengrößen | Konfigliste mit Litern je Ballenart (Platzhalter, im Spiel zu messen). Der Spieler kann immer auch Liter eintragen. |
| F3 ✅ 13.10.2026: in Spielmonaten, grün ab 3, rot unter 1 | Warnschwellen | `ok-days` 30, `warn-days` 10 Spieltage; Ampel je Gruppe. |
| F4 ✅ 13.10.2026: Tierpfleger; Mail beim Wechsel auf Rot, dann höchstens einmal je Spielmonat und Gruppe | Wer warnt? | Der Tierpfleger, ohne Tierpfleger die Genossenschaft (wie die Gülle-Warnung, R31-B3). Höchstens einmal je 5 Spieltage und Gruppe. |
| F ✅ 13.10.2026 wie vorgeschlagen | Wo steht die Futterbilanz? | Neuer Tab „Futter“ in der App „Stall“, dazu eine Kachel „Futter reicht noch x Tage“ auf dem Startbildschirm, wenn eine Gruppe gelb oder rot ist. |
| A1 ✅ 13.10.2026 wie vorgeschlagen | Für welche Saison gilt der Plan? | Die Saison nach dem laufenden Eintrag; ist im laufenden Eintrag noch nichts gesät, dieser. Je Feld genau ein offener Plan. |
| A3 ✅ 13.10.2026 wie vorgeschlagen | Ertrag ohne Feldbuch-Werte | Grundertrag × 1.0 (ohne Düngung und Kalk). Alternative: × typischer Ertragsfaktor mit voller Düngung (Konfigwert). |
| A5 ✅ 13.10.2026: Standard **an** | Erinnerung zur Saatzeit per Mail | Schalter, Standard aus. Die Aufgabe in „Aufgaben“ kommt immer. |
| A6 ✅ 13.10.2026 wie vorgeschlagen | „Plan besprechen“ | Einmal je Planjahr, Antwort der Genossenschaft am nächsten Spieltag. |

---

## Bewusst nicht aufgenommen

| Idee | Warum nicht |
| --- | --- |
| Ballen, Paletten und Big Bags automatisch zählen (Heu, Stroh, Silage, Saatgut, Dünger) | Ballen und Paletten sind Objekte und kein Füllstand. Objektlager sind seit V3 ausgeklammert (`PlaceableObjectStorage` ohne geprüften Zugriff). Ersatz: Vorrat selbst eintragen (F2). |
| Diesel, Verschleiß und Arbeitszeit genau je Feld | Verbrauch und Schaden laufen am Fahrzeug (`Motorized`, `Wearable`), nicht am Flurstück. Ein Helfer-Job ist nicht sicher einem Feld zugeordnet. Ersatz: nach Fläche verteilen (D3, Schalter). |
| Das Tool sät oder düngt nach Plan | Säen kann das Tool nur über den Lohnunternehmer (R31-A1). Der Plan bleibt eine Hilfe (A5). |
| Rationsberechnung nach Nährstoffen (Energie, Eiweiß) | Das Spiel kennt keine Nährstoffe, nur Futtergruppen mit `productionWeight` (`getFoodInfos`). |
| Mischration (TMR) durch das Tool mischen | Keine geprüfte API. Der Futtermischwagen bleibt Sache des Spielers. |
| Wasser in der Futterbilanz | Wasser ist eine Bedingung des Stalls (`conditions`, R2-A7) und wird dort schon gewarnt. |
| Teilflächen (halb gedüngt, halb gesät) | Wie V3.3: ein Feldzustand je Feld. Der Verbrauchs-Zähler bucht nach dem Flurstück, auf dem die Maschine steht. |
| Precision-Farming-DLC (`ExtendedSprayer`, variable Ausbringmenge, Ertragskarte) | Gehört zu einem DLC, nicht zum Grundspiel. Der Zähler aus Q1 braucht es nicht. |
| Deckungsbeitrag je Stall (Milch, Fleisch, Futterkosten je Liter Milch) | Nicht Teil der Auswahl vom 13.10.2026. F3 zeigt die Futterkosten je Stall, die volle Rechnung kann eine spätere Roadmap ergänzen. |

---

## Quellen

- FS25-Quellcode-Dump (`dataS`): <https://github.com/Dukefarming/FS25-lua-scripting>. Genutzt wurden aus
  `animals/husbandry/placeables/`:
  - `PlaceableHusbandryFood.lua` (`onHusbandryAnimalsUpdate`, `litersPerHour`, `getFoodLitersPerHour`, `fillLevels`,
    `getFoodCapacity`, `getTotalFood`, `updateFeeding`, `removeFood`, `getFoodInfos`, `onPostLoad` mit
    `getMixturesByAnimalTypeIndex`)
  - `PlaceableHusbandry.lua` (`onHourChanged` → `updateFeeding`)
  - `PlaceableHusbandryStraw.lua` (`inputLitersPerHour`, `updateOutput`, `removeHusbandryFillLevel` für `STRAW`)
  - `PlaceableHusbandryWater.lua` (Wasser je Stunde, nicht genutzt)
  - `PlaceableHusbandryFeedingRobot.lua` (`updateFeeding`, `createFoodMixture`)
- FS25 Community LUADOC: <https://github.com/umbraprior/FS25-Community-LUADOC> (Stand 13.10.2026). Genutzt wurden:
  - `script/Specializations/SowingMachine.md` (`onEndWorkAreaProcessing`: Saatgutverbrauch, Helper-Kauf,
    `PURCHASE_SEEDS`; `getIsPlantableInPeriod` beim Säen)
  - `script/Specializations/Sprayer.md` (`onStartWorkAreaProcessing`, `onEndWorkAreaProcessing`: Verbrauch, Sorte,
    Helper-Kauf, `PURCHASE_FERTILIZER`)
  - `script/Specializations/FertilizingSowingMachine.md` (Düngung beim Säen über `spec_sprayer`)
  - `script/Specializations/Motorized.md`, `TreePlanter.md` (Aufrufe von `economyManager:getCostPerLiter`)
  - `script/Fruits/FruitTypeDesc.md` (`seedUsagePerSqm`, `getIsPlantableInPeriod`, `getIsHarvestableInPeriod`)
  - `script/FillTypes/FillTypeDesc.md` (`pricePerLiter`)
  - `script/Economy/FarmlandManager.md` (`getFarmlandIdAtWorldPosition`)
  - `script/Specializations/Combine.md` (Stroh als Schwad, nicht im Tank)
- Bestehende Projekt-Doku und Code: [`ROADMAP_V2.md`](ROADMAP_V2.md) (B1, C1, E2), [`ROADMAP_V3.md`](ROADMAP_V3.md) (H3,
  K2, K3, M2), [`ROADMAP_V3.1.md`](ROADMAP_V3.1.md) (A1, B1, B3), [`ROADMAP_V3.2.md`](ROADMAP_V3.2.md) (G),
  [`ROADMAP_V3.3.md`](ROADMAP_V3.3.md) (Q1, F, E, W), `docs/dev/bridge-protocol.md` (`finances`, `bookings`,
  `husbandries`, `fields`, `harvests`, `fruitTypes`, `assets.storage`, `tradeStorage`),
  `docs/dev/offene-technische-punkte.md` (Nr. 8), `mod/FS25_RPSim/src/RPSim.lua` (Hooks des Ernte-Zählers),
  `backend/.../field/FieldBookService.java`, `backend/.../field/FieldBookEvaluationService.java`,
  `backend/.../finance/BookingStatementService.java`, `backend/.../finance/FarmReportService.java`,
  `backend/.../farmwork/ContractorWorkService.java`, `backend/.../contract/LeaseService.java`,
  `backend/.../contract/LivestockService.java`, `backend/.../authority/DirectPaymentService.java`,
  `backend/.../authority/FertilizerRulesService.java`, `backend/.../market/ForwardContractService.java`,
  `frontend/src/app/layout/apps.ts`, `app-tabs.ts`.
