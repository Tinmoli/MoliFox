// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责准备界面运行时依赖并加载桌面程序。

package dev.molifox.bootstrap

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.molifox.core.LauncherLocation
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
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.roundToInt

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
 * [version] 是“应用版本-依赖列表哈希”：任何一个依赖的坐标或校验值变化都会得到新的缓存目录， 因此旧缓存不会被误用，也不需要额外的失效逻辑。
 */
data class RuntimeManifest(
    val version: String,
    val dependencies: List<RuntimeDependency>,
)

/** 一个 Maven 源。[roots] 按顺序尝试：androidx.* 只发布在 Google Maven，其余在 Central。 */
private data class Mirror(
    val name: String,
    val roots: List<String>,
    val latencyMillis: Long = 0,
)

/** 镜像探测结果，只保留真正可用的源。 */
private const val PROBE_TIMEOUT_MILLIS = 1500L
private const val CONNECT_TIMEOUT_MILLIS = 8000
private const val READ_TIMEOUT_MILLIS = 15000
private const val FILE_TIMEOUT_MILLIS = 60_000L
private const val PARALLEL_DOWNLOADS = 4
private const val RETRY_LIMIT = 2

private val mirrors =
    listOf(
        Mirror(
            "阿里云",
            listOf(
                "https://maven.aliyun.com/repository/central/",
                "https://maven.aliyun.com/repository/google/",
            ),
        ),
        Mirror("腾讯云", listOf("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")),
        Mirror("华为云", listOf("https://repo.huaweicloud.com/repository/maven/")),
        Mirror(
            "Maven Central",
            listOf(
                "https://repo1.maven.org/maven2/",
                "https://dl.google.com/dl/android/maven2/",
            ),
        ),
    )

private object BootstrapMainMarker

private var logFile: Path? = null
private val logLock = Any()

/**
 * 首次启动的准备流程：读取自带清单 → 挑最快镜像 → 并行下载并校验运行时依赖 → 写 ready.marker → 用 URLClassLoader 在同一个 JVM 里启动 Compose
 * 界面。
 *
 * 只有在下载、校验都完成后才会启动界面；失败会停在准备窗口里，提供「重试」「更换镜像」。
 */
