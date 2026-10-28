// 负责验证系统代理设置的接入的行为与边界。

package dev.molifox.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.nio.file.Path
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SystemProxyTest {
    @TempDir lateinit var directory: Path
    private val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 18080))

    private fun nativeProxy() =
        object : ProxySelector() {
            override fun select(uri: URI?) = listOf(proxy)

            override fun connectFailed(uri: URI?, sa: SocketAddress?, error: IOException?) {}
        }

    @Test
    fun `system mode uses OS proxy and direct or manual modes take effect immediately`() {
        var system = true
        var manual: Proxy? = null
        NetworkClient(
                { "official" },
                { manual },
                { system },
                SystemProxyResolver(nativeProxy(), { null }),
            )
            .use { network ->
                val selector = network.client.proxySelector
                val uri = URI("https://libraries.minecraft.net/test.jar")
                assertEquals(listOf(proxy), selector.select(uri))
                system = false
                assertEquals(listOf(Proxy.NO_PROXY), selector.select(uri))
                manual = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 18081))
                assertEquals(listOf(manual), selector.select(uri))
                assertEquals(
                    listOf(Proxy.NO_PROXY),
                    selector.select(URI("http://127.0.0.1:20000/test")),
                )
            }
    }

    @Test
    fun `Linux proxy environment and bypass list work without OS desktop services`() {
        val environment =
            mapOf(
                "HTTPS_PROXY" to "http://proxy.example:8080",
                "NO_PROXY" to ".internal.example,api.example:443",
            )
        val selector = SystemProxyResolver(null, environment::get)
        val selected =
            selector.select(URI("https://resources.download.minecraft.net/file")).single()
        assertEquals(Proxy.Type.HTTP, selected.type())
        assertEquals("proxy.example", (selected.address() as InetSocketAddress).hostString)
        assertEquals(
            listOf(Proxy.NO_PROXY),
            selector.select(URI("https://server.internal.example/file")),
        )
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("https://api.example:443/file")))
        assertEquals(listOf(Proxy.NO_PROXY), selector.select(URI("http://localhost:8080/test")))
    }

    @Test
    fun `new profiles inherit system proxy and old explicit settings retain their meaning`() {
        assertEquals(
            "system",
            LauncherStore(DesktopPlatform(), directory.resolve("new"))
                .data
                .settings
                .effectiveProxyMode(),
        )
        val store = LauncherStore(DesktopPlatform(), directory.resolve("old"))
        store.mutate { it.copy(settings = it.settings.copy(proxyMode = "", proxyEnabled = false)) }
        val file = store.directories.configuration
        val legacy = readObject(file)
        legacy.obj("settings").remove("proxyMode")
        writeJson(file, legacy)
        assertEquals(
            "direct",
            LauncherStore(DesktopPlatform(), directory.resolve("old"))
                .data
                .settings
                .effectiveProxyMode(),
        )
        assertEquals("manual", LauncherSettings(proxyEnabled = true).effectiveProxyMode())
    }
}
