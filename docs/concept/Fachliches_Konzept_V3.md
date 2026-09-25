# FS25 Mod-Konzept: KI-Rollenspiel-Simulation

@KenoSep 24, 2026 ·&#32;

**V2 – Verhandlungssystem ergänzt:** neues fünftes Kernmodul für Kauf/Verkauf handelbarer Güter zwischen Spieler und NPCs, in Version 1 ausschließlich für Ackerland (Versteigerung, Direktverhandlung, Verkauf eigener Felder – bewusst ohne Verpachtung).

**V3 – Silo-Warenbestand & Marktpreis-Übersicht ergänzt:** neuer Datenexport für Silobestände (nur klassische Silos) und laufende Verkaufspreise inkl. Preisverlauf-Chart in der Angular-Oberfläche. Wirkt zusätzlich an zwei bestehenden Stellen mechanisch: als neuer Vermögenswert in der Bonitätsprüfung und als Gewichtungsfaktor bei der Zielauswahl von Preis-Events.

## Ziel & Kernidee

Das Singleplayer-Erlebnis in Farming Simulator 25 wird auf Dauer eintönig, weil dem Hof echte Ansprechpartner fehlen. Dieser Mod ergänzt das Spiel um eine KI-gestützte Rollenspiel-Simulation: generierte Charaktere (Bank, Nachbarn, Dorfbewohner, Mitarbeiter, ...), mit denen der Spieler nicht in Echtzeit, sondern über Mails und Anrufe im begleitenden Tool interagiert.

Fünf Kernmodule tragen das Konzept:

1. **Charaktersystem** – generierte Rollen mit eigener Persönlichkeit, Gedächtnis und Beziehung zum Spieler
2. **Bank-/Kreditsystem** – löst das alte Vanilla-Kreditsystem ab, Bonitätsprüfung anhand von Live-Daten
3. **Event-/Preissystem** – dynamische Marktereignisse, kommuniziert durch Charaktere
4. **Mitarbeitersystem** – Stellenmarkt, Gehälter, Bedürfnisse und Skills
5. **Verhandlungssystem** – Kauf/Verkauf handelbarer Güter (in V1: Ackerland) zwischen Spieler und NPCs, formelbasierte Preisfindung mit KI-Verhandlungsnarrative

Alle fünf Module teilen dasselbe Architekturprinzip (siehe nächster Abschnitt).

## Grundprinzip: Zwei-Ebenen-Modell

Jeder Charakter und jedes Wirtschaftssystem im Mod trennt strikt zwei Ebenen:

- **Fakten-Ebene (deterministisch)**: alle Zahlen, die Spielgeld oder Skills bewegen – Bonitäts-Scores, Preisänderungen, Gehälter, Skill-Werte. Diese werden von fester Spiellogik/Formeln berechnet, niemals von der KI frei erfunden.
- **Persönlichkeits-Ebene (KI-generiert)**: Name, Charakterzüge, Sprachstil, Tonalität, Begründungen, Erzählung. Die KI bekommt die fertigen Zahlen als Vorgabe und formuliert nur, *wie* sie kommuniziert werden.

**Warum diese Trennung wichtig ist:** Ein LLM lässt sich durch geschickte Formulierungen manipulieren (Prompt Injection) und kann auch ohne böse Absicht inkonsistente oder wirtschaftlich unsinnige Entscheidungen treffen. Da dieses System direkt Spielgeld erzeugt und bewegt (Kredite, Gehälter, Marktpreise), muss die Kernlogik manipulationssicher bleiben. Die KI ist Erzähler und Schauspieler, nicht Buchhalter.

Diese Trennung zieht sich durch das gesamte Konzept: Vertrauenswerte, Bonitätsprüfung, Preisänderungen und Mitarbeiterzufriedenheit werden alle über Formeln/Engines bestimmt; die KI beschreibt und personalisiert nur das Ergebnis.

## Kommunikationskanäle: Mails & Anrufe

