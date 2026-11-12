// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责准备界面运行时依赖并加载桌面程序。
package dev.molifox.bootstrap

import com.google.gson.JsonParser
import dev.molifox.core.LauncherLocation
import dev.molifox.core.i18n.AppLanguage
import dev.molifox.core.i18n.I18n
import dev.molifox.core.platform.watchNativeLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import java.lang.management.ManagementFactory
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import javax.swing.SwingUtilities

/** 清单中的一个运行时依赖：Maven 坐标、落盘文件名与校验值。 */
data class RuntimeDependency(
    val group: String,
    val artifact: String,
    val version: String,
    val filename: String,
    val sha256: String,
    val size: Long,
)

/**
 * `MoliFox.jar` 内 `/molifox-runtime.json` 的内容。
 *
 * [version] 只由依赖列表哈希决定；单纯更新启动器不会使已校验的依赖失效。
 */
data class RuntimeManifest(
    val version: String,
    val dependencies: List<RuntimeDependency>,
)

internal object BootstrapMainMarker

private var logFile: Path? = null
private val logLock = Any()

/** 启动入口：准备运行时组件并拉起桌面界面；失败时关闭准备窗口并给出可读的提示。 */
fun main() {
    if (relaunchForMacFirstThread()) return
    // 准备窗口阶段也可能被强杀；此时桌面入口尚未执行，必须在这里就开始监视原生外壳。
    watchNativeLauncher()
    val data = resolveDataDirectory()
    applySavedLanguage(data)
    var preparation: BootstrapWindow? = null
    val failure =
        runCatching {
            prepareRuntime(data) { window -> preparation = window }
        }.exceptionOrNull()
    if (failure != null) reportStartupFailure(data, preparation, failure)
}

/**
 * macOS 直接 `java -jar` 时不会带 `-XstartOnFirstThread`，AWT/Skiko 会报错或不出窗口。
 * 自己补参数重启一次；已有该参数或非 macOS 时返回 false，继续当前进程。
 */
private fun relaunchForMacFirstThread(): Boolean {
    val codeSource =
        BootstrapMainMarker::class.java.protectionDomain.codeSource.location.let {
            runCatching { Path.of(it.toURI()) }.getOrNull()
        } ?: return false
    val javaExecutable =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            },
        )
    val command =
        macRelaunchCommand(
            osName = System.getProperty("os.name").orEmpty(),
            jvmArguments = ManagementFactory.getRuntimeMXBean().inputArguments,
            javaExecutable = javaExecutable,
            codeSource = codeSource,
            classPath = System.getProperty("java.class.path").orEmpty(),
            dataDirectory = resolveDataDirectory(),
        ) ?: return false
    val started =
        runCatching {
            ProcessBuilder(command).inheritIO().start()
            true
        }.getOrElse {
            System.err.println("MoliFox: macOS first-thread relaunch failed: ${it.message}")
            false
        }
    if (started) kotlin.system.exitProcess(0)
    return false
}

/**
 * 准备窗口与启动失败对话框在桌面界面之前显示，也必须跟随用户语言：
 * 创建任何窗口前先读一次配置里的 `settings.language`（缺失或损坏按「跟随系统」处理）。
 */
private fun applySavedLanguage(data: Path) {
    val config = data.resolve("config").resolve("launcher.json")
    if (!Files.isRegularFile(config)) {
        I18n.setLanguage(AppLanguage.resolveTag(null))
        return
    }
    val saved =
        runCatching {
            JsonParser
                .parseString(Files.readString(config))
                .asJsonObject
                .getAsJsonObject("settings")
                ?.get("language")
                ?.asString
        }.getOrNull()
    I18n.setLanguage(AppLanguage.resolveTag(saved))
}

/**
 * 数据目录仍是启动器旁的 MoliFox/；未由原生启动器或环境变量指定时，
 * 按启动器自身（jar / 类目录）的位置推断，而不是用可能被 shell 改变的工作目录。
 */
private fun resolveDataDirectory(): Path =
    Path
        .of(
            System.getProperty("molifox.data.dir")
                ?: System.getenv("MOLIFOX_DATA_DIR")
                ?: LauncherLocation.locate().resolve("MoliFox").toString(),
        ).toAbsolutePath()
        .normalize()

/** 临时目录、编码等进程级属性必须在读取清单、下载依赖之前设置好。 */
private fun configureProcessEnvironment(data: Path) {
    Files.createDirectories(data.resolve("cache/tmp"))
    Files.createDirectories(data.resolve("logs"))
    logFile = data.resolve("logs/runtime.log")
    System.setProperty("molifox.data.dir", data.toString())
    val temporaryRoot = data.resolve("cache/tmp")
    val temporary =
        System
            .getProperty("molifox.temp.dir")
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?.also {
                require(
                    it.parent == temporaryRoot && it.fileName.toString().startsWith("session-"),
                )
            } ?: temporaryRoot
    Files.createDirectories(temporary)
    System.setProperty("java.io.tmpdir", temporary.toString())
    System.setProperty("jna.tmpdir", temporary.toString())
    Files.createDirectories(data.resolve("dependencies/native"))
    System.setProperty("skiko.data.path", data.resolve("dependencies/native").toString())
    System.setProperty("java.net.useSystemProxies", "true")
    // 原生启动器已经把 -Dfile.encoding=UTF-8 与 --enable-native-access=ALL-UNNAMED 传给 JVM。
    // 直接 `java -jar MoliFox.jar` 启动时，编码类属性这里还能补上；JVM 启动参数不能中途添加，
    // 所以 native-access 只能由启动脚本或原生启动器提供（缺失时 JDK 只会有警告，不影响启动）。
    System.setProperty("file.encoding", "UTF-8")
    System.setProperty("stdout.encoding", "UTF-8")
    System.setProperty("stderr.encoding", "UTF-8")
}

