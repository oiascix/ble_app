package com.example.smartdoor

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val etDeviceName = findViewById<EditText>(R.id.etDeviceName)
        val etCommand = findViewById<EditText>(R.id.etCommand)
        val btnSave = findViewById<Button>(R.id.btnSave)

        // Загрузка текущих настроек
        val prefs = getPreferences(MODE_PRIVATE)
        val currentName = prefs.getString("device_name", "HC-05")
        val currentCommand = prefs.getString("command", "OPEN")

        etDeviceName.setText(currentName)
        etCommand.setText(currentCommand)

        btnSave.setOnClickListener {
            val newName = etDeviceName.text.toString().trim()
            val newCommand = etCommand.text.toString().trim()

            if (newName.isEmpty()) {
                Toast.makeText(this, "⚠️ Введите имя устройства", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (newCommand.isEmpty()) {
                Toast.makeText(this, "⚠️ Введите команду", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Сохранение настроек
            prefs.edit()
                .putString("device_name", newName)
                .putString("command", newCommand)
                .apply()

            Toast.makeText(this, "✅ Настройки сохранены:\nУстройство: $newName\nКоманда: $newCommand", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}