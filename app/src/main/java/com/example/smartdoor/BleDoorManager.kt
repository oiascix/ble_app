package com.example.smartdoor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.nio.ByteBuffer
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BleDoorManager(private val context: Context) {

    companion object {
        private const val TAG = "BleDoorManager"
        private val SERVICE_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
        private val CHAR_UUID: UUID =
            UUID.fromString("12345678-1234-5678-1234-56789abcdef1")
        private const val PREFS_NAME = "door_prefs"
        private const val KEY_SECRET = "secret_key"
    }

    private val prefs: SharedPreferences by lazy { createPrefs() }
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }
    private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null

    private fun createPrefs(): SharedPreferences {
        return try {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKey,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun isConfigured(callback: (Boolean) -> Unit) {
        callback(prefs.contains(KEY_SECRET))
    }

    fun saveSecret(secret: String) {
        prefs.edit().putString(KEY_SECRET, secret).apply()
    }

    fun hasPermissions(): Boolean {
        val loc = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (loc != PackageManager.PERMISSION_GRANTED) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val connect = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            )
            scan == PackageManager.PERMISSION_GRANTED &&
                    connect == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun unlockDoor() {
        if (!hasPermissions()) {
            Log.e(TAG, "Нет permissions")
            return
        }

        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "Нет BluetoothAdapter")
            return
        }

        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth выключен")
            return
        }

        val secret = prefs.getString(KEY_SECRET, null) ?: run {
            Log.e(TAG, "Секрет не настроен")
            return
        }

        startScan(secret)
    }

    private fun startScan(secret: String) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Log.e(TAG, "Нет BluetoothLeScanner")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                scanner.stopScan(this)
                connect(result.device, secret)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
            }
        }

        @SuppressLint("MissingPermission")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice, secret: String) {
        gatt?.close()
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    gatt.disconnect()
                    return
                }
                val service = gatt.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHAR_UUID)
                if (characteristic == null) {
                    gatt.disconnect()
                    return
                }

                val code = generateTOTP(secret)
                characteristic.value = code.toByteArray(Charsets.UTF_8)

                gatt.writeCharacteristic(characteristic)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "Код отправлен успешно")
                } else {
                    Log.e(TAG, "Ошибка записи: $status")
                }
                gatt.disconnect()
            }
        })
    }

    private fun generateTOTP(secretBase32: String): String {
        return try {
            val key = base32Decode(secretBase32)
            val timestep = System.currentTimeMillis() / 1000 / 30
            val data = ByteBuffer.allocate(8).putLong(timestep).array()

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(data)

            val offset = hash[hash.size - 1].toInt() and 0x0F
            val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)
            val otp = binary % 1_000_000
            String.format("%06d", otp)
        } catch (e: Exception) {
            Log.e(TAG, "TOTP error", e)
            "000000"
        }
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = StringBuilder()

        input.uppercase().forEach { c ->
            val idx = alphabet.indexOf(c)
            if (idx >= 0) {
                bits.append(idx.toString(2).padStart(5, '0'))
            }
        }

        val bytes = ArrayList<Byte>()
        var i = 0
        while (i + 8 <= bits.length) {
            val byte = bits.substring(i, i + 8).toInt(2).toByte()
            bytes.add(byte)
            i += 8
        }
        return bytes.toByteArray()
    }

    fun destroy() {
        scanCallback?.let { cb ->
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
        }
        gatt?.close()
        gatt = null
        scanCallback = null
    }
}
