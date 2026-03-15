package com.example.smartdoor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*

class BluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "SmartDoor"
        private const val DISCOVERY_TIMEOUT_MS = 12000L
        private const val CONNECTION_TIMEOUT_MS = 10000L
        private const val UUID_SPP = "00001101-0000-1000-8000-00805F9B34FB" // Standard SerialPortService ID
    }

    // Настройки
    var targetDeviceName = "HC-05" // Имя устройства для поиска
    var commandPrefix = "OPEN" // Префикс команды
    var debugMode = false // Режим отладки: показывает все устройства

    // Внутренние переменные
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    private var discoveryReceiver: BroadcastReceiver? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var isDiscovering = false
    private var lastFoundAddress: String? = null
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    // Callbacks для внешнего использования
    var onDeviceFound: ((String, String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onCommandSent: ((String) -> Unit)? = null
    var onCommandResponse: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDiscoveryFinished: (() -> Unit)? = null

    /**
     * Проверка поддержки Bluetooth
     */
    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null
    }

    /**
     * Проверка включенности Bluetooth
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled ?: false
    }

    /**
     * Включение Bluetooth
     */
    @SuppressLint("MissingPermission")
    fun enableBluetooth() {
        bluetoothAdapter?.enable()
    }

    /**
     * Запуск сканирования устройств
     */
    fun startDiscovery() {
        if (!isBluetoothSupported()) {
            onError?.invoke("Bluetooth не поддерживается на этом устройстве")
            return
        }

        if (!isBluetoothEnabled()) {
            onError?.invoke("Bluetooth отключен. Включите в настройках.")
            return
        }

        if (isDiscovering) {
            Log.w(TAG, "Сканирование уже запущено")
            return
        }

        discoveredDevices.clear()

        // Регистрация ресивера для обнаружения устройств
        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        device?.let { processDiscoveredDevice(it) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        isDiscovering = false
                        unregisterReceiver()
                        Log.d(TAG, "✅ Сканирование завершено. Найдено устройств: ${discoveredDevices.size}")

                        if (discoveredDevices.isEmpty()) {
                            onError?.invoke("Устройства не найдены. Проверьте, что модуль включен и в радиусе действия.")
                        }

                        onDiscoveryFinished?.invoke()
                    }
                }
            }
        }

        // Регистрация ресивера
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            context.registerReceiver(discoveryReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка регистрации ресивера", e)
            onError?.invoke("Ошибка регистрации: ${e.message}")
            return
        }

        isDiscovering = true
        Log.d(TAG, "🔍 Запуск сканирования Bluetooth устройств...")

        // Запуск сканирования
        if (bluetoothAdapter?.startDiscovery() == true) {
            Log.d(TAG, "✅ Сканирование запущено")

            // Таймаут сканирования
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    if (isDiscovering) {
                        Log.w(TAG, "⏰ Таймаут сканирования (12 сек)")
                        cancelDiscovery()
                    }
                }
            }, DISCOVERY_TIMEOUT_MS)

        } else {
            Log.e(TAG, "❌ Не удалось запустить сканирование")
            unregisterReceiver()
            isDiscovering = false
            onError?.invoke("Не удалось запустить сканирование")
        }
    }

    /**
     * Обработка найденного устройства
     */
    @SuppressLint("MissingPermission")
    private fun processDiscoveredDevice(device: BluetoothDevice) {
        val name = device.name ?: "Unknown"
        val address = device.address
        val bondState = device.bondState

        if (debugMode) {
            val bondStateStr = when (bondState) {
                BluetoothDevice.BOND_BONDED -> "Сопряжено"
                BluetoothDevice.BOND_BONDING -> "Сопряжение..."
                else -> "Не сопряжено"
            }
            Log.d(TAG, "📡 Найдено: $name | MAC: $address | Состояние: $bondStateStr")
        }

        // Если в режиме отладки, добавляем все устройства
        if (debugMode) {
            if (!discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                onDeviceFound?.invoke(name, address)
            }
            return
        }

        // Ищем устройство по имени
        if (name.contains(targetDeviceName, ignoreCase = true)) {
            Log.d(TAG, "🎯 Целевое устройство найдено: $name ($address)")

            if (!discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                onDeviceFound?.invoke(name, address)

                // Автоматически подключаемся к первому найденному устройству
                cancelDiscovery()
                connect(device)
            }
        }
    }

    /**
     * Отмена сканирования
     */
    fun cancelDiscovery() {
        if (isDiscovering) {
            try {
                bluetoothAdapter?.cancelDiscovery()
                Log.d(TAG, "⏹️ Сканирование отменено")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Ошибка отмены сканирования", e)
            }
        }
        unregisterReceiver()
        isDiscovering = false
    }

    /**
     * Отмена регистрации ресивера
     */
    private fun unregisterReceiver() {
        try {
            if (discoveryReceiver != null) {
                context.unregisterReceiver(discoveryReceiver)
                discoveryReceiver = null
                Log.d(TAG, "⏹️ Ресивер отменён")
            }
        } catch (e: IllegalArgumentException) {
            // Ресивер уже был отменён - это нормально
            discoveryReceiver = null
            Log.d(TAG, "ℹ️ Ресивер уже был отменён ранее")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка отмены регистрации ресивера", e)
            discoveryReceiver = null
        }
    }

    /**
     * Подключение к устройству
     */
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "🔗 Подключение к ${device.name} (${device.address})")

        Thread {
            try {
                // Создание сокета
                val uuid = UUID.fromString(UUID_SPP)
                val tmpSocket = device.createRfcommSocketToServiceRecord(uuid)

                // Отмена обнаружения (освобождает ресурсы)
                bluetoothAdapter?.cancelDiscovery()

                // Подключение с таймаутом
                val connectionThread = Thread {
                    try {
                        tmpSocket.connect()
                        Log.d(TAG, "✅ Сокет подключен")
                    } catch (e: IOException) {
                        Log.e(TAG, "❌ Ошибка подключения сокета", e)
                        try {
                            tmpSocket.close()
                        } catch (closeEx: IOException) {
                            Log.e(TAG, "❌ Ошибка закрытия сокета", closeEx)
                        }
                    }
                }

                connectionThread.start()
                connectionThread.join(CONNECTION_TIMEOUT_MS)

                if (!tmpSocket.isConnected) {
                    throw IOException("Таймаут подключения")
                }

                // Сохранение сокета и потоков
                socket = tmpSocket
                outputStream = socket?.outputStream
                inputStream = socket?.inputStream

                lastFoundAddress = device.address
                Log.d(TAG, "✅ Подключение установлено")

                // Уведомление об успешном подключении
                onConnected?.invoke()

                // Запуск потока для чтения ответов
                startResponseListener()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка подключения", e)
                onError?.invoke("Ошибка подключения: ${e.message ?: "Неизвестная ошибка"}")
                disconnect()
            }
        }.start()
    }

    /**
     * Запуск потока для чтения ответов от устройства
     */
    private fun startResponseListener() {
        Thread {
            try {
                val buffer = ByteArray(1024)
                val inputStream = this.inputStream ?: return@Thread

                while (true) {
                    try {
                        val bytes = inputStream.read(buffer)
                        if (bytes > 0) {
                            val response = String(buffer, 0, bytes).trim()
                            Log.d(TAG, "📥 Получен ответ: $response")
                            onCommandResponse?.invoke(response)
                        }
                    } catch (e: IOException) {
                        Log.w(TAG, "⚠️ Ошибка чтения", e)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Поток чтения завершен", e)
            }
        }.start()
    }

    /**
     * Отправка команды на устройство
     */
    fun sendCommand(command: String): Boolean {
        if (socket == null || !socket!!.isConnected) {
            Log.e(TAG, "❌ Сокет не подключен")
            onError?.invoke("Устройство не подключено")
            return false
        }

        return try {
            val finalCommand = if (command.endsWith("\n")) command else "$command\n"
            outputStream?.write(finalCommand.toByteArray())
            outputStream?.flush()

            Log.d(TAG, "📤 Отправлена команда: $command")
            onCommandSent?.invoke(command)
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки команды", e)
            onError?.invoke("Ошибка отправки: ${e.message ?: "Неизвестная ошибка"}")
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
     * Отправка команды закрытия двери
     */
    fun sendCloseCommand(): Boolean {
        return sendCommand("CLOSE")
    }

    /**
     * Отправка запроса статуса
     */
    fun sendStatusCommand(): Boolean {
        return sendCommand("STATUS")
    }

    /**
     * Отключение от устройства
     */
    fun disconnect() {
        try {
            // Закрытие потоков
            outputStream?.close()
            inputStream?.close()

            // Закрытие сокета
            socket?.close()

            Log.d(TAG, "🔌 Отключено от устройства")
            onDisconnected?.invoke()

        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка отключения", e)
        } finally {
            socket = null
            outputStream = null
            inputStream = null
        }
    }

    /**
     * Очистка ресурсов
     */
    fun destroy() {
        try {
            cancelDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка при отмене сканирования", e)
        }

        try {
            disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Ошибка при отключении", e)
        }

        Log.d(TAG, "🧹 Ресурсы освобождены")
    }

    /**
     * Получение списка найденных устройств
     */
    fun getDiscoveredDevices(): List<BluetoothDevice> {
        return discoveredDevices
    }
}