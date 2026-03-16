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

/**
 * Управляет Bluetooth SPP соединением с HC-05 (BLE_LOCKER).
 *
 * Протокол открытия замка:
 *   1. Сканирование / поиск устройства по имени
 *   2. RFCOMM-подключение (SPP UUID)
 *   3. Отправка строки: "TTTTTTTTXXXXXXXXXX" (8 цифр TOTP + 10 цифр unixTime)
 *   4. Arduino проверяет TOTP и открывает замок
 */
@SuppressLint("MissingPermission")
class BluetoothManager(private val context: Context) {

    companion object {
        private const val TAG = "SmartDoor"
        private const val DISCOVERY_TIMEOUT_MS = 12_000L
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    // ── Настройки ─────────────────────────────────────────────────────────────
    var targetDeviceName = "BLE_LOCKER"  // Имя HC-05 (устанавливается в configureHC05())
    var debugMode = false                 // Показывать все устройства при сканировании

    // ── Состояние ─────────────────────────────────────────────────────────────
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var discoveryReceiver: BroadcastReceiver? = null
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var isDiscovering = false
    private val discoveredDevices = mutableListOf<BluetoothDevice>()

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onDeviceFound: ((name: String, address: String) -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onCommandSent: ((command: String) -> Unit)? = null
    var onCommandResponse: ((response: String) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null
    var onDiscoveryFinished: (() -> Unit)? = null

    // ── Bluetooth state ───────────────────────────────────────────────────────
    fun isSupported() = bluetoothAdapter != null
    fun isEnabled() = bluetoothAdapter?.isEnabled == true
    val isConnected get() = socket?.isConnected == true

    // ── Discovery ─────────────────────────────────────────────────────────────

    fun startDiscovery() {
        if (!isSupported()) { onError?.invoke("Bluetooth не поддерживается"); return }
        if (!isEnabled())   { onError?.invoke("Bluetooth отключён. Включите в настройках."); return }
        if (isDiscovering)  { return }

        // Сначала ищем среди уже сопряжённых устройств
        val bonded = bluetoothAdapter!!.bondedDevices
            ?.firstOrNull { it.name?.contains(targetDeviceName, ignoreCase = true) == true }
        if (bonded != null) {
            Log.d(TAG, "✅ Найдено сопряжённое устройство: ${bonded.name}")
            onDeviceFound?.invoke(bonded.name ?: targetDeviceName, bonded.address)
            connect(bonded)
            return
        }

        // Если не нашли среди сопряжённых — сканируем
        discoveredDevices.clear()
        discoveryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        device?.let { handleDiscovered(it) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        isDiscovering = false
                        unregisterReceiver()
                        Log.d(TAG, "Сканирование завершено. Найдено: ${discoveredDevices.size}")
                        if (discoveredDevices.isEmpty())
                            onError?.invoke("Устройство не найдено. Проверьте, что модуль включён.")
                        onDiscoveryFinished?.invoke()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(discoveryReceiver, filter)
        isDiscovering = true

        if (bluetoothAdapter.startDiscovery()) {
            Log.d(TAG, "🔍 Сканирование запущено")
            Timer().schedule(object : TimerTask() {
                override fun run() { if (isDiscovering) cancelDiscovery() }
            }, DISCOVERY_TIMEOUT_MS)
        } else {
            unregisterReceiver(); isDiscovering = false
            onError?.invoke("Не удалось запустить сканирование")
        }
    }

    private fun handleDiscovered(device: BluetoothDevice) {
        val name = device.name ?: "Unknown"
        val addr = device.address
        if (debugMode) {
            Log.d(TAG, "📡 Найдено: $name | $addr")
            if (!discoveredDevices.contains(device)) {
                discoveredDevices.add(device); onDeviceFound?.invoke(name, addr)
            }
            return
        }
        if (name.contains(targetDeviceName, ignoreCase = true)) {
            Log.d(TAG, "🎯 Целевое устройство: $name ($addr)")
            if (!discoveredDevices.contains(device)) {
                discoveredDevices.add(device)
                onDeviceFound?.invoke(name, addr)
                cancelDiscovery()
                connect(device)
            }
        }
    }

    fun cancelDiscovery() {
        if (isDiscovering) {
            try { bluetoothAdapter?.cancelDiscovery() } catch (_: Exception) {}
        }
        unregisterReceiver()
        isDiscovering = false
    }

    private fun unregisterReceiver() {
        try {
            discoveryReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        discoveryReceiver = null
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "🔗 Подключение к ${device.name} (${device.address})")
        Thread {
            try {
                val tmpSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothAdapter?.cancelDiscovery()

                // Подключение с таймаутом
                var connectError: Exception? = null
                val t = Thread {
                    try { tmpSocket.connect() }
                    catch (e: IOException) { connectError = e; try { tmpSocket.close() } catch (_: Exception) {} }
                }
                t.start(); t.join(CONNECTION_TIMEOUT_MS)

                if (connectError != null) throw connectError!!
                if (!tmpSocket.isConnected) throw IOException("Таймаут подключения")

                socket = tmpSocket
                outputStream = tmpSocket.outputStream
                inputStream  = tmpSocket.inputStream
                Log.d(TAG, "✅ Подключено")
                onConnected?.invoke()
                startResponseListener()

            } catch (e: Exception) {
                Log.e(TAG, "❌ Ошибка подключения", e)
                onError?.invoke("Ошибка подключения: ${e.message ?: "Неизвестная ошибка"}")
                disconnect()
            }
        }.start()
    }

    private fun startResponseListener() {
        Thread {
            val buf = ByteArray(1024)
            val stream = inputStream ?: return@Thread
            while (true) {
                try {
                    val n = stream.read(buf)
                    if (n > 0) {
                        val resp = String(buf, 0, n).trim()
                        Log.d(TAG, "📥 Ответ: $resp")
                        onCommandResponse?.invoke(resp)
                    }
                } catch (_: IOException) { break }
            }
        }.start()
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Отправляет TOTP-команду для конкретного замка.
     * Формат: "TTTTTTTTXXXXXXXXXX" (18 символов)
     */
    fun sendTotpCommand(lock: LockEntry): Boolean {
        val now = System.currentTimeMillis() / 1000L
        val command = TotpHelper.buildCommand(lock.secretBytes, now)
        Log.d(TAG, "📤 TOTP команда для замка ${lock.uidStr}: $command (time=$now)")
        return sendRaw(command)
    }

    /**
     * Отправляет произвольную строку (для отладки / старого протокола).
     */
    fun sendRaw(text: String): Boolean {
        if (!isConnected) { onError?.invoke("Устройство не подключено"); return false }
        return try {
            val data = if (text.endsWith("\n")) text else "$text\n"
            outputStream!!.write(data.toByteArray())
            outputStream!!.flush()
            Log.d(TAG, "📤 Отправлено: $text")
            onCommandSent?.invoke(text)
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка отправки", e)
            onError?.invoke("Ошибка отправки: ${e.message}")
            false
        }
    }

    // ── Disconnect / Destroy ──────────────────────────────────────────────────

    fun disconnect() {
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close()  } catch (_: Exception) {}
        try { socket?.close()       } catch (_: Exception) {}
        socket = null; outputStream = null; inputStream = null
        Log.d(TAG, "🔌 Отключено")
        onDisconnected?.invoke()
    }

    fun destroy() {
        try { cancelDiscovery() } catch (_: Exception) {}
        try { disconnect()      } catch (_: Exception) {}
    }

    fun getDiscoveredDevices(): List<BluetoothDevice> = discoveredDevices
}