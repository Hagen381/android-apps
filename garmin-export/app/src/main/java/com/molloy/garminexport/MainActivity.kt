package com.molloy.garminexport

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Einrichtung: Adresse der Google-Tabelle (Apps-Script) eintragen, Health-Connect-Zugriff erlauben,
 * erste Übertragung starten. Danach läuft alles automatisch.
 */
class MainActivity : Activity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val prefs by lazy { getSharedPreferences("export", MODE_PRIVATE) }
    private val contract = PermissionController.createRequestPermissionResultContract()
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        root.addView(TextView(this).apply {
            text = "\n1. Adresse der Google-Tabelle (Apps-Script-Web-App, beginnt mit https://script.google.com/…):"
        })
        val urlField = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://script.google.com/macros/s/…/exec"
            setText(prefs.getString("url", ""))
        }
        root.addView(urlField)
        button("Adresse speichern") {
            prefs.edit().putString("url", urlField.text.toString().trim()).apply()
            refresh()
        }
        button("2. Health Connect: Zugriff erlauben") { requestAccess() }
        button("3. Jetzt übertragen (letzte 30 Tage)") { syncNow(30) }
        button("Health Connect öffnen") {
            try { startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)) } catch (_: Exception) {}
        }

        root.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            text = """
                Danach läuft alles von selbst: Alle 6 Stunden werden die letzten 7 Tage in die Tabelle geschrieben (vorhandene Zeilen werden aktualisiert, nichts doppelt).

                Übertragen werden: Trainings (Sportart, Dauer, Puls Ø/max, kcal, Distanz), Tageswerte (Schritte, Ruhepuls, kcal) und Schlaf (Dauer, Tief-/Leicht-/REM-/Wachphasen).

                Wichtig: In Health Connect bei „Garmin → Tabelle“ auch „Im Hintergrund auf Daten zugreifen“ erlauben – sonst klappt die automatische Übertragung nicht, nur der Knopf oben.
            """.trimIndent()
        })
        setContentView(ScrollView(this).apply { addView(root) })
        SyncWorker.schedule(this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        if (!HealthExporter.available(this)) {
            status.text = "❌ Health Connect ist auf diesem Handy nicht verfügbar."
            return
        }
        scope.launch {
            val granted = try {
                HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
            } catch (_: Exception) { emptySet() }
            val data = if (granted.containsAll(HealthExporter.DATA_PERMISSIONS)) "✅" else "❌"
            val bg = if (granted.contains(HealthExporter.BACKGROUND)) "✅" else "❌"
            val url = if (prefs.getString("url", "").orEmpty().startsWith("https://script.google.com/")) "✅" else "❌"
            status.text = "Tabellen-Adresse: $url\nHealth-Connect-Zugriff: $data\nHintergrund-Zugriff: $bg\n\n" +
                "Letzte Übertragung: " + (prefs.getString("lastResult", null) ?: "noch keine")
        }
    }

    private fun requestAccess() {
        val intent = contract.createIntent(this, HealthExporter.ALL_PERMISSIONS)
        try { startActivityForResult(intent, 7) } catch (e: Exception) {
            status.text = "Health Connect ließ sich nicht öffnen: ${e.message}"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7) refresh()
    }

    private fun syncNow(days: Int) {
        status.text = "Überträgt …"
        scope.launch {
            val msg = try {
                "✅ " + withContext(Dispatchers.IO) { HealthExporter.sync(this@MainActivity, days) }
            } catch (e: Exception) {
                "⚠️ ${e.message ?: e.javaClass.simpleName}"
            }
            refresh()
            status.append("\n\n$msg")
        }
    }
}
