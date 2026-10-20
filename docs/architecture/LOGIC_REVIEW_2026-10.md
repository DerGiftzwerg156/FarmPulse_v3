# Logik-Review FarmPulse (Stand 16.10.2026, Commit `4d541a9`)

Untersucht wurden Backend (Fachlogik, Bridge, Scheduler), Lua-Mod (Import, Batch-Prüfung, GameAdapter), Frontend
(Angular) und Bridge-Simulator auf **fachlich falsches Verhalten**: Geld oder Ware geht verloren oder wird doppelt
gezählt, Fristen laufen falsch, Zustände bleiben hängen. Architektur, Sicherheit und Performance stehen in
[TECHNICAL_REVIEW_2026-10.md](TECHNICAL_REVIEW_2026-10.md) und werden hier nicht wiederholt.

Pfade sind relativ zu `backend/src/main/java/de/farmpulse/rpsim/`, wenn nichts anderes dasteht. Zeilennummern beziehen
sich auf Commit `4d541a9`.

## Arbeitsweise und Regeln dieses Berichts

- **Belegt** heißt: Die Aussage ist am Code nachgelesen. H1 ist zusätzlich mit einem temporären Test reproduziert
  (Test danach entfernt). Alle anderen Befunde stammen aus Code-Lektüre mit nachverfolgtem Aufrufpfad.
- **Dokumentierte Fakten** zitieren ausschließlich Owner-Entscheidungen aus [QUESTIONS.md](QUESTIONS.md), die
  Roadmaps, [bridge-protocol.md](../dev/bridge-protocol.md) oder die
  [configuration-reference.md](../dev/configuration-reference.md).
- Wo es **keine dokumentierte Entscheidung** gibt, steht eine **Offene Frage an den Owner**. Der Umsetzungsweg nennt
  dann die Optionen, trifft aber keine Wahl. Diese Punkte sollten erst nach der Antwort umgesetzt werden.
- Alle FS25-Verhaltensannahmen, die weder im Code noch in der Doku belegt sind, sind als **unbelegt** markiert.

## Legende

| Schwere | Bedeutung |
| --- | --- |
| **Hoch** | Spieler verliert Geld, Ware oder Hinweise ohne eigenes Zutun; tritt im normalen Spiel auf |
| **Mittel** | Falsche Zahlen oder Fristen, Doppelzählung, Ausnutzbarkeit; tritt in bestimmten Spielsituationen auf |
| **Niedrig** | Randfall, Robustheit oder Unschärfe ohne dauerhaften Schaden |

---

## 1. Übersicht

| Nr. | Schwere | Kurzbeschreibung | Fundstelle | Owner-Frage nötig? |
| --- | --- | --- | --- | --- |
| H1 | Hoch | Rewind sendet bei Warengeschäften nur das Geld erneut, nicht die Ware | `bridge/RewindService.java:65,167` | ja |
| H2 | Hoch | Benachrichtigungen mit Level `WARNING` werden vom Mod abgelehnt | 3 Services | ja (welches Level) |
| M3 | Mittel | Lohnunternehmer-Ernte kann verloren gehen (Ernte vor Lagerprüfung) | `OutboxService.fieldWorkDeal`, Mod `game/GameAdapter.lua` | ja (FS25-Fakt) |
| M4 | Mittel | Falsches Jahr beim Aufholen mehrerer Monate | 7 Stellen | nein |
| M5 | Mittel | Monatsbasierte Fristen werden bei Kalenderwechsel nicht umgerechnet | 7 Stellen | nein |
| M6 | Mittel | Leasing wird in der Bonitätsprüfung doppelt gezählt | `credit/CreditScoringService.java:125` | ja |
| M7 | Mittel | Liquiditätsplan zählt Verpachtungseinnahmen doppelt | `finance/LiquidityPlanService.java:196`, `application.yml:1017` | nein |
| M8 | Mittel | Zusätzlicher Gehalts-Malus beim automatischen Teil-Nachzahlen | `payroll/PayrollScheduler.java:106` | Bestätigung |
| M9 | Mittel | Vertrauen per Antwort/Anruf ohne Pacing beliebig steigerbar | `communication/ConversationService.java:97`, `communication/CallService.java:120` | ja |
| M10 | Mittel | Abbuchungen ohne Folgebehandlung bei Ablehnung | 5 Stellen | teilweise |
| M11 | Mittel | Liquiditätssperre bleibt nach einem Rewind hängen | `bridge/LiquidityService.java:71,83` | nein |
| L12 | Niedrig | Fehlgeschlagenes Schreiben von `instructions.xml` wird nicht wiederholt | `bridge/BridgeSyncService.java:389` | nein |
| L13 | Niedrig | Ack ohne `status` erzeugt eine NullPointerException (Giftpille) | `bridge/BridgeSyncService.java:278` | nein |
| L14 | Niedrig | Investitionsförderung: Verkauf an Nachbarn löst später falsche Rückforderung aus | `InvestmentGrantService` | ja |
| L15 | Niedrig | Rewind-Korrektur abgelehnter Lieferungen nur für W1 | `investor/InvestorService.java:740` | nein |
| L16 | Niedrig | Mod: Wiederausführung nach 30 Spieltagen bei veralteter Anweisungsdatei | Mod `bridge/Config.lua:18` | nein |
| L17 | Niedrig | Rückbuchung einer Rate bei gekündigtem Kredit | `credit/LoanService.java:342` | ja |
| L18 | Niedrig | Fristen nur beim Tageswechsel geprüft | Auktion, Sonderangebot | nein |
| L19 | Niedrig | Abgelehnter `PRICE_EVENT` lässt Vertrag/Angebot offen | `ForwardContract`, Sonderangebot | nein |
| L20 | Niedrig | Wegziehender Verpächter-NPC: Pacht läuft weiter | `VillageRotationService` | ja |
| L21 | Niedrig | Viehhändler-Prämie zählt Geburten und Todesfälle mit | `LivestockService` | ja |

**Frontend:** Außer dem bekannten Punkt F-1 aus dem technischen Review (Anfragen werden nicht abgebrochen) wurden keine
Logikfehler gefunden. `ng test` läuft grün (mit Node 24.15.0, weil die Projektvorgabe Node ≥ 22.22.3 verlangt).

---

## 2. Hohe Schwere

### H1 – Rewind sendet bei Warengeschäften nur das Geld erneut

**Fundstelle:** `bridge/RewindService.java:65` (`RESENDABLE`), `:71` (`WHOLE_BATCH`), `:167` (`candidates`).

