package com.molloy.appstopper

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * Icon „Tubee Schlaf“: kleine Auswahl der Laufzeit. Mit dem Extra „minutes“
 * (Kurzbefehl beim langen Drücken) startet der Timer sofort.
 */
class SleepActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SleepTimer.restoreVolumeIfPending(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        val direct = intent.getIntExtra("minutes", 0)
        if (direct > 0) {
            startTimer(direct)
            return
        }
        showChooser()
    }

    private fun showChooser() {
        val label = SleepTimer.label(this)
        val minutes = listOf(15, 30, 45, 60, 90, 120)
        val items = mutableListOf<String>()
        val active = SleepTimer.isActive(this)
        if (active) {
            items += "⏹ Timer abbrechen (läuft bis ${SleepTimer.endText(this)})"
            items += "➕ 15 Minuten verlängern"
        }
        items += minutes.map { "$it Minuten" }

        AlertDialog.Builder(this)
            .setTitle("Schlaftimer für $label")
            .setItems(items.toTypedArray()) { _, i ->
                val offset = if (active) 2 else 0
                when {
                    active && i == 0 -> { SleepTimer.cancel(this); toast("Schlaftimer abgebrochen") }
                    active && i == 1 -> { SleepTimer.extend(this, 15); toast("Verlängert bis ${SleepTimer.endText(this)}") }
                    else -> { startTimer(minutes[i - offset]); return@setItems }
                }
                finish()
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun startTimer(min: Int) {
        SleepTimer.start(this, min)
        toast("${SleepTimer.label(this)} stoppt um ${SleepTimer.endText(this)}")
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
