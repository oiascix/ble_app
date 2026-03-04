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
        val btnSave = findViewById<Button>(R.id.btnSave)

        // Загрузка текущего имени
        val prefs = getPreferences(MODE_PRIVATE)
        val currentName = prefs.getString("device_name", "Arduino Lock")
        etDeviceName.setText(currentName)

        btnSave.setOnClickListener {
            val newName = etDeviceName.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(this, "Введите имя устройства", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Сохранение имени
            prefs.edit().putString("device_name", newName).apply()

            Toast.makeText(this, "✅ Имя устройства сохранено: $newName", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}