**Befund (per Test reproduziert):** Ein Warengeschäft geht als Batch aus `STORAGE_TRANSFER` und `MONEY_TRANSACTION`
an den Mod (`OutboxService.storageDeal`). Nach „Laden ohne Speichern“ wählt `candidates()` aus den angewendeten
Anweisungen nur Typen aus `RESENDABLE` aus. Dazu kommen ganze Batches, die einen `WHOLE_BATCH`-Typ enthalten, und
`STORAGE_TRANSFER` mit `relatedEntityType = INVESTOR_DELIVERY`. Ein gewöhnlicher `STORAGE_TRANSFER` steht in keiner
dieser Mengen. Folge: Das **Geld wird erneut gebucht, die Ware nicht**. Im Test war der Transfer `APPLIED` und das
erneut gesendete Geld `PENDING`.

Betroffen sind alle Aufrufer von `storageDeal`: `NeighborTradeService` (Warenhandel mit Nachbarn), `FarmShopService`,
`BulkOrderService.deliver` und der P2-Ankauf in `InvestorService`. Bei einem Verkauf bekommt der Spieler das Geld
also erneut, obwohl die Ware nach dem Neuladen wieder im Silo liegt. Bei einem Kauf zahlt er erneut, ohne die Ware zu
bekommen.

**Dokumentierte Fakten:**
- QUESTIONS.md, 2026-10-02 R31-A: `FIELD_WORK` und `ANIMAL_TRANSFER` werden „together with their whole batch (money
  and storage partners)“ erneut gesendet.
- QUESTIONS.md, 2026-10-08 R32-I3 / R32-Q1: `STORAGE_TRANSFER` einer W1-/W2-Lieferung wird erneut gesendet. Das
  Backend zählt eine Lieferung nur einmal, beim ersten Ack.
- Für den **normalen Warenhandel** (`storageDeal`) gibt es **keine** dokumentierte Entscheidung.

**Offene Frage an den Owner:** Soll ein Warengeschäft (Nachbarhandel, Hofladen, Großauftrag, P2-Ankauf) nach einem
Rewind **komplett** erneut gesendet werden (Ware und Geld), oder **gar nicht** (Tool-Stand bleibt, Spieler entscheidet
wie bei `AWAITING_PLAYER`)? Ein Zustand „nur Geld“ ist in keinem Fall gewollt. Er widerspricht dem
Alles-oder-nichts-Prinzip der Batches (bridge-protocol.md, Batch-Abschnitt).

**Umsetzungsweg (nach der Antwort):**
1. Option „komplett“: in `candidates()` jede `batchId` zu `wholeBatches` hinzufügen, deren Batch einen
   `STORAGE_TRANSFER` enthält. Am einfachsten geht das, indem `STORAGE_TRANSFER` in `WHOLE_BATCH` aufgenommen wird.
   Dann wird der `INVESTOR_DELIVERY`-Sonderfall für Transfers in einem Batch überflüssig. Für Transfers ohne Batch
   muss er bleiben.
2. Option „gar nicht“: in `candidates()` Batches, die einen nicht erneut sendbaren `STORAGE_TRANSFER` enthalten, ganz
   aus der Kandidatenliste entfernen, also auch das Geld.
3. In beiden Fällen vorher prüfen, wie die Ack-Handler der vier Services einen zweiten `APPLIED`-Ack zum selben
   Geschäft verarbeiten. Laut R32-I3 muss „nur einmal zählen“ gelten, sonst entstehen doppelte Tagebuch- und
   Vertrauenseffekte.
4. Tests in `RewindServiceTest`:
   - Batch `STORAGE_TRANSFER` + `MONEY_TRANSACTION` → beide oder keiner in `candidates()`.
   - Bestehender Test für `FIELD_WORK` bleibt grün.
   - Integrationstest: Nachbarverkauf, Rewind, Resend, zweiter Ack → Geschäft zählt einmal.
5. bridge-protocol.md (Rewind-Abschnitt) und QUESTIONS.md um die Entscheidung ergänzen.

### H2 – Benachrichtigungen mit Level `WARNING` werden abgelehnt

**Fundstelle:**
- `farmwork/MachineLoanService.java:684`
- `vehicle/VehicleTradeService.java:431`
- `villagelife/CropDamageService.java:195`

**Befund:** Alle drei Stellen rufen `outbox.notification(sg, text, "WARNING", …)` auf. Der Mod prüft das Level in
`mod/FS25_RPSim/src/import/Instructions.lua:307` gegen `NOTIFICATION_LEVELS = { INFO, OK, CRITICAL }` (Zeile 53) und
lehnt mit `unknown level WARNING` ab. Der Hinweis erscheint nie im Spiel. Laut Protokoll erzeugt eine fehlgeschlagene
Benachrichtigung keinen Hinweis, der Verlust bleibt also unbemerkt.

**Dokumentierte Fakten:**
- bridge-protocol.md:735: `level` ∈ `INFO, OK, CRITICAL` (`FSBaseMission.INGAME_NOTIFICATION_*`); „a failure
  creates no notice“.
- Das Schema `tools/bridge-simulator/schema/instructions.schema.json` erlaubt dieselben drei Werte.
- Für vergleichbare Warnungen nutzt das Protokoll `CRITICAL` (bridge-protocol.md:180, :201, :209, :784).

**Offene Frage an den Owner:** Welches Level sollen die drei Hinweise bekommen?
- Ausleihmaschine: Rückgabe fällig.
- Gebrauchtmaschine: Hinweis.
- Flurschaden: „Hinweistext“ der Konfiguration.

Zur Wahl stehen `CRITICAL` (wie bei den übrigen Warnungen im Protokoll) und `INFO`.

**Umsetzungsweg:**
1. In den drei Aufrufen `"WARNING"` durch das gewählte Level ersetzen.
2. Gegen Wiederholung: den `String level` in `OutboxService.notification` durch ein Enum
   `NotificationLevel { INFO, OK, CRITICAL }` ersetzen. Dann prüft der Compiler das Level. Alle Aufrufer anpassen; die
   Serialisierung bleibt der Enum-Name.
3. Test: ein Unit-Test, der den Payload einer Benachrichtigung gegen `instructions.schema.json` validiert. Den
   Validator des Simulators wiederverwenden, falls er im Backend-Test erreichbar ist; sonst ein Test, der alle
   Enum-Werte gegen die Schema-Enum-Liste vergleicht.

---

## 3. Mittlere Schwere

### M3 – Lohnunternehmer-Ernte kann verloren gehen

