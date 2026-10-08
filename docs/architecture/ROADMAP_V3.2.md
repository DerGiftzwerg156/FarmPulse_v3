# FarmPulse – Roadmap V3.2

Ergänzung zu [Roadmap V3](ROADMAP_V3.md) und [Roadmap V3.1](ROADMAP_V3.1.md), Stand 08.10.2026. Ziel: Der Hof bekommt
große Geschäftspartner. **Großabnehmer** bestellen Ware in großen Mengen, sofort aus dem Silo oder zu einem festen
Liefermonat. Selten meldet sich ein **Großinvestor**, der Geld in den Hof steckt und dafür eine Gegenleistung verlangt.

Beide Bereiche hat der Projektinhaber am 08.10.2026 beschrieben; die Entscheidungen zu Lieferweg, Menge, Preis,
Fehlmenge, Kapitalart, Vertragsbruch, Verhandlung, Gegenleistungen, Rückkauf, Häufigkeit und Absender stehen in
`QUESTIONS.md` (08.10.2026). Was noch nicht entschieden ist, steht als Vorschlag unter
[Offene Punkte](#offene-punkte-vorschläge-zur-bestätigung). Wie in V2 bis V3.1 enthält die Datei **nur Punkte, die mit
der FS25-Schnittstelle machbar sind**. Jeder Punkt mit Mod-Eingriff wurde am 08.10.2026 gegen den FS25-Quellcode-Dump
und die Community-LUADOC geprüft (Quellen am Ende).

**Grundsätze (unverändert aus V1–V3.1)**

- Der Mod bleibt **Sensor und Aktuator**. Alle Entscheidungen und Zahlen berechnet das Backend nach festen,
  konfigurierbaren Formeln (`rpsim.formulas.*`). Die KI formuliert nur, auch die Mails und Anrufe der Großabnehmer und
  Investoren.
- Jeder Zugriff auf FS25 läuft in `RPSimGameAdapter` und ist mit `pcall` abgesichert. Fehlt eine API, fällt die Funktion
  still auf das bisherige Verhalten zurück.
- Neue Felder in den Bridge-Dateien sind **optional** (`schemaVersion` bleibt `1`).
- Beträge und Mengen gibt der Spieler nur über Formulare ein. Das Backend legt jeden Preis fest. Anweisungen, die Geld
  bewegen, laufen als Batch mit ihrer `MONEY_TRANSACTION`.
- Spielmonat = FS25-Periode. Ein Termin ist **immer ein ganzer Monat**, weil „Tage je Periode“ in den
  Spielstand-Einstellungen jederzeit geändert werden kann. Das Backend rechnet heute schon so: Ein geplanter Termin
  behält seinen Monat, auch wenn sich die Länge ändert (Vorkontrakte erst seit G, solange ihre Anweisung noch nicht
  ausgeführt ist) (`docs/dev/bridge-protocol.md`, Block `calendar`;
  `time/GameTime`, `time/CalendarService`; Vorkontrakt R3-M2 mit „Lieferfenster = ganzer Liefermonat“).

**Hängt ab von V3 / V3.1:**
- `tradeStorage` und `STORAGE_TRANSFER` (R3-H2 bis H4, genutzt vom Hofladen R3-M3),
- `PRICE_EVENT` / `FIXED` mit `contractReports` und die Regel „ein Festpreis je Verkaufsstelle und Fruchtsorte“ (R3-M2),
- Hofbericht und Liquiditätsplanung (R3-K2, R3-K3), Bonitätsscore (`credit/CreditScoringService`),
- `ANIMAL_TRANSFER` (R31-A3), Stallwerte `husbandries[]` (R2-A7), Feldwerte `fields[]` (R2-C1),
- Ferienwohnung (R31-D6), Dorfblatt (R31-D1), Kalender-Vorgänge (R31-D3/D7), Feldverkauf im Spielmenü (R2-D2).

## Legende

| Zeichen | Bedeutung |
| --- | --- |
| ✅ **Belegt** | Die genutzte Funktion bzw. das Feld steht so im FS25-Code; Fundstelle ist angegeben. |
| 🟡 **Im Spiel prüfen** | Die API existiert, aber ein Detail lässt sich nur im laufenden Spiel klären. Zu jedem 🟡 steht ein Fallback. |
| – | Kein Mod-Eingriff: nur Backend und Oberfläche mit Daten und Anweisungen, die es seit V1–V3.1 gibt. |

Punkt-IDs: `R32-<Bereich><Nummer>`, z. B. `R32-G2`. Alle Pfade sind relativ zum Repo-Root.

## Übersicht und empfohlene Reihenfolge

| Phase | Bereich | Inhalt | Mod-Eingriff | Hängt ab von |
| --- | --- | --- | --- | --- |
| 0 | [Q – Querschnitt](#q--querschnitt) | Rollen, Fälle, Buchungsgründe, Stall-Lager-Export, Anweisung `HUSBANDRY_TRANSFER`, Simulator, Testplan | ja | – |
| 1 | [G – Großaufträge](#g--großaufträge) | Großabnehmer, Sofortlieferung aus dem Silo, Termin-Lieferung an die Verkaufsstelle, Strafe | nein | R3-H, R3-M2 |
| 2 | [I – Großinvestoren](#i--großinvestoren) | Anlass, Angebot mit 2–3 Paketen, Gegenleistungs-Katalog, Prüfung, Vertragsbruch, Rückkauf | I3 (Milch): ja | Q, R3-K, R31-A3, R31-D6 |

G zuerst: Es braucht keinen Mod-Eingriff, nutzt nur vorhandene Anweisungen und ist die Grundlage für die
Waren-Gegenleistungen der Investoren. In I ist nur die Milch-Lieferung (I3, Typ W3) auf den neuen Stall-Lager-Export
angewiesen; alle anderen Gegenleistungen kommen ohne Mod-Änderung aus.

---

## Q – Querschnitt

**Stand 08.10.2026: umgesetzt.** Wie R3-Q und R31-Q legt Q den Vertrag an (Schemas, DTOs, Validator, Normalisierung
im Mod, Simulator, Doku). Anders als dort liest der Mod den Stall-Lager-Export schon im Spiel aus und führt
`HUSBANDRY_TRANSFER` aus (Entscheidung 08.10.2026). Entscheidungen (siehe `QUESTIONS.md`): Die Tabellen `bulk_order`,
`investor_contract` / `investor_obligation` und die Werte unter `rpsim.formulas.bulk-order.*` / `investor.*` kommen
mit G bzw. I; Q bringt nur die Enums und die Buchungsklassen. `INVESTOR_PAYOUT` ist `FINANCING`. Buchungstitel:
Investorenkapital, Rückzahlung an Investor, Ausschüttung an Investor, Ausgleichszahlung an Investor; Rollen
„Großabnehmer“ und „Investor/in“. `husbandries[].storage[]` wie `tradeStorage` (nur Milch-Sorten, ganze Liter, nur
Einträge mit Menge oder Kapazität, sortiert). Der Simulator führt `HUSBANDRY_TRANSFER` aus wie der Mod; nur
`investor-milch` liefert `storage[]`. Ob ein fehlender `HUSBANDRY_TRANSFER` nach dem Zurückspulen erneut gesendet wird,
entscheidet I3. Spieler-Doku nur im `CHANGELOG`.

### R32-Q1 Domäne und Bridge erweitern

- [x] Neue `CharacterRole`-Werte:
  - `BULK_BUYER` (Großabnehmer, G1),
  - `INVESTOR` (Großinvestor, I1).

  Beide werden bei Bedarf angelegt, wie Bewerber; sie gehören nicht zur Startbesetzung.
- [x] Neue `CaseKind`-Werte: `BULK_ORDER` (G), `INVESTOR_OFFER`, `INVESTOR_REMINDER`, `INVESTOR_CLAIM` (I).
- [ ] Neue Tabellen `bulk_order` und `investor_contract` mit `investor_obligation` (je Gegenleistung eine Zeile mit Typ,
  Parametern, Soll und Ist je Abrechnungszeitraum). Kommt mit G bzw. I (Entscheidung 08.10.2026).
- [x] Neue `MoneyReason`-Werte und ihre Klassen in `rpsim.formulas.finance.categories`:

  | Grund | Wofür | Klasse |
  | --- | --- | --- |
  | `INVESTOR_CAPITAL` | Einzahlung des Investors (I2) | `FINANCING` |
  | `INVESTOR_REPAYMENT` | Rückkauf der Anteile bzw. Rückzahlung des Darlehens (I5, Kündigung I4) | `FINANCING` |
  | `INVESTOR_PAYOUT` | Gewinnanteil oder feste Ausschüttung (I3, Typ R1/R2) | `FINANCING` (Entscheidung 08.10.2026) |
  | `INVESTOR_COMPENSATION` | Ausgleichszahlung bei Fehlmenge oder verfehlter Auflage (I4) | `OPERATING_EXPENSE` |

  Großaufträge brauchen keinen neuen Grund: Sofortlieferung bucht `GOODS_SALE` (wie Hofladen), Termin-Lieferung zahlt
  das Spiel an der Verkaufsstelle, eine Fehlmenge kostet `CONTRACT_PENALTY` (wie R3-M2).
- [x] `farm_facts.json`, neues optionales Feld `husbandries[].storage[]` = `{ fillType, amount, capacity }` für die
  Milch-Sorten des Stalls (I3, Typ W3), siehe Beleg.
- [x] Neue Anweisung `HUSBANDRY_TRANSFER { husbandryUniqueId, fillType, amount }` (nur Entnahme). Eine **eigene**
  Anweisung statt eines neuen Felds an `STORAGE_TRANSFER`: Ein älterer Mod würde ein unbekanntes Feld überlesen und aus
  den Silos buchen. Ein unbekannter Typ wird dagegen abgelehnt (`REJECTED`, bzw. `FAILED` / `NOT_SUPPORTED`), und das
  Backend rät zum Update (`modOutdated`, wie R31-Q1).
- [x] `docs/dev/bridge-protocol.md` je Feld und Anweisung mit Quelle im FS25-Code.

**Beleg (Stall-Lager, nur für I3 / W3):**
- ✅ `Specializations/PlaceableHusbandry.md` (LUADOC) und `animals/husbandry/placeables/PlaceableHusbandry.lua` (Dump):
  - der Stall hat ein eigenes Lager (`spec.storage = Storage.new(...)`) mit Ab- und Beladestation,
  - `getHusbandryFillLevel(fillTypeIndex, farmId)` und `getHusbandryCapacity(fillTypeIndex, farmId)` lesen es über die
    Abladestation,
  - `removeHusbandryFillLevel(farmId, deltaFillLevel, fillTypeIndex)` entnimmt über die Beladestation
    (`spec.loadingStation:removeFillLevel(...)`) und gibt die **nicht** entnommene Restmenge zurück; ohne Beladestation
    kommt das ganze Delta zurück.
- ✅ Das Spiel nutzt `removeHusbandryFillLevel` selbst: `PlaceableHusbandryWater.md` (Wasser) und
  `PlaceableHusbandryStraw.md` (Stroh), jeweils mit Auswertung der Restmenge.
- ✅ `Specializations/PlaceableHusbandryMilk.md` / Dump `PlaceableHusbandryMilk.lua`: Die Milch-Sorten des Stalls stehen
  in `spec_husbandryMilk.fillTypes` (aus `subType.output.milk.fillType` jedes Untertyps). Die Milch landet mit
  `addHusbandryFillLevelFromTool` im Stall-Lager, die Info-Box liest sie mit `getHusbandryFillLevel`.

**Mod-Ablauf `HUSBANDRY_TRANSFER`:** Stall über `husbandryUniqueId` suchen, Besitz der Spieler-Farm prüfen, Sorte über
`g_fillTypeManager:getFillTypeIndexByName` auflösen und gegen `spec_husbandryMilk.fillTypes` prüfen,
`getHusbandryFillLevel` ≥ Menge prüfen, dann `removeHusbandryFillLevel(farmId, amount, fillTypeIndex)`. Fehlercodes:
`HUSBANDRY_NOT_FOUND`, `UNKNOWN_FILLTYPE`, `WRONG_FILLTYPE`, `INSUFFICIENT_STOCK`; ist die Restmenge > 0, bucht der Mod
die entnommene Menge zurück (`addHusbandryFillLevelFromTool`) und meldet `INSUFFICIENT_STOCK`.

**🟡 Im Spiel prüfen:**
- Entnimmt `removeHusbandryFillLevel` auch aus Milchtank-Erweiterungen in Reichweite (der Dump hängt sie in
  `PlaceableHusbandry` an die Ladestationen)? **Fallback:** Export und Prüfung nur mit dem, was
  `getHusbandryFillLevel` liefert; die Entnahme wertet die Restmenge aus (siehe oben).
- Zeigt die Info-Box des Stalls den neuen Stand sofort? **Fallback:** Anzeige beim nächsten Update genügt (wie R3-H4).

### R32-Q2 Simulator, Tests, Konfiguration, Doku, Testplan

- [x] Bridge-Simulator: Szenarien `grossauftrag` (volles Raps-Silo, Ölmühle als Verkaufsstelle) und `investor-milch`
  (Kuhstall mit Milch im Lager); Steuer-Endpunkt für die Milchmenge eines Stalls.
- [x] Mod-Tests mit gemockten FS25-Globals: Normalisierung von `husbandries[].storage[]`, Prüfung und Ausführung von
  `HUSBANDRY_TRANSFER` samt Fehlercodes und Rückbuchung.
- [x] Backend: Grenzwert-Tests je Formel (Preis, Menge, Strafe, Paket-Bewertung, Vertragsbruch-Stufen), End-to-End-Test
  gegen den Simulator, `BridgeValidatorTest` und `FailedInstructionTest` für das neue Feld und die neue Anweisung.
  Q bringt keine Formel; die Grenzwert-Tests kommen mit G und I. `SimulatorScenariosEndToEndTest` prüft Milch-Lager,
  Ölmühle und `HUSBANDRY_TRANSFER`.
- [x] Neue Werte unter `rpsim.formulas.bulk-order.*` und `rpsim.formulas.investor.*` samt
  `docs/dev/configuration-reference.md` (`ConfigurationReferenceDocTest`). Q bringt nur die Klassen der neuen
  Buchungsgründe unter `rpsim.formulas.finance.categories`; die Werte kommen mit G bzw. I.
- [x] Spieler-Doku `docs/user-guide/funktionen.md`, `CHANGELOG.md`. Q ist für Spieler nicht sichtbar, daher nur der
  `CHANGELOG`-Eintrag.
- [x] `docs/dev/manual-test-plan.md`: Abschnitt **„28. Roadmap V3.2 im echten FS25“**, eine Zeile je 🟡 und je
  Akzeptanzkriterium.

---

## G – Großaufträge

**Idee:** Neben dem Hofladen (kleine Mengen ans Dorf, R3-M3) kommen ab und zu Anfragen von **Großabnehmern** ins
Postfach, z. B. „500.000 l Raps“ von der Ölmühle. Liegt die Menge schon im Silo, lieferst du sofort. Sonst – oder wenn
du lieber später lieferst – vereinbarst du einen **Liefermonat** und fährst die Ware in diesem Monat selbst zur
Verkaufsstelle des Abnehmers.

**Entscheidungen 08.10.2026 (`QUESTIONS.md`):**
- Termin-Lieferung = **Hinfahren zur Verkaufsstelle** (Muster Vorkontrakt R3-M2).
- Menge = **feste Spanne je Fruchtsorte** (Konfig), unabhängig von der Hofgröße.
- Preis: **sofort** bester Marktpreis × **1,25**; **Termin** heutiger Preis der Verkaufsstelle × (1 + 0,05 + 0,01 × Monate
  Vorlauf).
- Fehlmenge am Monatsende: **Strafe wie Vorkontrakt** (25 % der Fehlmenge zum Festpreis, `CONTRACT_PENALTY`).
- Absender: **neue Großabnehmer-Figuren**, passend zu einer Verkaufsstelle der Karte.

**Stand 08.10.2026: umgesetzt** (`market/BulkOrderService`, Tab „Handel → Großaufträge“). Weitere Entscheidungen vom
08.10.2026 (`QUESTIONS.md`): Sorten und Spannen, Häufigkeit (1 Anfrage/Monat, 0,3 × Faktor, Faktor × 0,75 je Ablehnung,
Ignorieren oder Fehlmenge, mindestens 0,1), 5 Tage Antwortfrist und höchstens 3 offene Termin-Aufträge wie
vorgeschlagen. **Kein Filter** auf eigene Silos oder Anbau. **Jede Anfrage bringt eine neue Figur**, die sich nach dem
Auftrag verabschiedet. Keine Produktionen als Großabnehmer. 10 % der Anfragen als Anruf; ein verpasster oder
abgelehnter Anruf bringt die Anfrage zusätzlich als Mail. **Keine Ja/Nein-Frage im Spiel.** Termin vereinbaren geht
bis zur Antwortfrist. Kein Dorf-Ansehen; Vertrauen +3 bei voller Lieferung (sofort wie Termin), −5 bei Fehlmenge;
Ablehnen kostet kein Vertrauen. Festpreis **monatsgenau** (siehe G3). „Tage je Periode“ geändert: vor dem Liefermonat
wird die noch nicht ausgeführte Anweisung verschoben, im laufenden Liefermonat gilt das alte Ende im Mod (kein
Mod-Eingriff); dieselbe Regel gilt jetzt auch für Vorkontrakte (R3-M2), die ihr Fenster vorher nicht umgerechnet
haben.

### R32-G1 Großabnehmer und Anfrage

- [x] Ein Großabnehmer (`BULK_BUYER`) gehört zu einer **Verkaufsstelle der Karte**, die die Sorte annimmt
  (`market_context.sellPoints[]` mit `acceptedFillTypes`). Name und Art kommen aus dem Namen der Verkaufsstelle (z. B.
  „Ölmühle Nord“), die KI gibt der Ansprechperson einen Namen. Ohne passende Verkaufsstelle gibt es für diese Sorte
  keine Großaufträge.
- [x] **Welche Sorten:** eine Konfigliste (`bulk-order.fill-types`) mit fester Mengenspanne je Sorte
  (`bulk-order.amounts.<FILLTYPE>.min/max/step`). Werte siehe [Offene Punkte](#offene-punkte-vorschläge-zur-bestätigung).
- [x] **Wann:** monatlich höchstens N Anfragen mit Wahrscheinlichkeit p × Ablehnungsfaktor des Spielstands (Muster
  Hofladen: Ablehnen oder Ignorieren senkt den Faktor, eine erfüllte Lieferung hebt ihn). Eine Fehlmenge senkt ihn
  ebenfalls (G4).
- [x] **Wie:** Mail ins Postfach (Kategorie Handel) mit Link auf das Formular; selten als Anruf (`Channel.CALL`,
  Zustandsautomat aus `docs/architecture/call-state-machine.md`). Antwortfrist in Spieltagen (Konfig), danach verfällt
  die Anfrage wie eine ignorierte Hofladen-Bestellung.
- [x] Ansicht: neuer Bereich **„Großaufträge“** in der App „Handel“ (neben „Hofladen“), Eintrag in „Aufgaben“. Eine
  Ja/Nein-Frage im Spiel (F2) gibt es nicht (Entscheidung 08.10.2026).

**Beleg:** – (`market_context.sellPoints` und `prices` gibt es seit V1, `docs/dev/bridge-protocol.md`).

### R32-G2 Sofort liefern (aus dem Silo)

- [x] Angeboten nur, wenn `tradeStorage.amount` der Sorte ≥ Auftragsmenge. Teillieferung gibt es nicht: Der
  Großabnehmer will die ganze Menge oder einen Termin.
- [x] Preis = bester Marktpreis (`prices`) × `bulk-order.instant-markup` (**1,25**).
- [x] **„Sofort liefern“** prüft den Bestand erneut und sendet den Batch wie Hofladen/H4:
  `STORAGE_TRANSFER { direction: "OUT", fillType, amount }` + `MONEY_TRANSACTION` `GOODS_SALE`.
  `FAILED` / `INSUFFICIENT_STOCK` → keine Buchung, der Auftrag bleibt offen, „Termin vereinbaren“ ist weiter möglich.
- [x] Erfolg: Dank-Mail, Vertrauen beim Großabnehmer, Tagebucheintrag. Kein Dorf-Ansehen (Entscheidung 08.10.2026:
  Großaufträge sind Geschäftssache).

**Beleg:** ✅ wie R3-H4 / R3-M3 (`storage:getFillLevel` / `setFillLevel` in `PlaceableSilo`, LUADOC
`Specializations/PlaceableSilo.md`). Eine Obergrenze für `amount` hat `STORAGE_TRANSFER` nicht (Mod prüft nur `> 0`,
`mod/FS25_RPSim/src/import/Instructions.lua`).

### R32-G3 Termin vereinbaren (Lieferung an die Verkaufsstelle)

- [x] Formular **„Termin vereinbaren“**: Liefermonat als FS25-Monat aus einer Liste (Vorlauf `min-lead-months` bis
  `max-lead-months`), nie ein Tag. Das Backend nennt den Festpreis:
  `heutiger Preis an der Verkaufsstelle × (1 + term-base-markup 0,05 + term-markup-per-month 0,01 × Monate Vorlauf)`.
- [x] Umsetzung mit der vorhandenen Anweisung `PRICE_EVENT` / `FIXED` wie der Vorkontrakt:
  `fixedPrice`, `maxQuantity` = Auftragsmenge, `gameTimeEarliest` = Beginn des Liefermonats, `deadlineGameTime` = sein
  Ende. Der Spieler fährt die Ware selbst hin (oder liefert direkt vom Feld). Die gelieferte Menge meldet der Mod in
  `contractReports` (`deliveredQuantity`).
- [x] **Ein Festpreis je Verkaufsstelle und Fruchtsorte** (Regel aus R3-M2, weil `FIXED` Vorrang vor `MULTIPLIER` hat):
  Monate, in denen dort schon ein Vorkontrakt oder anderer Großauftrag läuft, stehen nicht zur Wahl. `MarketEventEngine`
  erzeugt in der Zeit kein `SPECIAL_OFFER` an dieser Stelle. Umgesetzt (Entscheidung 08.10.2026): auch Monate mit einem
  angebotenen oder laufenden Sonderangebot sind gesperrt; ein Vorkontrakt bleibt am ganzen Paar gesperrt, solange dort
  ein Vorkontrakt oder Großauftrag offen ist; Sonderangebote und Dürre-Preisanstiege meiden das Paar, solange ein
  Großauftrag offen ist (`ForwardContractService.openPairs`).
- [x] Höchstzahl offener Termin-Aufträge je Spielstand (Konfig). Kein Rücktritt nach dem Abschluss (wie R3-M2).
- [x] Die Liquiditätsplanung (R3-K2) zeigt die erwartete Einnahme im Liefermonat als „erwartet“; der Kalender zeigt den
  Liefermonat; zu Beginn des Liefermonats kommt ein Hinweis im Spiel (`NOTIFICATION`: „Ölmühle Nord wartet diesen
  Monat auf 500.000 l Raps“).

**Beleg:** – (`PRICE_EVENT` / `FIXED` und `contractReports` gibt es seit V1). Die Menge ist im Mod nicht nach oben
begrenzt (`Instructions.lua` prüft `maxQuantity > 0`, `PriceEvents.lua` zählt bis `maxQuantity`); die Grenze
200.000 l gilt nur im Formular des Vorkontrakts (`forward-contract.max-quantity`).

### R32-G4 Abschluss, Strafe, Vertrauen

- [x] Nach `deadlineGameTime` wertet das Backend `contractReports` aus (`endReason` `MAX_QUANTITY_REACHED` oder
  `DEADLINE_REACHED`):
  - volle Menge → Dank-Mail, Vertrauen + (Konfig), Ablehnungsfaktor eine Stufe hoch,
  - Fehlmenge → `Fehlmenge × Festpreis × penalty-share` (**0,25**) als `MONEY_TRANSACTION` `CONTRACT_PENALTY`,
    Mail des Abnehmers, Vertrauen − (Konfig), Ablehnungsfaktor eine Stufe runter.
- [x] Die Strafe erscheint im Journal als operative Ausgabe (wie R3-M2).
- [x] Werte unter `rpsim.formulas.bulk-order.*`.

**Beleg:** – (Abrechnung wie R3-M2).

**Akzeptanz G:** Eine Großanfrage über 500.000 l Raps erscheint im Postfach. Liegt die Menge im Silo, bucht „Sofort
liefern“ sie ab und zahlt 125 % des besten Preises. Ein Termin in einem späteren Monat setzt an der Ölmühle den
Festpreis für genau diesen Monat. Wer am Monatsende zu wenig geliefert hat, zahlt 25 % der Fehlmenge. Das Ändern der
Tage je Periode verschiebt den Termin nicht aus seinem Monat.

---

## I – Großinvestoren

**Idee:** Selten meldet sich per Mail oder Anruf ein **Großinvestor**. Ihm gefällt dein Hof, und er will zwischen
250.000 € und 2.500.000 € investieren. Dafür verlangt er eine Gegenleistung, z. B. „drei Jahre lang 100.000 l Raps“ oder
„jeden Monat 5.000 l Milch“. Er legt 2–3 Pakete vor, du wählst eins oder lehnst ab.

**Entscheidungen 08.10.2026 (`QUESTIONS.md`):**
- Kapitalart **je Angebot verschieden**: *Stille Beteiligung* (zählt als Eigenkapital, Rückkauf am Ende zum
  **Nennwert**) oder *Nachrangdarlehen* (zählt als Schuld, Rückzahlung am Ende; die Gegenleistung ersetzt die Zinsen).
- Vertragsbruch **gestuft**: Mahnung mit Nachfrist → Ausgleichszahlung → nach wiederholtem Bruch Kündigung mit
  Rückforderung.
- Verhandlung: **Auswahl aus 2–3 Paketen**, kein Freitext.
- Katalog: **alle vier Gruppen** – Waren & Milch, Geld-Rendite, Tiere & Auflagen, Rechte & Rollenspiel.
- Häufigkeit: **höchstens ein Angebot je FS25-Jahr, höchstens 2 laufende Investoren**.

### R32-I1 Anlass und Investor

- [ ] Prüfung einmal je FS25-Monat, höchstens ein Angebot je FS25-Jahr, höchstens `investor.max-active` (**2**) laufende
  Verträge. Die Wahrscheinlichkeit steigt nach Formel mit:
  - Dorf-Ansehen (`VillageRelation`),
  - Ergebnis des letzten Hofberichts (R3-K3, `FarmReport`),
  - Bonitätsscore (`CreditScoringService`) und Zahlungsmoral (keine offenen Verzüge, keine laufende Fälligstellung),
  - Meilensteinen (R3-T1, z. B. 100 ha, Rekordernte).

  Ohne Hofbericht (erstes Jahr) gibt es kein Angebot. Das Ganze ist je Spielstand abschaltbar (Einstellungen →
  Ereignisse).
- [ ] Der Investor (`INVESTOR`) wird beim Angebot angelegt. Seine **Art** (Konfig-Liste) bestimmt, welche
  Gegenleistungen er bevorzugt und welche Rendite er erwartet, z. B.:

  | Art | bevorzugte Gegenleistung |
  | --- | --- |
  | Agrarfonds | Geld-Rendite (R1/R2), Wachstumsziel (A4) |
  | Regionale Lebensmittelkette | Ware (W1/W2), Vorkaufsrecht (P2), Namensnennung (P5) |
  | Molkerei-Unternehmer | Milch (W3), Tierwohl (A2) |
  | Brauerei / Ölmühle | Ware einer Sorte (W1/W2), Anbaupflicht (A3) |
  | Energieunternehmen | Anbaupflicht (A3, z. B. Mais/Silage), Geld-Rendite (R2) |
  | Privatinvestorin / Familienstiftung | Geld-Rendite (R1), Ferienwohnung (P3), Hoffest (P4) |

- [ ] Kontakt per Mail ins Postfach oder als Anruf (`Channel.CALL`). Ein verpasster oder abgelehnter Anruf lässt das
  Thema offen (`openTopic`), das Angebot kommt dann zusätzlich als Mail.
- [ ] Summe zwischen `investor.amount-min` (**250.000 €**) und `investor.amount-max` (**2.500.000 €**), in Schritten;
  wie sie zur Hofgröße passt, siehe [Offene Punkte](#offene-punkte-vorschläge-zur-bestätigung).

**Beleg:** – (Backend; Daten aus R3-K3, R3-T1, Kreditbewertung, Dorf-Ansehen).

### R32-I2 Angebot mit 2–3 Paketen

- [ ] Der Investor legt für **dieselbe Summe** 2–3 Pakete vor. Jedes Paket nennt:
  - Kapitalart (stille Beteiligung oder Nachrangdarlehen),
  - Laufzeit in FS25-Jahren (Konfig-Spanne),
  - eine Hauptleistung und 0–2 Nebenleistungen aus dem Katalog (I3), passend zur Art des Investors und zum Hof (nur
    was der Hof liefern kann: Silo für die Sorte, Stall mit Milch, Tiere des Untertyps, Ferienwohnung eingerichtet …).
- [ ] **Bewertung nach Formel:** Wert aller Gegenleistungen über die Laufzeit ≈ Summe × Zielrendite der Art × Jahre.
  Ware wird zum heutigen besten Marktpreis bewertet, Auflagen und Rechte mit festen Konfig-Werten. So sind die Pakete
  untereinander gleichwertig, aber unterschiedlich belastend.
- [ ] Formular in der Bank-App, neuer Bereich **„Investoren“**: Pakete nebeneinander, Knopf „Annehmen“ je Paket,
  „Ablehnen“. Antwortfrist in Spieltagen; Ablehnen kostet nichts, ein ignoriertes Angebot etwas Vertrauen beim Investor.
- [ ] Annahme → `MONEY_TRANSACTION` `INVESTOR_CAPITAL` über die Summe, Vertrag `investor_contract` mit Startmonat,
  Tagebucheintrag, Mail der Bankberaterin (Einordnung, kein Rat).

**Beleg:** – (`MONEY_TRANSACTION` gibt es seit V1).

### R32-I3 Katalog der Gegenleistungen

Jede Gegenleistung hat einen Abrechnungszeitraum (**Monat** oder **FS25-Jahr**) und wird nur aus Spielwerten geprüft,
die das Tool heute schon bekommt – einzige Ausnahme ist die Milch (W3, neuer Export aus Q1).

**Waren & Milch**

| Typ | Gegenleistung | Erfüllung | Beleg |
| --- | --- | --- | --- |
| W1 | Gesamtmenge über die Laufzeit, z. B. 100.000 l Raps in 3 Jahren, mit Mindestmenge je Jahr | Knopf „Liefern“ mit Menge (Teillieferung erlaubt): `STORAGE_TRANSFER OUT` **ohne** Geld | ✅ wie R3-H4; `STORAGE_TRANSFER` ohne eigene Buchung gibt es seit R31-A1 (Ernte des Lohnunternehmers) |
| W2 | Feste Menge je Monat, z. B. 20.000 l Weizen | wie W1, je Monat | ✅ wie W1 |
| W3 | Milch je Monat, z. B. 5.000 l (auch Ziegen- oder Büffelmilch, je nach Stall) | Knopf „Liefern“: `HUSBANDRY_TRANSFER` aus dem gewählten Stall | ✅ Q1 (`getHusbandryFillLevel`, `removeHusbandryFillLevel`), 🟡 siehe Q1 |

**Geld-Rendite**

| Typ | Gegenleistung | Erfüllung | Beleg |
| --- | --- | --- | --- |
| R1 | Gewinnanteil: x % des Betriebsergebnisses eines FS25-Jahres, nach dem Jahresabschluss | automatisch nach dem Hofbericht als `INVESTOR_PAYOUT`; kein Gewinn = keine Zahlung | – (Hofbericht R3-K3 aus dem Journal R2-B) |
| R2 | Feste Ausschüttung: y % der Summe je FS25-Jahr | automatisch zum Jahreswechsel als `INVESTOR_PAYOUT` | – |

**Tiere & Auflagen**

| Typ | Gegenleistung | Prüfung | Beleg |
| --- | --- | --- | --- |
| A1 | Tiere liefern, z. B. 10 Rinder je Jahr | Knopf „Liefern“: `ANIMAL_TRANSFER OUT` ohne Geld. Das Alter ist bei der Entnahme nicht wählbar (`changeNumAnimals` je Untertyp), „Kälber“ heißt daher nur „Tiere dieses Untertyps“ | ✅ wie R31-A3 |
| A2 | Tierwohl: Gesundheit der Ställe im Monatsmittel ≥ Schwelle | monatlich aus `husbandries[].health` | ✅ R2-A7 (`PlaceableHusbandryAnimals:updateInfo`) |
| A3 | Anbaupflicht: x ha Kultur y je Erntejahr | aus `fields[]` (Kultur, Fläche) wie beim Sammelantrag | ✅ R2-C1 / R31-B1 |
| A4 | Wachstumsziel: Hof-Fläche ≥ n ha oder Tierbestand ≥ n bis zu einem Monat | aus `fields[].hectares` bzw. `assets.animals[].count` | ✅ R2-C1, V1 |

**Rechte & Rollenspiel**

| Typ | Gegenleistung | Prüfung | Beleg |
| --- | --- | --- | --- |
| P1 | Vetorecht beim Feldverkauf | Verkauf in der Flurkarte nur nach Zustimmung (Mail, Knopf, wie R3-K1). Verkauf im Spielmenü (Erkennung R2-D2) = Vertragsbruch | – (R2-D2, Muster R3-K1) |
| P2 | Vorkaufsrecht: jährlich bis x l Sorte y zum Festpreis (Marktpreis − Abschlag) | Der Investor fragt an, du lieferst wie G2 (`STORAGE_TRANSFER OUT` + `GOODS_SALE`). Ablehnen = Bruch | ✅ wie R3-H4 |
| P3 | Ferienwohnung: n Monate je Jahr für den Investor | In diesen Monaten keine Gäste und kein `GUEST_INCOME` (R31-D6); nur wenn die Wohnung eingerichtet ist | – (R31-D6) |
| P4 | Hoffest bzw. Besuch: n Termine je Jahr | Kalender-Vorgang mit Zusage per Knopf (Muster Stammtisch / Generalversammlung); Fernbleiben = Bruch | – (R31-D3/D7) |
| P5 | Namensnennung | Das Dorfblatt meldet die Beteiligung (öffentliche Tat); je nach Art des Investors kleiner Bonus oder Malus beim Dorf-Ansehen (z. B. Energieunternehmen) | – (R31-D1, `PublicActionEvent`) |

- [ ] Ware und Tiere aus W1/W2/A1 bringen dem Hof **kein Geld**: Der Wert ist die Gegenleistung. Im Journal erscheint
  nichts; der Hofbericht führt die gelieferten Mengen im Abschnitt „Investoren“.
- [ ] Werte (Spannen, Schwellen, Bewertungssätze) unter `rpsim.formulas.investor.*`.

### R32-I4 Erfüllung prüfen und gestufter Vertragsbruch

- [ ] Das Backend prüft je Abrechnungszeitraum (Monatswechsel bzw. Jahreswechsel, `GameMonthPassedEvent`) Soll und Ist
  jeder Gegenleistung. Offene Lieferungen stehen vorher in „Aufgaben“ und im Kalender; eine Woche vor Ende des Monats
  kommt eine Erinnerung (Mail, optional `NOTIFICATION`).
- [ ] **Stufe 1 – Mahnung:** Fehlt etwas, kommt eine Mahnung mit Nachfrist (`investor.grace-days`). Liefert der Spieler
  nach, ist der Bruch erledigt (Vertrauen −, klein).
- [ ] **Stufe 2 – Ausgleichszahlung:** Nach der Nachfrist zahlt der Hof einen Ausgleich als `INVESTOR_COMPENSATION`:
  - Ware/Milch/Tiere: Fehlmenge × heutiger Marktpreis (Tiere: Tierwert des Spiels) × `compensation-markup`,
  - Auflagen und Rechte (A2–A4, P1–P5): fester Betrag je Typ (Konfig).

  Die Fehlmenge ist damit abgegolten.
- [ ] **Stufe 3 – Kündigung:** Ab dem n-ten Bruch in der Laufzeit (`investor.breaches-to-terminate`) kündigt der Investor.
  Die Rückforderung wird sofort fällig (Beteiligung: Rückkauf, Darlehen: Rückzahlung), Höhe siehe
  [Offene Punkte](#offene-punkte-vorschläge-zur-bestätigung). Der Fall erscheint wie ein Bescheid
  (`INVESTOR_CLAIM`, Zahlen per Knopf, Frist). Reicht das Geld nicht, gilt das vorhandene Verzugs-Muster (Mahnung,
  Vertrauen, Bank erfährt davon).
- [ ] Ein Vertragsbruch und eine Kündigung sind öffentlich, wenn der Investor mit Namensnennung (P5) im Dorfblatt stand.

**Beleg:** – (Backend; Prüfdaten siehe I3).

### R32-I5 Laufzeitende

- [ ] Stille Beteiligung: Rückkauf der Anteile zum **Nennwert** im letzten Monat der Laufzeit als `INVESTOR_REPAYMENT`.
- [ ] Nachrangdarlehen: Rückzahlung der Summe im letzten Monat der Laufzeit als `INVESTOR_REPAYMENT` (endfällig).
- [ ] Ankündigung drei Monate vorher (Mail, Kalender, Liquiditätsplanung). Ohne Bruch in der Laufzeit bietet der
  Investor optional eine Verlängerung mit neuen Paketen an (zählt nicht gegen „ein Angebot je Jahr“).
- [ ] Reicht das Geld zum Termin nicht, wie Stufe 3 in I4.

**Beleg:** – (Backend).

### R32-I6 Wirkung auf Bank, Planung und Dorf

- [ ] **Bank:** Ein Nachrangdarlehen zählt in `CreditScoringService` zur Schuld (heute `loanDebt + vanilla`). Eine stille
  Beteiligung zählt nicht als Schuld, das eingezahlte Geld hebt also die Eigenkapitalquote. Die Bankberaterin
  kommentiert neue Investoren im Jahresgespräch (R3-K3).
- [ ] **Liquiditätsplanung (R3-K2):** Rückkauf/Rückzahlung, feste Ausschüttungen (R2) und Ausgleichszahlungen als bekannte
  Posten; der Gewinnanteil (R1) als „erwartet“.
- [ ] **Hofbericht (R3-K3):** Abschnitt „Investoren“ mit Summe, Kapitalart, gelieferten Mengen, Zahlungen und Brüchen.
- [ ] **Chronik (R3-T2):** Abschluss und Ende eines Investorenvertrags als Eintrag.

**Beleg:** – (Backend und Oberfläche).

**Akzeptanz I:** Nach einem guten Jahr ruft ein Investor an und bietet 1.000.000 € in drei Paketen. Nach „Annehmen“
ist das Geld gebucht. Bei „5.000 l Milch je Monat“ nimmt „Liefern“ die Milch aus dem Kuhstall. Fehlt sie am Monatsende,
kommt eine Mahnung, nach der Nachfrist eine Ausgleichszahlung, beim dritten Bruch die Kündigung mit Rückforderung. Am
Laufzeitende kauft der Hof die stille Beteiligung zum Nennwert zurück. Ein Nachrangdarlehen senkt die
Eigenkapitalquote der Bank, eine stille Beteiligung hebt sie.

---

## Offene Punkte (Vorschläge zur Bestätigung)

Nicht durch Belege oder Entscheidungen gedeckt. Bis zur Entscheidung gilt der Vorschlag nur als Platzhalter in der
Konfig. Eingetragen in `QUESTIONS.md` mit Status `open`.

| Punkt | Frage | Vorschlag |
| --- | --- | --- |
| G1 ✅ 08.10.2026 wie vorgeschlagen | Sorten und Mengenspannen | `WHEAT`, `BARLEY`, `CANOLA`, `SUNFLOWER`, `SOYBEAN`, `MAIZE`, `POTATO`, `SUGARBEET`; je Sorte 50.000–500.000 l in Schritten von 10.000 l (Kartoffeln/Zuckerrüben 50.000–300.000 l). |
| G1 ✅ 08.10.2026 wie vorgeschlagen | Häufigkeit, Antwortfrist, Höchstzahl | höchstens 1 Anfrage je Monat (Wahrscheinlichkeit 0,3 × Ablehnungsfaktor, Faktor 0,75 je Ablehnung, mindestens 0,1), 5 Tage Antwortfrist, höchstens 3 offene Termin-Aufträge. |
| G1 ✅ 08.10.2026: **kein Filter** | Nur Sorten, die der Hof lagern oder anbauen kann? | Ja: eigenes Silo für die Sorte (`tradeStorage`) **oder** die Kultur steht im laufenden/letzten Erntejahr auf einem eigenen Feld. Sonst würden Anfragen kommen, die der Hof nie erfüllen kann. |
| G2 ✅ 08.10.2026 wie vorgeschlagen | Dorf-Ansehen für Großaufträge? | Nein, Geschäftssache. Nur Vertrauen beim Großabnehmer. |
| G3 ✅ 08.10.2026 wie vorgeschlagen | Vorlauf | 1–12 Monate (wie Vorkontrakt). |
| G4 ✅ 08.10.2026 wie vorgeschlagen | Vertrauen | +3 bei voller Lieferung, −5 bei Fehlmenge (wie R3-M2). |
| I1 | Summe im Verhältnis zur Hofgröße | Summe höchstens 50 % des Hofvermögens (Bank-Sicht), gerundet auf 50.000 €, innerhalb 250.000–2.500.000 €. Liegt die Grenze unter 250.000 €, kommt kein Angebot. |
| I2 | Laufzeit und Zielrendite | 2–5 FS25-Jahre; Zielrendite je Art 6–12 % p. a. |
| I3 | Buchungsklasse des Gewinnanteils / der Ausschüttung (`INVESTOR_PAYOUT`) | **Entschieden 08.10.2026:** `FINANCING` (Gewinnverwendung, mindert nicht den Gewinn und nicht die Steuer). |
| I4 | Nachfrist, Aufschlag, Anzahl Brüche | 5 Spieltage Nachfrist, Ausgleich = Fehlmenge × Marktpreis × 1,25, Kündigung beim 3. Bruch in der Laufzeit. |
| I4 | Höhe der Rückforderung bei Kündigung („anteilig“) | Volle Summe (Nennwert bzw. Restschuld) sofort fällig, plus offene Ausgleichszahlungen. Alternative: Summe × Restlaufzeit / Laufzeit (früher Bruch teurer, später billiger). |
| I6 | Zählt ein Nachrangdarlehen bei der Bank voll als Schuld? | Ja, voll (Faktor 1,0, konfigurierbar). |

---

## Bewusst nicht aufgenommen

| Idee | Warum nicht |
| --- | --- |
| Großaufträge oder Investoren-Lieferungen aus Produktionen (Mehl, Brot, Käse …) | Die Klasse `ProductionPoint` ist in der LUADOC weiterhin nicht dokumentiert (nur `PlaceableProductionPoint` ruft `ProductionPoint.new` auf). Wie in V3: kein belegter Schreibzugriff. |
| Eier, Wolle und andere Paletten-Ware aus dem Stall | Diese Ware erzeugt der Stall als Paletten (`PlaceableHusbandryPallets`), also als Objekte, nicht im Stall-Lager. Objektlager sind seit V3 ausgeklammert. |
| Großauftrag mit Abholung aus dem Silo zum Termin | Entscheidung 08.10.2026: Termin = Hinfahren zur Verkaufsstelle. Sofortlieferung aus dem Silo bleibt. |
| Teil-Sofortlieferung (Rest zum Termin) | Ein Festpreis gilt je Verkaufsstelle und Sorte für einen Monat; zwei Preise für einen Auftrag würden die Abrechnung verdoppeln. Der Spieler wählt sofort **oder** Termin. |
| Freitext-Verhandlung mit dem Investor | Entscheidung 08.10.2026: Auswahl aus 2–3 Paketen. Grundsatz: Beträge und Mengen nur über Formulare. |
| Investor bekommt ein eigenes Feld im Spiel | Felder gehören im Spiel einer Farm oder keinem (NPC). Eine Investoren-Farm ist nicht belegt. Ersatz: Vetorecht (P1) und Vorkaufsrecht (P2). |
| Altersgenaue Tierlieferung („nur Kälber“) | `ANIMAL_TRANSFER OUT` zieht Tiere je Untertyp über `cluster:changeNumAnimals` ab; eine Auswahl nach Alter ist dort nicht geprüft. |

---

## Quellen

- FS25-Quellcode-Dump (`dataS`): <https://github.com/Dukefarming/FS25-lua-scripting>. Genutzt wurden
  `animals/husbandry/placeables/PlaceableHusbandry.lua` (`spec.storage`, Ab- und Beladestation,
  `getHusbandryFillLevel`, `getHusbandryCapacity`, `removeHusbandryFillLevel`, Storage-Erweiterungen in Reichweite) und
  `animals/husbandry/placeables/PlaceableHusbandryMilk.lua` (`spec.fillTypes`, `updateOutput` mit
  `addHusbandryFillLevelFromTool`).
- FS25 Community LUADOC: <https://github.com/umbraprior/FS25-Community-LUADOC> (Stand 16.08.2026). Genutzt wurden:
  - `script/Specializations/PlaceableHusbandry.md` (`getHusbandryFillLevel`, `getHusbandryCapacity`,
    `getHusbandryFreeCapacity`, `removeHusbandryFillLevel`, `addHusbandryFillLevelFromTool`)
  - `script/Specializations/PlaceableHusbandryMilk.md` (`spec.fillTypes` aus `subType.output.milk`, `updateInfo`)
  - `script/Specializations/PlaceableHusbandryWater.md`, `PlaceableHusbandryStraw.md` (Spiel nutzt
    `removeHusbandryFillLevel` mit Restmenge)
  - `script/Specializations/PlaceableHusbandryPallets.md` (Paletten-Ware)
  - `script/Specializations/PlaceableProductionPoint.md` (nur `ProductionPoint.new`, Klasse selbst undokumentiert)
- Bestehende Projekt-Doku und Code: [`ROADMAP_V3.md`](ROADMAP_V3.md) (H, K, M, T), [`ROADMAP_V3.1.md`](ROADMAP_V3.1.md)
  (A3, B1, D1, D3, D6, D7), `docs/dev/bridge-protocol.md` (`calendar`, `sellPoints`, `tradeStorage`,
  `STORAGE_TRANSFER`, `PRICE_EVENT`, `contractReports`), `docs/architecture/call-state-machine.md`,
  `mod/FS25_RPSim/src/import/Instructions.lua` und `PriceEvents.lua`, `backend/.../neighbor/FarmShopService.java`,
  `backend/.../market/ForwardContractService.java`, `backend/.../credit/CreditScoringService.java`,
  `backend/.../time/GameTime.java`.
