package com.molloy.regenradar

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Startbildschirm-Widget: zeigt den Radarfilm als Bildfolge (ViewFlipper).
 * Tipp aufs Bild öffnet die Originalseite, Tipp auf die Uhrzeit lädt sofort neu.
 */
class RadarWidget : AppWidgetProvider() {

    companion object {
        private const val ACTION_REFRESH = "com.molloy.regenradar.REFRESH"
        private const val PERIODIC = "radar-periodic"
        private const val ONCE = "radar-once"

        private val netConstraint = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RadarWorker>(15, TimeUnit.MINUTES)
                .setConstraints(netConstraint).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun refreshNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RadarWorker>().setConstraints(netConstraint).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        }

        /** Baut die Widget-Ansicht aus den gespeicherten Einzelbildern neu auf. */
        fun renderAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, RadarWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            views.removeAllViews(R.id.flipper)

            val files = RadarWorker.framesDir(ctx).listFiles()?.sortedBy { it.name }.orEmpty()
            for (f in files) {
                val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: continue
                val frame = RemoteViews(ctx.packageName, R.layout.frame)
                frame.setImageViewBitmap(R.id.frame, bmp)
                views.addView(R.id.flipper, frame)
            }

            val prefs = ctx.getSharedPreferences("radar", Context.MODE_PRIVATE)
            val lm = prefs.getLong("lastModified", 0L)
            val fmt = SimpleDateFormat("HH:mm", Locale.GERMANY)
            val stamp = when {
                files.isEmpty() && prefs.getString("error", null) != null -> "Kein Netz – tippen ↻"
                files.isEmpty() -> "Lädt …"
                lm > 0 -> "Radar ${fmt.format(Date(lm))} ↻"
                else -> "↻"
            }
            views.setTextViewText(R.id.stamp, stamp)

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(Intent.ACTION_VIEW, Uri.parse(RadarWorker.PAGE_URL)), PendingIntent.FLAG_IMMUTABLE
            )
            val refresh = PendingIntent.getBroadcast(
                ctx, 1, Intent(ctx, RadarWidget::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.flipper, open)
            views.setOnClickPendingIntent(R.id.stamp, refresh)

            try {
                mgr.updateAppWidget(ids, views)
            } catch (_: IllegalArgumentException) {
                // Zu viel Bildspeicher: nur das neueste Bild anzeigen
                val single = RemoteViews(ctx.packageName, R.layout.widget)
                single.removeAllViews(R.id.flipper)
                files.lastOrNull()?.let { f ->
                    BitmapFactory.decodeFile(f.absolutePath)?.let { bmp ->
                        val frame = RemoteViews(ctx.packageName, R.layout.frame)
                        frame.setImageViewBitmap(R.id.frame, bmp)
                        single.addView(R.id.flipper, frame)
                    }
                }
                single.setTextViewText(R.id.stamp, stamp)
                single.setOnClickPendingIntent(R.id.flipper, open)
                single.setOnClickPendingIntent(R.id.stamp, refresh)
                mgr.updateAppWidget(ids, single)
            }
        }
    }

    override fun onEnabled(context: Context) {
        schedule(context)
        refreshNow(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        schedule(context)
        renderAll(context)
        refreshNow(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, RadarWidget::class.java))
            val v = RemoteViews(context.packageName, R.layout.widget)
            v.setTextViewText(R.id.stamp, "Lädt …")
            mgr.partiallyUpdateAppWidget(ids, v)
            refreshNow(context)
        }
    }
}
