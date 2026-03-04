package com.example.smartdoor

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.charset.Charset
import java.util.*

class ArduinoBleManager(private val context: Context) {

    companion object {
        private const val TAG = "ArduinoBle"
        private const val SCAN_TIMEOUT_MS = 10000L
        private const val SERVICE_UUID = "12345678-1234-5678-1234-56789abcdef0"
        private const val COMMAND_CHAR_UUID = "12345678-1234-5678-1234-56789abcdef3"
        private val UTF_8 = Charset.forName("UTF-8")
    }

    // Настройки
    var targetDeviceName = "Arduino Lock" // Имя устройства для поиска
    var commandPrefix = "OPEN" // Префикс команды
    var debugMode = false // Режим отладки: показывает все устройства

    // Внутренние переменные
    private val bluetoothManager by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter by lazy { bluetoothManager.adapter }
    private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private val scanHandler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private var lastFoundAddress: String? = null

    // Callbacks для внешнего использования
    var onDeviceFound: ((String, String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onCommandSent: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Проверка поддержки BLE
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

    /**
     * Проверка разрешений
     */
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
     * Запуск сканирования устройств
     */
    fun startScan() {
        if (!isBleSupported()) {
            onError?.invoke("BLE не поддерживается на этом устройстве")
            return
        }

        if (!isBluetoothEnabled()) {
            onError?.invoke("Bluetooth отключен. Включите в настройках.")
            return
        }

        if (!hasPermissions()) {
            onError?.invoke("Отсутствуют разрешения для работы с Bluetooth")
            return
        }

        if (isScanning) {
            Log.w(TAG, "Сканирование уже запущено")
            return
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            onError?.invoke("BluetoothLeScanner недоступен")
            return
        }

        // Фильтр по имени устройства (если не в режиме отладки)
        val filters = if (debugMode) {
            emptyList()
        } else {
            listOf(
                ScanFilter.Builder()
                    .setDeviceName(targetDeviceName)
                    .build()
            )
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: "Unknown"
                val address = device.address
                val rssi = result.rssi

                if (debugMode) {
                    Log.d(TAG, "📡 Найдено: $name | RSSI: $rssi dBm | MAC: $address")
                }

                // Если в режиме отладки, ищем по части имени
                if (debugMode && !name.contains(targetDeviceName, ignoreCase = true)) {
                    return
                }

                Log.d(TAG, "🎯 Целевое устройство найдено: $name ($address)")
                stopScan()
                onDeviceFound?.invoke(name, address)
                connect(device)
            }

            override fun onScanFailed(errorCode: Int) {
                val error = when (errorCode) {
                    SCAN_FAILED_ALREADY_STARTED -> "Сканирование уже запущено"
                    SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Ошибка регистрации приложения"
                    SCAN_FAILED_INTERNAL_ERROR -> "Внутренняя ошибка"
                    SCAN_FAILED_FEATURE_UNSUPPORTED -> "Функция не поддерживается"
                    else -> "Неизвестная ошибка ($errorCode)"
                }
                Log.e(TAG, "❌ Сканирование завершилось ошибкой: $error")
                isScanning = false
                onError?.invoke("Ошибка сканирования: $error")
            }
        }

        isScanning = true
        try {
            @SuppressLint("MissingPermission")
            scanner.startScan(filters, settings, scanCallback!!)
            Log.d(TAG, "✅ Сканирование запущено")

            // Таймаут сканирования
            scanHandler.postDelayed({
                if (isScanning) {
                    Log.w(TAG, "⏰ Таймаут сканирования (10 сек)")
                    stopScan()
                    if (lastFoundAddress == null) {
                        onError?.invoke("Устройство не найдено. Проверьте, что оно включено и в радиусе действия.")
                    }
                }
            }, SCAN_TIMEOUT_MS)

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Ошибка безопасности", e)
            isScanning = false
            onError?.invoke("Ошибка безопасности: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка сканирования", e)
            isScanning = false
            onError?.invoke("Ошибка: ${e.message}")
        }
    }

    /**
     * Остановка сканирования
     */
    fun stopScan() {
        try {
            scanCallback?.let { cb ->
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(cb)
                Log.d(TAG, "⏹️ Сканирование остановлено")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка остановки сканирования", e)
        } finally {
            isScanning = false
            scanCallback = null
            scanHandler.removeCallbacksAndMessages(null)
        }
    }

    /**
     * Подключение к устройству
     */
    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        Log.d(TAG, "🔗 Подключение к ${device.address}")

        gatt?.close()
        gatt = null

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "✅ Подключено к ${device.name}")
                        lastFoundAddress = device.address
                        this@ArduinoBleManager.gatt = gatt
                        onConnected?.invoke()
                        gatt.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "🔌 Отключено")
                        gatt.close()
                        this@ArduinoBleManager.gatt = null
                        onDisconnected?.invoke()
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "✅ Сервисы обнаружены")
                    if (debugMode) {
                        gatt.services.forEach { service ->
                            Log.d(TAG, "   📡 Сервис: ${service.uuid}")
                            service.characteristics.forEach { char ->
                                val props = getPropertiesString(char.properties)
                                Log.d(TAG, "      🔷 Характеристика: ${char.uuid} | $props")
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "❌ Ошибка обнаружения сервисов: $status")
                    onError?.invoke("Ошибка обнаружения сервисов")
                    gatt.disconnect()
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.d(TAG, "📤 Команда отправлена успешно")
                    onCommandSent?.invoke(String(characteristic.value, UTF_8))
                } else {
                    Log.e(TAG, "❌ Ошибка отправки команды: $status")
                    onError?.invoke("Ошибка отправки команды")
                }
            }
        }

        try {
            gatt = device.connectGatt(context, false, gattCallback)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка подключения", e)
            onError?.invoke("Ошибка подключения: ${e.message}")
        }
    }

    /**
     * Отправка команды на устройство
     */
    @SuppressLint("MissingPermission")
    fun sendCommand(command: String): Boolean {
        val gatt = this.gatt ?: run {
            Log.e(TAG, "❌ GATT не подключен")
            onError?.invoke("Устройство не подключено")
            return false
        }

        val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: run {
            Log.e(TAG, "❌ Сервис не найден")
            onError?.invoke("Сервис не найден на устройстве")
            return false
        }

        val characteristic = service.getCharacteristic(UUID.fromString(COMMAND_CHAR_UUID)) ?: run {
            Log.e(TAG, "❌ Характеристика не найдена")
            onError?.invoke("Характеристика команды не найдена")
            return false
        }

        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
            Log.e(TAG, "❌ Характеристика не поддерживает запись")
            onError?.invoke("Характеристика не поддерживает запись")
            return false
        }

        characteristic.value = command.toByteArray(UTF_8)

        return if (gatt.writeCharacteristic(characteristic)) {
            Log.d(TAG, "📤 Отправлена команда: $command")
            true
        } else {
            Log.e(TAG, "❌ Не удалось отправить команду")
            onError?.invoke("Не удалось отправить команду")
            false
        }
    }

    /**
     * Отправка команды открытия двери
     */
    fun sendOpenCommand(): Boolean {
        return sendCommand(commandPrefix)
    }

    /**
     * Отключение от устройства
     */
    fun disconnect() {
        try {
            gatt?.disconnect()
            Log.d(TAG, "🔌 Отключение от устройства")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка отключения", e)
        }
    }

    /**
     * Очистка ресурсов
     */
    fun destroy() {
        stopScan()
        disconnect()
        gatt?.close()
        gatt = null
        Log.d(TAG, "🧹 Ресурсы освобождены")
    }

    /**
     * Вспомогательная функция для вывода свойств характеристики
     */
    private fun getPropertiesString(properties: Int): String {
        val flags = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) flags.add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) flags.add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) flags.add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) flags.add("INDICATE")
        return flags.joinToString("|")
    }
}