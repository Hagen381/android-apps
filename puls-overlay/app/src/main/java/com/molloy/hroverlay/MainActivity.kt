package com.molloy.hroverlay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Startbildschirm: Berechtigungen einholen, nach der Uhr suchen,
 * Overlay-Dienst starten/stoppen.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private data class Found(val address: String, val name: String, val isHr: Boolean, var rssi: Int, val rank: Int, val tag: String)

    private lateinit var status: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private val found = LinkedHashMap<String, Found>()
    private val sorted = mutableListOf<Found>()
    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false

    private val prefs by lazy { getSharedPreferences("hr", MODE_PRIVATE) }
    private val btManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val scanner get() = btManager.adapter?.bluetoothLeScanner

    private fun looksLikeWatch(name: String?) = name != null &&
        Regex("garmin|forerunner|fenix|f\u0113nix|venu|vivo|epix|instinct|enduro", RegexOption.IGNORE_CASE).containsMatchIn(name)

    /** Bereits mit dem Handy verbundene bzw. gekoppelte Geräte (z. B. Uhr über Garmin Connect). */
    private fun addKnownDevices() {
        val connected = try { btManager.getConnectedDevices(BluetoothProfile.GATT) } catch (_: Exception) { emptyList() }
        for (dev in connected) {
            val name = dev.name ?: dev.address
            found[dev.address] = Found(dev.address, name, false, 0, if (looksLikeWatch(name)) 3 else 2, "verbunden")
        }
        val bonded = try { btManager.adapter?.bondedDevices.orEmpty() } catch (_: Exception) { emptySet() }
        for (dev in bonded) {
            if (found.containsKey(dev.address)) continue
            if (dev.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) continue
            val name = dev.name ?: dev.address
            found[dev.address] = Found(dev.address, name, false, 0, if (looksLikeWatch(name)) 2 else 1, "gekoppelt")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)

        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(TextView(this).apply {
            text = "Herzfrequenzzonen wie in Garmin Connect (Profil › Herzfrequenzzonen): jeweils Untergrenze in bpm, dazu deine max. HF."
        })
        val labels = arrayOf("Max") + Zones.keys.map { "Z" + it.substring(1) + " ab" }
        val prefKeys = arrayOf("maxHr") + Zones.keys
        val fields = mutableListOf<EditText>()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in labels.indices) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply { text = labels[i]; textSize = 12f })
            val f = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(Zones.bounds(prefs).let { b -> if (i == 0) b[5] else b[i - 1] }.toString())
                val key = prefKeys[i]
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(e: android.text.Editable?) {
                        e?.toString()?.toIntOrNull()?.takeIf { it in 40..250 }?.let { prefs.edit().putInt(key, it).apply() }
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            fields += f
            col.addView(f)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(row)
        button("Zonen aus max. HF berechnen (Garmin-Standard)") {
            val max = fields[0].text.toString().toIntOrNull() ?: prefs.getInt("maxHr", 190)
            Zones.defaultBounds(max).forEachIndexed { i, v -> fields[i + 1].setText(v.toString()) }
        }
        button("1. Berechtigungen erteilen") { requestRuntimePermissions() }
        button("2. Über anderen Apps anzeigen erlauben") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("3. Nach Uhr suchen") { startScan() }
        button("Mit zuletzt genutzter Uhr verbinden") {
            prefs.getString("address", null)?.let { startOverlay(it) } ?: toastStatus("Noch keine Uhr gespeichert – bitte erst suchen.")
        }
        button("Overlay beenden") {
            startService(Intent(this, HrService::class.java).setAction(HrService.ACTION_STOP))
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        val list = ListView(this).apply {
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, pos, _ ->
                stopScan()
                startOverlay(sorted[pos].address)
            }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        stopScan()
    }

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    private fun missingPermissions() = neededPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun requestRuntimePermissions() {
        val missing = missingPermissions()
        if (missing.isEmpty()) toastStatus("Alle Berechtigungen vorhanden.") else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun refreshStatus() {
        val perms = if (missingPermissions().isEmpty()) "✅" else "❌"
        val overlay = if (Settings.canDrawOverlays(this)) "✅" else "❌"
        val last = prefs.getString("name", null) ?: "–"
        val running = if (HrService.running) "läuft" else "aus"
        status.text = """
            Berechtigungen: $perms    Overlay: $overlay
            Zuletzt genutzte Uhr: $last
            Overlay-Dienst: $running

            Vorher auf der Garmin-Uhr: Einstellungen › Sensoren & Zubehör › Handgelenk-Herzfrequenz › „Herzfrequenz übertragen“ bzw. „Während Aktivität übertragen“ einschalten.
        """.trimIndent()
    }

    private fun toastStatus(msg: String) {
        refreshStatus()
        status.append("\n\n$msg")
    }

    private fun startScan() {
        if (missingPermissions().isNotEmpty()) { requestRuntimePermissions(); return }
        val s = scanner ?: run { toastStatus("Bluetooth ist ausgeschaltet."); return }
        if (scanning) return
        found.clear(); addKnownDevices(); render()
        scanning = true
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        s.startScan(null, settings, scanCallback)
        toastStatus("Suche läuft (15 s)… ❤ = sendet Herzfrequenz, ⌚ = Garmin-Uhr (auch wenn schon über Garmin Connect verbunden – einfach antippen).")
        handler.postDelayed({ stopScan() }, 15_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacksAndMessages(null)
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids.orEmpty()
            val isHr = uuids.contains(ParcelUuid(HrService.HR_SERVICE))
            val name = result.scanRecord?.deviceName ?: result.device.name
            if (name == null && !isHr) return
            val addr = result.device.address
            val existing = found[addr]
            if (existing != null && !(isHr && !existing.isHr)) existing.rssi = result.rssi
            else found[addr] = Found(addr, name ?: existing?.name ?: addr, isHr, result.rssi,
                if (isHr) 4 else if (looksLikeWatch(name)) 2 else 0, existing?.tag ?: "")
            render()
        }
    }

    private fun render() {
        sorted.clear()
        sorted += found.values.sortedWith(compareByDescending<Found> { it.rank }.thenByDescending { it.rssi })
        adapter.clear()
        adapter.addAll(sorted.map {
            val icon = when { it.isHr -> "❤ "; looksLikeWatch(it.name) -> "⌚ "; else -> "    " }
            val info = if (it.tag.isNotEmpty()) it.tag else "${it.rssi} dBm"
            "$icon${it.name}   ($info)"
        })
    }

    private fun startOverlay(address: String) {
        if (!Settings.canDrawOverlays(this)) { toastStatus("Bitte zuerst Schritt 2 (Overlay erlauben)."); return }
        if (missingPermissions().isNotEmpty()) { requestRuntimePermissions(); return }
        found[address]?.let { prefs.edit().putString("address", address).putString("name", it.name).apply() }
            ?: prefs.edit().putString("address", address).apply()
        ContextCompat.startForegroundService(this, Intent(this, HrService::class.java).putExtra(HrService.EXTRA_ADDRESS, address))
        toastStatus("Overlay gestartet – du kannst die App jetzt verlassen.")
    }
}