**Fundstelle:**
- `bridge/OutboxService.java:279` (`fieldWorkDeal`)
- `farmwork/ContractorWorkService.java:589` (`onInstructionFailed`)
- Mod `mod/FS25_RPSim/src/game/GameAdapter.lua:1122` (`fieldWork`) und `:1733` (`transferStorage`)

**Befund:**
1. Ein Erntebatch enthält zuerst `FIELD_WORK` (HARVEST) und danach `STORAGE_TRANSFER IN`.
2. Der Mod setzt beim Ernten den Feldzustand auf „abgeerntet“, ohne die Lagerkapazität zu prüfen.
3. Scheitert danach `transferStorage` mit `NO_CAPACITY`, bricht der Batch ab. Bereits angewendete Glieder werden
   laut Protokoll nicht zurückgerollt, das Feld ist also abgeerntet und die Ernte nirgends.
4. Im Backend ruft `onInstructionFailed` `cancel` auf und meldet „nichts gebucht“.

Die Reservierung `NeighborTradeService.freeCapacity` (Zeile 146) rechnet je Fruchtart.

**Dokumentierte Fakten:**
- bridge-protocol.md (Batches): Vorprüfung aller Glieder, danach bricht ein scheiterndes Glied den Rest ab, ohne
  Rücknahme der bereits angewendeten.
- QUESTIONS.md 2026-10-02 R31-A1: Ertragsformel und Arbeitstag; zur Kapazität vor der Ernte gibt es keine Aussage.
- **Unbelegt:** Ob sich in FS25 mehrere Fruchtarten die Kapazität eines Silos teilen, ist weder im Code noch in der
  Doku belegt. Davon hängt ab, ob die Reservierung je Fruchtart ausreicht.

**Offene Frage an den Owner:** Ist in den unterstützten Silos die Kapazität geteilt (alle Fruchtarten zusammen) oder
getrennt je Fruchtart? Und soll eine Ernte ohne Platz **vorher abgelehnt** werden (Feld bleibt stehen, Auftrag wird
neu terminiert oder storniert), oder soll die Ernte **trotzdem stattfinden** und der Rest verfallen?

**Umsetzungsweg:**
1. **Mod (unabhängig von der Antwort, verhindert stillen Verlust):** In der Batch-Vorprüfung
   (`checkBatchFunds` bzw. `batchApplicable` im Import) für jeden `STORAGE_TRANSFER IN` die freie Kapazität prüfen und
   bei Mangel den ganzen Batch vor dem ersten Glied mit `NO_CAPACITY` ablehnen. So bleibt das Feld unberührt.
2. Die Kapazitätsermittlung im Mod muss dieselbe Rechnung verwenden wie `transferStorage`. Sie in eine gemeinsame
   Funktion auslagern.
3. **Backend:** `ContractorWorkService.onInstructionFailed` unterscheidet bereits „nichts gebucht“. Nach der
   Owner-Antwort entweder stornieren (heute) oder neu terminieren.
4. Je nach Antwort zu „geteilt“: `NeighborTradeService.freeCapacity` auf Gesamtkapazität umstellen.
5. Tests:
   - Mod-Testsuite: Batch mit HARVEST + `STORAGE_TRANSFER IN` ohne Platz → `REJECTED`, Feldzustand unverändert.
   - Bridge-Simulator-Szenario dazu.

### M4 – Falsches Jahr beim Aufholen mehrerer Monate

**Fundstelle:**
- `tax/TaxService.java:157`
- `finance/FarmReportService.java:206`
- `authority/SocialInsuranceService.java:98`
- `authority/DirectPaymentService.java:207`
- `field/FieldMarginService.java:462`
- `cooperative/CooperativeService.java:211`, `:324`
- `village/VillageRotationService.java` (`resetBudget`)

**Befund:** `GameClockService` holt verpasste Tage einzeln nach. Die genannten Stellen lesen das Jahr aber aus dem
**neuesten Export** (`Savegame`- bzw. Snapshot-Kalenderfelder), nicht aus dem **gerade verarbeiteten** Monat. Holt das
Backend z. B. Februar bis April nach, sieht der Jahreswechsel am Ende von Februar bereits das Jahr des April-Exports.
Die Folge: Steuerjahr, Jahresbericht, Sozialversicherung, Flächenprämie, Feldbilanz, Genossenschafts-Dividende und
Dorfbudget werden dem falschen Jahr zugeordnet oder doppelt bzw. gar nicht ausgelöst.

**Dokumentierte Fakten:**
- Das Aufholen geschieht Tag für Tag (TECHNICAL_REVIEW_2026-10.md, Stärken; `GameClockService`).
- Das FS25-Jahr wechselt mit Periode 1 (März). Implementiert ist das in `LiquidityPlanService` und `CalendarText`.
- Die Kalenderverankerung steht in `GameTime.Anchor` (`calMonthIndex`, `calYear`, `calPeriod`).

**Umsetzungsweg:**
1. Einen zentralen Helfer `int yearOf(Savegame sg, long gameTime)` in `GameTimeService` (oder `GameTime`) anlegen.
   Er rechnet aus dem Anker und der Anzahl Monate zwischen Anker und `gameTime` das FS25-Jahr. Die bestehende
   Rechnung aus `LiquidityPlanService`/`CalendarText` wiederverwenden, nicht neu erfinden.
2. Alle sieben Stellen auf `yearOf(sg, <verarbeitete Spielzeit>)` umstellen. Die verarbeitete Spielzeit liefert der
   jeweilige Zyklus-Event (Tages- bzw. Monatswechsel).
3. Tests:
   - Parametrisierter Test für `yearOf` über einen Jahreswechsel bei 1 und 3 Tagen je Periode.
   - Je Service ein Aufhol-Test (Sprung über einen Jahreswechsel mit einem einzigen Export) → Buchung im richtigen
     Jahr.

### M5 – Monatsbasierte Fristen werden bei Kalenderwechsel nicht umgerechnet

**Fundstelle:**
- `employee/HiringService.java:393`: `apprenticeshipEndsAtGameTime` aktiver Mitarbeiter.
- `employee/HiringService.java:397`, `:438`: `contractEndsAtGameTime` von Saisonkräften.
- `HiringService.onCalendarChanged` (`:542`) behandelt nur `PENDING_START`.
- `contract/LivestockService.java:389`: Frist des Viehhändlers.
- `authority/InvestmentGrantService.java` Zeilen 220 und 261: `purchaseDeadline`, `bindingEnds`.
- `cooperative/CooperativeService.java:184`: `dueGameTime` der Kündigung.
- `authority/DirectPaymentService.java:220`: Antragsfrist.
- `farmwork/WinterServiceService.java:180`: Ablauf des Angebots.