/**
 * 首次启动的准备流程：读取自带清单 → 挑最快镜像 → 并行下载并校验运行时依赖 → 写 ready.marker → 用 URLClassLoader 在同一个 JVM 里启动 Compose
 * 界面。
 *
 * 只有在下载、校验都完成后才会启动界面；失败会停在准备窗口里，提供「重试」「更换镜像」。
 * [onWindowCreated] 在窗口建好后立即回调，失败路径据此关闭窗口。
 */
private fun prepareRuntime(
    data: Path,
    onWindowCreated: (BootstrapWindow) -> Unit,
) {
    configureProcessEnvironment(data)
    val manifest = readManifest()
    val cache = runtimeCacheDirectory(data)
    require(!Files.isSymbolicLink(cache)) { I18n.t("bootstrapB.startup.invalidCache", cache) }
    val marker = cache.resolve("ready.marker")
    log(
        I18n.t(
            "bootstrapB.startup.manifestSummary",
            manifest.version,
            manifest.dependencies.size,
            manifest.dependencies.sumOf { it.size },
        ),
    )
    log(I18n.t("bootstrapB.startup.cacheDirectory", cache))
    if (runtimeCacheReady(data, cache, marker, manifest)) {
        launchUi(manifest, cache)
        return
    }
    Files.createDirectories(cache)
    migrateLegacyRuntime(data, cache, manifest.dependencies)
    if (runtimeCacheReady(data, cache, marker, manifest)) {
        launchUi(manifest, cache)
        return
    }
    val installer = RuntimeInstaller(cache, manifest.dependencies)
    val window = BootstrapWindow(installer)
    onWindowCreated(window)
    window.open()
    val preparationJob = Job()
    awaitInstallation(preparationJob, manifest, installer, window)
    // 全部文件已校验完成，写入标记；下次启动直接进界面。
    window.markVerifying()
    writeRuntimeMarker(marker, manifest)
    cleanupRuntimeCache(data, cache, manifest.dependencies)
    window.close()
    log(I18n.t("bootstrapB.startup.preparationDone"))
    launchUi(manifest, cache)
}

/** 等待镜像探测与下载全部完成；失败时把错误放进准备窗口，按用户选择重试或换源。 */
private fun awaitInstallation(
    preparationJob: Job,
    manifest: RuntimeManifest,
    installer: RuntimeInstaller,
    window: BootstrapWindow,
) {
    runBlocking(preparationJob) {
        var candidates = selectMirrors(probePath(manifest))
        while (candidates.isEmpty()) {
            log(I18n.t("bootstrapB.mirror.probeAllFailed"))
            window.showError(
                listOf(I18n.t("bootstrapB.mirror.noneReachable")),
                canSwitchMirror = false,
            )
            window.awaitRecovery()
            window.clearError()
            candidates = selectMirrors(probePath(manifest))
        }
        candidates.forEach { log(I18n.t("bootstrapB.mirror.available", it.name, it.latencyMillis)) }
        installer.useMirrors(candidates)
        while (true) {
            val failures = installer.install()
            if (failures.isEmpty()) break
            log(I18n.t("bootstrapB.download.failedFiles", failures.size, failures.joinToString("；")))
            window.showError(failures, installer.canSwitchMirror)
            when (window.awaitRecovery()) {
                RecoveryAction.Retry -> Unit
                RecoveryAction.SwitchMirror ->
                    installer.switchMirror().also {
                        log(I18n.t("bootstrapB.mirror.switched", installer.mirror?.name))
                    }
            }
            window.clearError()
        }
    }
}

/** 启动失败：关闭准备窗口、写启动日志并用对话框说明原因，最后以非零码退出。 */
private fun reportStartupFailure(
    data: Path,
    preparation: BootstrapWindow?,
    error: Throwable,
) {
    preparation?.let { SwingUtilities.invokeLater { it.close(completed = false) } }
    val cause =
        (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
    if (cause is CancellationException) kotlin.system.exitProcess(0)
    val failureLog = data.resolve("logs/startup.log")
    runCatching {
        Files.createDirectories(failureLog.parent)
        Files.writeString(failureLog, cause.stackTraceToString())
    }
    StartupFailureDialog.show(cause, failureLog)
    kotlin.system.exitProcess(1)
}

/** 用缓存目录里的 jar 加上本程序集启动界面，使用同一个 JVM， 父加载器为平台加载器，主类仍是 dev.molifox.desktop.MainKt。 */
private fun launchUi(
    manifest: RuntimeManifest,
    cache: Path,
) {
    val ownJar = BootstrapMainMarker::class.java.protectionDomain.codeSource.location
    val urls =
        listOf(ownJar) + manifest.dependencies.map { cache.resolve(it.filename).toUri().toURL() }
    val loader = URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
    Thread.currentThread().contextClassLoader = loader
    // 首次启动路径里 BootstrapWindow 已把 AWT 事件线程提前创建，它的 context classloader
    // 停在只有 MoliFox.jar 的系统加载器上；Compose 组合跑在事件线程，不纠正的话依赖 jar
    // 里的 ServiceLoader 声明（mediamp 等）在这个会话里永远扫描不到。
    SwingUtilities.invokeAndWait { Thread.currentThread().contextClassLoader = loader }
    loader.loadClass("dev.molifox.desktop.MainKt").getMethod("main").invoke(null)
}

internal fun log(message: String) {
    val line = "${LocalDateTime.now()} $message"
    println("MoliFox: $line")
    val target = logFile ?: return
    synchronized(logLock) {
        runCatching {
            Files.createDirectories(target.parent)
            Files.writeString(
                target,
                "$line\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
        }
    }
}
