package com.molloy.appstopper

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings

/**
 * Das Icon. Ohne eigene Oberfläche: öffnet die App-Info der gewählten App,
 * die Bedienungshilfe tippt dann „Stoppen erzwingen“ und „OK“.
 * Ist noch nichts eingerichtet, öffnet sich stattdessen die Einrichtung.
 */
class StopActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("stopper", MODE_PRIVATE)
        val pkg = prefs.getString("pkg", null)
        val service = ForceStopService.instance

        if (pkg == null || service == null || !ForceStopService.isEnabled(this)) {
            startActivity(Intent(this, SetupActivity::class.java))
        } else {
            service.start(prefs.getString("label", pkg) ?: pkg)
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    )
            )
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