**Befund:** Diese Zeitpunkte werden als „n Monate ab jetzt“ berechnet und als absolute Spielzeit gespeichert. Ändert
der Spieler die Tage je Periode, verschiebt `CalendarChangedEvent.remap` die Spielzeiten anderer Entitäten so, dass
der Monatsindex erhalten bleibt. Die genannten Felder hören nicht auf das Event. Folge: Ausbildung, Saisonvertrag,
Förderfrist usw. enden plötzlich viel früher oder später als in Monaten vereinbart.

**Dokumentierte Fakten:** T-08: Monatsbasierte Zeitpunkte werden bei einem Kalenderwechsel mit `remap` umgerechnet
(Javadoc von `CalendarChangedEvent`). Bestehende Listener, z. B. `PayrollScheduler.onCalendarChanged`, setzen das
schon um.

**Umsetzungsweg:**
1. Je betroffenem Service einen `@EventListener onCalendarChanged(CalendarChangedEvent e)` ergänzen, oder bestehende
   erweitern. Darin alle offenen Entitäten laden und das Feld mit `e.remap(...)` setzen. Bei `HiringService` zusätzlich
   `ACTIVE` behandeln, nicht nur `PENDING_START`.
2. Nur offene Zustände umrechnen (z. B. Förderung `APPROVED`, Kündigung offen, Angebot offen). Abgeschlossene
   Datensätze bleiben, wie sie sind.
3. Tests: je Service ein Test „Kalender 1 → 3 Tage je Periode → Frist liegt im gleichen Monat wie vorher“, analog zu
   den bestehenden T-08-Tests.
4. Prüfliste für neue Features in CONTRIBUTING oder `docs/dev` ergänzen: „monatsbasierte Spielzeit gespeichert? →
   `onCalendarChanged`“.

### M6 – Leasing doppelt in der Bonitätsprüfung

**Fundstelle:** `credit/CreditScoringService.java:125`

**Befund:** Die Kapitaldienstfähigkeit rechnet `existingInstallments += leasing`. Gleichzeitig ist `LEASING_COSTS`
im Journal als `OPERATING_EXPENSE` klassifiziert (`application.yml`, `rpsim.formulas.finance.categories`) und mindert
damit bereits den Cashflow, aus dem die Rate bedient wird. Leasing belastet die Prüfung also zweimal.

**Dokumentierte Fakten:**
- ROADMAP_V2.md R2-B3 (Zeile 170): „`LEASING_COSTS` je Periode ersetzt die Schätzung
  `FactsService.leasingCostPerMonth` in der Bonitätsprüfung.“
- Ob die Bank Leasing als Betriebsausgabe **oder** als Schuldendienst sehen soll, ist nicht festgelegt.

**Offene Frage an den Owner:** Soll Leasing in der Bonitätsprüfung als **laufende Belastung neben den Raten** zählen
(dann aus dem Cashflow herausrechnen) oder als **Betriebsausgabe** (dann nicht zusätzlich zu den Raten)?

**Umsetzungsweg:**
- Variante „Belastung“: Beim Cashflow für die Prüfung die Klasse von `LEASING_COSTS` wieder hinzurechnen, bevor die
  Raten abgezogen werden.
- Variante „Betriebsausgabe“: Zeile 125 entfernen.
- Test in `CreditScoringServiceTest`: Betrieb mit Leasing X → Leasing mindert das Ergebnis genau einmal.
- Entscheidung in QUESTIONS.md eintragen.

### M7 – Liquiditätsplan zählt Verpachtungseinnahmen doppelt

**Fundstelle:** `finance/LiquidityPlanService.java:196`; `application.yml:1017`
(`known-posting-categories`).

**Befund:** Der Plan setzt eine bekannte Buchung „Verpachtung“ (`LEASE_OUT`) ein. Die Einkommensschätzung nimmt das
Betriebsergebnis des Journals und rechnet nur die Kategorien aus `known-posting-categories` heraus. Dort fehlt
`RPSIM_LEASE_INCOME`. Die Pachteinnahme steckt also in der Schätzung **und** in der bekannten Buchung.

**Dokumentierte Fakten:** QUESTIONS.md 2026-10-01 R3-K2: Einkommensschätzung „with the categories the plan lists as
known postings added back“. Ziel ist, nichts doppelt zu zählen.

**Umsetzungsweg:**
1. `RPSIM_LEASE_INCOME` in `rpsim.formulas.liquidity-plan.known-posting-categories` (application.yml) **und** in den
   Defaults von `RpsimProperties` ergänzen. `RpsimPropertiesDefaultsTest` hält beide synchron.
2. Die Konfigurationsreferenz neu generieren bzw. den Eintrag ergänzen.
3. Prüfen, ob weitere bekannte Buchungen des Plans fehlen. Dazu jede Buchungsart in `LiquidityPlanService` mit der
   Liste abgleichen (z. B. Investoren-Zahlungen, Genossenschaft).
4. Test in `LiquidityPlanServiceTest`: Journal mit Pachteinnahme + aktiver Verpachtung → Monatssumme enthält die
   Pacht einmal.

### M8 – Zusätzlicher Gehalts-Malus beim automatischen Teil-Nachzahlen

**Fundstelle:** `payroll/PayrollScheduler.java:106` (`paySalaries`), `:144` (`payDue`).

**Befund:**
1. `payDue` setzt bei **jedem** gezahlten Monat `salaryOverdue = false`.
2. Bleibt danach ein Monat offen, sieht `paySalaries` `!emp.isSalaryOverdue()`.
3. Daraufhin vergibt es einen **neuen** Zufriedenheits-Malus und einen **neuen** `PaymentDelay`, obwohl der
   Mitarbeiter schon überfällig war.

Beispiel: Zwei Monate offen, Geld für einen → automatisch einer gezahlt → zweiter Malus.

**Dokumentierte Fakten:** QUESTIONS.md 2026-10-14 (Overdraft): „the button pays every overdue month the money
covers, months left unpaid stay overdue **without a second malus**“. `payOverdue` setzt das um. Für den automatischen
Weg gibt es keine eigene Aussage.

**Offene Frage (Bestätigung):** Gilt „kein zweiter Malus“ auch für die automatische Zahlung zum Monatswechsel? Die
dokumentierte Regel und die Gleichbehandlung beider Wege sprechen dafür.

