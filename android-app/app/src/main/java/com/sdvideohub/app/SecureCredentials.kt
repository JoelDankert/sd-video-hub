package com.sdvideohub.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val baseUrl: String, val username: String, val password: String)

class SecureCredentials(context: Context) {
    private val preferences = context.getSharedPreferences("encrypted-login-v1", Context.MODE_PRIVATE)
    private val alias = "sd-video-hub-login"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun save(value: Credentials) {
        val plain = JSONObjectCompat.encode(value).toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val joined = cipher.iv + cipher.doFinal(plain)
        preferences.edit().putString("payload", Base64.encodeToString(joined, Base64.NO_WRAP)).apply()
    }
    fun load(): Credentials? = try {
        val joined = Base64.decode(preferences.getString("payload", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, joined.copyOfRange(0, 12))) }
        JSONObjectCompat.decode(String(cipher.doFinal(joined.copyOfRange(12, joined.size)), StandardCharsets.UTF_8))
    } catch (_: Exception) { null }
}

private object JSONObjectCompat {
    fun encode(c: Credentials) = org.json.JSONObject().put("base", c.baseUrl).put("user", c.username).put("password", c.password).toString()
    fun decode(value: String) = org.json.JSONObject(value).let { Credentials(it.getString("base"), it.getString("user"), it.getString("password")) }
}
