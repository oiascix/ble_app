package com.example.smartdoor

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SetupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val doorManager = ArduinoDoorManager(this)
        val tilFixedTime = findViewById<TextInputLayout>(R.id.tilFixedTime)
        val etFixedTime = findViewById<TextInputEditText>(R.id.etFixedTime)
        val btnSave = findViewById<Button>(R.id.btnSave)

        btnSave.setOnClickListener {
            val fixedTimeStr = etFixedTime.text.toString().trim()

            fixedTimeStr.toLongOrNull()?.let { fixedTime ->
                doorManager.saveFixedTime(fixedTime)
                Toast.makeText(this, "✅ Fixed time: $fixedTime", Toast.LENGTH_LONG).show()
                finish()
            } ?: run {
                tilFixedTime.error = "Неверный формат числа"
            }
        }
    }
}
