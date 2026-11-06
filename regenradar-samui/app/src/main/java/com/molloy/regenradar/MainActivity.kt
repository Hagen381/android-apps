package com.molloy.regenradar

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Kurze Anleitung + Knopf, der das Widget direkt auf den Startbildschirm legt. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val info = TextView(this).apply {
            textSize = 15f
            text = """
                Regenradar Samui zeigt den Radarfilm des thailändischen Wetterdienstes (Radar Surat Thani), Ausschnitt rund um Koh Samui, direkt auf dem Startbildschirm.

                • Aktualisiert sich von selbst im gewählten Abstand und lädt nur, wenn es wirklich neue Radarbilder gibt.
                • Bei niedrigem Akku pausiert es automatisch.
                • Tipp auf die Uhrzeit unten rechts: sofort neu laden.
                • Tipp aufs Bild: Originalseite im Browser öffnen.
                • Größe ändern: Widget lange drücken und an den Rändern ziehen.
            """.trimIndent()
        }
        root.addView(info)

        // Spar-Einstellungen
        val prefs = getSharedPreferences("radar", MODE_PRIVATE)
        root.addView(TextView(this).apply { text = "\nAktualisieren alle:"; textSize = 15f })
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (m in listOf(15, 30, 60)) {
            group.addView(RadioButton(this).apply {
                id = m
                text = "$m Min."
                isChecked = RadarWidget.intervalMinutes(this@MainActivity) == m
            })
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putInt("interval", checkedId).apply()
            RadarWidget.schedule(this, replace = true)
        }
        root.addView(group)
        root.addView(CheckBox(this).apply {
            text = "Nur im WLAN laden (spart mobile Daten, ca. 2 MB pro neuem Film)"
            isChecked = RadarWidget.wifiOnly(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean("wifiOnly", on).apply()
                RadarWidget.schedule(this@MainActivity, replace = true)
            }
        })

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val status = TextView(this)
        button("Widget auf den Startbildschirm legen") {
            val mgr = getSystemService(AppWidgetManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(ComponentName(this, RadarWidget::class.java), null, null)
            } else {
                status.text = "Bitte von Hand: Startbildschirm lange drücken › Widgets › Regenradar Samui."
            }
        }
        button("Jetzt aktualisieren") {
            RadarWidget.schedule(this)
            RadarWidget.refreshNow(this)
            status.text = "Wird geladen – das Widget zeigt gleich den neuesten Stand."
        }
        root.addView(status)
        setContentView(root)
    }
}
