package com.molloy.garminexport

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/** Von Health Connect verlangte Erklärung, wofür die Gesundheitsdaten genutzt werden. */
class PrivacyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        setContentView(ScrollView(this).apply {
            addView(TextView(this@PrivacyActivity).apply {
                setPadding(pad, pad, pad, pad)
                textSize = 15f
                text = """
                    Datenschutz – Garmin → Tabelle

                    Diese App liest ausschließlich lesend aus Health Connect: Trainings, Puls, Ruhepuls, Schritte, Distanz, Kalorien und Schlaf.

                    Die Werte werden nur an die eine Google-Tabelle geschickt, deren Adresse du in der App einträgst (dein eigenes Apps-Script in deinem Google-Konto). Es gibt keinen weiteren Server, keine Werbung und keine Weitergabe an Dritte.

                    Den Zugriff kannst du jederzeit in Health Connect entziehen.
                """.trimIndent()
            })
        })
    }
}
