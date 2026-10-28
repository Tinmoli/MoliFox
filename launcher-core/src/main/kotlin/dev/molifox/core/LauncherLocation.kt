package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*

/**
 * 启动器自身所在目录（单文件 exe 或启动 jar 的上级目录）。
 *
 * `user.dir` 只是进程工作目录：从终端、快捷方式或 run.bat 启动时可能是任意位置，不能用来 推断启动器在哪。这里按可靠性依次取值，并在进程内缓存：
 * 1. `-Dmolifox.launcher.dir` / `MOLIFOX_LAUNCHER_DIR`：安装器、测试或用户显式指定；
 * 2. 原生启动器写入的 `MOLIFOX_DATA_DIR`（标准布局为 `<启动器目录>/MoliFox`）取其上级；
 * 3. 当前代码来源：内置 jar 位于 `<启动器目录>/MoliFox/dependencies/launcher/` 时回到 数据目录的上级；普通 jar 取 jar 所在目录；开发运行（classes
 *    目录）向上寻找构建入口；
 * 4. 以上都不成立才退回 `user.dir`，保持旧行为而不是抛出异常。
 */
object LauncherLocation {
    @Volatile private var cached: Path? = null

    /** 启动器目录的真实绝对路径；同一进程内只解析一次。 */
    fun locate(): Path = cached ?: resolve().also { cached = it }

    private fun resolve(): Path =
        explicit() ?: packagedDataDirectory() ?: codeSourceDirectory() ?: workingDirectory()

    /** 显式指定优先，便于安装器、便携包和测试固定目录。 */
    private fun explicit(): Path? =
        sequenceOf(
                System.getProperty("molifox.launcher.dir"),
                System.getenv("MOLIFOX_LAUNCHER_DIR"),
            )
            .mapNotNull { value ->
                value?.takeIf { it.isNotBlank() }?.let { directory(it) }
            }
            .firstOrNull()

    /**
     * 打包运行时原生启动器把 exe 目录写进 `MOLIFOX_DATA_DIR`（标准布局 `<exe目录>/MoliFox`）。
     * 只有这一标准布局才能反推启动器目录；用户自定义数据目录时不做猜测，避免把无关目录当成游戏根。
     */
    private fun packagedDataDirectory(): Path? {
        val configured =
            listOf(System.getProperty("molifox.data.dir"), System.getenv("MOLIFOX_DATA_DIR"))
                .firstOrNull { !it.isNullOrBlank() }
                ?: return null
        val data = directory(configured) ?: return null
        if (!data.fileName?.toString().equals("MoliFox", ignoreCase = true)) return null
        return data.parent
    }

    private fun codeSourceDirectory(): Path? {
        val location =
            runCatching {
                    LauncherLocation::class.java.protectionDomain?.codeSource?.location?.toURI()
                }
                .getOrNull() ?: return null
        val source = runCatching { Path.of(location) }.getOrNull() ?: return null
        if (source.isDirectory()) return developmentRoot(source)
        if (!source.isRegularFile()) return null
        val directory = source.parent ?: return null
        // 打包布局：<启动器目录>/MoliFox/dependencies/launcher/MoliFox-<版本>.jar。
        val data = directory.parent?.parent
        if (
            directory.fileName?.toString() == "launcher" &&
                directory.parent?.fileName?.toString() == "dependencies" &&
                data != null &&
                data.fileName?.toString().equals("MoliFox", ignoreCase = true)
        )
            return data.parent ?: directory
        return directory
    }

    /** 开发运行：类目录位于 `<项目>/<模块>/build/classes/kotlin/main`，向上找构建入口。 */
    private fun developmentRoot(source: Path): Path? {
        val ancestors = generateSequence(source) { it.parent }.take(8).toList()
        ancestors.firstOrNull { Files.isRegularFile(it.resolve("settings.gradle.kts")) }?.let {
            return it
        }
        return ancestors.firstOrNull { Files.isRegularFile(it.resolve("build.gradle.kts")) }
    }

    private fun directory(value: String): Path? =
        runCatching { Path.of(value).toAbsolutePath().normalize() }
            .getOrNull()
            ?.takeIf { Files.isDirectory(it) }

    private fun workingDirectory(): Path =
        runCatching { Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize() }
            .getOrDefault(Path.of(".").toAbsolutePath().normalize())
}