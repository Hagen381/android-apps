package com.molloy.garminexport

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Überträgt automatisch alle 6 Stunden die letzten 7 Tage (doppelte Einträge ersetzt die Tabelle). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val NAME = "garmin-export"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                ).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result = try {
        HealthExporter.sync(applicationContext, 7)
        Result.success()
    } catch (e: Exception) {
        val t = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        applicationContext.getSharedPreferences("export", Context.MODE_PRIVATE).edit()
            .putString("lastResult", "⚠️ $t (automatisch): ${e.message ?: e.javaClass.simpleName}")
            .apply()
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}