**Umsetzungsweg:**
1. In `paySalaries` vor `payDue` `boolean wasOverdue = emp.isSalaryOverdue()` merken.
2. Malus und `delays.record` nur, wenn `stillDue && !wasOverdue`.
3. Danach `emp.setSalaryOverdue(stillDue)`, wie in `payOverdue`.
4. Test in `PayrollSchedulerTest`: zwei offene Monate, Deckung für einen → genau ein Malus über beide Zyklen.

### M9 – Vertrauen per Antwort/Anruf ohne Pacing

**Fundstelle:** `communication/ConversationService.java:97` (`reply`), `communication/CallService.java:120` (`say`).

**Befund:** Jede Antwort auf eine Nachricht bzw. jeder Satz im Anruf kann Vertrauen ändern, ohne Sperrfrist. Ein
Spieler kann so per Wiederholung Vertrauen „farmen“.

**Dokumentierte Fakten:**
- configuration-reference.md:448: `messages.pacing-cooldown-days` gilt ausdrücklich für **proaktive** Nachrichten.
- configuration-reference.md:1305: Dorf-Chat hat eigenes Pacing.
- Für Antworten und Anrufe gibt es keine Aussage.

**Offene Frage an den Owner:** Sollen Antworten und Anrufe ebenfalls gedrosselt werden (z. B. gleiche Sperrfrist je
Figur), oder ist das unbegrenzte Vertrauen über Gespräche gewollt?

**Umsetzungsweg (falls Drosseln):**
1. Die vorhandene Pacing-Logik der proaktiven Nachricht in einen gemeinsamen Dienst (`TrustPacing`) auslagern.
2. In `reply` und `say` vor der Vertrauensänderung abfragen. Die Antwort selbst kommt immer, nur die Wirkung entfällt,
   analog zur Doku-Formulierung.
3. Neuen Konfigurationswert (z. B. `messages.reply-pacing-days`) in `RpsimProperties`, application.yml und Referenz
   ergänzen.
4. Tests: zwei Antworten am selben Spieltag → Vertrauen ändert sich einmal.

### M10 – Abbuchungen ohne Folgebehandlung bei Ablehnung

**Fundstellen und Befund:**

| Stelle | Verhalten heute |
| --- | --- |
| `bypass/VanillaBypassService.pay` (`:309`) | Keine Liquiditätsprüfung. Fall wird sofort `SETTLED`/`PAID`, dann `outbox.money(-…, COMPENSATION)`. Lehnt der Mod mit `INSUFFICIENT_FUNDS` ab, hat `FailedInstructionService` keinen Zweig für diesen `RELATED`-Typ: Der Fall bleibt „bezahlt“, Geld floss nie. |
| `villagelife/CropDamageService` (`:257–260`) | Gleiches Muster (Flurschaden-Ausgleich). |
| `contract/InsuranceService.java:436` (Hagel/Sturm, `DAMAGE`) | Schadensbuchung wird gesendet; die spätere Versicherungsleistung wird unabhängig vom Ergebnis der Schadensbuchung gezahlt. |
| `contract/HuntingService.java:190` (Wildschaden, `DAMAGE`) | Wie Versicherung: Schaden kann abgelehnt werden, Verhandlung/Erstattung läuft trotzdem. |
| `cooperative/CooperativeService.buy` | Liquiditätsprüfung vorhanden, aber Anteile werden gutgeschrieben, bevor die Buchung bestätigt ist. Kein Zweig in `FailedInstructionService`: Bei einer Ablehnung durch den Mod (z. B. Stand zwischenzeitlich gesunken) bleiben die Anteile. |

**Dokumentierte Fakten:**
- QUESTIONS.md 2026-09-28 R2-D2: Ausgleichsforderung „pay (COMPENSATION booking) or refuse“. Zum Fall „Buchung vom
  Mod abgelehnt“ gibt es keine Aussage.
- QUESTIONS.md 2026-10-08 R32-I4 nennt das Muster für abgelehnte Zahlungen an Investoren: „It stays open: paid by
  button“. Das ist ein dokumentiertes Vorbild, aber nur für Investoren entschieden.
- `FailedInstructionService` (`notice/FailedInstructionService.java:163ff`) dispatcht nach `relatedEntityType`.
  Bestehende Zweige (Kredit, Gehalt, Pacht, …) setzen den Fall wieder offen.

**Offene Fragen an den Owner:**
1. Ausgleich (Bypass, Flurschaden): Soll ein vom Mod abgelehnter Ausgleich wie bei den Investoren **offen bleiben**
   (erneut per Button zahlbar), oder als **verweigert** gelten (Vertrauensverlust wie „refuse“)?
2. `DAMAGE` (Hagel, Sturm, Wild): Der Schaden ist eine Spielfolge, keine freiwillige Zahlung. Was soll bei fehlender
   Deckung passieren: Forderung/Schuld, Abbuchung ins Minus trotz Dispo-Grenze, oder Verfall? Und soll die
   Versicherungs- bzw. Jäger-Erstattung in diesem Fall trotzdem fließen?

**Umsetzungsweg:**
1. **Ohne Owner-Antwort machbar:** In `VanillaBypassService.pay` und `CropDamageService.pay` vor der Buchung
   `liquidity.available(sg) >= betrag` prüfen und sonst `BusinessRuleException("INSUFFICIENT_FUNDS", …)` werfen, wie
   in `PayrollScheduler.payOverdue`. Das deckt den Normalfall ab.
2. Status erst bei `APPLIED` endgültig setzen oder bei Ablehnung zurücksetzen. Dafür in `FailedInstructionService` je
   einen Zweig für `VanillaBypassService.RELATED` und `CropDamageService.RELATED` ergänzen. Sie rufen
   `onPaymentFailed(caseId)` im Service auf, das je nach Owner-Antwort den Fall wieder auf `AWAITING_PLAYER` oder auf
   „verweigert“ setzt.
3. Genossenschaft: Zweig `CooperativeService.RELATED` ergänzen, der die gutgeschriebenen Anteile zurücknimmt, analog
   zu `LoanService.reverseInstallment`.
4. `DAMAGE`: erst nach der Owner-Antwort. Technisch: in `FailedInstructionService` einen Zweig für
   `InsuranceService.RELATED`/`HuntingService.RELATED` mit `reason = DAMAGE` einführen.
5. Tests: je Zweig ein Test „Ack FAILED INSUFFICIENT_FUNDS → Fall/Anteile im erwarteten Zustand“.

### M11 – Liquiditätssperre bleibt nach einem Rewind hängen

**Fundstelle:** `bridge/LiquidityService.java:71` (`available`), `:83` (`lastRefusedForFunds`).

