package com.molloy.hroverlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.ParcelUuid
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ServiceCompat
import java.util.UUID

/**
 * Vordergrund-Dienst: verbindet sich per Bluetooth LE mit dem
 * Standard-Herzfrequenzprofil (0x180D) der Uhr und zeigt den Puls
 * in einem kleinen, verschiebbaren Fenster über allen Apps.
 */
@SuppressLint("MissingPermission")
class HrService : Service() {

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val ACTION_STOP = "com.molloy.hroverlay.STOP"
        val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val CHANNEL = "hr"
        private const val NOTIFICATION_ID = 1
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var stopping = false
    private var overlay: LinearLayout? = null
    private var bpmView: TextView? = null
    private var statusView: TextView? = null
    private var hrScanning = false
    private var zoneBar: ZoneBar? = null
    private var lastBpm = 0
    private var lastUpdate = 0L
    private lateinit var params: WindowManager.LayoutParams
    private val wm by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val prefs by lazy { getSharedPreferences("hr", MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val newAddress = intent?.getStringExtra(EXTRA_ADDRESS) ?: prefs.getString("address", null)
        if (newAddress == null) { stopSelf(); return START_NOT_STICKY }

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
        running = true
        stopping = false
        showOverlay()
        main.removeCallbacks(ticker)
        main.post(ticker)

        if (newAddress != address || gatt == null) {
            address = newAddress
            closeGatt()
            connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopping = true
        running = false
        main.removeCallbacksAndMessages(null)
        stopHrScan()
        closeGatt()
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
        bpmView = null
        statusView = null
        zoneBar = null
        super.onDestroy()
    }

    // ---------- Bluetooth ----------

    private fun connect() {
        val addr = address ?: return
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            setText("BT aus", Color.GRAY)
            scheduleReconnect()
            return
        }
        setText("♥ …", Color.GRAY)
        val device = adapter.getRemoteDevice(addr)
        setStatus("Verbinde mit ${device.name ?: addr} …")
        gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE)
        // Verbindung hängt? Nach 15 s neu versuchen und nach HF-Sendern suchen.
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, 15_000)
        startHrScan()
    }

    private val connectTimeout = Runnable {
        if (!stopping) {
            setStatus("Keine Verbindung – neuer Versuch …")
            closeGatt(); connect()
        }
    }

    private val noDataTimeout = Runnable {
        if (!stopping && System.currentTimeMillis() - lastUpdate > 10_000) {
            setStatus("Keine Pulsdaten – neuer Versuch …")
            closeGatt(); connect()
        }
    }

    // ---------- Suche nach einem HF-Sender (Uhr sendet evtl. unter eigener Kennung) ----------

    private val hrScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address
            val name = result.scanRecord?.deviceName ?: result.device.name
            // Nur die eigene Uhr akzeptieren, keine fremden Brustgurte in der Nähe
            if (!isOwnWatch(addr, name)) return
            main.post {
                if (stopping || addr == address) return@post
                if (System.currentTimeMillis() - lastUpdate < 5_000) return@post
                setStatus("HF-Sender gefunden: ${result.scanRecord?.deviceName ?: result.device.name ?: addr}")
                address = addr
                prefs.edit().putString("address", addr).apply()
                stopHrScan()
                closeGatt(); connect()
            }
        }
    }

    private val watchNames = Regex("garmin|forerunner|fenix|f\u0113nix|venu|vivo|epix|instinct|enduro", RegexOption.IGNORE_CASE)

    private fun isOwnWatch(addr: String, name: String?): Boolean {
        if (addr == prefs.getString("hrAddress", null)) return true
        if (name == null) return false
        if (name == prefs.getString("hrName", null) || name == prefs.getString("name", null)) return true
        return watchNames.containsMatchIn(name)
    }

    private fun startHrScan() {
        if (hrScanning) return
        val scanner = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner ?: return
        try {
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(listOf(filter), settings, hrScanCallback)
            hrScanning = true
        } catch (_: Exception) {}
    }

    private fun stopHrScan() {
        if (!hrScanning) return
        hrScanning = false
        try { (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeScanner?.stopScan(hrScanCallback) } catch (_: Exception) {}
    }

    private fun closeGatt() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
    }

    private val reconnect = Runnable { if (!stopping) { closeGatt(); connect() } }

    /** Sekündlicher Abgleich: Anzeige auffrischen, veraltete Werte (>5 s) ausgrauen. */
    private val ticker = object : Runnable {
        override fun run() {
            if (stopping) return
            if (lastUpdate > 0) {
                val age = System.currentTimeMillis() - lastUpdate
                if (age > 5_000) {
                    setText("♥ --", Color.GRAY, -1.0)
                    if (age > 10_000) { lastUpdate = 0; main.removeCallbacks(noDataTimeout); main.post(noDataTimeout) }
                } else render()
            }
            main.postDelayed(this, 1_000)
        }
    }

    private fun scheduleReconnect() {
        if (stopping) return
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, 3_000)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != null && g != gatt) return   // Meldung einer alten Verbindung
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.post {
                    main.removeCallbacks(connectTimeout)
                    setText("♥ …", Color.LTGRAY)
                    setStatus("Verbunden – suche Pulsdienst …")
                }
                // Android merkt sich die Dienste gekoppelter Geräte; Cache leeren, damit
                // eine erst später eingeschaltete HF-Übertragung erkannt wird.
                try { g.javaClass.getMethod("refresh").invoke(g) } catch (_: Exception) {}
                main.postDelayed({ try { g.discoverServices() } catch (_: Exception) {} }, 600)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post {
                    setText("♥ --", Color.GRAY)
                    setStatus("Getrennt (Status $status) – neuer Versuch …")
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (gatt != null && g != gatt) return
            val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (ch == null) {
                val n = g.services.size
                main.post {
                    setText("♥ …", Color.GRAY)
                    setStatus("Kein Pulsdienst ($n Dienste) – suche HF-Sender …")
                    startHrScan()
                    // erneut versuchen, bis an der Uhr die Übertragung läuft
                    if (!stopping) main.postDelayed(reconnect, 5_000)
                }
                return
            }
            main.post { setStatus("Pulsdienst gefunden – fordere Daten an …") }
            g.setCharacteristicNotification(ch, true)
            val desc = ch.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(desc)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (gatt != null && g != gatt) return
            main.post {
                setStatus(if (status == BluetoothGatt.GATT_SUCCESS) "Warte auf Pulsdaten …" else "Anmeldung abgelehnt (Status $status)")
                main.removeCallbacks(noDataTimeout)
                main.postDelayed(noDataTimeout, 10_000)
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleValue(ch, value)
        }

        // bis Android 12
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                ch.value?.let { handleValue(ch, it) }
            }
        }
    }

    private fun handleValue(ch: BluetoothGattCharacteristic, v: ByteArray) {
        if (ch.uuid != HR_MEASUREMENT || v.size < 2) return
        val sixteenBit = (v[0].toInt() and 0x01) != 0
        val bpm = if (sixteenBit && v.size >= 3) {
            (v[1].toInt() and 0xFF) or ((v[2].toInt() and 0xFF) shl 8)
        } else {
            v[1].toInt() and 0xFF
        }
        main.post {
            lastBpm = bpm
            lastUpdate = System.currentTimeMillis()
            if (hrScanning || prefs.getString("hrAddress", null) != address) {
                // Diese Kennung liefert Puls -> als eigene Uhr merken
                prefs.edit().putString("hrAddress", address)
                    .putString("hrName", gatt?.device?.name ?: prefs.getString("hrName", null)).apply()
            }
            stopHrScan()
            setStatus(null)
            render()
        }
    }

    // ---------- Overlay ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (overlay != null) return
        val d = resources.displayMetrics.density
        val bpmText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            text = "♥ …"
        }
        val bar = ZoneBar(this).apply { zone = -1.0 }
        val statusText = TextView(this).apply {
            textSize = 10f
            setTextColor(Color.LTGRAY)
            maxWidth = (160 * d).toInt()
        }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(Color.argb(190, 20, 20, 20))
            }
            addView(bpmText)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * d).toInt() })
            addView(statusText)
        }
        statusView = statusText
        bpmView = bpmText
        zoneBar = bar
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", (16 * d).toInt())
            y = prefs.getInt("y", (120 * d).toInt())
        }

        // Fenster mit dem Finger verschieben
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y; touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - touchX).toInt()
                    params.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(view, params); true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.edit().putInt("x", params.x).putInt("y", params.y).apply(); true
                }
                else -> false
            }
        }
        wm.addView(view, params)
        overlay = view
    }

    /** Kleiner Statustext unter dem Balken; null blendet ihn aus. */
    private fun setStatus(msg: String?) {
        statusView?.apply {
            text = msg ?: ""
            visibility = if (msg == null) android.view.View.GONE else android.view.View.VISIBLE
        }
    }

    private fun render() {
        val zone = Zones.zoneOf(lastBpm, Zones.bounds(prefs))
        val color = if (zone >= 1) Zones.colors[zone.toInt().coerceIn(1, 5) - 1] else Color.WHITE
        val label = if (zone >= 1) "  Z" + String.format(java.util.Locale.GERMANY, "%.1f", zone) else ""
        setText("♥ $lastBpm$label", color, zone)
    }

    private fun setText(text: String, color: Int, zone: Double = -1.0) {
        bpmView?.apply {
            this.text = text
            setTextColor(color)
        }
        zoneBar?.zone = zone
    }

    // ---------- Benachrichtigung ----------

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Puls-Overlay", NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(
            this, 0, Intent(this, HrService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_heart)
            .setContentTitle("Puls-Overlay aktiv")
            .setContentText("Herzfrequenz der Uhr wird angezeigt")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .build()
    }
}
