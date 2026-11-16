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

## APK und Updates
Bei jedem Upload auf `main` baut GitHub Actions die App und veröffentlicht sie als **Release**.

- Neueste APK: `https://github.com/Djlover007/Fox-Testpilot/releases/latest/download/Fox-Testpilot.apk`
- Die App zeigt ein Banner, wenn eine neuere Version da ist.
- Alle Versionen sind mit `fox-testpilot.keystore` signiert, Updates lassen sich direkt drüber installieren.

## Technik
Capacitor 7 + eigener Android-Code in `native/`: Die Seite wird in einem unsichtbaren Browser (WebView) geladen und nach unten gescrollt, damit alle Produktkacheln erscheinen (`WebLoader.java`). `PilotChecker.java` liest Bewerbungszeitraum und Produkte aus und verschickt die Benachrichtigungen. Die Oberfläche steckt in `www/index.html`.