**Befund:** Nach einer Ablehnung mit `INSUFFICIENT_FUNDS` liefert `available()` höchstens 0, solange der neueste
Snapshot eine Spielzeit `≤` der Ablehnung hat (T-03). Wird danach ein **älterer** Spielstand geladen, liegen alle
neuen Snapshots zeitlich **vor** der Ablehnung aus der verlorenen „Zukunft“. Die Sperre bleibt, bis die Spielzeit
diesen Punkt wieder überholt. In dieser Zeit lehnt das Backend alle Zahlungen ab, obwohl der Spieler Geld hat.

**Dokumentierte Fakten:** T-03 (Javadoc von `available`): Die Sperre soll nur verhindern, dass dieselbe Zahlung jeden
Zyklus erneut eingereiht wird, bis ein **neuerer** Snapshot vorliegt. „Neuer“ ist dort als Spielzeit umgesetzt.
Gemeint ist nach dem Zweck „nach der Ablehnung eingegangen“.

**Umsetzungsweg:**
1. Die Ablehnung nicht über die Spielzeit, sondern über die Eingangsreihenfolge vergleichen. Wenn `OutboxInstruction`
   einen Echtzeit-Zeitstempel des Acks führt, gegen `FactsSnapshot.receivedAt`/`id` vergleichen. Sonst eine Spalte
   `ackedAt` (Instant) per Flyway-Migration ergänzen.
2. Alternative ohne Migration: `RewindService` setzt bei einem erkannten Rewind die Ablehnungen mit
   `ackedAtGameTime > rewoundToGameTime` als „überholt“ (z. B. eigene `ackMessage`), sodass `lastRefusedForFunds` sie
   ignoriert.
3. Test: Ablehnung bei Spielzeit T, Rewind auf T−5 Tage, neuer Snapshot mit Guthaben → `available() > 0`.

---

## 4. Niedrige Schwere

### L12 – Fehlgeschlagenes Schreiben der Anweisungsdatei wird nicht wiederholt

**Fundstelle:** `bridge/BridgeSyncService.java:389`; `bridge/BridgeFiles.java:97–111`.

**Befund:** `writeAtomic` und `writeAtomicXmlPayload` fangen `IOException` ab und loggen nur. `BridgeSyncService` setzt
danach trotzdem `lastInstructionsWritten = key`. Bei unverändertem Inhalt wird die Datei im nächsten Zyklus nicht neu
geschrieben. Der Mod bekommt die Anweisungen erst, wenn sich der Inhalt ändert.

**Umsetzungsweg:**
1. Beide Schreibmethoden geben `boolean` zurück (Erfolg).
2. Den Cache nur setzen, wenn beide Schreibvorgänge erfolgreich waren.
3. Test mit einem `BridgeFiles`-Mock, der beim ersten Mal `false` liefert → zweiter Zyklus schreibt erneut.

### L13 – Ack ohne `status` erzeugt eine NullPointerException

**Fundstelle:** `bridge/BridgeSyncService.java:278`.

**Befund:** `switch (ack.status())` über einen `String` wirft bei `null` eine NPE. Ein einzelner fehlerhafter Ack in
`instructions_ack.json` bricht dann den Import jedes Zyklus ab (Giftpille). Der Mod schreibt `status` laut Protokoll
immer. Das ist also nur eine Robustheitsfrage, etwa für den Simulator oder eine beschädigte Datei.

**Umsetzungsweg:**
1. Vor dem `switch` `if (ack.status() == null) { log.warn(…); continue; }`.
2. Test mit einem Ack ohne Status → übrige Acks werden verarbeitet.

### L14 – Förderung: Verkauf an Nachbarn löst später falsche Rückforderung aus

**Fundstelle:** `authority/InvestmentGrantService.java` (Bindungsprüfung), `vehicle/VehicleTradeService` (Verkauf an Nachbarn).

**Befund:** Die Rückforderung greift laut Umsetzung, wenn ein gefördertes Fahrzeug fehlt **und** `SHOP_VEHICLE_SELL`
steigt. Ein Verkauf an einen Nachbarn (R3-V) erhöht `SHOP_VEHICLE_SELL` nicht. Das Fahrzeug fehlt, aber erst ein
späterer, unabhängiger Shop-Verkauf eines anderen Fahrzeugs löst die Rückforderung aus, mit dem Wert des falschen
Objekts.

**Dokumentierte Fakten:** QUESTIONS.md 2026-10-05 R31-B2: Rückforderung bei „A funded vehicle disappears and
`SHOP_VEHICLE_SELL` rises“. Gebrauchtmaschinen von Nachbarn zählen nicht als Kauf. Zum Verkauf an Nachbarn sagt die
Entscheidung nichts.

**Offene Frage an den Owner:** Ist der Verkauf einer geförderten Maschine an einen Nachbarn ein Bindungsverstoß
(Rückforderung) oder erlaubt?

**Umsetzungsweg:**
- Verstoß: `VehicleTradeService` meldet den Verkauf (Event `VehicleSoldToNeighbor` mit `uniqueId`, Preis), und
  `InvestmentGrantService` rechnet die Rückforderung sofort mit dem Verkaufswert.
- Erlaubt: Das Event entfernt das Fahrzeug aus der Liste der geförderten Objekte.
- In beiden Fällen muss das Fahrzeug aus der Bindungsliste verschwinden, damit kein späterer Shop-Verkauf es
  „findet“.
- Test: geförderte Maschine an Nachbarn verkauft, danach anderes Fahrzeug im Shop verkauft → keine falsche
  Rückforderung.

### L15 – Rewind-Korrektur abgelehnter Lieferungen nur für W1

**Fundstelle:** `investor/InvestorService.java:740` (`onDeliveryFailed`).

**Befund:** Wird eine nach Rewind erneut gesendete, bereits `DONE` gezählte Lieferung abgelehnt, zieht das Backend die
Menge nur bei W1 wieder ab. Bei den anderen erneut gesendeten Lieferungen (W2, Milch `HUSBANDRY_TRANSFER`, Tiere A1)
bleibt die Menge gezählt, obwohl sie im Spiel fehlt.

**Dokumentierte Fakten:** QUESTIONS.md 2026-10-08 R32-I3/R32-Q1: W1-/W2-Lieferungen, Milch und Tiere werden erneut
gesendet; „the backend counts a delivery only once, with its first ack“.

**Umsetzungsweg:**
1. Für jede Gegenleistungsart nachlesen, in welchem Feld die gelieferte Menge geführt wird.
2. Die Korrektur in `onDeliveryFailed` für alle erneut sendbaren Arten symmetrisch anwenden, statt nur
   `type(...) == W1`.
