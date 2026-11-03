# Android-Apps

Sammlung kleiner Android-Apps. Jede App liegt in einem eigenen Ordner und hat
einen eigenen Workflow unter `.github/workflows/<app>.yml`, der die APK baut und
als GitHub-Release (`<app>-latest`) veröffentlicht.

| App | Ordner | Download |
| --- | --- | --- |
| Puls-Overlay – Garmin-Herzfrequenz als schwebendes Fenster mit Zonenbalken | `puls-overlay/` | [Puls-Overlay.apk](https://github.com/Hagen381/android-apps/releases/download/puls-overlay-latest/Puls-Overlay.apk) |

## Puls-Overlay – Einrichtung
1. Garmin-Uhr: Einstellungen › Gesundheit & Wellness › Herzfrequenz am Handgelenk ›
   „Während Aktivität übertragen“ einschalten.
2. APK installieren, in der App Zonen eintragen, Berechtigungen und „Über anderen Apps
   anzeigen“ erlauben, „Nach Uhr suchen“ und die Uhr wählen.
3. Aktivität auf der Uhr starten – das Fenster zeigt Puls, Zone und Zonenbalken.
