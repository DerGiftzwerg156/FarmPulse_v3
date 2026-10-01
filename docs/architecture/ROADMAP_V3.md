# FarmPulse – Roadmap V3

Ausbauplan nach dem Abschluss von [Roadmap V2](ROADMAP_V2.md) (alle Punkte Q, A–F umgesetzt, Stand 30.09.2026). Ziel:
Das Dorf wird zum Wirtschaftspartner. Nachbarn handeln mit dir Ware aus deinen Silos und vergeben echte Aufträge im
Spiel. Du kannst Felder verpachten, Maschinen gebraucht kaufen und verkaufen, dich gegen Dürre absichern, deine Ernte
vorab verkaufen und das Hof-Tablet auf einem echten Tablet im Heimnetz nutzen.

Wie in V2 enthält diese Datei **nur Punkte, die mit der FS25-Schnittstelle machbar sind**. Jeder Punkt mit
Mod-Eingriff wurde am 30.09.2026 gegen den FS25-Quellcode-Dump und die Community-LUADOC geprüft (Quellen am Ende).
Was nicht machbar ist, steht mit Begründung in [Bewusst nicht aufgenommen](#bewusst-nicht-aufgenommen).

**Grundsätze (unverändert aus V1/V2)**

- Der Mod bleibt **Sensor und Aktuator**: Er liest Spielwerte und führt Anweisungen aus. Alle Entscheidungen und
  Zahlen berechnet das Backend nach festen, konfigurierbaren Formeln (`rpsim.formulas.*`). Die KI formuliert nur.
- Jeder Zugriff auf FS25 läuft in `RPSimGameAdapter` und ist mit `pcall` abgesichert. Fehlt eine API, fällt die Funktion
  still auf das bisherige Verhalten zurück.
- Neue Felder in den Bridge-Dateien sind **optional** (`schemaVersion` bleibt `1`). Ein fehlender Block heißt „nicht
  vorhanden“, ein leerer Block ist eine echte Antwort des Spiels.
- Beträge und Mengen gibt der Spieler nie im Freitext ein, sondern über Formulare. Das Backend legt jeden Preis fest.
- Neue Anweisungen, die Geld bewegen, laufen als **Batch** mit ihrer `MONEY_TRANSACTION`: alles oder nichts.

**Neu in V3**

- Die Nachbarn (`NEIGHBOR_FARMER`) haben im Spiel **kein eigenes Lager, keine Tiere und keine Fahrzeuge**. Das
  FS25-Grundspiel simuliert nur ihre Felder (siehe H1). Deshalb kommen Vorrat und Bedarf eines Nachbarn aus Formeln
  des Backends, abgeleitet von seinen **echten Feldern** im Spiel. **Echt** sind dagegen deine Silos: Was du verkaufst,
  wird im Spiel abgebucht, was du kaufst, wird eingebucht.
- Das Tablet im Heimnetz (N) ist die erste Funktion, die nicht auf `localhost` beschränkt ist. Zugriff aus dem Internet
  bleibt ausgeschlossen.

## Legende

| Zeichen | Bedeutung |
| --- | --- |
| ✅ **Belegt** | Die genutzte Funktion bzw. das Feld steht so im FS25-Code; Fundstelle ist angegeben. |
| 🟡 **Im Spiel prüfen** | Die API existiert, aber ein Detail lässt sich nur im laufenden Spiel klären. Zu jedem 🟡 steht ein Fallback. |
| **Beleg** | Datei im FS25-Code-Dump (`Dukefarming/FS25-lua-scripting`, „Dump“) oder Seite der Community-LUADOC („LUADOC“). |
| – | Kein Mod-Eingriff: Der Punkt nutzt nur Daten und Anweisungen, die es seit V1/V2 gibt. |

Punkt-IDs: `R3-<Bereich><Nummer>`, z. B. `R3-H3`. Alle Pfade sind relativ zum Repo-Root.

## Übersicht und empfohlene Reihenfolge

| Phase | Bereich | Inhalt | Mod-Eingriff | Hängt ab von |
| --- | --- | --- | --- | --- |
| 0 | [Q – Querschnitt](#q--querschnitt) | Neue Bridge-Blöcke und Anweisungen, Simulator, Testplan, Doku | ja | – |
| 1 | [N – Hof-Tablet im Heimnetz](#n--hof-tablet-im-heimnetz) | Zugriff vom Tablet im eigenen Netz, PIN, Adresse, Startbildschirm-Symbol | nein | – |
| 2 | [H – Nachbarn: Handel und Aufträge](#h--nachbarn-handel-und-aufträge) | Nachbarfelder lesen, Ware aus eigenen Silos handeln, echte Aufträge der Nachbarn | ja | Q |
| 3 | [L – Eigene Felder verpachten](#l--eigene-felder-verpachten) | Verpachtung mit monatlicher Pacht | nein | – |
| 4 | [K – Kredit und Finanzplanung](#k--kredit-und-finanzplanung) | Kreditsicherheiten, Liquiditätsplanung, Jahresabschluss | nein | – (K1 nutzt L) |
| 5 | [M – Markt und Vermarktung](#m--markt-und-vermarktung) | Preisalarm, Vorkontrakt, Hofladen | M3: ja | M3 ← Q, H |
| 6 | [W – Dürre und Wetterrisiko](#w--dürre-und-wetterrisiko) | Dürre erkennen, Dürrehilfe, Wetterindex-Versicherung | nein | – |
| 7 | [V – Gebrauchtmaschinen](#v--gebrauchtmaschinen) | Maschinen gebraucht kaufen und verkaufen | ja | Q |
| 8 | [P – Personal](#p--personal) | Bürokraft mit mehr Wirkung, Azubi | P2: ja | – |
| 9 | [T – Chronik](#t--chronik) | Meilensteine, Hofchronik als Datei | nein | – |

N zuerst, weil es der ausdrückliche Wunsch ist und ohne Mod-Änderung auskommt. H ist der größte neue Bereich. Er legt
die Lager-Anweisung an, die M3 (Hofladen) mitbenutzt. L, K, W, P und T sind reine Backend-/Oberflächen-Arbeit und
können jederzeit dazwischen umgesetzt werden.

---

## Q – Querschnitt

Arbeiten, die mehrere Bereiche brauchen. Einmal sauber anlegen, dann bei jedem Punkt nur ergänzen (Muster aus R2-Q).

**Stand 30.09.2026: umgesetzt.** Q legt wie R2-Q nur den Vertrag an (Schemas, DTOs, Validator, Normalisierung im Mod,
Simulator, Doku). Das Auslesen im Mod folgt mit H1 (`npcFields`), H2 (`tradeStorage`) und V1 (`storeVehicles`), bis
dahin fehlen die Blöcke. Die vier neuen Anweisungen quittiert der Mod bis H3/H5/V2/V3 mit `FAILED` / `NOT_SUPPORTED`.
Entscheidungen (siehe `QUESTIONS.md`): Die Mod-Schalter `npcFieldExport` und `storeCatalogExport` kommen mit H1 bzw.
V1. Der Hinweis „Mod aktualisieren“ ist ein Merkmal (`modOutdated`) des bestehenden Hinweises `INSTRUCTION_FAILED`.
`result` wird an der Anweisung gespeichert. Die Buchungstitel folgen dem Vorschlag. Der Simulator führt die neuen
Anweisungen aus wie der spätere Mod. Nur `nachbarhandel` liefert die neuen Blöcke. `VEHICLE_SPAWN.price` ist positiv.

### R3-Q1 Bridge-Schema erweitern, ohne alte Stände zu brechen

- [x] `farm_facts.json`, neue optionale Blöcke:
  - `npcFields` (H1): Felder ohne Besitzer, gleiche Felder wie `fields`.
  - `tradeStorage` (H2): je Fruchtsorte Menge und freie Kapazität **nur** der eigenen Silos und Silo-Erweiterungen.
- [x] `market_context.json`, neuer optionaler Block `storeVehicles` (V1): Fahrzeug-Katalog des Shops.
- [x] Neue Anweisungstypen in Mod (`RPSimInstructions.TYPES`) und Backend (`domain/InstructionType.java`):
  `STORAGE_TRANSFER` (H3/H4/M3), `MISSION_CREATE` (H5), `VEHICLE_SPAWN` (V2), `VEHICLE_REMOVE` (V3). Ein älterer Mod
  lehnt einen unbekannten Typ bei der Prüfung ab (`RPSimInstructions.validate`: „unknown type …“, Quittung `REJECTED`;
  ein Mod mit R3-Q, aber ohne das Feature quittiert `FAILED` / `NOT_SUPPORTED`).
  Das Backend storniert dann den Vorgang (Geld wird wegen des Batches nicht gebucht) und erklärt es per Hinweis:
  „Mod aktualisieren“. Umgesetzt in Q: der Hinweis (`FailedInstructionService`, Merkmal `modOutdated`). Das
  Stornieren des Vorgangs kommt mit dem jeweiligen Feature.
- [x] Die Quittung (`instructions_ack.json`) bekommt ein optionales Feld `result` (z. B. `vehicleId` nach
  `VEHICLE_SPAWN`, `missionId` nach `MISSION_CREATE`). Der Mod speichert es im Spielstand, das Backend an der
  Anweisung (`outbox_instruction.ack_result_json`).
- [x] Neue `MoneyReason`-Werte in Mod, Backend und `modDesc.xml` (`rpsim_money_<REASON>`):
  - `LEASE_INCOME` (L)
  - `GOODS_PURCHASE`, `GOODS_SALE` (H, M3)
  - `VEHICLE_PURCHASE`, `VEHICLE_SALE` (V)
  - `CONTRACT_PENALTY` (M2)
- [x] Die Klassen für `rpsim.formulas.finance.categories` festlegen:
  - `RPSIM_LEASE_INCOME`, `RPSIM_GOODS_*` → operativ
  - `RPSIM_VEHICLE_PURCHASE` → Investition, `RPSIM_VEHICLE_SALE` → Desinvestition
  - `RPSIM_CONTRACT_PENALTY` → operative Ausgabe
- [x] `docs/dev/bridge-protocol.md` je Feld und Anweisung mit Quelle im FS25-Code ergänzen.

### R3-Q2 Bridge-Simulator und Tests

- [x] `tools/bridge-simulator`: neue Szenarien, z. B. `nachbarhandel` (Nachbarfelder, Silos mit freier Kapazität,
  Shop-Katalog) und `duerre-sommer` (Monate ohne Regen). Der Simulator versteht die neuen Anweisungen und
  JSON-Schemas. Umgesetzt: `nachbarhandel` und `duerre-sommer` (Start im Juni, kein Regen). Der Simulator führt die
  neuen Anweisungen aus wie der spätere Mod.
- [x] Mod-Tests (`mod/tests/`) für jede neue Adapter-Funktion mit gemockten FS25-Globals, wie in
  `test_game_adapter.lua`. In Q: Normalisierung der Blöcke, Prüfung der neuen Anweisungen, `NOT_SUPPORTED`, `result`
  in Quittung und Spielstand. Gilt weiter für jede Adapter-Funktion der Features.
- [x] Backend: Grenzwert-Tests für jede neue Formel (wie `CreditFormulaTest`), End-to-End-Test gegen den Simulator.
  Q bringt keine Formel. `BridgeValidatorTest`, `FailedInstructionTest`, `BridgeSyncIntegrationTest` und
  `SimulatorScenariosEndToEndTest` prüfen die neuen Blöcke, den Hinweis und `result`.

### R3-Q3 Konfiguration und Doku

- [x] Alle neuen Werte unter `rpsim.formulas.*` in `backend/src/main/resources/application.yml` und
  `config/RpsimProperties.java`. **Achtung:** `ConfigurationReferenceDocTest` schlägt fehl, wenn ein Schlüssel in
  `docs/dev/configuration-reference.md` fehlt. Q bringt nur die Klassen der neuen Buchungsgründe unter
  `rpsim.formulas.finance.categories`. Die Regel gilt für jedes Feature.
- [x] Neue Mod-Schalter in `RPSimConfig.DEFAULTS` (z. B. `npcFieldExport`, `storeCatalogExport`). Q bringt keine
  Schalter (`npcFieldExport` kommt mit H1, `storeCatalogExport` mit V1).
- [x] Spieler-Doku `docs/user-guide/funktionen.md` je Feature, Eintrag in `CHANGELOG.md`. Q ist für Spieler nicht
  sichtbar, daher nur der `CHANGELOG`-Eintrag.

### R3-Q4 Prüfliste für den Spieltest erweitern

- [x] In `docs/dev/manual-test-plan.md` einen neuen Abschnitt **„11. Roadmap V3 im echten FS25“** anlegen. Jeder
  🟡-Punkt dieser Roadmap bekommt dort eine Zeile mit „Wie prüfen“ und „Erwartet“.

---

## N – Hof-Tablet im Heimnetz

**Problem heute:** Die Doku nennt nur <http://localhost:8080>, und das Backend hat keine Anmeldung („Kein Auth in V1“,
`docs/concept/Technisches_Konzept_V6.md`). In `application.yml` steht nur `server.port: 8080`, kein `server.address`.
Spring Boot lauscht dann auf **allen** Netzwerkschnittstellen. Technisch erreicht also schon heute jedes Gerät im Netz
das Tool, aber ohne Schutz, ohne Anleitung und ohne Einschränkung auf das Heimnetz. Nur die CORS-Regel in
`config/WebConfig.java` nennt ausschließlich `localhost` / `127.0.0.1`. Für die ausgelieferte Oberfläche spielt sie
keine Rolle, weil Oberfläche und API vom selben Server kommen (`apiBase: '/api'`).

**Idee:** Den Zugriff im Heimnetz bewusst freischalten: Das Tablet öffnet die Adresse des Spiele-PCs. Anfragen aus dem
Internet werden immer abgewiesen, Geräte im Heimnetz melden sich mit einer PIN an.

**Beleg:** – (kein Mod-Eingriff). Alles läuft im Backend (Spring Boot) und in der Angular-Oberfläche.

**Stand 30.09.2026: umgesetzt.** Entscheidungen (siehe `QUESTIONS.md`): Der Schalter ist standardmäßig aus. Die PIN
ist **freiwillig**: Ohne PIN kommt jedes Gerät im Heimnetz bei eingeschaltetem Schalter direkt hinein. Die PIN hat 4–8
Ziffern. Nach 5 Fehlversuchen ist eine Absenderadresse 5 Minuten gesperrt. Eine Sitzung gilt 30 Tage und übersteht
einen Neustart, weil nur der Hash des Cookie-Werts gespeichert ist. Eine neue PIN, das Entfernen der PIN oder das
Ausschalten beendet alle Sitzungen. Schalter und PIN lassen sich nur am Spiele-PC ändern. Den QR-Code erzeugt der
Browser (`qrcode-generator`). Das Profil `prod` setzt `server.address` nicht mehr.

### R3-N1 Zugriff nur aus dem eigenen Netz

- [x] Neuer Filter im Backend (vor allen `/api/**`- und Oberflächen-Anfragen), entschieden nach der Absenderadresse:
  - Loopback (`127.0.0.1`, `::1`) ist immer erlaubt.
  - Private Adressen (`InetAddress.isSiteLocalAddress()` / `isLinkLocalAddress()`, dazu IPv6 ULA `fc00::/7`) sind nur
    erlaubt, wenn der Schalter **„Im Heimnetz erreichbar“** an ist. Umgesetzt: `lan/LanAccessFilter`,
    `lan/NetworkAddresses`.
  - Jede andere Adresse bekommt immer `403`. Eine Portfreigabe am Router öffnet das Tool also nicht fürs Internet.
- [x] Den Schalter speichert die Installation, nicht der Spielstand (neue Einstellungskarte „Tablet & Netzwerk“).
  Er wirkt sofort, ohne Neustart. `server.address` bleibt ungesetzt. Umgesetzt: Tabelle `lan_settings` (eine Zeile je
  Installation). `application-prod.yml` setzt `server.address: 0.0.0.0` nicht mehr.
- [x] Die CORS-Regel in `WebConfig` bleibt unverändert. Das Tablet lädt die Oberfläche vom Backend selbst, es gibt
  also keine fremde Herkunft.
- [x] Live-Updates (Server-Sent Events) laufen über dieselbe Adresse und denselben Filter.

### R3-N2 PIN für Geräte im Heimnetz

- [x] Ist der Heimnetz-Zugriff an, müssen sich Geräte, die nicht der Spiele-PC selbst sind, einmal mit einer PIN
  anmelden. Danach gilt ein Sitzungs-Cookie (`HttpOnly`, `SameSite=Strict`), das auch `EventSource` automatisch
  mitschickt. Der Spiele-PC (Loopback) braucht nie eine PIN.
- [x] Die PIN liegt nur als Hash in der Datenbank (PBKDF2 aus dem JDK, `SecretKeyFactory`
  `PBKDF2WithHmacSHA256`, keine neue Bibliothek). Nach mehreren Fehlversuchen gibt es eine kurze Sperre
  (`rpsim.web.lan.*`).
- [x] Der API-Schlüssel der KI bleibt geschützt: `SettingsController` gibt ihn schon heute nie zurück (nur
  `apiKeySet`).
- [x] Offene Entscheidung (`QUESTIONS.md`): Ist die PIN Pflicht, sobald der Heimnetz-Zugriff an ist? Vorschlag: ja.
  **Entschieden:** nein, die PIN ist freiwillig. Der Schalter ist standardmäßig aus.

### R3-N3 Adresse und QR-Code in den Einstellungen

- [x] Das Backend listet seine Adressen im Heimnetz (`java.net.NetworkInterface`, nur aktive, private IPv4-Adressen)
  und loggt sie beim Start: „Auf dem Tablet öffnen: http://192.168.x.y:8080“.
- [x] Die Einstellungskarte zeigt die Adresse(n) und dazu einen QR-Code zum Abscannen mit dem Tablet.
- [x] Offene Entscheidung (`QUESTIONS.md`): Wer erzeugt den QR-Code (kleine Frontend-Bibliothek oder das Backend)?
  Die Wahl bringt eine neue Abhängigkeit. **Entschieden:** das Frontend mit `qrcode-generator` (MIT).

### R3-N4 Symbol auf dem Startbildschirm des Tablets

- [x] Web-App-Manifest (`manifest.webmanifest`: Name „FarmPulse“, Symbole, `display: standalone`, Farben des
  Hof-Tablets) im Frontend verlinken. `apple-touch-icon.png` ist schon in `frontend/src/index.html` eingebunden.
  Umgesetzt: Symbole `icon-192.png` / `icon-512.png` aus `tools/release/make-icons.py`, Farbe `#0B0F0D`.
- [x] **Kein** Service Worker und **keine** Push-Benachrichtigungen. Beides verlangt eine sichere Verbindung (HTTPS
  oder `localhost`), das Tablet ruft das Tool im Heimnetz aber über `http://` auf. Siehe
  [Bewusst nicht aufgenommen](#bewusst-nicht-aufgenommen).

### R3-N5 Doku und Test

- [x] `docs/user-guide/installation.md`: neuer Abschnitt „Auf dem Tablet oder Handy öffnen“.
  `docs/user-guide/fehlerbehebung.md`: Windows-Firewall (Java für private Netzwerke freigeben), Tablet im Gast-WLAN.
- [x] `tools/release/build-release.sh`: Der Text von `start.bat` nennt den Weg zur Tablet-Adresse (Einstellungen).
- [x] Manueller Testplan: Aufruf vom Tablet, Anmeldung mit PIN, Live-Updates, Anruf-Overlay und Bedienung per Touch.

**Akzeptanz N:** Mit eingeschaltetem Heimnetz-Zugriff öffnet ein Tablet im selben WLAN die angezeigte Adresse, meldet
sich mit der PIN an (sofern eine gesetzt ist) und sieht Live-Updates. Prüfliste: `docs/dev/manual-test-plan.md`,
Abschnitt 12. Mit ausgeschaltetem Schalter und aus jedem nicht privaten Netz kommt
`403`.

---

## H – Nachbarn: Handel und Aufträge

**Problem heute:** Nachbarn schreiben Mails und besitzen Felder, das Tool weiß aber nicht, was darauf wächst. Wer
Ware braucht oder übrig hat, kann nur über die Verkaufsstellen des Spiels handeln. Die Aufträge im Spiel entstehen
zufällig (`g_missionManager`), und die Nachbarn haben mit ihnen nichts zu tun, außer dass das Tool sie liest
(`farm_facts.missions`, T-22).

**Idee:** Die Nachbarn werden Handelspartner. Sie bieten Ware aus ihrer Ernte an oder fragen nach Ware, die du hast.
Jede Fruchtsorte und jedes Produkt ist möglich, **sofern du dafür ein eigenes Silo hast**. Der Handel bucht die Menge
echt aus deinen Silos ab oder in sie ein. Braucht ein Nachbar Hilfe, erzeugt er einen **echten Auftrag im Spiel** auf
seinem eigenen Feld.

**Stand 30.09.2026: umgesetzt.** Entscheidungen (siehe `QUESTIONS.md`): Rollen Milchviehbetrieb (Stroh, Silage, Heu),
Ackerbau (Saatgut, Dünger, Flüssigdünger) und Gemischtbetrieb (Stroh, Saatgut). Ein Nachbar verkauft zu 105 % und
kauft zu 95 % der besten Verkaufsstelle, Vertrauen wirkt mit höchstens ± 5 %. 30 % einer Nachbarernte gehen in seinen
Vorrat, bei Weizen, Gerste und Hafer dazu 50 % davon als Stroh; der Vorrat sinkt um 20 % je Spielmonat. Höchstens
zwei Handelsnachrichten und ein Auftrag je Monat, Antwortfrist 5 Tage. Aufträge nur Pflügen und Steine sammeln, 250 €
Bonus bei Erfolg. Handel und Aufträge stehen in der eigenen App **„Handel“** (`/handel`), die Kontaktseite eines
Nachbarn verlinkt dorthin. Die Ja/Nein-Fragen im Spiel sind zwei neue Anlässe, standardmäßig aus. Der Mod-Schalter
`npcFieldExport` ist standardmäßig an. Die Auftragsgrenze des Spiels exportiert der Mod als optionales
`farm_facts.missionLimitReached`.

**Befund der Prüfung (30.09.2026):**

- ✅ FS25 bewirtschaftet Felder ohne Besitzer selbst. `field/FieldManager.lua` beschreibt sich als Klasse für „AI
  fields and the NPCs handling them“ und plant beim Laden für jedes Feld ohne Besitzer eine Frucht
  (`not field:getHasOwner() and field.isMissionAllowed`) über `FieldUpdateTask`. Die Nachbarfelder sind also echte
  Spielwerte. Das Tool kann sie lesen wie die eigenen Felder (R2-C1).
- Die NPCs bewirtschaften nur Feldzustände. Sichtbare Fahrzeuge von Nachbarn, ein Lager oder Tiere gibt es im
  Grundspiel nicht. Deshalb kommen Vorrat und Bedarf aus Backend-Formeln (siehe Grundsatz oben).
- ✅ Silos lassen sich gezielt füllen und leeren: `PlaceableSilo:refillAmount` und `PlaceableSilo:setAmount` laufen über
  die `Storage`-Objekte der Silos mit `storage:getFreeCapacity(fillTypeIndex)`, `storage:getFillLevel(fillTypeIndex)`
  und `storage:setFillLevel(level, fillTypeIndex)`.
- ✅ Einzelne Aufträge lassen sich gezielt auf einem bestimmten Feld erzeugen. Das Spiel macht es in
  `PlowMission.tryGenerateMission` / `StonePickMission.tryGenerateMission` genauso, nur mit zufälligem Feld.

### R3-H1 Nachbarfelder exportieren

- [x] `RPSimGameAdapter:collectFields` liest zusätzlich die Felder, für die `field:getHasOwner()` `false` ist und
  `field.isMissionAllowed` gilt. Export als `farm_facts.npcFields[]` mit denselben Feldern wie `fields[]`
  (`farmlandId`, `name`, `hectares`, `fruitType`, `growthState`, min/max, `withered`, `cut`, `fillType`,
  `litersPerSqm`, `groundType`). Umgesetzt: `RPSimGameAdapter:collectNpcFields` (gemeinsame Sammlung
  `collectFieldsWhere` mit den eigenen Feldern).
- [x] Gleiche Taktung wie `fields` (`fieldExportIntervalMs`), abschaltbar über den Mod-Schalter `npcFieldExport`
  (Standard an). Ohne Schalter fehlt der Block `npcFields` ganz.
- [x] Backend: `FieldService` führt die Nachbarfelder wie die eigenen (Phase, Kultur je Erntejahr). Wem ein Feld gehört,
  sagt die vorhandene Zuordnung Farmland → Charakter (`FarmlandOwnership`, `use-game-npc-owners`, T-21). Umgesetzt:
  eigener `neighbor/NpcFieldService` mit denselben Phasen (Tabellen `npc_field_record`, `npc_field_crop`), damit die
  eigenen Feld-Nachrichten und Fruchtfolge-Regeln die Nachbarfelder nicht erfassen.

**Beleg:** ✅ `field/FieldManager.lua` (Dump): `field:getHasOwner()`, `field.isMissionAllowed`, Planung der NPC-Frucht
über `FieldUpdateTask`. ✅ Die Feldwerte selbst wie in R2-C1 (`field/FieldState.lua`, `field:getFieldState()`).

**🟡 Im Spiel prüfen:** Wie oft ändert das Grundspiel die Nachbarfelder im Jahreslauf? Die Logik dafür steht nicht im
Dump (`FieldManager.lua` endet nach `saveToXMLFile`). **Fallback:** Der Nachbarvorrat (H2) rechnet mit der zuletzt
gesehenen Kultur und dem Erntejahr, wie die Fruchtfolge-Historie aus C1.

### R3-H2 Vorrat und Bedarf der Nachbarn (Backend)

- [x] **Handelsfähige Ware des Spielers:** Der Mod exportiert `farm_facts.tradeStorage[]` =
  `{ fillType, amount, freeCapacity }`. Er summiert dafür **nur** eigene Silos (`spec_silo.storages`) und
  Silo-Erweiterungen (`spec_siloExtension.storage`) und nutzt `storage:getFillLevel` / `storage:getFreeCapacity` je
  Fruchtsorte. Umgesetzt: `RPSimGameAdapter:collectTradeStorage` (nur Lager, die der eigenen Farm gehören).
  - „Du hast ein Silo dafür“ heißt: `freeCapacity + amount > 0`, das Silo nimmt diese Fruchtsorte also an.
  - Das gilt für **jede** Fruchtsorte und jedes Produkt, das ein eigenes Silo annimmt, nicht nur für Stroh.
- [x] **Vorrat eines Nachbarn:** entsteht aus seinen Feldern in H1. Eine Ernte (Phase `HARVESTED` nach `HARVESTABLE`)
  bringt Fläche × `litersPerSqm` × Anteil (Konfig) in seinen Vorrat, bei Getreide auch Stroh (Konfig-Tabelle
  Frucht → Nebenprodukt). Der Vorrat sinkt über die Zeit (Verkauf, Eigenbedarf). Umgesetzt: Tabelle
  `neighbor_stock`; eine Ernte zählt nicht, solange ein Rückspulen läuft.
- [x] **Bedarf eines Nachbarn:** feste Rollen je Nachbar (Konfig bzw. beim Anlegen des Charakters ausgewürfelt), z. B.
  „Milchviehbetrieb“ braucht Stroh, Silage und Heu, „Ackerbau“ braucht Saatgut und Dünger, sofern es diese als
  Silo-Ware gibt. Die Tiere sind Erzählung, keine Spielobjekte. Umgesetzt: `game_character.neighbor_role`,
  ausgewürfelt beim Anlegen eines Nachbarn (ältere Nachbarn beim ersten Bedarf).
- [x] Preise: aktueller Preis der besten Verkaufsstelle (`prices`) × Spanne (Konfig, z. B. Nachbar verkauft zu 105 %,
  kauft zu 95 %), Vertrauen als gedeckelter Bonus/Malus wie in der Verhandlungs-Engine. Ohne Preis im Spiel (Ware
  ohne Verkaufsstelle) nennt die Konfig einen Richtpreis je 1000 l.
- [x] Alle Werte unter `rpsim.formulas.neighbor-trade.*`, Häufigkeit gedeckelt wie bei den anderen Spawnern.

**Beleg:** ✅ `Specializations/PlaceableSilo.md` und `PlaceableSiloExtension.md` (LUADOC): beide legen ihr Lager mit
`Storage.new(...)` an. `PlaceableSilo` nutzt `storage:getFillLevels()`, `getFillLevel`, `getFreeCapacity` und
`setFillLevel`. Der Mod liest diese Lager schon heute (`RPSimGameAdapter.storageSources`).

### R3-H3 Ware beim Nachbarn kaufen

- [x] Auf der Kontaktseite eines Nachbarn (Entscheidung: in der App „Handel“, verlinkt von der Kontaktseite): **„Ware anfragen“** mit Formular (Fruchtsorte aus dem Nachbarvorrat,
  Menge). Angeboten wird nur Ware, für die du laut `tradeStorage` Platz hast. Außerdem bietet ein Nachbar von sich aus
  an, was er übrig hat.
- [x] Das Backend prüft Vorrat, freie Kapazität und Kontostand und nennt den Preis (H2). Antwort per Mail oder Anruf,
  Annahme per Knopf (optional als Ja/Nein-Frage im Spiel, F2).
- [x] Ausführung als Batch: `STORAGE_TRANSFER { direction: "IN", fillType, amount }` + `MONEY_TRANSACTION`
  (`GOODS_PURCHASE`).
- [x] Mod: verteilt die Menge wie `PlaceableSilo:refillAmount` auf die eigenen Silo-Lager mit freier Kapazität
  (`getFreeCapacity` → `setFillLevel(getFillLevel + moved)`), aber **ohne** die Spielbuchung `BOUGHT_MATERIALS`. Das
  Geld bucht die `MONEY_TRANSACTION`. Reicht die freie Kapazität nicht für die ganze Menge: `FAILED` mit
  `NO_CAPACITY`, der ganze Batch wird abgelehnt.
- [x] Vertrauen und Tagebucheintrag („Stroh von Otto Wendler gekauft“). Die Ware des Nachbarn ist ab der Zusage
  reserviert und geht bei einer abgelehnten Anweisung zurück in seinen Vorrat.

**Beleg:** ✅ `PlaceableSilo:refillAmount(fillTypeIndex, amount, price)` (LUADOC): Schleife über `spec.storages`,
`getFreeCapacity` → `setFillLevel(fillLevel + moved, fillTypeIndex)`, danach `addMoney(..., MoneyType.BOUGHT_MATERIALS)`.
Den Buchungsteil übernimmt das Tool.

### R3-H4 Nachbar fragt nach deiner Ware

- [x] Hat ein Nachbar Bedarf (H2) und du genug davon in deinen Silos (`tradeStorage.amount`), fragt er per Mail oder
  Anruf: „Mir geht das Stroh aus, kannst du mir 8.000 Liter abgeben?“ Menge und Preis legt das Backend fest.
- [x] Zusage per Knopf (optional als Ja/Nein-Frage im Spiel, F2). Ausführung als Batch:
  `STORAGE_TRANSFER { direction: "OUT", fillType, amount }` + `MONEY_TRANSACTION` (`GOODS_SALE`).
- [x] Mod: entnimmt die Menge aus den eigenen Silo-Lagern (`setFillLevel(getFillLevel - moved)`). Liegt inzwischen zu
  wenig im Silo: `FAILED` mit `INSUFFICIENT_STOCK`, keine Buchung. Der Nachbar bedankt sich trotzdem oder ist
  enttäuscht (Text und Vertrauen).
- [x] Absage kostet wenig Vertrauen, Ignorieren bis zur Frist etwas mehr. Häufige Hilfe stärkt das Dorf-Ansehen
  (Formel des Dorf-Ansehens, `PublicActionType`). Umgesetzt: `PublicActionType.NEIGHBOR_HELP`, höchstens dreimal je
  FS25-Jahr.

**Beleg:** ✅ wie H3 (`storage:getFillLevel`, `storage:setFillLevel` in `PlaceableSilo`).

**🟡 Im Spiel prüfen:** Zeigt das Spiel (Silo-Info, Preismenü) den neuen Füllstand sofort? `PlaceableSilo:refillAmount`
ruft `setFillLevel` nur auf dem Server-Pfad (`self.isServer`), ein Client schickt dafür ein
`PlaceableSiloRefillEvent`. Im Einzelspiel ist das Spiel selbst der Server, und der Mod nimmt denselben Pfad.
**Fallback:** Erscheint der Stand erst nach dem nächsten Update, genügt das. Mehrspieler bleibt wie in V1 ausgeklammert.

### R3-H5 Nachbarn vergeben echte Aufträge

- [x] Neue Anweisung `MISSION_CREATE { missionType, farmlandId }`. Der Mod sucht das Feld des Farmlands und prüft:
  - das Feld hat keinen Besitzer (`getHasOwner()` = `false`),
  - auf dem Feld läuft kein Auftrag (`field.currentMission == nil`),
  - der Auftragstyp passt zum Feldzustand (`<Klasse>.isAvailableForField(field, nil)`).

  Dann erzeugt er den Auftrag wie das Spiel selbst:
  `classObject.new(true, g_client ~= nil)` → `mission:init(field)` → `mission:setDefaultEndDate()` →
  `g_missionManager:registerMission(mission, missionType)`. Die Quittung trägt die `uniqueId` des Auftrags (`result`).
  Passt etwas nicht: `FAILED` mit `NOT_AVAILABLE`. Umgesetzt: `RPSimGameAdapter:createMission` mit den Klassen
  `PlowMission` / `StonePickMission` und `g_missionManager:getMissionType(<Klasse>.NAME)`.
- [x] Backend: Ein Nachbar mit passendem Feld (H1: z. B. abgeerntet und ungepflügt → Pflügen; Steine → Steine
  sammeln) bittet per Mail um Hilfe. Die Anweisung geht erst nach der Zusage raus. Der Auftrag erscheint dann im
  Auftragsmenü des Spiels.
- [x] Der Auftraggeber im Spiel ist automatisch der Nachbar, dem das Farmland gehört (`AbstractFieldMission:getNPC()` =
  `field.farmland:getNPC()`). Das Tool führt denselben NPC schon als Charakter (T-21).
- [x] Belohnung: Die Vergütung zahlt das Spiel nach seiner eigenen Formel. Das Tool bewertet den Abschluss über
  `farm_facts.missions` (`FINISHED`, `success`): Vertrauen, Dank-Mail, bei Erfolg optional ein kleiner Bonus
  (Konfig, `MONEY_TRANSACTION` `OTHER`). Bei Misserfolg oder Ablauf ist der Nachbar enttäuscht.
- [x] Umgekehrt (optional): Der Spieler fragt einen Nachbarn auf dessen Kontaktseite nach Arbeit. Das Backend wählt
  ein passendes Nachbarfeld. Umgesetzt: Knopf „Nach Arbeit fragen“ in der App „Handel“.
- [x] Werte unter `rpsim.formulas.neighbor-missions.*` (Häufigkeit, Bonus, Vertrauen), Obergrenze je Monat. Die
  Obergrenze des Spiels `g_missionManager:hasFarmReachedMissionLimit` wird vor dem Angebot geprüft (Export als
  `farm_facts.missionLimitReached`). Ein nach dem Laden verlorener Auftrag wird erneut angeboten.

**Beleg:** ✅ `Field/PlowMission.md` und `Field/StonePickMission.md` (LUADOC): `tryGenerateMission` =
`g_fieldManager:getFieldForMission()` → `field.currentMission`-Prüfung → `isAvailableForField(field, nil)` →
`new(true, g_client ~= nil)` → `init(field)` → `setDefaultEndDate()`. ✅ `Missions/MissionManager.md`:
`registerMission(mission, missionType)` (setzt `mission.type`, `register()`, `addMission`,
`MessageType.MISSION_GENERATED`), `getMissionType(name)`, `hasFarmReachedMissionLimit`. ✅
`Field/AbstractFieldMission.md`: `init(field)` → `setField`, `getNPC()`, `getReward()` (Fläche × `getRewardPerHa` ×
Schwierigkeitsfaktor).

**🟡 Im Spiel prüfen:**

- Belegt ist das Muster für **Pflügen** (`PlowMission`) und **Steine sammeln** (`StonePickMission`). Für die anderen
  Auftragsarten (Säen, Ernten, Düngen, …) fehlt `tryGenerateMission` bzw. `isAvailableForField` in der LUADOC.
  **Fallback:** Nur die belegten Typen anbieten. Weitere Typen werden über `g_missionManager:getMissionType(name)`
  und ihr `classObject` aktiviert, sobald ein Spieltest sie bestätigt.
- Übersteht ein so erzeugter Auftrag Speichern und Laden (`MissionManager:saveToXMLFile` / `loadFromXMLFile`)?
  **Fallback:** Das Backend gleicht nach dem Laden über `farm_facts.missions` ab und bietet einen verlorenen Auftrag
  erneut an.

**Akzeptanz H:** Ein Nachbar mit Kühen fragt nach Stroh. Nach der Zusage fehlt die Menge in deinem Silo, und das Geld
ist gebucht. Du kaufst beim Nachbarn Weizen, er landet in deinem Silo. Ohne passendes Silo wird die Ware nicht
angeboten. Ein Nachbar bittet ums Pflügen seines Feldes, und der Auftrag erscheint im Auftragsmenü des Spiels mit ihm
als Auftraggeber.

---

## L – Eigene Felder verpachten

**Problem heute:** Das Fachkonzept schließt die Verpachtung eigener Felder für V1 aus („Bewusst nicht in Version 1:
Verpachtung eigener Felder“, `docs/concept/Fachliches_Konzept_V3.md`). Gepachtet werden kann nur ein NPC-Feld
(`ContractKind.LEASE`, `contract/LeaseService.java`).

**Idee:** Die Pacht in umgekehrter Richtung. Du verpachtest ein eigenes Feld an einen Nachbarn und bekommst eine
monatliche Pacht.

**Beleg:** – (kein neuer Mod-Eingriff). `LeaseService` überträgt das Feld schon heute per `FARMLAND_TRANSFER`
(`TO_PLAYER` bei Beginn, `FROM_PLAYER` am Ende). Die Verpachtung nutzt dieselbe Anweisung in umgekehrter Richtung.

### R3-L1 Verpachtung anbieten und abschließen

- [ ] Flurkarte → eigenes Feld → **„Verpachten“**: Das Formular nennt Laufzeit (FS25-Jahre, Konfig) und Wunschpacht.
  Nicht möglich bei gepachteten Feldern, bei laufender Verhandlung oder Versteigerung und bei einem als Sicherheit
  eingetragenen Feld ohne Zustimmung der Bank (K1).
- [ ] Interessenten wie beim Feldverkauf (Verhandlungs-Engine): Nachbarn mit Interesse und Kapital geben ein Gebot je
  Hektar und Monat ab, bis zu drei Runden. Richtwert: Feldpreis × Pachtrendite (Konfig) / 12.
- [ ] Beginn: Batch `FARMLAND_TRANSFER FROM_PLAYER`. Das Feld gehört im Spiel keiner Farm mehr, und das Grundspiel
  bewirtschaftet es als NPC-Feld. Im Tool bleibt der Spieler Eigentümer mit dem neuen Merkmal `leasedFromPlayer`, als
  Gegenstück zu `isLeasedToPlayer` in `FarmlandOwnershipService.reconcile`. Der Abgleich wertet die Übertragung also
  nicht als Verkauf.
- [ ] Monatliche Pacht als `MONEY_TRANSACTION` `LEASE_INCOME` über `ContractBillingService`.
- [ ] Ende: `FARMLAND_TRANSFER TO_PLAYER`, Mail des Pächters, Verlängerung per Knopf (wie `/renew`).
- [ ] Auswirkungen:
  - Steuer: Die Pacht zählt als operative Einnahme (Journal, R2-B2).
  - Bank: laufende Einnahme im Cashflow.
  - Familie: Das Verpachten des Familienfelds kostet weniger Familien-Vertrauen als ein Verkauf (Konfig).
  - Amt: Die Bewirtschaftungspflicht (R2-E2) prüft nur eigene bewirtschaftete Felder, das verpachtete Feld fällt heraus.
- [ ] Werte unter `rpsim.formulas.lease-out.*`.

**🟡 Im Spiel prüfen:** Was passiert mit dem Aufwuchs beim Übergang? Bleibt der Feldzustand beim Besitzerwechsel
über `FarmlandManager:setLandOwnership` stehen, und bekommt das Feld während der Pacht Aufträge des Grundspiels?
**Fallback:** Beginn und Ende nur zulassen, wenn das Feld in Phase `EMPTY` oder `HARVESTED` ist (Daten aus R2-C1).

**Akzeptanz L:** Ein verpachtetes Feld verschwindet im Spiel aus deinem Besitz, die Pacht kommt jeden Monat, und
nach Ablauf gehört das Feld wieder dir. Das Tool meldet dabei weder Verkauf noch Kauf.

---

## K – Kredit und Finanzplanung

**Stand 01.10.2026: umgesetzt.** Entscheidungen (siehe `QUESTIONS.md`): Beleihungswert = Feldpreis × 0,6. Volle Deckung
senkt den Zins um 1,0 Prozentpunkt (anteilig darunter), dazu bis zu +20 Punkte auf „Kredit zu groß für den Betrieb“.
Über 50 % des Vermögens muss die Grundschuld den Teil darüber decken; sonst nennt die Bank im Gegenangebot weitere
eigene Felder (größte zuerst). Verkauf in der Flurkarte nur mit Zustimmung: Der Erlös tilgt den Beleihungswert im selben
Batch, ohne Vorfälligkeitsentschädigung. Verkauf im Spielmenü: Vertrauen −10, Forderung in der Bank-App (10 Tage),
unbezahlt eine verpasste Rate, Vertrauen −5 und keine neuen Kredite bis zur Zahlung. Verwertung bei Fälligstellung nur
im harten Weltmodus. Die Liquiditätsplanung zeigt 12 Monate, die Reserve ist ein Monat Fixkosten, die Bankberaterin
warnt einmal je Engpass in den nächsten 3 Monaten. Jahresgespräch: Einladung 10 Tage, ab Score 75 −0,25
Prozentpunkte auf jeden laufenden Kredit (höchstens −1,0 je Kredit, nie unter 1 %), die Rate sinkt, die Laufzeit bleibt.

### R3-K1 Kreditsicherheiten (Grundschuld)

**Problem heute:** Das Fachkonzept hat Sicherheiten „als spätere Erweiterung vorgemerkt“. `CreditFormula` kennt nur
Kapitaldienst, Eigenkapital, Liquidität, Größe und Zahlungshistorie.

- [x] Im Kreditantrag eigene Felder als Sicherheit wählen. Beleihungswert = Feldpreis (`assets.farmland[].price`) ×
  Beleihungsquote (Konfig, Vorschlag 0,6). Umgesetzt: Auswahl im Kreditformular der Bank-App, Tabelle
  `loan_collateral` (Migration V26), `credit/CollateralService`. Eigenes Feld = in der Flurkarte dem Spieler gehörend,
  nicht gepachtet.
- [x] Wirkung nach Formel, gedeckelt:
  - Zinsnachlass proportional zur Deckung (`credit.collateral.max-interest-discount`),
  - Bonus auf die Kennzahl „Kredit zu groß für den Betrieb“,
  - große Kredite über `credit.collateral.required-above-share` des Vermögens nur mit Sicherheit. Ohne Sicherheit
    wird der Antrag nicht abgelehnt, sondern die Bank macht ein Gegenangebot („mit Grundschuld“), wie im Fachkonzept
    vorgesehen. Umgesetzt: `CreditFormula.coverage` / `interestDiscount` / `requiredCollateral`,
    `rpsim.formulas.credit.collateral.*`.
- [x] Belastete Felder: Verkauf oder Verpachtung (L) im Tool nur nach Zustimmung der Bank (Mail, Knopf). Ein Verkauf
  über das Spielmenü (Erkennung aus R2-D2) löst eine Reaktion der Bank aus (Vertrauensverlust, Forderung einer
  Sondertilgung in Höhe des Beleihungswerts). Umgesetzt: Knopf „Verkauf erlauben lassen“ am Kredit; der Erlös tilgt
  im Verkaufs-Batch. Der Verkauf im Spielmenü wird als Fall `COLLATERAL_CLAIM` gefordert. Die Verpachtung (L) folgt mit
  Abschnitt L.
- [x] Die Sicherheit wird frei, wenn der Kredit getilgt ist (auch durch Sondertilgung).
- [x] Entschieden (`QUESTIONS.md`): Soll die Bank bei Fälligstellung (bestehende Eskalation
  `CREDIT_CALLBACK`) das Feld verwerten (`FARMLAND_TRANSFER FROM_PLAYER` + Gutschrift gegen die Restschuld)? Oder
  bleibt es wie heute bei Text und Vertrauen? Entscheidung: Verwertung nur im harten Weltmodus
  (`credit-hard.collateral.realise-on-callback: true`), Feld für Feld, bis die Restschuld gedeckt ist.

**Beleg:** – (kein Mod-Eingriff; `FARMLAND_TRANSFER` gibt es seit V1).

### R3-K2 Liquiditätsplanung über 12 Monate

**Problem heute:** Der Kalender (`tablet/CalendarPlanService.java`) zeigt nur die Abbuchungen des nächsten
Monatsanfangs und den voraussichtlichen Kontostand.

- [x] Neue Ansicht in der Bank-App: die nächsten 12 FS25-Monate mit allen **bekannten** Posten:
  - Gehälter, Kreditraten (Restlaufzeit aus `LoanService`), Verträge, Pacht (L) und Altenteil,
  - Steuervorauszahlungen in den Perioden 1, 4, 7 und 10 (`CalendarPlanService.PREPAYMENT_PERIODS`),
  - fällige Vorkontrakte (M2). Seit Abschnitt M als erwartete Einnahme im Liefermonat; Pachteinnahmen (L) folgen mit
    Abschnitt L.
- [x] Einnahmen als klar gekennzeichnete **Schätzung**: Durchschnitt des operativen Ergebnisses je Kalendermonat aus
  dem Journal (R2-B) des Vorjahres, sonst der Schnitt der vorhandenen Monate. Die schon bekannten Posten werden
  herausgerechnet, damit nichts doppelt zählt.
- [x] Warnung, in welchem Monat der Kontostand unter null oder unter die Liquiditätsreserve fällt. Optional meldet
  sich die Bankberaterin vorab (wie die Frühwarnung aus R2-B5). Umgesetzt: `finance/LiquidityPlanService`,
  `GET /api/liquidity-plan`, Karte „Liquiditätsplanung“, `rpsim.formulas.liquidity-plan.*`.

**Beleg:** – (nur Backend und Oberfläche).

### R3-K3 Jahresabschluss und Jahresgespräch

- [x] Beim Jahreswechsel (Periode 12 → 1) erzeugt das Backend einen **Hofbericht** mit Einnahmen und Ausgaben je
  Kategorie aus dem Journal. Der Mod hält `financeJournalPeriods` = 13 Monate, ein volles Jahr ist also da. Dazu:
  - Gewinn und Steuer (`TaxYear`),
  - Kultur und Ertrag je Feld (`FieldCropHistory`),
  - Regenstunden (`RainPeriod`),
  - Stallwerte (`HusbandryRecord`),
  - Personal, Vertrauen und Dorf-Ansehen im Vergleich zum Vorjahr.

  Umgesetzt: `finance/FarmReportService` (Tabelle `farm_report`), `GET /api/farm-reports`. Der Ertrag je Feld wird ab
  jetzt bei der Ernte gespeichert (Fläche × `litersPerSqm` der letzten Sichtung reif). Der Vergleich nutzt die
  Kennzahlen des vorigen Berichts; der erste Bericht hat keine Vorjahresspalte.
- [x] Ansicht in der Bank-App, dazu ein kurzer Tagebucheintrag. Die KI kommentiert, die Zahlen kommen aus dem Backend.
- [x] **Jahresgespräch:** Die Bankberaterin lädt ein. Nach Formel (Bonitätsscore zum Stichtag) bietet sie bei gutem
  Ergebnis eine Zinssenkung für laufende Kredite an (`credit.annual-review.*`, gedeckelt). Bei schlechtem Ergebnis
  gibt es nur einen ernsten Ton, keine automatische Verschärfung laufender Verträge. Umgesetzt:
  `credit/AnnualReviewService`, Fälle `ANNUAL_REVIEW` / `ANNUAL_REVIEW_OFFER` in der Bank-App.

**Beleg:** – (nur Backend und Oberfläche).

**Akzeptanz K:** Ein Kredit mit Grundschuld hat einen niedrigeren Zins als ohne. Die Liquiditätsplanung zeigt den
Monat der nächsten Steuervorauszahlung mit Betrag. Nach dem ersten FS25-Jahr gibt es einen Hofbericht und eine
Einladung zum Jahresgespräch.

---

## M – Markt und Vermarktung

**Stand 01.10.2026: umgesetzt.** Entscheidungen (siehe `QUESTIONS.md`): höchstens 10 aktive Preisalarme, „beliebig“
= bester Preis aller Verkaufsstellen, Hinweis 1 Spieltag gültig. Vorkontrakt: Festpreis = aktueller Preis × (1 − 2 %
je Monat Vorlauf), Liefermonat 1–12 Monate voraus, Lieferfenster der ganze Liefermonat, 1.000–200.000 l, höchstens 5
offene, kein Rücktritt, Abschluss nur über das Formular (Preis anfragen, dann abschließen). Strafe 25 % der Fehlmenge
zum Festpreis, Vertrauen der Landhändlerin −5 / +3. Hofladen: Kartoffeln, Weizen, Hafer, Zuckerrüben, Raps zum besten
Marktpreis × 1,3, 200–2.000 l (höchstens 20 % des Bestands), höchstens 2 Bestellungen je Monat (Wahrscheinlichkeit
0,4), 3 Tage Antwortfrist, Bestellungen in der App „Handel“. Ein neuer Anlass für Ja/Nein-Fragen im Spiel ist
standardmäßig aus. Der Mod bleibt unverändert: `NOTIFICATION`, `PRICE_EVENT` / `FIXED` und `STORAGE_TRANSFER` gibt
es schon.

### R3-M1 Preisalarm

- [x] In der Agrarbörse einen Alarm anlegen: Fruchtsorte, Verkaufsstelle (oder „beliebig“), Schwelle, Richtung
  (über/unter). Umgesetzt: Karte „Preisalarm“, `market/PriceAlarmService`, Tabelle `price_alarm` (Migration V27).
- [x] Geprüft wird bei jedem Eingang von `farm_facts.prices`. Wird die Schwelle erreicht, blendet das Spiel einen
  Hinweis ein (`NOTIFICATION`, Taste aus F3 nicht nötig). Dazu kommt eine kurze Mail der Landhändlerin
  (`LAND_AGENT`) mit Menge im Silo und aktuellem Wert.
- [x] Ein Alarm feuert einmal und schaltet sich dann ab (erneut aktivierbar). Höchstzahl je Spielstand in der Konfig.

**Beleg:** – (`NOTIFICATION` und `prices` gibt es seit V1).

### R3-M2 Vorkontrakt (Ernte vorab verkaufen)

- [x] Formular in der Agrarbörse: Fruchtsorte, Verkaufsstelle, Menge, Liefermonat. Das Backend nennt den Festpreis:
  aktueller Preis × Terminfaktor (Konfig, je Monat Vorlauf ein Ab- oder Aufschlag). Umgesetzt: Karte „Vorkontrakt“,
  `market/ForwardContractService`, Tabelle `forward_contract`.
- [x] Umsetzung mit der vorhandenen Anweisung `PRICE_EVENT` / `FIXED` (`fixedPrice`, `maxQuantity`,
  `deadlineGameTime`, Start über `gameTimeEarliest` = Beginn des Liefermonats). Die gelieferte Menge meldet der Mod
  schon heute in `contractReports` (`deliveredQuantity`).
- [x] Nach der Frist: Fehlmenge × Festpreis × Strafanteil (Konfig) als `MONEY_TRANSACTION` `CONTRACT_PENALTY`, Mail des
  Abnehmers, Vertrauensverlust. Volle Lieferung → Vertrauensbonus.
- [x] Je Verkaufsstelle und Fruchtsorte nur ein aktiver Festpreis, denn `FIXED` hat Vorrang vor `MULTIPLIER`.
  `MarketEventEngine` erzeugt dort in der Zeit kein `SPECIAL_OFFER`.
- [x] Die Liquiditätsplanung (K2) zeigt die erwartete Einnahme als eigenen, als „erwartet“ markierten Posten im
  Liefermonat.

**Beleg:** – (`PRICE_EVENT` / `FIXED` und `contractReports` gibt es seit V1, `docs/dev/bridge-protocol.md`).

### R3-M3 Hofladen (Direktvermarktung ans Dorf)

- [x] Dorfbewohner (`VILLAGER`) bestellen in Abständen kleine Mengen aus deinem Silo-Bestand, zum Hofladenpreis:
  bester Marktpreis × Aufschlag (Konfig). Welche Fruchtsorten gefragt sind, steht in einer Konfigliste. Angeboten
  wird nur, was laut `tradeStorage` in eigenen Silos liegt.
- [x] Annahme per Knopf, Ausführung wie H4: `STORAGE_TRANSFER OUT` + `MONEY_TRANSACTION` `GOODS_SALE`.
- [x] Wirkung auf das Dorf-Ansehen (`PublicActionType`, gedeckelt). Wer oft ablehnt, bekommt seltener Bestellungen.
- [x] Werte unter `rpsim.formulas.farm-shop.*`. Umgesetzt: `neighbor/FarmShopService`, Fall `FARM_SHOP_ORDER`,
  Bereich „Hofladen“ in der App „Handel“.

**Beleg:** ✅ wie R3-H4 (Silo-Lager über `storage:getFillLevel` / `setFillLevel`).

**Akzeptanz M:** Ein Preisalarm erscheint im Spiel, sobald die Schwelle erreicht ist. Ein Vorkontrakt zahlt den
Festpreis an der gewählten Verkaufsstelle, und eine Fehlmenge kostet die Strafe. Eine Hofladen-Bestellung bucht die
Ware aus dem Silo ab.

---

## W – Dürre und Wetterrisiko

**Problem heute:** Das Backend zählt die Regenstunden je Monat (`RainPeriod`, R2-C2). Bisher wirken sie nur auf die
Hagelwahrscheinlichkeit (`insurance.hail-rain-factor`). Eine Wettervorhersage gibt es nicht (keine FS25-Funktion,
siehe V2).

**Stand 01.10.2026: umgesetzt.** Entscheidungen (siehe `QUESTIONS.md`): Statt fester Regenstunden zählt der
Regenanteil, weil die Regenstunden von „Tage je Periode“ abhängen und das Backend nur die beobachtete Zeit kennt:
Ein Wachstumsmonat (Perioden 3–8, Mai bis Oktober) ist trocken bei Regen unter 3 % der beobachteten Zeit
(`drought.max-rain-share`), wenn mindestens die Hälfte des Monats beobachtet wurde; sonst gilt er als unbekannt und
beendet die Reihe. Dürre ab 2 trockenen Monaten in Folge, je Reihe höchstens eine. Die Genossenschaft warnt beim
ersten trockenen Monat; läuft keine Dürreversicherung, schickt die Versicherung dazu ein Angebot. Bei der Dürre
`HARVEST_FAILURE` für die 3 flächengrößten Kulturen im Dorf (eigene Felder im Wachstum oder erntereif, Nachbarfelder
aus H1) an jeder Verkaufsstelle, die sie annimmt; Paare mit offenem Ereignis oder Festpreis werden übersprungen. Eine
Klatschnachricht (`FIELD_GOSSIP`, Thema `DROUGHT`) über das größte eigene Feld mit Kultur. Dürrehilfe 150 € je Hektar
eigener Felder, die in einem Dürremonat wuchsen (das Backend zeichnet die wachsenden eigenen Felder ab jetzt je Monat
auf), Antrag per Knopf in der App „Ämter“, Frist 15 Tage, sofort als `SUBSIDY`, 50 % Abzug bei laufender
Dürreversicherung, ohne wachsende Felder kein Antrag. Dürreversicherung (Tarif `DROUGHT_INDEX`): 4 € je Hektar und
Monat (eigene Felder ohne Pachtflächen, die Prämie folgt zu jedem Monatsbeginn der aktuellen Fläche), 200 € je Hektar
der Fläche bei Ausrufung, nur wenn bezahlt und vor dem ersten trockenen Monat der Reihe abgeschlossen; Angebot auf
Anfrage (7 Tage gültig), jederzeit kündbar, endet nach 2 offenen Prämien.

**Beleg:** – (kein Mod-Eingriff; `weather` und `RainPeriod` gibt es seit R2-C2).

### R3-W1 Dürre erkennen

- [x] Eine Dürre liegt vor, wenn in `drought.min-periods` aufeinanderfolgenden Wachstumsmonaten (Konfigliste
  `drought.periods`, FS25-Perioden, 1 = März) die Regenstunden unter `drought.max-rain-hours` liegen.
- [x] Folgen:
  - Die Genossenschaft warnt beim ersten trockenen Monat.
  - Bei ausgerufener Dürre erzeugt `MarketEventEngine` ein regionales `HARVEST_FAILURE` für Kulturen, die im Dorf
    stehen (eigene Felder und Nachbarfelder aus H1).
  - Klatsch im Dorf.
- [x] Der Spielertrag ändert sich **nicht** (kein belegter Schreibzugriff auf die Fruchtdichte, siehe V2
  „Bewusst nicht aufgenommen“). Die Dürre wirkt über Preise, Geld und Geschichten.

### R3-W2 Dürrehilfe

- [x] Das Amt (`AUTHORITY`) zahlt nach einer ausgerufenen Dürre eine Hilfe je Hektar eigener Felder, die in der Zeit
  eine Kultur in Phase `GROWING` hatten (Daten aus R2-C1). Die Zahlung läuft als `SUBSIDY`, auf Antrag per Knopf und
  mit Frist.
- [x] Wer eine Dürreversicherung (W3) hat, bekommt einen Abzug auf die Hilfe (Konfig), wie bei echten Hilfsprogrammen.

### R3-W3 Wetterindex-Versicherung

- [x] Neues Angebot der Versicherung (`INSURANCE_AGENT`): Beitrag je Hektar und Monat. Die Auszahlung je Hektar kommt,
  sobald W1 eine Dürre feststellt, ohne Schadensmeldung (Index statt Gutachten).
- [x] Umsetzung als weiterer Deckungstyp von `ContractKind.INSURANCE` neben Sturm und Hagel. Auszahlung als
  `INSURANCE_PAYOUT`.
- [x] Werte unter `rpsim.formulas.drought.*` und `rpsim.formulas.insurance.drought-*`.

**Akzeptanz W:** Nach mehreren trockenen Sommermonaten melden sich Genossenschaft und Amt. Die Preise der betroffenen
Kulturen steigen regional, und die Versicherung zahlt ohne Schadensmeldung.

---

## V – Gebrauchtmaschinen

**Problem heute:** Die Verhandlungs-Engine ist laut Fachkonzept „bewusst nicht ackerland-spezifisch benannt“ („z. B.
Gebrauchtmaschinen direkt von einem NPC“), `AssetType` kennt aber nur `FARMLAND`. Es gibt keine Anweisung, die ein
Fahrzeug ins Spiel bringt oder entfernt.

**Befund der Prüfung (30.09.2026):** Machbar.

- ✅ Das Spiel lädt selbst Fahrzeuge für einen Besitzer und legt sie auf einen freien Shop-Platz: Aufträge tun es in
  `AbstractMission:spawnVehicle` mit `VehicleLoadingData`.
- ✅ Fahrzeuge werden mit `vehicle:delete()` entfernt: Aufträge löschen ihre Leihfahrzeuge so. `Vehicle:delete` stoppt
  einen laufenden Helfer und trägt das Fahrzeug aus den eigenen Fahrzeugen aus.
- ✅ Gebrauchtwerte (Alter, Betriebsstunden, Schaden, Abnutzung) lassen sich setzen. Der Gebrauchtmarkt des
  Grundspiels macht dasselbe mit `saleItem.damage`, `saleItem.wear` und `saleItem.operatingTime`.

### R3-V1 Fahrzeug-Katalog exportieren

- [ ] `market_context.storeVehicles[]` beim Missionsstart:
  - Einträge aus `g_storeManager:getItems()` mit `species == StoreSpecies.VEHICLE` und `showInStore`,
  - Felder: `xmlFilename`, `name`, `price`, `lifetime`, `categoryName`, `isMod`,
  - die Gruppe `motorized`: Motor vorhanden (`storeItem.specs.power ~= nil`, nach `StoreItemUtil.loadSpecsFromXML`).
- [ ] Abschaltbar über den Mod-Schalter `storeCatalogExport`, Obergrenze der Einträge in der Konfig.

**Beleg:** ✅ `Shop/StoreManager.md` (LUADOC): `getItems()`, in `loadItem` die Felder `name`, `xmlFilename`, `species`,
`showInStore`, `isMod`, `categoryName`, `price`, `lifetime`. ✅ `Vehicle.calculateSellPrice` (Dump `Vehicle.lua`) ruft
`StoreItemUtil.loadSpecsFromXML(storeItem)` und nutzt `storeItem.specs.power`.

**🟡 Im Spiel prüfen:** Wie lange dauert `loadSpecsFromXML` für den ganzen Katalog? **Fallback:** `motorized` weglassen,
das Backend nutzt dann den Faktor für Motorfahrzeuge.

### R3-V2 Gebrauchte Maschine kaufen

- [ ] Die Landmaschinenwerkstatt (`WORKSHOP`) oder ein Nachbar bietet in Abständen eine gebrauchte Maschine aus dem
  Katalog an. Alter (Monate), Betriebsstunden und Schaden würfelt das Backend im Rahmen der Konfig. Den Preis
  berechnet das Backend mit der Formel des Spiels:

  ```text
  Stundenfaktor = 1 − Stunden ^ Faktor / lifetime
  Faktor        = 1,3 ohne Motor, sonst 1,0
  Altersfaktor  = min(−0,1 · ln(Jahre) + 0,75; 0,85)
  Preis         = max(Listenpreis × Stundenfaktor × Altersfaktor; 3 % des Listenpreises)
  ```

  Dazu kommt ein Händleraufschlag bzw. ein Nachbarrabatt (Konfig).
- [ ] Verhandlung mit der Verhandlungs-Engine: neuer `AssetType.VEHICLE`, bis zu drei Runden wie bei Feldern.
- [ ] Nach der Einigung: neue Anweisung `VEHICLE_SPAWN { storeXmlFilename, ageMonths, operatingHours, damage, wear,
  price, moneyReason: VEHICLE_PURCHASE }`. Das Laden läuft **asynchron** (Callback), deshalb bucht der Mod den Preis
  selbst im Callback. Ein Batch mit getrennter `MONEY_TRANSACTION` wäre nicht mehr „im selben Zyklus“. Ablauf im Mod:
  1. Guthaben prüfen (wie `checkBatchFunds`).
  2. Laden wie `AbstractMission:spawnVehicle`: `VehicleLoadingData.new()`, `setFilename`,
     `setLoadingPlace(g_currentMission.storeSpawnPlaces, g_currentMission.usedStorePlaces)`,
     `setPropertyState(VehiclePropertyState.OWNED)`, `setOwnerFarmId(farmId)`, `load(callback)`.
  3. Im Callback Gebrauchtwerte setzen: `vehicle:setOperatingTime(ms, true)`, `vehicle.age = ageMonths`,
     `setDamageAmount(damage, true)` und `addWearAmount(wear, true)` (bei `Wearable`).
  4. Preis buchen (`VEHICLE_PURCHASE`) und mit `result.vehicleId` = `uniqueId` quittieren.
- [ ] Fehlerfälle: kein Platz (Ladezustand `NO_SPACE`) oder unbekanntes Shop-Item → `FAILED`, keine Buchung, die
  Werkstatt meldet sich („Stellen Sie erst Platz auf dem Hof frei“).
- [ ] Nach einem Neuladen ohne Speichern schickt das Backend die Anweisung erneut, wie `FARMLAND_TRANSFER`. Die Liste
  `processedInstructions` verhindert eine doppelte Ausführung.

**Beleg:**

- ✅ `Missions/AbstractMission.md` (LUADOC): `spawnVehicle` mit `VehicleLoadingData.new()`, `setFilename`,
  `setLoadingPlace(mission.storeSpawnPlaces, mission.usedStorePlaces)`, `setPropertyState`, `setOwnerFarmId`,
  `load(callback, target, args)`.
- ✅ `VehicleLoadingData.lua` (Dump): `setLoadingPlace` liefert `false`, wenn kein Platz frei ist.
- ✅ `Vehicle.lua` (Dump):
  - Laden: Ladezustand `NO_SPACE`, `onFinishedLoading` trägt `OWNED`-Fahrzeuge mit `addOwnedItem` ein.
  - Gebrauchtwerte: `setOperatingTime(operatingTime, isLoading)`, das Feld `age` (gespeichert als `#age`).
  - Preis: `calculateSellPrice`.
- ✅ `Specializations/Wearable.md`: `onSaleItemSet` ruft `addDamageAmount` und `addWearAmount`.

**🟡 Im Spiel prüfen:**

- Zeigt der Fahrzeugmanager des Spiels Alter und Stunden der geladenen Maschine richtig an?
- Ist ein Shop-Platz belegt, wenn der Spieler gerade dort steht?

**Fallback:** Fehlschlag mit `NO_SPACE` und Hinweis im Spiel. Die Werkstatt versucht es am nächsten Spieltag erneut.

### R3-V3 Eigene Maschine an einen Nachbarn verkaufen

- [ ] Auf der Werkstatt-App bzw. im Fahrzeug-Überblick: „Zum Verkauf anbieten“. Grundlage ist der Wert aus
  `assets.vehicles[].value` (`getSellPrice()`). Interessierte Nachbarn bieten mehr als der Händlerpreis des Spiels,
  gedeckelt (Konfig).
- [ ] Nach der Einigung: Batch `VEHICLE_REMOVE { vehicleId }` + `MONEY_TRANSACTION` `VEHICLE_SALE`.
- [ ] Mod: Fahrzeug über `g_currentMission.vehicleSystem:getVehicleByUniqueId(id)` holen und prüfen:
  - Besitzer ist die Spieler-Farm,
  - `propertyState == OWNED` (geleaste Fahrzeuge nie),
  - niemand sitzt drin (`getIsControlled()`, `Enterable`),
  - kein Helfer fährt (`getIsAIActive()`).

  Dann `vehicle:delete()`. Sonst `FAILED` mit `VEHICLE_IN_USE`, `NOT_OWN_VEHICLE` oder `VEHICLE_NOT_FOUND`.
- [ ] Tagebuch, Klatsch („Der Nachbar fährt jetzt deinen alten Fendt“).

**Beleg:**

- ✅ `VehicleSystem.lua` (Dump): `getVehicleByUniqueId`, `removeVehicle`.
- ✅ `Vehicle.lua` (Dump): `Vehicle:delete` stoppt einen laufenden Helfer (`stopCurrentAIJob`) und ruft
  `removeOwnedItem`. Außerdem `getIsAIActive()`.
- ✅ `Specializations/Enterable.md`: `getIsControlled()`.
- ✅ `Missions/AbstractMission.md`: `onSpawnedVehicle` löscht Fahrzeuge mit `vehicle:delete()`.

**🟡 Im Spiel prüfen:** Was passiert mit angehängten Geräten und geladener Ware beim Löschen? **Fallback:** Verkauf
nur zulassen, wenn das Fahrzeug die Wurzel ist (`getRootVehicle() == vehicle`) und nichts angehängt ist. Andernfalls
`FAILED` mit dem Hinweis „Bitte erst abkoppeln“.

**Akzeptanz V:** Eine gekaufte Gebrauchtmaschine steht auf dem Shop-Platz, gehört dir, zeigt die vereinbarten
Betriebsstunden und den Schaden, und der Preis ist gebucht. Eine verkaufte eigene Maschine verschwindet, und der Erlös
ist gebucht. Ohne Platz wird nichts gebucht.

---

## P – Personal

### R3-P1 Bürokraft mit mehr Wirkung

**Problem heute:** Die Bürokraft (`JobRole.OFFICE_CLERK`) verkürzt nur die Bearbeitungszeit eines Kreditantrags
(`credit/CreditApplicationService.java`).

- [ ] Sie erinnert vor Fristen: Steuer, Rechnungen der Ämter, Vorkontrakte (M2), Pachtende. Das kann heute nur der
  Steuerberater.
- [ ] Sie senkt die Wahrscheinlichkeit einer Betriebsprüfung (`TaxService`, Abschnitt „audit“) um einen Anteil, der
  mit Skill und Zufriedenheit wächst. Mit Steuerberater zählt nur der größere Effekt.
- [ ] Sie senkt Säumniszuschläge: verpasste Fristen nur, wenn sie überlastet ist (Arbeitsbelastung aus der
  Zufriedenheit).
- [ ] Werte unter `rpsim.formulas.office-clerk.*`.

**Beleg:** – (nur Backend).

### R3-P2 Azubi

- [ ] Neue Rolle `JobRole.APPRENTICE`: niedriges Gehalt (Konfig), Skill steigt monatlich, fährt Helfer wie ein
  Maschinenführer **ohne** Schulung, also nur kleine und mittlere Traktoren (Schulungen, siehe CHANGELOG).
- [ ] Nach der Ausbildungszeit (Konfig, FS25-Jahre): Übernahme als Maschinenführer mit Gehaltsverhandlung, oder er
  geht. Tagebucheintrag.
- [ ] Mod: `EMPLOYEE_ROSTER` akzeptiert `APPRENTICE` wie `MACHINE_OPERATOR` ohne `trainings`. Ein älterer Mod ignoriert
  unbekannte Rollen, dann fährt der normale Helfer.

**Beleg:** ✅ wie R2-A2 und „Schulungen“ (Zuordnung im Hook auf `AIJob.start`, Prüfung der Shop-Kategorie des Fahrzeugs).
Neu ist nur die zugelassene Rolle.

**Akzeptanz P:** Mit Bürokraft kommt vor einer Steuerfrist eine Erinnerung, und Betriebsprüfungen sind seltener. Ein
Azubi fährt im Spiel einen mittleren Traktor als Helfer, aber keinen Mähdrescher.

---

## T – Chronik

### R3-T1 Meilensteine

- [ ] Feste Liste von Meilensteinen in der Konfig, jeder mit einer Bedingung aus vorhandenen Daten, z. B.:
  - erster Kredit getilgt,
  - erstes Jahr ohne Zahlungsverzug,
  - 100 ha bewirtschaftet,
  - Rekordernte (R2-B5),
  - fünf Jahre Fruchtfolge ohne Beanstandung (R2-E2),
  - erster Handel mit einem Nachbarn (H).
- [ ] Erreichte Meilensteine landen im Tagebuch mit neuem Typ (`DiaryEntryType.MILESTONE`, heute nur `AUTO` /
  `PLAYER_NOTE`) und als Abzeichen auf dem Startbildschirm. Keine mechanische Wirkung.

### R3-T2 Hofchronik als Datei

- [ ] Tagebuch-App → **„Chronik herunterladen“**: Markdown-Datei mit Vorgeschichte, allen Einträgen, Meilensteinen
  und den Jahresberichten (K3).
- [ ] Als PDF über die Druckfunktion des Browsers, mit eigenem Druck-Stylesheet. Keine neue Bibliothek.

**Beleg:** – (nur Backend und Oberfläche).

**Akzeptanz T:** Nach dem Tilgen des ersten Kredits steht ein Meilenstein im Tagebuch. Die Chronik lässt sich als
Datei herunterladen.

---

## Bewusst nicht aufgenommen

Geprüft und verworfen, weil es im FS25-Code **keine belegte Schnittstelle** gibt oder ein Grundsatz dagegen spricht.
Falls sich das ändert (neue LUADOC, Mod-Beispiel), können die Punkte nachgezogen werden.

| Idee | Warum nicht |
| --- | --- |
| Nachbarn fahren sichtbar mit eigenen Maschinen auf ihren Feldern | Das Grundspiel bewirtschaftet Nachbarfelder nur über Zustandsänderungen (`FieldUpdateTask` in `field/FieldManager.lua`), nicht mit Fahrzeugen. Helfer-Jobs gehören immer einer Farm (`job.startedFarmId`), eine NPC-Farm mit Fahrzeugen ist nicht belegt. Ersatz: H1 liest die echten Nachbarfelder. |
| Das Tool bestimmt, was ein Nachbar anbaut | Ein `FieldUpdateTask` auf ein NPC-Feld wäre technisch möglich (Aufträge nutzen ihn beim Abschluss). Die Logik, mit der das Grundspiel die Nachbarfelder im Jahreslauf ändert, steht aber nicht im Dump und würde die Eingriffe unkontrolliert überschreiben. |
| Nachbarn haben echte Tiere oder ein echtes Lager im Spiel | NPCs besitzen im Grundspiel keine Ställe und keine Silos. Ihr Vorrat und Bedarf ist Backend-Fiktion aus ihren echten Feldern (H2). |
| Handel mit Ware außerhalb eigener Silos (Ballen, Paletten, Stall-Lager, Fahrsilo, Produktionen) | Wunsch des Projektinhabers: nur Ware, für die ein eigenes Silo da ist. Außerdem ist für Objektlager (`PlaceableObjectStorage`) kein Schreibzugriff geprüft. `ProductionPoint` ist in der LUADOC nicht dokumentiert, und ein Fahrsilo ist ein Haufen ohne `Storage`-Objekt (`BunkerSilo`). |
| Push-Benachrichtigungen und Offline-Modus auf dem Tablet | Service Worker und Web-Push verlangen eine sichere Verbindung (HTTPS oder `localhost`). Im Heimnetz läuft das Tool über `http://`. Ein eigenes Zertifikat für jede Installation wäre für Spieler zu aufwendig. Hinweise im Spiel gibt es über `NOTIFICATION`. |
| Zugriff aus dem Internet | Wunsch des Projektinhabers: nur im eigenen Netz. N1 weist nicht private Adressen immer ab. |
| Echter Ertragsverlust im Spiel durch Dürre | Wie in V2 für Hagel: kein belegter, sicherer Schreibzugriff auf Fruchtdichte-Karten. Die Dürre wirkt über Preise, Hilfen und Versicherung (W). |
| Andere Auftragsarten als Pflügen und Steine sammeln sofort anbieten | `tryGenerateMission` / `isAvailableForField` ist in der LUADOC nur für `PlowMission` und `StonePickMission` vollständig belegt. Weitere Typen folgen nach Spieltest (🟡 in H5). |

---

## Quellen

- FS25-Quellcode-Dump (`dataS`): <https://github.com/Dukefarming/FS25-lua-scripting>. Genutzt wurden
  `field/FieldManager.lua`, `Vehicle.lua`, `VehicleLoadingData.lua` und `VehicleSystem.lua`.
- FS25 Community LUADOC: <https://github.com/umbraprior/FS25-Community-LUADOC>. Genutzt wurden:
  - `script/Missions/MissionManager.md` (`registerMission`, `addMission`, `getMissionType`,
    `hasFarmReachedMissionLimit`)
  - `script/Missions/AbstractMission.md` (`spawnVehicle`, `onSpawnedVehicle`, `setDefaultEndDate`)
  - `script/Field/AbstractFieldMission.md` (`init`, `setField`, `getNPC`, `getReward`), `script/Field/PlowMission.md`,
    `script/Field/StonePickMission.md`
  - `script/Specializations/PlaceableSilo.md` (`refillAmount`, `setAmount`), `PlaceableSiloExtension.md`
  - `script/Specializations/Wearable.md` (`onSaleItemSet`), `Enterable.md` (`getIsControlled`)
  - `script/Shop/StoreManager.md` (`getItems`, `loadItem`), `script/Shop/BuyVehicleData.md`,
    `script/Vehicles/VehicleLoadingData.md`, `script/Vehicles/VehicleSystem.md`
- Bestehende Projekt-Doku: [`ROADMAP_V2.md`](ROADMAP_V2.md), `docs/concept/Fachliches_Konzept_V3.md`,
  `docs/concept/Technisches_Konzept_V6.md`, `docs/dev/bridge-protocol.md`, `docs/dev/offene-technische-punkte.md`,
  `docs/dev/manual-test-plan.md`.
