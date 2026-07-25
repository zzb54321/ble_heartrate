package com.example.ble_heartrate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.view.View
import android.widget.ScrollView
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter

class MainActivity : Activity() {

    private lateinit var tvHeartRate: TextView
    private lateinit var tvStatus: TextView
    private lateinit var etThreshold: EditText
    private lateinit var btnScan: Button
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnSetThreshold: Button
    private lateinit var lvDevices: ListView
    private lateinit var tvError: android.widget.TextView
    private lateinit var scrollError: ScrollView

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanning = false
    private val scannedDevices = mutableListOf<BluetoothDevice>()
    private val deviceNames = mutableListOf<String>()
    private var selectedDevice: BluetoothDevice? = null
    private lateinit var listAdapter: ArrayAdapter<String>

    private var heartRateService: HeartRateService? = null
    private var bound = false

    companion object {
        const val ACTION_HEART_RATE_UPDATE = "com.example.ble_heartrate.HEART_RATE_UPDATE"
        const val EXTRA_HEART_RATE = "heart_rate"
        const val EXTRA_STATUS = "status"
        const val EXTRA_THRESHOLD_EXCEEDED = "threshold_exceeded"
        const val ACTION_SERVICE_ERROR = "com.example.ble_heartrate.SERVICE_ERROR"
        const val EXTRA_ERROR = "error"
        private const val SCAN_PERIOD_MS = 10_000L
        private const val REQUEST_PERMISSIONS = 1001
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HeartRateService.LocalBinder
            heartRateService = binder.getService()
            bound = true
            val saved = heartRateService?.getThreshold() ?: 0
            if (saved > 0) etThreshold.setText(saved.toString())
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            heartRateService = null
            bound = false
        }
    }

    private val heartRateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SERVICE_ERROR -> {
                    val err = intent.getStringExtra(EXTRA_ERROR) ?: "Unknown service error"
                    showError("SERVICE ERROR:\n$err")
                }
                else -> {
                    val hr = intent?.getIntExtra(EXTRA_HEART_RATE, -1) ?: -1
                    val status = intent?.getStringExtra(EXTRA_STATUS)
                    val exceeded = intent?.getBooleanExtra(EXTRA_THRESHOLD_EXCEEDED, false) ?: false

                    if (hr >= 0) {
                        tvHeartRate.text = getString(R.string.heart_rate_bpm, hr)
                        tvHeartRate.setTextColor(
                            if (exceeded) Color.parseColor("#FF6D00") else Color.parseColor("#E53935")
                        )
                    }
                    status?.let { tvStatus.text = it }
                }
            }
        }
    }

    private fun showError(msg: String) {
        runOnUiThread {
            scrollError.visibility = View.VISIBLE
            tvError.text = msg
        }
    }

    private fun stackTrace(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize error display first so it is available even if later init throws
        tvError = findViewById(R.id.tvError)
        scrollError = findViewById(R.id.scrollError)

        try {
            initUi()
        } catch (t: Throwable) {
            showError("ACTIVITY onCreate CRASH:\n" + stackTrace(t))
        }
    }

    private fun initUi() {
        tvHeartRate = findViewById(R.id.tvHeartRate)
        tvStatus = findViewById(R.id.tvStatus)
        etThreshold = findViewById(R.id.etThreshold)
        btnScan = findViewById(R.id.btnScan)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnSetThreshold = findViewById(R.id.btnSetThreshold)
        lvDevices = findViewById(R.id.lvDevices)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, deviceNames)
        lvDevices.adapter = listAdapter
        lvDevices.choiceMode = ListView.CHOICE_MODE_SINGLE
        lvDevices.setOnItemClickListener { _, _, position, _ ->
            selectedDevice = scannedDevices[position]
            tvStatus.text = getString(R.string.device_selected, deviceNames[position])
        }

        btnScan.setOnClickListener { if (scanning) stopScan() else startScan() }

        btnConnect.setOnClickListener {
            val device = selectedDevice
            if (device != null) {
                heartRateService?.connectToDevice(device)
                tvStatus.text = getString(R.string.connecting)
            } else {
                Toast.makeText(this, R.string.select_device_first, Toast.LENGTH_SHORT).show()
            }
        }

        btnDisconnect.setOnClickListener {
            heartRateService?.disconnect()
        }

        btnSetThreshold.setOnClickListener {
            val input = etThreshold.text.toString().trim()
            val threshold = input.toIntOrNull()
            if (threshold != null && threshold > 0) {
                heartRateService?.setThreshold(threshold)
                Toast.makeText(this, getString(R.string.threshold_set, threshold), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.invalid_threshold, Toast.LENGTH_SHORT).show()
            }
        }

        // On Android 14+, startForeground() with connectedDevice type requires BLUETOOTH_CONNECT
        // to be granted; defer service startup until after permissions are obtained.
        if (hasRequiredPermissions()) {
            startAndBindService()
        } else {
            checkAndRequestPermissions()
        }

        // Register a single instance with both actions to avoid double-registration / leak
        val combinedFilter = IntentFilter().apply {
            addAction(ACTION_HEART_RATE_UPDATE)
            addAction(ACTION_SERVICE_ERROR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(heartRateReceiver, combinedFilter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(heartRateReceiver, combinedFilter)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_SHORT).show()
            checkAndRequestPermissions()
            return
        }
        // Use string-based getSystemService for API 21+ compatibility (Class<T> overload is API 23+)
        @Suppress("DEPRECATION")
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: run {
            Toast.makeText(this, R.string.enable_bluetooth, Toast.LENGTH_SHORT).show()
            return
        }
        bluetoothAdapter = btManager.adapter

        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Toast.makeText(this, R.string.enable_bluetooth, Toast.LENGTH_SHORT).show()
            return
        }

        scannedDevices.clear()
        deviceNames.clear()
        listAdapter.notifyDataSetChanged()
        selectedDevice = null

        val scanner = bluetoothAdapter!!.bluetoothLeScanner ?: return
        scanning = true
        btnScan.text = getString(R.string.stop_scan)

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(HeartRateService.HEART_RATE_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(filters, settings, leScanCallback)
        Handler(Looper.getMainLooper()).postDelayed({ if (scanning) stopScan() }, SCAN_PERIOD_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanning = false
        btnScan.text = getString(R.string.scan)
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // getDeviceName accesses device.name / device.address which require BLUETOOTH_CONNECT
            // on API 31+; safe to call on a background thread since permissions were verified.
            val device = result.device
            val name = getDeviceName(device)
            // All list mutations must happen on the main thread to prevent ConcurrentModificationException
            // (startScan() clears the lists on the main thread while callbacks arrive on a Binder thread).
            runOnUiThread {
                if (scannedDevices.none { it.address == device.address }) {
                    scannedDevices.add(device)
                    deviceNames.add(name)
                    listAdapter.notifyDataSetChanged()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, getString(R.string.scan_failed, errorCode), Toast.LENGTH_SHORT).show()
                scanning = false
                btnScan.text = getString(R.string.scan)
            }
        }
    }

    private fun getDeviceName(device: BluetoothDevice): String {
        return if (hasBluetoothConnectPermission()) {
            device.name ?: device.address
        } else {
            device.address
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requiredPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun checkAndRequestPermissions() {
        val missing = requiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun startAndBindService() {
        try {
            val serviceIntent = Intent(this, HeartRateService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            if (!bound) {
                bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        } catch (t: Throwable) {
            showError("startAndBindService CRASH:\n" + stackTrace(t))
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isEmpty() || grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
            } else {
                // All permissions granted — safe to start the foreground service now
                startAndBindService()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        try {
            unregisterReceiver(heartRateReceiver)
        } catch (_: IllegalArgumentException) {
            // not registered
        }
        if (scanning) stopScan()
    }
}
