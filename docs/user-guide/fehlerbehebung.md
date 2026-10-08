# Fehlerbehebung

## Das Backend startet nicht

| Meldung / Symptom | Lösung |
| --- | --- |
| `java` wird nicht gefunden, oder `UnsupportedClassVersionError` | Java 21 installieren (`java -version` muss 21 oder neuer zeigen) |
| `Port 8080 was already in use` | anderes Programm auf Port 8080 beenden, oder in `application-local.yml` `server.port: 8090` setzen und dann <http://localhost:8090> öffnen |
| Fenster schließt sich sofort | `start.bat` aus einer Eingabeaufforderung starten, um die Meldung zu lesen |
| `The database … refuses the password from …\db.properties` | Die Datei `<Benutzerordner>\.rpsim\db.properties` gehört nicht (mehr) zur Datenbank – meist wurde sie gelöscht oder aus einer anderen Installation kopiert. Die passende `db.properties` aus einer Sicherung zurücklegen. Ohne sie ist die Datenbank nicht mehr zu öffnen: dann wie unter [Neu anfangen](#neu-anfangen) den Ordner `.rpsim` löschen. |

## Der Mod schreibt keine Daten

Oben im Browser steht dauerhaft *Kein Spielstand verknüpft*, und im Onboarding erscheint kein Spielstand.

1. Ist **FS25_RPSim** im Spielstand aktiviert? (Mod-Auswahl beim Laden)
2. Liegt das ZIP ungeöffnet in `Dokumente\My Games\FarmingSimulator2025\mods\`?
3. Gibt es den Ordner `…\modSettings\FS25_RPSim\export\` mit einer frischen `farm_facts.json`? Der Mod schreibt
   etwa **jede Minute**, nur solange der Spielstand geladen ist.
4. `Dokumente\My Games\FarmingSimulator2025\log.txt` nach `RPSim` durchsuchen – dort stehen Fehler des Mods.
5. Sucht das Backend am richtigen Ort? Beim Start zeigt die Konsole den Bridge-Pfad. Bei umgeleitetem
   Dokumente-Ordner (OneDrive) den Pfad in `application-local.yml` eintragen, siehe
   [Installation](installation.md#eigene-einstellungen-optional).

## Spielstand erscheint nicht zum Verknüpfen

- Der Mod braucht nach dem Laden bis zu einer Minute. Im Onboarding-Schritt 5 **Aktualisieren** klicken.
- Es werden nur Spielstände angezeigt, die **noch nicht verknüpft** sind. Wurde der Spielstand schon mit einem
  früheren Onboarding verknüpft, ist er bereits aktiv – öffne einfach den Startbildschirm.
- Jeder Spielstand hat eine eigene Kennung (`savegameId`), die in allen Dateien steht. Hast du im Spiel einen
  anderen Spielstand geladen, meldet der Mod dessen Kennung – FarmPulse schaltet automatisch auf den dazu
  verknüpften Spielstand um bzw. bietet ihn im Onboarding an. Daten verschiedener Spielstände werden nie vermischt.

## Buchungen kommen im Spiel nicht an

Kredit genehmigt, aber das Geld fehlt im Spiel?

- Das Spiel muss laufen (Spielstand geladen). Der Mod holt Anweisungen etwa alle 5 Sekunden ab.
- Anweisungen für einen anderen Spielstand verwirft der Mod bewusst (Warnung im `log.txt`).
- Jede Anweisung wird genau einmal ausgeführt – auch nach einem Neustart des Spiels. Bereits ausgeführte stehen
  im Spielstand in `FS25_RPSim.xml`.
- Zusammengehörige Buchungen (z. B. Feld und Kaufpreis) werden nur gemeinsam ausgeführt oder gar nicht.
- **Zu wenig Geld:** Eine Abbuchung, die dein Kontostand nicht deckt, führt der Mod nicht aus. Die App **Aufgaben**
  zeigt dann unter *Hinweise aus dem Spiel* „Buchung nicht ausgeführt“. FarmPulse behandelt das wie eine verpasste
  Zahlung: Eine Kreditrate bleibt fällig und läuft in die Mahnstufen, ein Gehalt bleibt offen, ein Feldkauf platzt.

## Spielstand ohne Speichern neu geladen

Hast du FS25 beendet, ohne zu speichern, oder einen älteren Spielstand geladen, fehlen im Spiel die Buchungen, die
seitdem ausgeführt wurden (z. B. eine Kreditauszahlung), obwohl FarmPulse sie schon kennt. FarmPulse erkennt den
Zeitsprung zurück:

- **Bis zu einem Spieltag** zurück: Die fehlenden Buchungen werden automatisch erneut gesendet. Die App **Aufgaben**
  zeigt einen Hinweis, wie viele es waren.
- **Mehr als ein Spieltag** zurück: Die App **Aufgaben** fragt dich: **Nachbuchen** (die Buchungen werden im Spiel erneut
  ausgeführt) oder **Tool-Stand beibehalten** (nichts wird nachgebucht).

Wichtig: Nur die Buchungen im Spiel werden wiederhergestellt. Mails, Vertrauen, Verhandlungen und alle anderen
Abläufe im Tool werden **nicht** zurückgedreht. Die Schwelle ist einstellbar
(`rpsim.bridge.rewind-auto-resend-max-hours`).

## Andere Mods mit Überschneidungen

Die App **Aufgaben** zeigt im Tab *Meldungen* unter *Hinweise aus dem Spiel* „Mods mit Überschneidungen erkannt“, wenn einer dieser Mods
aktiv ist (Liste auch unter **Einstellungen → Im Spiel**). FarmPulse schaltet nichts ab, aber die Effekte können
sich überlagern:

| Mod | Was sich überschneidet |
| --- | --- |
| `FS25_MarketDynamics` | Verändert dieselben Verkaufspreise. Preisfaktoren multiplizieren sich, eigene Markt-Ereignisse doppeln sich mit denen von FarmPulse. |
| `FS25_UsedPlus` | Eigene Kredite, Bonität und Leasing. Seine Kredite erhöhen den Vanilla-Kredit, den FarmPulse als Verbindlichkeit mitzählt. |
| `FS25_EnhancedLoanSystem` | Ersetzt den Vanilla-Kredit. |
| `FS25_BetterContracts` | Ändert Aufträge und Feldpreise. |

Tipp: Nutze die Kredit- bzw. Preisfunktionen nur eines Mods, um doppelte Effekte zu vermeiden.

## Die KI antwortet nicht (oder klingt nach Vorlage)

FarmPulse funktioniert immer – auch ohne KI. Kann der KI-Anbieter nicht antworten (kein Schlüssel, falscher
Schlüssel, keine Internetverbindung, Kontingent aufgebraucht, Ollama nicht gestartet), verwendet FarmPulse
automatisch **vorformulierte Texte** mit denselben Zahlen. Du verpasst also keine Entscheidung, nur die
persönliche Note.

- Unter **Einstellungen** prüfen, welcher Anbieter aktiv ist und ob ein Schlüssel hinterlegt ist.
- Schlüssel neu eintragen und speichern (das Feld bleibt danach leer – das ist Absicht).
- **Google Gemini:** Google benennt die kostenlosen Modelle gelegentlich um. Bei Fehlern im Feld *Modell* ein
  aktuelles Modell eintragen (Liste im Google AI Studio).
- **Ollama:** läuft `ollama serve`? Ist das Modell installiert (`ollama pull llama3.1`)? Stimmt die Adresse?
- Die Konsole des Backends zeigt den Grund jedes KI-Fehlers.

Antworten kommen außerdem nicht sofort: Charaktere melden sich in Spielzeit. Ist das Spiel pausiert, vergeht keine
Spielzeit.

## Hinweis „Verarbeitungsschritt übersprungen“

Ein Teil der Spiellogik (z. B. die Dorfzeitung an einem Spieltag) ist dreimal hintereinander mit einem Fehler
abgebrochen. FarmPulse hat diesen einen Schritt übersprungen, damit Buchungen, Gehälter und die Spielzeit
weiterlaufen – der Rest des Tages ist normal verarbeitet. Der Hinweis nennt den Schritt und die Fehlermeldung; die
Einzelheiten stehen im Backend-Fenster (`Cycle step … failed`). Bitte als Fehler melden (siehe unten) und den Hinweis
mit *Verstanden* schließen.

## Oben steht „Offline“

Die Live-Verbindung zum Backend ist unterbrochen. Läuft das Backend-Fenster noch? Nach einem Neustart verbindet
sich die Oberfläche von selbst wieder (nach 1 bis 30 Sekunden); notfalls die Seite neu laden.

## Das Tablet erreicht FarmPulse nicht

- **Seite lädt nicht (Zeitüberschreitung):** Meist blockiert die **Windows-Firewall**. Beim ersten Start fragt Windows,
  ob Java im Netzwerk kommunizieren darf – dort **Private Netzwerke** erlauben. Nachträglich: *Windows-Sicherheit →
  Firewall- & Netzwerkschutz → Zugriff von Apps durch die Firewall zulassen* → *Java(TM) Platform SE binary* (bzw.
  *OpenJDK Platform binary*) für **Privat** anhaken. Außerdem muss das WLAN des PCs in Windows als **privates** Netzwerk
  eingestellt sein, nicht als öffentliches.
- **Tablet im Gast-WLAN:** Viele Router trennen das Gast-WLAN vom Heimnetz – das Tablet sieht den PC dann nicht. Das
  Tablet ins normale WLAN bringen (dasselbe Netz wie der Spiele-PC).
- **Meldung „Zugriff nur vom Spiele-PC …“ (403):** Der Schalter **Im Heimnetz erreichbar** ist aus, oder das Gerät
  kommt nicht aus dem Heimnetz (z. B. über mobile Daten). Am Spiele-PC unter *Einstellungen → Tablet & Netzwerk*
  einschalten und das Tablet ins WLAN bringen.
- **Meldung „Unbekannte Adresse …“ (403):** FarmPulse wurde über einen Namen aufgerufen, den es nicht kennt, z. B.
  `mein-pc.fritz.box`. Das schützt vor fremden Webseiten (DNS-Rebinding). Die IP-Adresse aus der Einstellungskarte
  oder den Rechnernamen verwenden – oder den Namen in `application-local.yml` unter `rpsim.web.allowed-hosts`
  eintragen und das Backend neu starten.
- **KI-Einstellungen lassen sich am Tablet nicht ändern:** Absicht. Anbieter, Schlüssel und Adresse ändert nur der
  Spiele-PC, denn der Schlüssel wird an die eingestellte Adresse geschickt.
- **Adresse passt nicht mehr:** Der Router hat dem PC eine neue Adresse gegeben. Die aktuelle steht in der
  Einstellungskarte und im Backend-Fenster.
- **Immer wieder nach der PIN gefragt:** Die PIN wurde geändert oder der Heimnetz-Zugriff aus- und wieder eingeschaltet
  – dann meldet sich jedes Tablet neu an. Nach fünf falschen PINs fünf Minuten warten.

## Datensicherung zurückspielen

Bei jedem Start sichert FarmPulse die Datenbank – noch bevor sie nach einem Update umgestellt wird. Die Sicherungen liegen in
`<Benutzerordner>\.rpsim\backups\`; die neuesten **5** bleiben erhalten, ältere werden gelöscht. Der Name nennt den
Zeitpunkt des Starts: `rpsim-20261007-181500-123.zip` = 07.10.2026, 18:15:00 Uhr.

So holst du einen älteren Stand zurück, etwa nach einem fehlgeschlagenen Update:

1. Das Backend beenden (Fenster schließen).
2. Im Ordner `<Benutzerordner>\.rpsim\` die Datei `rpsim.mv.db` umbenennen, z. B. in `rpsim.mv.db.alt` (so bleibt der
   aktuelle Stand erhalten, falls du zurückwechseln willst).
3. Die gewünschte Sicherung öffnen (Doppelklick auf die ZIP-Datei) und die Datei `rpsim.mv.db` daraus nach
   `<Benutzerordner>\.rpsim\` kopieren.
4. Das Backend starten. Die Datei `db.properties` bleibt, wie sie ist: Das Passwort der Datenbank ändert sich nicht.

Alles, was FarmPulse nach dem Zeitpunkt der Sicherung gespeichert hat, ist im zurückgespielten Stand nicht enthalten.
Jeder Start legt eine neue Sicherung an und löscht dabei die älteste – kopiere eine Sicherung, die du behalten willst,
vorher an einen anderen Ort.

## Neu anfangen

- **Neuer Spielstand:** einfach das Onboarding erneut durchlaufen und mit dem neuen FS25-Spielstand verknüpfen.
- **Alles zurücksetzen:** Backend beenden und den Ordner `<Benutzerordner>\.rpsim\` löschen (Datenbank, ihr
  Passwort `db.properties` und die Sicherungen in `backups\` zusammen). Achtung: Damit sind
  alle Charaktere, Mails und Kredite aller Spielstände weg; das Spielgeld im FS25-Spielstand bleibt, wie es ist.

## Fehler melden

Bitte ein Issue mit der Vorlage *Bug report* anlegen: was du getan hast, was passiert ist, Auszug aus der
Backend-Konsole und dem `log.txt` des Spiels. **Niemals API-Schlüssel mitschicken.**