**Mails** sind asynchron und ohne Zeitdruck – der Spieler beantwortet sie, wann er will, passend zum gemächlichen Grundtempo von Farming Simulator.

**Anrufe** funktionieren anders und heben sich damit bewusst von Mails ab:

- Ein eingehender Anruf kann **angenommen oder abgelehnt** werden
- **Ablehnen hat spürbare Konsequenzen** – z. B. Vertrauensverlust beim jeweiligen Charakter oder die Notwendigkeit, selbst zurückzurufen, um das Anliegen doch noch zu klären
- **Annehmen öffnet ein weiches Zeitfenster** – reine Stimmungsmache für das "Anruf-Feeling", ohne harte Strafe bei etwas längerem Nachdenken

Damit bleibt der Zeitdruck spürbar, ohne dass ein Spieler durch eine kurze Ablenkung im echten Leben hart bestraft wird.

## Vorgeschichte & Onboarding-Ablauf

**Ablauf beim Erstellen eines neuen Spielstands:**

1. **Vorgeschichte-Bausteine wählen**: Ursprung des Hofs (geerbt / gekauft-Neustart / Rückkehr in die Heimat / Pacht übernommen), Verhältnis zum Dorf (unbekannt / gut vernetzt / belastet), finanzielle Ausgangslage (schuldenfrei / mit Altlasten) – ergänzt um ein optionales Freitextfeld für persönliche Details
2. **Mitarbeiter-Ausgangslage angeben**: Anzahl und Rollen bereits vorhandener Mitarbeiter
3. **Generierung des Startpakets**: Pflichtrollen (u.a. Bank), eine passende Zahl dynamischer Charaktere sowie die angegebenen Mitarbeiter werden generiert – jeweils mit Fakten-Akte und Persönlichkeits-Layer, plus 1–2 Story-Hooks aus der Vorgeschichte
4. **Vorschau der Startbesetzung**: Name, Rolle und Kurzbeschreibung jedes generierten Charakters, mit **Reroll-Option** (einzeln oder gesamt), bevor der Spielstand endgültig losgeht
5. **Bestätigen & Verknüpfen** mit dem neuen FS-Spielstand

**Nach dem Start:** Eine kurze Begrüßungssequenz (z. B. Willkommensmail der Bank) führt niedrigschwellig ins Mail-/Anruf-System ein. Die Story-Hooks aus der Vorgeschichte treffen **gestaffelt über die ersten Spielwochen** ein, nicht alle sofort.

**Wichtiger Guardrail:** Das Freitextfeld beeinflusst ausschließlich die KI-Charaktergenerierung (Stimmung, Details) – es fließt **nie** direkt in deterministische Werte (Startgeld, Bonität o. ä.) ein. Eine leichte Inhaltsprüfung filtert Missbrauchsversuche. Die strukturierten Bausteine sind davon ausgenommen: sie dürfen echte Startwerte setzen (z. B. reduziert "mit Altlasten" das Startkapital oder hinterlegt einen Alt-Kredit) – kontrollierte Auswahlmöglichkeiten sind manipulationssicher, freier Text nicht.

**Edge Case:** Ein leeres oder minimales Freitextfeld führt zu einer stimmigen Standard-Startbesetzung statt zu blockieren.

**Entscheidung:** Vorgeschichte-Profile werden **nicht** als wiederverwendbare Vorlagen gespeichert – jeder neue Spielstand beginnt mit einer frischen Eingabe.

## Charaktersystem

**Pflichtrollen vs. dynamische Charaktere**

- **Pflichtrollen** (nie unbesetzt, z. B. Bank, evtl. Genossenschaft/Amt): bei Abwesenheit (Urlaub, Krankheit) entweder verzögerte Antwort mit Abwesenheitsnotiz oder ein Vertretungs-Charakter
- **Dynamische Charaktere** (andere Bauern, Dorfbewohner, Lieferanten): können neu hinzukommen (Zuzug) oder wegfallen (Wegzug, Ruhestand) – **Rotation bewusst selten gehalten**: maximal 1–2 Wechsel pro Spieljahr, damit das Dorf vertraut statt beliebig wirkt