3. Test je Art: Lieferung `DONE`, Rewind, Resend wird abgelehnt → Liefermenge sinkt.

### L16 – Mod: Wiederausführung nach Ablauf der Aufbewahrungsfrist

**Fundstelle:** Mod `mod/FS25_RPSim/src/bridge/Config.lua:18` (`processedRetentionGameDays = 30`),
`bridge/Bridge.lua:527` (`RPSimProcessor.prune`).

**Befund:** Der Mod vergisst verarbeitete `instructionId`s nach 30 Spieltagen. Läuft das Backend nicht, bleibt
`instructions.xml` mit den alten Anweisungen liegen, weil nur das Backend sie bereinigt. Nach 30 Spieltagen,
die bei Zeitraffer schnell vergehen, liest der Mod dieselben Anweisungen wieder und führt sie erneut aus, z. B. Geld.

**Umsetzungsweg:**
1. In `prune` keine IDs löschen, die in der zuletzt gelesenen Anweisungsdatei noch vorkommen.
2. Alternative: Alter der Datei anhand ihres Schreibzeitpunkts bzw. einer Generationsnummer prüfen.
3. Mod-Test: veraltete Datei mit verarbeiteter ID, 31 Spieltage → keine erneute Ausführung.
4. bridge-protocol.md (Idempotenz-Abschnitt) ergänzen.

### L17 – Rückbuchung einer Rate bei gekündigtem Kredit

**Fundstelle:** `credit/LoanService.java:342` (`reverseInstallment`).

**Befund:** `reverseInstallment` erhöht die Restschuld und setzt nur `PAID_OFF` zurück auf `ACTIVE`. Bei einem
gekündigten Kredit (`CALLED`) steigt die Restschuld, und `nextDueGameTime` wird um einen Monat zurückgesetzt. Beides
gehört zum Ratenplan, den es bei einem gekündigten Kredit nicht mehr gibt. Wie die Kündigungsforderung danach
berechnet wird, hängt davon ab, ob sie die Restschuld neu liest.

**Offene Frage an den Owner:** Was soll gelten, wenn eine vor der Kündigung gezahlte Rate nach der Kündigung abgelehnt
zurückkommt? Höhere Kündigungsforderung, oder eigene Forderung?

**Umsetzungsweg:**
1. In `reverseInstallment` einen Zweig für `CALLED` einfügen, der nach der Antwort die Kündigungsforderung anpasst und
   `nextDueGameTime` nicht verändert.
2. Test: Kredit `CALLED`, abgelehnte Rate → Forderung und Status wie entschieden.

### L18 – Fristen nur beim Tageswechsel geprüft

**Fundstelle:** Auktionsgebote und Annahme von Sonderangeboten (Endpunkte der jeweiligen Services).

**Befund:** Der Ablauf der Frist wird im Tageszyklus gesetzt. Die Endpunkte für Gebot bzw. Annahme prüfen nur den
Status, nicht `deadline < currentGameTime`. Zwischen Fristende und nächstem Tageswechsel ist eine Aktion daher noch
möglich.

**Umsetzungsweg:**
1. In den Endpunkten zusätzlich `sg.getCurrentGameTime() > deadline` prüfen und `BusinessRuleException("EXPIRED", …)`
   werfen.
2. Test: Gebot nach Fristende vor Tageswechsel → abgelehnt.

### L19 – Abgelehnter `PRICE_EVENT` lässt Vertrag/Angebot offen

**Fundstelle:** `ForwardContract` (Terminvertrag) und Sonderangebot. `FailedInstructionService` hat für diese
`RELATED`-Typen keinen Zweig.

**Befund:** Lehnt der Mod den `PRICE_EVENT` ab, z. B. wegen unbekannter Fruchtart oder fehlender Verkaufsstelle,
bleibt der Vertrag `OPEN` bzw. das Angebot `ACTIVE`, obwohl der Preis im Spiel nie gilt.

**Umsetzungsweg:**
1. Zweige in `FailedInstructionService` ergänzen, die den Vertrag bzw. das Angebot auf einen Endzustand (z. B.
   `FAILED`) setzen und den Spieler per Hinweis informieren. Dafür das vorhandene Hinweismuster der übrigen Zweige
   nutzen.
2. Test: Ack `REJECTED` → Status Endzustand, Hinweis vorhanden.

### L20 – Wegziehender Verpächter-NPC: Pacht läuft weiter

**Fundstelle:** `village/VillageRotationService.java` (Wegzug), `LeaseService` (Pachtverträge).

**Befund:** Zieht eine Figur weg, die Verpächter eines laufenden Pachtvertrags ist, wird der Vertrag nicht angefasst.
Pacht wird weiter an eine Figur gezahlt, die es im Dorf nicht mehr gibt. Verlängerung und Verhandlung laufen mit einer
`TERMINATED`-Figur.

**Offene Frage an den Owner:** Was soll mit der Pacht passieren? Optionen:
- Vertrag läuft bis zum Ende weiter, ohne Verlängerung.
- Ein Nachfolger übernimmt den Vertrag.
- Der Vertrag endet mit Frist.

Alternativ: Verpächter werden von der Rotation ausgenommen.

**Umsetzungsweg:**
1. In `VillageRotationService` vor dem Wegzug laufende Verträge der Figur ermitteln.
2. Je nach Antwort die Figur von der Rotation ausschließen oder den Vertrag umhängen bzw. beenden.
3. Test: Verpächter-Figur zur Rotation ausgewählt → erwartetes Verhalten.

### L21 – Viehhändler-Prämie zählt Geburten und Todesfälle

**Fundstelle:** `contract/LivestockService.java` (Prämienberechnung).

**Befund:** Die Prämie wird aus der Änderung der Tierzahl im Stall berechnet. Geburten erhöhen sie, Todesfälle senken
sie, obwohl beides keine Lieferung an bzw. vom Händler ist.

**Offene Frage an den Owner:** Soll die Prämie nur die tatsächlich gehandelten Tiere zählen? Dann müsste die Menge aus
den `ANIMAL_TRANSFER`-Acks kommen. Oder ist die Bestandsänderung als vereinfachte Messgröße gewollt?

**Umsetzungsweg (falls nur Handel):**
1. Menge aus den bestätigten `ANIMAL_TRANSFER` des Händler-Geschäfts summieren statt aus der Stall-Differenz.
2. Test: Geburt im Prämienzeitraum → Prämie unverändert.

---

## 5. Gesammelte Fragen an den Owner

