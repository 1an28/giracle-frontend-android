package com.giracle.android.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ログインのトークンを、端末の鍵 (Android Keystore の AES 鍵) で暗号化して保存する (#27、D-027)。
 * 鍵は端末から取り出せないので、アプリの保存領域を読まれてもトークンは分からない。
 */
interface TokenCipher {
    /** 暗号化した文字列。暗号化できない (端末の鍵が使えない) ときは null */
    fun encrypt(plain: String): String?

    /** 復号した文字列。復号できない (鍵が消えた・壊れた) ときは null */
    fun decrypt(stored: String): String?
}

class KeystoreTokenCipher : TokenCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun encrypt(plain: String): String? = runCatching {
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        VERSION + Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "encrypt failed", it) }.getOrNull()

    override fun decrypt(stored: String): String? = runCatching {
        require(stored.startsWith(VERSION))
        val all = Base64.decode(stored.removePrefix(VERSION), Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, IV_LEN))
        String(c.doFinal(all, IV_LEN, all.size - IV_LEN), Charsets.UTF_8)
    }.onFailure { Log.w(TAG, "decrypt failed", it) }.getOrNull()

    private companion object {
        const val TAG = "TokenCipher"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "giracle-token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val VERSION = "v1:"
    }
}
