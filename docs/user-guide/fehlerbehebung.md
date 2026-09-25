# Fehlerbehebung

## Das Backend startet nicht

| Meldung / Symptom | Lösung |
| --- | --- |
| `java` wird nicht gefunden, oder `UnsupportedClassVersionError` | Java 21 installieren (`java -version` muss 21 oder neuer zeigen) |
| `Port 8080 was already in use` | anderes Programm auf Port 8080 beenden, oder in `application-local.yml` `server.port: 8090` setzen und dann <http://localhost:8090> öffnen |
| Fenster schließt sich sofort | `start.bat` aus einer Eingabeaufforderung starten, um die Meldung zu lesen |

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
  früheren Onboarding verknüpft, ist er bereits aktiv – öffne einfach das Dashboard.
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

## Oben steht „Offline“

Die Live-Verbindung zum Backend ist unterbrochen. Läuft das Backend-Fenster noch? Nach einem Neustart verbindet
sich die Oberfläche von selbst wieder (nach 1 bis 30 Sekunden); notfalls die Seite neu laden.

## Neu anfangen

- **Neuer Spielstand:** einfach das Onboarding erneut durchlaufen und mit dem neuen FS25-Spielstand verknüpfen.
- **Alles zurücksetzen:** Backend beenden und den Ordner `<Benutzerordner>\.rpsim\` löschen. Achtung: Damit sind
  alle Charaktere, Mails und Kredite aller Spielstände weg; das Spielgeld im FS25-Spielstand bleibt, wie es ist.

## Fehler melden

Bitte ein Issue mit der Vorlage *Bug report* anlegen: was du getan hast, was passiert ist, Auszug aus der
Backend-Konsole und dem `log.txt` des Spiels. **Niemals API-Schlüssel mitschicken.**
