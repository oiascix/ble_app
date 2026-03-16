package com.example.smartdoor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.integration.android.IntentIntegrator

/**
 * QR-сканер на базе ZXing IntentIntegrator.
 * Не использует ScanContract — работает через старый onActivityResult.
 */
class QrScanActivity : AppCompatActivity() {

    companion object {
        const val RESULT_KEY = "qr_result"
        private const val CAMERA_PERM_REQUEST = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERM_REQUEST
            )
        } else {
            launchScanner()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERM_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            launchScanner()
        } else {
            Toast.makeText(this, "Нужно разрешение на камеру", Toast.LENGTH_LONG).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun launchScanner() {
        IntentIntegrator(this).apply {
            setPrompt("Наведите камеру на QR-код Arduino")
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
            initiateScan()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                val intent = Intent().putExtra(RESULT_KEY, result.contents)
                setResult(Activity.RESULT_OK, intent)
            } else {
                setResult(Activity.RESULT_CANCELED)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
    }
}