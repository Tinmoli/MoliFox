// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 导出格式测试共用的临时目录、实例工厂与归档读取辅助。

package dev.molifox.core

import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

abstract class PackExportTestSupport {
    @TempDir lateinit var temporary: Path
    protected val metadata = MrpackExportMetadata("多格式包", "1.2.3", "中文说明")

    protected suspend fun fixture(
        loader: LoaderType = LoaderType.Fabric,
        loaderVersion: String = "0.15.11",
        action: suspend (LauncherStore, GameInstance, Path) -> Unit,
    ) {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val instance =
            GameInstance(
                name = "Source",
                gameVersion = "1.20.1",
                versionId = "1.20.1",
                loader = loader,
                loaderVersion = loaderVersion,
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        val root = store.instancePath(instance.id).apply { createDirectories() }
        action(store, instance, root)
    }

    protected fun service(
        store: LauncherStore,
        network: NetworkClient,
        server: MockWebServer? = null,
    ) = ContentService(
        store,
        network,
        MinecraftInstaller(store, network, JavaService(store, network)),
        server?.loopbackUrl("/v2")?.toString() ?: "https://api.modrinth.com/v2",
    )

    protected fun write(
        root: Path,
        path: String,
        content: String = path,
    ) = root.resolve(path).apply {
        parent.createDirectories()
        writeText(content)
    }

    protected fun listing(zip: ZipFile): Set<String> =
        zip
            .entries()
            .asSequence()
            .map { it.name }
            .toSet()
}
