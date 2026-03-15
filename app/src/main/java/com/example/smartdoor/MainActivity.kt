package com.example.smartdoor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btManager: BluetoothManager
    private lateinit var tvStatus: TextView
    private var isDebugMode = false

    // Явный запрос разрешений с обработкой результата
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            updateStatus()
            Toast.makeText(this, "✅ Разрешения получены", Toast.LENGTH_SHORT).show()
            // Автоматически запускаем сканирование после получения разрешений
            if (btManager.isBluetoothEnabled()) {
                tvStatus.text = "🔍 Поиск устройств Bluetooth..."
                btManager.startDiscovery()
            }
        } else {
            tvStatus.text = "❌ Разрешения отклонены\nТребуется доступ к геолокации для поиска устройств"
            Toast.makeText(this, "Разрешите геолокацию в настройках приложения", Toast.LENGTH_LONG).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btManager = BluetoothManager(this)
        tvStatus = findViewById(R.id.tvStatus)

        // Настройка колбэков
        setupCallbacks()

        // Кнопка открытия двери
        findViewById<Button>(R.id.btnOpenDoor).setOnClickListener {
            if (!btManager.isBluetoothEnabled()) {
                Toast.makeText(this, "❌ Включите Bluetooth в настройках устройства", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Проверяем разрешения перед сканированием
            if (!hasRequiredPermissions()) {
                requestRequiredPermissions()
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
            val modeText = if (isDebugMode) "🔧 Режим отладки: ВКЛ" else "🔧 Режим отладки: ВЫКЛ"
            Toast.makeText(this, modeText, Toast.LENGTH_SHORT).show()
            updateStatus()
        }

        // Первоначальная проверка разрешений и статуса
        updateStatus()
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
            // Отправляем команду через короткую задержку
            android.os.Handler(mainLooper).postDelayed({
                btManager.sendOpenCommand()
            }, 300)
        }

        btManager.onCommandSent = { command ->
            runOnUiThread {
                tvStatus.text = "📤 Отправлена команда: $command"
            }
        }

        btManager.onCommandResponse = { response ->
            runOnUiThread {
                val current = tvStatus.text.toString()
                tvStatus.text = "$current\n📥 Ответ: $response"
                if (response.contains("OK", ignoreCase = true)) {
                    tvStatus.text = "$tvStatus.text\n🎉 Дверь открыта!"
                    Toast.makeText(this, "✅ Успех! Дверь открыта", Toast.LENGTH_SHORT).show()
                }
            }
            // Автоматическое отключение через 2 секунды
            android.os.Handler(mainLooper).postDelayed({
                btManager.disconnect()
            }, 2000)
        }

        btManager.onDisconnected = {
            runOnUiThread {
                val current = tvStatus.text.toString()
                tvStatus.text = "$current\n🔌 Отключено"
            }
        }

        btManager.onDiscoveryFinished = {
            runOnUiThread {
                if (btManager.getDiscoveredDevices().isEmpty()) {
                    tvStatus.text = "❌ Устройства не найдены\nУбедитесь, что модуль включён и рядом"
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

    // Проверка ВСЕХ необходимых разрешений
    private fun hasRequiredPermissions(): Boolean {
        // Геолокация ОБЯЗАТЕЛЬНА для сканирования на всех версиях Android 6.0+
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return false
        }

        // Для Android 12+ нужны дополнительные разрешения
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        return true
    }

    // Явный запрос ВСЕХ необходимых разрешений
    @RequiresApi(Build.VERSION_CODES.M)
    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        // Обязательно для сканирования
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        // Для Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // Показываем пояснение перед первым запросом
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Toast.makeText(
                this,
                "Для поиска Bluetooth-устройств требуется доступ к геолокации",
                Toast.LENGTH_LONG
            ).show()
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun updateStatus() {
        val sb = StringBuilder()

        if (!btManager.isBluetoothSupported()) {
            sb.append("❌ Bluetooth не поддерживается этим устройством\n")
        } else {
            sb.append("✅ Bluetooth поддерживается устройством\n")
        }

        if (!btManager.isBluetoothEnabled()) {
            sb.append("❌ Bluetooth отключен. Включите в настройках устройства.\n")
        } else {
            sb.append("✅ Bluetooth включен и готов к работе\n")
        }

        if (!hasRequiredPermissions()) {
            sb.append("⚠️ Требуются разрешения для поиска устройств\n")
        } else {
            sb.append("✅ Все необходимые разрешения предоставлены\n")
        }

        if (btManager.debugMode) {
            sb.append("🔧 Режим отладки: ВКЛЮЧЕН\n")
        }

        tvStatus.text = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        btManager.destroy()
    }
}