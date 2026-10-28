// 负责验证设置项与运行行为的接线的行为与边界。

package dev.molifox.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.test.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** 板块④设置接线的回归测试：版本列表源、缓存目录、代理、更新检查都必须作用于真实行为。 */
class SettingsWiringTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `version list source selects the manifest endpoint`() {
        assertEquals(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            versionManifestUrl("official"),
        )
        assertEquals(
            "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json",
            versionManifestUrl("bmclapi"),
        )
    }

    @Test
    fun `cache directory follows the download cache setting`() {
        val store = LauncherStore(DesktopPlatform(), directory)
        assertEquals(directory.resolve("cache"), store.cacheDirectory)
        val custom = directory.resolve("custom-cache")
        store.mutate {
            it.copy(settings = it.settings.copy(downloadCachePath = custom.toString()))
        }
        assertEquals(custom, store.cacheDirectory)
    }

    @Test
    fun `proxy setting feeds the client proxy selector dynamically`() {
        var proxy: Proxy? = null
        val client = NetworkClient({ "official" }, { proxy }).client
        val uri = URI("https://example.com")
        assertEquals(listOf(Proxy.NO_PROXY), client.proxySelector.select(uri))
        val configured = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 18080))
        proxy = configured
        assertEquals(listOf(configured), client.proxySelector.select(uri))
        val socks = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 18081))
        proxy = socks
        assertEquals(listOf(socks), client.proxySelector.select(uri))
        proxy = null
        assertEquals(listOf(Proxy.NO_PROXY), client.proxySelector.select(uri))
    }

    @Test
    fun `gameRoot migration runs exactly once across restarts`() {
        val data = directory.resolve("moli-migrate")
        // 模拟旧版配置：写入 semantics=0 的 gameRoot。
        val first = LauncherStore(DesktopPlatform(), data)
        first.mutate {
            it.copy(
                settings =
                    it.settings.copy(
                        gameRoot = data.resolve("games").toString(),
                        gameRootSemantics = 0,
                    )
            )
        }
        val migratedRoot = data.resolve("games").resolve("minecraft")
        // 第二次加载执行一次迁移：gameRoot 下移一级并写入新语义标记。
        val second = LauncherStore(DesktopPlatform(), data)
        assertEquals(migratedRoot.toString(), second.data.settings.gameRoot)
        assertEquals(1, second.data.settings.gameRootSemantics)
        // 第三次加载不得重复迁移。
        val third = LauncherStore(DesktopPlatform(), data)
        assertEquals(migratedRoot.toString(), third.data.settings.gameRoot)
        assertEquals(1, third.data.settings.gameRootSemantics)
    }

    private suspend fun awaitToast(controller: LauncherController, contains: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val toast = controller.state.value.toast
            if (toast != null && toast.contains(contains)) return
            delay(50)
        }
        throw AssertionError(
            "toast 未包含「" +
                contains +
                "」，当前 toast=" +
                controller.state.value.toast +
                "，日志末尾=" +
                controller.state.value.logs.takeLast(4).map { it.message }
        )
    }

    @Test
    fun `update check reads GitHub Releases regardless of legacy manifest setting`() = runBlocking {
        MockWebServer().use { server ->
            // 绑定字面量 127.0.0.1：本机 DNS 会把 localhost 解析成无关域名。
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(
                MockResponse()
                    .setBody(
                        """[{"tag_name":"v9.9.9","name":"MoliFox 9.9.9","body":"测试更新","html_url":"https://github.com/Tinmoli/MoliFox/releases/tag/v9.9.9","draft":false,"prerelease":false,"assets":[]}]"""
                    )
            )
            val api = "http://127.0.0.1:" + server.port + "/repos/Tinmoli/MoliFox"
            LauncherController(
                    dataDirectory = directory.resolve("moli-update"),
                    updateApiBase = api,
                )
                .use { controller ->
                    controller.store.mutate {
                        it.copy(
                            settings =
                                it.settings.copy(
                                    updateManifestUrl = "https://example.test/obsolete.json"
                                )
                        )
                    }
                    controller.checkForUpdates(true)
                    awaitToast(controller, "9.9.9")
                }
            assertEquals("/repos/Tinmoli/MoliFox/releases?per_page=100", server.takeRequest().path)
        }
    }

    @Test
    fun `empty GitHub Releases reports honestly without a custom manifest`() = runBlocking {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            server.enqueue(MockResponse().setBody("[]"))
            LauncherController(
                    dataDirectory = directory.resolve("moli-nomanifest"),
                    updateApiBase = "http://127.0.0.1:${server.port}/repos/Tinmoli/MoliFox",
                )
                .use { controller ->
                    controller.checkForUpdates(true)
                    awaitToast(controller, "尚未发布正式版本")
                }
        }
    }
}
