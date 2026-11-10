package com.molloy.appstopper

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * Bedienungshilfe: Wird nur aktiv, nachdem das Icon getippt wurde. Dann sucht sie in der
 * geöffneten App-Info den Knopf „Stoppen erzwingen“, tippt ihn an und bestätigt mit „OK“.
 */
class ForceStopService : AccessibilityService() {

    companion object {
        @Volatile var instance: ForceStopService? = null

        /** Beschriftungen des Knopfs je nach Hersteller/Sprache. */
        private val STOP_LABELS = listOf(
            "Stoppen erzwingen", "Beenden erzwingen", "Stopp erzwingen", "Erzwungener Stopp",
            "Force stop", "Force Stop"
        )
        private val OK_LABELS = listOf("OK", "Ok")

        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, ForceStopService::class.java).flattenToString()
            val list = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var stage = 0          // 0 = untätig, 1 = Knopf suchen, 2 = Bestätigung suchen
    private var deadline = 0L
    private var label = ""

    override fun onServiceConnected() { instance = this }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (stage != 0) { handler.removeCallbacks(step); handler.postDelayed(step, 120) }
    }

    /** Vom Schlaftimer: App-Info öffnen und beenden (nur bei entsperrtem Bildschirm sinnvoll). */
    fun stopApp(pkg: String, appLabel: String) {
        handler.post {
            start(appLabel)
            startActivity(
                android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_NO_HISTORY or
                        android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            )
        }
    }

    /** Vom Icon aufgerufen, kurz bevor die App-Info geöffnet wird. */
    fun start(appLabel: String) {
        label = appLabel
        stage = 1
        deadline = System.currentTimeMillis() + 8_000
        handler.removeCallbacks(step)
        handler.postDelayed(step, 400)
    }

    private val step: Runnable = object : Runnable {
        override fun run() {
            if (stage == 0) return
            if (System.currentTimeMillis() > deadline) {
                stage = 0
                toast("$label konnte nicht automatisch beendet werden – bitte hier von Hand tippen.")
                return
            }
            try { process() } catch (_: Exception) {}
            if (stage != 0) handler.postDelayed(this, 300)   // weiter nachsehen, falls kein Ereignis kommt
        }
    }

    private fun process() {
        when (stage) {
            1 -> {
                val root = rootInActiveWindow ?: return
                val btn = findByLabels(listOf(root), STOP_LABELS) ?: return
                val clickable = clickableOf(btn) ?: return
                if (!clickable.isEnabled) {
                    finish("$label war schon beendet.")
                    return
                }
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                stage = 2
                deadline = System.currentTimeMillis() + 5_000
            }
            2 -> {
                val fromWindows: List<AccessibilityNodeInfo> =
                    try { windows.mapNotNull { it.root } } catch (_: Exception) { emptyList() }
                val roots = fromWindows.ifEmpty { listOfNotNull(rootInActiveWindow) }
                // Standard-Dialogknopf „OK“ (android:id/button1), sonst per Beschriftung
                val ok = roots.firstNotNullOfOrNull { r ->
                    r.findAccessibilityNodeInfosByViewId("android:id/button1").firstOrNull { it.isEnabled }
                } ?: findByLabels(roots, OK_LABELS, requireDialog = true) ?: return
                clickableOf(ok)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: return
                finish("$label beendet.")
            }
        }
    }

    /** Sucht einen Knoten, dessen Text genau einer der Beschriftungen entspricht. */
    private fun findByLabels(roots: List<AccessibilityNodeInfo>, labels: List<String>, requireDialog: Boolean = false): AccessibilityNodeInfo? {
        for (r in roots) for (l in labels) {
            val hits = r.findAccessibilityNodeInfosByText(l)
            for (n in hits) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim() ?: continue
                if (!t.equals(l, ignoreCase = true)) continue
                if (requireDialog && n.viewIdResourceName?.startsWith("android:id/button") == false &&
                    n.className?.contains("Button") != true) continue
                return n
            }
        }
        return null
    }

    private fun clickableOf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n
    }

    private fun finish(msg: String) {
        stage = 0
        handler.removeCallbacks(step)
        // App-Info wieder schließen -> zurück zum Startbildschirm
        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 350)
        toast(msg)
    }

    private fun toast(msg: String) = handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
