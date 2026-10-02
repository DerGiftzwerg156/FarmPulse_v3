# FarmPulse – Roadmap V3.1

Ergänzung zu [Roadmap V3](ROADMAP_V3.md), Stand 30.09.2026. Ziel: Der Hof-Alltag wird vollständiger. Lohnunternehmer
und Nachbarn packen im Spiel mit an, Behörden und Förderprogramme verlangen Anträge mit Fristen, und das Dorf hat eine
Zeitung, einen Gruppenchat und einen Stammtisch. Dazu kommen Winterdienst, Viehhandel, Dieselklau und eine Hofkarte.

Alle Ideen hat der Projektinhaber am 30.09.2026 ausgewählt. Ausgenommen sind die Anrufe mit Sprachausgabe (siehe
[Bewusst nicht aufgenommen](#bewusst-nicht-aufgenommen)). Wie in V2 und V3 enthält die Datei **nur Punkte, die mit der
FS25-Schnittstelle machbar sind**. Jeder Punkt mit Mod-Eingriff wurde gegen den FS25-Quellcode-Dump und die
Community-LUADOC geprüft (Quellen am Ende).

**Grundsätze (unverändert aus V1–V3)**

- Der Mod bleibt **Sensor und Aktuator**. Alle Entscheidungen und Zahlen berechnet das Backend nach festen,
  konfigurierbaren Formeln (`rpsim.formulas.*`). Die KI formuliert nur, auch die Dorfzeitung und den Gruppenchat.
- Jeder Zugriff auf FS25 läuft in `RPSimGameAdapter` und ist mit `pcall` abgesichert. Fehlt eine API, fällt die Funktion
  still auf das bisherige Verhalten zurück.
- Neue Felder in den Bridge-Dateien sind **optional** (`schemaVersion` bleibt `1`).
- Beträge und Mengen gibt der Spieler nur über Formulare ein. Anweisungen, die Geld bewegen, laufen als Batch mit ihrer
  `MONEY_TRANSACTION`.
- Belastende Ereignisse (Kontrollen, Kürzungen, Diebstahl, Beschwerden) sind je Spielstand einzeln abschaltbar und im
  Weltmodus „idyllisch“ seltener bzw. milder. Offene Entscheidung dazu siehe `QUESTIONS.md` (R31-B / R31-D).

**Hängt ab von V3:** V3.1 nutzt Bausteine aus [Roadmap V3](ROADMAP_V3.md):
- `STORAGE_TRANSFER`, `tradeStorage` und `npcFields` (R3-Q1 / R3-H),
- `VEHICLE_SPAWN` und `VEHICLE_REMOVE` samt Shop-Katalog (R3-V),
- die zusätzliche Helfer-Rolle aus R3-P2 (Azubi).

Punkte, die davon abhängen, sind in der Übersicht markiert.

## Legende

| Zeichen | Bedeutung |
| --- | --- |
| ✅ **Belegt** | Die genutzte Funktion bzw. das Feld steht so im FS25-Code; Fundstelle ist angegeben. |
| 🟡 **Im Spiel prüfen** | Die API existiert, aber ein Detail lässt sich nur im laufenden Spiel klären. Zu jedem 🟡 steht ein Fallback. |
| – | Kein Mod-Eingriff: nur Backend und Oberfläche mit Daten, die es seit V1–V3 gibt. |

Punkt-IDs: `R31-<Bereich><Nummer>`, z. B. `R31-A1`. Alle Pfade sind relativ zum Repo-Root.

## Übersicht und empfohlene Reihenfolge

| Phase | Bereich | Inhalt | Mod-Eingriff | Hängt ab von |
| --- | --- | --- | --- | --- |
| 0 | [Q – Querschnitt](#q--querschnitt) | Neue Bridge-Felder und Anweisungen, Simulator, Testplan | ja | V3-Q |
| 1 | [D – Dorfleben](#d--dorfleben) | Dorfzeitung, Gruppenchat, Stammtisch, Nachtarbeit, Flurschaden, Ferien auf dem Hof, Genossenschaftsanteile, Dieselklau | D5, D8: ja | – |
| 2 | [A – Arbeit auf dem Hof](#a--arbeit-auf-dem-hof) | Lohnunternehmer, Leihmaschine, Viehhandel, Winterdienst, Erntehelfer | ja | A1 ← R3-H, A2 ← R3-V, A5 ← R3-P2 |
| 3 | [B – Behörden und Förderung](#b--behörden-und-förderung) | Sammelantrag, Investitionsförderung, Düngeverordnung, Tierseuche, Berufsgenossenschaft | B3: ja | – |
| 4 | [K – Hofkarte](#k--hofkarte) | Felder als Karte mit Kultur und Phase | ja | R3-H1 (Nachbarfelder) |

D zuerst: Dorfzeitung, Gruppenchat und Stammtisch brauchen keinen Mod-Eingriff und machen alle späteren Ereignisse
sichtbarer. A braucht die Bausteine aus V3. B ist überwiegend Backend-Arbeit.

---

## Q – Querschnitt

**Stand 02.10.2026: umgesetzt.** Q legt wie R2-Q und R3-Q nur den Vertrag an (Schemas, DTOs, Validator,
Normalisierung im Mod, Simulator, Doku). Das Auslesen im Mod folgt mit A4 (`snowHeight`, `category`), B3
(`sprayType`), D4 (`dayTimeMs`), D5 (`vehiclePositions`), D8 (`fuel`) und K1 (`fieldShapes`), bis dahin fehlen die
Werte. Die drei neuen Anweisungen quittiert der Mod bis A1/A3/D8 mit `FAILED` / `NOT_SUPPORTED`. Entscheidungen
(siehe `QUESTIONS.md`): `calendar.dayTimeMs` (D4) gehört mit in Q. `vehiclePositions[]` = `{ uniqueId, x, z,
farmlandId?, onCrop? }`, `fieldShapes` = `{ mapSize, fields: [{ farmlandId, name, points }] }` mit höchstens 64
Punkten je Feld (Mod-Schalter `fieldShapeMaxPoints`), `fuel` = `{ liters, capacity }`, `sprayType` mit `NONE`,
`category` in Großbuchstaben. `FIELD_WORK.work` ∈ `PLOW`, `CULTIVATE`, `LIME`, `SOW` (mit `fruitType`), `HARVEST`.
Fehlercodes je Anweisung und `result.liters` nach `VEHICLE_FUEL` wie im Bridge-Protokoll. Die Buchungstitel und
-klassen folgen dem Vorschlag. Der Simulator führt die neuen Anweisungen aus wie der spätere Mod. Spieler-Doku nur
im `CHANGELOG`; der Testplan-Abschnitt ist **21** (12 war schon belegt).

### R31-Q1 Bridge-Schema erweitern

- [x] `farm_facts.json`, neue optionale Felder:
  - `weather.snowHeight`: Schneehöhe der Welt (A4).
  - `fields[].sprayType`: Art der letzten Düngung (B3).
  - `assets.vehicles[].category`: Shop-Kategorie jedes eigenen Fahrzeugs (A4, D8).
  - `assets.vehicles[].fuel`: Dieselstand (D8).
  - `vehiclePositions[]`: Stichprobe der Fahrzeugpositionen (D5).
  - `calendar.dayTimeMs`: Tageszeit (D4, Entscheidung 02.10.2026).
- [x] `market_context.json`, neuer optionaler Block `fieldShapes` (K1): Umriss jedes Feldes und Kartengröße.
- [x] Neue Anweisungstypen:
  - `FIELD_WORK` (A1): Endzustand einer Feldarbeit setzen,
  - `ANIMAL_TRANSFER` (A3): Tiere ein- oder ausstallen,
  - `VEHICLE_FUEL` (D8): Diesel abziehen.

  Ein älterer Mod lehnt einen unbekannten Typ ab (`REJECTED` bei der Prüfung, ein Mod mit R31-Q ohne das Feature
  `FAILED` / `NOT_SUPPORTED`). Das Backend rät dann per Hinweis zum Update (`modOutdated`, „Mod aktualisieren“); das
  Stornieren des Vorgangs kommt mit dem jeweiligen Feature.
- [x] Neue `MoneyReason`-Werte:
  - `CONTRACTOR_FEE` (A1), `MACHINE_RENT` (A2), `LIVESTOCK_PURCHASE` / `LIVESTOCK_SALE` (A3),
  - `WINTER_SERVICE` (A4), `DIRECT_PAYMENT` (B1), `INVESTMENT_GRANT` (B2), `SOCIAL_INSURANCE` (B5),
  - `GUEST_INCOME` (D6), `COOP_SHARES` / `COOP_DIVIDEND` (D7).

  Dazu die Klassen in `rpsim.formulas.finance.categories`: Förderungen und Einnahmen operativ, Genossenschaftsanteile
  als Finanzierung.
- [x] `docs/dev/bridge-protocol.md` je Feld und Anweisung mit Quelle im FS25-Code.

### R31-Q2 Simulator, Tests, Konfiguration, Doku, Testplan

- [x] Bridge-Simulator:
  - Szenarien `winter-schnee`, `lohnunternehmer` und `viehhandel`,
  - Steuer-Endpunkte für Schnee, Fahrzeugpositionen und Diesel.
- [x] Mod-Tests je Adapter-Funktion mit gemockten FS25-Globals. In Q: Normalisierung der neuen Felder und Blöcke,
  Prüfung der neuen Anweisungen, `NOT_SUPPORTED`, `result` in der Quittung, Buchungstitel. Gilt weiter für jede
  Adapter-Funktion der Features.
- [x] Backend: Grenzwert-Tests je Formel, End-to-End-Test gegen den Simulator. Q bringt keine Formel;
  `BridgeValidatorTest`, `FailedInstructionTest` und `SimulatorScenariosEndToEndTest` prüfen die neuen Felder, den
  Hinweis und `result.liters`.
- [x] Neue Werte unter `rpsim.formulas.*` samt `docs/dev/configuration-reference.md`
  (`ConfigurationReferenceDocTest`). Q bringt nur die Klassen der neuen Buchungsgründe unter
  `rpsim.formulas.finance.categories`.
- [x] Spieler-Doku `docs/user-guide/funktionen.md`, `CHANGELOG.md`. Q ist für Spieler nicht sichtbar, daher nur der
  `CHANGELOG`-Eintrag.
- [x] `docs/dev/manual-test-plan.md`: Abschnitt **„21. Roadmap V3.1 im echten FS25“**, eine Zeile je 🟡.

---

## A – Arbeit auf dem Hof

### R31-A1 Lohnunternehmer bearbeitet dein Feld

**Idee:** Du beauftragst den Lohnunternehmer (`CONTRACTOR`) mit einer Arbeit auf deinem Feld. Am vereinbarten Spieltag
ist sie erledigt: Das Feld springt in den Endzustand, genau wie beim Abschluss eines Auftrags im Spiel. Die Ernte
landet in deinem Silo.

- [ ] Formular in der Flurkarte → eigenes Feld → **„Lohnunternehmer beauftragen“**. Arbeiten:
  - Pflügen, Grubbern, Kalken,
  - Säen (Fruchtsorte aus einer Liste),
  - Ernten (nur in Phase `HARVESTABLE`).

  Das Backend bietet nur an, was zum Feldzustand passt (Daten aus R2-C1).
- [ ] Preis je Hektar und Arbeit (Konfig), Termin in 1–3 Spieltagen, in der Erntezeit länger (Auslastung).
  Vertrauen verkürzt die Wartezeit leicht.
- [ ] Neue Anweisung `FIELD_WORK { farmlandId, work, fruitType? }` im Batch mit `MONEY_TRANSACTION` `CONTRACTOR_FEE`.
  Der Mod nimmt das eigene Feld und prüft, ob es der Spieler-Farm gehört und kein Auftrag darauf läuft. Dann setzt er
  den Endzustand wie `AbstractFieldMission:finishField`:
  `task = field:getFieldState():createFieldUpdateTask()` → Werte setzen → `task:setField(field)` →
  `g_fieldManager:addFieldUpdateTask(task)`.

  | Arbeit | Endzustand (Muster aus dem Spielcode) |
  | --- | --- |
  | Pflügen | `groundType = PLOWED`, Frucht entfernt, `plowLevel` voll (wie `PlowMission:getFieldFinishTask`) |
  | Grubbern | `groundType = CULTIVATED`, Frucht entfernt |
  | Kalken | `limeLevel` voll, `sprayType = LIME` |
  | Säen | `setFruit(fruitIndex, 1)`, `groundType = SOWN` |
  | Ernten | Frucht auf `fruitTypeDesc.cutState` (Stoppel) |
- [ ] **Ernte:** Die Menge berechnet das Backend: Fläche × `litersPerSqm` (aus R2-C1) × Ertragsfaktor. Der Faktor kommt
  aus der Konfig und berücksichtigt Düngung, Kalk, Pflug und Unkraut aus den Feldwerten, die Formel des Spiels wird
  nicht nachgebaut. Einlagern im selben Batch mit `STORAGE_TRANSFER IN` (R3-H3). Reicht die Silokapazität nicht, lehnt
  das Backend den Auftrag vorher ab („Wohin mit dem Weizen?“).
- [ ] Mail des Lohnunternehmers, Tagebucheintrag, Vertrauen. Die Kosten stehen im Journal als operative Ausgabe.
- [ ] Werte unter `rpsim.formulas.contractor-work.*`. Offene Entscheidung (`QUESTIONS.md`): ob Ernten dazugehört oder nur
  die Bodenbearbeitung.

**Beleg:**
- ✅ `Field/AbstractFieldMission.md` (LUADOC):
  - `finishField`: `task:setField(self.field)`, `g_fieldManager:addFieldUpdateTask(task)`,
  - `getFieldFinishTask`: `fieldState:createFieldUpdateTask()`.
- ✅ `Field/PlowMission.md`: `getFieldFinishTask` setzt `fruitTypeIndex = FruitType.UNKNOWN`,
  `groundType = FieldGroundType.PLOWED`.
- ✅ `field/FieldManager.lua` (Dump): `FieldUpdateTask` mit `setFruit`, `setGroundType`, `setSprayType`, `setSprayLevel`,
  `setLimeLevel`, `setPlowLevel`, `setWeedState`, `setStoneLevel`, `clearHeight`.
- ✅ `Fruits/FruitTypeDesc.md`: `cutState` aus der Frucht-Definition.

**🟡 Im Spiel prüfen:**
- Übernimmt `createFieldUpdateTask()` die geänderten Werte des Feldzustands (Muster aus `PlowMission`), oder müssen die
  Setter der Task genutzt werden? **Fallback:** Die Setter der Task (Dump) direkt aufrufen.
- Hinterlässt das Ernten per Zustandssprung Stroh auf dem Feld? **Fallback:** Stroh gehört nicht zur Leistung, es wird
  nur die Hauptfrucht eingelagert.

### R31-A2 Leih- und Vorführmaschine

**Idee:** Ein Nachbar leiht dir seinen Mähdrescher für die Ernte, oder die Landmaschinenwerkstatt stellt dir eine neue
Maschine zur Probe hin.

- [ ] Nachbar (`NEIGHBOR_FARMER`): „Maschine leihen“ auf der Kontaktseite. Die Auswahl kommt aus dem Shop-Katalog
  (R3-V1) nach Kategorien, die zum Nachbarbetrieb passen. Miete je Spieltag (Konfig), Vertrauen senkt sie.
- [ ] Werkstatt (`WORKSHOP`): Vorführung für 1–2 Spieltage kostenlos. Danach folgt ein Kaufangebot (Neupreis mit
  Rabatt, Kauf über R3-V2).
- [ ] Beginn: `VEHICLE_SPAWN` (R3-V2) mit `ageMonths`/`operatingHours` nach Konfig, Miete als `MACHINE_RENT` je
  Spieltag. Das Tool markiert das Fahrzeug als Leihgerät: Es zählt nicht zum Vermögen der Bank und nicht zur
  Abschreibung.
- [ ] Ende: `VEHICLE_REMOVE` (R3-V3). Ist das Fahrzeug in Benutzung (`VEHICLE_IN_USE`), kommt eine Erinnerung, ein neuer
  Versuch am nächsten Spieltag und je Tag ein Aufschlag. Schaden über dem Ausgangswert (`assets.vehicles[].condition`)
  kostet eine Entschädigung an den Nachbarn.

**Beleg:** ✅ wie R3-V2 / R3-V3 (`VehicleLoadingData`, `Vehicle:delete`). Keine neue Spiel-API.

### R31-A3 Viehhandel mit Nachbarn

**Idee:** Tiere direkt vom Nachbarn kaufen oder an ihn verkaufen, zum Beispiel Kälber, Ferkel oder Schafe.

- [ ] Angebote und Anfragen wie beim Warenhandel (R3-H2 bis H4). Jeder Nachbar hat einen Betriebstyp, und daraus
  entstehen Tierbestand und Bedarf als Backend-Formel. Preis je Tier = Wert des Spiels
  (`assets.animals[].estimatedValue / count`, aus `cluster:getSellPrice()`) × Spanne (Konfig) ± Vertrauen.
- [ ] Neue Anweisung `ANIMAL_TRANSFER { husbandryUniqueId, subType, count, age?, direction: "IN" | "OUT" }` im Batch
  mit `MONEY_TRANSACTION` (`LIVESTOCK_PURCHASE` / `LIVESTOCK_SALE`).
  - **IN:** Der Mod prüft freie Plätze (`getNumOfFreeAnimalSlots()`) und die Tierart des Stalls und fügt dann
    `addAnimals(subTypeIndex, count, age)` hinzu. Den Index liefert `g_currentMission.animalSystem:getSubTypeByName`.
  - **OUT:** Der Mod zieht die Tiere über `cluster:changeNumAnimals(-n)` aus den Gruppen dieses Untertyps ab, wie der
    Konsolenbefehl des Spiels.
  - Nicht genug Platz oder Tiere: `FAILED` mit `NO_ANIMAL_SPACE` / `NOT_ENOUGH_ANIMALS`.
- [ ] Der Viehhändler (`LIVESTOCK_TRADER`) bleibt für Geschäfte außerhalb des Dorfs. Die Nachbarn sind persönlicher,
  und es gibt Klatsch.
- [ ] Während einer Tierseuche (B4) ist der Handel mit der betroffenen Tierart gesperrt.
- [ ] Offene Entscheidung (`QUESTIONS.md`): Bieten Nachbarn von sich aus an, oder nur auf Anfrage?

**Beleg:**
- ✅ `Specializations/PlaceableHusbandryAnimals.md` (LUADOC):
  - `addAnimals(subTypeIndex, numAnimals, age)` baut die Gruppen über `animalSystem:createClusterFromSubTypeIndex`
    und `addCluster`,
  - `getNumOfFreeAnimalSlots`, `getClusters`,
  - der Konsolenbefehl zieht Tiere mit `cluster:changeNumAnimals(remainingAnimals)` ab.
- ✅ `animals/husbandry/placeables/PlaceableHusbandryAnimals.lua` (Dump): `cluster:changeNumAnimals(-1)` beim Reiten.
- ✅ `Specializations/Rideable.md`: `animalSystem:getSubTypeByName(name)`.

**🟡 Im Spiel prüfen:** Werden die neuen Tiere sofort angezeigt (`addPendingAddCluster` + `raiseActive`)? **Fallback:**
Die Anzeige folgt beim nächsten Update des Stalls; der Export zählt erst danach.

### R31-A4 Winterdienst für die Gemeinde

**Idee:** Viele Landwirte räumen im Winter für die Gemeinde Schnee. Das Amt (`AUTHORITY`) bietet einen
Winterdienst-Vertrag an: eine Grundpauschale je Wintermonat plus Geld je Schneetag.

- [ ] Voraussetzung: ein eigenes Fahrzeug aus einer Konfigliste von Shop-Kategorien (z. B. Traktoren mittel/groß).
  Dafür wird die Kategorie der eigenen Fahrzeuge exportiert, `assets.vehicles[].category` aus
  `g_storeManager:getItemByXMLFilename(configFileName).categoryName`. Diesen Weg nutzen schon die Schulungen.
- [ ] `weather.snowHeight` = `g_currentMission.snowSystem.height`. Ein Spieltag mit Schnee über der Schwelle (Konfig)
  zählt als Einsatztag, das Backend zählt wie bei den Regenstunden (R2-C2).
- [ ] Vertrag als `ContractKind` (neu `WINTER_SERVICE`), Zahlung monatlich als `WINTER_SERVICE`. Einsatztage kündigt das
  Amt mit einem Hinweis im Spiel an („Schnee! Winterdienst ab 5 Uhr“).
- [ ] Ob wirklich geräumt wurde, prüft das Tool **nicht** (siehe
  [Bewusst nicht aufgenommen](#bewusst-nicht-aufgenommen)). Das ist Rollenspiel, und die Bezahlung ist an den Vertrag
  gebunden.
- [ ] Ist der Spielstand ohne Schnee eingestellt oder fällt keiner, gibt es nur die Grundpauschale. Das Amt verlängert
  dann seltener.

**Beleg:** ✅ `Wheels/WheelDestruction.md` (LUADOC): `local snowSystem = g_currentMission.snowSystem`,
`snowSystem.height` (Schneehöhe der Welt), `getSnowHeightAtArea`. ✅ `Shop/StoreManager.md`:
`getItemByXMLFilename`, `categoryName`.

**🟡 Im Spiel prüfen:** Wie verhält sich `snowSystem.height` bei ausgeschaltetem Schnee in den Spielstand-Einstellungen?
**Fallback:** Dann fehlt das Feld, und der Vertrag wird nicht angeboten.

### R31-A5 Erntehelfer auf Zeit

**Idee:** Für die Erntemonate stellst du Saisonkräfte ein, befristet mit festem Ende.

- [ ] Neue Rolle `JobRole.SEASONAL_WORKER`: Bewerbungen nur vor und in der Erntezeit (Konfig: FS25-Perioden),
  befristet bis zum Vertragsende (FS25-Perioden). Danach endet der Vertrag automatisch mit Abschiedsmail. Wer gut
  behandelt wurde, kommt im nächsten Jahr gern wieder (Vertrauen bleibt erhalten).
- [ ] Im Spiel fahren sie Helfer wie ein Maschinenführer **ohne** Schulung: kleine und mittlere Traktoren. Bei den
  Schulungen gleich wie der Azubi aus R3-P2, der Mod lässt die Rolle in `EMPLOYEE_ROSTER` zu.
- [ ] Gehalt je Monat höher als bei Festangestellten (Konfig), keine Gehaltsverhandlung und keine Schulungen.

**Beleg:** ✅ wie R3-P2 (Rolle in `EMPLOYEE_ROSTER`, Zuordnung im Hook auf `AIJob.start`).

**Akzeptanz A:** Ein beauftragtes Pflügen ist am vereinbarten Spieltag im Spiel erledigt, und die Rechnung ist gebucht.
Eine Ernte durch den Lohnunternehmer liegt danach im Silo. Ein gekauftes Kalb steht im Stall. Ein Leih-Mähdrescher
steht auf dem Hof und verschwindet am Ende der Leihzeit. An einem Schneetag zahlt der Winterdienst.

---

## B – Behörden und Förderung

Alle Punkte folgen dem V2-Muster der Ämter (R2-E): Mail mit Frist, Vorgang unter **Ämter**, Bezahlen oder Beantragen
per Knopf, Säumnis und Kontrolle mit Vorankündigung. Werte unter `rpsim.formulas.authority.*` bzw. eigenen Blöcken.

### R31-B1 Sammelantrag und Flächenprämie

- [ ] Einmal im FS25-Jahr ein **Sammelantrag** mit Stichtag (Konfig-Periode, Vorschlag Mai = Periode 3). Das Formular
  ist mit den eigenen Feldern und Kulturen vorbefüllt (R2-C1). Der Spieler bestätigt oder korrigiert die Kultur je Feld
  aus einer Liste. Verpachtete Felder (R3-L) gehören nicht dazu, gepachtete schon.
- [ ] Auszahlung der **Flächenprämie** je Hektar (`DIRECT_PAYMENT`) zu einem festen Termin (Konfig-Periode).
- [ ] **Vor-Ort-Kontrolle:** Ein Anteil der Anträge wird geprüft (Wahrscheinlichkeit aus der Konfig). Weicht die
  angegebene Kultur von der exportierten ab, wird gekürzt, im Wiederholungsfall stärker. Die Fruchtfolge-Regeln
  (R2-E2) fließen als Auflagen ein.
- [ ] Verspätet → Abzug je Tag, nicht gestellt → keine Prämie.

**Beleg:** – (Daten aus R2-C1, Ablauf wie R2-E1/E2).

### R31-B2 Investitionsförderung

- [ ] Vor einem Kauf stellt der Spieler beim Amt einen **Förderantrag**: Art (Stall/Gebäude oder Maschine), geplante
  Summe, Frist. Nach der Bewilligung (Bearbeitungszeit, mit Bürokraft kürzer, R3-P1) muss er innerhalb der Frist
  wirklich kaufen.
- [ ] Nachweis aus dem Buchungsjournal: `SHOP_PROPERTY_BUY` bzw. `SHOP_VEHICLE_BUY` (R2-B, im Spiel geprüft) summiert ab
  der Bewilligung. Ein Kauf **vor** der Bewilligung zählt nicht (vorzeitiger Maßnahmenbeginn).
- [ ] Zuschuss = Förderquote × anerkannte Summe, gedeckelt (Konfig), als `INVESTMENT_GRANT`. Wer das gekaufte Objekt
  innerhalb einer Bindungsfrist verkauft (`SHOP_VEHICLE_SELL` im Journal), muss anteilig zurückzahlen (Rechnung wie
  `TAX_BILL`).

**Beleg:** – (Journal aus R2-B).

### R31-B3 Düngeverordnung

- [ ] **Sperrfrist:** In den Konfig-Perioden des Winters ist organischer Dünger auf Ackerland verboten. Der Mod
  exportiert je eigenem Feld `sprayType` als Namen aus der Tabelle `FieldSprayType` (z. B. `LIQUID_MANURE`, `MANURE`,
  `LIME`). Wechselt ein Feld in der Sperrfrist auf `LIQUID_MANURE` oder `MANURE` und steigt `sprayLevel`, kündigt das
  Amt eine Kontrolle an. Im Wiederholungsfall folgt ein Bußgeld (`FINE`) und Ansehensverlust.
- [ ] **Güllelager:** Steht der Füllgrad des Güllelagers eines Stalls (Bedingung aus `husbandries[].conditions`, R2-A7)
  lange über der Schwelle, warnt der Tierpfleger bzw. die Genossenschaft. Kurz vor der Sperrfrist kommt eine Erinnerung
  („Jetzt noch Gülle fahren, ab November ist Schluss“).
- [ ] Alle Schwellen und Perioden als Platzhalter in `rpsim.formulas.fertilizer-rules.*`.

**Beleg:** ✅ `field/FieldState.lua` (Dump): Feld `sprayType`. ✅ `field/FieldManager.lua`: Werte
`FieldSprayType.NONE`, `LIQUID_MANURE`, `MANURE`, `LIME`. ✅ Güllelager als Eintrag von `getConditionInfos` (R2-A7,
`PlaceableHusbandryLiquidManure`).

**🟡 Im Spiel prüfen:** Bleibt `sprayType` nach dem Ausbringen stehen, bis die nächste Arbeit ihn ändert? Oder springt er
zurück? **Fallback:** Nur `sprayLevel`-Anstieg in der Sperrfrist werten und die Art offenlassen. Das Amt schreibt dann
„Düngung festgestellt“ statt „Gülle“.

### R31-B4 Tierseuche und Sperrzone

- [ ] Seltenes Ereignis (Konfig, im idyllischen Weltmodus aus): Eine Seuche (z. B. Afrikanische Schweinepest,
  Geflügelpest) trifft eine Tierart in der Region. Das Amt richtet eine Sperrzone für eine Anzahl Perioden ein.
- [ ] Folgen im Tool:
  - Viehhandel (A3) und Angebote des Viehhändlers für diese Tierart sind gesperrt.
  - Tierarzt-Pflichtuntersuchung (`VET_INVOICE`).
  - Auflagen mit Frist (Stall mit guter Gesundheit, R2-A7).
  - Nachrichten und Klatsch.
- [ ] Die Tierpreise des Spiels ändert das Tool **nicht** (nicht geprüft, siehe
  [Bewusst nicht aufgenommen](#bewusst-nicht-aufgenommen)). Die Preise im Handel mit den Nachbarn (A3) sinken nach
  Formel.

**Beleg:** – (Backend; Tierbestand aus `assets.animals`).

### R31-B5 Berufsgenossenschaft, Arbeitsunfall und Krankheit

- [ ] Jahresbeitrag der landwirtschaftlichen Berufsgenossenschaft (Konfig: Grundbetrag + je Hektar + je Mitarbeiter)
  als `SOCIAL_INSURANCE`, Bescheid unter **Ämter**.
- [ ] **Krankheit und Arbeitsunfall:** Selten (Konfig), ein Mitarbeiter fällt für einige Spieltage aus. Er ist in dieser
  Zeit `ON_LEAVE` in `EMPLOYEE_ROSTER`, das Gehalt läuft weiter. Unfälle werden wahrscheinlicher bei hoher
  Arbeitsbelastung (Stunden aus R2-A4) und schlechtem Maschinenzustand (`condition`). Gute Bedingungen senken das Risiko.
- [ ] Genesungswünsche als Wertschätzung, Tagebucheintrag.

**Beleg:** – (Backend; `ON_LEAVE` gibt es seit R2-A0).

**Akzeptanz B:** Der Sammelantrag lässt sich bis zum Stichtag stellen, und die Prämie wird zum Termin gezahlt. Ein Kauf
nach Förderbewilligung bringt den Zuschuss, ein Kauf davor nicht. Gülle in der Sperrfrist führt zu einer
Kontrollankündigung. Ein kranker Mitarbeiter fährt ein paar Tage keinen Helfer.

---

## D – Dorfleben

### R31-D1 Dorfzeitung

- [ ] Neue App **„Dorfblatt“**: eine Ausgabe je FS25-Periode, optional zusätzlich zur Monatsmitte. Rubriken:
  - Aus dem Dorf: Feste, Zu- und Wegzüge, Vereine.
  - Vom Hof: deine öffentlichen Taten, z. B. Sponsoring, Hofladen, Rekordernte, Winterdienst.
  - Markt: Preisentwicklung aus `prices`, Gerüchte ohne Gewähr.
  - Amtliches: Stichtage, Sperrzonen, Kontrollen.
  - Kleinanzeigen: offene Angebote von Nachbarn (R3-H, A2, A3).
- [ ] **Fakten aus dem Backend, Text von der KI**, ohne KI mit Vorlagen. Die Zeitung nennt nur, was öffentlich ist
  (`PublicActionEvent`). Private Geldsachen erscheinen nie. Ausnahme: Öffentlich gewordene Zahlungsausfälle, wie beim
  Dorf-Ansehen.
- [ ] Ältere Ausgaben bleiben lesbar. Die Chronik-Datei (R3-T2) kann die Titelzeilen übernehmen.

**Beleg:** – (Backend und Oberfläche).

### R31-D2 Dorf-Gruppenchat

- [ ] Neue App **„Dorfchat“** im Stil eines Messengers: Gruppe „Dorf“ plus Gruppen für Vereine (R2-E4) und Nachbarn.
- [ ] Nachrichten der Charaktere:
  - kurze Ankündigungen („Morgen Grünschnittabfuhr“),
  - Hilfegesuche (Links auf R3-H4, R3-H5 und A3),
  - Klatsch und Glückwünsche.

  Ton und Häufigkeit wie beim Dorfleben (`VillageLifeService`), Obergrenze je Tag.
- [ ] Der Spieler kann in Gruppen schreiben. Es gelten die Regeln von „Nachricht verfassen“: Ton-Klassifikator,
  Pacing-Limit, keine Mechanik über Freitext.

**Beleg:** – (Backend und Oberfläche).

### R31-D3 Stammtisch

- [ ] Wiederkehrende Einladung in die Dorfkneipe (feste Periode oder alle N Spieltage, Konfig). Zusage per Knopf oder
  als Frage im Spiel (F2).
- [ ] Wer hingeht:
  - Vertrauensbonus bei den Anwesenden,
  - das nächste Gerücht (`MarketEventType.RUMOR`) ist mit höherer Wahrscheinlichkeit richtig
    (Aufschlag auf `rpsim.formulas.market.rumor-accurate-probability`, heute 0,7),
  - ab und zu ein Tipp auf eine anstehende Versteigerung oder einen verkaufsbereiten Feldbesitzer.
- [ ] Wer nie kommt, gilt irgendwann als „eigenbrötlerisch“: kleiner Abzug beim Dorf-Ansehen, gedeckelt.

**Beleg:** – (Backend; Gerüchte aus `MarketEventEngine`).

### R31-D4 Beschwerden über Nachtarbeit

- [ ] Laufen Helfer der Spieler-Farm (`workforce.activeJobs`, R2-A4) in der Nacht (Tageszeit aus
  `environment.dayTime`, Konfig z. B. 22–6 Uhr), sammelt das Backend Nachtstunden.
- [ ] In der **Erntezeit** (eigene Felder `HARVESTABLE`) zeigt das Dorf Verständnis, und es gibt keine Beschwerde.
  Sonst kommt nach einer Schwelle eine Beschwerde eines Dorfbewohners, erst freundlich, dann genervt. Folgen: kleiner
  Vertrauensverlust, Klatsch in der Dorfzeitung.
- [ ] Abschaltbar, Werte in `rpsim.formulas.night-work.*`.

**Beleg:** ✅ `workforce.activeJobs` (R2-A4) und die Tageszeit (`environment.dayTime`, heute schon in
`RPSimGameAdapter:getGameTime`) werden exportiert. Der Export muss die Tageszeit als eigenes Feld mitgeben
(`calendar.dayTimeMs`).

### R31-D5 Flurschaden

- [ ] Der Mod exportiert alle 10 s eine Stichprobe der Positionen eigener Fahrzeuge, die gerade gefahren werden
  (`getIsControlled()` oder `getIsAIActive()`). Für jede Position bestimmt er:
  - `farmlandId` über `g_farmlandManager:getFarmlandIdAtWorldPosition(x, z)`,
  - ob dort ein Feld mit Frucht steht, über eine Stichprobe mit `FieldState` an dieser Stelle (wie der V2-Fallback
    `fieldState:update(x, z)`).
- [ ] Backend: Mehrere Stichproben in Folge auf dem **bestellten Feld eines Nachbarn**, ohne laufenden Auftrag auf
  diesem Feld, ergeben eine Beschwerde des Besitzers („Da sind ja Fahrspuren quer durch meinen Raps!“). Folgen:
  Vertrauensverlust, bei Wiederholung eine Entschädigungsforderung (Formular zahlen/ablehnen, wie R2-D2).
- [ ] Mindestens N Stichproben in Folge (Konfig), damit kurzes Abkürzen am Feldrand nicht zählt. Abschaltbar.

**Beleg:** ✅ `Economy/FarmlandManager.md` (LUADOC): `getFarmlandIdAtWorldPosition(worldPosX, worldPosZ)`. ✅
Fahrzeugposition über `getWorldTranslation(self.rootNode)` (Dump `Vehicle.lua`). ✅ `FieldState:update(x, z)` als
Stichprobe (Roadmap V2, R2-C1 Fallback). ✅ `Enterable:getIsControlled`, `Vehicle:getIsAIActive`.

**🟡 Im Spiel prüfen:** Wie viele Fehlalarme gibt es? Zum Beispiel wenn Feldwege über ein Farmland laufen oder bei der
Anfahrt zu einem eigenen Auftrag. **Fallback:** Standardmäßig aus, Schwelle höher setzen, Hinweis vor der ersten
Beschwerde („Pass auf, wo du langfährst“).

### R31-D6 Ferien auf dem Bauernhof und Hofführungen

- [ ] **Ferien auf dem Bauernhof:** einmalige Einrichtung (Konfig-Betrag, Formular), danach monatliche Gäste. Die
  Einnahmen (`GUEST_INCOME`) hängen ab von:
  - Saison: Sommer und Ferien höher,
  - Dorf-Ansehen,
  - Tieren auf dem Hof mit guter Gesundheit (R2-A7).
- [ ] Die Gäste reagieren auf echte Werte:
  - schlechte Stallwerte → schlechte Bewertungen,
  - Nachtarbeit (D4) → Beschwerde über Lärm,
  - Güllefahren in der Hauptsaison (B3-Daten) → Beschwerde über den Geruch.
- [ ] **Hofführungen für Schulklassen:** Anfragen der Schule, Zusage per Knopf. Stärkt das Dorf-Ansehen und bringt eine
  kleine Aufwandsentschädigung. Voraussetzung sind Tiere mit guter Gesundheit.

**Beleg:** – (Backend; Daten aus R2-A7, D4, B3).

### R31-D7 Genossenschaftsanteile

- [ ] Anteile an der Genossenschaft (`COOPERATIVE`) zeichnen (Formular, feste Stückelung). Jährliche Dividende nach
  dem Jahresabschluss (`COOP_DIVIDEND`), abhängig von der Marktlage (Durchschnitt der Preise im Jahr, Konfig).
- [ ] **Generalversammlung** einmal im Jahr: Abstimmung über ein Thema (Ja/Nein, auch als Frage im Spiel, F2), z. B. ein
  neues Getreidelager oder eine höhere Dividende. Das Ergebnis bestimmt eine Formel aus den Stimmen der Charaktere und
  deiner.
- [ ] Ab einer Anteilszahl und gutem Vertrauen: **Wahl in den Vorstand**. Das bringt Vorteile nach Formel, z. B. frühere
  Gerüchte, Vorrang bei Vorkontrakten (R3-M2) und etwas Ansehen, aber auch Pflichttermine im Kalender.
- [ ] Kündigung der Anteile mit Frist (Konfig), Rückzahlung zum Nennwert.

**Beleg:** – (Backend).

### R31-D8 Dieselklau

- [ ] Seltenes Ereignis (Konfig, im idyllischen Weltmodus aus): Nachts fehlt Diesel in einer abgestellten eigenen
  Maschine.
- [ ] Neue Anweisung `VEHICLE_FUEL { vehicleId, delta }` (nur negativ). Der Mod prüft:
  - das Fahrzeug gehört der Spieler-Farm, niemand sitzt drin, kein Helfer fährt,
  - es hat einen Diesel-Tank (`getConsumerFillUnitIndex(FillType.DIESEL)`).

  Dann zieht er höchstens den vorhandenen Stand ab (`getFillUnitFillLevel`,
  `addFillUnitFillLevel(farmId, fillUnitIndex, -menge, FillType.DIESEL, ToolType.UNDEFINED, nil)`). Elektrische und
  Methan-Fahrzeuge bleiben verschont.
- [ ] Danach Mail der Polizei bzw. Klatsch („Bei Müllers haben sie auch schon abgezapft“). Ist der Schaden groß, zahlt
  die Versicherung (Sturm/Hagel-Vertrag, neuer Baustein „Diebstahl“, Konfig).
- [ ] Gegenmaßnahme: **Tankschloss** bei der Werkstatt kaufen (Formular, einmalig je Fahrzeug). Es senkt die
  Wahrscheinlichkeit für dieses Fahrzeug stark.
- [ ] `assets.vehicles[].fuel` (Dieselstand) wird exportiert, damit das Backend nur Fahrzeuge mit genug Diesel wählt.

**Beleg:**
- ✅ `Specializations/FillUnit.md` (LUADOC): `addFillUnitFillLevel(farmId, fillUnitIndex, delta, fillType, toolType,
  …)` mit negativem Delta im Spielcode, `getFillUnitFillLevel`, `getFillUnitCapacity`.
- ✅ `Specializations/Motorized.md`: `getConsumerFillUnitIndex(fillTypeIndex)`.
- ✅ `Vehicles/VehicleSystem.md`: Muster `vehicle:getConsumerFillUnitIndex(FillType.DIESEL)`.

**Akzeptanz D:** Jede Periode erscheint eine Dorfzeitung mit echten Ereignissen. Nachbarn bitten im Dorfchat um Hilfe.
Wer zum Stammtisch geht, bekommt verlässlichere Gerüchte. Nächtliche Helfer außerhalb der Erntezeit führen zu einer
Beschwerde. Nach einem Dieselklau fehlt der Diesel im Tank der Maschine.

---

## K – Hofkarte

### R31-K1 Felder als Karte

- [ ] Der Mod exportiert beim Missionsstart `market_context.fieldShapes[]`:
  - je Feld die Eckpunkte des Umrisses (`field.polygonPoints`, Weltkoordinaten x/z über `getWorldTranslation`,
    vereinfacht auf höchstens N Punkte),
  - `farmlandId` und `name`,
  - dazu `mapSize` = `g_currentMission.terrainSize`.

  Die Umrisse ändern sich nicht, ein Export je Missionsstart genügt.
- [ ] Neue Ansicht in der Flurkarte: **Karte** neben **Tabelle** (SVG, keine Kartenbibliothek). Farben:
  - eigene Felder nach Phase (`EMPTY` / `GROWING` / `HARVESTABLE` / `HARVESTED` / `WITHERED`),
  - gepachtete Felder schraffiert,
  - verpachtete Felder (R3-L) umrandet,
  - Nachbarfelder (R3-H1) blass mit Namen des Besitzers,
  - Aufträge, Versteigerungen und Hinweise als Symbole.
- [ ] Klick auf ein Feld öffnet die bekannte Feldkarte mit den Aktionen: Verkaufen, Verpachten, Lohnunternehmer (A1),
  Familienfeld.

**Beleg:** ✅ `field/Field.lua` (Dump): `self.polygonPoints` (Knoten des Feldumrisses), `self.posX`/`posZ` über
`MathUtil.getPolygonLabel`. ✅ `Economy/FarmlandManager.md`: `g_currentMission.terrainSize`. ✅ `getWorldTranslation`
auf Knoten (Dump `Vehicle.lua`).

**🟡 Im Spiel prüfen:** Stimmt die Ausrichtung von x/z zur Karte im Spiel (Norden oben)? **Fallback:** Die Achse in der
Oberfläche spiegeln (Schalter im Code, einmal beim Spieltest festlegen).

**Akzeptanz K:** Die Flurkarte zeigt alle Felder der Karte in ihrer echten Form. Eigene Felder sind nach Phase
eingefärbt, und ein Klick öffnet die Aktionen des Feldes.

---

## Bewusst nicht aufgenommen

| Idee | Warum nicht |
| --- | --- |
| Anrufe mit Sprachausgabe (jede Figur spricht mit eigener Stimme) | Wunsch des Projektinhabers vom 30.09.2026: vorerst nicht. |
| Maschinen gehen dauerhaft kaputt (Motorschaden als Ereignis) | `Vehicle:setBroken()` (LUADOC `Vehicles/Vehicle.md`) setzt `isBroken` endgültig und ist nicht reparierbar. Ein Motorschaden wäre höchstens ein hoher, reparierbarer Schaden über `setDamageAmount` (gibt es seit V1). Als eigener Punkt nicht aufgenommen. |
| Sonntagsruhe und Feiertage mit Wochentag | Weder im Dump noch in der LUADOC wurde eine Funktion für den Wochentag gefunden. Das Tool kennt nur Periode, Tag in der Periode und Tageszeit. |
| Tierpreise des Spiels bei einer Seuche ändern | Ein Eingriff in die Tierpreise des Händlers im Spiel ist nicht geprüft. Die Seuche (B4) wirkt auf den Handel im Tool. |
| Prüfen, ob beim Winterdienst wirklich geräumt wurde | Das Tool müsste Straßen und Fahrwege von Feldern unterscheiden. Dafür gibt es keine geprüfte Funktion. Bezahlt wird nach Vertrag. |
| Nachbarn räumen oder ernten sichtbar mit eigenen Maschinen | Wie in V3: NPCs haben im Grundspiel keine Fahrzeuge. Der Lohnunternehmer (A1) arbeitet per Zustandssprung wie ein abgeschlossener Auftrag. |

---

## Quellen

- FS25-Quellcode-Dump (`dataS`): <https://github.com/Dukefarming/FS25-lua-scripting>. Genutzt wurden
  `field/Field.lua`, `field/FieldState.lua`, `field/FieldManager.lua`, `Vehicle.lua`,
  `animals/husbandry/placeables/PlaceableHusbandryAnimals.lua`.
- FS25 Community LUADOC: <https://github.com/umbraprior/FS25-Community-LUADOC>. Genutzt wurden:
  - `script/Field/AbstractFieldMission.md` (`finishField`, `getFieldFinishTask`), `script/Field/PlowMission.md`,
    `script/Fruits/FruitTypeDesc.md` (`cutState`)
  - `script/Specializations/PlaceableHusbandryAnimals.md` (`addAnimals`, `addCluster`, `getNumOfFreeAnimalSlots`,
    `changeNumAnimals`), `script/Specializations/Rideable.md` (`getSubTypeByName`)
  - `script/Wheels/WheelDestruction.md` (`snowSystem.height`)
  - `script/Specializations/FillUnit.md`, `script/Specializations/Motorized.md`, `script/Vehicles/VehicleSystem.md`
  - `script/Economy/FarmlandManager.md` (`getFarmlandIdAtWorldPosition`, `terrainSize`)
  - `script/Shop/StoreManager.md`, `script/Specializations/Enterable.md`, `script/Vehicles/Vehicle.md` (`setBroken`)
- Bestehende Projekt-Doku: [`ROADMAP_V2.md`](ROADMAP_V2.md), [`ROADMAP_V3.md`](ROADMAP_V3.md),
  `docs/dev/bridge-protocol.md`, `docs/dev/manual-test-plan.md`.
