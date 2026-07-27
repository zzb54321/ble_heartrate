package com.example.ble_heartrate

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
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

/**
 * Foreground service that manages the BLE GATT connection to a heart rate device,
 * monitors the received heart rate against a set of configurable thresholds, and
 * vibrates with the frequency configured for the highest exceeded threshold.
 */
class HeartRateService : Service() {

    companion object {
        const val HEART_RATE_SERVICE_UUID = "0000180D-0000-1000-8000-00805f9b34fb"
        private const val HEART_RATE_MEASUREMENT_UUID = "00002A37-0000-1000-8000-00805f9b34fb"
        private const val CLIENT_CHARACTERISTIC_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"

        private const val CHANNEL_ID = "HeartRateChannel"
        private const val NOTIFICATION_ID = 1

        private const val PREF_NAME = "ble_heartrate_prefs"
        private const val PREF_THRESHOLD = "threshold"
        private const val PREF_RULES = "threshold_rules"
        private const val PREF_SOUND_ENABLED = "alert_sound_enabled"
        private const val PREF_OVERLAY_ENABLED = "alert_overlay_enabled"
        private const val PREF_BACKGROUND_ENABLED = "alert_background_enabled"
        private const val PREF_VIBRATION_ENABLED = "alert_vibration_enabled"
        private const val PREF_TONE_INDEX = "alert_tone_index"
        private const val PREF_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val PREF_LAST_DEVICE_NAME = "last_device_name"
        private const val DEFAULT_THRESHOLD = 100

        /** Bounds for the configurable vibration period (one buzz + one pause). */
        const val MIN_PERIOD_MS = 200L
        const val MAX_PERIOD_MS = 5_000L
        const val DEFAULT_PERIOD_MS = 1_000L

        // Some OEM ROMs silently drop indefinitely repeating waveforms, so the pattern is
        // re-issued periodically instead of relying on the repeat index.
        private const val VIBRATION_REARM_INTERVAL_MS = 100L
        private const val VIBRATION_PULSES_PER_BURST = 3

        /** Volume (0-100) used for the optional alert tone. */
        private const val TONE_VOLUME = 100

        /**
         * Alarm-style tone used for the audible alert. TONE_CDMA_HIGH_L is a sharp,
         * high pitched beep that cuts through ambient noise much better than the
         * previously used TONE_PROP_BEEP.
         */
        private const val ALERT_TONE_TYPE = ToneGenerator.TONE_CDMA_HIGH_L

        /**
         * Selectable alert tones; the user picks one from the drop-down next to the
         * "alert tone" option. The first entry is the default sharp beep.
         */
        val ALERT_TONES: List<Int> = listOf(
            ALERT_TONE_TYPE,
            ToneGenerator.TONE_CDMA_ABBR_ALERT,
            ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,
            ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK,
            ToneGenerator.TONE_SUP_RINGTONE,
            ToneGenerator.TONE_PROP_BEEP2
        )

        fun heartRateServiceParcelUuid(): ParcelUuid = ParcelUuid.fromString(HEART_RATE_SERVICE_UUID)
    }

    /**
     * A heart-rate alert rule: once the measured rate goes above [bpm], vibrate with a
     * cycle length of [periodMs] (shorter period = higher vibration frequency).
     * The highest matching rule wins, so several rules can escalate the alert.
     */
    data class AlertRule(val bpm: Int, val periodMs: Long, val enabled: Boolean = true) {
        fun normalized(): AlertRule =
            AlertRule(bpm, periodMs.coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS), enabled)

