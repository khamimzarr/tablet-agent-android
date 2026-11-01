package id.khamimzarr.tabletagent

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Skema kripto wajib sama persis dengan server (main.js):
 * AES-256-CBC, kunci = sha256(token), format pesan "<iv_hex>:<ciphertext_hex>".
 */
object Crypto {

    private fun key(token: String): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)), "AES")

    fun encrypt(token: String, text: String): String {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key(token), IvParameterSpec(iv))
        val ct = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return iv.toHex() + ":" + ct.toHex()
    }

    fun decrypt(token: String, payload: String): String {
        val parts = payload.split(":")
        if (parts.size != 2) return payload
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key(token), IvParameterSpec(parts[0].hexToBytes()))
            String(cipher.doFinal(parts[1].hexToBytes()), Charsets.UTF_8)
        } catch (e: Exception) {
            payload
        }
    }
}

fun ByteArray.toHex(): String = buildString {
    for (b in this@toHex) {
        val v = b.toInt() and 0xFF
        append("0123456789abcdef"[v shr 4])
        append("0123456789abcdef"[v and 0x0F])
    }
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex ganjil" }
    return ByteArray(length / 2) {
        substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
