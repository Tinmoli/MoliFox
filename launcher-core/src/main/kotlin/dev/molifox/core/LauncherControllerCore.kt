// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责桌面入口的状态装配与生命周期。

package dev.molifox.core

import dev.molifox.core.data.instance.FileInstanceWorkspaceRepository
import dev.molifox.core.domain.instance.InstanceWorkspaceQueries
import dev.molifox.core.i18n.I18n
import dev.molifox.core.platform.files.NioFileSystemAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 桌面入口的全部运行时状态：文件锁、协程作用域与各服务都在这里创建一次。
 *
 * 方法体按职责分散在 Launcher*Actions 的继承链里，本类只保留字段、[startup] 与 [shutdown]；
 * 字段声明顺序与原先一致，初始化时机不变。
 */
internal class LauncherControllerCore(
    val platform: PlatformServices,
    dataDirectory: Path,
    updateApiBase: String,
    networkClient: NetworkClient?,
) {
    private val lockChannel =
        FileChannel.open(
            dataDirectory.apply { createStorageDirectory(this) }.resolve("instance.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )

    private val lock: FileLock =
        try {
            lockChannel.tryLock() ?: error(I18n.t("miscA1.controllerCore.alreadyRunning"))
        } catch (failure: IOException) {
            lockChannel.close()
            throw failure
        } catch (failure: IllegalStateException) {
            // error() 与 OverlappingFileLockException 都属于这一类：没拿到锁时同样要关掉通道。
            lockChannel.close()
            throw failure
        }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val store =
        runCatching {
            LauncherStore(platform, dataDirectory)
        }.getOrElse { failure ->
            // 构造失败的原因不可穷举（配置解析、目录权限、平台探测等），但无论哪种都必须先释放
            // 上面已打开的协程作用域与文件锁，再把原异常抛给调用方，避免残留锁。
            scope.cancel()
            lock.release()
            lockChannel.close()
            throw failure
        }

    val state = store.state

    val downloadConcurrency = {
        store.data.settings.downloadThreads
            .coerceIn(1, MAX_DOWNLOAD_THREADS)
    }
    val network =
        networkClient ?: NetworkClient(
            { store.data.settings.effectiveDownloadSource },
            {
                // 代理设置每次请求时读取；配置无效时直连。
                val proxy = store.data.settings
                if (
                    proxy.effectiveProxyMode() != "manual" ||
                    proxy.proxyHost.isBlank() ||
                    proxy.proxyPort !in 1..65535
                ) {
                    null
                } else {
                    runCatching {
                        Proxy(
                            if (proxy.proxyType ==
                                "socks"
                            ) {
                                Proxy.Type.SOCKS
                            } else {
                                Proxy.Type.HTTP
                            },
                            InetSocketAddress(proxy.proxyHost, proxy.proxyPort),
                        )
                    }.getOrNull()
                }
            },
            { store.data.settings.effectiveProxyMode() == "system" },
            // 文件并发统一使用设置上限；来源限流仍由重试和退避处理。
            downloadSegments = { store.data.settings.downloadSegments },
            downloadAttempts = { 5 },
            requestTimeoutSeconds = { store.data.settings.networkTimeoutSeconds },
            maxConcurrent = downloadConcurrency,
            mirrorConcurrency = downloadConcurrency,
            // 源被禁用、限流与并发预算调整都要在启动器日志里留一行。
            logger = { message, error -> store.log(message, error) },
        )

    val jobs = JobQueue(store, scope)

    val updater = GitHubUpdater(store, network, updateApiBase)

    val java = JavaService(store, network)

    val accounts = AccountService(store, network, scope)

    val skins = SkinService(store, network, accounts::credentials)

    val installer = MinecraftInstaller(store, network, java)

    val directories = GameDirectoryService(store)

    val icons = InstanceIcons(store)

    val screenshots = ScreenshotManager(store)

    val content = ContentService(store, network, installer)

    val tools = InstanceTools(store)

    val deletion = InstanceDeletionService(store)

    val saves = SaveManager(store)

    val instanceWorkspace =
        InstanceWorkspaceQueries(
            FileInstanceWorkspaceRepository(
                store::instancePath,
                NioFileSystemAccess(),
            ) { path, failure ->
                if (failure !is NoSuchFileException) {
                    store.log(
                        I18n.t("miscA1.controllerCore.readDirectoryFailed", path, failure.message),
                        error = true,
                    )
                }
            },
        )

    val datapacks = DatapackManager(store)

    internal val renamer = InstanceRename(store)

    internal val migration = IsolationMigration(store)

    val game = GameLauncher(store, installer, java, accounts, scope)

    val busy: Boolean
        get() = jobs.globallyBusy

    /** 方法实现；先于 [startup] 建好，构造期间只保存引用。 */
    internal val actions: LauncherControllerActions = LauncherControllerActions(this)

    /** 构造末尾的一次性初始化：与原先的 init 块逐行对应。 */
    fun startup() {
        // 打开文件夹失败时要写日志并提示用户；DesktopPlatform 本身不持有 store，由这里注入。
        (platform as? DesktopPlatform)?.let { desktop ->
            desktop.onLog = { message -> store.log(message) }
            desktop.onNotice = { message -> store.notify(message) }
        }
        directories.refreshVersionMetadata()
        // 启动时以 versions/ 扫描为准重建实例列表。
        store.refreshInstances()
        store.log(I18n.t("miscA1.controllerCore.welcome", platform.osName, platform.architecture))
        if (store.data.settings.autoCheckUpdates) scope.launch { updater.check(false) }
        actions.refreshJavaPrompt()
    }

    /** 释放顺序与原先的 close() 一致：先停服务，再取消作用域，最后放锁。 */
    @Synchronized
    fun shutdown() {
        if (shutdownStarted) return
        shutdownStarted = true
        accounts.cancelLogin()
        jobs.cancelAll()
        network.close()
        // 最后一批游戏日志可能还在 100ms 批量缓冲里：先补进界面，再停刷新线程。
        store.actions.flushPendingLogs()
        store.core.logFlusher.shutdownNow()
        scope.cancel()
        lock.release()
        lockChannel.close()
    }

    /** 桌面层注册的优雅退出回调；自动更新替换前调用，先停联机与后台任务再关闭窗口。 */
    internal var exitHandler: (() -> Unit)? = null

    @Volatile private var shutdownStarted = false
}
