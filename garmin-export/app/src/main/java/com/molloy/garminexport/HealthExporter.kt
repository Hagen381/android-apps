package com.molloy.garminexport

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Liest Trainings, Tageswerte und Schlaf aus Health Connect (dort schreibt Garmin Connect hinein)
 * und schickt sie als JSON an das Apps-Script der Google-Tabelle.
 */
object HealthExporter {

    const val BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"
    const val HISTORY = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    val DATA_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )
    val ALL_PERMISSIONS = DATA_PERMISSIONS + BACKGROUND + HISTORY

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun available(ctx: Context) = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    /** Liest die letzten [days] Tage und lädt sie hoch. Gibt eine kurze Zusammenfassung zurück. */
    suspend fun sync(ctx: Context, days: Int): String {
        val prefs = ctx.getSharedPreferences("export", Context.MODE_PRIVATE)
        val url = prefs.getString("url", null)?.trim().orEmpty()
        require(url.startsWith("https://script.google.com/")) { "Keine gültige Apps-Script-Adresse eingetragen" }

        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = client.permissionController.getGrantedPermissions()
        require(granted.containsAll(DATA_PERMISSIONS)) { "Health-Connect-Zugriff fehlt – bitte in der App erlauben" }

        val today = LocalDate.now(zone)
        val from = today.minusDays(days.toLong() - 1)
        val start = from.atStartOfDay(zone).toInstant()
        val end = Instant.now()

        val sessions = readSessions(client, start, end)
        val dayRows = readDays(client, from, today)
        val sleep = readSleep(client, start.minus(Duration.ofHours(12)), end)

        val payload = JSONObject()
            .put("sessions", sessions)
            .put("days", dayRows)
            .put("sleep", sleep)
            .put("geraet", android.os.Build.MODEL)
            .put("gesendet", LocalDateTime.now(zone).format(timeFmt))

        val answer = post(url, payload.toString())
        require(answer.contains("\"ok\":true")) { "Tabelle antwortet nicht wie erwartet: ${answer.take(120)}" }

        val msg = "${sessions.length()} Trainings, ${dayRows.length()} Tage, ${sleep.length()} Nächte übertragen"
        prefs.edit()
            .putString("lastResult", "✅ ${LocalDateTime.now(zone).format(timeFmt)}: $msg")
            .apply()
        return msg
    }

    private suspend fun readSessions(client: HealthConnectClient, start: Instant, end: Instant): JSONArray {
        val out = JSONArray()
        val records = client.readRecords(
            ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(start, end))
        ).records
        for (s in records) {
            val agg = client.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                    ),
                    timeRangeFilter = TimeRangeFilter.between(s.startTime, s.endTime),
                )
            )
            val kcal = agg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                ?: agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
            val minutes = Duration.between(s.startTime, s.endTime).toMinutes()
            out.put(
                JSONObject()
                    .put("id", s.metadata.id)
                    .put("datum", s.startTime.atZone(zone).toLocalDate().format(dateFmt))
                    .put("start", s.startTime.atZone(zone).format(timeFmt))
                    .put("ende", s.endTime.atZone(zone).format(timeFmt))
                    .put("dauer_min", minutes)
                    .put("sportart", sportName(s.exerciseType))
                    .put("titel", s.title ?: "")
                    .put("puls_avg", agg[HeartRateRecord.BPM_AVG] ?: "")
                    .put("puls_max", agg[HeartRateRecord.BPM_MAX] ?: "")
                    .put("kcal", kcal?.roundToInt() ?: "")
                    .put("distanz_km", agg[DistanceRecord.DISTANCE_TOTAL]?.inKilometers?.let { (it * 100).roundToInt() / 100.0 } ?: "")
                    .put("quelle", s.metadata.dataOrigin.packageName)
            )
        }
        return out
    }

    private suspend fun readDays(client: HealthConnectClient, from: LocalDate, to: LocalDate): JSONArray {
        val out = JSONArray()
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL, RestingHeartRateRecord.BPM_AVG,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                ),
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            )
        )
        for (g in groups) {
            val r = g.result
            out.put(
                JSONObject()
                    .put("datum", g.startTime.toLocalDate().format(dateFmt))
                    .put("schritte", r[StepsRecord.COUNT_TOTAL] ?: "")
                    .put("ruhepuls", r[RestingHeartRateRecord.BPM_AVG] ?: "")
                    .put("kcal_gesamt", r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories?.roundToInt() ?: "")
                    .put("kcal_aktiv", r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.roundToInt() ?: "")
                    .put("distanz_km", r[DistanceRecord.DISTANCE_TOTAL]?.inKilometers?.let { (it * 100).roundToInt() / 100.0 } ?: "")
            )
        }
        return out
    }

    private suspend fun readSleep(client: HealthConnectClient, start: Instant, end: Instant): JSONArray {
        val out = JSONArray()
        val records = client.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start, end))
        ).records
        for (s in records) {
            fun minutesOf(type: Int) = s.stages.filter { it.stage == type }
                .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
            val total = Duration.between(s.startTime, s.endTime).toMinutes()
            out.put(
                JSONObject()
                    .put("id", s.metadata.id)
                    .put("nacht", s.endTime.atZone(zone).toLocalDate().format(dateFmt))
                    .put("start", s.startTime.atZone(zone).format(timeFmt))
                    .put("ende", s.endTime.atZone(zone).format(timeFmt))
                    .put("dauer_h", (total / 60.0 * 100).roundToInt() / 100.0)
                    .put("tief_min", minutesOf(SleepSessionRecord.STAGE_TYPE_DEEP))
                    .put("leicht_min", minutesOf(SleepSessionRecord.STAGE_TYPE_LIGHT))
                    .put("rem_min", minutesOf(SleepSessionRecord.STAGE_TYPE_REM))
                    .put("wach_min", minutesOf(SleepSessionRecord.STAGE_TYPE_AWAKE))
                    .put("quelle", s.metadata.dataOrigin.packageName)
            )
        }
        return out
    }

    private fun sportName(t: Int): String = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Laufen"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Laufband"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Gehen"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Wandern"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Radfahren"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Indoor-Rad"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Schwimmen (Becken)"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Schwimmen (Freiwasser)"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Krafttraining"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Gewichtheben"
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "Eigengewicht"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Crosstrainer"
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rudergerät"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "Stepper"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "Dehnen"
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> "Boxen"
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS -> "Kampfsport"
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS -> "Kurs"
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "Cardio/Sonstiges"
        else -> "Typ $t"
    }

    private fun post(url: String, body: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        // Apps Script antwortet mit einer Weiterleitung auf das Ergebnis
        var c = conn
        var hops = 0
        while (c.responseCode in 300..399 && hops < 5) {
            val next = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = (URL(next).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000; readTimeout = 60_000
            }
            hops++
        }
        val text = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return text
    }
}
