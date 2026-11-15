package me.d3sox.nevumi.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.d3sox.nevumi.security.CredentialVault
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncTlsTest {
    private val day = 86_400_000L

    private fun identity(notBefore: Long, notAfter: Long): Pair<String, String> {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val subject = X500Name("CN=Nevumi-test")
        val signer = JcaContentSignerBuilder("SHA256withECDSA").setProvider(BouncyCastleProvider()).build(keys.private)
        val certificate = JcaX509CertificateConverter().getCertificate(JcaX509v3CertificateBuilder(subject,
            BigInteger(160, SecureRandom()).setBit(159), Date(notBefore), Date(notAfter), subject, keys.public).build(signer))
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(keys.private.encoded) to encoder.encodeToString(certificate.encoded)
    }

    private fun vault(key: String, identity: Pair<String, String>) = object : CredentialVault {
        private val secrets = ConcurrentHashMap(mapOf(key to """{"key":"${identity.first}","certificate":"${identity.second}"}"""))
        override suspend fun save(key: String, secret: String) { secrets[key] = secret }
        override suspend fun read(key: String) = secrets[key]
        override suspend fun delete(key: String) { secrets.remove(key) }
    }

    @Test
    fun pinnedCertificatesOutsideTheirValidityPeriodStillConnect() = runBlocking {
        val now = System.currentTimeMillis()
        // One device's clock ran ahead when it created its identity; the other identity has expired.
        val early = SyncTls.load(vault("nevumi-sync-identity:early", identity(now + day, now + 3650 * day)), "early")
        val expired = SyncTls.load(vault("nevumi-sync-identity:expired", identity(now - 3650 * day, now - day)), "expired")
        val server = early.context { it == expired.fingerprint }.serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.needClientAuth = true
        server.use {
            val accepted = async(Dispatchers.IO) {
                (server.accept() as SSLSocket).use { socket -> socket.startHandshake(); socket.outputStream.write(1); socket.outputStream.flush() }
            }
            val received = (expired.context { it == early.fingerprint }.socketFactory
                .createSocket(InetAddress.getLoopbackAddress(), server.localPort) as SSLSocket).use { socket ->
                socket.soTimeout = 5_000
                socket.startHandshake()
                socket.inputStream.read()
            }
            accepted.await()
            assertEquals(1, received)
        }
    }
}