**Bei einem Rollenwechsel** (z. B. neuer Bankberater): Die Fakten-Akte (Kreditstatus, Zahlungshistorie) bleibt bestehen, da es sich um Firmenwissen handelt. Die persönliche Beziehungsgeschichte startet dagegen neu – der Nachfolger kennt höchstens die Akte, nicht die gemeinsame Vergangenheit.

**Charakter-Datenmodell** (pro Charakter):

- Fakten-Ebene: Rolle, Status (aktiv/Urlaub/ausgeschieden), harte Daten je nach Rolle (Kredit, Gehalt, Skills, ggf. besessene Feldflächen), **Vertrauenswert**
- Persönlichkeits-Ebene: Name, 3–5 Charakterzüge, Sprachstil, Hintergrundgeschichte, Beziehungen zu anderen Charakteren im Dorf

**Vertrauenswert**: steigt/fällt durch klar definierte, geloggte Ereignisse (pünktliche Zahlungen, gehaltene/gebrochene Zusagen) – nicht durch freie KI-Interpretation eines Gesprächs. Fließt als **gedeckelter Faktor** in die Bonitätsformel (siehe Bank-/Kreditsystem) und in die Preisfindung des Verhandlungssystems ein, kann also bei Grenzfällen den Ausschlag geben, aber nie eine wirtschaftlich unhaltbare Anfrage allein durchwinken.

**Gedächtnis über Zeit**: Statt jede Nachricht wortwörtlich mitzuschleppen, wird die Interaktionshistorie regelmäßig zu Kernfakten verdichtet ("Kredit im März abgelehnt, Grund: zu wenig Eigenkapital"), damit Charaktere über Monate konsistent bleiben.

## Bank-/Kreditsystem

**Datenbasis der Bonitätsprüfung** (Live-Daten aus dem Spielstand):

- Eigenkapital / Vermögenswerte (Maschinen, Gebäude, Flächen, Tierbestand, Warenbestand im Silo)
- Liquidität (Kontostand, kurzfristige Reserven)
- Bestehende Verbindlichkeiten – **inklusive des ignorierten Vanilla-Kredits als Altlast**, damit keine Doppelfinanzierung über zwei parallele Systeme möglich ist
- Cashflow/regelmäßige Einnahmen
- Betriebsgröße im Verhältnis zur beantragten Summe
- Zahlungshistorie im neuen System
- **Vertrauenswert** (gedeckelter Faktor, siehe Charaktersystem)

**Ergebnis-Stufen** (statt nur Ja/Nein):

1. **Voll genehmigt** – beantragte Summe, marktüblicher Zins
2. **Gegenangebot** – z. B. geringere Summe, kürzere Laufzeit oder höherer Zins
3. **Abgelehnt** – mit grober, nachvollziehbarer Begründung (keine exakte Formel-Offenlegung, um den Rollenspiel-Charakter zu erhalten)

**Ablauf eines Antrags:**

1. Spieler stellt Antrag (Betrag, Zweck, Laufzeit)
2. Formel berechnet den Bonitäts-Score im Hintergrund
3. **Künstliche Bearbeitungszeit von 1–2 Spieltagen** (Spannung, Realismus)
4. KI formuliert die Antwort im Charakter des Bankberaters – Zahlen liefert die Formel
5. Bei Genehmigung: Gutschrift + automatischer monatlicher Abzug bis zur Tilgung

**Zahlungsausfall-Eskalation:** Mahnung → Strafzins/Verzugsgebühr → spürbarer Vertrauensverlust → bei wiederholtem Ausfall sofortige Fälligstellung der Restschuld oder Verweigerung künftiger Kredite. Eine Bitte um **Stundung** (Ratenpause) ist per Mail möglich und wird ebenfalls formelbasiert geprüft.

**Bewusst nicht in Version 1:** Sicherheiten/Pfand als Voraussetzung für große Kredite – als spätere Erweiterung vorgemerkt.

