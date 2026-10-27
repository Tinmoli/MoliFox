package dev.molifox.bootstrap

import com.google.gson.Gson
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class RuntimeDependency(
    val filename: String,
    val sha256: String,
    val size: Long,
)

data class RuntimeManifest(val version: String, val dependencies: List<RuntimeDependency>)

/**
 * Extract verified UI components carried inside the launcher; preparing the UI never uses a
 * network.
 */
fun main() {
    val data =
        Path.of(
                System.getProperty("molifox.data.dir")
                    ?: System.getenv("MOLIFOX_DATA_DIR")
                    ?: Path.of(System.getProperty("user.dir"), "MoliFox").toString()
            )
            .toAbsolutePath()
            .normalize()
    var preparation: JFrame? = null
    val preparationJob = Job()
    try {
        Files.createDirectories(data.resolve("cache/tmp"))
        Files.createDirectories(data.resolve("logs"))
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
        val manifest =
            BootstrapMainMarker::class
                .java
                .getResourceAsStream("/molifox-runtime.json")!!
                .reader()
                .use { Gson().fromJson(it, RuntimeManifest::class.java) }
        val dependencyDirectory = data.resolve("dependencies/${manifest.version}")
        Files.createDirectories(dependencyDirectory)
        val progress =
            JProgressBar(0, manifest.dependencies.size).apply {
                isStringPainted = true
                string = "正在检查启动组件"
                foreground = Color(0xE67D4A)
            }
        SwingUtilities.invokeAndWait {
            preparation =
                JFrame("MoliFox · 准备启动").apply {
                    defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
                    addWindowListener(
                        object : WindowAdapter() {
                            override fun windowClosing(event: WindowEvent) {
                                preparationJob.cancel()
                            }
                        }
                    )
                    contentPane.background = Color(0xF4F5F0)
                    val title =
                        JLabel("MoliFox", SwingConstants.LEFT).apply {
                            font = Font("Dialog", Font.BOLD, 26)
                            foreground = Color(0x285848)
                            border = BorderFactory.createEmptyBorder(22, 25, 10, 25)
                        }
                    add(title, BorderLayout.NORTH)
                    add(
                        JPanel(BorderLayout(0, 15)).apply {
                            isOpaque = false
                            border = BorderFactory.createEmptyBorder(5, 25, 25, 25)
                            add(JLabel("正在解压内置组件，无需联网。"), BorderLayout.NORTH)
                            add(progress, BorderLayout.CENTER)
                        },
                        BorderLayout.CENTER,
                    )
                    size = Dimension(540, 180)
                    setLocationRelativeTo(null)
                    isVisible = true
                }
        }
        runBlocking(preparationJob) {
            val semaphore = Semaphore(6)
            val completed = AtomicInteger()
            manifest.dependencies
                .map { dependency ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            require(dependency.filename.matches(Regex("[A-Za-z0-9_.-]+\\.jar")))
                            val target = dependencyDirectory.resolve(dependency.filename)
                            extractDependency(dependency, target)
                            val count = completed.incrementAndGet()
                            SwingUtilities.invokeLater {
                                progress.value = count
                                progress.string = "准备启动组件 $count/${manifest.dependencies.size}"
                            }
                        }
                    }
                }
                .awaitAll()
        }
        SwingUtilities.invokeAndWait { preparation?.dispose() }
        val ownJar = BootstrapMainMarker::class.java.protectionDomain.codeSource.location
        val urls =
            listOf(ownJar) +
                manifest.dependencies.map {
                    dependencyDirectory.resolve(it.filename).toUri().toURL()
                }
        val loader = URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
        Thread.currentThread().contextClassLoader = loader
        loader.loadClass("dev.molifox.desktop.MainKt").getMethod("main").invoke(null)
    } catch (error: Throwable) {
        preparation?.let { SwingUtilities.invokeLater { it.dispose() } }
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
                "启动未完成：${cause.message}\n\n详细日志：$log\n请确认启动文件完整，并放在可写入的目录后重试。",
                "MoliFox 启动失败",
                JOptionPane.ERROR_MESSAGE,
            )
        }
        kotlin.system.exitProcess(1)
    }
}

private object BootstrapMainMarker

private suspend fun extractDependency(dependency: RuntimeDependency, target: Path) {
    fun hash(path: Path): String {
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
    if (
        Files.isRegularFile(target) &&
            Files.size(target) == dependency.size &&
            hash(target) == dependency.sha256
    )
        return
    val temporary = Files.createTempFile(target.parent, ".molifox-", ".part")
    try {
        val input =
            BootstrapMainMarker::class
                .java
                .getResourceAsStream("/molifox-dependencies/${dependency.filename}")
                ?: error("启动文件缺少组件：${dependency.filename}，请重新获取完整启动器")
        input.use { source ->
            Files.newOutputStream(temporary).use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        }
        check(Files.size(temporary) == dependency.size && hash(temporary) == dependency.sha256) {
            "内置组件校验失败：${dependency.filename}"
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        Files.deleteIfExists(temporary)
    }
}
