package me.d3sox.nevumi.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.encodeUtf8
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidCredentialVault(context: Context, namespace: String = context.packageName) : CredentialVault {
    private val directory = File(context.filesDir, "credentials/${namespace.encodeUtf8().sha256().hex()}")
    private val alias = "MusicPlayer.$namespace"
    private fun file(key: String) = File(directory, key.encodeUtf8().sha256().hex())
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    // Concurrent first saves, even from separate vault instances, must not each generate a key and replace the other's.
    private fun encryptionKey(): SecretKey = synchronized(keyCreation) {
        val keys = keyStore()
        if (keys.containsAlias(alias)) keys.getKey(alias, null) as SecretKey
        else KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override suspend fun save(key: String, secret: String) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val bytes = cipher.iv + cipher.doFinal(secret.encodeToByteArray())
        val atomic = AtomicFile(file(key))
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) {
        if (!file(key).exists()) return@withContext null
        val bytes = AtomicFile(file(key)).readFully()
        val secretKey = keyStore().getKey(alias, null) as? SecretKey ?: return@withContext null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    }
    override suspend fun delete(key: String) = withContext(Dispatchers.IO) { AtomicFile(file(key)).delete() }

    private companion object {
        val keyCreation = Any()
    }
}
