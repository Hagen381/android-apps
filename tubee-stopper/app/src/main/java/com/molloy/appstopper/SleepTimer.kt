package com.molloy.appstopper

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Schlaftimer: Nach Ablauf wird die Musik/das Video sanft leiser gedreht und angehalten
 * (Medientaste „Pause“ + Audiofokus übernehmen). Läuft über einen Wecker des Systems –
 * kein Dauerdienst im Hintergrund, kostet also praktisch keinen Akku.
 */
object SleepTimer {
    const val ACTION_FIRE = "com.molloy.appstopper.SLEEP_FIRE"
    const val ACTION_ADD = "com.molloy.appstopper.SLEEP_ADD"
    const val ACTION_CANCEL = "com.molloy.appstopper.SLEEP_CANCEL"
    private const val CHANNEL = "sleep"
    private const val NOTIF_ID = 42

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("stopper", Context.MODE_PRIVATE)

    fun endTime(ctx: Context) = prefs(ctx).getLong("sleepEnd", 0L)
    fun isActive(ctx: Context) = endTime(ctx) > System.currentTimeMillis()
    fun label(ctx: Context) = prefs(ctx).getString("label", null) ?: "Tubee"
    fun endText(ctx: Context): String = SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(endTime(ctx)))

    fun start(ctx: Context, minutes: Int) = setEnd(ctx, System.currentTimeMillis() + minutes * 60_000L)

    fun extend(ctx: Context, minutes: Int) =
        setEnd(ctx, maxOf(endTime(ctx), System.currentTimeMillis()) + minutes * 60_000L)

    private fun setEnd(ctx: Context, end: Long) {
        prefs(ctx).edit().putLong("sleepEnd", end).apply()
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = firePi(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pi)
        }
        showNotification(ctx, end)
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePi(ctx))
        prefs(ctx).edit().remove("sleepEnd").apply()
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }

    private fun firePi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 10, Intent(ctx, SleepReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun showNotification(ctx: Context, end: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Schlaftimer", NotificationManager.IMPORTANCE_LOW))
        }
        fun action(a: String, req: Int) = PendingIntent.getBroadcast(
            ctx, req, Intent(ctx, SleepReceiver::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            ctx, 13, Intent(ctx, SleepActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_moon)
            .setContentTitle("Schlaftimer: ${label(ctx)} stoppt um ${endText(ctx)}")
            .setWhen(end)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "+15 Min.", action(ACTION_ADD, 11)).build())
            .addAction(Notification.Action.Builder(null, "Abbrechen", action(ACTION_CANCEL, 12)).build())
            .build()
        try { nm.notify(NOTIF_ID, n) } catch (_: SecurityException) {}
    }

    /** Sanft leiser, anhalten, Lautstärke zurück. Läuft im Hintergrund-Thread des Empfängers. */
    fun fadeAndPause(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val p = prefs(ctx)
        // Falls ein früherer Lauf die Lautstärke nicht zurücksetzen konnte
        val pending = p.getInt("restoreVolume", -1)
        val orig = if (pending >= 0) pending else am.getStreamVolume(AudioManager.STREAM_MUSIC)
        p.edit().putInt("restoreVolume", orig).apply()

        // 1. In ca. 6 Sekunden leiser drehen
        val steps = orig.coerceAtLeast(1)
        for (v in orig downTo 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            SystemClock.sleep(6_000L / steps)
        }

        // 2. Anhalten: Medientaste „Pause“ an die laufende Wiedergabe …
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        // … und zusätzlich den Audiofokus übernehmen – darauf pausieren fast alle Player
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            ).build()
        am.requestAudioFocus(focus)
        SystemClock.sleep(2_500)
        am.abandonAudioFocusRequest(focus)

        // 3. Lautstärke nur zurückdrehen, wenn wirklich nichts mehr spielt
        if (!am.isMusicActive) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, orig, 0)
            p.edit().remove("restoreVolume").apply()
        }

        // 4. Optional: Tubee zusätzlich beenden – geht nur bei eingeschaltetem, entsperrtem Bildschirm
        if (p.getBoolean("killAfterSleep", false)) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val km = ctx.getSystemService(KeyguardManager::class.java)
            val pkg = p.getString("pkg", null)
            val svc = ForceStopService.instance
            if (pkg != null && svc != null && pm.isInteractive && !km.isKeyguardLocked) {
                svc.stopApp(pkg, label(ctx))
            }
        }
    }

    /** Beim Öffnen der App: eine evtl. offene Lautstärke wiederherstellen. */
    fun restoreVolumeIfPending(ctx: Context) {
        val p = prefs(ctx)
        val v = p.getInt("restoreVolume", -1)
        if (v < 0) return
        val am = ctx.getSystemService(AudioManager::class.java)
        if (!am.isMusicActive || am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        }
        p.edit().remove("restoreVolume").apply()
    }
}

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SleepTimer.ACTION_ADD -> SleepTimer.extend(context, 15)
            SleepTimer.ACTION_CANCEL -> SleepTimer.cancel(context)
            SleepTimer.ACTION_FIRE -> {
                // Wurde inzwischen verlängert? Dann ist dieser Wecker veraltet.
                if (SleepTimer.endTime(context) - System.currentTimeMillis() > 5_000) return
                SleepTimer.cancel(context)
                val pending = goAsync()
                Thread {
                    try { SleepTimer.fadeAndPause(context) } finally { pending.finish() }
                }.start()
            }
        }
    }
}
