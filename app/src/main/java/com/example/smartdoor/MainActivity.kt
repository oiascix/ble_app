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

    private lateinit var doorManager: ArduinoDoorManager
    private lateinit var tvStatus: TextView
    private var isDebugMode = false

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

        doorManager = ArduinoDoorManager(this)
        tvStatus = findViewById(R.id.tvStatus)

        findViewById<Button>(R.id.btnOpenDoor).setOnClickListener {
            doorManager.openDoor()
        }

        findViewById<Button>(R.id.btnSetup).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        // Кнопка для переключения режима отладки
        findViewById<Button>(R.id.btnDebug)?.setOnClickListener {
            isDebugMode = !isDebugMode
            if (isDebugMode) {
                doorManager.enableDebugMode()
                Toast.makeText(this, "🔧 Режим отладки: ВКЛ (сканирование всех устройств)", Toast.LENGTH_LONG).show()
            } else {
                doorManager.disableDebugMode()
                Toast.makeText(this, "🔧 Режим отладки: ВЫКЛ (фильтрация по UUID)", Toast.LENGTH_LONG).show()
            }
        }

        checkPermissions()
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

    private fun updateStatus() {
        val status = StringBuilder()

        // Проверка BLE
        if (!doorManager.isBleSupported()) {
            status.append("❌ BLE не поддерживается!\n")
        } else {
            status.append("✅ BLE поддерживается\n")
        }

        // Проверка Bluetooth
        if (!doorManager.isBluetoothEnabled()) {
            status.append("❌ Bluetooth отключен!\n")
        } else {
            status.append("✅ Bluetooth включен\n")
        }

        // Проверка разрешений
        if (!doorManager.hasPermissions()) {
            status.append("❌ Нет разрешений\n")
        } else {
            status.append("✅ Разрешения получены\n")
        }

        // Проверка конфигурации
        doorManager.isConfigured { configured ->
            runOnUiThread {
                if (!configured) {
                    status.append("⚠️ Не настроено fixed_time\n")
                } else {
                    status.append("✅ fixed_time настроено\n")
                }
                tvStatus.text = status.toString()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        doorManager.destroy()
    }
}