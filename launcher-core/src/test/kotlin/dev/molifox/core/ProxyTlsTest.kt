// 用本地 HTTPS 和 CONNECT 代理验证 TLS 回退及证书校验边界。

package dev.molifox.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.TlsVersion
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProxyTlsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `proxy closing initial handshake retries with TLS 12 and trusted certificate`() {
        withTrustedFixture("fixture.test") { certificate ->
            MockWebServer().use { server ->
                server.useHttps(
                    HandshakeCertificates.Builder()
                        .heldCertificate(certificate)
                        .build()
                        .sslSocketFactory(),
                    false,
                )
                server.enqueue(MockResponse().setBody("metadata"))
                server.start()
                ConnectProxy(server.port, closeFirst = true).use { proxy ->
                    NetworkClient(proxyProvider = { proxy.address }).use { network ->
                        assertEquals(
                            "metadata",
                            runBlocking {
                                network.text("https://fixture.test:${server.port}/manifest")
                            },
                        )
                        assertTrue(proxy.connections.get() >= 2)
                        assertEquals(TlsVersion.TLS_1_2, server.takeRequest().handshake?.tlsVersion)
                    }
                }
            }
        }
    }

    @Test
    fun `hostname mismatch is still rejected through a proxy`() {
        withTrustedFixture("different.test") { certificate ->
            MockWebServer().use { server ->
                server.useHttps(
                    HandshakeCertificates.Builder()
                        .heldCertificate(certificate)
                        .build()
                        .sslSocketFactory(),
                    false,
                )
                server.start()
                ConnectProxy(server.port).use { proxy ->
                    NetworkClient(proxyProvider = { proxy.address }).use { network ->
                        val failure =
                            assertFailsWith<IOException> {
                                runBlocking {
                                    network.text("https://fixture.test:${server.port}/manifest")
                                }
                            }
                        assertTrue(
                            generateSequence<Throwable>(failure) { it.cause }
                                .any { it is javax.net.ssl.SSLPeerUnverifiedException }
                        )
                        assertEquals(1, proxy.connections.get())
                    }
                }
            }
        }
    }

    private fun withTrustedFixture(host: String, action: (HeldCertificate) -> Unit) {
        val certificate =
            HeldCertificate.Builder().commonName(host).addSubjectAlternativeName(host).build()
        val file = directory.resolve("fixture-trust.p12")
        val password = "fixture-only".toCharArray()
        val store =
            KeyStore.getInstance("PKCS12").apply {
                load(null, password)
                setCertificateEntry("fixture", certificate.certificate)
            }
        file.outputStream().use { store.store(it, password) }
        val properties =
            mapOf(
                "javax.net.ssl.trustStore" to file.toString(),
                "javax.net.ssl.trustStorePassword" to String(password),
                "javax.net.ssl.trustStoreType" to "PKCS12",
            )
        val previous = properties.mapValues { System.getProperty(it.key) }
        try {
            properties.forEach { (key, value) -> System.setProperty(key, value) }
            assertNull(
                windowsTlsConfiguration(),
                "Explicit trust-store must not be expanded with Windows roots",
            )
            action(certificate)
        } finally {
            previous.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    private class ConnectProxy(
        private val upstreamPort: Int,
        private val closeFirst: Boolean = false,
    ) : AutoCloseable {
        private val listener = ServerSocket(0, 8, java.net.InetAddress.getLoopbackAddress())
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val connections = AtomicInteger()
        val address = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", listener.localPort))
        private val accepting =
            thread(isDaemon = true, name = "fixture-connect") {
                while (!listener.isClosed) {
                    val client =
                        try {
                            listener.accept()
                        } catch (_: IOException) {
                            break
                        }
                    sockets.add(client)
                    val index = connections.incrementAndGet()
                    thread(isDaemon = true, name = "fixture-tunnel") {
                        tunnel(client, closeFirst && index == 1)
                    }
                }
            }

        private fun tunnel(client: Socket, closeHandshake: Boolean) {
            try {
                client.soTimeout = 5000
                val input = client.getInputStream()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n") && header.length < 16_384) {
                    val byte = input.read()
                    if (byte < 0) return
                    header.append(byte.toChar())
                }
                check(header.startsWith("CONNECT fixture.test:$upstreamPort "))
                client
                    .getOutputStream()
                    .write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                if (closeHandshake) {
                    input.readNBytes(5)
                    return
                }
                Socket("127.0.0.1", upstreamPort).use { remote ->
                    sockets.add(remote)
                    val returning =
                        thread(isDaemon = true, name = "fixture-return") {
                            runCatching {
                                remote.getInputStream().transferTo(client.getOutputStream())
                            }
                            runCatching { client.shutdownOutput() }
                        }
                    runCatching { input.transferTo(remote.getOutputStream()) }
                    runCatching { remote.shutdownOutput() }
                    returning.join(6000)
                    sockets.remove(remote)
                }
            } finally {
                client.close()
                sockets.remove(client)
            }
        }

        override fun close() {
            listener.close()
            sockets.forEach { runCatching { it.close() } }
            accepting.join(2000)
        }
    }
}
