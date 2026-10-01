# FS25_RPSim – Datei-Bridge-Mod

Der Mod ist der **reine Sensor/Aktuator** der FS25 KI-Rollenspiel-Simulation.

## Was der Mod tut

- Exportiert alle 10 s (konfigurierbar) `farm_facts.json`: Kontostand, Fahrzeuge (Wert + Zustand), Gebäude,
  eigene Felder, Tierbestand, **Warenbestand (Silos, Silo-Erweiterungen, Produktionen, Fahrsilos)**, Vanilla-Kredit, laufende
  Verkaufspreise je Verkaufsstelle/Fruchtart.
- Exportiert beim Spielstart (und nach jeder Feldübertragung sowie bei jeder inhaltlichen Änderung im
  `farm_facts`-Takt) `market_context.json`: Kartenname, Verkaufsstellen (Produktionen gekennzeichnet), Fruchtarten,
  alle Farmlands inkl. Besitzer und FS25-NPC.
- Exportiert außerdem Kalender inkl. Jahreszeit, Leasing-Fahrzeuge und die Aufträge des Spiels (verfügbare und
  eigene; nur lesend).
- Führt ein **Buchungsjournal** (Roadmap V2, R2-B1): Jede Buchung der Spieler-Farm (`Farm:changeBalance`) wird je
  FS25-Monat und Buchungsart summiert, die eigenen Buchungen unter `RPSIM_<GRUND>`. Die letzten
  `financeJournalPeriods` Monate stehen im Spielstand und in `farm_facts.json` (`finances`).
- Lässt angestellte **Maschinenführer die FS25-Helfer fahren** (Roadmap V2, R2-A0..A5): Ein gestarteter Helfer der
  Spieler-Farm bekommt den ersten freien aktiven Maschinenführer der Mitarbeiterliste; die Spielmeldungen zeigen seinen
  Namen, im Lohnmodus `EMPLOYEES` bucht das Spiel für ihn keinen Helferlohn (`AIJob.getPricePerMs` = 0), im strengen
  Modus begrenzt der Mod `maxNumHirables` auf die Zahl der aktiven Maschinenführer. Die gefahrene Zeit je Mitarbeiter
  steht im Spielstand und in `farm_facts.json` (`workforce`).
- Exportiert den **Zustand der Ställe** (R2-A7, `husbandries`): Gesundheit, Produktivität, Futter und die
  Bedingungen (Wasser, Stroh …) je Stall.
- Exportiert **Felder und Wetter** (R2-C, `fields`, `fieldRules`, `weather`): Kultur, Wachstum, Unkraut, Steine,
  Kalk und Pflug je eigenem Feld (dazu verdorrt/abgeerntet, Fülltyp und Ertrag je m²), die Bodeneinstellungen des
  Spielstands und das aktuelle Wetter. Die Felder werden nur alle `fieldExportIntervalMs` neu gelesen.
- Exportiert für den **Handel mit den Nachbarn** (Roadmap V3, R3-H): die Felder ohne Besitzer, die die NPCs des
  Spiels bewirtschaften (`npcFields`, Schalter `npcFieldExport`), Füllstand und freien Platz der eigenen Silos je
  Sorte (`tradeStorage`) und ob die Höchstzahl an Aufträgen erreicht ist (`missionLimitReached`).
- Exportiert für **Gebrauchtmaschinen** (Roadmap V3, R3-V): einmal beim Spielstart den Fahrzeug-Katalog des Shops
  (`storeVehicles`, Schalter `storeCatalogExport`, höchstens `storeCatalogMaxEntries` Einträge) und zu jeder eigenen
  Maschine Name und Shop-XML (`assets.vehicles[].name`, `xmlFilename`).
