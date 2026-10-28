// 负责准备界面运行时依赖并加载桌面程序。

package dev.molifox.bootstrap

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.molifox.core.LauncherLocation
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
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
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
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
data class RuntimeManifest(val version: String, val dependencies: List<RuntimeDependency>)

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
        Path.of(
                System.getProperty("molifox.data.dir")
                    ?: System.getenv("MOLIFOX_DATA_DIR")
                    ?: LauncherLocation.locate().resolve("MoliFox").toString()
            )
            .toAbsolutePath()
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
            System.getProperty("molifox.temp.dir")
                ?.let { Path.of(it).toAbsolutePath().normalize() }
                ?.also {
                    require(
                        it.parent == temporaryRoot && it.fileName.toString().startsWith("session-")
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
            "运行时清单 ${manifest.version}，依赖 ${manifest.dependencies.size} 个，共 ${manifest.dependencies.sumOf { it.size }} 字节"
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
        Files.writeString(
            marker,
            "version=${manifest.version}\nverified=${LocalDateTime.now()}\nfiles=${manifest.dependencies.size}\n",
        )
        window.close()
        log("运行时组件准备完成，启动界面")
        launchUi(manifest, cache)
    } catch (error: Throwable) {
        preparation?.let { SwingUtilities.invokeLater { it.close() } }
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
    val dependencies = items.map { element ->
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
    return "org/jetbrains/kotlin/kotlin-stdlib/${stdlib.version}/kotlin-stdlib-${stdlib.version}.pom"
}

/** 同时探测全部镜像，按耗时升序返回可用源；失败的源不进入列表。 */
private suspend fun selectMirrors(mirrorPath: String): List<Mirror> = coroutineScope {
    mirrors
        .map { mirror -> async(Dispatchers.IO) { probeMirror(mirror, mirrorPath) } }
        .awaitAll()
        .filterNotNull()
        .sortedBy { it.latencyMillis }
}

private suspend fun probeMirror(mirror: Mirror, mirrorPath: String): Mirror? =
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
                        log("镜像探测成功：${mirror.name} $root ${elapsed} ms")
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
                }
                .awaitAll()
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
                    if (completed == null)
                        throw IOException("下载超时（${FILE_TIMEOUT_MILLIS / 1000} 秒）")
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
    private suspend fun transfer(mirror: Mirror, dependency: RuntimeDependency, part: Path) {
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

    private suspend fun downloadFrom(url: String, dependency: RuntimeDependency, part: Path) {
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

    private fun moveIntoPlace(part: Path, target: Path) {
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

    private fun isCached(target: Path, dependency: RuntimeDependency): Boolean {
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

/** 准备窗口：镜像与延迟、文件数、字节数、实时速度，以及失败时的每条错误与两个按钮。 只用 Swing，不加载 Compose（Compose 依赖正是在这一步下载的）。 */
private class BootstrapWindow(private val installer: RuntimeInstaller) {
    private val frame = JFrame("MoliFox · 准备启动")
    private val progress =
        JProgressBar(0, installer.totalFiles.coerceAtLeast(1)).apply {
            isStringPainted = true
            string = "正在准备运行时组件"
            foreground = Color(0xE67D4A)
        }
    private val mirrorLabel = JLabel("正在选择最快的镜像…")
    private val filesLabel = JLabel("文件 0/${installer.totalFiles}")
    private val bytesLabel = JLabel("已下载 0 B / ${formatBytes(installer.totalBytes)}")
    private val speedLabel = JLabel("速度 0 B/s")
    private val errorArea =
        JTextArea(3, 40).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = Color(0xFDF3F0)
            foreground = Color(0x8C2F2F)
        }
    private val errorPanel =
        JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            isVisible = false
            add(
                JLabel("以下文件没有下载成功（其余文件已就绪）："),
                BorderLayout.NORTH,
            )
            add(
                JScrollPane(errorArea).apply {
                    border = BorderFactory.createLineBorder(Color(0xE8C4C4))
                },
                BorderLayout.CENTER,
            )
        }
    private val retryButton = JButton("重试")
    private val switchButton = JButton("更换镜像")
    private val actionsPanel =
        JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
            isOpaque = false
            isVisible = false
            add(retryButton)
            add(switchButton)
        }
    private val actions = LinkedBlockingQueue<RecoveryAction>()
    private var lastBytes = 0L
    private var lastSampleAt = System.nanoTime()
    private val timer = Timer(250) { refresh() }

    init {
        retryButton.addActionListener { actions.offer(RecoveryAction.Retry) }
        switchButton.addActionListener { actions.offer(RecoveryAction.SwitchMirror) }
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.contentPane.background = Color(0xF4F5F0)
        frame.add(
            JLabel("MoliFox", SwingConstants.LEFT).apply {
                font = Font("Dialog", Font.BOLD, 26)
                foreground = Color(0x285848)
                border = BorderFactory.createEmptyBorder(22, 25, 4, 25)
            },
            BorderLayout.NORTH,
        )
        frame.add(
            JPanel(BorderLayout(0, 10)).apply {
                isOpaque = false
                border = BorderFactory.createEmptyBorder(4, 25, 18, 25)
                add(
                    JPanel(GridLayout(0, 1, 0, 6)).apply {
                        isOpaque = false
                        add(JLabel("首次启动需要联网下载运行时组件；完成后不再重复校验。"))
                        add(mirrorLabel)
                        add(filesLabel)
                        add(bytesLabel)
                        add(speedLabel)
                        add(progress)
                    },
                    BorderLayout.NORTH,
                )
                add(errorPanel, BorderLayout.CENTER)
                add(actionsPanel, BorderLayout.SOUTH)
            },
            BorderLayout.CENTER,
        )
        frame.size = Dimension(580, 330)
        frame.setLocationRelativeTo(null)
    }

    fun open() {
        SwingUtilities.invokeLater {
            frame.isVisible = true
            timer.start()
        }
    }

    fun close() {
        timer.stop()
        SwingUtilities.invokeLater { frame.dispose() }
    }

    fun showError(messages: List<String>, canSwitchMirror: Boolean) {
        SwingUtilities.invokeLater {
            errorArea.text = messages.joinToString("\n")
            errorArea.caretPosition = 0
            errorPanel.isVisible = true
            switchButton.isEnabled = canSwitchMirror
            actionsPanel.isVisible = true
            frame.validate()
        }
    }

    fun clearError() {
        SwingUtilities.invokeLater {
            errorPanel.isVisible = false
            actionsPanel.isVisible = false
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

    private fun refresh() {
        val done = installer.filesDone
        val bytes = installer.bytesDone
        progress.maximum = installer.totalFiles.coerceAtLeast(1)
        progress.value = done
        progress.string = "文件 $done/${installer.totalFiles}"
        filesLabel.text = "文件 $done/${installer.totalFiles}"
        bytesLabel.text = "已下载 ${formatBytes(bytes)} / ${formatBytes(installer.totalBytes)}"
        installer.mirror?.let { mirror ->
            mirrorLabel.text = "镜像：${mirror.name}（${mirror.latencyMillis} ms）"
        }
        val now = System.nanoTime()
        val seconds = (now - lastSampleAt) / 1_000_000_000.0
        if (seconds >= 0.5) {
            val speed = ((bytes - lastBytes) / seconds).toLong().coerceAtLeast(0)
            speedLabel.text = "速度 ${formatBytes(speed)}/s"
            lastBytes = bytes
            lastSampleAt = now
        }
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

/** 用缓存目录里的 jar 加上本程序集启动界面，使用同一个 JVM， 父加载器为平台加载器，主类仍是 dev.molifox.desktop.MainKt。 */
private fun launchUi(manifest: RuntimeManifest, cache: Path) {
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
