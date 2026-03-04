package com.example.smartdoor

import android.Manifest
import android.content.Intent
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

    private lateinit var bleManager: ArduinoBleManager
    private lateinit var tvStatus: TextView
    private var isDebugMode = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
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

        bleManager = ArduinoBleManager(this)
        tvStatus = findViewById(R.id.tvStatus)

        // Настройка колбэков
        setupCallbacks()

        // Кнопка открытия двери
        findViewById<Button>(R.id.btnOpenDoor).setOnClickListener {
            if (!bleManager.hasPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }
            tvStatus.text = "🔍 Поиск устройства..."
            bleManager.startScan()
        }

        // Кнопка настроек
        findViewById<Button>(R.id.btnSetup).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        // Кнопка отладки
        findViewById<Button>(R.id.btnDebug)?.setOnClickListener {
            isDebugMode = !isDebugMode
            bleManager.debugMode = isDebugMode

            val modeText = if (isDebugMode) {
                "🔧 Режим отладки: ВКЛ"
            } else {
                "🔧 Режим отладки: ВЫКЛ"
            }

            Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        // Проверка разрешений при запуске
        checkPermissions()
    }

    private fun setupCallbacks() {
        bleManager.onDeviceFound = { name, address ->
            runOnUiThread {
                tvStatus.text = "🎯 Найдено: $name"
            }
        }

        bleManager.onConnected = {
            runOnUiThread {
                tvStatus.text = "✅ Подключено, отправка команды..."
            }

            // Отправляем команду через небольшую задержку
            android.os.Handler(mainLooper).postDelayed({
                if (bleManager.sendOpenCommand()) {
                    // Команда отправлена, отключение произойдёт автоматически
                }
            }, 500)
        }

        bleManager.onCommandSent = { command ->
            runOnUiThread {
                tvStatus.text = "🎉 Команда '$command' отправлена!\nДверь должна открыться."
                Toast.makeText(this, "✅ Дверь открыта!", Toast.LENGTH_SHORT).show()
            }

            // Отключаемся через 2 секунды
            android.os.Handler(mainLooper).postDelayed({
                bleManager.disconnect()
            }, 2000)
        }

        bleManager.onDisconnected = {
            runOnUiThread {
                tvStatus.text = "\n🔌 Отключено"
            }
        }

        bleManager.onError = { error ->
            runOnUiThread {
                tvStatus.text = "❌ Ошибка: $error"
                Toast.makeText(this, "Ошибка: $error", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
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

    private fun updateStatus() {
        val status = StringBuilder()

        if (!bleManager.isBleSupported()) {
            status.append("❌ BLE не поддерживается\n")
        } else {
            status.append("✅ BLE поддерживается\n")
        }

        if (!bleManager.isBluetoothEnabled()) {
            status.append("❌ Bluetooth отключен\n")
        } else {
            status.append("✅ Bluetooth включен\n")
        }

        if (!bleManager.hasPermissions()) {
            status.append("❌ Нет разрешений\n")
        } else {
            status.append("✅ Разрешения получены\n")
        }

        if (bleManager.debugMode) {
            status.append("🔧 Режим отладки: ВКЛЮЧЕН (показываю все устройства)\n")
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
        bleManager.destroy()
    }
}