| Nr. | Frage | Optionen |
| --- | --- | --- |
| H1 | Rewind bei Warengeschäften (Nachbar, Hofladen, Großauftrag, P2) | komplett erneut senden / gar nicht senden |
| H2 | Level der drei Hinweise (Ausleihe, Gebrauchtmaschine, Flurschaden) | `CRITICAL` / `INFO` (je Hinweis) |
| M3 | Silokapazität in FS25 geteilt oder je Fruchtart? Ernte ohne Platz: vorher ablehnen oder Rest verfällt? | geteilt / getrennt; ablehnen / verfallen |
| M6 | Leasing in der Bonitätsprüfung | laufende Belastung / Betriebsausgabe |
| M8 | Kein zweiter Malus auch bei automatischer Teilzahlung? | ja / nein |
| M9 | Pacing für Antworten und Anrufe? | ja (Sperrfrist) / nein |
| M10 | Abgelehnter Ausgleich (Bypass, Flurschaden) | bleibt offen / gilt als verweigert |
| M10 | Abgelehnte Schadensbuchung (`DAMAGE`) und Erstattung | Forderung / ins Minus / Verfall; Erstattung ja/nein |
| L14 | Verkauf geförderter Maschine an Nachbarn | Verstoß / erlaubt |
| L17 | Abgelehnte Rate nach Kündigung | Kündigungsforderung erhöhen / eigene Forderung |
| L20 | Wegzug eines Verpächters | Rotation ausnehmen / Nachfolger / Vertrag endet |
| L21 | Viehhändler-Prämie | nur gehandelte Tiere / Bestandsänderung |

## 6. Empfohlene Reihenfolge der Umsetzung

1. **Sofort, ohne Owner-Antwort:** M7 (Konfiguration), L12, L13, M4, M5, M11, L15, L16, L18, L19. Dazu der
   Mod-Teil von M3 (Vorprüfung der Kapazität) und der Liquiditätsprüfungs-Teil von M10.
2. **Nach kurzer Antwort:** H2 (ein Wort je Stelle, plus Enum), M8 (Bestätigung), H1.
3. **Nach fachlicher Entscheidung:** M6, M9, die restlichen Teile von M10, L14, L17, L20, L21.

Jede Umsetzung sollte die Checkliste der PR-Vorlage erfüllen: `mvn -B verify`, Frontend-Build und Tests, `luacheck`
und Mod-Tests, neue Werte als `rpsim.formulas.*` sowie Doku und CHANGELOG. Neue Entscheidungen gehören in
[QUESTIONS.md](QUESTIONS.md).

## 7. Entscheidungen und Umsetzungsstand

Antworten des Owners vom 20.10.2026; eingetragen in [QUESTIONS.md](QUESTIONS.md) (Kennung `LR-…`). Seit dem Bericht
kam mit #58 (Fahrzeughändler) eine vierte `WARNING`-Stelle hinzu (`dealer/DealerDemoService.java`), sie wird mit H2
behoben. Zu M3: Der Mod rechnet die freie Kapazität mit `storage:getFreeCapacity(fillTypeIndex)` der FS25-Storage
(`game/GameAdapter.lua`, `transferStorage`). Die Vorprüfung im Batch nutzt dieselbe Funktion und braucht deshalb
keine Annahme über geteilte Silos; die Frage entfällt für die Umsetzung.

| Nr. | Entscheidung | Stand |
| --- | --- | --- |
| H1 | Ganzen Batch erneut senden | umgesetzt |
| H2 | `CRITICAL` für alle | umgesetzt |
| M3 | Vorprüfung der Kapazität im Mod-Batch (und im Simulator); das Backend storniert wie bisher | umgesetzt |
| M4 | – (ohne Entscheidung); `GameTime.yearOf` an allen Stellen, die im Tages- oder Monatslauf das Jahr lesen (12 statt der 7 genannten: zusätzlich Feldbilanz-Beratung, Anbauplan, Meilenstein, Dorfzeitung, Investor-Zahlungsjahr). Ansichten und `FactsIngested`-Handler lesen weiter das Jahr ihres Exports; `CooperativeService.samplePrices` ordnet laut Javadoc die Preise des neuesten Exports dessen Jahr zu und bleibt | umgesetzt |
| M5 | – (ohne Entscheidung); Remap nur für in Monaten geplante Zeitpunkte, Fristen in Spieltagen bleiben (Antwortfrist des Viehhändlers, Bearbeitungszeit der Förderung) | umgesetzt |
| M6 | Leasing als Rate, aus dem Cashflow herausgerechnet (Journal und Kontostand-Trend) | umgesetzt |
| M7 | – (ohne Entscheidung); `RPSIM_LEASE_INCOME` in `known-posting-categories`. Abgleich der übrigen Planbuchungen: Kredite und Investoren-Zahlungen sind `FINANCING` (nicht im Betriebsergebnis), der Winterdienst hat keinen `nextDueGameTime` und steht nicht im Plan | umgesetzt |
| M8 | Kein zweiter Malus bei automatischer Teilzahlung | umgesetzt |
| M9 | Sperrfrist je Figur für Antworten und Anrufe, gemeinsam mit „Nachricht verfassen“ (Nachfrage 2026-10-20) | umgesetzt |
| M10 | Ausgleich bleibt offen mit neuer Entscheidungsfrist; Schaden wird später erneut abgebucht; Genossenschaftsanteile werden zurückgenommen (Nachfragen 2026-10-20) | umgesetzt |
| M11 | – (ohne Entscheidung); Echtzeit des Acks (`acked_at`, V56) gegen `bridge_rewind.created_at` | umgesetzt |
| N1 | Neu (per Test bewiesen): nach einem Rewind lieferte `latest` den Snapshot der verworfenen Zukunft; Zukunfts-Snapshots werden gelöscht (Entscheidung 2026-10-20) | umgesetzt |
| L12 | – | umgesetzt |
| L13 | – | umgesetzt |
| L14 | Nachbarverkauf zählt als Verkauf (wie Inzahlungnahme) | umgesetzt |
| L15 | Menge wie bei W1 je Periode zurück (Nachfrage 2026-10-20), Aufteilung in `period_split` (V57) | umgesetzt |
| L16 | – (Mod und Simulator) | umgesetzt |
| L17 | Wie offene Restschuld (`DEFAULTED`, Einzug bei Deckung) | umgesetzt |
| L18 | – | offen |
| L19 | – | offen |
| L20 | Figuren mit laufender Pacht ziehen nicht weg | offen |
| L21 | Nur dokumentieren | offen |