fun main() {
    // 数据目录仍是启动器旁的 MoliFox/；未由原生启动器或环境变量指定时，
    // 按启动器自身（jar / 类目录）的位置推断，而不是用可能被 shell 改变的工作目录。
    val data =
        Path
            .of(
                System.getProperty("molifox.data.dir")
                    ?: System.getenv("MOLIFOX_DATA_DIR")
                    ?: LauncherLocation.locate().resolve("MoliFox").toString(),
            ).toAbsolutePath()
            .normalize()
    var preparation: BootstrapWindow? = null
    val preparationJob = Job()
    try {
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

        val manifest = readManifest()
        val cache = data.resolve("runtime").resolve(manifest.version)
        val marker = cache.resolve("ready.marker")
        log(
            "运行时清单 ${manifest.version}，依赖 ${manifest.dependencies.size} 个，" +
                "共 ${manifest.dependencies.sumOf { it.size }} 字节",
        )
        log("缓存目录：$cache")
        if (
            Files.isRegularFile(marker) &&
            manifest.dependencies.all { Files.isRegularFile(cache.resolve(it.filename)) }
        ) {
            // ready.marker 表示“已逐字节校验过”：这里只确认真实存在，不再重复计算哈希。
            log("运行时组件已就绪，跳过下载")
            launchUi(manifest, cache)
            return
        }
        Files.createDirectories(cache)
        val installer = RuntimeInstaller(cache, manifest.dependencies)
        val window = BootstrapWindow(installer)
        preparation = window
        window.open()
        runBlocking(preparationJob) {
            var candidates = selectMirrors(probePath(manifest))
            while (candidates.isEmpty()) {
                log("全部镜像探测失败")
                window.showError(
                    listOf("无法连接任何 Maven 镜像，请检查网络或代理后重试。"),
                    canSwitchMirror = false,
                )
                window.awaitRecovery()
                window.clearError()
                candidates = selectMirrors(probePath(manifest))
            }
            candidates.forEach { log("镜像可用：${it.name}（${it.latencyMillis} ms）") }
            installer.useMirrors(candidates)
            while (true) {
                val failures = installer.install()
                if (failures.isEmpty()) break
                log("下载失败（${failures.size} 个文件）：${failures.joinToString("；")}")
                window.showError(failures, installer.canSwitchMirror)
                when (window.awaitRecovery()) {
                    RecoveryAction.Retry -> Unit
                    RecoveryAction.SwitchMirror ->
                        installer.switchMirror().also {
                            log("切换镜像：${installer.mirror?.name}")
                        }
                }
                window.clearError()
            }
        }
        // 全部文件已校验完成，写入标记；下次启动直接进界面。
        window.markVerifying()
        Files.writeString(
            marker,
            "version=${manifest.version}\nverified=${LocalDateTime.now()}\nfiles=${manifest.dependencies.size}\n",
        )
        window.close()
        log("运行时组件准备完成，启动界面")
        launchUi(manifest, cache)
    } catch (error: Throwable) {
        preparation?.let { SwingUtilities.invokeLater { it.close(completed = false) } }
        val cause =
            (error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
        if (cause is CancellationException) kotlin.system.exitProcess(0)
        val log = data.resolve("logs/startup.log")
        runCatching {
            Files.createDirectories(log.parent)
            Files.writeString(log, cause.stackTraceToString())
        }
        SwingUtilities.invokeAndWait {
            JOptionPane.showMessageDialog(
                null,
                "启动未完成：${cause.message}\n\n详细日志：$log\n请确认网络可用，并把启动器放在可写入的目录后重试。",
                "MoliFox 启动失败",
                JOptionPane.ERROR_MESSAGE,
            )
        }
        kotlin.system.exitProcess(1)
    }
}

/** 读取并校验自带清单；清单损坏时给出可读的错误，而不是空指针。 */
private fun readManifest(): RuntimeManifest {
    val text =
        BootstrapMainMarker::class
            .java
            .getResourceAsStream("/molifox-runtime.json")
            ?.reader(Charsets.UTF_8)
            ?.use { it.readText() } ?: error("启动文件缺少运行时清单 /molifox-runtime.json")
    val json = Gson().fromJson(text, JsonObject::class.java) ?: error("运行时清单不是有效的 JSON")
    val version = json.string("version")
    require(version.matches(Regex("[A-Za-z0-9._-]+"))) { "运行时清单版本号不合法：$version" }
    val items = json.getAsJsonArray("dependencies") ?: error("运行时清单缺少 dependencies")
    val dependencies =
        items.map { element ->
            val item = element.asJsonObject
            RuntimeDependency(
                group = item.string("group"),
                artifact = item.string("artifact"),
                version = item.string("version"),
                filename = item.string("filename"),
                sha256 = item.string("sha256"),
                size = item.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            )
        }
    require(dependencies.isNotEmpty()) { "运行时清单里没有任何依赖" }
    dependencies.forEach { dependency ->
        require(dependency.filename.matches(Regex("[A-Za-z0-9_.+-]+\\.jar"))) {
            "运行时清单文件名不合法：${dependency.filename}"
        }
        require(dependency.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "运行时清单校验值不合法：${dependency.filename}"
        }
        require(dependency.size > 0) { "运行时清单缺少大小：${dependency.filename}" }
    }
    return RuntimeManifest(version, dependencies)
}

private fun JsonObject.string(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        ?: error("运行时清单字段 $key 无效")

/** 探测路径取清单里真实的 kotlin-stdlib 版本，保证探测和下载的是同一批文件。 */
private fun probePath(manifest: RuntimeManifest): String {
    val stdlib =
        manifest.dependencies.firstOrNull {
            it.group == "org.jetbrains.kotlin" && it.artifact == "kotlin-stdlib"
        } ?: error("运行时清单缺少 kotlin-stdlib，无法探测镜像")
    return "org/jetbrains/kotlin/kotlin-stdlib/${stdlib.version}/" +
        "kotlin-stdlib-${stdlib.version}.pom"
}

/** 同时探测全部镜像，按耗时升序返回可用源；失败的源不进入列表。 */
private suspend fun selectMirrors(mirrorPath: String): List<Mirror> =
    coroutineScope {
        mirrors
            .map { mirror -> async(Dispatchers.IO) { probeMirror(mirror, mirrorPath) } }
            .awaitAll()
            .filterNotNull()
            .sortedBy { it.latencyMillis }
    }

private suspend fun probeMirror(
    mirror: Mirror,
    mirrorPath: String,
): Mirror? =
    withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
        for (root in mirror.roots) {
            val started = System.nanoTime()
            try {
                val connection =
                    open(root + mirrorPath).apply {
                        requestMethod = "GET"
                        connectTimeout = PROBE_TIMEOUT_MILLIS.toInt()
                        readTimeout = PROBE_TIMEOUT_MILLIS.toInt()
                    }
                try {
                    val status = connection.responseCode
                    if (status == HttpURLConnection.HTTP_OK) {
                        // 读一点响应体就断开：既确认能取到内容，也不浪费流量。
                        connection.inputStream.use { it.read(ByteArray(64)) }
                        val elapsed = (System.nanoTime() - started) / 1_000_000
                        log("镜像探测成功：${mirror.name} $root $elapsed ms")
                        return@withTimeoutOrNull mirror.copy(
                            roots = listOf(root) + mirror.roots.filter { it != root },
                            latencyMillis = elapsed,
                        )
                    }
                    log("镜像探测失败：${mirror.name} $root HTTP $status")
                } finally {
                    connection.disconnect()
                }
            } catch (error: IOException) {
                log("镜像探测失败：${mirror.name} $root ${error.message}")
            }
        }
        null
    }

private fun open(url: String): HttpURLConnection =
    (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        useCaches = false
        setRequestProperty("User-Agent", "MoliFox-Bootstrap/1.0")
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

    private fun failureMessages(): List<String> =
        synchronized(failures) { failures.values.toList() }

    private suspend fun installOne(dependency: RuntimeDependency): String? {
        val target = cacheDirectory.resolve(dependency.filename)
        active[dependency.filename] = 0L
        try {
            if (isCached(target, dependency)) {
                verified[dependency.filename] = dependency.size
                log("已存在且校验通过：${dependency.filename}")
                return null
            }
            if (mirrorOrder.isEmpty()) return "没有可用的镜像"
            var lastMessage = "下载失败"
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
                        throw IOException("下载超时（${FILE_TIMEOUT_MILLIS / 1000} 秒）")
                    }
                    moveIntoPlace(part, target)
                    verified[dependency.filename] = dependency.size
                    log("下载完成：${dependency.filename} via ${current.name}")
                    return null
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    lastMessage = error.message ?: error.javaClass.simpleName
                    log("下载失败：${dependency.filename} via ${current.name}：$lastMessage")
                } finally {
                    runCatching { Files.deleteIfExists(part) }
                    active[dependency.filename] = 0L
                }
            }
            return "$lastMessage（已重试 $RETRY_LIMIT 次）"
        } finally {
            active.remove(dependency.filename)
        }
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
                log("下载源失败：${mirror.name} $root ${error.message}")
            }
        }
        throw lastFailure ?: IOException("没有可用的下载地址")
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
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
            val declared = connection.contentLengthLong
            if (declared > 0 && declared != dependency.size) {
                throw IOException("服务器声明大小 $declared 字节，与清单不符")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            Files.newOutputStream(part).use { output ->
                connection.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        written += count
                        active[dependency.filename] = written
                    }
                }
            }
            if (written != dependency.size) {
                throw IOException("下载不完整：$written/${dependency.size} 字节")
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(dependency.sha256, ignoreCase = true)) {
                throw IOException("SHA-256 校验失败")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun moveIntoPlace(
        part: Path,
        target: Path,
    ) {
        try {
            Files.move(
                part,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun isCached(
        target: Path,
        dependency: RuntimeDependency,
    ): Boolean {
        if (!Files.isRegularFile(target) || Files.size(target) != dependency.size) return false
        return sha256Of(target).equals(dependency.sha256, ignoreCase = true)
    }
}

private fun sha256Of(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private enum class RecoveryAction {
    Retry,
    SwitchMirror,
}

// ---------- 准备窗口的视觉令牌：与桌面端主题（樱花粉、浅色底、1dp 细边）对应 ----------

private object BootstrapPalette {
    val background = Color(0xFBF7F9)
    val surface = Color(0xFFFFFF)
    val border = Color(0xEADFE5)
    val accent = Color(0xB94E7B)
    val accentHover = Color(0xC75C89)
    val accentPressed = Color(0xA64470)
    val track = Color(0xF2E6EC)
    val heading = Color(0xB94E7B)
    val onSurface = Color(0x3A333A)
    val muted = Color(0x6B646B)
    val faint = Color(0x948A92)
    val error = Color(0xB3384A)
    val errorFill = Color(0xFDF3F0)
    val errorBorder = Color(0xE8C4C4)
}

/** 与桌面端一致的中文字体：优先系统 UI 字体，找不到时退回逻辑字体（Swing 会自动补中文字形）。 */
private val bootstrapFontFamily: String by lazy {
    val preferred =
        listOf(
            "Microsoft YaHei UI",
            "Microsoft YaHei",
            "PingFang SC",
            "Noto Sans CJK SC",
            "Source Han Sans SC",
            "WenQuanYi Micro Hei",
        )
    val available =
        runCatching {
            java.awt.GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .toSet()
        }.getOrDefault(emptySet())
    preferred.firstOrNull { it in available } ?: Font.SANS_SERIF
}

private fun uiFont(
    size: Int,
    style: Int = Font.PLAIN,
) = Font(bootstrapFontFamily, style, size)

/** 圆角实色面板：白卡片、错误卡片与按钮共用同一套绘制方式（无新依赖，只用 Java2D）。 */
private open class RoundedSurface(
    private val fill: Color,
    private val stroke: Color? = null,
    private val radius: Int = 12,
) : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
            if (stroke != null) {
                g2.color = stroke
                g2.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
            }
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

/** 8dp 圆角进度条：浅色底轨 + 主题色填充；还没有镜像/总量时画一段循环扫过的指示条。 */
private class RoundedProgressBar : JComponent() {
    var progress: Float = 0f
    var indeterminate: Boolean = false
    private var phase = 0f
    private val barHeight = 8

    init {
        isOpaque = false
        preferredSize = Dimension(320, barHeight)
        minimumSize = Dimension(80, barHeight)
    }

    /** 由 250ms 的刷新定时器推动扫动动画；确定进度时不做多余重绘。 */
    fun tick() {
        if (!indeterminate) return
        phase = (phase + 0.014f) % 1f
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val height = barHeight.coerceAtMost(this.height).coerceAtLeast(1)
            val top = (this.height - height) / 2
            g2.color = BootstrapPalette.track
            g2.fillRoundRect(0, top, width, height, height, height)
            val fillWidth =
                if (indeterminate) {
                    (width * 0.34f)
                } else {
                    (width * progress.coerceIn(0f, 1f)).coerceAtLeast(
                        if (progress >
                            0f
                        ) {
                            height.toFloat()
                        } else {
                            0f
                        },
                    )
                }
            if (fillWidth >= 1f) {
                g2.clipRect(0, 0, width, this.height)
                val left =
                    if (indeterminate) {
                        -fillWidth + (width + fillWidth) * phase
                    } else {
                        0f
                    }
                g2.color = BootstrapPalette.accent
                g2.fillRoundRect(
                    left.roundToInt(),
                    top,
                    fillWidth.roundToInt(),
                    height,
                    height,
                    height,
                )
            }
        } finally {
            g2.dispose()
        }
    }
}

/** 与主界面主/次按钮同高（34dp）的圆角按钮：主按钮实色主题色，次按钮白底细边。 */
private class RoundedButton(
    text: String,
    private val primary: Boolean,
) : JButton(text) {
    init {
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        isRolloverEnabled = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = uiFont(12, Font.BOLD)
        border = BorderFactory.createEmptyBorder(7, 16, 7, 16)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val enabled = isEnabled
            val rollover = model.isRollover && enabled
            val pressed = model.isArmed && model.isPressed && enabled
            val fill =
                when {
                    !primary -> BootstrapPalette.surface
                    pressed -> BootstrapPalette.accentPressed
                    rollover -> BootstrapPalette.accentHover
                    else -> BootstrapPalette.accent
                }
            g2.color = fill
            g2.fillRoundRect(0, 0, width - 1, height - 1, 8, 8)
            g2.color =
                when {
                    !primary -> BootstrapPalette.border
                    !enabled -> BootstrapPalette.track
                    else -> fill
                }
            g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)
        } finally {
            g2.dispose()
        }
        foreground =
            when {
                !isEnabled -> BootstrapPalette.faint
                primary -> Color.WHITE
                else -> BootstrapPalette.onSurface
            }
        super.paintComponent(g)
    }
}

/** 内置狐狸图标：标题栏、任务栏与左上角品牌图共用同一张 icons/app/icon.png；缺失时窗口仍可打开。 */
private val launcherIconBytes: ByteArray? by lazy {
    runCatching {
        BootstrapMainMarker::class
            .java
            .getResourceAsStream("/icons/app/icon.png")
            ?.use { it.readBytes() }
    }.getOrNull()
}

private fun launcherIcon(size: Int): BufferedImage? {
    val bytes = launcherIconBytes ?: return null
    return runCatching {
        val source =
            ImageIO.read(java.io.ByteArrayInputStream(bytes)) ?: return@runCatching null
        val scaled = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC,
            )
            g.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON,
            )
            g.drawImage(source, 0, 0, size, size, null)
        } finally {
            g.dispose()
        }
        scaled
    }.getOrNull()
}

