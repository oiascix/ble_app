package com.example.smartdoor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner  // ✅ ДОБАВЛЕН
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

class ArduinoDoorManager(private val context: Context) {

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

    private lateinit var prefs: SharedPreferences
    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }

    private var currentScanner: BluetoothLeScanner? = null  // ✅ Теперь тип известен
    private var currentScanCallback: ScanCallback? = null
    private var gatt: BluetoothGatt? = null
    private var fixedKey: String? = null
    private var fixedTime: Long = DEFAULT_FIXED_TIME

    init {
        prefs = createSecurePrefs()
    }

    private fun createSecurePrefs(): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                PREFS_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "Secure prefs failed, using regular", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun isConfigured(callback: (Boolean) -> Unit) {
        fixedTime = prefs.getLong(KEY_FIXED_TIME, DEFAULT_FIXED_TIME)
        callback(fixedTime != DEFAULT_FIXED_TIME)
    }

    fun saveFixedTime(time: Long) {
        prefs.edit().putLong(KEY_FIXED_TIME, time).apply()
        fixedTime = time
        Log.d(TAG, "✅ Fixed time сохранён: $time")
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    fun openDoor() {
        if (!hasPermissions()) {
            Log.e(TAG, "❌ Нет разрешений BLE")
            return
        }

        if (fixedTime == DEFAULT_FIXED_TIME) {
            Log.e(TAG, "❌ Настройте fixed_time в настройках")
            return
        }

        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "❌ BluetoothAdapter недоступен")
            return
        }

        if (!adapter.isEnabled) {
            Log.e(TAG, "❌ Включите Bluetooth")
            return
        }

        Log.d(TAG, "🔍 Начинаем поиск Arduino Lock...")
        startScan()
    }

    private fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Log.e(TAG, "❌ BluetoothLeScanner недоступен")
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(SERVICE_UUID))  // ✅ ParcelUuid.fromString()
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        currentScanner = scanner

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val deviceName = result.device.name ?: "Unknown"
                Log.d(TAG, "📡 Найден Arduino: $deviceName (${result.device.address})")

                currentScanner?.stopScan(this)
                currentScanCallback = null

                connect(result.device)
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "❌ Scan failed: $errorCode")
            }
        }

        currentScanCallback = callback

        @SuppressLint("MissingPermission")
        try {
            scanner.startScan(listOf(filter), settings, callback)
            Log.d(TAG, "🔍 Сканирование запущено...")
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Ошибка запуска сканирования", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        Log.d(TAG, "🔗 Подключение к ${device.name ?: device.address}")

        gatt?.close()
        gatt = null

        val gattCallback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gattParam: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "✅ Подключение установлено")
                        gattParam.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "🔌 Отключено от Arduino")
                        gattParam.close()
                        synchronized(this@ArduinoDoorManager) {
                            this@ArduinoDoorManager.gatt = null
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gattParam: BluetoothGatt, status: Int) {
                synchronized(this@ArduinoDoorManager) {
                    this@ArduinoDoorManager.gatt = gattParam
                }

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "✅ Сервисы обнаружены")
                    readFixedKey(gattParam)
                } else {
                    Log.e(TAG, "❌ Ошибка поиска сервисов: $status")
                    gattParam.disconnect()
                }
            }

            override fun onCharacteristicRead(
                gattParam: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val charUuid = characteristic.uuid.toString()
                    if (charUuid.contains(FIXED_KEY_CHAR)) {
                        handleFixedKeyRead(gattParam, characteristic)
                    }
                } else {
                    Log.e(TAG, "❌ Ошибка чтения характеристики: $status")
                }
            }

            override fun onCharacteristicWrite(
                gattParam: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val charUuid = characteristic.uuid.toString()
                Log.d(TAG, "📝 Запись выполнена: $charUuid (status: $status)")

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    if (charUuid.contains(JWT_AUTH_CHAR)) {
                        sendTotpCommand(gattParam)
                    } else if (charUuid.contains(TOTP_COMMAND_CHAR)) {
                        Log.d(TAG, "🎉 Дверь должна открыться!")
                        gattParam.disconnect()
                    }
                }
            }
        }

        gatt = device.connectGatt(context, false, gattCallback)
    }

    private fun readFixedKey(gatt: BluetoothGatt) {
        val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: run {
            Log.e(TAG, "❌ Сервис не найден")
            gatt.disconnect()
            return
        }

        val fixedKeyChar = service.getCharacteristic(UUID.fromString(FIXED_KEY_CHAR)) ?: run {
            Log.e(TAG, "❌ Характеристика fixed_key не найдена")
            gatt.disconnect()
            return
        }

        @SuppressLint("MissingPermission")
        gatt.readCharacteristic(fixedKeyChar)
        Log.d(TAG, "📖 Чтение fixed_key...")
    }

    private fun handleFixedKeyRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        fixedKey = characteristic.getStringValue(0)
        Log.d(TAG, "✅ Fixed key получен: ${fixedKey?.take(8)}...")
        sendJwtAuth(gatt)
    }

    private fun sendJwtAuth(gatt: BluetoothGatt) {
        if (fixedKey == null) {
            Log.e(TAG, "❌ Fixed key не установлен")
            return
        }

        val jwt = generateSimpleJwt(fixedKey!!)
        val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: return
        val jwtChar = service.getCharacteristic(UUID.fromString(JWT_AUTH_CHAR)) ?: return

        jwtChar.value = jwt.toByteArray(Charsets.UTF_8)
        @SuppressLint("MissingPermission")
        gatt.writeCharacteristic(jwtChar)
        Log.d(TAG, "📤 JWT отправлен (${jwt.length} символов)")
    }

    private fun sendTotpCommand(gatt: BluetoothGatt) {
        if (fixedKey == null) return

        val totp = generateTotp8(fixedKey!!, fixedTime)
        val command = "$totp|$fixedTime|OPEN"

        val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: return
        val totpChar = service.getCharacteristic(UUID.fromString(TOTP_COMMAND_CHAR)) ?: return

        totpChar.value = command.toByteArray(Charsets.UTF_8)
        @SuppressLint("MissingPermission")
        gatt.writeCharacteristic(totpChar)
        Log.d(TAG, "📤 TOTP команда: $command")
    }

    private fun generateSimpleJwt(fixedKey: String): String {
        val now = System.currentTimeMillis() / 1000
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val payload = """{"sub":"arduino","iat":$now,"exp":${now + 3600}}"""

        val unsignedToken = "${base64UrlEncode(header)}.${base64UrlEncode(payload)}"
        val signature = hmacSha256(unsignedToken, fixedKey)
        return "$unsignedToken.$signature"
    }

    private fun generateTotp8(secretBase32: String, fixedTime: Long): String {
        return try {
            val key = base32Decode(secretBase32)
            val timeStep = fixedTime / 30L

            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeStep).array())

            val offset = hash[hash.size - 1].toInt() and 0x0F
            var code = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)

            code = code and 0x7FFFFFFF
            String.format("%08d", code % 100000000)
        } catch (e: Exception) {
            Log.e(TAG, "TOTP генерация ошибка", e)
            "00000000"
        }
    }

    private fun base32Decode(input: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = StringBuilder()

        input.uppercase(Locale.getDefault()).forEach { c ->
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
            mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val hash = mac.doFinal(message.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                .replace("=", "")
        } catch (e: Exception) {
            Log.e(TAG, "HMAC-SHA256 ошибка", e)
            ""
        }
    }

    fun destroy() {
        try {
            currentScanCallback?.let { callback ->
                currentScanner?.stopScan(callback)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки сканирования", e)
        }

        currentScanCallback = null
        currentScanner = null

        try {
            gatt?.close()
            gatt = null
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка закрытия GATT", e)
        }
        fixedKey = null
    }
}
