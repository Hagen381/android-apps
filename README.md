# Android-Apps

Sammlung kleiner Android-Apps. Jede App liegt in einem eigenen Ordner und hat
einen eigenen Workflow unter `.github/workflows/<app>.yml`, der die APK baut und
als GitHub-Release (`<app>-latest`) veröffentlicht.

| App | Ordner | Download |
| --- | --- | --- |
| Puls-Overlay – Garmin-Herzfrequenz als schwebendes Fenster mit Zonenbalken | `puls-overlay/` | [Puls-Overlay.apk](https://github.com/Hagen381/android-apps/releases/download/puls-overlay-latest/Puls-Overlay.apk) |
| Tubee aus – Icon, das Tubee (oder eine andere gewählte App) per „Stoppen erzwingen“ beendet | `tubee-stopper/` | [Tubee-Stopper.apk](https://github.com/Hagen381/android-apps/releases/download/tubee-stopper-latest/Tubee-Stopper.apk) |

## Puls-Overlay – Einrichtung
1. Garmin-Uhr: Einstellungen › Gesundheit & Wellness › Herzfrequenz am Handgelenk ›
   „Während Aktivität übertragen“ einschalten.
2. APK installieren, in der App Zonen eintragen, Berechtigungen und „Über anderen Apps
   anzeigen“ erlauben, „Nach Uhr suchen“ und die Uhr wählen.
3. Aktivität auf der Uhr starten – das Fenster zeigt Puls, Zone und Zonenbalken.

## Tubee aus – Einrichtung
1. APK installieren und öffnen (beim ersten Tipp öffnet sich die Einrichtung).
2. Tubee als zu beendende App wählen.
3. Bedienungshilfe „Tubee aus“ einschalten (ausgegraut? App-Info › ⋮ › „Eingeschränkte Einstellungen zulassen“).
4. Icon unter Tubee legen – ein Tipp öffnet kurz die App-Info, tippt „Stoppen erzwingen“ + „OK“ und schließt sie wieder.
