package com.example.smartdoor

import android.Manifest
import android.Manifest.permission.BLUETOOTH_SCAN
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
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

class ArduinoDoorManager {

    private val context: Context

    constructor(context: Context) {
        this.context = context
        this.fixedTime = DEFAULT_FIXED_TIME
    }

    companion object {
        private const val TAG = "ArduinoDoor"
        private const val SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
        private const val FIXED_KEY_CHAR = "12345678-1234-5678-1234-56789abcdef1"
        private const val JWT_AUTH_CHAR = "12345678-1234-5678-1234-56789abcdef2"
        private const val TOTP_COMMAND_CHAR = "12345678-1234-5678-1234-56789abcdef3"
        private const val PREFS_NAME = "arduino_door_prefs"
        private const val KEY_FIXED_TIME = "fixed_time"
        private const val DEFAULT_FIXED_TIME = 1640995200L
    }

    private val prefs: SharedPreferences by lazy { createSecurePrefs() }
    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private var gatt: BluetoothGatt? = null
    private var currentScanCallback: ScanCallback? = null
    private var fixedKey: String? = null
    private var fixedTime: Long

    private fun createSecurePrefs() = try {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_NAME, masterKey, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "Secure prefs failed", e)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isConfigured(callback: (Boolean) -> Unit) {
        fixedTime = prefs.getLong(KEY_FIXED_TIME, DEFAULT_FIXED_TIME)
        callback(fixedTime != DEFAULT_FIXED_TIME)
    }

    fun saveFixedTime(time: Long) {
        prefs.edit().putLong(KEY_FIXED_TIME, time).apply()
        fixedTime = time
        Log.d(TAG, "Fixed time сохранён: $time")
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun openDoor() {
        if (!hasPermissions()) {
            Log.e(TAG, "❌ Нет разрешений BLE")
            return
        }

        if (fixedTime == DEFAULT_FIXED_TIME) {
            Log.e(TAG, "❌ Fixed time не настроен")
            return
        }

        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "❌ Нет BluetoothAdapter")
            return
        }

        if (!adapter.isEnabled) {
            Log.e(TAG, "❌ Bluetooth выключен")
            return
        }

        Log.d(TAG, "🔍 Поиск Arduino Lock...")
        startScan()
    }

    private fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return

        val filter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(UUID.fromString(SERVICE_UUID)))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        currentScanCallback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                Log.d(TAG, "📡 Найден Arduino: ${result.device.name ?: "Unknown"}")
                scanner.stopScan(this)
                connect(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
            }
        }

        @SuppressLint("MissingPermission")
        scanner.startScan(listOf(filter), settings, currentScanCallback)
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        Log.d(TAG, "🔗 Подключение к ${device.name ?: device.address}")
        gatt?.close()
        gatt = device.connectGatt(context, false, object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "✅ Подключено!")
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "🔌 Отключено")
                        gatt?.close()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "🔍 Сервисы найдены")
                    readFixedKey(gatt)
                } else {
                    Log.e(TAG, "Ошибка поиска сервисов: $status")
                    gatt?.disconnect()
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS &&
                    characteristic.uuid.toString().contains(FIXED_KEY_CHAR)) {
                    handleFixedKeyRead(gatt, characteristic)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                Log.d(TAG, "📝 Запись выполнена: ${characteristic.uuid}")

                if (characteristic.uuid.toString().contains(JWT_AUTH_CHAR)) {
                    Log.d(TAG, "📤 Отправляем TOTP команду")
                    sendTotpCommand(gatt)
                } else if (characteristic.uuid.toString().contains(TOTP_COMMAND_CHAR)) {
                    Log.d(TAG, "✅ Дверь должна открыться!")
                    gatt.disconnect()
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun readFixedKey(gatt: BluetoothGatt) {
        val service = gatt.getService(UUID.fromString(SERVICE_UUID))
        val fixedKeyChar = service?.getCharacteristic(UUID.fromString(FIXED_KEY_CHAR))

        if (fixedKeyChar != null) {
            @SuppressLint("MissingPermission")
            gatt.readCharacteristic(fixedKeyChar)
            Log.d(TAG, "📖 Читаем fixed_key...")
        } else {
            Log.e(TAG, "❌ fixed_key характеристика не найдена")
            gatt.disconnect()
        }
    }

    private fun handleFixedKeyRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        fixedKey = characteristic.getStringValue(0)
        Log.d(TAG, "✅ Fixed key: ${fixedKey?.take(8)}...")

        sendJwtAuth(gatt)
    }

    private fun sendJwtAuth(gatt: BluetoothGatt) {
        val jwt = generateSimpleJwt(fixedKey!!)
        val service = gatt.getService(UUID.fromString(SERVICE_UUID))
        val jwtChar = service?.getCharacteristic(UUID.fromString(JWT_AUTH_CHAR))

        if (jwtChar != null) {
            jwtChar.value = jwt.toByteArray(Charsets.UTF_8)
            @SuppressLint("MissingPermission")
            gatt.writeCharacteristic(jwtChar)
            Log.d(TAG, "📤 JWT отправлен (${jwt.length} символов)")
        }
    }

    private fun sendTotpCommand(gatt: BluetoothGatt) {
        val totp = generateTotp8(fixedKey!!, fixedTime)
        val command = "$totp|$fixedTime|OPEN"

        val service = gatt.getService(UUID.fromString(SERVICE_UUID))
        val totpChar = service?.getCharacteristic(UUID.fromString(TOTP_COMMAND_CHAR))

        if (totpChar != null) {
            totpChar.value = command.toByteArray(Charsets.UTF_8)
            @SuppressLint("MissingPermission")
            gatt.writeCharacteristic(totpChar)
            Log.d(TAG, "📤 TOTP команда: $command")
        }
    }

    private fun generateSimpleJwt(fixedKey: String): String {
        val now = System.currentTimeMillis() / 1000
        val header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}"
        val payload = "{\"sub\":\"arduino\",\"iat\":$now,\"exp\":${now + 3600}}"

        val unsignedToken = "${base64UrlEncode(header)}.${base64UrlEncode(payload)}"
        val signature = hmacSha256(unsignedToken, fixedKey)

        return "$unsignedToken.$signature"
    }

    private fun generateTotp8(secretBase32: String, fixedTime: Long): String {
        return try {
            val key = base32Decode(secretBase32)
            val timeStep = fixedTime / 30L  // 30-секундные шаги

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeStep).array())

            val offset = (hash[hash.size - 1].toInt() and 0x0F)
            var code = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)

            code = code and 0x7FFFFFFF
            String.format("%08d", code % 100000000)
        } catch (e: Exception) {
            Log.e(TAG, "TOTP ошибка", e)
            "00000000"
        }
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = StringBuilder()

        input.uppercase().forEach { c ->
            val idx = alphabet.indexOf(c)
            if (idx >= 0) {
                bits.append(String.format("%5s", idx).replace(' ', '0'))
            }
        }

        val bytes = ByteArray((bits.length + 7) / 8)
        for (i in bytes.indices) {
            val start = i * 8
            if (start < bits.length) {
                val end = min(start + 8, bits.length)
                val chunk = bits.substring(start, end)
                bytes[i] = chunk.toInt(2).toByte()
            }
        }
        return bytes
    }

    private fun base64UrlEncode(input: String): String {
        return Base64.encodeToString(
            input.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        ).replace("=", "")
    }

    private fun hmacSha256(message: String, secret: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            val hash = mac.doFinal(message.toByteArray())
            Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                .replace("=", "")
        } catch (e: Exception) {
            Log.e(TAG, "HMAC ошибка", e)
            ""
        }
    }

}
