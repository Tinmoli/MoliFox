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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.GridLayout
import java.awt.Image
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

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

private const val CONNECT_TIMEOUT_MILLIS = 8000
private const val READ_TIMEOUT_MILLIS = 15000
private const val FILE_TIMEOUT_MILLIS = 60_000L
private const val PARALLEL_DOWNLOADS = 4
private const val RETRY_LIMIT = 2
private const val MILLIS_PER_SECOND = 1000L

// ---------- 准备窗口的尺寸/节奏令牌 ----------

private const val CONTENT_ROW_GAP = 16
private const val WINDOW_PADDING_TOP = 22
private const val WINDOW_PADDING_HORIZONTAL = 26
private const val WINDOW_PADDING_BOTTOM = 20
private const val VERIFY_PHASE_HOLD_MILLIS = 180L
private const val DONE_PHASE_HOLD_MILLIS = 420L
private const val PERCENT_SCALE = 100
private const val SPEED_SAMPLE_INTERVAL_SECONDS = 0.5
private const val WINDOW_ICON_SIZE_TASKBAR = 16
private const val WINDOW_ICON_SIZE_TITLEBAR = 24
private const val WINDOW_ICON_SIZE_DIALOG = 32
private const val WINDOW_ICON_SIZE_LARGE = 48
private const val WINDOW_ICON_SIZE_EXTRA_LARGE = 64
private const val WINDOW_ICON_SIZE_HUGE = 128
private const val WINDOW_ICON_SIZE_MAX = 256

internal object BootstrapMainMarker

private var logFile: Path? = null
private val logLock = Any()

