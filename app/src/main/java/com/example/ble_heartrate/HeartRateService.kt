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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.ServiceInfo
import java.util.UUID

/**
 * Foreground service that manages the BLE GATT connection to a heart rate device,
 * monitors the received heart rate against a configurable threshold, and vibrates
 * continuously when the heart rate exceeds the threshold.
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
        private const val DEFAULT_THRESHOLD = 100

        // Vibration pattern: [delay, vibrate, pause, vibrate, pause …] repeat from index 0
        private val VIBRATION_PATTERN = longArrayOf(0L, 600L, 400L)
    }

    inner class LocalBinder : Binder() {
        fun getService(): HeartRateService = this@HeartRateService
    }

    private val binder = LocalBinder()

    private var gatt: BluetoothGatt? = null
    private var vibrator: Vibrator? = null
    private var isVibrating = false

    private var currentHeartRate = 0
    private var threshold = DEFAULT_THRESHOLD

    private lateinit var prefs: SharedPreferences
    private lateinit var notificationManager: NotificationManager

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        threshold = prefs.getInt(PREF_THRESHOLD, DEFAULT_THRESHOLD)

        notificationManager = getSystemService(NotificationManager::class.java)
        vibrator = resolveVibrator()

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
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        stopVibrating()
        closeGatt()
    }

    // -------------------------------------------------------------------------
    // Public API (called from MainActivity via binder)
    // -------------------------------------------------------------------------

    /** Connect to the given BLE device and subscribe to its heart-rate notifications. */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        closeGatt()
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    /** Disconnect from the currently connected device. */
    fun disconnect() {
        closeGatt()
        stopVibrating()
        broadcastStatus(getString(R.string.disconnected))
        updateNotification(getString(R.string.notification_idle))
    }

    /** Update the heart-rate threshold and persist it. */
    fun setThreshold(bpm: Int) {
        threshold = bpm
        prefs.edit().putInt(PREF_THRESHOLD, bpm).apply()
        // Re-evaluate vibration with the new threshold
        evaluateThreshold()
    }

    /** Return the currently configured threshold (0 means none set). */
    fun getThreshold(): Int = threshold

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

            val hrChar = gatt
                .getService(UUID.fromString(HEART_RATE_SERVICE_UUID))
                ?.getCharacteristic(UUID.fromString(HEART_RATE_MEASUREMENT_UUID))
                ?: return

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
        val exceeded = threshold > 0 && hr > threshold
        broadcastHeartRate(hr, exceeded)
        updateNotification(
            getString(R.string.notification_monitoring, hr, threshold)
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
        if (threshold <= 0 || currentHeartRate <= 0) return
        if (currentHeartRate > threshold) {
            startVibrating()
        } else {
            stopVibrating()
        }
    }

    private fun startVibrating() {
        if (isVibrating) return
        isVibrating = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(VIBRATION_PATTERN, 0)
        }
    }

    private fun stopVibrating() {
        if (!isVibrating) return
        isVibrating = false
        vibrator?.cancel()
    }

    // -------------------------------------------------------------------------
    // Broadcast helpers
    // -------------------------------------------------------------------------

    private fun broadcastHeartRate(hr: Int, exceeded: Boolean) {
        sendBroadcast(Intent(MainActivity.ACTION_HEART_RATE_UPDATE).apply {
            putExtra(MainActivity.EXTRA_HEART_RATE, hr)
            putExtra(MainActivity.EXTRA_THRESHOLD_EXCEEDED, exceeded)
        })
    }

    private fun broadcastStatus(status: String) {
        sendBroadcast(Intent(MainActivity.ACTION_HEART_RATE_UPDATE).apply {
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
