package com.molloy.appstopper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Einrichtung: Ziel-App wählen, Bedienungshilfe einschalten, testen. */
class SetupActivity : Activity() {

    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("stopper", MODE_PRIVATE) }

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
        button("1. App auswählen, die beendet werden soll") { pickApp() }
        button("2. Bedienungshilfe einschalten") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("Falls Schritt 2 ausgegraut ist: App-Info dieser App öffnen") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        button("3. Jetzt testen") {
            startActivity(Intent(this, StopActivity::class.java))
        }
        root.addView(TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
            text = """
                So geht's:
                • Schritt 1: Tubee in der Liste antippen.
                • Schritt 2: In den Bedienungshilfen „Installierte Apps“ bzw. „Heruntergeladene Apps“ › „Tubee aus“ › einschalten.
                • Ist der Schalter ausgegraut („Eingeschränkte Einstellung“): App-Info dieser App öffnen › oben rechts ⋮ › „Eingeschränkte Einstellungen zulassen“, dann Schritt 2 wiederholen.
                • Danach das Icon „Tubee aus“ auf dem Startbildschirm unter Tubee legen. Ein Tipp beendet Tubee.
                • Diese Einrichtung erreichst du später über langes Drücken auf das Icon › „Einrichtung“.
            """.trimIndent()
        })
        setContentView(ScrollView(this).apply { addView(root) })
        publishShortcut()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val label = prefs.getString("label", null)
        val app = if (label != null) "✅ $label" else "❌ noch nicht gewählt"
        val acc = if (ForceStopService.isEnabled(this)) "✅ an" else "❌ aus"
        status.text = "Zu beendende App: $app\nBedienungshilfe: $acc\n"
    }

    private fun pickApp() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedWith(compareByDescending<Pair<String, String>> { it.second.contains("tubee", true) }
                .thenBy { it.second.lowercase() })
        AlertDialog.Builder(this)
            .setTitle("Welche App soll beendet werden?")
            .setItems(apps.map { it.second }.toTypedArray()) { _, i ->
                prefs.edit().putString("pkg", apps[i].first).putString("label", apps[i].second).apply()
                refresh()
            }
            .show()
    }

    /** Langes Drücken auf das Icon › „Einrichtung“. */
    private fun publishShortcut() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val sm = getSystemService(ShortcutManager::class.java) ?: return
        val sc = ShortcutInfo.Builder(this, "setup")
            .setShortLabel("Einrichtung")
            .setIcon(Icon.createWithResource(this, R.drawable.ic_stop))
            .setIntent(Intent(this, SetupActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        try { sm.dynamicShortcuts = listOf(sc) } catch (_: Exception) {}
    }
}
