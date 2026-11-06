package com.molloy.regenradar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Rect
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lädt den Radarfilm (animiertes GIF) des Wetterdienstes TMD, schneidet aus jedem Bild
 * den Bereich um Koh Samui aus und legt die Einzelbilder für das Widget ab.
 */
class RadarWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val GIF_URL = "https://weather.tmd.go.th/srt/srtloop.gif"
        const val PAGE_URL = "https://weather.tmd.go.th/srtloop.php"

        /** Ausschnitt im 800×800-Originalbild: Chumphon bis Nakhon Si Thammarat, Koh Samui in der Mitte. */
        private val CROP = Rect(400, 15, 770, 640)

        /** Zielbreite der Einzelbilder (begrenzt den Speicher des Widgets). */
        private const val OUT_WIDTH = 300

        fun framesDir(ctx: Context) = File(ctx.filesDir, "frames").apply { mkdirs() }
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("radar", Context.MODE_PRIVATE)
        return try {
            val conn = (URL(GIF_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "RegenradarSamui/1.0 (Android widget)")
                useCaches = false
                // Spart Daten und Akku: der Server schickt nur dann neue Bilder, wenn es welche gibt
                val known = prefs.getLong("lastModified", 0L)
                if (known > 0 && framesDir(applicationContext).listFiles()?.isNotEmpty() == true) ifModifiedSince = known
            }
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                conn.disconnect()
                prefs.edit().putLong("checked", System.currentTimeMillis()).putString("error", null).apply()
                RadarWidget.renderAll(applicationContext)
                return Result.success()
            }
            val lastModified = conn.lastModified
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()

            // Unverändert seit dem letzten Abruf? Dann nur den Zeitpunkt der Prüfung merken.
            if (lastModified != 0L && lastModified == prefs.getLong("lastModified", -1L) &&
                (framesDir(applicationContext).listFiles()?.isNotEmpty() == true)
            ) {
                prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
                RadarWidget.renderAll(applicationContext)
                return Result.success()
            }

            val frames = decodeFrames(bytes)
            if (frames.isEmpty()) return Result.retry()

            val dir = framesDir(applicationContext)
            dir.listFiles()?.forEach { it.delete() }
            frames.forEachIndexed { i, bmp ->
                FileOutputStream(File(dir, "f%02d.png".format(i))).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
            }
            prefs.edit()
                .putLong("lastModified", lastModified)
                .putLong("checked", System.currentTimeMillis())
                .putString("error", null)
                .apply()
            RadarWidget.renderAll(applicationContext)
            Result.success()
        } catch (e: Exception) {
            prefs.edit().putString("error", e.javaClass.simpleName).apply()
            RadarWidget.renderAll(applicationContext)
            Result.retry()
        }
    }

    /** Zerlegt das GIF über die Bildverzögerungen in Einzelbilder und schneidet sie zu. */
    @Suppress("DEPRECATION")
    private fun decodeFrames(bytes: ByteArray): List<Bitmap> {
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
        val w = movie.width()
        val h = movie.height()
        if (w <= 0 || h <= 0) return emptyList()

        val delays = gifDelays(bytes).ifEmpty { listOf(800) }
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)

        val sx = w / 800f
        val sy = h / 800f
        val src = Rect((CROP.left * sx).toInt(), (CROP.top * sy).toInt(), (CROP.right * sx).toInt(), (CROP.bottom * sy).toInt())
        val outH = OUT_WIDTH * src.height() / src.width()

        val result = mutableListOf<Bitmap>()
        var t = 0
        for (d in delays) {
            movie.setTime(t + 1)
            canvas.drawColor(android.graphics.Color.BLACK)
            movie.draw(canvas, 0f, 0f)
            val cut = Bitmap.createBitmap(full, src.left, src.top, src.width(), src.height())
            result += Bitmap.createScaledBitmap(cut, OUT_WIDTH, outH, true)
            cut.recycle()
            t += d
        }
        full.recycle()
        return result
    }

    /** Liest die Verzögerung jedes Einzelbilds (ms) aus den Graphic-Control-Blöcken des GIFs. */
    private fun gifDelays(b: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i < b.size - 7) {
            if (b[i] == 0x21.toByte() && b[i + 1] == 0xF9.toByte() && b[i + 2] == 0x04.toByte() && b[i + 7] == 0x00.toByte()) {
                val cs = (b[i + 4].toInt() and 0xFF) or ((b[i + 5].toInt() and 0xFF) shl 8)
                out += (cs * 10).coerceAtLeast(100)
                i += 8
            } else i++
        }
        return out
    }
}