## Event-/Preissystem

**Grundprinzip:** Eine deterministische Event-Engine bestimmt Ereignistyp, betroffenes Produkt, betroffenen Verkaufsort, Stärke der Preisänderung (innerhalb fester Grenzen) sowie Dauer und Abklingverlauf. Die KI erhält dieses fertige Paket und erzeugt nur die Erzählung – welcher Charakter meldet es, mit welcher Begründung, in welchem Ton.

**Event-Typen (Beispielkatalog):**

- Nachfrage-Spitze / -Einbruch bei einem Produkt an einem Verkaufsort
- Ernteausfall/-Überschuss in der Region
- Befristetes Sonderangebot / Ausschreibung eines Abnehmers (Fixpreis-Kontrakt)
- Subventions-/Förderankündigung
- Gerücht ohne garantierte Genauigkeit

**Regionalität:** Events wirken sich auf einzelne Verkaufsorte aus, nicht pauschal auf alle gleichzeitig – erhält die taktische Tiefe der Verkaufsort-Wahl.

**Charakterbindung:** Jedes Event kommt über einen Charakter herein (Landhändler, Nachbar, Genossenschaft) statt als anonyme Systemmeldung.

**Timing:** Events treten **überwiegend überraschend** auf, nur selten mit Vorwarnung. **Unzuverlässige Gerüchte** sind Teil des Systems – manche Vorab-Infos stellen sich als falsch heraus, was ein Risiko-/Spannungselement schafft.

**Spielerreaktion:** Der Spieler kann aktiv auf Events reagieren, z. B. Sonderkontrakte mit einem Abnehmer aushandeln, statt nur passiv zu beobachten.

**Beispiel-Ablauf:** Engine wählt "Nachfrage-Spitze, Weizen, Mühle Nord, +18 % über 6 Tage, danach abklingend über 4 Tage" → KI schreibt passende Mail vom zuständigen Charakter mit plausibler (frei erfundener) Begründung → Tool passt den Preis an der Verkaufsstelle live an → Spieler entscheidet, wann/ob er verkauft.

**Silo-Warenbestand & Marktpreis-Übersicht:** Der Mod exportiert laufend den Inhalt der Farm-Silos – welche Frucht in welcher Menge eingelagert ist. **Bewusst nur klassische Silos**, nicht Fahrsilo/Bunkersilo oder Hallen. Zusammen mit den laufend erfassten Verkaufspreisen je Verkaufsstelle entsteht daraus eine Marktübersicht in der Angular-Oberfläche: Der Spieler sieht auf einen Blick, was er eingelagert hat und was es aktuell wert ist, ohne dafür ins Spiel wechseln zu müssen – inklusive **Preisverlauf** über die Zeit als Chart, da die Werte ohnehin fortlaufend erfasst werden.

Der Warenbestand wirkt dabei nicht nur informativ, sondern an zwei Stellen mechanisch:

- **Bonitätsprüfung:** Der Wert des eingelagerten Warenbestands (Menge × aktueller Preis) fließt als zusätzlicher Vermögenswert in die Eigenkapitalberechnung ein (siehe Bank-/Kreditsystem) – ein volles Silo verbessert die Bonität genau wie ein wertvoller Maschinenpark.
- **Zielauswahl von Preis-Events:** Die Event-Engine gewichtet bevorzugt Früchte, die der Spieler tatsächlich einlagert – ein Nachfrage-Spitze-Event für eine Frucht, von der nichts im Silo liegt, wirkt sich kaum aus und ist deshalb seltener das Ziel als eine Frucht, die der Betrieb wirklich produziert und lagert.

## Mitarbeitersystem

Mitarbeiter folgen demselben Zwei-Ebenen-Modell wie andere Charaktere – Fakten-Akte (Skills, Gehalt, Vertrag, Bedürfniswerte) plus Persönlichkeits-Layer.

**Stellenmarkt & Einstellung:**

