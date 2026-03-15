package com.example.smartdoor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btManager: BluetoothManager
    private lateinit var tvStatus: TextView
    private var isDebugMode = false
    private var bluetoothStateReceiver: BroadcastReceiver? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            updateStatus()
            Toast.makeText(this, "✅ Разрешения получены", Toast.LENGTH_SHORT).show()
        } else {
            tvStatus.text = "❌ Разрешения отклонены"
            Toast.makeText(this, "Требуются разрешения для работы", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btManager = BluetoothManager(this)
        tvStatus = findViewById(R.id.tvStatus)

        // Настройка колбэков
        setupCallbacks()

        // Кнопка открытия двери
        findViewById<Button>(R.id.btnOpenDoor).setOnClickListener {
            if (!hasPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }

            if (!btManager.isBluetoothEnabled()) {
                Toast.makeText(this, "❌ Включите Bluetooth в настройках устройства", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            tvStatus.text = "🔍 Поиск устройств Bluetooth..."
            btManager.startDiscovery()
        }

        // Кнопка настроек
        findViewById<Button>(R.id.btnSetup).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        // Кнопка отладки
        findViewById<Button>(R.id.btnDebug)?.setOnClickListener {
            isDebugMode = !isDebugMode
            btManager.debugMode = isDebugMode

            val modeText = if (isDebugMode) {
                "🔧 Режим отладки: ВКЛ"
            } else {
                "🔧 Режим отладки: ВЫКЛ"
            }

            Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        // Регистрация ресивера для отслеживания состояния Bluetooth
        bluetoothStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action
                if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    updateStatus()
                }
            }
        }

        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothStateReceiver, filter)

        // Проверка разрешений при запуске
        checkPermissions()
    }

    private fun setupCallbacks() {
        btManager.onDeviceFound = { name, address ->
            runOnUiThread {
                tvStatus.text = "🎯 Найдено: $name\nMAC: $address"
            }
        }

        btManager.onConnected = {
            runOnUiThread {
                tvStatus.text = "✅ Подключено, отправка команды..."
            }

            // Отправляем команду через небольшую задержку
            android.os.Handler(mainLooper).postDelayed({
                if (btManager.sendOpenCommand()) {
                    // Команда отправлена
                } else {
                    runOnUiThread {
                        val currentText = tvStatus.text.toString()
                        tvStatus.text = currentText + "\n❌ Ошибка отправки команды"
                    }
                }
            }, 500)
        }

        btManager.onCommandSent = { command ->
            runOnUiThread {
                tvStatus.text = "📤 Команда '$command' отправлена"
            }
        }

        btManager.onCommandResponse = { response ->
            runOnUiThread {
                val currentText = tvStatus.text.toString()
                tvStatus.text = currentText + "\n📥 Ответ: $response"

                if (response.contains("OK", ignoreCase = true)) {
                    tvStatus.text = tvStatus.text.toString() + "\n🎉 Дверь должна открыться!"
                    Toast.makeText(this, "✅ Дверь открыта!", Toast.LENGTH_SHORT).show()
                }
            }

            // Отключаемся через 2 секунды после получения ответа
            android.os.Handler(mainLooper).postDelayed({
                btManager.disconnect()
            }, 2000)
        }

        btManager.onDisconnected = {
            runOnUiThread {
                val currentText = tvStatus.text.toString()
                tvStatus.text = currentText + "\n🔌 Отключено"
            }
        }

        btManager.onDiscoveryFinished = {
            runOnUiThread {
                if (btManager.getDiscoveredDevices().isEmpty()) {
                    tvStatus.text = "❌ Устройства не найдены\nПроверьте, что модуль включен и в радиусе действия"
                }
            }
        }

        btManager.onError = { error ->
            runOnUiThread {
                tvStatus.text = "❌ Ошибка: $error"
                Toast.makeText(this, "Ошибка: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        // Разрешения для всех версий
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        // Разрешение на геолокацию для сканирования (Android 6.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        // Новые разрешения для Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            updateStatus()
        }
    }

    private fun requestPermissions() {
        checkPermissions()
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun updateStatus() {
        val status = StringBuilder()

        if (!btManager.isBluetoothSupported()) {
            status.append("❌ Bluetooth не поддерживается этим устройством\n")
        } else {
            status.append("✅ Bluetooth поддерживается устройством\n")
        }

        if (!btManager.isBluetoothEnabled()) {
            status.append("❌ Bluetooth отключен. Включите в настройках устройства.\n")
        } else {
            status.append("✅ Bluetooth включен и готов к работе\n")
        }

        if (!hasPermissions()) {
            status.append("❌ Необходимо предоставить разрешения для работы приложения\n")
        } else {
            status.append("✅ Все необходимые разрешения предоставлены\n")
        }

        if (btManager.debugMode) {
            status.append("🔧 Режим отладки: ВКЛЮЧЕН (отображаются все устройства)\n")
        }

        runOnUiThread {
            tvStatus.text = status.toString()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        btManager.destroy()

        // Отмена регистрации ресивера
        bluetoothStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // Игнорируем ошибку, если ресивер уже отменён
            }
        }
    }
}