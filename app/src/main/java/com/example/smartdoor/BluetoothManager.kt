package com.example.smartdoor

import android.Manifest
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
        private const val UUID_SPP = "00001101-0000-1000-8000-00805F9B34FB"
    }

    // Настройки (можно изменить через приложение)
    var targetDeviceName = "HC-05"
    var commandPrefix = "OPEN"
    var debugMode = false

    // Внутренние переменные
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        BluetoothAdapter.getDefaultAdapter()
    }

    private var discoveryReceiver: BroadcastReceiver? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var isDiscovering = false
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    // Callbacks
    var onDeviceFound: ((String, String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onCommandSent: ((String) -> Unit)? = null
    var onCommandResponse: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onDiscoveryFinished: (() -> Unit)? = null

    // Проверки состояния
    fun isBluetoothSupported(): Boolean = bluetoothAdapter != null
    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled ?: false

    // ЗАПУСК СКАНИРОВАНИЯ (с аннотацией для подавления предупреждений)
    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        // Базовые проверки
        if (!isBluetoothSupported()) {
            onError?.invoke("Bluetooth не поддерживается")
            return
        }

        if (!isBluetoothEnabled()) {
            onError?.invoke("Bluetooth отключен")
            return
        }

        if (isDiscovering) {
            Log.d(TAG, "Сканирование уже запущено")
            return
        }

        discoveredDevices.clear()

        // Регистрация ресивера для обнаружения устройств
        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let { processDiscoveredDevice(it) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        isDiscovering = false
                        unregisterReceiver()
                        Log.d(TAG, "Сканирование завершено. Найдено: ${discoveredDevices.size} устройств")
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
            @SuppressLint("MissingPermission")
            context.registerReceiver(discoveryReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка регистрации ресивера", e)
            onError?.invoke("Ошибка регистрации: ${e.message}")
            return
        }

        isDiscovering = true
        Log.d(TAG, "Запуск сканирования Bluetooth...")

        // Запуск сканирования с обработкой исключений
        try {
            @SuppressLint("MissingPermission")
            val started = bluetoothAdapter?.startDiscovery() ?: false

            if (started) {
                Log.d(TAG, "Сканирование запущено")
                // Таймаут для автоматической остановки
                Timer().schedule(object : TimerTask() {
                    override fun run() {
                        if (isDiscovering) {
                            Log.w(TAG, "Таймаут сканирования")
                            cancelDiscovery()
                        }
                    }
                }, DISCOVERY_TIMEOUT_MS)
            } else {
                throw IOException("Не удалось запустить сканирование")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException при сканировании", e)
            onError?.invoke("Требуется разрешение на геолокацию")
            cancelDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска сканирования", e)
            onError?.invoke("Ошибка сканирования: ${e.message}")
            cancelDiscovery()
        }
    }

    // Обработка найденного устройства
    private fun processDiscoveredDevice(device: BluetoothDevice) {
        val name = device.name ?: "Unknown"
        val address = device.address

        if (debugMode) {
            Log.d(TAG, "Найдено: $name | MAC: $address")
        }

        // В режиме отладки показываем все устройства
        if (debugMode) {
            if (!discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                onDeviceFound?.invoke(name, address)
            }
            return
        }

        // Ищем устройство по имени
        if (name.contains(targetDeviceName, ignoreCase = true)) {
            Log.d(TAG, "Целевое устройство найдено: $name ($address)")
            if (!discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                onDeviceFound?.invoke(name, address)
                cancelDiscovery()
                connect(device)
            }
        }
    }

    // ОТМЕНА СКАНИРОВАНИЯ
    @SuppressLint("MissingPermission")
    fun cancelDiscovery() {
        if (isDiscovering) {
            try {
                bluetoothAdapter?.cancelDiscovery()
                Log.d(TAG, "Сканирование остановлено")
            } catch (e: Exception) {
                Log.w(TAG, "Ошибка остановки сканирования", e)
            }
        }
        unregisterReceiver()
        isDiscovering = false
    }

    // Отмена регистрации ресивера
    private fun unregisterReceiver() {
        try {
            discoveryReceiver?.let {
                context.unregisterReceiver(it)
                discoveryReceiver = null
            }
        } catch (e: Exception) {
            discoveryReceiver = null
        }
    }

    // ПОДКЛЮЧЕНИЕ К УСТРОЙСТВУ
    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "Подключение к ${device.name} (${device.address})")

        Thread {
            try {
                // Создание RFCOMM сокета
                val uuid = UUID.fromString(UUID_SPP)
                val tmpSocket = device.createRfcommSocketToServiceRecord(uuid)

                // Отмена сканирования освобождает ресурсы
                @SuppressLint("MissingPermission")
                bluetoothAdapter?.cancelDiscovery()

                // Подключение с таймаутом
                tmpSocket.connect()

                if (!tmpSocket.isConnected) {
                    throw IOException("Не удалось подключиться")
                }

                // Сохранение соединения
                socket = tmpSocket
                outputStream = socket?.outputStream
                inputStream = socket?.inputStream

                Log.d(TAG, "Подключение установлено")
                onConnected?.invoke()

                // Запуск потока для чтения ответов
                startResponseListener()

            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException при подключении", e)
                onError?.invoke("Ошибка подключения: требуется разрешение")
                disconnect()
            } catch (e: IOException) {
                Log.e(TAG, "IOException при подключении", e)
                onError?.invoke("Ошибка подключения: ${e.message}")
                disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка подключения", e)
                onError?.invoke("Ошибка: ${e.message}")
                disconnect()
            }
        }.start()
    }

    // Поток для чтения ответов от устройства
    private fun startResponseListener() {
        Thread {
            val buffer = ByteArray(1024)
            val input = inputStream ?: return@Thread

            while (true) {
                try {
                    val bytes = input.read(buffer)
                    if (bytes > 0) {
                        val response = String(buffer, 0, bytes).trim()
                        Log.d(TAG, "Получен ответ: $response")
                        onCommandResponse?.invoke(response)
                    }
                } catch (e: IOException) {
                    break // Соединение разорвано
                } catch (e: Exception) {
                    break
                }
            }
            Log.d(TAG, "Поток чтения завершён")
        }.start()
    }

    // ОТПРАВКА КОМАНДЫ
    @SuppressLint("MissingPermission")
    fun sendCommand(command: String): Boolean {
        if (socket == null || !socket!!.isConnected) {
            onError?.invoke("Устройство не подключено")
            return false
        }

        return try {
            // Добавляем символ новой строки для корректной обработки на Arduino
            val finalCommand = if (command.endsWith("\n")) command else "$command\n"
            outputStream?.write(finalCommand.toByteArray())
            outputStream?.flush()
            Log.d(TAG, "Отправлена команда: $command")
            onCommandSent?.invoke(command)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки команды", e)
            onError?.invoke("Ошибка отправки: ${e.message}")
            false
        }
    }

    // Удобные методы для отправки команд
    fun sendOpenCommand(): Boolean = sendCommand(commandPrefix)
    fun sendCloseCommand(): Boolean = sendCommand("CLOSE")

    // ОТКЛЮЧЕНИЕ
    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            outputStream?.close()
            inputStream?.close()
            socket?.close()
            Log.d(TAG, "Отключено от устройства")
            onDisconnected?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка отключения", e)
        } finally {
            socket = null
            outputStream = null
            inputStream = null
        }
    }

    // ОЧИСТКА РЕСУРСОВ
    fun destroy() {
        try { cancelDiscovery() } catch (e: Exception) { /* ignore */ }
        try { disconnect() } catch (e: Exception) { /* ignore */ }
        Log.d(TAG, "Ресурсы освобождены")
    }

    // Получение списка найденных устройств
    fun getDiscoveredDevices(): List<BluetoothDevice> = discoveredDevices
}