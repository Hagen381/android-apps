# Android-Apps – Regeln für Claude

- Eine App = ein Ordner im Wurzelverzeichnis + ein Workflow `.github/workflows/<app>.yml`
  (Vorlage: `puls-overlay.yml`, `paths:`-Filter auf den App-Ordner, Release-Tag `<app>-latest`).
- In der Sandbox gibt es kein Android-SDK: nie lokal bauen, immer über GitHub Actions.
  Artifact-Downloads sind gesperrt → APK über den Release-Link holen und per SendUserFile
  in den Chat schicken (Hagen installiert direkt aus dem Chat).
- Kotlin, AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, minSdk 26, nur core-ktx,
  Oberfläche programmatisch, Texte auf Deutsch. Details im Skill „android-apps-bauen“.
- Neue App → Zeile in der Tabelle in `README.md` ergänzen.
- Signatur: Alle Apps signieren mit dem festen Schlüssel aus dem Repo-Secret `SIGNING_KEYSTORE_B64`
  (Alias `molloy`). Neue App: Signier-Block aus `puls-overlay/app/build.gradle.kts` und den Workflow-Schritt
  „Signierschlüssel bereitstellen“ übernehmen. Den Schlüssel NIE ins Repo legen (öffentlich!).
