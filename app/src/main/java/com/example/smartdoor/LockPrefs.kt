package com.example.smartdoor

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Хранит список замков: uid → (name, secretBytes).
 * Использует EncryptedSharedPreferences с fallback на обычные prefs.
 */
class LockPrefs(context: Context) {

    companion object {
        private const val TAG = "LockPrefs"
        private const val PREFS = "smartdoor_locks"
    }

    private val prefs: SharedPreferences = try {
        val mk = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS, mk, context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.w(TAG, "EncryptedSharedPreferences недоступен", e)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    fun save(uid: Long, name: String, secretBytes: ByteArray) {
        prefs.edit()
            .putString("secret_$uid", Base64.encodeToString(secretBytes, Base64.NO_WRAP))
            .putString("name_$uid", name)
            .apply()
    }

    fun delete(uid: Long) {
        prefs.edit().remove("secret_$uid").remove("name_$uid").apply()
    }

    fun rename(uid: Long, newName: String) {
        if (prefs.contains("secret_$uid"))
            prefs.edit().putString("name_$uid", newName).apply()
    }

    fun getAll(): List<LockEntry> = prefs.all.keys
        .filter { it.startsWith("secret_") }
        .mapNotNull { key ->
            val uid = key.removePrefix("secret_").toLongOrNull() ?: return@mapNotNull null
            val b64 = prefs.getString(key, null) ?: return@mapNotNull null
            val secret = Base64.decode(b64, Base64.NO_WRAP)
            val name = prefs.getString("name_$uid", "Замок ${String.format("%08d", uid)}") ?: ""
            LockEntry(uid, name, secret)
        }
        .sortedBy { it.uid }

    fun isEmpty() = getAll().isEmpty()
}

data class LockEntry(
    val uid: Long,
    val name: String,
    val secretBytes: ByteArray
) {
    val uidStr: String get() = String.format("%08d", uid)
    override fun equals(other: Any?) = other is LockEntry && uid == other.uid
    override fun hashCode() = uid.hashCode()
}