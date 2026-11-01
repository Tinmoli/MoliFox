// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责存档测试共用的夹具：临时目录、实例、世界、压缩包与锁文件。

package dev.molifox.core

import org.junit.jupiter.api.AfterEach
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

/** 存档测试共用的夹具，每个测试使用独立的 `tests/.tmp/` 目录。 */
abstract class SaveManagerTestSupport(
    workspaceName: String,
) {
    /** 本次测试的数据目录：项目 `tests/.tmp/` 下，测试结束（含失败）递归删除。 */
    protected val temporary: Path = workTestDirectory(workspaceName)

    @AfterEach
    fun cleanUp() {
        removeTestTree(temporary)
    }

    protected fun store(): LauncherStore = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))

    /** 建一个实例：isolate=true 用版本隔离（独占目录），false 共用游戏目录；external 为外部实例。 */
    protected fun instance(
        store: LauncherStore,
        name: String = "Fixture",
        isolate: Boolean = true,
        external: Boolean = false,
    ): GameInstance {
        val instance =
            GameInstance(
                name = name,
                gameVersion = "1.20.1",
                versionId = "1.20.1",
                gameDirectoryName = name,
                loader = LoaderType.Fabric,
                loaderVersion = "0.15.0",
                installed = true,
                managedGameDirectory = isolate,
                isolation = if (isolate) VersionIsolation.Enabled else VersionIsolation.Disabled,
                externalProfile = external,
            )
        store.mutate {
            it.copy(
                instances = it.instances + instance,
                settings = it.settings.copy(isolateVersions = isolate),
            )
        }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    protected fun write(
        file: Path,
        text: String,
    ) {
        file.parent.createDirectories()
        file.writeText(text)
    }

    /** 建一个像样的世界：level.dat、region/ 与 playerdata/。 */
    protected fun world(
        root: Path,
        name: String,
        content: String = "level",
    ) {
        write(root.resolve("saves/$name/level.dat"), content)
        write(root.resolve("saves/$name/region/r.0.0.mca"), "$content-region")
        write(root.resolve("saves/$name/playerdata/player.dat"), "$content-player")
    }

    protected fun zipEntries(archive: Path): Map<String, String> =
        ZipFile(archive.toFile()).use { zip ->
            Collections
                .list(zip.entries())
                .filter { !it.isDirectory }
                .associate { entry ->
                    entry.name to
                        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                }
        }

    protected fun writeZip(
        archive: Path,
        entries: List<Pair<String, String>>,
    ) {
        archive.parent.createDirectories()
        ZipOutputStream(archive.outputStream().buffered()).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** 与原版游戏共用的备份目录 `<游戏目录>/backups/`。 */
    protected fun backupDirectory(
        store: LauncherStore,
        instance: GameInstance,
    ): Path = store.instancePath(instance.id).resolve("backups")

    /** 备份目录里本次操作留下的临时文件（`.part` 或未完成的半成品）。 */
    protected fun leftovers(directory: Path): List<String> =
        if (!directory.exists()) {
            emptyList()
        } else {
            directory.listDirectoryEntries().map { it.name }.filter { it.endsWith(".part") }
        }

    /** 原版命名：`yyyy-MM-dd_HH-mm-ss_<世界名>.zip`。 */
    protected val vanillaBackupName: Regex =
        Regex("^\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_.+\\.zip$")

    /** 在独立线程池里持有一个世界的 `session.lock`，模拟游戏正在打开该世界。 */
    protected suspend fun <T> withSessionLock(
        world: Path,
        block: suspend () -> T,
    ): T {
        world.createDirectories()
        val lockFile = world.resolve("session.lock").apply { writeText("locked") }
        return FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
            val lock = channel.lock()
            try {
                block()
            } finally {
                runCatching { lock.release() }
            }
        }
    }

    /** 先试符号链接，失败再退到目录联接（Windows 普通账户可用）；都不行时返回 false。 */
    protected fun linkDirectory(
        link: Path,
        target: Path,
    ): Boolean {
        if (runCatching { Files.createSymbolicLink(link, target) }.isSuccess) return true
        return runCatching {
            val process =
                ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true)
                    .start()
            process.waitFor(10, TimeUnit.SECONDS) &&
                process.exitValue() == 0
        }.getOrDefault(false)
    }
}
