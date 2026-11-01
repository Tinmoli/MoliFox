// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责整合包导出测试共用的实例夹具、写入与远程响应构造。

package dev.molifox.core

import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** 导出相关测试的共享夹具；子类只负责各自的断言。 */
abstract class MrpackExportTestBase {
    @TempDir lateinit var temporary: Path
    protected val metadata = MrpackExportMetadata("茉狐测试包", "1.0.0", "中文简介")

    protected suspend fun fixture(action: suspend (LauncherStore, ContentService, GameInstance, Path) -> Unit) {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val instance =
            GameInstance(
                name = "Source",
                gameVersion = "1.20.1",
                versionId = "1.20.1",
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        val root = store.instancePath(instance.id).apply { createDirectories() }
        NetworkClient().use { network ->
            val service =
                ContentService(
                    store,
                    network,
                    MinecraftInstaller(store, network, JavaService(store, network)),
                )
            action(store, service, instance, root)
        }
    }

    protected fun write(
        root: Path,
        path: String,
        content: String = path,
    ) = root.resolve(path).apply {
        parent.createDirectories()
        writeText(content)
    }

    protected fun service(
        store: LauncherStore,
        network: NetworkClient,
        server: MockWebServer,
    ) = ContentService(
        store,
        network,
        MinecraftInstaller(store, network, JavaService(store, network)),
        server.loopbackUrl("/v2").toString(),
    )

    protected fun remote(
        file: MrpackExportFile,
        url: String = "https://cdn.modrinth.com/data/project/versions/version/file.jar",
    ) = gson
        .toJsonTree(
            mapOf(
                "id" to "version",
                "files" to
                    listOf(
                        mapOf(
                            "url" to url,
                            "size" to file.size,
                            "hashes" to mapOf("sha1" to file.sha1, "sha512" to file.sha512),
                        ),
                    ),
            ),
        ).asJsonObject
}