        /** Waveform for one burst: alternating buzz / pause of half the period each. */
        fun pattern(): LongArray {
            val safePeriod = periodMs.coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
            val on = safePeriod / 2
            val off = safePeriod - on
            val pattern = LongArray(1 + VIBRATION_PULSES_PER_BURST * 2)
            pattern[0] = 0L
            for (i in 0 until VIBRATION_PULSES_PER_BURST) {
                pattern[1 + i * 2] = on
                pattern[2 + i * 2] = off
            }
            return pattern
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): HeartRateService = this@HeartRateService
    }

    private val binder = LocalBinder()

    @Volatile private var gatt: BluetoothGatt? = null
    private var vibrator: Vibrator? = null
    private var toneGenerator: ToneGenerator? = null
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    @Volatile private var isVibrating = false
    private val vibrationHandler = Handler(Looper.getMainLooper())
    private val vibrationRunnable = object : Runnable {
        override fun run() {
            val rule = activeRule ?: return
            if (!isVibrating) return
            val pattern = rule.pattern()
            playVibrationPattern(pattern)
            playAlertTones(pattern)
            vibrationHandler.postDelayed(this, pattern.sum() + VIBRATION_REARM_INTERVAL_MS)
        }
    }

    private var currentHeartRate = 0
    /** Alert rules sorted ascending by BPM. */
    private val rules = mutableListOf<AlertRule>()
    @Volatile private var activeRule: AlertRule? = null

    @Volatile private var soundEnabled = false
    @Volatile private var toneIndex = 0
    @Volatile private var vibrationEnabled = true
    /** Temporary master switch; not persisted so alerts resume after a restart. */
    @Volatile private var alertsEnabled = true
    @Volatile private var overlayEnabled = false
    @Volatile private var backgroundEnabled = false
    private val alertHandler = Handler(Looper.getMainLooper())
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        try {
            prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            loadRules()
            loadOptions()

            notificationManager = getSystemService(NotificationManager::class.java)
            vibrator = resolveVibrator()
            windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

            createNotificationChannel()
            // Android 14 (API 34) enforces that services with a declared foregroundServiceType
            // must call the 3-argument startForeground() specifying the matching type;
            // the constant FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE is available from API 31.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(getString(R.string.notification_idle)),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_idle)))
            }
        } catch (t: Throwable) {
            try { broadcastError(t) } catch (_: Throwable) {}
            stopSelf()
        }
    }

    private fun broadcastError(t: Throwable) {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        sendBroadcast(Intent(MainActivity.ACTION_SERVICE_ERROR).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_ERROR, "${t::class.java.name}: ${t.message}\n$sw")
        })
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopAlert()
        releaseToneGenerator()
        closeGatt()
    }

    // -------------------------------------------------------------------------
    // Public API (called from MainActivity via binder)
    // -------------------------------------------------------------------------

    /** Connect to the given BLE device and subscribe to its heart-rate notifications. */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        rememberDevice(device)
        closeGatt()
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Persist the device so it can be reconnected with a single tap next time. */
    @SuppressLint("MissingPermission")
    private fun rememberDevice(device: BluetoothDevice) {
        val name = try {
            device.name
        } catch (_: SecurityException) {
            null
        } ?: device.address
        prefs.edit()
            .putString(PREF_LAST_DEVICE_ADDRESS, device.address)
            .putString(PREF_LAST_DEVICE_NAME, name)
            .apply()
    }

    /** Address of the most recently connected device (null when there is none). */
    fun lastDeviceAddress(): String? = prefs.getString(PREF_LAST_DEVICE_ADDRESS, null)

    /** Display name of the most recently connected device (null when there is none). */
    fun lastDeviceName(): String? = prefs.getString(PREF_LAST_DEVICE_NAME, null)

    /** Disconnect from the currently connected device. */
    fun disconnect() {
        closeGatt()
        stopVibrating()
        broadcastStatus(getString(R.string.disconnected))
        updateNotification(getString(R.string.notification_idle))
    }

    /** Replace all alert rules and persist them. */
    fun setRules(newRules: List<AlertRule>) {
        rules.clear()
        rules.addAll(
            newRules.map { it.normalized() }
                .filter { it.bpm > 0 }
                .distinctBy { it.bpm }
                .sortedBy { it.bpm }
        )
        saveRules()
        // Re-evaluate vibration with the new rules
        evaluateThreshold()
        if (currentHeartRate > 0) {
            val active = activeRule
            broadcastHeartRate(currentHeartRate, active != null)
            updateNotification(
                getString(R.string.notification_monitoring, currentHeartRate, lowestThreshold())
            )
        }
    }

    /** Add or replace a single rule (rules are keyed by BPM). */
    fun addRule(rule: AlertRule) {
        setRules(rules.filter { it.bpm != rule.bpm } + rule)
    }

    /** Temporarily enable or disable a single rule without deleting it. */
    fun setRuleEnabled(bpm: Int, enabled: Boolean) {
        setRules(rules.map { if (it.bpm == bpm) it.copy(enabled = enabled) else it })
    }

    /** Remove the rule with the given BPM threshold. */
    fun removeRule(bpm: Int) {
        setRules(rules.filter { it.bpm != bpm })
    }

    /** Current alert rules, sorted ascending by BPM. */
    fun getRules(): List<AlertRule> = rules.toList()

    /** Lowest active threshold (0 when no rule is enabled). */
    fun lowestThreshold(): Int = rules.firstOrNull { it.enabled }?.bpm ?: 0

    /** Threshold of the rule that is currently triggering the alert (0 when idle). */
    fun activeThreshold(): Int = activeRule?.bpm ?: 0

    /** Latest heart rate received from the device (0 when nothing received yet). */
    fun getCurrentHeartRate(): Int = currentHeartRate

    /** Whether the alert vibration is currently running. */
    fun isAlerting(): Boolean = isVibrating

    /** Whether the alert tone is enabled. */
    fun isSoundEnabled(): Boolean = soundEnabled

    /** Whether the alert vibration is enabled. */
    fun isVibrationEnabled(): Boolean = vibrationEnabled

    /** Whether alerts are currently allowed to fire at all. */
    fun areAlertsEnabled(): Boolean = alertsEnabled

    /** Whether the screen border overlay alert is enabled. */
    fun isOverlayEnabled(): Boolean = overlayEnabled

    /** Whether the alerting background colour is enabled. */
    fun isBackgroundEnabled(): Boolean = backgroundEnabled

    /** Enable/disable the alert tone that follows the vibration frequency. */
    fun setSoundEnabled(enabled: Boolean) {
        soundEnabled = enabled
        prefs.edit().putBoolean(PREF_SOUND_ENABLED, enabled).apply()
        if (!enabled) stopAlertTones()
    }

    /** Index into [ALERT_TONES] of the currently selected alert tone. */
    fun getToneIndex(): Int = toneIndex

    /** Select the alert tone used while alerting. */
    fun setToneIndex(index: Int) {
        if (index !in ALERT_TONES.indices) return
        toneIndex = index
        prefs.edit().putInt(PREF_TONE_INDEX, index).apply()
        stopAlertTones()
    }

    /** Enable/disable the alert vibration. */
    fun setVibrationEnabled(enabled: Boolean) {
        vibrationEnabled = enabled
        prefs.edit().putBoolean(PREF_VIBRATION_ENABLED, enabled).apply()
        if (!enabled) vibrator?.cancel()
        evaluateThreshold()
    }

    /** Temporarily suspend or resume every alert channel without touching the rules. */
    fun setAlertsEnabled(enabled: Boolean) {
        alertsEnabled = enabled
        if (enabled) evaluateThreshold() else stopVibrating()
    }

    /** Enable/disable the screen border overlay shown while alerting. */
    fun setOverlayEnabled(enabled: Boolean) {
        overlayEnabled = enabled
        prefs.edit().putBoolean(PREF_OVERLAY_ENABLED, enabled).apply()
        if (enabled && isVibrating) showOverlay() else hideOverlay()
    }

    /** Enable/disable the alerting background colour used by the activity. */
    fun setBackgroundEnabled(enabled: Boolean) {
        backgroundEnabled = enabled
        prefs.edit().putBoolean(PREF_BACKGROUND_ENABLED, enabled).apply()
    }

    // -------------------------------------------------------------------------
    // BLE GATT callback
    // -------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    broadcastStatus(getString(R.string.connected))
                    updateNotification(getString(R.string.notification_connected))
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    broadcastStatus(getString(R.string.disconnected))
                    updateNotification(getString(R.string.notification_idle))
                    stopVibrating()
                    closeGatt()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val heartRateService = gatt.getService(UUID.fromString(HEART_RATE_SERVICE_UUID))
                ?: return handleUnsupportedDevice(gatt)

            val hrChar = heartRateService.getCharacteristic(UUID.fromString(HEART_RATE_MEASUREMENT_UUID))
                ?: return handleUnsupportedDevice(gatt)

            // Enable local notifications
            gatt.setCharacteristicNotification(hrChar, true)

            // Write CCCD to enable server-side notifications
            val descriptor = hrChar.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG_UUID))
                ?: return
            enableNotificationDescriptor(gatt, descriptor)

            broadcastStatus(getString(R.string.subscribed))
        }

        // API < 33 callback
        @Suppress("DEPRECATION")
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleHeartRateCharacteristic(characteristic.value)
            }
        }

        // API 33+ callback
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleHeartRateCharacteristic(value)
        }
    }

    // -------------------------------------------------------------------------
    // Heart rate processing
    // -------------------------------------------------------------------------

    private fun handleHeartRateCharacteristic(value: ByteArray) {
        if (value.isEmpty()) return
        val hr = parseHeartRate(value)
        currentHeartRate = hr
        evaluateThreshold()
        broadcastHeartRate(hr, activeRule != null)
        updateNotification(
            getString(R.string.notification_monitoring, hr, lowestThreshold())
        )
    }

    /**
     * Parse the Heart Rate Measurement characteristic value per Bluetooth spec.
     * Byte 0 flags: bit 0 → 0 = UINT8 format, 1 = UINT16 format.
     */
    private fun parseHeartRate(bytes: ByteArray): Int {
        val flags = bytes[0].toInt() and 0xFF
        return if (flags and 0x01 != 0) {
            // UINT16 little-endian at offset 1
            if (bytes.size >= 3) {
                (bytes[1].toInt() and 0xFF) or ((bytes[2].toInt() and 0xFF) shl 8)
            } else 0
        } else {
            // UINT8 at offset 1
            if (bytes.size >= 2) bytes[1].toInt() and 0xFF else 0
        }
    }

    // -------------------------------------------------------------------------
    // Threshold / vibration logic
    // -------------------------------------------------------------------------

    private fun evaluateThreshold() {
        if (!alertsEnabled) {
            stopVibrating()
            return
        }
        val matching = rules.lastOrNull { it.enabled && currentHeartRate > it.bpm }
        if (matching == null || currentHeartRate <= 0) {
            stopVibrating()
            return
        }
        if (matching != activeRule) {
            // A different rule took over — restart with its vibration frequency.
            stopVibrating()
            activeRule = matching
        }
        startVibrating()
    }

    private fun startVibrating() {
        if (isVibrating) return
        if (activeRule == null) return
        val vib = vibrator
        val canVibrate = vibrationEnabled && vib != null && vib.hasVibrator()
        if (vibrationEnabled && (vib == null || !vib.hasVibrator())) {
            broadcastStatus(getString(R.string.vibrator_unavailable))
        }
        // The optional tone / overlay alerts still work without vibration.
        if (!canVibrate && !soundEnabled && !overlayEnabled && !backgroundEnabled) return
        isVibrating = true
        if (overlayEnabled) showOverlay()
        vibrationHandler.removeCallbacks(vibrationRunnable)
        vibrationHandler.post(vibrationRunnable)
    }

    private fun playVibrationPattern(pattern: LongArray) {
        if (!vibrationEnabled) return
        val vib = vibrator ?: return
        // USAGE_ALARM keeps the alert audible/tactile even when the device is in
        // silent mode or the app is not in the foreground.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(pattern, -1), alarmAudioAttributes())
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(pattern, -1, alarmAudioAttributes())
        }
    }

    private fun alarmAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    private fun stopVibrating() {
        vibrationHandler.removeCallbacks(vibrationRunnable)
        activeRule = null
        stopAlertTones()
        hideOverlay()
        if (!isVibrating) return
        isVibrating = false
        vibrator?.cancel()
    }

    /** Stop every alert channel (vibration, tone and overlay). */
    private fun stopAlert() = stopVibrating()

    // -------------------------------------------------------------------------
    // Optional alert: tone following the vibration frequency
    // -------------------------------------------------------------------------

    /** Beep once per vibration pulse so the tone frequency matches the vibration. */
    private fun playAlertTones(pattern: LongArray) {
        if (!soundEnabled) return
        val tone = ensureToneGenerator() ?: return
        var delay = 0L
        for (i in 0 until VIBRATION_PULSES_PER_BURST) {
            val on = pattern[1 + i * 2]
            val off = pattern[2 + i * 2]
            alertHandler.postDelayed({
                if (isVibrating && soundEnabled) {
                    try {
                        tone.startTone(currentToneType(), on.toInt())
                    } catch (_: Exception) {
                        // Tone generator may have been released concurrently.
                    }
                }
            }, delay)
            delay += on + off
        }
    }

    /** Tone constant of the currently selected alert tone. */
    private fun currentToneType(): Int =
        ALERT_TONES.getOrElse(toneIndex) { ALERT_TONE_TYPE }

    private fun stopAlertTones() {
        alertHandler.removeCallbacksAndMessages(null)
        try {
            toneGenerator?.stopTone()
        } catch (_: Exception) {
            // Nothing to stop.
        }
    }

    private fun ensureToneGenerator(): ToneGenerator? {
        toneGenerator?.let { return it }
        return try {
            ToneGenerator(AudioManager.STREAM_ALARM, TONE_VOLUME).also { toneGenerator = it }
        } catch (_: RuntimeException) {
            // Some devices fail to allocate the tone generator; degrade gracefully.
            broadcastStatus(getString(R.string.alert_sound_unavailable))
            null
        }
    }

    private fun releaseToneGenerator() {
        try {
            toneGenerator?.release()
        } catch (_: Exception) {
            // Already released.
        }
        toneGenerator = null
    }

    // -------------------------------------------------------------------------
    // Optional alert: screen border overlay
    // -------------------------------------------------------------------------

    private fun canDrawOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun showOverlay() = runOnMain {
        if (!overlayEnabled) return@runOnMain
        addOverlayView()
    }

    private fun hideOverlay() = runOnMain { removeOverlayView() }

    /** Window operations must run on a thread with a Looper; GATT callbacks do not have one. */
    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else uiHandler.post(action)
    }

    private fun addOverlayView() {
        if (overlayView != null) return
        val wm = windowManager ?: return
        if (!canDrawOverlay()) {
            broadcastStatus(getString(R.string.overlay_permission_required))
            return
        }
        val view = View(this).apply { setBackgroundResource(R.drawable.alert_border) }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        try {
            wm.addView(view, params)
            overlayView = view
        } catch (_: Exception) {
            // Overlay rejected by the system (e.g. permission revoked meanwhile).
            overlayView = null
        }
    }

    private fun removeOverlayView() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
            // Already detached.
        }
    }

    // -------------------------------------------------------------------------
    // Broadcast helpers
    // -------------------------------------------------------------------------

    private fun broadcastHeartRate(hr: Int, exceeded: Boolean) {
        sendBroadcast(Intent(MainActivity.ACTION_HEART_RATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_HEART_RATE, hr)
            putExtra(MainActivity.EXTRA_THRESHOLD, if (exceeded) activeThreshold() else lowestThreshold())
            putExtra(MainActivity.EXTRA_THRESHOLD_EXCEEDED, exceeded)
        })
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(MainActivity.ACTION_HEART_RATE_UPDATE).apply {
            setPackage(packageName)
            putExtra(MainActivity.EXTRA_STATUS, status)
        })
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(contentText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun enableNotificationDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleUnsupportedDevice(gatt: BluetoothGatt) {
        stopVibrating()
        broadcastStatus(getString(R.string.heart_rate_service_not_found))
        updateNotification(getString(R.string.notification_idle))
        gatt.disconnect()
    }

    /** Load rules from preferences, migrating the legacy single-threshold setting. */
    private fun loadRules() {
        rules.clear()
        val json = prefs.getString(PREF_RULES, null)
        if (json.isNullOrBlank()) {
            val legacy = prefs.getInt(PREF_THRESHOLD, DEFAULT_THRESHOLD)
            if (legacy > 0) rules.add(AlertRule(legacy, DEFAULT_PERIOD_MS))
            saveRules()
            return
        }
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val bpm = obj.optInt("bpm", 0)
                if (bpm <= 0) continue
                rules.add(
                    AlertRule(
                        bpm,
                        obj.optLong("periodMs", DEFAULT_PERIOD_MS),
                        obj.optBoolean("enabled", true)
                    ).normalized()
                )
            }
        } catch (_: Exception) {
            // Corrupted preference — fall back to no rules rather than crashing.
            rules.clear()
        }
        rules.sortBy { it.bpm }
    }

    private fun loadOptions() {
        soundEnabled = prefs.getBoolean(PREF_SOUND_ENABLED, false)
        overlayEnabled = prefs.getBoolean(PREF_OVERLAY_ENABLED, false)
        backgroundEnabled = prefs.getBoolean(PREF_BACKGROUND_ENABLED, false)
        vibrationEnabled = prefs.getBoolean(PREF_VIBRATION_ENABLED, true)
        toneIndex = prefs.getInt(PREF_TONE_INDEX, 0).coerceIn(0, ALERT_TONES.size - 1)
    }

    private fun saveRules() {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("bpm", rule.bpm)
                    .put("periodMs", rule.periodMs)
                    .put("enabled", rule.enabled)
            )
        }
        prefs.edit().putString(PREF_RULES, array.toString()).apply()
    }

    private fun resolveVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        gatt?.close()
        gatt = null
    }
}