/**
 * 准备窗口：品牌标题 + 一行副标题 + 紧凑统计卡片（状态、圆角进度条、镜像/文件/已下载/速度）， 以及失败时的每条错误与两个按钮。
 *
 * 只用 Swing，不加载 Compose（Compose 依赖正是在这一步下载的）。窗口固定大小、不可缩放； 文案与配色与桌面端一致，状态行随准备阶段切换动词（准备 / 下载 / 校验 / 完成）。
 */
private class BootstrapWindow(
    private val installer: RuntimeInstaller,
) {
    private enum class Phase { Preparing, Downloading, Verifying, Done }

    private val frame = JFrame("MoliFox · 准备启动")

    @Volatile private var phase = Phase.Preparing

    private val progressBar = RoundedProgressBar()
    private val statusLabel =
        JLabel("正在准备运行时组件").apply {
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
    private val mirrorValue = statValue("正在选择最快的镜像…")
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
    private val retryButton = RoundedButton("重试", primary = true)
    private val switchButton = RoundedButton("更换镜像", primary = false)
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
                JLabel("以下文件没有下载成功，其余文件已就绪").apply {
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
                    add(statCell("镜像", mirrorValue))
                    add(statCell("文件", filesValue))
                    add(statCell("已下载", bytesValue))
                    add(statCell("速度", speedValue))
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
                        JLabel("正在为启动器准备运行环境").apply {
                            font = uiFont(12)
                            foreground = BootstrapPalette.muted
                        },
                    )
                },
                BorderLayout.CENTER,
            )
        }
    private val footer =
        JLabel("首次启动需要联网下载运行时组件，网络较慢时可能需要几分钟；完成后不再重复校验。")
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
            JPanel(BorderLayout(0, 16)).apply {
                isOpaque = true
                background = BootstrapPalette.background
                border = BorderFactory.createEmptyBorder(22, 26, 20, 26)
                add(header, BorderLayout.NORTH)
                add(bodyCards, BorderLayout.CENTER)
                add(footer, BorderLayout.SOUTH)
            }
        // 窗口图标与左上角品牌图同源：就是启动器自己的 icon.png，不再显示 Java 默认图标。
        frame.iconImages =
            listOf(16, 24, 32, 48, 64, 128, 256)
                .mapNotNull { launcherIcon(it) }
                .map { it as Image }
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
        runCatching { Thread.sleep(180) }
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
            if (!SwingUtilities.isEventDispatchThread()) runCatching { Thread.sleep(420) }
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
                Phase.Preparing -> "正在准备运行时组件"
                Phase.Downloading -> "正在下载运行时组件"
                Phase.Verifying -> "正在校验运行时组件"
                Phase.Done -> "运行时组件已就绪"
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
        percentLabel.text = if (progressBar.indeterminate) "—" else "${(fraction * 100).toInt()}%"
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
        if (seconds >= 0.5) {
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

/** 统计卡片里的一格：11sp 灰色标签 + 13sp 深色数值。 */
private fun statValue(text: String): JLabel =
    JLabel(text).apply {
        font = uiFont(13)
        foreground = BootstrapPalette.onSurface
    }

private fun statCell(
    title: String,
    value: JLabel,
): JPanel =
    JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        add(
            JLabel(title).apply {
                font = uiFont(11)
                foreground = BootstrapPalette.faint
            },
        )
        add(Box.createVerticalStrut(3))
        add(value)
    }

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
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

private fun log(message: String) {
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
