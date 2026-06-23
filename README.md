# Marstek B2500 Reader

Native Android-Testapp fuer den Marstek B2500 Akku per Bluetooth Low Energy.

## Was die App macht

- Scan nach BLE-Geraeten mit Namen `HM_B2500...`
- Verbindung zum Service `0000ff00-0000-1000-8000-00805f9b34fb`
- Zugriff auf Characteristic `0000ff02-0000-1000-8000-00805f9b34fb`
- Aktiviert Notifications
- Sendet die bekannten Kommandos `0x03` und `0x0F`
- Zeigt SOC, Ein-/Ausgangsleistung, Temperatur und Zellspannungswerte an
- Zeigt die Rohantworten als Hex an, damit der erste Test nachvollziehbar ist

## Bauen

1. Ordner in Android Studio oeffnen: `/Users/michael/Documents/Codex/Android app`
2. Gradle Sync abwarten.
3. Tablet per USB verbinden oder APK bauen.
4. App starten und Bluetooth-Berechtigungen erlauben.

Falls Android Studio eine neuere Android-Gradle-Plugin-Version vorschlaegt, kann sie uebernommen werden.

Dieses Projekt enthaelt bewusst keinen Gradle Wrapper, weil in der aktuellen Umgebung kein Android SDK/Gradle installiert war. Android Studio kann das Projekt trotzdem importieren und mit seiner eigenen Gradle-Installation synchronisieren.

## Hinweise zum Test

- Der Akku darf wahrscheinlich nicht bereits mit einer anderen App, einem ESP32-Display oder Gateway verbunden sein.
- Bluetooth muss am Tablet eingeschaltet sein.
- Auf Android 11 oder aelter kann Standortfreigabe fuer BLE-Scans notwendig sein.
- Beim ersten Test sind die Rohdaten wichtig. Wenn Werte leer bleiben, Screenshot oder Hex-Ausgabe notieren.

## Herkunft der BLE-Daten

Die BLE-UUIDs, Kommandos und Parser-Offsets wurden aus dem oeffentlichen ESP32-Projekt von SolarPixelWerk abgeleitet:

https://github.com/SolarPixelWerk/Displayerweiterung-f-r-OpenDTU-OnBattery-
