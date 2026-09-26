# FS25_RPSim – Datei-Bridge-Mod

Der Mod ist der **reine Sensor/Aktuator** der FS25 KI-Rollenspiel-Simulation.

## Was der Mod tut

- Exportiert alle ~60 s (konfigurierbar) `farm_facts.json`: Kontostand, Fahrzeuge (Wert + Zustand), Gebäude,
  eigene Felder, Tierbestand, **Silo-Warenbestand (nur klassische Silos)**, Vanilla-Kredit, laufende
  Verkaufspreise je Verkaufsstelle/Fruchtart.
- Exportiert beim Spielstart (und nach jeder Feldübertragung sowie bei jeder inhaltlichen Änderung im
  `farm_facts`-Takt) `market_context.json`: Kartenname, Verkaufsstellen (Produktionen gekennzeichnet), Fruchtarten,
  alle Farmlands inkl. Besitzer und FS25-NPC.
- Exportiert außerdem Kalender inkl. Jahreszeit, Leasing-Fahrzeuge und die Aufträge des Spiels (verfügbare und
  eigene; nur lesend).
- Der erste Export läuft erst, wenn der Spielstand vollständig geladen ist (`Mission00.onStartMission`).
- Liest `instructions.json` und wendet an:
  - `MONEY_TRANSACTION` – Geld buchen (Kredit, Gehalt, Förderung, Feldkauf …); Abbuchungen, die das Guthaben
    nicht deckt, werden abgelehnt (`FAILED`, Meldung `INSUFFICIENT_FUNDS`)
  - `PRICE_EVENT` – Preis an **einer** Verkaufsstelle ändern (`MULTIPLIER` mit Ramp-Up/Hold/Decay oder
    `FIXED`-Sonderkontrakt mit Mengen-Tracking)
  - `FARMLAND_TRANSFER` – Feldbesitz übertragen (Kauf/Verkauf, Beginn und Ende einer Pacht)
  - `REPAIR_VEHICLE` – eigenes Fahrzeug instand setzen (`Wearable:setDamageAmount(0, true)`, Wartungsvertrag)
  - `NOTIFICATION` – Hinweis im Spiel einblenden (neue Mail, Anruf); zu spät verarbeitete Hinweise werden nicht
    gezeigt
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
  rpsim_config.json          (optional, eigene Einstellungen)
  export/
    farm_facts.json          (Mod schreibt, ~60 s)
    market_context.json      (Mod schreibt, beim Spielstart + nach FARMLAND_TRANSFER + bei Änderung)
  import/
    instructions.json        (Backend schreibt)
    instructions_ack.json    (Mod schreibt)
```

Jede Datei hat genau einen Schreiber. Jede Datei trägt die `savegameId` des aktiven Spielstands;
Instruktionen für einen anderen Spielstand werden verworfen (mit Warnung im `log.txt`).

## Konfiguration (`rpsim_config.json`)

| Schlüssel | Standard | Bedeutung |
| --- | --- | --- |
| `exportIntervalMs` | 60000 | Export-Intervall `farm_facts.json` (Echtzeit-ms) |
| `importIntervalMs` | 5000 | Abfrage-Intervall `instructions.json` |
| `processedRetentionGameDays` | 30 | Aufbewahrung erledigter Instruktionen (Spieltage) |
| `startFallbackMs` | 30000 | Sicherheitsnetz: Start der Bridge nach so vielen ms, falls der Spielstart-Hook nicht feuert |
| `atomicWriteMode` | `direct` | `direct` (Standard, Datei direkt schreiben). `rename`, `marker`, `auto` brauchen das `os`-Modul, das es in FS25 nicht gibt – nur für Tests |
| `pricePerLiters` | 1000 | Preiseinheit der exportierten Preise (Preis je 1000 l) |
| `conflictMods` | `FS25_MarketDynamics`, `FS25_UsedPlus`, `FS25_EnhancedLoanSystem`, `FS25_BetterContracts` | Mods mit überlappenden Funktionen; erkannte werden in `market_context.json` gemeldet (nur Warnung) |
| `moneyTypeTitles` | `true` | Buchungen bekommen eigene Bezeichnungen (`MoneyType.register(statistik, "rpsim_money_<GRUND>")`, Texte in `modDesc.xml`); `false` = alles als „Sonstiges“ (`MoneyType.OTHER`) |
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
