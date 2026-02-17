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
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

@SuppressLint("NewApi")
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
        private const val SCAN_TIMEOUT_MS = 10000L // 10 секунд
        private val UTF_8 = Charset.forName("UTF-8")
    }

    private val serviceUuid = UUID.fromString(SERVICE_UUID)
    private val fixedKeyUuid = UUID.fromString(FIXED_KEY_CHAR)
    private val jwtAuthUuid = UUID.fromString(JWT_AUTH_CHAR)
    private val totpCommandUuid = UUID.fromString(TOTP_COMMAND_CHAR)

    private val prefs: SharedPreferences by lazy { createSecurePrefs() }
    private val bluetoothManager: BluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }
    private var gatt: BluetoothGatt? = null
    private var currentScanCallback: ScanCallback? = null
    private var scanTimeoutHandler: Handler? = null
    private var fixedKey: String? = null
    private var fixedTime: Long = DEFAULT_FIXED_TIME
    private var isScanning = false

    // Флаг для отладки: если true - сканирует ВСЕ устройства
    private var debugScanAll = false

    init {
        fixedTime = prefs.getLong(KEY_FIXED_TIME, DEFAULT_FIXED_TIME)
        scanTimeoutHandler = Handler(Looper.getMainLooper())
        Log.d(TAG, "Инициализация менеджера. Fixed time: $fixedTime")
    }

    /**
     * Включить режим отладки (сканирование всех устройств)
     */
    fun enableDebugMode() {
        debugScanAll = true
        Log.w(TAG, "⚠️ Режим отладки ВКЛЮЧЕН: сканирование всех устройств")
    }

    /**
     * Выключить режим отладки
     */
    fun disableDebugMode() {
        debugScanAll = false
        Log.w(TAG, "⚠️ Режим отладки ВЫКЛЮЧЕН: фильтрация по UUID")
    }

    @RequiresApi(Build.VERSION_CODES.M)
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
            Log.w(TAG, "⚠️ Защищённые настройки недоступны", e)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun isConfigured(callback: (Boolean) -> Unit) {
        callback(fixedTime != DEFAULT_FIXED_TIME)
    }

    fun saveFixedTime(time: Long) {
        if (time < 0) {
            Log.w(TAG, "⚠️ Некорректное время: $time")
            return
        }
        prefs.edit().putLong(KEY_FIXED_TIME, time).apply()
        fixedTime = time
        Log.d(TAG, "✅ Время сохранено: $time")
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Проверка поддержки BLE на устройстве
     */
    fun isBleSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    }

    /**
     * Проверка включенности Bluetooth
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled ?: false
    }

    fun openDoor() {
        // Диагностика перед запуском
        if (!isBleSupported()) {
            Log.e(TAG, "❌ BLE не поддерживается на этом устройстве!")
            return
        }

        if (!isBluetoothEnabled()) {
            Log.e(TAG, "❌ Bluetooth отключен! Включите в настройках.")
            return
        }

        if (!hasPermissions()) {
            Log.e(TAG, "❌ Отсутствуют разрешения BLE")
            return
        }

        if (fixedTime == DEFAULT_FIXED_TIME) {
            Log.e(TAG, "❌ Не настроено время синхронизации")
            return
        }

        if (isScanning) {
            Log.w(TAG, "⚠️ Сканирование уже запущено, остановка...")
            stopScan()
        }

        Log.d(TAG, "🔍 Запуск поиска устройства...")
        startScan()
    }

    private fun startScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Log.e(TAG, "❌ BluetoothLeScanner недоступен")
            return
        }

        // Создаем фильтр ТОЛЬКО если не в режиме отладки
        val filters = if (debugScanAll) {
            emptyList()
        } else {
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(serviceUuid))
                    .build()
            )
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0L)
            .build()

        val callback = object : ScanCallback() {
            private var deviceCount = 0

            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                deviceCount++
                val deviceName = result.device.name ?: "Unknown"
                val rssi = result.rssi
                val address = result.device.address

                // В режиме отладки выводим ВСЕ найденные устройства
                if (debugScanAll) {
                    Log.d(TAG, "📡 [$deviceCount] Устройство: $deviceName | RSSI: $rssi dBm | MAC: $address")

                    // Выводим все сервисы из рекламы
                    result.scanRecord?.serviceUuids?.forEach { uuid ->
                        Log.d(TAG, "   📡 Сервис в рекламе: ${uuid.uuid}")
                    }
                }

                // Если не в режиме отладки ИЛИ имя содержит "Arduino" - подключаемся
                if (!debugScanAll || deviceName.contains("Arduino", ignoreCase = true)) {
                    Log.d(TAG, "🎯 Целевое устройство найдено: $deviceName ($address)")
                    stopScan()
                    connect(result.device)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { result ->
                    onScanResult(0, result)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val errorDesc = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "SCAN_FAILED_ALREADY_STARTED"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "SCAN_FAILED_APPLICATION_REGISTRATION_FAILED"
                    SCAN_FAILED_INTERNAL_ERROR -> "SCAN_FAILED_INTERNAL_ERROR"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "SCAN_FAILED_FEATURE_UNSUPPORTED"
                    else -> "UNKNOWN_ERROR_$errorCode"
                }
                Log.e(TAG, "❌ Сканирование завершилось ошибкой: $errorDesc ($errorCode)")
                isScanning = false
                currentScanCallback = null
            }
        }

        currentScanCallback = callback
        isScanning = true

        try {
            @SuppressLint("MissingPermission")
            scanner.startScan(filters, settings, callback)
            Log.d(TAG, "✅ Сканирование запущено (фильтр: ${if (debugScanAll) "ВЫКЛ" else "UUID"})")

            // Устанавливаем таймаут
            scanTimeoutHandler?.postDelayed({
                if (isScanning) {
                    Log.w(TAG, "⏰ Таймаут сканирования (10 сек) - устройство не найдено")
                    stopScan()
                }
            }, SCAN_TIMEOUT_MS)

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Ошибка безопасности при сканировании", e)
            isScanning = false
            currentScanCallback = null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Неожиданная ошибка при сканировании", e)
            isScanning = false
            currentScanCallback = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try {
            currentScanCallback?.let { cb ->
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
                Log.d(TAG, "⏹️ Сканирование остановлено")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка при остановке сканирования", e)
        } finally {
            isScanning = false
            currentScanCallback = null
            scanTimeoutHandler?.removeCallbacksAndMessages(null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        Log.d(TAG, "🔗 Подключение к ${device.address}")

        gatt?.close()
        gatt = null

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gattParam: BluetoothGatt, status: Int, newState: Int) {
                synchronized(this@ArduinoDoorManager) {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "✅ GATT подключено (status: $status)")
                            gattParam.discoverServices()
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "🔌 GATT отключено (status: $status)")
                            gattParam.close()
                            this@ArduinoDoorManager.gatt = null
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gattParam: BluetoothGatt, status: Int) {
                synchronized(this@ArduinoDoorManager) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.e(TAG, "❌ Ошибка обнаружения сервисов: $status")
                        gattParam.disconnect()
                        return
                    }
                    this@ArduinoDoorManager.gatt = gattParam
                    Log.d(TAG, "✅ Сервисы обнаружены")

                    // Выводим все найденные сервисы для отладки
                    gattParam.services.forEach { service ->
                        Log.d(TAG, "   📡 Сервис: ${service.uuid}")
                        service.characteristics.forEach { char ->
                            Log.d(TAG, "      🔷 Характеристика: ${char.uuid} | Свойства: ${getPropertiesString(char.properties)}")
                        }
                    }

                    readFixedKey(gattParam)
                }
            }

            override fun onCharacteristicRead(
                gattParam: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "❌ Ошибка чтения: $status")
                    gattParam.disconnect()
                    return
                }
                if (characteristic.uuid == fixedKeyUuid) {
                    handleFixedKeyRead(gattParam, characteristic)
                }
            }

            override fun onCharacteristicWrite(
                gattParam: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "❌ Ошибка записи: $status")
                    gattParam.disconnect()
                    return
                }
                when (characteristic.uuid) {
                    jwtAuthUuid -> {
                        Log.d(TAG, "📤 JWT отправлен, отправка TOTP...")
                        sendTotpCommand(gattParam)
                    }
                    totpCommandUuid -> {
                        Log.d(TAG, "🎉 Команда открытия отправлена!")
                        gattParam.disconnect()
                    }
                }
            }
        }

        try {
            gatt = device.connectGatt(context, false, gattCallback)
            Log.d(TAG, "🔌 Создано GATT соединение")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка подключения", e)
            gattCallback.onConnectionStateChange(gatt ?: return, 0, BluetoothProfile.STATE_DISCONNECTED)
        }
    }

    private fun getPropertiesString(properties: Int): String {
        val flags = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) flags.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) flags.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) flags.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) flags.add("INDICATE")
        return flags.joinToString("|")
    }

    @SuppressLint("MissingPermission")
    private fun readFixedKey(gattParam: BluetoothGatt) {
        val service = gattParam.getService(serviceUuid) ?: run {
            Log.e(TAG, "❌ Сервис не найден!")
            gattParam.disconnect()
            return
        }

        val char = service.getCharacteristic(fixedKeyUuid) ?: run {
            Log.e(TAG, "❌ Характеристика fixed_key не найдена!")
            gattParam.disconnect()
            return
        }

        if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
            Log.e(TAG, "❌ Характеристика не поддерживает чтение")
            gattParam.disconnect()
            return
        }

        if (!gattParam.readCharacteristic(char)) {
            Log.e(TAG, "❌ Не удалось инициировать чтение")
            gattParam.disconnect()
        } else {
            Log.d(TAG, "📖 Чтение fixed_key...")
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleFixedKeyRead(gattParam: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        fixedKey = characteristic.getStringValue(0)?.trim()
        if (fixedKey.isNullOrBlank()) {
            Log.e(TAG, "❌ Пустой ключ!")
            gattParam.disconnect()
            return
        }
        Log.d(TAG, "✅ Fixed key получен (${fixedKey?.length} символов)")
        sendJwtAuth(gattParam)
    }

    @SuppressLint("MissingPermission")
    private fun sendJwtAuth(gattParam: BluetoothGatt) {
        val jwt = generateSimpleJwt(fixedKey!!)
        writeCharacteristic(gattParam, jwtAuthUuid, jwt, "JWT")
    }

    @SuppressLint("MissingPermission")
    private fun sendTotpCommand(gattParam: BluetoothGatt) {
        val totp = generateTotp8(fixedKey!!, fixedTime)
        val command = "$totp|$fixedTime|OPEN"
        writeCharacteristic(gattParam, totpCommandUuid, command, "TOTP")
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristic(
        gattParam: BluetoothGatt,
        uuid: UUID,
        value: String,
        label: String
    ) {
        val service = gattParam.getService(serviceUuid) ?: run {
            Log.e(TAG, "❌ Сервис не найден для $label")
            gattParam.disconnect()
            return
        }

        val char = service.getCharacteristic(uuid) ?: run {
            Log.e(TAG, "❌ Характеристика не найдена для $label")
            gattParam.disconnect()
            return
        }

        if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            Log.e(TAG, "❌ Характеристика не поддерживает запись для $label")
            gattParam.disconnect()
            return
        }

        char.value = value.toByteArray(UTF_8)
        if (!gattParam.writeCharacteristic(char)) {
            Log.e(TAG, "❌ Ошибка записи $label")
            gattParam.disconnect()
        } else {
            Log.d(TAG, "📤 $label отправлен (${value.length} байт)")
        }
    }

    private fun generateSimpleJwt(secret: String): String {
        val now = System.currentTimeMillis() / 1000
        val header = """{"alg":"HS256","typ":"JWT"}"""
        val payload = """{"sub":"arduino","iat":$now,"exp":${now + 300}}"""
        val unsigned = "${base64UrlEncode(header)}.${base64UrlEncode(payload)}"
        val signature = hmacSha256(unsigned, secret)
        return "$unsigned.$signature"
    }

    private fun generateTotp8(secretBase32: String, timeSeconds: Long): String {
        if (timeSeconds < 0) return "00000000"
        return try {
            val key = base32Decode(secretBase32)
            val timeStep = timeSeconds / 30L
            val mac = Mac.getInstance("HmacSHA1").apply {
                init(SecretKeySpec(key, "HmacSHA1"))
            }
            val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(timeStep).array())
            val offset = hash[hash.size - 1].toInt() and 0x0F
            var code = ((hash[offset].toInt() and 0x7F) shl 24) or
                    ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                    ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                    (hash[offset + 3].toInt() and 0xFF)
            code = code and 0x7FFFFFFF
            String.format(Locale.US, "%08d", code % 100_000_000)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка TOTP", e)
            "00000000"
        }
    }

    private fun base32Decode(input: String): ByteArray {
        if (input.isBlank()) return byteArrayOf()
        val cleanInput = input.uppercase(Locale.US).filter { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" }
        if (cleanInput.isEmpty()) return byteArrayOf()

        val bits = StringBuilder()
        for (c in cleanInput) {
            val idx = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c)
            bits.append(String.format("%5s", Integer.toBinaryString(idx)).replace(' ', '0'))
        }

        val byteLength = (bits.length + 7) / 8
        val bytes = ByteArray(byteLength)
        for (i in 0 until byteLength) {
            val start = i * 8
            val end = min(start + 8, bits.length)
            val byteStr = bits.substring(start, end).padEnd(8, '0')
            bytes[i] = byteStr.toInt(2).toByte()
        }
        return bytes
    }

    private fun base64UrlEncode(input: String): String {
        return Base64.encodeToString(
            input.toByteArray(UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        ).replace("=", "")
    }

    private fun hmacSha256(message: String, secret: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256"))
            val hash = mac.doFinal(message.toByteArray(UTF_8))
            Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                .replace("=", "")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка HMAC", e)
            ""
        }
    }
    @SuppressLint("MissingPermission")
    fun destroy() {
        stopScan()
        try {
            synchronized(this) {
                gatt?.close()
                gatt = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка закрытия GATT", e)
        }
        fixedKey = null
        scanTimeoutHandler?.removeCallbacksAndMessages(null)
        Log.d(TAG, "🧹 Ресурсы освобождены")
    }
}