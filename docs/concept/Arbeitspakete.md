# Arbeitspakete: FS25 KI-Rollenspiel-Simulation

**Für:** Claude Code — schrittweise, eigenständige Abarbeitung
**Quelle der Wahrheit:** `docs/concept/Fachliches_Konzept_V3.md`, `docs/concept/Technisches_Konzept_V6.md` (beide vor Start ins Repo legen, siehe „Voraussetzungen")
**Design-Referenz:** `docs/design-reference/Dashboard.html` (Tailwind-Farben/Layout-Muster als Basis für das Angular-Design-System, siehe Phase 7)

---

## 0. Wie diese Datei zu benutzen ist

Diese Datei enthält alle Arbeitspakete (AP) in der Reihenfolge, in der sie abgearbeitet werden sollen. Jedes AP hat:

- **Ziel** – was am Ende funktionieren muss
- **Aufgaben** – konkrete Schritte
- **Definition of Done (DoD)** – prüfbare Abnahmekriterien
- **Referenzen** – Kapitel im Fach-/Technischen Konzept, die maßgeblich sind

Arbeite die Phasen **sequenziell** ab (Phase 0 → Phase 11), innerhalb einer Phase können unabhängige AP parallel gedacht, aber nacheinander umgesetzt werden, wenn du alleine arbeitest. Committe und pushe nach **jedem einzelnen AP** (nicht erst am Phasenende) — siehe Grundregeln unten.

---

## 1. Grundregeln für die Abarbeitung (gelten für JEDES Arbeitspaket)

1. **Frag nach, wenn du blockiert bist.** Wenn ein AP eine Entscheidung erfordert, die weder im Fachkonzept noch im Technischen Konzept eindeutig beantwortet ist (z. B. eine der Fragen aus „Offene technische Punkte" im Technischen Konzept), **rate nicht** und **erfinde keine Fachlogik**. Stoppe an dieser Stelle, dokumentiere die Frage kurz in einem `QUESTIONS.md` im Repo-Root (Datum, betroffenes AP, konkrete Frage, deine vorgeschlagene Default-Lösung) und frage explizit beim Nutzer nach, bevor du weitermachst. Blockiere dabei nicht das gesamte Projekt — arbeite an einem parallel möglichen AP weiter, sofern eines existiert, sonst warte auf Antwort.
2. **Formeln/Schwellen sind Konfiguration, keine Code-Konstanten.** Jeder Zahlenwert aus dem Formel-Abschnitt des Technischen Konzepts (Bonitäts-Gewichte, Trust-Caps, Zufriedenheits-Schwellen, Verhandlungs-Rundenzahl, Spawn-Wahrscheinlichkeiten etc.) muss in `application.yml`/einer eigenen Konfigurationsklasse landen, mit dem im Konzept genannten Platzhalterwert als Default. Kommentiere im Code, aus welchem Kapitel der Wert stammt.
3. **Zwei-Ebenen-Prinzip ist nicht verhandelbar.** Kein KI-Call darf jemals eine Zahl erzeugen, die Spielgeld, Skills, Bonität oder Preise bestimmt. Die KI bekommt immer nur fertige Fakten/Kategorien (z. B. `reasonCategory`) und liefert Text. Baue das so, dass ein Reviewer das an der Code-Struktur sofort erkennt (z. B. `AiNarrationService` bekommt nur DTOs ohne Rohzahlen-Setter).
4. **Offene technische Punkte blockieren nicht, sie werden markiert.** Für Punkte wie „ist `os.rename` in der FS25-Lua-Sandbox verfügbar" o. ä.: implementiere die im Konzept vorgeschlagene beste Lösung, baue einen dokumentierten Fallback, markiere die Stelle mit `-- TODO(offene-frage): ...` bzw. `// TODO(offene-frage): ...` und trage sie in `docs/dev/offene-technische-punkte.md` ein. Erst wenn eine solche Unsicherheit die Fach-Logik selbst verändern würde (nicht nur die technische Umsetzung), gilt Regel 1.
5. **Commit- und Push-Disziplin (dem Nutzer sehr wichtig!):**
   - Nach jedem abgeschlossenen AP: `git add -A && git commit -m "<Typ>(<Bereich>): <kurze Beschreibung> [AP-x.y]"` und **sofort** `git push`.
   - Commit-Typ nach Conventional Commits: `feat`, `fix`, `test`, `docs`, `chore`, `refactor`, `ci`.
   - Ist ein AP sehr groß (z. B. Phase 4/8), committe zusätzlich nach jedem sinnvollen Zwischenschritt (einzelner Service, einzelne Komponente) — lieber zu oft als zu selten pushen.
   - Kein AP gilt als „done", bevor es gepusht ist.
6. **Jedes AP endet mit lauffähigem Zustand.** Build muss grün sein (Backend kompiliert + Tests laufen, Frontend baut, Lua-Syntax ist valide), bevor committet wird. Kaputte Zwischenstände werden nicht gepusht.
7. **Tests sind Teil des AP, nicht optional.** Jedes AP mit Fachlogik (Formeln, Services, Endpunkte, Komponenten mit Logik) bekommt Unit-Tests im selben Commit. Reine „es soll keine Lücken geben"-Vorgabe des Nutzers gilt auch für Testabdeckung der Kernformeln (insbesondere Grenzfälle: Trust-Cap-Deckelung, Schwellenwerte ≥/</==).
8. **Nichts aus den Konzepten weglassen.** Bevor eine Phase als abgeschlossen gilt, gleiche sie gegen das jeweils referenzierte Konzept-Kapitel ab. Die Abnahme-Checkliste in Abschnitt 14 dieser Datei ist die verbindliche Vollständigkeits-Prüfung — sie am Ende jeder Phase durchgehen, nicht erst ganz am Schluss.
9. **Sprache:** Code, Kommentare, Commit-Messages, technische Doku (`docs/dev/`) auf Englisch. UI-Texte und Nutzer-Anleitung (`docs/user-guide/`) auf Deutsch (Fachkonzept-Entscheidung: „Version 1 erscheint zunächst nur auf Deutsch"). Java-/TypeScript-Bezeichner folgen den im Technischen Konzept vorgegebenen Namen (Entities, Services), damit Code und Konzept durchsuchbar aufeinander verweisen.

---

## 2. Voraussetzungen (vom Nutzer VOR dem Start zu erledigen)

- [ ] Leeres GitHub-Repository angelegt, Claude Code hat Push-Zugriff (Remote `origin` konfiguriert bzw. Zugangsdaten hinterlegt).
- [ ] Diese Datei liegt Claude Code vor (z. B. als `ARBEITSPAKETE.md` im Projekt-Root oder als Prompt-Anhang).
- [ ] `Fachliches_Konzept_V3.md` und `Technisches_Konzept_V6.md` liegen Claude Code vor (werden in AP-0.2 ins Repo unter `docs/concept/` übernommen).
- [ ] `Dashboard.html` liegt Claude Code vor (wird in AP-0.2 ins Repo unter `docs/design-reference/` übernommen).
- [ ] Der Nutzer weiß: **keine echten API-Keys** (OpenAI/Anthropic/Gemini) werden jemals committet — Claude Code legt dafür `*.local.yml`/`.env`-Mechanismen an und trägt sie in `.gitignore` ein (siehe AP-0.1). Der Nutzer trägt seine eigenen Keys erst nach Fertigstellung lokal ein.

---

## 3. Festgelegte Technologie-Entscheidungen

Diese Entscheidungen sind getroffen. Nicht ohne Rückfrage ändern:

| Bereich | Entscheidung |
| --- | --- |
| Repo-Struktur | Monorepo: `mod/`, `backend/`, `frontend/`, `tools/bridge-simulator/`, `docs/` |
| Mod | FS25-Lua-Mod, reiner Sensor/Aktuator, Datei-Bridge (kein HTTP) |
| Backend | Spring Boot (aktuelle stabile Version zum Zeitpunkt der Umsetzung prüfen), Maven, embedded DB **H2 im Dateimodus**, Flyway für Migrationen |
| Frontend | Angular (aktuelle stabile Version prüfen), Standalone Components, **Tailwind CSS** mit Design-Tokens aus `Dashboard.html` |
| Echtzeit | Server-Sent Events (SSE) vom Backend zum Frontend, kein Polling |
| KI-Anbindung | Pluggable `AiProvider`-Interface mit **vier** Implementierungen: OpenAI, Anthropic (Claude API), Google Gemini (kostenloses Free-Tier-Modell), Ollama (lokal) |
| Repo-Hosting | GitHub, inkl. GitHub Actions CI |
| Doku-Bilder | Mermaid-Diagramme (Architektur/Abläufe) **und** automatisierte Angular-Screenshots (Playwright) |
| Sprache | Code/Doku(dev) Englisch, UI/Nutzer-Doku Deutsch |
| Lizenz | Sofern der Nutzer nichts anderes sagt: MIT-Lizenz (nicht-kommerzielles Fan-Mod-Projekt) — bei erstem Zweifel in `QUESTIONS.md` eintragen und fragen, bevor `LICENSE` final committet wird |

**Zum KI-Provider Google Gemini:** Das Free-Tier-Modell von Google ändert sich häufig (Modellnamen/Aliase werden regelmäßig abgelöst). Beim Implementieren in AP-5.5 aktuell gültigen kostenlosen Modellnamen gegen die offizielle Google-AI-Studio-Dokumentation prüfen, den Modellnamen **konfigurierbar** machen (kein Hardcoding) und einen sinnvollen Default setzen, der zum Zeitpunkt der Umsetzung tatsächlich frei nutzbar ist.

---

## 4. Repository-Struktur (Zielzustand)

```
/
├── mod/                          # FS25 Lua-Mod (FS25_RPSim)
│   ├── FS25_RPSim/
│   │   ├── modDesc.xml
│   │   ├── src/
│   │   └── ...
│   └── tests/                    # luaunit/busted Tests für reine Logik
├── backend/                      # Spring Boot
│   ├── src/main/java/...
│   ├── src/main/resources/application.yml
│   ├── src/test/java/...
│   └── pom.xml
├── frontend/                     # Angular
│   ├── src/app/...
│   ├── tailwind.config.js
│   └── package.json
├── tools/
│   └── bridge-simulator/         # Mock für die Datei-Bridge ohne FS25
├── docs/
│   ├── concept/                  # Original-Konzeptdateien (Quelle der Wahrheit)
│   ├── design-reference/         # Dashboard.html
│   ├── architecture/             # Mermaid-Diagramme, ADRs
│   ├── dev/                      # Entwickler-Doku, Konfigurationsreferenz, offene Punkte
│   ├── user-guide/               # Nutzer-Anleitung (Deutsch)
│   └── screenshots/              # automatisiert erzeugte Screenshots
├── .github/
│   ├── workflows/                # CI
│   └── ISSUE_TEMPLATE/
├── QUESTIONS.md                  # laufendes Log offener Rückfragen an den Nutzer
├── CHANGELOG.md
├── CONTRIBUTING.md
├── LICENSE
├── README.md
└── .gitignore
```

---

## 5. Phasenübersicht

| Phase | Inhalt |
| --- | --- |
| 0 | Repo-Grundgerüst & Projekt-Setup |
| 1 | FS25-Lua-Mod & Datei-Bridge |
| 2 | Bridge-Simulator (Testwerkzeug ohne FS25) |
| 3 | Backend-Grundgerüst & Domänenmodell |
| 4 | Backend: Fakten-Ebene-Services (alle Module) |
| 5 | Backend: KI-Adapter (Persönlichkeits-Ebene) |
| 6 | Backend: REST-API & Echtzeit |
| 7 | Frontend: Grundgerüst & Design-System |
| 8 | Frontend: Feature-Module |
| 9 | Qualitätssicherung (Tests, End-to-End) |
| 10 | Dokumentation |
| 11 | CI/CD & Release-Vorbereitung |

---

## Phase 0 — Repo-Grundgerüst & Projekt-Setup

### AP-0.1 — Monorepo-Grundgerüst

**Ziel:** Leeres, sauberes Repo-Skelett gemäß Struktur in Abschnitt 4.

**Aufgaben:**
- Ordnerstruktur gemäß Abschnitt 4 anlegen (mit `.gitkeep` wo nötig).
- `.gitignore` für Java/Maven, Node/Angular, Lua, IDE-Dateien, `*.local.yml`, `.env`, `application-local.yml`.
- `LICENSE` (MIT, siehe Abschnitt 3 — bei Unsicherheit erst `QUESTIONS.md` befüllen und fragen).
- `README.md`-Platzhalter (wird in Phase 10 final ausgebaut, aber Grundgerüst mit Projektname „FS25 KI-Rollenspiel-Simulation", einem Satz Beschreibung, Platzhalter-Badges-Sektion und Lizenzhinweis jetzt schon anlegen).
- `CONTRIBUTING.md` mit: Branching-Modell (kurzlebige Feature-Branches `feature/<AP-Kürzel>-<slug>`, PR gegen `main`, oder — falls Solo-Projekt — direkte Commits auf `main` mit AP-Referenz im Commit; entscheide für Solo-Nutzung auf direkte `main`-Commits, dokumentiere das explizit), Commit-Konvention (Conventional Commits, siehe Grundregel 5), Code-Style-Hinweise pro Sprache.
- `QUESTIONS.md` mit Kopfzeile/Format-Vorlage anlegen (Datum | AP | Frage | Vorschlag | Status).
- `CHANGELOG.md` im Keep-a-Changelog-Format, Sektion `[Unreleased]`.

**DoD:** Repo-Struktur existiert, `git status` ist sauber, erster Commit gepusht (`chore(repo): initial monorepo scaffold [AP-0.1]`).

### AP-0.2 — Konzeptdokumente & Design-Referenz übernehmen

**Aufgaben:**
- `Fachliches_Konzept_V3.md` nach `docs/concept/` kopieren (unverändert).
- `Technisches_Konzept_V6.md` nach `docs/concept/` kopieren (unverändert).
- `Dashboard.html` nach `docs/design-reference/` kopieren (unverändert).
- `docs/concept/README.md` anlegen: ein Absatz, der erklärt, dass diese beiden Dateien die verbindliche fachliche/technische Spezifikation sind und bei Widersprüchen zwischen Code und Konzept das Konzept gewinnt (Abweichungen laufen über `QUESTIONS.md`).

**DoD:** Dateien im Repo, Commit gepusht (`docs(concept): import source concept documents [AP-0.2]`).

### AP-0.3 — GitHub-Repo-Zusatzdateien

**Aufgaben:**
- `.github/ISSUE_TEMPLATE/bug_report.md`, `feature_request.md`.
- `.github/PULL_REQUEST_TEMPLATE.md` (auch wenn Solo-Projekt, falls später Beiträge kommen).
- `.github/workflows/`-Ordner anlegen (Inhalt folgt in Phase 11, hier nur Grundgerüst/Platzhalter-Workflow, der aktuell nur "echo ok" macht, damit CI von Anfang an grün ist und in späteren Phasen erweitert wird).

**DoD:** Commit gepusht (`chore(github): add issue/PR templates and CI placeholder [AP-0.3]`).

---

## Phase 1 — FS25-Lua-Mod & Datei-Bridge

**Referenz:** Technisches Konzept, Kapitel „Überblick & Architektur-Grundsatzentscheidung", „Datei-Bridge (Mod ↔ Backend)" (komplett), „Offene technische Punkte".

### AP-1.1 — Mod-Grundgerüst

**Aufgaben:**
- `mod/FS25_RPSim/modDesc.xml` mit Mod-Metadaten (Name, Version, Beschreibung Deutsch, Icon-Platzhalter).
- Ordnerstruktur für Lua-Quellcode (`src/bridge/`, `src/export/`, `src/import/`, `src/util/`).
- Zentrales Einstiegs-Script, das sich beim Laden eines Spielstands registriert (FS25 Mod-Lifecycle-Hooks: `loadMap`/`update`/`saveToXMLFile` — exakte Hook-Namen zum Umsetzungszeitpunkt gegen aktuelle FS25-Modding-Doku verifizieren, falls unsicher in `docs/dev/offene-technische-punkte.md` vermerken statt zu raten).
- Versionsschema für den Mod (SemVer, an `CHANGELOG.md` gekoppelt).

**DoD:** Mod lädt syntaktisch fehlerfrei (`luacheck` gegen den Ordner läuft ohne Fehler), Commit gepusht (`feat(mod): scaffold FS25_RPSim mod structure [AP-1.1]`).

### AP-1.2 — Export: `farm_facts.json`

**Referenz:** Abschnitt „Export-Schema" im Technischen Konzept (vollständiges JSON-Beispiel dort).

**Aufgaben:**
- Modul, das alle ~60s (konfigurierbarer Zyklus, kein Hardcode) einen Snapshot schreibt mit exakt diesen Feldern: `schemaVersion`, `gameTime`, `savegameId`, `liquidity.balance`, `assets.vehicles[]` (`uniqueId`, `value`, `condition` 0–100 aus FS-Verschleißwert), `assets.placeables[]`, `assets.farmland[]`, `assets.animals[]`, `assets.storage[]` (siehe AP-1.6 für Silo-Logik), `liabilities.vanillaLoan`, `prices[]` (siehe AP-1.5).
- Atomares Schreiben: Versuch über Temp-Datei + `os.rename`; falls in der FS25-Lua-Sandbox nicht verfügbar, Marker-Datei-Fallback implementieren (`farm_facts.json.tmp` → Marker `farm_facts.json.ready` → Bridge-Leser wartet auf Marker). Beides bauen, per Config umschaltbar, offene Frage in `docs/dev/offene-technische-punkte.md` vermerken (siehe Grundregel 4).
- `savegameId`-Schutz: siehe AP-1.8, hier bereits die ID mitschreiben.

**DoD:** Unit-Tests für die reine JSON-Serialisierungslogik (ohne echten FS25-Kontext, mit gemocktem Zustand). Commit gepusht (`feat(mod): implement farm_facts.json export [AP-1.2]`).

### AP-1.3 — Export: `market_context.json`

**Aufgaben:**
- Einmaliger Export beim Laden: `mapName`, `sellPoints[]` (`id`, `name`, `acceptedFillTypes`), `fillTypes[]`, `farmlands[]` (`farmlandId`, `hectares`, `price`, `ownerFarmId`, 0 = unbesetzt).
- Sofortiger Re-Export nach jeder angewandten `FARMLAND_TRANSFER`-Instruktion (siehe AP-1.7) — gleiches Muster wie `savegameId`-Sofort-Reexport (AP-1.8).

**DoD:** Tests + Commit (`feat(mod): implement market_context.json export [AP-1.3]`).

### AP-1.4 — Import: Instruktions-Envelope & Idempotenz

**Referenz:** Abschnitte „Import-Schema", „Ack & Idempotenz", „Persistenz im Mod".

**Aufgaben:**
- Parser für den gemeinsamen Envelope und die drei Typen: `MONEY_TRANSACTION` (inkl. vollständigem `reason`-Enum: `CREDIT_DISBURSEMENT`, `CREDIT_INSTALLMENT`, `CREDIT_PENALTY`, `CREDIT_CALLBACK`, `SALARY_PAYMENT`, `EMPLOYEE_EFFECT`, `SUBSIDY`, `STARTING_CAPITAL_ADJUSTMENT`, `FARMLAND_PURCHASE`, `FARMLAND_SALE`, `OTHER`), `PRICE_EVENT` (siehe AP-1.5), `FARMLAND_TRANSFER` (siehe AP-1.7).
- `processedInstructions`-Liste über die offizielle `XMLFile`-Save/Load-Hook-API persistieren (nicht in der Bridge!), inkl. Bereinigung alter Einträge (z. B. nach 30 Spieltagen, konfigurierbar).
- `instructions_ack.json` schreiben (`instructionId`, `appliedAtGameTime`, `status`).
- Idempotenz-Check: eine bereits in `processedInstructions` vorhandene `instructionId` wird ignoriert, nicht erneut angewandt.

**DoD:** Tests für Parsing + Idempotenz-Logik (dieselbe Instruktion zweimal → nur einmal angewandt). Commit (`feat(mod): implement instruction import, ack and idempotency [AP-1.4]`).

### AP-1.5 — Preis-Events anwenden (inkl. Sonderkontrakte)

**Referenz:** Import-Schema (`PRICE_EVENT`), Abschnitt „Preis-Events & Sonderkontrakte" (Technische Abläufe).

**Aufgaben:**
- `MULTIPLIER`-Modus: `peakMultiplier`, `rampUpHours`, `holdHours`, `decayHours` deterministisch aus `gameTime` berechnen (Ramp-Up → Hold → Decay), Persistenz in `activePriceEvents` via `XMLFile`.
- `FIXED`-Modus (Sonderkontrakt): `fixedPrice`, `maxQuantity`, `deadlineGameTime`, wirkt sofort voll, hat Vorrang vor gleichzeitigem `MULTIPLIER` an selbem Verkaufsort/Fruchtart.
- Mengen-Tracking über `SellingStation:getEffectiveFillTypePrice`/`SellingStation:sellFillType`-Hook; Rückkanal `deliveredQuantity`/`maxQuantity`/`endReason` in `instructions_ack.json` ergänzen.
- Anwendung an der konkreten Verkaufsstelle (Preis überschreiben).

**DoD:** Tests für Ramp-Berechnung bei verschiedenen `gameTime`-Werten (vor Ramp, in Ramp, in Hold, in Decay, nach Ende), Tests für Vorrang-Logik FIXED vs. MULTIPLIER. Commit (`feat(mod): apply price events including fixed contracts [AP-1.5]`).

### AP-1.6 — Silo-Warenbestand erfassen

**Referenz:** Fachkonzept „Silo-Warenbestand & Marktpreis-Übersicht", Technisches Konzept `assets.storage`-Schema + offene Frage „Welche Lua-API FS25 für Silo-Füllstände bereitstellt und wie sich klassische Silos zuverlässig von Fahrsilo/Bunkersilo und Halle unterscheiden lassen".

**Aufgaben:**
- Alle klassischen Silogebäude der Farm identifizieren (**bewusst nicht** Fahrsilo/Bunkersilo/Hallen).
- Je Fruchtart über alle Silos aggregieren: `{ fillType, amount, capacity }`.
- In `farm_facts.json` unter `assets.storage[]` einhängen (gleicher ~60s-Zyklus wie der Rest).
- **Wichtig:** Da die exakte Lua-API zur Unterscheidung „klassischer Silo" vs. Fahrsilo/Bunkersilo/Halle laut Konzept ein offener technischer Punkt ist: bestmögliche Implementierung bauen (z. B. über Objekt-/Placeable-Typkennung), klar kommentieren, in `docs/dev/offene-technische-punkte.md` eintragen, **nicht blockieren**.

**DoD:** Tests für die Aggregationslogik (mehrere Silos derselben Fruchtart → korrekt summiert; Fahrsilo wird ausgeschlossen, sofern über Testdouble simulierbar). Commit (`feat(mod): export classic-silo storage inventory [AP-1.6]`).

**Auch erfassen:** laufende Verkaufspreise je Verkaufsstelle/Fruchtart als `prices[]` in `farm_facts.json` (unabhängig davon, ob gerade ein `PRICE_EVENT` aktiv ist) — gleicher Commit oder eigener Folgecommit, deutlich benannt.

### AP-1.7 — Farmland-Besitzübertragung (`FARMLAND_TRANSFER`)

**Referenz:** Fachkonzept „Verhandlungssystem", Technisches Konzept Import-Schema (`FARMLAND_TRANSFER`), offene Frage zur passenden Lua-API (Vorbild Community-Mod „Farmland Marketplace").

**Aufgaben:**
- `direction: TO_PLAYER` (Kauf) / `FROM_PLAYER` (Verkauf) anwenden: passende FS25-Ownership-API aufrufen.
- Nach erfolgreicher Anwendung: sofortigen Re-Export von `market_context.json` auslösen (AP-1.3).
- `price` im Envelope ist nur Referenz/Logging — die eigentliche Kontobewegung läuft als separate, parallele `MONEY_TRANSACTION` (`FARMLAND_PURCHASE`/`FARMLAND_SALE`), **beide Instruktionen derselben Batch werden unabhängig, aber garantiert gemeinsam verarbeitet**.
- Offene API-Frage dokumentieren wie in Grundregel 4 beschrieben, bestmögliche Implementierung liefern.

**DoD:** Tests für Envelope-Parsing und Re-Export-Trigger. Commit (`feat(mod): implement farmland ownership transfer [AP-1.7]`).

### AP-1.8 — `savegameId`-Schutz

**Aufgaben:**
- Jede Export-/Import-Datei trägt `savegameId`.
- Instruktionen mit abweichender `savegameId` werden verworfen (geloggt, nicht stillschweigend ignoriert).
- Beim Laden eines Spielstands: sofortiger frischer Export statt Warten auf nächsten Zyklus.

**DoD:** Tests (abweichende ID → Instruktion verworfen; gleiche ID → angewandt). Commit (`feat(mod): guard bridge files with savegameId [AP-1.8]`).

### AP-1.9 — Robustheit & Fehlerbehandlung

**Aufgaben:**
- Fehlerhafte/unvollständige JSON-Dateien (z. B. während Backend gerade schreibt) dürfen den Mod nicht zum Absturz bringen — defensives Parsing, bei Fehler: überspringen + nächsten Zyklus erneut versuchen, Warnung ins FS25-Log.
- Alle in AP-1.2–1.8 gebauten Module gegen fehlende/leere Bridge-Ordner testen (erster Start, Ordner existiert noch nicht → anlegen statt Crash).

**DoD:** Tests für die genannten Fehlerfälle. Commit (`fix(mod): defensive parsing and bootstrap of bridge folders [AP-1.9]`).

### AP-1.10 — Mod-Testsuite & Mod-README

**Aufgaben:**
- `luaunit`- oder `busted`-Testsuite unter `mod/tests/` für alle reinen Logikfunktionen (Ramp-Berechnung, Aggregation, Envelope-Parsing, Idempotenz, savegameId-Schutz) bündeln, lokal ausführbar (`docs/dev/` referenziert den Befehl).
- `mod/README.md`: Was der Mod tut, was er **nicht** tut (keine Spiellogik, kein KI-Call), wie er installiert wird (Kopieren nach `Documents/My Games/FarmingSimulator2025/mods/`), welche Ordnerstruktur er in `modSettings/FS25_RPSim/` erzeugt.

**DoD:** Testsuite läuft grün, README vorhanden, Commit (`test(mod): consolidate lua test suite and mod README [AP-1.10]`).

---

## Phase 2 — Bridge-Simulator (Testwerkzeug ohne FS25)

**Warum:** Claude Code kann kein echtes FS25 starten. Ohne dieses Werkzeug lassen sich Backend und Frontend nicht end-to-end testen. Kein Teil des eigentlichen Produkts, sondern internes Entwicklungswerkzeug.

### AP-2.1 — Simulator-CLI: Export erzeugen

**Aufgaben:**
- Eigenständiges Tool unter `tools/bridge-simulator/` (Sprache frei wählbar, empfohlen: Node.js für schnelle Iteration oder ein kleines Java/Spring-CLI, falls engere Kopplung an Backend-DTOs gewünscht — Entscheidung selbst treffen und in `tools/bridge-simulator/README.md` begründen).
- Schreibt periodisch (konfigurierbares Intervall) `farm_facts.json` und einmalig `market_context.json` exakt im Schema aus Phase 1 in einen konfigurierbaren Ordner (default: simuliert `modSettings/FS25_RPSim/export/`).
- Mehrere vordefinierte **Szenarien** als Presets: `leerer-hof` (Start ohne Vermögen), `verschuldeter-hof` (aktiver Vanilla-Kredit, wenig Eigenkapital), `wohlhabender-hof` (volle Silos, hohe Liquidität), `voller-silobestand` (Fokus Warenbestand-Bewertung). Szenario per CLI-Flag wählbar, Werte im Zeitverlauf leicht simuliert veränderbar (z. B. Kontostand steigt/fällt).

**DoD:** Simulator erzeugt valide Dateien, die gegen das JSON-Schema aus Phase 1 validieren. Commit (`feat(tools): bridge simulator export generation [AP-2.1]`).

### AP-2.2 — Simulator: Instruktionen konsumieren

**Aufgaben:**
- Liest `instructions.json`, wendet `MONEY_TRANSACTION`/`PRICE_EVENT`/`FARMLAND_TRANSFER` auf den simulierten internen Zustand an (gleiche Idempotenz-Logik wie der echte Mod), schreibt `instructions_ack.json`.
- Nach `FARMLAND_TRANSFER`: `market_context.json` neu schreiben (wie der echte Mod).

**DoD:** End-to-End-Testfall: Backend schreibt Instruktion → Simulator wendet an → Backend liest Ack → nächster `FactsSnapshot` zeigt neuen Zustand. Commit (`feat(tools): bridge simulator instruction consumption [AP-2.2]`).

### AP-2.3 — Simulator-Doku & Bedienung

**Aufgaben:**
- `tools/bridge-simulator/README.md`: Start, Szenario-Wahl, wie man den Simulator neben Backend + Frontend laufen lässt, um das komplette Tool ohne FS25 manuell durchzuklicken.

**DoD:** Commit (`docs(tools): bridge simulator usage guide [AP-2.3]`).

---

## Phase 3 — Backend-Grundgerüst & Domänenmodell

**Referenz:** Technisches Konzept, Kapitel „Spring-Boot-Domänenmodell" (komplett).

### AP-3.1 — Projekt-Setup

**Aufgaben:**
- Spring-Boot-Projekt unter `backend/` (Maven, aktuelle stabile Spring-Boot-Version zum Umsetzungszeitpunkt prüfen), Java-Version passend dazu.
- Profile `dev` (H2 file-mode, Simulator-Pfad als Default) und `prod` (echter Mod-Pfad).
- `application.yml`-Grundgerüst mit eigener Konfigurations-Sektion `rpsim.formulas.*` für **alle** Platzhalterwerte aus dem Formel-Kapitel (Bonität, Satisfaction, Trust, Verhandlung, Dorf-Ansehen, Rotation-Budget) — an dieser Stelle nur die Struktur anlegen, Werte werden mit den jeweiligen Services in Phase 4 befüllt.
- `application-local.yml.example` als Vorlage für lokale, nicht committete API-Keys.

**DoD:** `mvn clean install` läuft grün (leeres Grundgerüst mit Health-Endpoint). Commit (`chore(backend): bootstrap spring boot project [AP-3.1]`).

### AP-3.2 — Domänenmodell (Entities) exakt nach Konzept

**Aufgaben — jede Entity mit allen im Konzept genannten Feldern, JPA-Annotationen, sinnvollen Indizes:**
- `Savegame` (Aggregatwurzel: `mapName`, `currentGameTime`, `tonePreset`)
- `Character` (Rolle, `Status` ∈ `ACTIVE/ON_LEAVE/TERMINATED`, `terminationReason` bei `TERMINATED`, `trustScore`, Fakten-Akte-Felder je Rolle, Persönlichkeits-Layer: Name, 3–5 Charakterzüge, Sprachstil, Hintergrundgeschichte, Beziehungen)
- `TrustEvent` (append-only Log)
- `Loan` / `CreditApplication` (inkl. `submittedAtGameTime`, `decisionVisibleAtGameTime`, `blocksNewCredit`)
- `MarketEvent` (inkl. Sonderkontrakte, `isAccurate`-Flag für Gerüchte)
- `FarmlandOwnership` (Besitzer PLAYER/Character/UNCLAIMED, Referenzpreis)
- `Negotiation` / `NegotiationOffer` (Gütertyp, Richtung, Status, Rundenzähler, Gebotshistorie)
- `Employee` / `SatisfactionEvent`
- `JobApplication`
- `Communication` (vereinheitlicht Mail **und** Anruf: `channel` ∈ MAIL/CALL, `initiatedBy` ∈ PLAYER/CHARACTER, `relatedEntityId`)
- `OutboxInstruction` (Backing Store für `instructions.json`)
- `FactsSnapshot` (Zeitreihe, Basis für Cashflow-Trend, Warenbestand, Preisverlauf)
- `StoryHook` (gestaffelt geplante Onboarding-Hooks)
- `PublicActionEvent` (savegame-weites Log fürs Dorf-Ansehen)
- `DiaryEntry`
- `NarrationJob` (analog zu `OutboxInstruction`, siehe Phase 5)
- Jede Entity referenziert `Savegame` als Aggregatwurzel (Fremdschlüssel), keine spielstandübergreifende Wiederverwendung (Fachkonzept-Entscheidung: Vorgeschichten sind keine Vorlagen).

**DoD:** Flyway-Migrationsskripte für alle Entities, `mvn test` grün inkl. Repository-Smoke-Tests (Speichern/Laden je Entity). Commit (`feat(backend): domain model and flyway migrations [AP-3.2]`).

### AP-3.3 — File-Bridge-Reader/Writer im Backend

**Referenz:** Datei-Bridge-Kapitel, `savegameId`-Schutz auch backend-seitig.

**Aufgaben:**
- Scheduler, der `export/farm_facts.json` pollt, in `FactsSnapshot` persistiert (nur Rohzustände übernehmen, keine abgeleiteten Kennzahlen — die berechnet das Backend selbst).
- Scheduler/Reader für `export/market_context.json` (bei Änderung neu einlesen).
- Writer für `import/instructions.json` aus der `OutboxInstruction`-Tabelle (Envelope exakt wie im Mod-Schema).
- Reader für `import/instructions_ack.json`, Abgleich gegen `OutboxInstruction`.
- `savegameId`-Konsistenzprüfung auch backend-seitig (Warnung/Log bei Abweichung, kein Crash).
- JSON-Schema-Validierung vor dem Verarbeiten (defensiv gegen unvollständige Schreibvorgänge des Mods, analog AP-1.9).

**DoD:** Integrationstest mit dem Bridge-Simulator aus Phase 2 (Backend liest echte vom Simulator geschriebene Dateien). Commit (`feat(backend): file bridge reader and writer [AP-3.3]`).

---

## Phase 4 — Backend: Fakten-Ebene-Services (alle Module)

**Referenz:** Technisches Konzept, Kapitel „Kern-Services", „Formeln der Fakten-Ebene" (komplett), „Technische Abläufe" (komplett). Fachliches Konzept für den fachlichen Kontext jedes Moduls.

**Grundregel für diese ganze Phase:** Alle Zahlen/Schwellen aus den folgenden Formeln als Konfiguration (`rpsim.formulas.*`) mit exakt den im Konzept genannten Platzhaltern als Default. Jeder Service ist reines Java, **kein** KI-Call (KI kommt erst in Phase 5).

### AP-4.1 — `TrustScoreService`

**Aufgaben:**
- Gedeckelter Score aus `TrustEvent`-Historie berechnen.
- Decay bei Inaktivität (Zeit seit letztem Event → Score nähert sich neutral an).
- API: `getCurrentTrust(characterId)`, `recordEvent(characterId, delta, reason)`.

**DoD:** Tests: Cap-Grenzen werden nie überschritten, Decay-Verhalten über simulierte Zeit. Commit (`feat(backend): trust score service [AP-4.1]`).

### AP-4.2 — `CreditScoringService` + Kredit-Lebenszyklus

**Referenz:** Formel-Abschnitt „Bonitäts-Score" und „Zahlungsausfall-Eskalation".

**Aufgaben:**
- `coreScore`-Berechnung exakt nach Formel: `0.30·debtServiceCoverage + 0.25·equityRatio + 0.15·liquidityBuffer + 0.15·loanToFarmSize + 0.15·paymentHistoryScore` (Start ohne Historie: neutral ~70), jede der fünf Kennzahlen 0–100 normalisiert **mit Sättigung** (kein linearer Verlauf ins Unendliche — konkrete Sättigungsfunktion selbst festlegen, z. B. geclampte Skalierung, im Code kommentieren).
- `equityRatio` **inklusive** `storageValue` (siehe unten).
- `debtServiceCoverage` aus gleitendem Durchschnitt der `FactsSnapshot`-Zeitreihe (z. B. 30 Spieltage, konfigurierbar).
- `trustBonus = clamp(trustScore/10, -8, +8)`, `finalScore = clamp(coreScore + trustBonus, 0, 100)`.
- Schwellen: `≥75` Voll genehmigt, `45–75` Gegenangebot (Konditionen skaliert nach Lücke bis 75), `<45` Abgelehnt mit grober `reasonCategory` (z. B. `INSUFFICIENT_EQUITY`) — **niemals die Rohzahl nach außen geben**.
- **Sicherheitsprinzip testen:** Trust-Spielraum (±8) bleibt immer kleiner als Schwellenabstand (30 Punkte) — expliziter Test, der das absichert.
- **Warenbestand-Bewertung:** `storageValue = Σ(amount · bestAvailablePrice)` je Fruchtart, `bestAvailablePrice` = höchster `currentPrice` dieser Fruchtart aus `prices`; geht in dieselbe Vermögenswert-Summe wie Maschinen/Gebäude/Flächen/Tierbestand ein, bevor `equityRatio` normalisiert wird.
- Kreditantrag-Ablauf: `POST /api/credit-applications` (Endpoint-Anbindung folgt in Phase 6, hier die Service-Logik) — Antrag mit Betrag/Zweck/Laufzeit, `submittedAtGameTime`/`decisionVisibleAtGameTime` (siehe AP-4.8 Bearbeitungszeit-Muster), Score sofort berechnen, Ergebnis erst ab `decisionVisibleAtGameTime` sichtbar/an `NarrationJob` übergeben.
- Bei Genehmigung: `CREDIT_DISBURSEMENT`-Instruktion + `Loan` mit automatischem monatlichem Abzug bis Tilgung (`PayrollScheduler`, siehe AP-4.5).
- **Zahlungsausfall-Eskalation** vollständig: Rate überfällig → Mahnung (`NarrationJob`, keine Geldbewegung) → weiterhin überfällig → `CREDIT_PENALTY` (`MONEY_TRANSACTION`) → weiterhin überfällig → `TrustEvent` negativ → wiederholter Ausfall → `CREDIT_CALLBACK` (volle Restschuld) **oder** `Loan.blocksNewCredit=true`. Alle Schwellen (Tage überfällig) als Konfiguration.
- Eine öffentlich gewordene `CREDIT_CALLBACK`-Fälligstellung erzeugt automatisch `PublicActionEvent(PUBLIC_DEFAULT)`.
- Stundung: Antrag per Mail möglich, formelbasiert geprüft (eigene kleine Formel/Schwellenwert-Check, als Konfiguration).

**DoD:** Umfangreiche Tests: alle drei Ergebnis-Stufen, Grenzfälle exakt an den Schwellen (74.9 vs. 75.0 etc.), vollständige Eskalationskette Schritt für Schritt, Trust-Cap-Sicherheitstest. Commit(s) (`feat(backend): credit scoring service and loan lifecycle [AP-4.2]`).

### AP-4.3 — `MarketEventEngine`

**Referenz:** Fachkonzept „Event-/Preissystem", Technisches Konzept „Preis-Events & Sonderkontrakte".

**Aufgaben:**
- Spawn: täglicher Wahrscheinlichkeits-Roll + Deckel für gleichzeitig aktive Events (Konfiguration).
- Event-Typen: Nachfrage-Spitze/-Einbruch, Ernteausfall/-Überschuss, befristetes Sonderangebot/Ausschreibung (Fixpreis), Subventions-/Förderankündigung, Gerücht.
- Zielauswahl aus `market_context.json`, **gewichtet nach vorhandenem Warenbestand** (`assets.storage`) — Fruchtart ohne Bestand seltener Ziel.
- Regionalität: Events wirken auf einzelne Verkaufsorte, nicht global.
- Stärke/Dauer randomisiert innerhalb typgebundener Bänder (Konfiguration).
- **Gerücht-Mechanik:** ~70 % referenzieren ein reales, geplantes Event mit verzerrter Beschreibung, ~30 % komplett erfunden, gesteuert über `isAccurate`-Flag, das in den Prompt-Kontext für Phase 5 wandert.
- Charakterauswahl nach Rollen-Mapping (Landhändler/Genossenschaft für Marktevents, Nachbar für Ernte/Gerüchte).
- `SUBSIDY` erzeugt `MONEY_TRANSACTION` statt `PRICE_EVENT`.
- Sonderkontrakt = `PRICE_EVENT` mit `priceMode: FIXED`; „Aushandeln" = reine Teilnahme-Entscheidung, keine Freitext-Preisverhandlung.

**DoD:** Tests für Gewichtungslogik (Fruchtart mit Bestand wird statistisch häufiger gewählt — z. B. über viele simulierte Spawns), Tests für Gerücht-Verteilung (~70/30 über große Stichprobe), Tests für Regionalitäts-Isolation. Commit (`feat(backend): market event engine [AP-4.3]`).

### AP-4.4 — `NegotiationEngine` + `FarmlandOwnershipService`

**Referenz:** Fachkonzept „Verhandlungssystem", Technisches Konzept „Verhandlungs-Preisfindung" + „Verhandlungssystem" (Technische Abläufe).

**Aufgaben:**
- **Preisfindung Kauf:** `minAccept = basePrice·(1−stubbornnessDiscount)`, `stubbornnessDiscount ∈ [0.05, 0.20]` aus Charakterzug; `trustAdjustment = clamp(trustScore/20, -0.05, +0.05)`; `effectiveMinAccept = minAccept·(1−trustAdjustment)`; Gebot ≥ `effectiveMinAccept` → Angenommen; ≥ `0.9·effectiveMinAccept` → Gegenangebot (= `effectiveMinAccept`); sonst Abgelehnt. **Maximal drei Runden**, danach endgültig abgelehnt.
- **Versteigerung — NPC-Mitgebote (deterministisch, kein KI-Call):** `npcMaxBid = basePrice·random(0.9, 1.15)` (Charakter-Seed), Gewinner = höchstes Gebot, bei Gleichstand entscheidet Trust des Spielers zum ausschreibenden Charakter.
- **Verkauf eigener Felder (Rollen vertauscht):** `npcCounterOffer = askingPrice·random(0.85, 1.0)`, gleiche Rundenlogik; ob überhaupt Interesse besteht, per Schwellenwert-Check auf Platzhalter-Vermögensfeld je Charakter (Konfiguration).
- **Ablauf Versteigerung (system-initiiert):** `NegotiationEngine` würfelt Spawn-Kadenz (Konfiguration), wählt Ziel-Farmland aus `market_context.json` (`ownerFarmId=0` oder verkaufsbereiter NPC), Ankündigung über `NarrationJob` (Landhändler/Genossenschaft), Spieler bietet über API.
- **Ablauf Direktverhandlung (spieler-initiiert):** aus Charakter-Detailansicht für jeden `Character` mit aktiver `FarmlandOwnership`, `initiatedBy=PLAYER`, ohne Mitbieter, sonst gleiche Pipeline.
- **Ein Feld, eine Verhandlung:** Solange zu einem `farmlandId` eine `Negotiation` im Status `OPEN` existiert, blockiert die Engine weitere Versteigerungs-/Direktverhandlungs-Starts für dasselbe Feld — direkt in der Spawn-/Anlage-Logik, nicht nur als Doku-Regel.
- **Abschluss:** bei `Negotiation.status=ACCEPTED` synchron **zwei** `OutboxInstruction`-Einträge derselben Batch erzeugen: `FARMLAND_TRANSFER` (passende `direction`) **und** `MONEY_TRANSACTION` (`FARMLAND_PURCHASE`/`FARMLAND_SALE`) — nie einzeln.
- **`FarmlandOwnershipService` — Ownership-Abgleich:** bei jedem `FactsSnapshot` eigene `assets.farmland`-Liste + `farmlands` aus `market_context.json` gegen letzten `FarmlandOwnership`-Stand vergleichen. Neuer eigener Farmland-Eintrag ohne zugehörige `FARMLAND_TRANSFER`-Instruktion (Vanilla-Kauf am Tool vorbei) → Besitz kommentarlos nachführen, keine Sperre. Symmetrisch für verschwundenes eigenes Farmland.
- Keine Verpachtung — nur echter Besitzerwechsel (bewusste Fachkonzept-Entscheidung, nicht implementieren).
- **Namensgebung generisch halten** (`NegotiationEngine`, nicht `FarmlandNegotiationEngine` o. ä.) — die Engine ist laut Fachkonzept „bewusst nicht ackerland-spezifisch benannt", damit spätere Erweiterungen auf weitere handelbare Güter (kein Ziel für V1) ohne Bruch möglich wären. `Gütertyp`/`AssetType` als Enum mit aktuell genau einem Wert `FARMLAND` anlegen, nicht als Freitext oder Klassenname hart verdrahten.

**DoD:** Tests: Preisfindungs-Formel inkl. Trust-Cap-Sicherheitstest (Trust verzerrt Grundpreis nie beliebig — analog Bonität), 3-Runden-Limit, „ein Feld eine Verhandlung"-Blockade, Transaktions-Atomarität (FARMLAND_TRANSFER + MONEY_TRANSACTION immer gemeinsam), Ownership-Abgleich-Szenarien (Zugang/Abgang ohne Tool-Instruktion). Commit(s) (`feat(backend): negotiation engine and farmland ownership service [AP-4.4]`).

### AP-4.5 — `SatisfactionService`, `HiringService`, `PayrollScheduler`

**Referenz:** Fachkonzept „Mitarbeitersystem", Technisches Konzept „Satisfaction-Formel", „Kündigung & Bewerbung".

**Aufgaben — Satisfaction:**
- Vier Kategorien: `payFairness` (Event, Zerfall/Gehaltserhöhung), `workload` (Event, Zerfall/freier Tag), `appreciation` (Event, Zerfall/Mail-Gespräch), `workingConditions` (**Live**, direkt aus `condition`-Feld der Fahrzeuge).
- `satisfactionScore = 0.25·Σ(vier Kategorien)`, `effectMultiplier = clamp(satisfactionScore/100, 0.5, 1.2)`, `employeeEffectAmount = baselineOutputValue·(effectMultiplier−1.0)`, monatlich als `EMPLOYEE_EFFECT`-Instruktion (**kein** FS-Parameter-Eingriff, reiner Tool-interner ökonomischer Effekt — Architektur-Entscheidung, siehe Technisches Konzept „Klarstellung Mitarbeiter/Skill").
- Kündigungs-Eskalation: `≥14 Tage <30 Punkte am Stück` → Warn-Mail (`NarrationJob`), `≥30 Tage am Stück` → Kündigung, `Status=TERMINATED`.
- Gehaltsverzug (zu wenig Liquidität im `FactsSnapshot`) triggert automatisch negatives `payFairness`-`SatisfactionEvent`.

**Aufgaben — Hiring:**
- Stellenausschreibung → `HiringService` generiert 3–5 Kandidaten (Skill/Gehalt deterministisch, Identität über `CharacterGeneratorService`), pro Kandidat ein `NarrationJob` für die Bewerbung.
- Simuliertes Vorstellungsgespräch per Mail/Anruf, `<spieler_nachricht>`-Kapselung (Phase 5), Skill/Gehalt bleiben fix.
- Bei Einstellung: übrige Bewerbungen automatisch abgelehnt, neuer `Employee` startet mit neutraler Satisfaction (~70).
- Rollenspezifische Skills (Maschinenführer, Mechaniker, Tierpfleger, Bürokraft — Bürokraft verkürzt ggf. Bearbeitungszeit bei Bank-/Event-Interaktionen leicht) als reine Tool-Werte, kein FS-Parameter-Mapping.

**Aufgaben — Payroll:**
- `PayrollScheduler` ausgelöst durch Spielzeit-Sprünge in eingehenden `FactsSnapshot`s (kein Realzeit-Cron): monatliche Gehaltsabbuchung, `CREDIT_INSTALLMENT`-Tilgungen.

**DoD:** Tests für Satisfaction-Formel-Grenzen (`effectMultiplier`-Clamp), Kündigungs-Eskalation über simulierte Tage, Hiring-Pool-Generierung (3–5 Kandidaten), Gehaltsverzug-Trigger. Commit(s) (`feat(backend): employee satisfaction, hiring and payroll [AP-4.5]`).

### AP-4.6 — `CharacterGeneratorService`, `VillageReputationService`, `VillageRotationService`, Pflichtrollen-Abwesenheit

**Referenz:** Fachkonzept „Charaktersystem", „Dorfweites Ansehen"; Technisches Konzept „Dorf-Ansehen", „Pflichtrollen-Abwesenheit", „Dynamische-Charaktere-Rotation".

**Aufgaben — CharacterGeneratorService:**
- Deterministische Identität (Rolle/Name/Fakten-Akte), wiederverwendet bei Onboarding, Bewerberpool und Rotation-Zuzug.
- Optionale KI-Anreicherung für Hintergrundgeschichte (Anbindung an Phase 5, hier nur die Schnittstelle vorbereiten — bei KI-Fehler bleibt deterministische Kurzbeschreibung stehen).

**Aufgaben — Dorf-Ansehen:**
- `trustAverage` = Mittelwert aller `ACTIVE`-Character-Trust, `publicActionSum = Σ PublicActionEvent.delta` mit Zerfall über Spielzeit, `villageReputationScore = clamp(0.6·trustAverage + 0.4·publicActionSum, -100, 100)`.
- `baseTrustForNewCharacter = clamp(villageReputationScore·0.2, -15, +15)` — für neue Charaktere (Onboarding **und** Rotation-Zuzug gleichermaßen nutzen).
- Stufen: `≥+25` „gut angesehen", `-25..+25` „neutral", `≤-25` „umstritten" — **API gibt nach außen nur die Stufe zurück, nie den Rohwert.**
- Öffentlich gewordene Kredit-Fälligstellung → `PublicActionEvent(PUBLIC_DEFAULT)` (Verknüpfung zu AP-4.2).

**Aufgaben — Pflichtrollen-Abwesenheit:**
- Periodischer Zufalls-Roll (niedrige Wahrscheinlichkeit, Konfiguration) versetzt Pflichtrollen-`Character` für randomisierte Dauer in `Status=ON_LEAVE`.
- Pro Fall zufällig eine von zwei Varianten: **verzögerte Antwort** (fixe Zusatzverzögerung + Abwesenheitsnotiz, kein neuer Charakter) oder **Vertretungs-Charakter** (temporärer `Character`, gleiche Fakten-Akte/Rolle, nach Ende `TERMINATED`, Original zurück auf `ACTIVE`).
- Fakten-Akte bleibt in beiden Fällen an Rolle/Savegame, nicht an Person (konsistent zur Rollenwechsel-Regel).

**Aufgaben — Dynamische-Charaktere-Rotation:**
- `Savegame.dynamicRotationsThisYear`-Zähler, Reset bei Jahreswechsel, Deckel `maxDynamicRotationsPerYear` (Platzhalter 1–2, Konfiguration) — gilt für Zuzug **und** Wegzug gemeinsam. Nur dynamische Charaktere betroffen, nicht Pflichtrollen oder `Employee`.
- `VillageRotationService` würfelt periodisch (Konfiguration), nur solange Budget übrig:
  - **Wegzug:** zufälligen `ACTIVE`-dynamischen `Character` wählen, `Status=TERMINATED`, `terminationReason` (`MOVED_AWAY`/`RETIREMENT`, zufällig gewichtet), automatische Abschieds-`Communication` + `DiaryEntry`.
  - **Zuzug:** `CharacterGeneratorService` erzeugt Rolle/Name/Fakten-Akte, optionale KI-Hintergrundgeschichte, Start-Trust über `baseTrustForNewCharacter`, kurze Vorstellungs-Mail.
- Jahreswechsel-Trigger: da das FS25-Perioden-/Jahreszeiten-Feld ein offener technischer Punkt ist (siehe Grundregel 4), **Fallback:** fixer Spieltage-Zähler ab Savegame-Start (z. B. 12 Spielmonate = 1 Jahr, Konfiguration), sauber austauschbar, sobald die echte Quelle geklärt ist. In `docs/dev/offene-technische-punkte.md` eintragen.

**DoD:** Tests: Dorf-Ansehen-Formel inkl. Stufen-Grenzen, Rotation-Budget-Deckel (nie überschritten, Zuzug+Wegzug zählen gemeinsam), Abwesenheits-Varianten (beide Pfade), Fakten-Akte-Kontinuität bei Rollenwechsel/Vertretung. Commit(s) (`feat(backend): character generation, village reputation and rotation [AP-4.6]`).

### AP-4.7 — `ToneClassifier` & Ton-/Genre-Konfigurationsprofile

**Referenz:** Fachkonzept „Freie Antworten des Spielers", „Ton-/Genre-Leitplanken"; Technisches Konzept „Ton-/Genre-Konfigurationsprofile".

**Aufgaben:**
- `ToneClassifier`: **deterministisch**, Keyword-/Lexikon-basiert (kein KI-Call), ordnet Spielerantwort grob ein (freundlich/neutral/schroff), Ergebnis fließt **gedeckelt** in Trust ein (gleiche Logik wie `TrustScoreService`).
- Mechanische Freitext-Wünsche (z. B. „gib mir einen besseren Zins") werden erkannt (einfache Muster-/Keyword-Erkennung reicht laut Konzept) und lösen **keine** Formel-Änderung aus — nur ein Hinweis-Flag für den Prompt in Phase 5, der die KI-Figur höflich auf den offiziellen Prozess zurücklenkt.
- `Savegame.tonePreset` (fix seit Onboarding, ∈ idyllisch-entspannt/realistisch-ausgewogen/hart-dramatisch) wirkt als: (a) Textbaustein im KI-Prompt (Phase 5), (b) alternatives Konfigurationsprofil einzelner Formeln — **explizit nur am Beispiel Bank** umsetzen: `CreditConfig.HART`: Vollgenehmigt ≥85, Gegenangebot ≥55, Trust-Cap enger (±5 statt ±8). Andere Formeln bleiben unverändert (bewusste Konzept-Vorgabe: nicht durchgängig auf alle Formeln).

**DoD:** Tests: ToneClassifier-Klassifikation an Beispielsätzen, Trust-Deckelung, `CreditConfig`-Profilwechsel HART vs. Default. Commit (`feat(backend): tone classifier and tone configuration profiles [AP-4.7]`).

### AP-4.8 — Onboarding-Orchestrierung & `StoryHookScheduler`

**Referenz:** Fachkonzept „Vorgeschichte & Onboarding-Ablauf"; Technisches Konzept „Onboarding & Zwei-Phasen-Verknüpfung", „Kreditantrag & Bearbeitungszeit".

**Aufgaben:**
- Zwei-Phasen-Ablauf exakt: (1) Web-Assistent **vor** dem FS25-Spielstand — Vorgeschichte-Bausteine (Ursprung, Dorf-Verhältnis `villageRelation` ∈ `UNKNOWN/STRAINED/CONNECTED`, `UNKNOWN`=neutraler Default ohne Trust-Offset) + optionales Freitextfeld + Mitarbeiter-Ausgangslage (Anzahl/Rollen) + `startingCapitalTarget` (exakter Zielbetrag) + optionaler `legacyLoanAmount` + Ton-Preset-Wahl, mit Vorschau (Name/Rolle/Kurzbeschreibung je generiertem Charakter) und **Reroll** (einzeln oder gesamt). (2) Spieler erstellt/lädt Spielstand in FS25. (3) Mod meldet neue `savegameId` beim ersten Export. (4) Web-App zeigt unverknüpfte `savegameId`s (Kartenname + Zeitpunkt) zur Bestätigung. (5) Savegame materialisiert, Start-Mitarbeiter angelegt, Story-Hooks gestaffelt eingeplant.
- Generierung zweistufig: `CharacterGeneratorService` deterministisch (funktioniert offline), optionale KI-Anreicherung für Hintergrundgeschichte inkl. Freitextfeld — bei KI-Fehler bleibt deterministische Kurzbeschreibung.
- **Freitextfeld-Guardrail:** beeinflusst ausschließlich KI-Anreicherung, **nie** Zahlen; bei Filter-Treffer (leichte Inhaltsprüfung, Phase 5) wie leeres Feld behandelt (stiller Fallback, kein Blockieren); leeres/minimales Freitextfeld → stimmige Standard-Startbesetzung.
- `villageRelation` verschiebt Start-Trust generierter Dorf-Charaktere (Konfiguration).
- **Startkapital:** `startingCapitalTarget` und `legacyLoanAmount` unabhängig kombinierbar.
  - `startingCapitalTarget`: Backend kennt tatsächliches FS-Startkapital erst beim ersten `farm_facts.json`-Export (Schritt 3); direkt danach Differenz zwischen `liquidity.balance` und `startingCapitalTarget` **einmalig** als `MONEY_TRANSACTION` (`STARTING_CAPITAL_ADJUSTMENT`) einreihen.
  - `legacyLoanAmount` (optional): durchläuft **nicht** `CreditScoringService`, keine Bearbeitungszeit; `Loan` wird in Schritt (5) direkt mit `Status=ACTIVE`, `remainingAmount=legacyLoanAmount` angelegt (Zinssatz/Laufzeit aus Konfiguration); **kein** `CREDIT_DISBURSEMENT` in die Bridge; ab erstem Spielmonat läuft er wie jeder andere `Loan` über `PayrollScheduler` und reduziert `equityRatio`/`loanToFarmSize` normal.
- **Mitarbeiter-Ausgangslage:** in Schritt (1) angegebene Mitarbeiter fließen in Schritt (5) ein: `HiringService` erzeugt Skill/Gehalt deterministisch je Mitarbeiter (wie im laufenden Bewerberpool), `CharacterGeneratorService` Name/Fakten-Akte, optionale KI-Hintergrundgeschichte, **kein** Vorstellungsgespräch (gelten als bereits angestellt), Start mit neutraler Satisfaction (~70).
- Nach Start: kurze Begrüßungssequenz (z. B. Willkommensmail der Bank).
- `StoryHookScheduler`: 1–2 Story-Hooks aus der Vorgeschichte **gestaffelt über die ersten Spielwochen**, nicht alle sofort.
- **Kreditantrag-Bearbeitungszeit** (gilt allgemein, hier zentral implementiert, da im Onboarding-Kontext eng verwandt): `CreditApplication` mit `submittedAtGameTime` und `decisionVisibleAtGameTime` (+1–2 Spieltage randomisiert, Konfiguration); `CreditScoringService` berechnet sofort, Ergebnis wird aber erst ab `decisionVisibleAtGameTime` an `NarrationJob` übergeben; Frontend zeigt bis dahin „in Bearbeitung".
- Vorgeschichte-Profile werden **nicht** als wiederverwendbare Vorlagen gespeichert (jeder Spielstand frisch).

**DoD:** Integrationstest über den kompletten Onboarding-Flow inkl. Reroll, verzögerte Kredit-Sichtbarkeit, `STARTING_CAPITAL_ADJUSTMENT`-Berechnung, `legacyLoanAmount`-Sonderpfad (kein `CREDIT_DISBURSEMENT`), Mitarbeiter-Ausgangslage ohne Interview. Commit(s) (`feat(backend): onboarding orchestration and story hook scheduler [AP-4.8]`).

### AP-4.9 — Dorfleben-Modul (Glückwünsche, Einladungen, Klatsch)

**Referenz:** Fachkonzept „Dorfleben jenseits der Wirtschaft"; Technisches Konzept „Dorfleben-Modul".

**Aufgaben:**
- Drei Subtypen mit **unterschiedlichem Auslöser** statt einheitlichem Zufalls-Roller:
  - **Glückwünsche** – faktenbasiert, ausgelöst über Cashflow-Trend aus `FactsSnapshot` (Wiederverwendung der Bonitäts-Berechnung aus AP-4.2, z. B. deutlich positiver Trend → Glückwunsch-Kommunikation).
  - **Einladungen** – kalendarisch geplant, analog `StoryHookScheduler` (AP-4.8); das benötigte FS25-Perioden-/Jahreszeiten-Feld ist laut Konzept ein offener technischer Punkt — gleicher Fallback wie beim Rotation-Jahreswechsel (AP-4.6): fixer Spieltage-Zähler, austauschbar, in `docs/dev/offene-technische-punkte.md` vermerkt.
  - **Klatsch** – reiner täglicher Zufalls-Roll, minimaler Fakten-Payload (kein besonderer Auslöser nötig).
- Alle drei münden in dieselbe `Communication`/`NarrationJob`-Pipeline (AP-5.7) wie die übrigen Module, lösen **standardmäßig keine Geldinstruktion** aus.
- Bewusst selten getaktet (Konfiguration), damit die Charaktere zwischen den „wichtigen" Nachrichten lebendig wirken, ohne zu überladen (Fachkonzept-Vorgabe).

**DoD:** Tests je Subtyp (Trigger-Bedingung korrekt erkannt, keine Geldinstruktion erzeugt, korrekte Kadenz-Begrenzung). Commit (`feat(backend): village-life module (congratulations, invitations, gossip) [AP-4.9]`).

---

## Phase 5 — Backend: KI-Adapter (Persönlichkeits-Ebene)

**Referenz:** Technisches Konzept, Kapitel „KI-Adapter (Persönlichkeits-Ebene)" (komplett).

### AP-5.1 — `AiProvider`-Interface & Konfigurationsmechanismus

**Aufgaben:**
- Interface `AiProvider` mit einer Methode, die (System-Prompt-Bausteine, strukturierten Fakten-Payload, Ausgabeformat-Vorgabe) entgegennimmt und ein strukturiertes `{"subject": "...", "body": "..."}`-Ergebnis liefert (oder Fehler wirft → Fallback in AP-5.7).
- Provider-Auswahl, API-Key und Modellname liegen in **lokaler Konfiguration** (`application-local.yml`, nie committet), pro Spieler/Installation, nicht im Code — „eigener API-Key pro Spieler" ist Fachkonzept-Entscheidung.
- Factory/Strategy, die anhand der Konfiguration die aktive `AiProvider`-Implementierung wählt.

**DoD:** Interface + Konfigurationsmechanismus mit Test-Double-Provider (liefert feste Antworten) für spätere Tests. Commit (`feat(backend): ai provider interface and configuration [AP-5.1]`).

### AP-5.2 — `OpenAiProvider`

**Aufgaben:** Implementierung gegen die OpenAI-Chat-Completions-/Responses-API, JSON-Ausgabemodus nutzen, Fehler-/Timeout-Handling, Modellname konfigurierbar.

**DoD:** Test mit gemocktem HTTP-Client (kein echter API-Call in der Testsuite). Commit (`feat(backend): openai provider implementation [AP-5.2]`).

### AP-5.3 — `AnthropicProvider`

**Aufgaben:** Implementierung gegen die Anthropic Messages API, strukturierte JSON-Ausgabe erzwingen (z. B. über Prompt-Vorgabe + Parsing, da kein natives JSON-Mode nötig ist), Modellname konfigurierbar.

**DoD:** Test mit gemocktem HTTP-Client. Commit (`feat(backend): anthropic provider implementation [AP-5.3]`).

### AP-5.4 — `GeminiProvider`

**Aufgaben:**
- Implementierung gegen die Google-Gemini-API (Generative-Language-API), Modellname konfigurierbar.
- **Vor der Implementierung:** aktuell gültigen kostenlosen Modellnamen/Alias gegen die offizielle Google-AI-Studio-Dokumentation prüfen (ändert sich häufig, siehe Abschnitt 3 dieser Datei) und als Default in `application.yml` setzen; Vorgehen und Quelle kurz in `docs/dev/ai-providers.md` (Phase 10) vermerken.

**DoD:** Test mit gemocktem HTTP-Client. Commit (`feat(backend): gemini provider implementation [AP-5.4]`).

### AP-5.5 — `OllamaProvider` (lokal)

**Aufgaben:** Implementierung gegen eine lokale Ollama-Instanz (HTTP, default `http://localhost:11434`), Modellname konfigurierbar, klarer Fehlertext, falls Ollama nicht erreichbar ist (führt zum Fallback-Text, AP-5.7, nicht zum Absturz).

**DoD:** Test mit gemocktem HTTP-Client. Commit (`feat(backend): ollama provider implementation [AP-5.5]`).

### AP-5.6 — Prompt-Building & Injection-Schutz

**Referenz:** Abschnitt „Vier Bausteine im Prompt" (mit vollständigem Beispiel-Prompt im Konzept).

**Aufgaben:**
- Prompt-Builder mit exakt den vier Bausteinen: (1) Charakter-Identität + Guardrails als System-Prompt, (2) Gedächtnis als deterministische Kurzfakten (siehe AP-5.8), (3) fertiger, unveränderlicher Fakten-Payload, (4) Aufgabe + strukturiertes JSON-Ausgabeformat.
- `<spieler_nachricht>`-Kapselung für **jeden** Freitext-Kanal (Mail-Antwort, Anruf-Gespräch, proaktive Nachricht, Bewerbungsgespräch, Verhandlungsgespräch) — Inhalt darin ist Dialog, keine Systemanweisung, Regelsatz weist die KI explizit an, Versuche zur Rollen-/Regel-/Fakten-Änderung darin zu ignorieren.
- Das eigentliche Gebot/die eigentliche Zahl bleibt **immer formularbasiert**, nie Teil des Freitexts.
- `reasonCategory` statt Rohzahlen überall dort, wo die KI eine Entscheidung erzählt (Kredit: `reasonCategory`; Verhandlung: `NEGOTIATION_ACCEPTED`/`NEGOTIATION_COUNTER`/`NEGOTIATION_REJECTED`) — die KI bekommt nie `coreScore` oder `minAccept`.
- Ton-Rahmen (`tonePreset`) als Textbaustein im System-Prompt.

**DoD:** Tests: Prompt enthält keine Rohzahlen aus Formeln (nur Kategorien), `<spieler_nachricht>`-Tag korrekt gesetzt bei allen Freitext-Kanälen, Injection-Testfall (Spielertext versucht Rollenänderung) landet nachweislich innerhalb des Tags. Commit (`feat(backend): prompt builder with injection guardrails [AP-5.6]`).

### AP-5.7 — `NarrationJob`-Pipeline & Fallback-Texte

**Aufgaben:**
- `AiNarrationService` als **einziger** Punkt im Backend, der `AiProvider` aufruft.
- `NarrationJob`-Tabelle analog `OutboxInstruction`: Fakten-Ebene-Services erzeugen nur den Job mit fertigen Fakten, separater Worker ruft KI auf, schreibt Ergebnis als `Communication`-Eintrag.
- Bei Fehler/Timeout: vorbereitete, generische Fallback-Vorlage **pro Ereignistyp** (Kredit genehmigt/abgelehnt/Gegenangebot, Preis-Event, Verhandlungsergebnis, Mitarbeiter-Ereignis, Dorfleben, Onboarding-Begrüßung usw. — vollständige Liste aus allen bisherigen Phasen ableiten, keine Lücke lassen) statt eines sichtbaren Fehlers — „das Spiel bleibt vollständig spielbar" auch ohne funktionierende KI-Anbindung.
- Fallback-Vorlagen **locale-schlüsselig** ablegen (z. B. `fallback-templates/de/*.txt`), auch wenn V1 nur `de` befüllt — konsistent zur Mehrsprachigkeits-Vorbereitung aus AP-7.1, gleiche Fachkonzept-Entscheidung.

**DoD:** Tests: Worker verarbeitet Job → `Communication` entsteht; simulierter Provider-Fehler → Fallback-Text statt Absturz, für **jeden** Ereignistyp mindestens ein Testfall. Commit (`feat(backend): narration job pipeline with fallback templates [AP-5.7]`).

### AP-5.8 — Gedächtnis-Verdichtung

**Aufgaben:**
- Deterministische Kurzfakten aus strukturierten, bereits geloggten Ereignissen ableiten (`TrustEvent`, `Loan`-Entscheidungen, wichtige `DiaryEntry`-Momente) — **keine** KI-generierte Zusammenfassung (vermeidet Drift/Halluzination über Monate).
- Begrenzung auf die relevantesten/jüngsten N Fakten pro Charakter (Konfiguration), damit der Prompt nicht unbegrenzt wächst.

**DoD:** Test: aus einer Ereignis-Historie wird eine erwartbare, stabile Kurzfakten-Liste erzeugt. Commit (`feat(backend): deterministic memory summarization [AP-5.8]`).

### AP-5.9 — Onboarding-Freitext-Moderation

**Aufgaben:**
- Leichte Inhaltsprüfung **nur** für das Onboarding-Freitextfeld (laufende Spieler-Nachrichten verlassen sich bewusst auf die Sicherheitsfilter des KI-Anbieters selbst — bereits getroffene Entscheidung, nicht erneut aufbauen).
- Bei Treffer: Feld wird wie leer behandelt (stiller Fallback), kein Blockieren des Onboardings.

**DoD:** Tests mit Beispiel-Eingaben (unproblematisch/Missbrauchsversuch). Commit (`feat(backend): onboarding free-text moderation [AP-5.9]`).

---

## Phase 6 — Backend: REST-API & Echtzeit

**Referenz:** Technisches Konzept, Kapitel „Angular-REST-Endpunkte" (vollständige Tabelle), „Anruf-Zustandsautomat".

### AP-6.1 — REST-Endpunkte vollständig

**Aufgaben — jede Zeile aus der Endpunkt-Tabelle des Technischen Konzepts 1:1 umsetzen, DTOs, Bean-Validation, Fehler-Responses konsistent, OpenAPI/Swagger-Doku automatisch generiert:**

| Modul | Endpunkte |
| --- | --- |
| Onboarding | `POST /api/onboarding`, `POST /api/onboarding/{id}/reroll`, `POST /api/onboarding/{id}/confirm` |
| Mails | `GET /api/mails`, `GET /api/mails/{id}`, `POST /api/mails/{id}/reply` |
| Anrufe | `GET /api/calls/pending`, `POST /api/calls/{id}/accept`, `POST /api/calls/{id}/decline` |
| Kredit | `POST /api/credit-applications`, `GET /api/loans`, `POST /api/loans/{id}/stundung` |
| Mitarbeiter | `POST /api/job-postings`, `GET /api/job-postings/{id}/applications`, `POST /api/job-postings/{id}/applications/{appId}/interview-question`, `POST /api/job-postings/{id}/applications/{appId}/hire`, `GET /api/employees`, `POST /api/employees/{id}/raise`, `POST /api/employees/{id}/time-off`, `DELETE /api/employees/{id}` |
| Verhandlung | `GET /api/farmlands`, `POST /api/farmlands/{id}/sell-offer`, `GET /api/negotiations`, `POST /api/negotiations/{id}/offer`, `POST /api/negotiations/{id}/withdraw` |
| Warenbestand & Preise | `GET /api/storage`, `GET /api/prices/current`, `GET /api/prices/history?fillType=&sellPoint=&from=&to=` |
| Dorfleben | `GET /api/characters`, `POST /api/characters/{id}/messages`, `GET /api/diary`, `POST /api/diary/entries`, `GET /api/village-reputation` |
| Live-Updates | `GET /api/events/stream` (SSE) |

- Kein Auth in V1 (Konzept-Entscheidung, lokal auf `localhost`), `savegameId` implizit aktiver Kontext — dennoch sauber als Property im Service-Context führen, damit später (falls je LAN-Zugriff kommt) ein einfaches Passwort ergänzt werden kann, ohne alles umzubauen.
- Formularpflicht dort, wo Zahlen entschieden werden (Kreditantrag, Stellenausschreibung, Verhandlungsgebot) strikt einhalten — kein Endpoint nimmt eine Zahl aus Freitext entgegen.
- Proaktive Nachricht (`POST /api/characters/{id}/messages`): Cooldown pro Charakter (z. B. 1 Spieltag, Konfiguration) unterdrückt nur ein erneutes `TrustEvent`, die KI antwortet immer normal weiter; `Communication` mit `initiatedBy=PLAYER`. Bei Charakteren mit `FarmlandOwnership` zusätzlich Möglichkeit, gezielt eine Direktverhandlung anzustoßen (Verweis auf AP-4.4).
- `GET /api/diary`/`POST /api/diary/entries`: automatische Chronik-Einträge + optionale freie Spieler-Notizen **ohne jede mechanische Wirkung**.

**DoD:** Für jeden Endpunkt mindestens ein Integrationstest (`@SpringBootTest` + `MockMvc` oder vergleichbar), inkl. Validierungsfehlerfälle (z. B. Gebot ohne Formularpflicht abgelehnt). Commit(s) (`feat(backend): complete rest api surface [AP-6.1]`).

### AP-6.2 — SSE-Live-Updates

**Aufgaben:**
- `GET /api/events/stream`: pusht neue Mails/Anrufe/Diary-Einträge in Echtzeit (SSE statt Polling, wie FS25-FarmMonitor-Vorbild).
- Event-Typen sauber differenzierbar (z. B. `event: mail`, `event: call`, `event: diary`).

**DoD:** Integrationstest, der eine neue `Communication` erzeugt und prüft, dass ein SSE-Client das Event empfängt. Commit (`feat(backend): sse live update stream [AP-6.2]`).

### AP-6.3 — Anruf-Zustandsautomat

**Referenz:** Abschnitt „Anruf-Zustandsautomat".

**Aufgaben:**
- Zustände: `RINGING → ACCEPTED (weiches Zeitfenster, kein Backend-Timeout danach) → COMPLETED`; `RINGING → DECLINED` (Trust-Malus, Spieler muss aktiv zurückrufen); `RINGING → MISSED` (Timeout ohne Klick).
- Ring-Timeout läuft in **Spielzeit**, nicht Realzeit — pausiert das Spiel, pausiert das Klingeln.
- Nach `DECLINED` bleibt das Thema offen (referenziert über `relatedEntityId`), bis der Spieler proaktiv auf den Charakter zugeht.
- „Weiches Zeitfenster" ist bewusst nur UI-Kosmetik — nach `ACCEPTED` kein serverseitiger Timer mehr.

**DoD:** Tests für alle vier Übergänge inkl. Spielzeit-Pausierung. Commit (`feat(backend): call state machine [AP-6.3]`).

### AP-6.4 — End-to-End-Integrationstests mit Bridge-Simulator

**Aufgaben:**
- Kompletter Durchlauf gegen den Bridge-Simulator aus Phase 2: Onboarding → erster `FactsSnapshot` → `STARTING_CAPITAL_ADJUSTMENT` → Kreditantrag → Genehmigung/Ablehnung → Preis-Event → Verhandlung (Versteigerung + Direktverhandlung) → Mitarbeiter-Einstellung → Kündigungs-Eskalation → Dorf-Rotation.
- Mindestens ein Test-Szenario je Kern-Modul, das den kompletten Datenfluss Backend↔Bridge-Simulator↔Backend abdeckt.

**DoD:** Alle Szenarien grün, Testlaufzeit dokumentiert. Commit (`test(backend): end-to-end scenarios against bridge simulator [AP-6.4]`).

---

## Phase 7 — Frontend: Grundgerüst & Design-System

**Referenz:** `docs/design-reference/Dashboard.html` (Design-Tokens unten bereits extrahiert), Fachkonzept „Kommunikationskanäle: Mails & Anrufe" für UI-Grundton.

### AP-7.1 — Angular-Projekt-Setup & Tailwind-Theme

**Aufgaben:**
- Angular-Projekt unter `frontend/` (aktuelle stabile Version prüfen), Standalone Components, Angular Router, strikte TypeScript-Einstellungen.
- Tailwind CSS einrichten, `tailwind.config.js` mit folgendem Theme (1:1 aus `Dashboard.html` extrahiert — **verbindlich für das gesamte Design**, nicht neu erfinden):

```js
// tailwind.config.js — Farb-/Font-Tokens aus docs/design-reference/Dashboard.html
theme: {
  extend: {
    colors: {
      bg:        '#0B0F0D', // Haupt-Hintergrund
      surface:   '#141A17', // Karten/Panels
      border:    '#222E28', // Rahmen (Opacity-Varianten 30/40/50/60 nach Bedarf)
      accent:    '#38B000', // Primärakzent (Grün) — CTAs, aktive States, Erfolg
      warn:      '#FF9F1C', // Sekundärakzent (Orange) — Warnungen, Preisänderungen
      text:      '#E2ECE9', // Primärtext
      muted:     '#7C9088', // Sekundärtext/Platzhalter
      danger:    '#dc2626', // Fehler/negative Werte
    },
    fontFamily: {
      body:    ['Barlow', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      display: ['"Chakra Petch"', 'ui-sans-serif', 'system-ui', 'sans-serif'],
      mono:    ['ui-monospace', 'SFMono-Regular', 'Menlo', 'Monaco', 'Consolas', 'monospace'],
    },
  },
}
```
- Google Fonts `Chakra Petch` (500/600/700, für Überschriften/Display) und `Barlow` (400/500/600, Fließtext) einbinden (gleiche Quelle wie im Dashboard: `fonts.googleapis.com/css2?family=Chakra+Petch:wght@500;600;700&family=Barlow:wght@400;500;600`).
- Grund-Layout: dunkles Theme (`bg-bg text-text font-body`), Zahlen/Tickerwerte in `font-mono`, Überschriften/Nav-Labels in `font-display` mit `uppercase tracking-[0.12em]`-artigen Stilen wie im Vorbild.

- **Mehrsprachigkeits-Vorbereitung (Fachkonzept-Entscheidung: „V1 nur Deutsch, Architektur aber von Anfang an erweiterbar"):** alle UI-Texte über einen zentralen i18n-Mechanismus (Angular `@angular/localize` oder ein einfacher Translation-Service mit `de.json` — Entscheidung selbst treffen und in `docs/dev/frontend.md` begründen), **keine** hartcodierten deutschen Strings direkt in Templates. Für V1 wird ausschließlich `de` befüllt.

**DoD:** `ng build` grün, eine Platzhalter-Startseite zeigt die Farbpalette (Style-Guide-Seite unter `/dev/style-guide`, nur im `dev`-Build). Commit (`feat(frontend): angular scaffold with tailwind design tokens [AP-7.1]`).

### AP-7.2 — Layout-Grundgerüst

**Aufgaben:**
- Sidebar-Navigation (analog Dashboard.html-Muster: linke Sidebar auf Desktop, umschaltbar auf Mobile) mit den Hauptbereichen: Home/Dashboard, Postfach, Anrufe, Bank/Finanzen, Mitarbeiter/Personal, Verhandlung/Felder, Warenbestand & Preise, Dorf/Charaktere, Tagebuch, Einstellungen.
- Header mit Spielstand-Kontext (Tag/Spielzeit, Kontostand als Live-Wert), Benachrichtigungs-Indikator für ungelesene Mails/anstehende Anrufe (SSE-gespeist).
- Responsive Verhalten: Sidebar kollabiert auf schmalen Viewports (Tailwind-Breakpoints wie im Vorbild: `sm/md/lg/xl`).
- Karten-/Panel-Grundkomponente mit `bg-surface border border-border rounded-md` im Look des Vorbilds.

**DoD:** Layout rendert auf Desktop und Mobile-Breite sichtbar korrekt (Screenshot-Vergleich manuell, automatisierter Screenshot folgt Phase 10). Commit (`feat(frontend): app shell with sidebar navigation and header [AP-7.2]`).

### AP-7.3 — Gemeinsame UI-Komponenten-Bibliothek

**Aufgaben:**
- Wiederverwendbare Standalone-Components im Dashboard-Look: `Card`, `Badge` (Status-Varianten: neutral/positiv/negativ/warnung, angelehnt an Grün/Orange/Rot-Akzente), `Button` (primär `bg-accent`, sekundär `border-border`), `Modal`/`Dialog`, `Toast`/Benachrichtigung, `ChartWrapper` (für Preisverlauf, s. AP-8.7), `Timeline`/`ListItem` (für Mails/Tagebuch), `Stat`-Kachel (großer `font-mono`-Wert + Label, wie die Kennzahl-Karten im Vorbild).
- Storybook **oder** eine einfache interne `/dev/style-guide`-Route zur visuellen Abnahme jeder Komponente (Entscheidung selbst treffen, in `docs/dev/frontend.md`, Phase 10, begründen).

**DoD:** Jede Komponente hat mindestens einen Unit-Test (Rendering + Kern-Interaktion), Commit (`feat(frontend): shared ui component library [AP-7.3]`).

### AP-7.4 — SSE-Client & globaler Live-State

**Aufgaben:**
- Service, der `GET /api/events/stream` konsumiert (EventSource), Events nach Typ (`mail`/`call`/`diary`) in einen globalen State (Angular Signals oder RxJS-Store — Entscheidung selbst treffen und dokumentieren) einspeist.
- Automatischer Reconnect bei Verbindungsabbruch.
- Header-Benachrichtigungs-Indikator (AP-7.2) und Postfach/Anrufe-Module (Phase 8) konsumieren diesen State reaktiv.

**DoD:** Test mit gemocktem EventSource (Event kommt an → State aktualisiert sich → UI reagiert). Commit (`feat(frontend): sse client and live state store [AP-7.4]`).

---

## Phase 8 — Frontend: Feature-Module

**Grundregel für diese Phase:** Jedes Modul bindet ausschließlich an die in Phase 6 gebauten Endpunkte an, nutzt die Komponenten aus AP-7.3 und den SSE-State aus AP-7.4. Formulare für alles, was Spielgeld/Zahlen bewegt (nie Freitext für Zahlen).

### AP-8.1 — Onboarding-Wizard

**Referenz:** Fachkonzept „Vorgeschichte & Onboarding-Ablauf".

**Aufgaben:**
- Mehrschrittiger Wizard: (1) Vorgeschichte-Bausteine (Ursprung des Hofs, Dorf-Verhältnis, `startingCapitalTarget`, optionaler `legacyLoanAmount`, Freitextfeld, Ton-Preset), (2) Mitarbeiter-Ausgangslage (Anzahl + Rollen), (3) Generierung/Vorschau der Startbesetzung (Name/Rolle/Kurzbeschreibung je Charakter, **Reroll** einzeln und gesamt), (4) Hinweis „jetzt Spielstand in FS25 erstellen/laden", (5) Liste unverknüpfter `savegameId`s (Kartenname + Zeitpunkt) zur Bestätigung & Verknüpfung.
- Leeres/minimales Freitextfeld führt sichtbar zu einer stimmigen Standard-Vorschau, kein Blockieren.

**DoD:** Komponententests je Schritt, ein E2E-Testfall (Phase 9) deckt den kompletten Wizard ab. Commit (`feat(frontend): onboarding wizard [AP-8.1]`).

### AP-8.2 — Postfach (Mails)

**Aufgaben:**
- Liste (`GET /api/mails`, ungelesen hervorgehoben, SSE-Live-Update bei neuer Mail), Detailansicht (`GET /api/mails/{id}`), freie Antwort (`POST /api/mails/{id}/reply`) außer dort, wo laut Fachkonzept ein Formular Pflicht ist (dann Verweis/Link auf das jeweilige Formular statt Freitextfeld).
- Dorfleben-Nachrichten (Glückwünsche/Einladungen/Klatsch, AP-4.9) laufen über dieselbe `Communication`-Tabelle und erscheinen hier ganz normal im Postfach — keine gesonderte Ansicht nötig, aber optisch leicht unterscheidbar markieren (z. B. dezentes Badge „Dorfleben"), damit sie nicht mit wirtschaftlich relevanten Mails verwechselt werden.

**DoD:** Tests, Commit (`feat(frontend): mailbox module [AP-8.2]`).

### AP-8.3 — Anrufe

**Referenz:** Fachkonzept „Kommunikationskanäle: Mails & Anrufe", Technisches Konzept „Anruf-Zustandsautomat".

**Aufgaben:**
- Eingehender-Anruf-Overlay (SSE-getriggert) mit Annehmen/Ablehnen, sichtbares „weiches Zeitfenster" nach Annahme (rein UI-seitig, kein hartes Timeout), klarer Hinweis auf Konsequenzen bei Ablehnen (Vertrauensverlust/Rückruf-Notwendigkeit).
- Gesprächsansicht (frei antwortbar, gleiche `<spieler_nachricht>`-Pipeline wie Mails serverseitig).

**DoD:** Tests für alle UI-Zustände (RINGING/ACCEPTED/DECLINED/MISSED-Anzeige). Commit (`feat(frontend): call module [AP-8.3]`).

### AP-8.4 — Bank/Kredit

**Aufgaben:**
- Antragsformular (Betrag, Zweck, Laufzeit — reines Formular, kein Freitext für Zahlen), Status „in Bearbeitung" bis `decisionVisibleAtGameTime`, Ergebnisanzeige (genehmigt/Gegenangebot/abgelehnt mit grober Begründungskategorie, nie Rohzahl), laufende Kredite mit Tilgungsplan/-historie, Stundungs-Anfrage.

**DoD:** Tests, Commit (`feat(frontend): credit and loan module [AP-8.4]`).

### AP-8.5 — Mitarbeiter

**Aufgaben:**
- Stellenausschreibung erstellen, Bewerberliste (Skills/Gehaltsvorstellung sichtbar), simuliertes Interview (Mail **oder** Anruf, Freitext-Fragen möglich, Skill/Gehalt bleiben fix und werden nicht durch das Gespräch verändert), Einstellung.
- Mitarbeiterliste mit Zufriedenheits-Anzeige (aggregiert, kategorienweise sichtbar: Bezahlung/Arbeitsbelastung/Wertschätzung/Arbeitsbedingungen), Gehaltserhöhung, freier Tag gewähren, Kündigung.

**DoD:** Tests, Commit (`feat(frontend): employee module [AP-8.5]`).

### AP-8.6 — Verhandlung / Farmland

**Aufgaben:**
- Kartenübersicht aller Farmlands inkl. Besitzer (`GET /api/farmlands`).
- Laufende Versteigerungen mit Gebotsformular (`POST /api/negotiations/{id}/offer`), Rundenverlauf sichtbar (max. 3 Runden), Ergebnis (Angenommen/Gegenangebot/Abgelehnt) mit KI-Erzähltext.
- Direktverhandlung aus der Charakter-Detailansicht heraus starten (Verweis auf AP-8.8).
- Eigenes Farmland zum Verkauf anbieten (`POST /api/farmlands/{id}/sell-offer`, Wunschpreis als Formular), eingehende NPC-Erstgebote anzeigen.

**DoD:** Tests, Commit (`feat(frontend): farmland negotiation module [AP-8.6]`).

### AP-8.7 — Warenbestand & Marktpreise

**Referenz:** Fachkonzept „Silo-Warenbestand & Marktpreis-Übersicht".

**Aufgaben:**
- Aktueller Silobestand je Fruchtart (`GET /api/storage`), aktuelle Verkaufspreise je Verkaufsstelle (`GET /api/prices/current`).
- **Preisverlauf-Chart** je Fruchtart/Verkaufsstelle über Zeit (`GET /api/prices/history?...`), Zeitraum wählbar.
- Deutliche visuelle Verbindung zwischen Silobestand und Bonität/Event-Wahrscheinlichkeit als kurzer erklärender Hinweistext (kein neuer Endpoint nötig, reine UI-Erklärung).

**DoD:** Tests inkl. Chart-Rendering mit Beispieldaten. Commit (`feat(frontend): storage and market price module [AP-8.7]`).

### AP-8.8 — Charaktere / Dorf

**Aufgaben:**
- Charakterliste (Rolle, Status, grobe Trust-Anzeige **nicht als Rohzahl**, sondern angemessen abstrahiert — z. B. Symbol/Balken ohne exakte Zahl, konsistent zum „keine exakte Formel-Offenlegung"-Prinzip), Detailansicht mit Persönlichkeits-Infos (Charakterzüge, Sprachstil-Andeutung, Hintergrundgeschichte).
- „Nachricht verfassen" (`POST /api/characters/{id}/messages`) mit sichtbarem Pacing-Hinweis, falls Cooldown aktiv (UI-Hinweis, keine Blockade der Konversation selbst).
- Dorf-Ansehen-Anzeige **nur als Stufe** („gut angesehen"/„neutral"/„umstritten"), nie als Zahl (`GET /api/village-reputation`).
- Bei Charakteren mit Farmland-Besitz: Direktverhandlung-Button (Verweis AP-8.6).

**DoD:** Tests, Commit (`feat(frontend): character and village module [AP-8.8]`).

### AP-8.9 — Tagebuch / Chronik

**Aufgaben:**
- Chronologische Liste automatischer Einträge (`GET /api/diary`), erster Eintrag = Onboarding-Vorgeschichte.
- Eigene freie Notiz hinzufügen (`POST /api/diary/entries`), deutlich als „rein narrativ, keine Spielauswirkung" gekennzeichnet.

**DoD:** Tests, Commit (`feat(frontend): diary module [AP-8.9]`).

### AP-8.10 — Dashboard/Home-Übersicht

**Aufgaben:**
- Startseite im Look von `Dashboard.html`: Kennzahl-Kacheln (Kontostand, ungelesene Mails, anstehende Anrufe, offene Kredite, laufende Verhandlungen, Dorf-Ansehen-Stufe), kompakte Liste der letzten Ereignisse (SSE-gespeist).
- Reine Aggregations-/Übersichtsseite, keine neue Fachlogik, nur Komposition bestehender Endpunkte.

**DoD:** Tests, Commit (`feat(frontend): home dashboard overview [AP-8.10]`).

### AP-8.11 — Einstellungen

**Aufgaben:**
- Lokale Verwaltung des KI-Providers (Auswahl OpenAI/Anthropic/Gemini/Ollama) und des jeweiligen API-Keys/Modellnamens — Speicherung ausschließlich lokal im Backend (`application-local.yml`/gesichertes lokales Config-Storage, **nie** im Git-Repo, **nie** im Frontend-State persistiert über Browser-Storage hinaus als reine UI-Eingabe, die ans Backend übergeben wird).
- Anzeige des aktiven Ton-Presets (read-only nach Onboarding, da laut Fachkonzept fix seit Onboarding).

**DoD:** Tests, Commit (`feat(frontend): settings module for ai provider configuration [AP-8.11]`).

---

## Phase 9 — Qualitätssicherung

### AP-9.1 — Backend-Testabdeckung vervollständigen

**Aufgaben:**
- Lücken-Review gegen Abschnitt 14 (Abnahme-Checkliste): jede Formel aus dem Technischen Konzept hat mindestens einen Grenzwert-Test (exakt an der Schwelle, knapp darüber, knapp darunter).
- Beide Sicherheitsprinzip-Tests noch einmal explizit gegenprüfen: Trust-Cap < Bonitäts-Schwellenabstand (Kredit) **und** Trust-Adjustment kann `minAccept` nie über einen sinnvollen Rahmen hinaus verzerren (Verhandlung).
- Testabdeckungs-Report erzeugen (z. B. JaCoCo), Ergebnis in `docs/dev/testing.md` dokumentieren, keine feste Prozent-Hürde vorschreiben, aber Lücken bei den oben genannten Kernformeln aktiv schließen.

**DoD:** Report vorhanden, Kernformel-Grenzfälle nachweislich abgedeckt. Commit (`test(backend): close coverage gaps on core formulas [AP-9.1]`).

### AP-9.2 — Frontend-Tests

**Aufgaben:**
- Unit-Tests (Jest oder Karma/Jasmine — Entscheidung selbst treffen, in `docs/dev/frontend.md` begründen) für alle Komponenten aus Phase 7/8 mit Logik (nicht nur reines Markup).
- End-to-End-Tests (Playwright) für die Kernflows: kompletter Onboarding-Durchlauf, Mail beantworten, Kreditantrag stellen und Ergebnis abwarten (Zeit im Bridge-Simulator vorspulen), Anruf annehmen/ablehnen, Verhandlung bis Abschluss, Mitarbeiter einstellen und kündigen lassen, Preisverlauf-Chart lädt Daten.
- E2E-Tests laufen gegen Backend + Bridge-Simulator (Phase 2), nicht gegen echtes FS25.

**DoD:** Alle E2E-Flows grün, in CI ausführbar (Vorbereitung für Phase 11). Commit (`test(frontend): unit and e2e coverage for core flows [AP-9.2]`).

### AP-9.3 — Manueller Testplan mit Bridge-Simulator-Szenarien

**Aufgaben:**
- `docs/dev/manual-test-plan.md`: Schritt-für-Schritt-Anleitung, wie man mit den Szenarien aus AP-2.1 das komplette Tool manuell durchklickt (für den Fall, dass der Nutzer selbst noch einmal alles von Hand prüfen will, bevor es an echtes FS25 geht).

**DoD:** Commit (`docs(dev): manual test plan using bridge simulator scenarios [AP-9.3]`).

---

## Phase 10 — Dokumentation

**Ziel dieser Phase:** ein Repository, das ein Fremder (oder der Nutzer in einem Jahr) ohne Rückfragen verstehen, installieren und weiterentwickeln kann — inklusive Bildern.

### AP-10.1 — Architektur-Dokumentation mit Mermaid-Diagrammen

**Aufgaben unter `docs/architecture/`:**
- `overview.md` mit Mermaid-Diagramm der 3-Schichten-Architektur (Lua-Mod ↔ Datei-Bridge ↔ Spring-Boot-Backend ↔ SSE/REST ↔ Angular-Frontend).
- `file-bridge-sequence.md`: Mermaid-Sequenzdiagramm eines vollständigen Bridge-Zyklus (Export → Backend liest → Fakten-Ebene entscheidet → `OutboxInstruction` → `instructions.json` → Mod wendet an → `instructions_ack.json`).
- `domain-model.md`: Mermaid-ER-Diagramm aller Entities aus AP-3.2 mit Beziehungen.
- `two-tier-principle.md`: Diagramm, das das Zwei-Ebenen-Prinzip visualisiert (Fakten-Ebene-Service → `NarrationJob` → `AiNarrationService` → `Communication`), da das das zentrale Sicherheitskonzept des gesamten Projekts ist.
- `call-state-machine.md`: Mermaid-Zustandsdiagramm des Anruf-Automaten (AP-6.3).
- Alle Diagramme müssen im GitHub-Markdown-Renderer direkt sichtbar sein (native Mermaid-Fences ```` ```mermaid ````).

**DoD:** Alle fünf Diagramme vorhanden und in GitHub korrekt darstellbar (lokal mit einem Mermaid-Renderer prüfen). Commit (`docs(architecture): mermaid diagrams for system design [AP-10.1]`).

### AP-10.2 — Automatisierte Screenshots

**Aufgaben:**
- Playwright-Skript unter `tools/screenshot-generator/`, das Backend + Bridge-Simulator + Frontend startet, durch die Kernseiten navigiert (Onboarding, Home-Dashboard, Postfach, Anruf-Overlay, Bank, Mitarbeiter, Verhandlung/Farmland-Karte, Warenbestand-Chart, Charakterdetail, Tagebuch, Einstellungen) und Screenshots nach `docs/screenshots/<seitenname>.png` speichert.
- Skript wiederholbar/CI-fähig machen (auch wenn es primär lokal/on-demand läuft, nicht zwingend bei jedem Push).

**DoD:** Skript erzeugt für jede in Phase 8 gebaute Seite mindestens einen Screenshot. Commit (`feat(tools): automated screenshot generator [AP-10.2]`).

### AP-10.3 — Nutzer-Anleitung (Deutsch)

**Aufgaben unter `docs/user-guide/` (Deutsch, mit Screenshots aus AP-10.2 eingebunden):**
- `installation.md`: Mod-Installation (Kopieren nach `mods`-Ordner), Backend starten (JAR/Maven), Frontend öffnen, KI-Provider/API-Key in den Einstellungen hinterlegen.
- `erster-spielstand.md`: kompletter Onboarding-Ablauf Schritt für Schritt mit Screenshots.
- `funktionen.md`: kurze, bebilderte Tour durch jedes Modul (Mails, Anrufe, Bank, Mitarbeiter, Verhandlung, Warenbestand, Dorf, Tagebuch).
- `fehlerbehebung.md`: Was tun, wenn der Mod nicht schreibt, wenn die KI nicht antwortet (Fallback-Texte erklären), wenn `savegameId` nicht verknüpft.

**DoD:** Vollständig bebildert, verweist konsistent auf Screenshots. Commit (`docs(user-guide): german user documentation [AP-10.3]`).

### AP-10.4 — Entwickler-Dokumentation

**Aufgaben unter `docs/dev/`:**
- `setup.md`: lokales Setup aller drei Komponenten + Bridge-Simulator, benötigte Tools/Versionen.
- `configuration-reference.md`: **vollständige** Tabelle aller `rpsim.formulas.*`-Konfigurationswerte aus Phase 4 mit Default (Platzhalter aus dem Konzept), Bedeutung, Konzept-Referenz.
- `ai-providers.md`: wie man jeden der vier Provider konfiguriert, inkl. Hinweis zum sich ändernden Gemini-Free-Tier-Modellnamen (AP-5.4).
- `offene-technische-punkte.md`: konsolidierte Liste aller in Phase 1–6 markierten `TODO(offene-frage)`-Stellen, je mit Fundstelle im Code und Fundstelle im Technischen Konzept.
- `testing.md`, `frontend.md` (Ergebnisse/Entscheidungen aus AP-9.1/9.2/7.3/8. Phase referenzieren).

**DoD:** Commit (`docs(dev): developer documentation set [AP-10.4]`).

### AP-10.5 — README final

**Aufgaben:**
- `README.md` final: Projektname, ein-Absatz-Pitch (Kernidee aus dem Fachkonzept), Screenshot-Collage (2–3 Bilder aus AP-10.2), Architektur-Kurzdiagramm (Verweis auf `docs/architecture/overview.md`), Quickstart (Kurzfassung von `docs/dev/setup.md`), Feature-Liste (alle 5 Kernmodule + Silo/Preise + 6 Rollenspiel-Vertiefungen als Aufzählung), Link-Sammlung zu allen Doku-Unterseiten, Lizenz-Hinweis, Disclaimer „Fan-Projekt, kein offizielles GIANTS-Software-/Farming-Simulator-Produkt".

**DoD:** Commit (`docs(readme): finalize project readme [AP-10.5]`).

---

## Phase 11 — CI/CD & Release-Vorbereitung

### AP-11.1 — GitHub Actions

**Aufgaben unter `.github/workflows/`:**
- `backend.yml`: bei Push/PR auf Pfade unter `backend/` → `mvn -B verify` (Build + Tests).
- `frontend.yml`: bei Push/PR auf Pfade unter `frontend/` → `npm ci && npm run lint && npm run build && npm test -- --watch=false`.
- `mod-lint.yml`: `luacheck` gegen `mod/`.
- `e2e.yml`: Playwright-E2E aus AP-9.2 gegen Backend + Bridge-Simulator, mindestens bei Push auf `main` (kann bei jedem PR zu langsam sein — Entscheidung selbst treffen und in `docs/dev/testing.md` begründen).
- Den Platzhalter-Workflow aus AP-0.3 durch diese echten Workflows ersetzen.

**DoD:** Alle Workflows laufen bei einem Test-Push grün durch (mit den bis dahin vorhandenen Tests). Commit (`ci(github): add backend, frontend, mod and e2e pipelines [AP-11.1]`).

### AP-11.2 — Release-Vorbereitung

**Aufgaben:**
- `CHANGELOG.md` rückwirkend befüllen (grobe Zusammenfassung je Phase, nicht jeder Commit einzeln).
- Versionsschema (SemVer) für Mod, Backend, Frontend gemeinsam als Projekt-Version festlegen (z. B. `v1.0.0` für den vollständigen V1-Funktionsumfang laut Fachkonzept).
- Release-Build: Backend-JAR + Frontend-`dist` (vom Backend als statische Ressourcen mitausgeliefert **oder** getrennt gestartet — Entscheidung selbst treffen, in `docs/dev/setup.md` dokumentieren) + Mod-ZIP als herunterladbare Artefakte, lokal per Skript baubar (`tools/release/build-release.sh` o. ä.).

**DoD:** Ein lokaler Release-Build-Lauf erzeugt alle drei Artefakte fehlerfrei. Commit (`chore(release): v1.0.0 release build tooling [AP-11.2]`).

### AP-11.3 — Finale Abnahme gegen die Konzepte

**Aufgaben:**
- Die Checkliste aus Abschnitt 14 dieser Datei komplett durchgehen und jeden Punkt mit Code-/Doku-Fundstelle abhaken.
- Für jeden nicht abhakbaren Punkt: entweder nachliefern (zurück ins passende AP/Phase) oder – falls es eine echte, im Fach-/Technischen Konzept begründete Nichtimplementierung ist (z. B. „bewusst nicht in Version 1: Verpachtung") – explizit als „bewusst nicht umgesetzt (V1-Scope)" markieren, niemals stillschweigend weglassen.
- Abschließenden Status in `docs/dev/acceptance-checklist.md` festhalten (Kopie der Checkliste mit Häkchen + Fundstellen).

**DoD:** `docs/dev/acceptance-checklist.md` vollständig ausgefüllt, letzter Commit (`docs(dev): final acceptance check against fach-/technisches konzept [AP-11.3]`), gepusht.

---

## 12. Anhang: `AiProvider`-Referenz (Kurzübersicht für AP-5.2 bis AP-5.5)

| Provider | AP | Hinweis |
| --- | --- | --- |
| OpenAI | AP-5.2 | Chat-Completions/Responses-API, JSON-Modus |
| Anthropic | AP-5.3 | Messages API, JSON über Prompt-Vorgabe |
| Google Gemini | AP-5.4 | **Kostenloses Modell** — Modellname zum Umsetzungszeitpunkt gegen Google-AI-Studio-Doku prüfen, konfigurierbar halten |
| Ollama | AP-5.5 | Lokal, `http://localhost:11434`, kein API-Key nötig |

---

## 13. Anhang: JSON-Kernschemata (Kurzreferenz)

Diese Schemata sind für Phase 1/2/3 verbindlich — vollständige Beispiele stehen im Technischen Konzept, Kapitel „Datei-Bridge (Mod ↔ Backend)":

- `export/farm_facts.json` — `schemaVersion`, `gameTime`, `savegameId`, `liquidity`, `assets.{vehicles,placeables,farmland,animals,storage}`, `liabilities.vanillaLoan`, `prices[]`
- `export/market_context.json` — `mapName`, `sellPoints[]`, `fillTypes[]`, `farmlands[]`
- `import/instructions.json` — Envelope + `MONEY_TRANSACTION` | `PRICE_EVENT` (`MULTIPLIER`/`FIXED`) | `FARMLAND_TRANSFER`
- `import/instructions_ack.json` — `acks[].{instructionId, appliedAtGameTime, status}`

---

## 14. Abnahme-Checkliste (Rückverfolgbarkeit Fachkonzept → Arbeitspaket)

Diese Tabelle in AP-11.3 final abhaken. „Scope-Ausschluss" = bewusste Konzept-Entscheidung, **nicht** implementieren, aber explizit so vermerken.

| # | Feature (Konzept) | Arbeitspaket(e) |
| --- | --- | --- |
| 1 | Zwei-Ebenen-Modell (Fakten deterministisch / Persönlichkeit KI) | AP-4.* (alle Services), AP-5.6 |
| 2 | Kommunikationskanäle Mails (asynchron) | AP-6.1, AP-8.2 |
| 3 | Kommunikationskanäle Anrufe (Annehmen/Ablehnen/weiches Zeitfenster) | AP-6.3, AP-8.3 |
| 4 | Onboarding: Vorgeschichte-Bausteine + Freitext | AP-4.8, AP-8.1 |
| 5 | Onboarding: Mitarbeiter-Ausgangslage | AP-4.8, AP-8.1 |
| 6 | Onboarding: Startpaket-Generierung + Story-Hooks gestaffelt | AP-4.6, AP-4.8 |
| 7 | Onboarding: Vorschau mit Reroll (einzeln/gesamt) | AP-4.8, AP-8.1 |
| 8 | Onboarding: Bestätigen & Verknüpfen mit Spielstand | AP-4.8, AP-8.1 |
| 9 | Freitext beeinflusst nie Zahlen, leichte Inhaltsprüfung, leeres Feld = Standard | AP-4.8, AP-5.9 |
| 10 | Vorgeschichte-Profile nicht als Vorlage gespeichert | AP-4.8 |
| 11 | Pflichtrollen (nie unbesetzt) + Abwesenheitslogik (verzögert/Vertretung) | AP-4.6 |
| 12 | Dynamische Charaktere: Rotation, max. 1–2/Jahr | AP-4.6 |
| 13 | Rollenwechsel: Fakten-Akte bleibt, Beziehung startet neu | AP-3.2, AP-4.6 |
| 14 | Charakter-Datenmodell (Fakten- + Persönlichkeits-Ebene) | AP-3.2 |
| 15 | Vertrauenswert: gedeckelt, ereignisgetrieben, in Bonität & Verhandlung | AP-4.1, AP-4.2, AP-4.4 |
| 16 | Gedächtnis über Zeit (verdichtete Kurzfakten) | AP-5.8 |
| 17 | Bonitätsprüfung: vollständige Datenbasis inkl. Silo-Warenbestand | AP-4.2 |
| 18 | Bonität: drei Ergebnisstufen | AP-4.2 |
| 19 | Kreditantrag-Ablauf + künstliche Bearbeitungszeit | AP-4.2, AP-4.8 |
| 20 | Zahlungsausfall-Eskalation vollständig (Mahnung→Strafzins→Trust→Callback/Sperre) | AP-4.2 |
| 21 | Stundung | AP-4.2, AP-8.4 |
| 22 | Scope-Ausschluss: Sicherheiten/Pfand | — (in `docs/dev/acceptance-checklist.md` als bewusst nicht umgesetzt vermerken) |
| 23 | Event-Engine: Typen, Regionalität, Charakterbindung, Timing/Gerüchte | AP-4.3 |
| 24 | Spielerreaktion auf Events (Sonderkontrakte aushandeln) | AP-4.3, AP-6.1, AP-8.6 |
| 25 | Silo-Warenbestand-Export (nur klassische Silos) | AP-1.6 |
| 26 | Laufende Verkaufspreise-Export | AP-1.2, AP-1.6 |
| 27 | Marktübersicht + Preisverlauf-Chart im Frontend | AP-8.7 |
| 28 | Warenbestand wirkt auf Bonität (storageValue) | AP-4.2 |
| 29 | Warenbestand wirkt auf Event-Zielauswahl (Gewichtung) | AP-4.3 |
| 30 | Mitarbeiter: Stellenmarkt, Bewerberpool, Interview | AP-4.5, AP-8.5 |
| 31 | Mitarbeiter: rollenspezifische Skills (reine Tool-Werte) | AP-4.5 |
| 32 | Mitarbeiter: Gehalt, monatliche Abbuchung | AP-4.5 |
| 33 | Mitarbeiter: vier Bedürfniskategorien | AP-4.5 |
| 34 | Mitarbeiter: Zufriedenheit → Skill-Malus/-Bonus (deterministisch) | AP-4.5 |
| 35 | Mitarbeiter: Kündigungs-Eskalation mit Vorwarnung | AP-4.5 |
| 36 | Systemverzahnung Gehaltsverzug → Zufriedenheit | AP-4.5 |
| 37 | Verhandlung: Versteigerung (system-initiiert) | AP-4.4, AP-8.6 |
| 38 | Verhandlung: Direktverhandlung (spieler-initiiert) | AP-4.4, AP-8.6, AP-8.8 |
| 39 | Verhandlung: Formular-Gebot, Formel-Prüfung, max. 3 Runden | AP-4.4, AP-8.6 |
| 40 | Verhandlung: Preisfindung inkl. Trust-Deckel | AP-4.4 |
| 41 | Verhandlung: NPC-Mitgebote bei Versteigerung | AP-4.4 |
| 42 | Verhandlung: Verkauf eigener Felder | AP-4.4, AP-8.6 |
| 43 | Verhandlung: ein Feld, eine Verhandlung | AP-4.4 |
| 44 | Verhandlung: Ownership-Abgleich gegen Vanilla-Parallel-Kauf | AP-4.4, AP-1.7 |
| 45 | Scope-Ausschluss: Verpachtung | — (bewusst nicht umgesetzt vermerken) |
| 46 | Generische Verhandlungs-Engine-Architektur (Erweiterbarkeit) | AP-4.4 |
| 47 | Freie Antworten + Ton-Klassifikator | AP-4.7 |
| 48 | Mechanische Freitext-Wünsche → höfliche Rücklenkung | AP-4.7, AP-5.6 |
| 49 | Dorfweites Ansehen (nur Stufe nach außen) | AP-4.6, AP-8.8 |
| 50 | Proaktive Nachricht („Nachricht verfassen") + Pacing-Limit | AP-6.1, AP-8.8 |
| 51 | Tagebuch/Chronik (automatisch + freie Notizen) | AP-4.6/AP-4.8 (Einträge), AP-8.9 |
| 52 | Dorfleben-Modul: Glückwünsche/Einladungen/Klatsch | AP-4.9 |
| 53 | Ton-/Genre-Leitplanken (Onboarding-Baustein + Formel-Profil Bank) | AP-4.7, AP-4.8, AP-8.1 |
| 54 | Datei-Bridge: `farm_facts.json` vollständig | AP-1.2 |
| 55 | Datei-Bridge: `market_context.json` vollständig | AP-1.3 |
| 56 | Datei-Bridge: Import-Envelope, drei Instruktionstypen | AP-1.4, AP-1.5, AP-1.7 |
| 57 | Datei-Bridge: Ack & Idempotenz | AP-1.4 |
| 58 | Datei-Bridge: `savegameId`-Schutz | AP-1.8, AP-3.3 |
| 59 | Zwei-Ebenen-Sicherheitsprinzip testbar verifiziert (Trust-Cap < Schwellenabstand) | AP-4.2, AP-4.4, AP-9.1 |
| 60 | Vier-Bausteine-Prompt + `<spieler_nachricht>`-Injection-Schutz | AP-5.6 |
| 61 | `NarrationJob`-Entkopplung + Fallback-Texte | AP-5.7 |
| 62 | Vier `AiProvider`-Implementierungen | AP-5.2–AP-5.5 |
| 63 | Alle REST-Endpunkte aus der Konzept-Tabelle | AP-6.1 |
| 64 | SSE-Live-Updates | AP-6.2, AP-7.4 |
| 65 | Anruf-Zustandsautomat inkl. Spielzeit-Timeout | AP-6.3 |
| 66 | Formel-Werte als Konfiguration, nicht Code-Konstanten | AP-4.* (durchgängig), AP-10.4 |
| 67 | Mehrsprachigkeits-Vorbereitung (nur `de` befüllt) | AP-7.1, AP-5.7 |
| 68 | Scope-Ausschluss: Mehrspieler-Höfe | — (bewusst nicht umgesetzt vermerken) |
| 69 | Design an `Dashboard.html` angelehnt | AP-7.1, AP-7.2, AP-7.3 |
| 70 | Vollständige Nutzer- und Entwickler-Dokumentation mit Bildern | AP-10.1–AP-10.5 |
| 71 | Regelmäßiges Commit/Push während der gesamten Umsetzung | Grundregel 5 (durchgängig) |