/** 启动入口：准备运行时组件并拉起桌面界面；失败时关闭准备窗口并给出可读的提示。 */
fun main() {
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

/** 下载、校验与进度状态。所有可变状态都用并发容器保存，Swing 定时器只读取快照。 */
private class RuntimeInstaller(
    private val cacheDirectory: Path,
    private val dependencies: List<RuntimeDependency>,
) {
    val totalFiles = dependencies.size
    val totalBytes = dependencies.sumOf { it.size }
    private val verified = ConcurrentHashMap<String, Long>()
    private val active = ConcurrentHashMap<String, Long>()
    private val failures = Collections.synchronizedMap(LinkedHashMap<String, String>())

    @Volatile private var mirrorOrder: List<Mirror> = emptyList()

    val mirror: Mirror?
        get() = mirrorOrder.firstOrNull()

    val canSwitchMirror: Boolean
        get() = mirrorOrder.size > 1

    val filesDone: Int
        get() = verified.size

    val bytesDone: Long
        get() = verified.values.sum() + active.values.sum()

    fun useMirrors(candidates: List<Mirror>) {
        mirrorOrder = candidates
    }

    /** 换到次快的镜像：把当前源移到队尾，重试时自然改用下一个。 */
    fun switchMirror() {
        if (mirrorOrder.size > 1) mirrorOrder = mirrorOrder.drop(1) + mirrorOrder.first()
    }

    /** 下载所有缺失文件；返回仍然失败的文件说明，空列表表示全部就绪。 */
    suspend fun install(): List<String> {
        failures.clear()
        val pending = dependencies.filterNot { verified.containsKey(it.filename) }
        val permits = Semaphore(PARALLEL_DOWNLOADS)
        coroutineScope {
            pending
                .map { dependency ->
                    async(Dispatchers.IO) {
                        permits.withPermit {
                            installOne(dependency)?.let { message ->
                                failures[dependency.filename] = "${dependency.filename}：$message"
                            }
                        }
                    }
                }.awaitAll()
        }
        return failureMessages()
    }

    private fun failureMessages(): List<String> = synchronized(failures) { failures.values.toList() }

    private suspend fun installOne(dependency: RuntimeDependency): String? {
        val target = cacheDirectory.resolve(dependency.filename)
        active[dependency.filename] = 0L
        try {
            return when {
                isRuntimeDependencyCached(target, dependency) -> {
                    verified[dependency.filename] = dependency.size
                    log(I18n.t("bootstrapB.download.reused", dependency.filename))
                    null
                }

                mirrorOrder.isEmpty() -> I18n.t("bootstrap.bootstrapMain.1")

                else -> downloadWithRetries(dependency, target)
            }
        } finally {
            active.remove(dependency.filename)
        }
    }

    /** 按镜像顺序重试下载；成功返回 null，全部失败返回最后一条错误（含重试次数）。 */
    private suspend fun downloadWithRetries(
        dependency: RuntimeDependency,
        target: Path,
    ): String? {
        var lastMessage = I18n.t("bootstrap.bootstrapMain.2")
        for (attempt in 0..RETRY_LIMIT) {
            val current = mirrorOrder[attempt % mirrorOrder.size]
            val part = Files.createTempFile(cacheDirectory, ".molifox-", ".part")
            try {
                val completed =
                    withTimeoutOrNull(FILE_TIMEOUT_MILLIS) {
                        transfer(current, dependency, part)
                        true
                    }
                if (completed == null) {
                    throw IOException(I18n.t("bootstrap.bootstrapMain.3", FILE_TIMEOUT_MILLIS / MILLIS_PER_SECOND))
                }
                moveRuntimeIntoPlace(part, target)
                verified[dependency.filename] = dependency.size
                log(I18n.t("bootstrapB.download.completed", dependency.filename, current.name))
                return null
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastMessage = error.message ?: error.javaClass.simpleName
                log(I18n.t("bootstrapB.download.fileFailed", dependency.filename, current.name, lastMessage))
            } finally {
                runCatching { Files.deleteIfExists(part) }
                active[dependency.filename] = 0L
            }
        }
        return I18n.t("bootstrap.bootstrapMain.4", lastMessage, RETRY_LIMIT)
    }

    /** 逐个尝试该镜像的根路径；HTTP 失败或校验失败都交给上层换镜像重试。 */
    private suspend fun transfer(
        mirror: Mirror,
        dependency: RuntimeDependency,
        part: Path,
    ) {
        val relative =
            dependency.group.replace('.', '/') +
                "/" +
                dependency.artifact +
                "/" +
                dependency.version +
                "/" +
                dependency.filename
        var lastFailure: IOException? = null
        for (root in mirror.roots) {
            try {
                downloadFrom(root + relative, dependency, part)
                return
            } catch (error: IOException) {
                lastFailure = error
                log(I18n.t("bootstrapB.download.sourceFailed", mirror.name, root, error.message))
            }
        }
        throw lastFailure ?: IOException(I18n.t("bootstrap.bootstrapMain.5"))
    }

    private suspend fun downloadFrom(
        url: String,
        dependency: RuntimeDependency,
        part: Path,
    ) {
        val connection =
            open(url).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("Accept", "application/octet-stream")
            }
        try {
            validateResponse(connection, dependency)
            writeVerifiedPart(connection, dependency, part)
        } finally {
            connection.disconnect()
        }
    }

    /** 落盘并校验：大小与 SHA-256 都对上才算成功，异常类型与提示保持与原实现一致。 */
    private suspend fun writeVerifiedPart(
        connection: HttpURLConnection,
        dependency: RuntimeDependency,
        part: Path,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val written =
            Files.newOutputStream(part).use { output ->
                connection.inputStream.use { input ->
                    copyChunks(input, output, dependency, digest)
                }
            }
        if (written != dependency.size) {
            throw IOException(I18n.t("bootstrap.bootstrapMain.6", written, dependency.size))
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(dependency.sha256, ignoreCase = true)) {
            throw IOException(I18n.t("bootstrap.bootstrapMain.7"))
        }
    }

    /** 逐块写入 .part 并同步更新摘要与进度，可被取消；返回实际写入的字节数。 */
    private suspend fun copyChunks(
        input: InputStream,
        output: OutputStream,
        dependency: RuntimeDependency,
        digest: MessageDigest,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
            written += count
            active[dependency.filename] = written
        }
        return written
    }
}

private enum class RecoveryAction {
    Retry,
    SwitchMirror,
}

/**
 * 准备窗口：品牌标题 + 一行副标题 + 紧凑统计卡片（状态、圆角进度条、镜像/文件/已下载/速度）， 以及失败时的每条错误与两个按钮。
 *
 * 只用 Swing，不加载 Compose（Compose 依赖正是在这一步下载的）。窗口固定大小、不可缩放；
 * 文案与配色与桌面端一致，状态行随准备阶段切换动词（准备 / 下载 / 校验 / 完成）。
 */