- Beim Spielstart: Anzahl + Rollen der Start-Mitarbeiter (Teil des Onboarding-Startpakets)
- Im laufenden Spiel: Spieler schaltet eine Stelle aus, die Engine generiert einen Bewerberpool mit deterministischen Skill-Werten und passenden Gehaltsvorstellungen, die KI schreibt die Bewerbungen im jeweiligen Charakter
- **Simuliertes Vorstellungsgespräch per Mail oder Anruf** vor der Einstellung – der Spieler kann Fragen stellen und einen ersten Eindruck der Persönlichkeit gewinnen

**Rollenspezifische Skills:** z. B. Maschinenführer (Präzision/Effizienz), Mechaniker (Reparaturgeschwindigkeit/-qualität), Tierpfleger (Tierwohl-Einfluss), Bürokraft (Verwaltungsentlastung, ggf. Bearbeitungszeit bei Bank-/Event-Interaktionen leicht verkürzend). Das Mapping auf konkrete FS-Arbeiter-Parameter ist Teil der technischen Umsetzung und hier bewusst offengelassen.

**Gehalt:** monatliche automatische Abbuchung, festgelegt bei Einstellung.

**Bedürfniskategorien** (sinken natürlich über Zeit, steuerbar durch Spieleraktionen):

- Fairness der Bezahlung → Gehaltserhöhung
- Arbeitsbelastung → bewilligte freie Tage
- Wertschätzung → aktive Mails/Gespräche
- Arbeitsbedingungen → Zustand der Ausrüstung

Ein aggregierter Zufriedenheits-Wert übersetzt sich **deterministisch** in einen Skill-Malus/-Bonus.

**Konsequenzen bei niedriger Zufriedenheit:** Leistungsabfall (Skill-Malus) **und** Kündigungsrisiko sind gekoppelt, abhängig von Dauer und Schwere der Unzufriedenheit – mit Vorwarnung (Mail des Mitarbeiters) vor einer tatsächlichen Kündigung, damit der Spieler reagieren kann.

**Systemverzahnung:** Zahlungsverzug bei Gehältern (z. B. durch einen geplatzten Kredit oder Preiseinbruch) wirkt sich direkt auf die Zufriedenheit aus – Bank-, Event- und Mitarbeitersystem sind bewusst nicht isoliert.

## Verhandlungssystem

**Grundidee:** Fünftes Kernmodul nach demselben Zwei-Ebenen-Prinzip wie Bank- und Event-System, zugeschnitten auf den Kauf und Verkauf handelbarer Güter zwischen Spieler und NPCs. Die Engine ist bewusst generisch für „handelbare Güter" ausgelegt, **in Version 1 mit genau einem Gütertyp: Ackerland.** Anders als das Event-/Preissystem, das laufende Verkaufspreise für Erzeugnisse verändert, geht es hier um den einmaligen Erwerb bzw. die Abgabe eines Vermögenswerts.

**Zwei Wege zu einer Feldverhandlung:**