- Der erste Export läuft erst, wenn der Spielstand vollständig geladen ist (`Mission00.onStartMission`).
- Liest `instructions.json` und wendet an:
  - `MONEY_TRANSACTION` – Geld buchen (Kredit, Gehalt, Förderung, Feldkauf …); Abbuchungen, die das Guthaben
    nicht deckt, werden abgelehnt (`FAILED`, Meldung `INSUFFICIENT_FUNDS`)
  - `PRICE_EVENT` – Preis an **einer** Verkaufsstelle ändern (`MULTIPLIER` mit Ramp-Up/Hold/Decay oder
    `FIXED`-Sonderkontrakt mit Mengen-Tracking)
  - `FARMLAND_TRANSFER` – Feldbesitz übertragen (Kauf/Verkauf, Beginn und Ende einer Pacht)
  - `REPAIR_VEHICLE` – eigenes Fahrzeug instand setzen (`Wearable:setDamageAmount(0, true)`, Wartungsvertrag);
    mit `targetDamage` nur bis zu diesem Schaden (Roadmap V2, R2-A6, auch der angestellte Mechaniker), der Schaden
    steigt dabei nie
  - `NOTIFICATION` – Hinweis im Spiel einblenden (neue Mail, Anruf); zu spät verarbeitete Hinweise werden nicht
    gezeigt
  - `EMPLOYEE_ROSTER` (Roadmap V2, R2-A0) – ersetzt die Mitarbeiterliste; Helfer streikender Mitarbeiter werden mit
    der Meldung „%s legt die Arbeit nieder“ angehalten (R2-A5)
  - `PROMPT` (Roadmap V2, R2-F2) – Ja/Nein-Frage: wird eingereiht und einzeln mit dem Dialog des Spiels
    (`YesNoDialog`) gezeigt, sobald kein Menü offen ist; die Knöpfe heißen „Ja“/„Nein“, ihre Bedeutung steht im Text.
    Die Antwort schreibt der Mod sofort nach `export/player_responses.json` (R2-F1); vom Backend quittierte Antworten
    (`ackedResponses`) und zurückgezogene Fragen (`withdrawnPrompts`) verschwinden. Die Taste „FarmPulse: offene
    Frage“ (Standard Alt+J, in der Steuerung änderbar; R2-F3) öffnet die nächste Frage, auch im Fahrzeug
  - `STORAGE_TRANSFER` (Roadmap V3, R3-H3/H4) – füllt die Ware in die eigenen Silos (`IN`) oder entnimmt sie
    (`OUT`), ohne Spielbuchung; das Geld bucht die `MONEY_TRANSACTION` desselben Batches. `FAILED` mit `NO_CAPACITY`,
    `INSUFFICIENT_STOCK` oder `UNKNOWN_FILLTYPE`, dann bucht der Batch nichts
  - `MISSION_CREATE` (Roadmap V3, R3-H5) – legt einen Auftrag (Pflügen, Steine sammeln) auf dem Feld eines Nachbarn an
    wie das Spiel selbst; die Quittung trägt die `missionId`, `FAILED` mit `NOT_AVAILABLE`, wenn das Feld nicht passt
  - `VEHICLE_SPAWN` (Roadmap V3, R3-V2) – lädt eine gebrauchte Maschine auf einen freien Shop-Platz wie ein Auftrag
    des Spiels (`VehicleLoadingData`), setzt Alter, Betriebsstunden, Schaden und Abnutzung und bucht den Preis erst im
    Lade-Callback selbst (`VEHICLE_PURCHASE`). Bis dahin bleibt die Anweisung offen (keine Quittung, nicht im
    Spielstand). `FAILED` mit `NO_SPACE`, `UNKNOWN_STORE_ITEM`, `INSUFFICIENT_FUNDS` oder `LOAD_FAILED`, dann ist
    nichts gebucht; die Quittung trägt die `vehicleId`
  - `VEHICLE_REMOVE` (Roadmap V3, R3-V3) – entfernt eine eigene Maschine (`vehicle:delete()`), vor dem Erlös
    `VEHICLE_SALE` desselben Batches. `FAILED` mit `VEHICLE_NOT_FOUND`, `NOT_OWN_VEHICLE` (auch geleaste),
    `VEHICLE_IN_USE` (jemand sitzt drin oder ein Helfer fährt) oder `VEHICLE_ATTACHED` (angehängt oder mit Anbaugerät,
    bis zum Spieltest gilt: erst abkoppeln). Eine Quittung kann ein Ergebnis `result` tragen (z. B. `vehicleId`,
    `missionId`); es wird mit im Spielstand gespeichert
- Bucht Geld mit eigenen Bezeichnungen je Buchungsgrund (`MoneyType.register`, Texte in `modDesc.xml`).
- Schreibt `instructions_ack.json` (Quittungen + Rückmeldung zu beendeten Sonderkontrakten).
- Merkt sich bereits ausgeführte Instruktionen im Spielstand (`FS25_RPSim.xml`), damit nichts doppelt gebucht wird.

## Was der Mod bewusst NICHT tut

- **Keine Spiellogik**: keine Formeln, keine Bonität, keine Preisentscheidungen – das macht das Backend.
- **Keine KI-Anbindung** und keine HTTP-Aufrufe. Der API-Key liegt nie im Mod-Ordner.
- Kein Mehrspieler (V1).

## Installation

1. Ordner `FS25_RPSim` zippen (Inhalt, nicht den Ordner selbst: `modDesc.xml` muss im ZIP-Root liegen) oder das
   Release-ZIP verwenden.
2. ZIP nach `Dokumente/My Games/FarmingSimulator2025/mods/` kopieren.
3. Im Spiel beim Anlegen/Laden eines Spielstands den Mod aktivieren.

## Erzeugte Ordnerstruktur

```
Dokumente/My Games/FarmingSimulator2025/modSettings/FS25_RPSim/
  rpsim_config.xml           (optional, eigene Einstellungen)
  export/
    farm_facts.json          (Mod schreibt, alle 10 s)
    market_context.json      (Mod schreibt, beim Spielstart + nach FARMLAND_TRANSFER + bei Änderung)
  import/
    instructions.json        (Backend schreibt, lesbare Fassung für Tools/Simulator)
    instructions.xml         (Backend schreibt, dieselben Daten - diese Datei liest der Mod)
    instructions_ack.json    (Mod schreibt)
```

