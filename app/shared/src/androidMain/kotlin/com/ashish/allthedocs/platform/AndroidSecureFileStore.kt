package com.ashish.allthedocs.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidSecureFileStore(context: Context) : SecureFileStore {
    private val directory = File(context.filesDir, "vault")

    override suspend fun write(name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(bytes)
        val temp = File(directory, "${checkedName(name)}.tmp")
        temp.outputStream().use { out ->
            out.write(iv.size)
            out.write(iv)
            out.write(encrypted)
        }
        check(temp.renameTo(File(directory, name))) { "Couldn't store the document" }
    }

    override suspend fun read(name: String): ByteArray = withContext(Dispatchers.IO) {
        val data = File(directory, checkedName(name)).readBytes()
        val ivLength = data[0].toInt()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 1, ivLength))
        cipher.doFinal(data, 1 + ivLength, data.size - 1 - ivLength)
    }

    override suspend fun delete(name: String) {
        withContext(Dispatchers.IO) { File(directory, checkedName(name)).delete() }
    }

    private fun checkedName(name: String): String {
        require(NAME_PATTERN.matches(name)) { "Invalid vault file name" }
        return name
    }

    private fun key(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }
            .generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "allthedocs_vault_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val NAME_PATTERN = Regex("[A-Za-z0-9-]+\\.bin")
        val lock = Any()
    }
}
