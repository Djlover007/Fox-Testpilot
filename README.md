# Fox Testpilot 🦊✈️

Behält die [MediaMarkt Testpiloten](https://www.mediamarkt.de/de/campaign/testpiloten) für dich im Blick und meldet sich, wenn:

- eine neue Runde angekündigt wird (📅 mit Bewerbungszeitraum)
- die Bewerbungsphase startet (🚀, auf Wunsch mit lautem Alarm)
- neue Testprodukte auftauchen (🆕)
- der letzte Bewerbungstag ist (⏰, mit Stand „x von y beworben“)

In der App siehst du alle Testprodukte, kannst sie mit einem Tipp in Kiwi öffnen (dort bist du bei myMediaMarkt angemeldet) und abhaken, wofür du dich schon beworben hast. Bewerben musst du dich selbst – die App schickt nichts automatisch ab.

- Prüft im Hintergrund alle 15 Min. bis 3 Std. (einstellbar, Android WorkManager)
- Tipp: In den Android-Einstellungen für Fox Testpilot „Akku: Nicht eingeschränkt“ wählen

## APK und Updates
Bei jedem Upload auf `main` baut GitHub Actions die App und veröffentlicht sie als **Release**.

- Neueste APK: `https://github.com/Djlover007/Fox-Testpilot/releases/latest/download/Fox-Testpilot.apk`
- Die App zeigt ein Banner, wenn eine neuere Version da ist.
- Alle Versionen sind mit `fox-testpilot.keystore` signiert, Updates lassen sich direkt drüber installieren.

## Technik
Capacitor 7 + eigener Android-Code in `native/`: Die Seite wird in einem unsichtbaren Browser (WebView) geladen und nach unten gescrollt, damit alle Produktkacheln erscheinen (`WebLoader.java`). `PilotChecker.java` liest Bewerbungszeitraum und Produkte aus und verschickt die Benachrichtigungen. Die Oberfläche steckt in `www/index.html`.