Jede Datei hat genau einen Schreiber. Jede Datei trägt die `savegameId` des aktiven Spielstands;
Instruktionen für einen anderen Spielstand werden verworfen (mit Warnung im `log.txt`).

FS25 erlaubt `io.open` nur zum Schreiben. Alles, was der Mod **liest**, ist deshalb eine XML-Datei, die das
JSON-Dokument in `<rpsim><json>…</json></rpsim>` einpackt und über die XML-API des Spiels gelesen wird.

## Konfiguration (`rpsim_config.xml`)

Die Schlüssel stehen als JSON im Element `json` (die frühere `rpsim_config.json` kann FS25 nicht lesen):

```xml
<?xml version="1.0" encoding="utf-8" standalone="no"?>
<rpsim>
    <json>{ "exportIntervalMs": 30000, "importIntervalMs": 5000 }</json>
</rpsim>
```

| Schlüssel | Standard | Bedeutung |
| --- | --- | --- |
| `exportIntervalMs` | 10000 | Export-Intervall `farm_facts.json` (Echtzeit-ms) |
| `importIntervalMs` | 5000 | Abfrage-Intervall `instructions.xml` |
| `processedRetentionGameDays` | 30 | Aufbewahrung erledigter Instruktionen (Spieltage) |
| `startFallbackMs` | 30000 | Sicherheitsnetz: Start der Bridge nach so vielen ms, falls der Spielstart-Hook nicht feuert |
| `atomicWriteMode` | `direct` | `direct` (Standard, Datei direkt schreiben). `rename`, `marker`, `auto` brauchen das `os`-Modul, das es in FS25 nicht gibt – nur für Tests |
| `pricePerLiters` | 1000 | Preiseinheit der exportierten Preise (Preis je 1000 l) |
| `conflictMods` | `FS25_MarketDynamics`, `FS25_UsedPlus`, `FS25_EnhancedLoanSystem`, `FS25_BetterContracts` | Mods mit überlappenden Funktionen; erkannte werden in `market_context.json` gemeldet (nur Warnung) |
| `moneyTypeTitles` | `true` | Buchungen bekommen eigene Bezeichnungen (`MoneyType.register(statistik, "rpsim_money_<GRUND>")`, Texte in `modDesc.xml`); `false` = alles als „Sonstiges“ (`MoneyType.OTHER`) |
| `financeJournalPeriods` | `13` | Roadmap V2 R2-B1: so viele FS25-Monate behält das Buchungsjournal (`farm_facts.finances`) |
| `fieldExportIntervalMs` | `10000` | Roadmap V2 R2-C1: so oft (Echtzeit, ms) werden die Felder neu gelesen; jeder Export dazwischen übernimmt den letzten Stand |
| `npcFieldExport` | `true` | Roadmap V3 R3-H1: die Felder der Nachbarn (ohne Besitzer) mit exportieren (`npcFields`, gleiche Taktung wie die eigenen Felder); aus = keine Ernte-Vorräte und keine Aufträge der Nachbarn |
| `storeCatalogExport` | `true` | Roadmap V3 R3-V1: den Fahrzeug-Katalog des Shops einmal beim Spielstart exportieren (`storeVehicles`); aus = keine Gebrauchtmaschinen-Angebote |
| `storeCatalogMaxEntries` | `2000` | Roadmap V3 R3-V1: höchstens so viele Katalog-Einträge (nach `xmlFilename` sortiert, der Rest wird mit Log-Eintrag weggelassen) |
| `promptsInVehicle` | `true` | Roadmap V2 R2-F2: Ja/Nein-Fragen erscheinen auch, während du im Fahrzeug sitzt; `false` = nur zu Fuß (die Taste öffnet sie trotzdem) |
| `moneyTypeStatistics` | `{}` | Finanzstatistik je Buchungsgrund, z. B. `{ "SALARY_PAYMENT": "wagePayment" }`. Belegt ist nur `other` (FS25 `FillTrigger.lua`); andere Namen erst im Spiel prüfen (Testplan 8.18) |

## Entwicklung & Tests

Voraussetzungen: Lua 5.1, [luaunit](https://github.com/bluebird75/luaunit), [luacheck](https://github.com/lunarmodules/luacheck)
(`luarocks install luaunit luacheck`).

```bash
cd mod
lua5.1 tests/run.lua      # komplette Testsuite
luacheck .                # Lint
```

Die Logik liegt in FS25-unabhängigen Modulen (`src/util`, `src/bridge`, `src/export`, `src/import`); nur
`src/game/GameAdapter.lua` und `src/RPSim.lua` greifen auf FS25-Globals zu. Offene API-Fragen sind mit
`TODO(offene-frage)` markiert, siehe `docs/dev/offene-technische-punkte.md`. Das Protokoll ist in
`docs/dev/bridge-protocol.md` beschrieben.

## Versionierung

SemVer, gekoppelt an `CHANGELOG.md`. FS25 erwartet eine vierstellige Version: `MAJOR.MINOR.PATCH.0`.
