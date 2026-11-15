package me.d3sox.nevumi.sync

import me.d3sox.nevumi.security.CredentialVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.*

@Serializable
private data class StoredSyncIdentity(val key: String, val certificate: String) {
    override fun toString() = "StoredSyncIdentity(<protected>)"
}

internal class SyncTls(private val key: PrivateKey, private val certificate: X509Certificate) {
    val fingerprint: String get() = fingerprint(certificate)
    fun context(accept: (String) -> Boolean): SSLContext {
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("nevumi-sync", key, CharArray(0), arrayOf(certificate))
        }
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, CharArray(0)) }
        val trust = object : X509ExtendedTrustManager() {
            private fun check(chain: Array<out X509Certificate>?) {
                // The pinned fingerprint is the trust anchor, so the validity period is ignored: clocks differ between devices.
                val peer = chain?.singleOrNull() ?: throw CertificateException("A device certificate is required")
                if (!accept(fingerprint(peer))) throw CertificateException("Device certificate does not match")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, type: String?) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, type: String?) = check(chain)
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, type: String?, socket: Socket?) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, type: String?, socket: Socket?) = check(chain)
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, type: String?, engine: SSLEngine?) = check(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, type: String?, engine: SSLEngine?) = check(chain)
        }
        return SSLContext.getInstance("TLS").apply { init(manager.keyManagers, arrayOf(trust), SecureRandom()) }
    }

    companion object {
        fun fingerprint(certificate: java.security.cert.Certificate): String = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }

        suspend fun load(vault: CredentialVault, deviceId: String): SyncTls {
            val stored = vault.read("nevumi-sync-identity:$deviceId")?.let { Json.decodeFromString<StoredSyncIdentity>(it) }
            if (stored != null) {
                val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(stored.key)))
                val certificate = CertificateFactory.getInstance("X.509").generateCertificate(
                    ByteArrayInputStream(Base64.getDecoder().decode(stored.certificate))) as X509Certificate
                return SyncTls(key, certificate)
            }
            val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            val keys = generator.generateKeyPair()
            val now = System.currentTimeMillis()
            val subject = X500Name("CN=Nevumi-$deviceId")
            val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider(BouncyCastleProvider()).build(keys.private)
            val certificate = JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(subject,
                BigInteger(160, SecureRandom()).setBit(159), Date(now - 86_400_000L), Date(now + 315_576_000_000L), subject, keys.public).build(signer))
            val encoder = Base64.getEncoder()
            vault.save("nevumi-sync-identity:$deviceId", Json.encodeToString(StoredSyncIdentity(
                encoder.encodeToString(keys.private.encoded), encoder.encodeToString(certificate.encoded))))
            return SyncTls(keys.private, certificate)
        }
    }
}
