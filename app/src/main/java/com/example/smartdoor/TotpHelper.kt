package com.example.smartdoor

import android.util.Log
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Генерация TOTP, совместимая с Arduino SimpleHOTP.cpp
 *
 * Arduino verifyTOTPFromString() ожидает строку ровно 18 символов:
 *   позиции 0..7  — TOTP (8 цифр, с ведущими нулями)
 *   позиции 8..17 — Unix timestamp (10 цифр, с ведущими нулями)
 *
 * generateTOTP8() на Arduino:
 *   counter = unixTime  (без деления на 30!)
 *   HMAC-SHA1(key, counter) → dynamic truncation → % 10^8
 */
object TotpHelper {

    private const val TAG = "TotpHelper"

    private val POW10 = longArrayOf(
        1, 10, 100, 1_000, 10_000,
        100_000, 1_000_000, 10_000_000, 100_000_000
    )

    /**
     * Генерирует строку-команду для Arduino.
     * Формат: 8 цифр TOTP + 10 цифр timestamp = 18 символов.
     *
     * @param secretBytes  Сырые байты ключа (20 байт из EEPROM Arduino)
     * @param unixTime     Unix-время в секундах
     */
    fun buildCommand(secretBytes: ByteArray, unixTime: Long): String {
        val totp = generate(secretBytes, unixTime)
        val time = String.format("%010d", unixTime)
        return "$totp$time"   // ровно 18 символов
    }

    /**
     * 8-значный TOTP с ведущими нулями.
     */
    fun generate(secretBytes: ByteArray, unixTime: Long): String {
        val code = generateRaw(secretBytes, unixTime)
        return String.format("%08d", code)
    }

    // RFC 4226 dynamic truncation — точная копия SimpleHOTP::generateHOTP()
    private fun generateRaw(secret: ByteArray, counter: Long): Long {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val hash = mac.doFinal(longToBigEndian(counter))   // 20 байт

        val offset = hash[hash.size - 1].toInt() and 0x0F
        val dt = (
                ((hash[offset    ].toInt() and 0xFF) shl 24) or
                        ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                        ((hash[offset + 2].toInt() and 0xFF) shl  8) or
                        (hash[offset + 3].toInt() and 0xFF)
                ) and 0x7FFFFFFF

        return (dt.toLong() and 0xFFFFFFFFL) % POW10[8]
    }

    private fun longToBigEndian(v: Long): ByteArray = ByteArray(8) { i ->
        ((v ushr ((7 - i) * 8)) and 0xFF).toByte()
    }

    // ── Base32 ────────────────────────────────────────────────────────────────

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** Декодирует Base32-строку (из Serial Monitor Arduino) в сырые байты. */
    fun base32Decode(input: String): ByteArray {
        val clean = input.uppercase().trimEnd('=').filter { it in ALPHABET }
        var bits = 0; var value = 0
        val out = mutableListOf<Byte>()
        for (ch in clean) {
            value = (value shl 5) or ALPHABET.indexOf(ch)
            bits += 5
            if (bits >= 8) { bits -= 8; out.add(((value ushr bits) and 0xFF).toByte()) }
        }
        return out.toByteArray()
    }

    /** Кодирует байты в Base32 (для отображения/проверки). */
    fun base32Encode(data: ByteArray): String {
        var bits = 0; var value = 0
        val sb = StringBuilder()
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { bits -= 5; sb.append(ALPHABET[(value ushr bits) and 0x1F]) }
        }
        if (bits > 0) sb.append(ALPHABET[(value shl (5 - bits)) and 0x1F])
        return sb.toString()
    }
}