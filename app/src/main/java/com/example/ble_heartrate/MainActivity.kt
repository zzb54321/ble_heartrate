package com.example.ble_heartrate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
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
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.view.View
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var tvThresholdInfo: TextView
    private lateinit var tvLiveReadings: TextView
    private lateinit var scrollLiveReadings: ScrollView
    private lateinit var rootContainer: View
    private lateinit var etVibrationPeriod: EditText
    private lateinit var lvRules: ListView
    private lateinit var cbAlertSound: CheckBox
    private lateinit var spAlertTone: Spinner
    private lateinit var tvLastDevice: TextView
    private lateinit var cbAlertOverlay: CheckBox
    private lateinit var cbAlertBackground: CheckBox
    private lateinit var cbAlertVibration: CheckBox
    private lateinit var btnToggleAlerts: Button
    private lateinit var chartHeartRate: HeartRateChartView
    private lateinit var headerChart: TextView
    private lateinit var headerLiveReadings: TextView
    private lateinit var headerAlertOptions: TextView
    private lateinit var alertOptionsContainer: View
    private lateinit var uiPrefs: android.content.SharedPreferences
    private var backgroundAlertEnabled = false
    private var backgroundBlinkOn = false
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            backgroundBlinkOn = !backgroundBlinkOn
            rootContainer.setBackgroundColor(
                Color.parseColor(if (backgroundBlinkOn) COLOR_BACKGROUND_ALERT else COLOR_BACKGROUND_NORMAL)
            )
            blinkHandler.postDelayed(this, BACKGROUND_BLINK_INTERVAL_MS)
        }
    }
    private lateinit var ruleAdapter: RuleAdapter

    private val liveReadings = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanning = false
    private val scannedDevices = mutableListOf<BluetoothDevice>()
    private val deviceNames = mutableListOf<String>()
    private val devicePriority = mutableListOf<Boolean>()
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
        const val EXTRA_THRESHOLD = "threshold"
        private const val SCAN_PERIOD_MS = 10_000L
        private const val REQUEST_PERMISSIONS = 1001
        private const val MAX_LIVE_READINGS = 50
        private const val PRIORITY_DEVICE_KEYWORD = "vivo"
        private const val REQUEST_OVERLAY_PERMISSION = 1002
        private const val COLOR_BACKGROUND_NORMAL = "#F5F5F5"
        private const val COLOR_BACKGROUND_ALERT = "#F44336"
        /** Half period of the alerting background blink. */
        private const val BACKGROUND_BLINK_INTERVAL_MS = 500L
        private const val UI_PREF_NAME = "ble_heartrate_ui_prefs"
        private const val PREF_CHART_EXPANDED = "chart_expanded"
        private const val PREF_LOG_EXPANDED = "log_expanded"
        private const val PREF_OPTIONS_EXPANDED = "options_expanded"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as HeartRateService.LocalBinder
            heartRateService = binder.getService()
            bound = true
            refreshRules()
            refreshAlertOptions()
            refreshLastDevice()
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
                    val threshold = intent?.getIntExtra(EXTRA_THRESHOLD, -1) ?: -1

                    if (hr >= 0) {
                        tvHeartRate.text = getString(R.string.heart_rate_bpm, hr)
                        tvHeartRate.setTextColor(
                            if (exceeded) Color.parseColor("#FF6D00") else Color.parseColor("#E53935")
                        )
                        appendLiveReading(hr, threshold, exceeded)
                        chartHeartRate.addValue(hr)
                        applyAlertBackground(exceeded)
                    }
                    if (exceeded && threshold > 0) {
                        tvThresholdInfo.text = getString(R.string.threshold_alerting, threshold)
                    } else if (threshold >= 0) {
                        updateThresholdInfo(threshold)
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
        tvThresholdInfo = findViewById(R.id.tvThresholdInfo)
        tvLiveReadings = findViewById(R.id.tvLiveReadings)
        scrollLiveReadings = findViewById(R.id.scrollLiveReadings)
        rootContainer = findViewById(R.id.rootContainer)
        etVibrationPeriod = findViewById(R.id.etVibrationPeriod)
        lvRules = findViewById(R.id.lvRules)
        cbAlertSound = findViewById(R.id.cbAlertSound)
        spAlertTone = findViewById(R.id.spAlertTone)
        tvLastDevice = findViewById(R.id.tvLastDevice)
        cbAlertOverlay = findViewById(R.id.cbAlertOverlay)
        cbAlertBackground = findViewById(R.id.cbAlertBackground)
        cbAlertVibration = findViewById(R.id.cbAlertVibration)
        btnToggleAlerts = findViewById(R.id.btnToggleAlerts)
        chartHeartRate = findViewById(R.id.chartHeartRate)
        headerChart = findViewById(R.id.headerChart)
        headerLiveReadings = findViewById(R.id.headerLiveReadings)
        headerAlertOptions = findViewById(R.id.headerAlertOptions)
        alertOptionsContainer = findViewById(R.id.alertOptionsContainer)
        uiPrefs = getSharedPreferences(UI_PREF_NAME, Context.MODE_PRIVATE)
        setupCollapsibleSections()
        allowInnerListScrolling(lvRules)
        allowInnerListScrolling(lvDevices)
        setupAlertOptions()
        applyWindowInsets()

        ruleAdapter = RuleAdapter(this, ::toggleRule, ::deleteRule)
        lvRules.adapter = ruleAdapter
        lvRules.emptyView = findViewById(R.id.tvNoRules)
        refreshRules()

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, deviceNames)
        lvDevices.adapter = listAdapter
        lvDevices.choiceMode = ListView.CHOICE_MODE_SINGLE
        lvDevices.setOnItemClickListener { _, _, position, _ ->
            if (position !in scannedDevices.indices) return@setOnItemClickListener
            selectedDevice = scannedDevices[position]
            lvDevices.setItemChecked(position, true)
            tvStatus.text = getString(R.string.device_selected, deviceNames[position])
        }

        btnScan.setOnClickListener { if (scanning) stopScan() else startScan() }

        btnConnect.setOnClickListener {
            val device = selectedDevice
            if (device == null) {
                Toast.makeText(this, R.string.select_device_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val service = heartRateService
            if (service == null) {
                Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
                startAndBindService()
                return@setOnClickListener
            }
            // Connecting while a scan is running is unreliable on many chipsets, so the
            // scan is stopped first — the discovered list is kept intact.
            if (scanning) stopScan(keepStatus = true)
            service.connectToDevice(device)
            refreshLastDevice()
            tvStatus.text = getString(R.string.connecting)
        }

        btnDisconnect.setOnClickListener {
            heartRateService?.disconnect()
        }

        btnSetThreshold.setOnClickListener { addRuleFromInput() }

        tvLastDevice.setOnClickListener { connectToLastDevice() }
        refreshLastDevice()

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

        // Before Android 12 the system silently returns zero scan results when the
        // location master switch is off, which looks exactly like "no devices nearby".
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationServiceEnabled()) {
            Toast.makeText(this, R.string.enable_location_services, Toast.LENGTH_LONG).show()
            tvStatus.text = getString(R.string.enable_location_services)
            openLocationSettings()
            return
        }

        scannedDevices.clear()
        deviceNames.clear()
        devicePriority.clear()
        lvDevices.clearChoices()
        listAdapter.notifyDataSetChanged()
        selectedDevice = null

        val scanner = bluetoothAdapter!!.bluetoothLeScanner ?: run {
            Toast.makeText(this, R.string.scanner_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        scanning = true
        btnScan.text = getString(R.string.stop_scan)
        tvStatus.text = getString(R.string.scanning_devices)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .build()

        scanner.startScan(null, settings, leScanCallback)
        Handler(Looper.getMainLooper()).postDelayed({ if (scanning) stopScan() }, SCAN_PERIOD_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(keepStatus: Boolean = false) {
        if (!scanning) return
        scanning = false
        btnScan.text = getString(R.string.scan)
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback)
        if (keepStatus) return
        tvStatus.text = if (scannedDevices.isEmpty()) {
            getString(R.string.no_ble_devices_found)
        } else {
            getString(R.string.scan_complete)
        }
    }

    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            addScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::addScanResult)
        }

        @SuppressLint("MissingPermission")
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, getString(R.string.scan_failed, errorCode), Toast.LENGTH_LONG).show()
                scanning = false
                btnScan.text = getString(R.string.scan)
                tvStatus.text = getString(R.string.scan_failed, errorCode)
                try {
                    bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
                } catch (_: Exception) {
                    // scanner already released
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun addScanResult(result: ScanResult) {
        // getDeviceName accesses device.name / device.address which require BLUETOOTH_CONNECT
        // on API 31+; safe to call on a background thread since permissions were verified.
        val device = result.device
        val label = formatDeviceLabel(device, result)
        val hrServiceUuid = HeartRateService.heartRateServiceParcelUuid()
        val advertisesHeartRate = result.scanRecord?.serviceUuids?.contains(hrServiceUuid) == true
        val priority = isPriorityDevice(getDeviceName(device))
        // All list mutations must happen on the main thread to prevent ConcurrentModificationException
        // (startScan() clears the lists on the main thread while callbacks arrive on a Binder thread).
        runOnUiThread {
            val existingIndex = scannedDevices.indexOfFirst { it.address == device.address }
            if (existingIndex >= 0) {
                deviceNames[existingIndex] = label
                devicePriority[existingIndex] = priority
            } else {
                scannedDevices.add(device)
                deviceNames.add(label)
                devicePriority.add(priority)
            }
            if (advertisesHeartRate) {
                tvStatus.text = getString(R.string.heart_rate_device_found)
            }
            sortDevices()
            listAdapter.notifyDataSetChanged()
        }
    }

    /** Devices whose name contains "vivo" are pinned to the top of the list. */
    private fun isPriorityDevice(name: String): Boolean =
        name.contains(PRIORITY_DEVICE_KEYWORD, ignoreCase = true)

    /**
     * Keep priority (vivo) devices first while preserving discovery order otherwise.
     * Reordering is suspended once the user has picked a device so that entries do not
     * shift under the finger while the scan is still running.
     */
    private fun sortDevices() {
        if (selectedDevice != null) return
        val order = scannedDevices.indices.sortedWith(
            compareByDescending<Int> { devicePriority[it] }.thenBy { it }
        )
        if (order == scannedDevices.indices.toList()) return

        val devices = order.map { scannedDevices[it] }
        val names = order.map { deviceNames[it] }
        val priorities = order.map { devicePriority[it] }

        scannedDevices.clear(); scannedDevices.addAll(devices)
        deviceNames.clear(); deviceNames.addAll(names)
        devicePriority.clear(); devicePriority.addAll(priorities)

        val selected = selectedDevice
        lvDevices.clearChoices()
        if (selected != null) {
            val idx = scannedDevices.indexOfFirst { it.address == selected.address }
            if (idx >= 0) lvDevices.setItemChecked(idx, true)
        }
    }

    /** Show the previously connected device so it can be reconnected with one tap. */
    private fun refreshLastDevice() {
        val name = heartRateService?.lastDeviceName()
        tvLastDevice.text = if (name.isNullOrBlank()) {
            getString(R.string.last_device_none)
        } else {
            getString(R.string.last_device, name)
        }
    }

    /** Reconnect directly to the stored device without scanning first. */
    @SuppressLint("MissingPermission")
    private fun connectToLastDevice() {
        val service = heartRateService
        if (service == null) {
            Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
            startAndBindService()
            return
        }
        val address = service.lastDeviceAddress()
        if (address.isNullOrBlank()) {
            Toast.makeText(this, R.string.last_device_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasRequiredPermissions()) {
            Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_SHORT).show()
            checkAndRequestPermissions()
            return
        }
        val adapter = resolveBluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, R.string.enable_bluetooth, Toast.LENGTH_SHORT).show()
            return
        }
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (device == null) {
            Toast.makeText(this, R.string.last_device_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        // Connecting while scanning is unreliable on many chipsets.
        if (scanning) stopScan(keepStatus = true)
        selectedDevice = device
        service.connectToDevice(device)
        refreshLastDevice()
        val label = service.lastDeviceName() ?: address
        tvStatus.text = getString(R.string.connecting_last_device, label)
    }

    private fun resolveBluetoothAdapter(): BluetoothAdapter? {
        @Suppress("DEPRECATION")
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.also { bluetoothAdapter = it } ?: bluetoothAdapter
    }

    private fun addRuleFromInput() {
        val bpm = etThreshold.text.toString().trim().toIntOrNull()
        if (bpm == null || bpm <= 0) {
            Toast.makeText(this, R.string.invalid_threshold, Toast.LENGTH_SHORT).show()
            return
        }
        val periodInput = etVibrationPeriod.text.toString().trim()
        val period = if (periodInput.isEmpty()) {
            HeartRateService.DEFAULT_PERIOD_MS
        } else {
            periodInput.toLongOrNull() ?: -1L
        }
        if (period < HeartRateService.MIN_PERIOD_MS || period > HeartRateService.MAX_PERIOD_MS) {
            Toast.makeText(
                this,
                getString(
                    R.string.invalid_period,
                    HeartRateService.MIN_PERIOD_MS,
                    HeartRateService.MAX_PERIOD_MS
                ),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val service = heartRateService
        if (service == null) {
            Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
            startAndBindService()
            return
        }
        service.addRule(HeartRateService.AlertRule(bpm, period))
        refreshRules()
        etThreshold.text.clear()
        etVibrationPeriod.text.clear()
        Toast.makeText(this, getString(R.string.rule_added, bpm, period), Toast.LENGTH_SHORT).show()
    }

    private fun deleteRule(rule: HeartRateService.AlertRule) {
        val service = heartRateService ?: return
        service.removeRule(rule.bpm)
        refreshRules()
        Toast.makeText(this, getString(R.string.rule_removed, rule.bpm), Toast.LENGTH_SHORT).show()
    }

    /** Temporarily switch a single rule off (or back on) without deleting it. */
    private fun toggleRule(rule: HeartRateService.AlertRule) {
        val service = heartRateService ?: return
        val enabled = !rule.enabled
        service.setRuleEnabled(rule.bpm, enabled)
        refreshRules()
        val message = if (enabled) R.string.rule_enabled_toast else R.string.rule_disabled_toast
        Toast.makeText(this, getString(message, rule.bpm), Toast.LENGTH_SHORT).show()
    }

    /**
     * Wire the three collapsible sections (chart, live log, alert options) and restore
     * the last expand/collapse state.
     */
    private fun setupCollapsibleSections() {
        bindSection(headerChart, chartHeartRate, R.string.heart_rate_chart, PREF_CHART_EXPANDED)
        bindSection(headerLiveReadings, scrollLiveReadings, R.string.live_readings, PREF_LOG_EXPANDED)
        bindSection(headerAlertOptions, alertOptionsContainer, R.string.alert_options, PREF_OPTIONS_EXPANDED)
    }

    private fun bindSection(header: TextView, content: View, titleRes: Int, prefKey: String) {
        var expanded = uiPrefs.getBoolean(prefKey, true)
        applySectionState(header, content, titleRes, expanded)
        header.setOnClickListener {
            expanded = !expanded
            uiPrefs.edit().putBoolean(prefKey, expanded).apply()
            applySectionState(header, content, titleRes, expanded)
        }
    }

    private fun applySectionState(header: TextView, content: View, titleRes: Int, expanded: Boolean) {
        val title = getString(titleRes)
        header.text = if (expanded) {
            getString(R.string.section_expanded, title)
        } else {
            getString(R.string.section_collapsed, title)
        }
        content.visibility = if (expanded) View.VISIBLE else View.GONE
    }

    /**
     * The layout is inside a ScrollView, so the parent must stop intercepting touches
     * while a nested list is being dragged, otherwise the inner list cannot scroll.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun allowInnerListScrolling(list: ListView) {
        list.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_MOVE ->
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                else -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
    }

    private fun setupAlertOptions() {
        setupToneSpinner()

        cbAlertSound.setOnClickListener {
            val service = heartRateService
            if (service == null) {
                cbAlertSound.isChecked = !cbAlertSound.isChecked
                Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
                startAndBindService()
                return@setOnClickListener
            }
            service.setSoundEnabled(cbAlertSound.isChecked)
        }

        cbAlertVibration.setOnClickListener {
            val service = heartRateService
            if (service == null) {
                cbAlertVibration.isChecked = !cbAlertVibration.isChecked
                Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
                startAndBindService()
                return@setOnClickListener
            }
            service.setVibrationEnabled(cbAlertVibration.isChecked)
        }

        btnToggleAlerts.setOnClickListener {
            val service = heartRateService
            if (service == null) {
                Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
                startAndBindService()
                return@setOnClickListener
            }
            val enabled = !service.areAlertsEnabled()
            service.setAlertsEnabled(enabled)
            updateAlertsToggle(enabled)
            val message = if (enabled) R.string.alerts_resumed_toast else R.string.alerts_paused_toast
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            if (!enabled) applyAlertBackground(false)
        }

        cbAlertOverlay.setOnClickListener {
            val service = heartRateService
            if (service == null) {
                cbAlertOverlay.isChecked = !cbAlertOverlay.isChecked
                Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
                startAndBindService()
                return@setOnClickListener
            }
            if (cbAlertOverlay.isChecked && !canDrawOverlays()) {
                cbAlertOverlay.isChecked = false
                requestOverlayPermission()
                return@setOnClickListener
            }
            service.setOverlayEnabled(cbAlertOverlay.isChecked)
        }

        cbAlertBackground.setOnClickListener {
            val service = heartRateService
            if (service == null) {
                cbAlertBackground.isChecked = !cbAlertBackground.isChecked
                Toast.makeText(this, R.string.service_not_ready, Toast.LENGTH_SHORT).show()
                startAndBindService()
                return@setOnClickListener
            }
            backgroundAlertEnabled = cbAlertBackground.isChecked
            service.setBackgroundEnabled(backgroundAlertEnabled)
            if (!backgroundAlertEnabled) applyAlertBackground(false)
        }
    }

    /** Populate the alert tone drop-down and forward the selection to the service. */
    private fun setupToneSpinner() {
        val labels = resources.getStringArray(R.array.alert_tone_names).toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spAlertTone.adapter = adapter
        spAlertTone.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                heartRateService?.setToneIndex(position)
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

    /** Mirror the option state persisted by the service into the checkboxes. */
    private fun refreshAlertOptions() {
        val service = heartRateService ?: return
        cbAlertSound.isChecked = service.isSoundEnabled()
        spAlertTone.setSelection(service.getToneIndex())
        cbAlertVibration.isChecked = service.isVibrationEnabled()
        updateAlertsToggle(service.areAlertsEnabled())
        cbAlertOverlay.isChecked = service.isOverlayEnabled()
        backgroundAlertEnabled = service.isBackgroundEnabled()
        cbAlertBackground.isChecked = backgroundAlertEnabled
        if (!backgroundAlertEnabled) applyAlertBackground(false)
    }

    private fun updateAlertsToggle(enabled: Boolean) {
        btnToggleAlerts.setText(if (enabled) R.string.alerts_pause else R.string.alerts_resume)
    }

    /**
     * While alerting, the background blinks red so the alert is noticeable even from
     * the corner of the eye; otherwise the neutral background is restored.
     */
    private fun applyAlertBackground(exceeded: Boolean) {
        blinkHandler.removeCallbacks(blinkRunnable)
        if (backgroundAlertEnabled && exceeded) {
            backgroundBlinkOn = false
            blinkHandler.post(blinkRunnable)
        } else {
            backgroundBlinkOn = false
            rootContainer.setBackgroundColor(Color.parseColor(COLOR_BACKGROUND_NORMAL))
        }
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            startActivityForResult(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.fromParts("package", packageName, null)
                ),
                REQUEST_OVERLAY_PERMISSION
            )
        } catch (_: Exception) {
            // Settings activity unavailable on this device
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION && canDrawOverlays()) {
            cbAlertOverlay.isChecked = true
            heartRateService?.setOverlayEnabled(true)
        }
    }

    /** Re-render the rule list and the threshold summary from the service state. */
    private fun refreshRules() {
        val rules = heartRateService?.getRules().orEmpty()
        ruleAdapter.submit(rules)
        updateThresholdInfo(rules.firstOrNull { it.enabled }?.bpm ?: 0)
    }

    private fun updateThresholdInfo(threshold: Int) {
        chartHeartRate.setThreshold(threshold)
        tvThresholdInfo.text = if (threshold > 0) {
            getString(R.string.threshold_current, threshold)
        } else {
            getString(R.string.threshold_none)
        }
    }

    /** Append the newest heart rate sample to the live reading log. */
    private fun appendLiveReading(hr: Int, threshold: Int, exceeded: Boolean) {
        val time = timeFormat.format(Date())
        val line = if (exceeded && threshold > 0) {
            getString(R.string.live_reading_item_alert, time, hr, threshold)
        } else {
            getString(R.string.live_reading_item, time, hr)
        }
        liveReadings.addLast(line)
        while (liveReadings.size > MAX_LIVE_READINGS) liveReadings.removeFirst()
        tvLiveReadings.text = liveReadings.joinToString("\n")
        scrollLiveReadings.post { scrollLiveReadings.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * Android 15 lays activities out edge-to-edge, which makes the status bar / title bar
     * overlap the first rows of content. Add the system bar insets to the base padding.
     */
    private fun applyWindowInsets() {
        val basePadding = rootContainer.paddingTop
        rootContainer.setOnApplyWindowInsetsListener { view, insets ->
            val top: Int
            val bottom: Int
            val left: Int
            val right: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout()
                )
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right
            } else {
                @Suppress("DEPRECATION")
                run {
                    top = insets.systemWindowInsetTop
                    bottom = insets.systemWindowInsetBottom
                    left = insets.systemWindowInsetLeft
                    right = insets.systemWindowInsetRight
                }
            }
            view.setPadding(
                basePadding + left,
                basePadding + top,
                basePadding + right,
                basePadding + bottom
            )
            insets
        }
        rootContainer.requestApplyInsets()
    }

    private fun getDeviceName(device: BluetoothDevice): String {
        return if (hasBluetoothConnectPermission()) {
            device.name ?: device.address
        } else {
            device.address
        }
    }

    private fun formatDeviceLabel(device: BluetoothDevice, result: ScanResult): String {
        val name = getDeviceName(device)
        val hrServiceUuid = HeartRateService.heartRateServiceParcelUuid()
        val advertisesHeartRate = result.scanRecord?.serviceUuids?.contains(hrServiceUuid) == true
        val typeLabel = if (advertisesHeartRate) {
            getString(R.string.heart_rate_device_tag)
        } else {
            getString(R.string.ble_device_tag)
        }
        return getString(R.string.device_list_item, typeLabel, name, result.rssi)
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

    /** Permissions that are nice to have but must not block scanning or the service. */
    private fun optionalPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun checkAndRequestPermissions() {
        val missing = (requiredPermissions() + optionalPermissions()).filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        }
    }

    private fun isLocationServiceEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun openLocationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (_: Exception) {
            // Settings activity unavailable on this device
        }
    }

    private fun openAppSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        } catch (_: Exception) {
            // Settings activity unavailable on this device
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
            // Optional permissions (e.g. notifications) must not prevent the service from starting.
            if (hasRequiredPermissions()) {
                startAndBindService()
            } else {
                Toast.makeText(this, R.string.permissions_required, Toast.LENGTH_LONG).show()
                tvStatus.text = getString(R.string.permissions_required)
                openAppSettings()
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
        blinkHandler.removeCallbacks(blinkRunnable)
    }
}
