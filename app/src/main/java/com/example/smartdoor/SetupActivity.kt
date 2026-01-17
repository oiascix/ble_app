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

        val doorManager = BleDoorManager(this)
        val tilSecret = findViewById<TextInputLayout>(R.id.tilSecretKey)
        val etSecret = findViewById<TextInputEditText>(R.id.etSecretKey)
        val btnSave = findViewById<Button>(R.id.btnSaveKey)

        btnSave.setOnClickListener {
            val secret = etSecret.text?.toString()?.trim()?.uppercase() ?: ""

            if (secret.length < 16) {
                tilSecret.error = "Минимум 16 символов"
                return@setOnClickListener
            }

            if (!secret.matches(Regex("^[A-Z2-7]+$"))) {
                tilSecret.error = "Допустимы только A-Z и цифры 2-7"
                return@setOnClickListener
            }

            tilSecret.error = null
            doorManager.saveSecret(secret)
            Toast.makeText(this, "Ключ сохранён", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