1. **Versteigerung (system-initiiert):** In unregelmäßigen Abständen kommt ein Feld zur Versteigerung – ein noch unbewirtschaftetes Feld oder eines im Besitz eines NPCs, der verkaufsbereit ist. Ein Charakter (Landhändler, Genossenschaft) kündigt sie per Mail an, mehrere Dorfbewohner bieten mit, der Spieler kann sich mit einem eigenen Gebot beteiligen.
2. **Direktverhandlung (spieler-initiiert):** Der Spieler spricht einen dynamischen Charakter, der ein Feld besitzt, gezielt an (über „Nachricht verfassen", siehe Rollenspiel-Vertiefung) und bittet um den Verkauf – unabhängig von einer laufenden Versteigerung.

**Ablauf einer Verhandlung** (beide Varianten):

1. Spieler gibt ein Gebot ab – **über ein Formular**, nicht als frei getippte Preisnennung, da hier echtes Spielgeld entschieden wird (siehe Zwei-Ebenen-Prinzip)
2. Formel prüft das Gebot gegen den (dem Spieler nicht offengelegten) Mindestpreis des Verkäufers
3. Ergebnis: **Angenommen**, **Gegenangebot** oder **Abgelehnt**
4. Bei Gegenangebot kann der Spieler nachbessern – **maximal drei Runden**, danach endet die Verhandlung endgültig
5. KI formuliert die Antwort im Charakter des Verkäufers bzw. Auktionators – die Zahl liefert ausschließlich die Formel
6. Bei Einigung: Feld wechselt den Besitzer, die Summe wird sofort vom Konto abgezogen bzw. gutgeschrieben

**Preisfindung (deterministisch):** Grundpreis des Feldes, ein Charakterzug des NPCs (verhandlungsbereit/stur) sowie der **Vertrauenswert** zum jeweiligen Charakter als gedeckelter Bonus/Malus – eine bessere Beziehung verbessert die Konditionen, kann aber, wie beim Kredit, nie eine wirtschaftlich absurde Summe erzwingen. Bei der Versteigerungs-Variante zählen zusätzlich die – ebenfalls formelbasierten statt KI-erfundenen – Mitgebote der übrigen NPCs.

**Verkauf eigener Felder:** Der Spieler kann umgekehrt eines seiner eigenen Felder zum Verkauf anbieten und einen Wunschpreis nennen. Die Engine ermittelt, welche dynamischen Charaktere grundsätzlich Interesse und Kapital haben (0 bis mehrere), diese melden sich mit einem Anfangsgebot – die weitere Verhandlung läuft nach demselben Schema wie beim Kauf, nur mit vertauschten Rollen. **Bewusst nicht in Version 1: Verpachtung eigener Felder** – es geht ausschließlich um echten Verkauf mit Besitzerwechsel, keine laufende Pachteinnahme.

**Ein Feld, eine Verhandlung:** Ein Feld, das gerade Teil einer laufenden Versteigerung oder Direktverhandlung ist, kann nicht parallel über einen zweiten Vorgang angeboten werden.

**Beispiel-Ablauf:** Genossenschaft kündigt per Mail die Versteigerung eines 6-Hektar-Felds am Dorfrand an → Spieler und zwei NPCs bieten mit → Formel ermittelt nach Rundenlimit den Höchstbietenden → KI schreibt die Zu-/Absage im Charakter der Genossenschaft → bei Zuschlag wird der Betrag sofort vom Konto abgebucht, das Feld erscheint im Spielstand als Eigentum des Spielers.

**Ausblick:** Die Verhandlungs-Engine ist bewusst nicht ackerland-spezifisch benannt – spätere Erweiterungen auf weitere handelbare Güter (z. B. Gebrauchtmaschinen direkt von einem NPC) wären ohne Bruch mit dem Grundprinzip möglich, sind aber kein Ziel für Version 1.

## Rollenspiel-Vertiefung

Sechs Ergänzungen, die gezielt das Rollenspiel-Gefühl stärken statt nur die Wirtschaftssimulation – alle halten sich an dasselbe Zwei-Ebenen-Prinzip.

**Freie Antworten des Spielers**

Formulare bleiben nur dort, wo echte Zahlen entschieden werden (Kreditantrag, Stellenausschreibung, Verhandlungsgebot). Mails und Anrufe werden ansonsten frei beantwortbar. Ein deterministischer **Ton-Klassifikator** ordnet die Spielerantwort grob ein (freundlich/neutral/schroff) und fließt gedeckelt in den Vertrauenswert des jeweiligen Charakters ein – dieselbe Logik wie beim bestehenden Vertrauenssystem. Mechanische Wünsche per Freitext (z. B. "gib mir einen besseren Zins") lenkt die KI-Figur höflich auf den offiziellen Prozess zurück, statt sie direkt zu gewähren.

**Dorfweites Ansehen**

Ein separater **Dorf-Ansehen-Wert**, aggregiert aus den einzelnen Vertrauenswerten plus öffentlichen Aktionen (Dorf-Events, Spenden, öffentlich gewordene Zahlungsausfälle). Neue Charaktere starten mit einem Grundvertrauen, das anteilig – ebenfalls gedeckelt – vom Dorf-Ansehen beeinflusst wird: der Ruf eilt dem Spieler voraus. Angezeigt wird nur eine grobe Stufe ("gut angesehen" / "neutral" / "umstritten"), keine exakte Zahl, um die Immersion zu wahren.

**Proaktive Spielerinitiative**

Eine neue Funktion "Nachricht verfassen": der Spieler wählt selbst einen aktiven Charakter und schreibt frei, statt nur auf eingehende Mails zu reagieren. Gleiche Guardrails wie bei freien Antworten, plus ein sanftes Pacing-Limit, damit daraus kein Vertrauens-Farming durch Nachrichtenspam wird. Bei Charakteren mit eigenem Feldbesitz kann der Spieler darüber zusätzlich gezielt eine Direktverhandlung anstoßen (siehe Verhandlungssystem) statt nur frei zu schreiben.

**Tagebuch/Chronik**

Eine automatisch fortgeschriebene, chronologische Zusammenfassung der wichtigsten Ereignisse (Kredit-Entscheidungen, Feld-Käufe/-Verkäufe, Charakterwechsel, Meilensteine, besondere Momente). Der erste Eintrag ist die im Onboarding eingegebene Vorgeschichte. Der Spieler kann optional eigene, rein narrative Notizen ergänzen – ohne jede mechanische Wirkung.

**Dorfleben jenseits der Wirtschaft**

Zusätzliche, nicht-wirtschaftliche Nachrichtentypen: Glückwünsche (z. B. zu einer guten Ernte), Einladungen (Dorffest, saisonale Anlässe passend zu den FS25-Jahreszeiten), Klatsch ohne Marktbezug. Bewusst selten getaktet, ähnlich wie das Event-System, damit es nicht überlädt, sondern die Charaktere zwischen den "wichtigen" Nachrichten lebendig hält.

**Ton-/Genre-Leitplanken**

Ein zusätzlicher Onboarding-Baustein neben der Vorgeschichte: der Spieler wählt einen groben Ton-Rahmen (idyllisch-entspannt / realistisch-ausgewogen / hart-dramatisch). Das steuert, wie schonungslos die KI Konsequenzen erzählerisch darstellt, und leicht auch die Formel-Toleranzen (z. B. eine strengere Bank im harten Modus) – sorgt für einen konsistenten Ton über alle generierten Inhalte hinweg.

## Offene Punkte & nächste Schritte

**Noch zu klärende Fragen:**

**Getroffene Entscheidungen:**

- KI-Anbindung: eigener API-Key pro Spieler, optional lokale KI-Unterstützung; kein gebündeltes Backend/Abo
- Mehrspieler-Höfe: für Version 1 bewusst ausgeklammert, spätere Erweiterung möglich
- Content-Filter/Moderation: Vertrauen auf die Sicherheitsfilter des gewählten KI-Anbieters, keine zusätzliche eigene Prüfung
- Offline-/API-Ausfall: generische, vorgefertigte Fallback-Texte, das Spiel bleibt vollständig spielbar
- Sprachlokalisierung: Version 1 erscheint zunächst nur auf Deutsch, die Architektur wird aber von Anfang an so angelegt, dass weitere Sprachen später ergänzt werden können

**Einziger noch offener Punkt:**

- Genaue Formel-Gewichtungen und Balancing (Bonitäts-Score, Zufriedenheits-Formel, Event-Grenzen) – wird erst beim Playtesting nach der technischen Umsetzung festgelegt

**Vorschlag für die weitere Detailplanung:** Nach diesem Grundgerüst als Nächstes je Modul konkrete Formeln/Zahlenwerte entwerfen (Bonitäts-Score, Preis-Grenzen, Zufriedenheits-Gewichtung), danach die Datenstruktur der Charakter-Akte festlegen, bevor es an die technische Umsetzung geht.
