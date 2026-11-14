# Android-Apps

Sammlung kleiner Android-Apps. Jede App liegt in einem eigenen Ordner und hat
einen eigenen Workflow unter `.github/workflows/<app>.yml`, der die APK baut und
als GitHub-Release (`<app>-latest`) veröffentlicht.

| App | Ordner | Download |
| --- | --- | --- |
| Puls-Overlay – Garmin-Herzfrequenz als schwebendes Fenster mit Zonenbalken | `puls-overlay/` | [Puls-Overlay.apk](https://github.com/Hagen381/android-apps/releases/download/puls-overlay-latest/Puls-Overlay.apk) |
| Tubee aus – Icon, das Tubee per „Stoppen erzwingen“ beendet; dazu Icon „Tubee Schlaf“ (Schlaftimer mit sanftem Ausblenden) | `tubee-stopper/` | [Tubee-Stopper.apk](https://github.com/Hagen381/android-apps/releases/download/tubee-stopper-latest/Tubee-Stopper.apk) |
| Regenradar Samui – Widget mit dem TMD-Radarfilm (Surat Thani), Ausschnitt Koh Samui, aktualisiert alle 15 Min. | `regenradar-samui/` | [Regenradar-Samui.apk](https://github.com/Hagen381/android-apps/releases/download/regenradar-samui-latest/Regenradar-Samui.apk) |
| Garmin → Tabelle – liest Garmin-Daten aus Health Connect und schreibt sie in eine Google-Tabelle (für Claude-Coworker) | `garmin-export/` | [Garmin-Export.apk](https://github.com/Hagen381/android-apps/releases/download/garmin-export-latest/Garmin-Export.apk) |

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
5. Schlaftimer: Icon „Tubee Schlaf“ → Dauer wählen. Am Ende wird es in ~6 s leiser, die Wiedergabe hält an
   (Medientaste Pause + Audiofokus), die Lautstärke wird zurückgesetzt. Verlängern/Abbrechen in der Benachrichtigung.

## Garmin → Tabelle – Einrichtung
1. Neue Google-Tabelle anlegen, Erweiterungen › Apps Script › Inhalt von `garmin-export/apps-script/Code.gs` einfügen.
2. Bereitstellen › Neue Bereitstellung › Web-App › Ausführen als „Ich“, Zugriff „Jeder“ › Autorisieren › URL kopieren.
3. App öffnen, URL eintragen, Health-Connect-Zugriff (inkl. Hintergrund) erlauben, „Jetzt übertragen“.
4. Danach überträgt die App alle 6 Stunden die letzten 7 Tage. Reiter: Trainings, Tage, Schlaf, Protokoll.
