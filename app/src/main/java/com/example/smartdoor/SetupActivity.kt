package com.example.smartdoor

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class SetupActivity : AppCompatActivity() {

    private lateinit var prefs: LockPrefs
    private lateinit var etQrData: EditText

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val scanned = result.data?.getStringExtra(QrScanActivity.RESULT_KEY) ?: return@registerForActivityResult
            etQrData.setText(scanned)
            Toast.makeText(this, "✅ QR отсканирован", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        prefs = LockPrefs(this)

        val etName      = findViewById<EditText>(R.id.etName)
        val tabQr       = findViewById<RadioButton>(R.id.tabQr)
        val tabManual   = findViewById<RadioButton>(R.id.tabManual)
        val panelQr     = findViewById<LinearLayout>(R.id.panelQr)
        val panelManual = findViewById<LinearLayout>(R.id.panelManual)
        etQrData        = findViewById(R.id.etQrData)
        val etBase32    = findViewById<EditText>(R.id.etBase32)
        val etUid       = findViewById<EditText>(R.id.etUid)
        val btnScan     = findViewById<Button>(R.id.btnScan)
        val btnSave     = findViewById<Button>(R.id.btnSave)
        val btnBack     = findViewById<ImageButton>(R.id.btnBack)

        btnBack.setOnClickListener { finish() }

        tabQr.setOnCheckedChangeListener { _, checked ->
            if (checked) { panelQr.visibility = View.VISIBLE; panelManual.visibility = View.GONE }
        }
        tabManual.setOnCheckedChangeListener { _, checked ->
            if (checked) { panelQr.visibility = View.GONE; panelManual.visibility = View.VISIBLE }
        }

        btnScan.setOnClickListener {
            qrLauncher.launch(Intent(this, QrScanActivity::class.java))
        }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            if (tabQr.isChecked) {
                val qr = etQrData.text.toString().trim()
                if (qr.isEmpty()) { toast("Отсканируйте QR или введите строку"); return@setOnClickListener }
                saveFromQr(qr, name)
            } else {
                val b32 = etBase32.text.toString().trim()
                val uid = etUid.text.toString().trim()
                if (b32.isEmpty()) { toast("Введите Base32 ключ"); return@setOnClickListener }
                if (uid.isEmpty()) { toast("Введите UID"); return@setOnClickListener }
                saveManual(b32, uid, name)
            }
        }
    }

    private fun saveFromQr(qr: String, name: String) {
        try {
            if (qr.length < 5) { toast("Слишком короткая строка"); return }
            val base32 = qr.drop(4).trimEnd('=')
            if (base32.isEmpty()) { toast("Неверный формат QR"); return }
            val secret = TotpHelper.base32Decode(base32)
            if (secret.size < 10) { toast("Ключ слишком короткий"); return }
            val fixedTime = 1769272850L
            val uidStr = TotpHelper.generate(secret, fixedTime)
            val uid = uidStr.toLong()
            val lockName = name.ifBlank { "Замок $uidStr" }
            prefs.save(uid, lockName, secret)
            toast("✅ Замок «$lockName» добавлен!\nUID: $uidStr")
            finish()
        } catch (e: Exception) {
            toast("Ошибка разбора QR: ${e.message}")
        }
    }

    private fun saveManual(base32: String, uidInput: String, name: String) {
        try {
            val secret = TotpHelper.base32Decode(base32)
            if (secret.size < 10) { toast("Ключ слишком короткий"); return }
            val uid = uidInput.trim().toLongOrNull() ?: run { toast("Неверный UID"); return }
            val lockName = name.ifBlank { "Замок ${String.format("%08d", uid)}" }
            prefs.save(uid, lockName, secret)
            toast("✅ Замок «$lockName» добавлен!")
            finish()
        } catch (e: Exception) {
            toast("Ошибка: ${e.message}")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}