private class BootstrapWindow(
    private val installer: RuntimeInstaller,
) {
    private enum class Phase { Preparing, Downloading, Verifying, Done }

    private val frame = JFrame(I18n.t("bootstrap.bootstrapMain.8"))

    @Volatile private var phase = Phase.Preparing

    private val progressBar = RoundedProgressBar()
    private val statusLabel =
        JLabel(I18n.t("bootstrap.bootstrapMain.9")).apply {
            font = uiFont(14, Font.BOLD)
            foreground = BootstrapPalette.onSurface
        }
    private val percentLabel =
        JLabel("—").apply {
            font = uiFont(13, Font.BOLD)
            foreground = BootstrapPalette.accent
            horizontalAlignment = SwingConstants.RIGHT
            preferredSize = Dimension(48, 18)
        }
    private val mirrorValue = statValue(I18n.t("bootstrap.bootstrapMain.10"))
    private val filesValue = statValue("0 / ${installer.totalFiles}")
    private val bytesValue = statValue("0 B / ${formatBytes(installer.totalBytes)}")
    private val speedValue = statValue("0 B/s")

    private val errorArea =
        JTextArea(3, 40).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = BootstrapPalette.error
            border = BorderFactory.createEmptyBorder(2, 4, 2, 2)
            font = uiFont(12)
        }
    private val errorScroll =
        JScrollPane(errorArea).apply {
            isOpaque = false
            viewport.isOpaque = false
            border = BorderFactory.createEmptyBorder()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    private val retryButton = RoundedButton(I18n.t("bootstrap.bootstrapMain.11"), primary = true)
    private val switchButton = RoundedButton(I18n.t("bootstrap.bootstrapMain.12"), primary = false)
    private val actionsPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
            isOpaque = false
            add(retryButton)
            add(switchButton)
        }
    private val errorCard =
        RoundedSurface(BootstrapPalette.errorFill, BootstrapPalette.errorBorder, 12).apply {
            layout = BorderLayout(0, 10)
            border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
            add(
                JLabel(I18n.t("bootstrap.bootstrapMain.13")).apply {
                    font = uiFont(12, Font.BOLD)
                    foreground = BootstrapPalette.error
                },
                BorderLayout.NORTH,
            )
            add(
                RoundedSurface(BootstrapPalette.surface, BootstrapPalette.errorBorder, 8).apply {
                    layout = BorderLayout()
                    border = BorderFactory.createEmptyBorder(3, 4, 3, 4)
                    add(errorScroll, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            add(actionsPanel, BorderLayout.SOUTH)
        }
    private val progressCard =
        RoundedSurface(BootstrapPalette.surface, BootstrapPalette.border, 12).apply {
            border = BorderFactory.createEmptyBorder(14, 18, 16, 18)
            layout = java.awt.GridBagLayout()
            // 错误卡片与进度卡片共用同一块区域：固定一块高度，切换时窗口不跳动。
            preferredSize = Dimension(520, 200)
            val statusRow =
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(statusLabel, BorderLayout.WEST)
                }
            val barRow =
                JPanel(BorderLayout(12, 0)).apply {
                    isOpaque = false
                    add(progressBar, BorderLayout.CENTER)
                    add(percentLabel, BorderLayout.EAST)
                }
            val separator =
                object : JComponent() {
                    init {
                        isOpaque = false
                        preferredSize = Dimension(1, 1)
                        minimumSize = Dimension(1, 1)
                    }

                    override fun paintComponent(g: Graphics) {
                        g.color = BootstrapPalette.border
                        g.fillRect(0, 0, width, 1)
                    }
                }
            val stats =
                JPanel(GridLayout(2, 2, 18, 10)).apply {
                    isOpaque = false
                    add(statCell(I18n.t("bootstrap.bootstrapMain.14"), mirrorValue))
                    add(statCell(I18n.t("bootstrap.bootstrapMain.15"), filesValue))
                    add(statCell(I18n.t("bootstrap.bootstrapMain.16"), bytesValue))
                    add(statCell(I18n.t("bootstrap.bootstrapMain.17"), speedValue))
                }
            val constraints =
                java.awt.GridBagConstraints().apply {
                    gridx = 0
                    gridy = 0
                    weightx = 1.0
                    fill = java.awt.GridBagConstraints.HORIZONTAL
                    anchor = java.awt.GridBagConstraints.NORTHWEST
                }
            add(statusRow, constraints)
            constraints.gridy = 1
            constraints.insets = java.awt.Insets(10, 0, 0, 0)
            add(barRow, constraints)
            constraints.gridy = 2
            constraints.insets = java.awt.Insets(16, 0, 0, 0)
            add(separator, constraints)
            constraints.gridy = 3
            constraints.insets = java.awt.Insets(12, 0, 0, 0)
            add(stats, constraints)
            constraints.gridy = 4
            constraints.weighty = 1.0
            constraints.fill = java.awt.GridBagConstraints.VERTICAL
            add(JPanel().apply { isOpaque = false }, constraints)
        }
    private val bodyCards =
        JPanel(CardLayout()).apply {
            isOpaque = false
            add(progressCard, CARD_PROGRESS)
            add(errorCard, CARD_ERROR)
        }
    private val header =
        JPanel(BorderLayout(14, 0)).apply {
            isOpaque = false
            val mark = launcherIcon(44)
            add(
                JLabel().apply {
                    icon = mark?.let { ImageIcon(it) }
                    preferredSize = Dimension(44, 44)
                    minimumSize = preferredSize
                    horizontalAlignment = SwingConstants.LEFT
                    verticalAlignment = SwingConstants.CENTER
                },
                BorderLayout.WEST,
            )
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(
                        JLabel("MoliFox").apply {
                            font = uiFont(26, Font.BOLD)
                            foreground = BootstrapPalette.heading
                        },
                    )
                    add(Box.createVerticalStrut(3))
                    add(
                        JLabel(I18n.t("bootstrap.bootstrapMain.18")).apply {
                            font = uiFont(12)
                            foreground = BootstrapPalette.muted
                        },
                    )
                },
                BorderLayout.CENTER,
            )
        }
    private val footer =
        JLabel(I18n.t("bootstrap.bootstrapMain.19"))
            .apply {
                font = uiFont(11)
                foreground = BootstrapPalette.muted
            }

    private val actions = LinkedBlockingQueue<RecoveryAction>()
    private var lastBytes = 0L
    private var lastSampleAt = System.nanoTime()
    private val timer = Timer(250) { refresh() }

    init {
        retryButton.addActionListener { actions.offer(RecoveryAction.Retry) }
        switchButton.addActionListener { actions.offer(RecoveryAction.SwitchMirror) }
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.isResizable = false
        frame.contentPane =
            JPanel(BorderLayout(0, CONTENT_ROW_GAP)).apply {
                isOpaque = true
                background = BootstrapPalette.background
                border =
                    BorderFactory.createEmptyBorder(
                        WINDOW_PADDING_TOP,
                        WINDOW_PADDING_HORIZONTAL,
                        WINDOW_PADDING_BOTTOM,
                        WINDOW_PADDING_HORIZONTAL,
                    )
                add(header, BorderLayout.NORTH)
                add(bodyCards, BorderLayout.CENTER)
                add(footer, BorderLayout.SOUTH)
            }
        // 窗口图标与左上角品牌图同源：就是启动器自己的 icon.png，不再显示 Java 默认图标。
        val iconSizes =
            listOf(
                WINDOW_ICON_SIZE_TASKBAR,
                WINDOW_ICON_SIZE_TITLEBAR,
                WINDOW_ICON_SIZE_DIALOG,
                WINDOW_ICON_SIZE_LARGE,
                WINDOW_ICON_SIZE_EXTRA_LARGE,
                WINDOW_ICON_SIZE_HUGE,
                WINDOW_ICON_SIZE_MAX,
            )
        frame.iconImages = iconSizes.mapNotNull { launcherIcon(it) }.map { it as Image }
        frame.pack()
        frame.setLocationRelativeTo(null)
    }

    fun open() {
        SwingUtilities.invokeLater {
            frame.isVisible = true
            timer.start()
        }
    }

    /** 全部文件已下载校验完成：状态行切到「校验」，随后由 [close] 显示「完成」。 */
    fun markVerifying() {
        phase = Phase.Verifying
        SwingUtilities.invokeLater {
            renderPhase()
            percentLabel.text = "100%"
            progressBar.indeterminate = false
            progressBar.progress = 1f
        }
        // 让「校验」这一步在窗口里可见（只在首次下载完成时出现一次）。
        runCatching { Thread.sleep(VERIFY_PHASE_HOLD_MILLIS) }
    }

    /** 关闭窗口；[completed] 为 true 时先短暂显示「完成」，再从准备窗口进入主界面。 */
    fun close(completed: Boolean = true) {
        timer.stop()
        if (completed) {
            val done =
                Runnable {
                    phase = Phase.Done
                    renderPhase()
                    progressBar.indeterminate = false
                    progressBar.progress = 1f
                    percentLabel.text = "100%"
                    speedValue.text = "—"
                }
            if (SwingUtilities.isEventDispatchThread()) {
                done.run()
            } else {
                runCatching { SwingUtilities.invokeAndWait(done) }
            }
            if (!SwingUtilities.isEventDispatchThread()) {
                runCatching { Thread.sleep(DONE_PHASE_HOLD_MILLIS) }
            }
        }
        if (SwingUtilities.isEventDispatchThread()) {
            frame.dispose()
        } else {
            SwingUtilities.invokeLater { frame.dispose() }
        }
    }

    fun showError(
        messages: List<String>,
        canSwitchMirror: Boolean,
    ) {
        SwingUtilities.invokeLater {
            errorArea.text = messages.joinToString("\n")
            errorArea.caretPosition = 0
            switchButton.isEnabled = canSwitchMirror
            (bodyCards.layout as CardLayout).show(bodyCards, CARD_ERROR)
            frame.validate()
        }
    }

    fun clearError() {
        SwingUtilities.invokeLater {
            (bodyCards.layout as CardLayout).show(bodyCards, CARD_PROGRESS)
            frame.validate()
        }
    }

    /** 等待「重试」或「更换镜像」操作，并响应当前协程的取消。 */
    suspend fun awaitRecovery(): RecoveryAction {
        while (true) {
            currentCoroutineContext().ensureActive()
            val action = withContext(Dispatchers.IO) { actions.poll(200, TimeUnit.MILLISECONDS) }
            if (action != null) return action
        }
    }

    private fun renderPhase() {
        statusLabel.text =
            when (phase) {
                Phase.Preparing -> I18n.t("bootstrap.bootstrapMain.9")
                Phase.Downloading -> I18n.t("bootstrap.bootstrapMain.20")
                Phase.Verifying -> I18n.t("bootstrap.bootstrapMain.21")
                Phase.Done -> I18n.t("bootstrap.bootstrapMain.22")
            }
    }

    private fun refresh() {
        val done = installer.filesDone
        val bytes = installer.bytesDone
        if (phase == Phase.Preparing && installer.mirror != null) {
            phase = Phase.Downloading
            renderPhase()
        }
        val totalBytes = installer.totalBytes
        val fraction =
            if (totalBytes > 0) {
                bytes.toFloat() / totalBytes
            } else {
                done.toFloat() / installer.totalFiles.coerceAtLeast(1)
            }
        progressBar.progress = fraction.coerceIn(0f, 1f)
        progressBar.indeterminate = phase == Phase.Preparing && installer.mirror == null
        progressBar.tick()
        percentLabel.text =
            if (progressBar.indeterminate) "—" else "${(fraction * PERCENT_SCALE).toInt()}%"
        filesValue.text = "$done / ${installer.totalFiles}"
        bytesValue.text = "${formatBytes(bytes)} / ${formatBytes(totalBytes)}"
        installer.mirror?.let { mirror ->
            mirrorValue.text =
                if (mirror.latencyMillis > 0) {
                    "${mirror.name} · ${mirror.latencyMillis} ms"
                } else {
                    mirror.name
                }
        }
        val now = System.nanoTime()
        val seconds = (now - lastSampleAt) / 1_000_000_000.0
        if (seconds >= SPEED_SAMPLE_INTERVAL_SECONDS) {
            val speed = ((bytes - lastBytes) / seconds).toLong().coerceAtLeast(0)
            speedValue.text = "${formatBytes(speed)}/s"
            lastBytes = bytes
            lastSampleAt = now
        }
    }

    private companion object {
        const val CARD_PROGRESS = "progress"
        const val CARD_ERROR = "error"
    }
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

/** 状态码与声明长度先核对，异常类型与提示保持与原实现一致。 */
private fun validateResponse(
    connection: HttpURLConnection,
    dependency: RuntimeDependency,
) {
    val status = connection.responseCode
    if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
    val declared = connection.contentLengthLong
    if (declared > 0 && declared != dependency.size) {
        throw IOException(I18n.t("bootstrap.bootstrapMain.23", declared))
    }
}
