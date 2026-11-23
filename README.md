# Fox Testpilot 🦊✈️

Behält die Testpiloten von [MediaMarkt](https://www.mediamarkt.de/de/campaign/testpiloten) und [Saturn](https://www.saturn.de/de/campaign/testpiloten) für dich im Blick und meldet sich, wenn:

- eine neue Runde angekündigt wird (📅 mit Bewerbungszeitraum)
- die Bewerbungsphase startet (🚀, auf Wunsch mit lautem Alarm)
- neue Testprodukte auftauchen (🆕)
- der letzte Bewerbungstag ist (⏰, mit Stand „x von y beworben“)

In der App siehst du alle Testprodukte und hakst ab, wofür du dich schon beworben hast.

**Automatisch ausfüllen:** Unter ⚙️ einmal Adresse und Bewerbungstext speichern (`{produkt}` wird durch den Produktnamen ersetzt) sowie E-Mail und Passwort für myMediaMarkt und mySaturn (verschlüsselt auf dem Gerät, Android Keystore) – die App meldet dich im App-Browser automatisch an. „Bewerben“ öffnet das Produkt im App-Browser, das Teilnahmeformular wird mit deinen Daten gefüllt (`www/autofill.js`, auch in eingebetteten Formularen). Häkchen setzen und Absenden machst du selbst.

- Prüft im Hintergrund alle 15 Min. bis 3 Std. (einstellbar, Android WorkManager)
- Tipp: In den Android-Einstellungen für Fox Testpilot „Akku: Nicht eingeschränkt“ wählen

**Konto anlegen:** Unter 👥 Personen → „🆕 Konto anlegen“ öffnet die Registrierung von MediaMarkt (gilt auch für Saturn) und trägt Name, Adresse, Geburtsdatum, E-Mail und das gespeicherte Passwort ein (das Passwort nur auf mediamarkt.de/saturn.de). Unter „📝 Andere Seite“ lässt sich jedes Anmelde- oder Registrierformular mit den Daten der Person füllen (ohne Passwort). Häkchen, Bestätigungs-Code und Absenden machst du immer selbst.

**Mehrere Personen im Haushalt:** Mit ＋ bekommt jede Person ein eigenes Profil (eigene Daten, eigene Konten, eigene „Beworben“-Liste). Beim Wechsel wird der App-Browser abgemeldet.

**Weitere Funktionen:** Auto-Modus (ausgewählte Produkte nacheinander ausfüllen und absenden, auch für alle Personen), eigene Bewerbungstexte je Stichwort, „Meine Bewerbungen“ mit Ergebnis und Erinnerung an die Bewertungs-Frist, Erinnerung 10 Minuten vor dem Start, Kalendereintrag, Nachtruhe, Startbildschirm-Widget, Sicherung als Text (ohne Passwörter) und eine Diagnose („Seiten testen“ / langes Drücken auf „Ausfüllen“ im App-Browser).

**Probelauf:** Unter Einstellungen → Auto-Modus (standardmäßig an): Der Auto-Modus füllt alles aus und markiert den Absende-Knopf rot, schickt aber nichts ab, und zeigt am Ende einen Bericht.

**Eigene Seiten:** Weitere Produkttester-Seiten mit Stichwörtern überwachen – Meldung, sobald ein Stichwort neu auf der Seite steht.

**Discord:** Unter Einstellungen → Discord eine Webhook-Adresse einfügen; alle Meldungen kommen dann zusätzlich in den Kanal (auf Wunsch mit @everyone beim Start). Die Adresse bleibt nur auf dem Handy.

## Windows-Version
`windows/` – Python-Programm (Playwright mit Edge/Chrome, Fenster mit tkinter), als `FoxTestpilot.exe` per GitHub Actions gebaut.
Überwachung, Windows- und Discord-Meldungen, Personen mit eigenem Browser-Profil, Ausfüll-Hilfe, Auto-Modus mit Probelauf, eigene Seiten.
Die Sicherung aus der Android-App lässt sich einspielen (ohne Passwörter).

- Neueste EXE: unter Releases die neueste „Fox Testpilot Windows …“
- Läuft nach dem Schließen unten rechts neben der Uhr weiter; das Programm meldet neue Versionen selbst.

## APK und Updates
Bei jedem Upload auf `main` baut GitHub Actions die App und veröffentlicht sie als **Release**.

- Neueste APK: `https://github.com/Djlover007/Fox-Testpilot/releases/latest/download/Fox-Testpilot.apk`
- Die App zeigt ein Banner, wenn eine neuere Version da ist.
- Alle Versionen sind mit `fox-testpilot.keystore` signiert, Updates lassen sich direkt drüber installieren.

## Technik
Capacitor 7 + eigener Android-Code in `native/`: Die Seite wird in einem unsichtbaren Browser (WebView) geladen und nach unten gescrollt, damit alle Produktkacheln erscheinen (`WebLoader.java`). `PilotChecker.java` liest Bewerbungszeitraum und Produkte aus und verschickt die Benachrichtigungen. Die Oberfläche steckt in `www/index.html`.
