// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 开始页的区块实现：安装状态、统计行、当前游戏卡、最近游玩与启动浮窗。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.DownloadJob
import dev.molifox.core.EntryKind
import dev.molifox.core.GameInstance
import dev.molifox.core.GameLaunchTarget
import dev.molifox.core.LauncherState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** 当前游戏的安装状态：与启动按钮的分支共用同一套判断，避免两处说法不一致。 */
internal fun homeInstallState(
    instance: GameInstance?,
    filesPresent: Boolean,
): String =
    when {
        instance == null -> tr("pages.home.state.noVersion")
        instance.broken -> tr("pages.home.state.broken", instance.brokenReason)
        !instance.installed -> tr("pages.home.state.notInstalled")
        instance.externalProfile && !filesPresent -> tr("pages.home.state.filesMissing")
        !filesPresent -> tr("pages.home.state.fillsOnLaunch")
        else -> tr("pages.home.state.installed")
    }

/** 右上角的实例与账户统计；[LauncherState] 里没开统计偏好时整行不渲染。 */
@Composable
internal fun HomeStatisticsRow(state: LauncherState) {
    val preferences = state.data.settings.pages.home
    if (!preferences.showStatistics) return
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        Icon(
            Icons.Outlined.Widgets,
            null,
            Modifier.size(14.dp),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            LocalStrings.current.t(
                "pages.home.statistics.summary",
                state.data.instances.size,
                state.data.accounts.size,
            ),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 版本配置与客户端 jar 是否还在：只在选中项变化时于 IO 线程检查一次。 */
@Composable
internal fun rememberHomeFilesPresent(
    actions: UiActions,
    instance: GameInstance?,
): Boolean {
    val present by
        produceState(true, instance?.id, instance?.versionId) {
            val target = instance
            value =
                if (target == null) {
                    true
                } else {
                    actions.controller.instanceFilesPresent(target.id)
                }
        }
    return present
}

/** 选中记录的回写与页面可见时的目录重扫：两条副作用各自只依赖查询键。 */
@Composable
internal fun HomePageSync(
    state: LauncherState,
    actions: UiActions,
    stored: GameInstance?,
    selected: GameInstance?,
) {
    // 页面重新可见时只做目录签名比较：安装/删除/改名/手动改文件夹后回到开始页即可看到最新扫描。
    LaunchedEffect(Unit) {
        withContext(
            Dispatchers.IO,
        ) { runCatching { actions.controller.refreshInstancesIfChanged() } }
    }
    // 选中记录可能为空或指向已删除的实例：先按「当前游戏目录 → 最近游玩」回退，再把回退结果
    // 写回状态。这样自动识别目录后开始页不会停在「未选择版本」，其他页面读到的也是同一个实例。
    LaunchedEffect(stored?.id, selected?.id) {
        if (stored != null) return@LaunchedEffect
        // 只回写与当前游戏目录一致的实例，避免把用户刚选定的空目录又切走；
        // 跨目录的可见实例仍然可以直接启动（点击时才会重新持久化选中）。
        val target =
            selected?.takeIf { it.gameDirectoryId == state.data.activeGameDirectoryId }
                ?: return@LaunchedEffect
        withContext(Dispatchers.IO) { runCatching { actions.controller.selectInstance(target.id) } }
    }
}

/** 启动浮窗的任务跟踪：任务被清除后保留最后一帧，进程退出后收起浮窗。 */
@Composable
internal fun HomeLaunchCardTracking(
    state: LauncherState,
    launchCard: LaunchCardState,
): DownloadJob? {
    // 启动浮窗只在本页存活：remember 状态随页面切换一起释放，启动任务本身留在任务队列继续。
    val cardJob = state.jobs.firstOrNull { it.id == launchCard.jobId } ?: launchCard.lastJob
    LaunchedEffect(cardJob) { if (cardJob != null) launchCard.remember(cardJob) }
    // 游戏进程起来后记录一次；退出（或 gameRun 标记结束）时把浮窗收起。
    LaunchedEffect(state.runningInstanceId, state.gameRun?.finished, launchCard.instanceId) {
        val runningId = state.runningInstanceId
        val run = state.gameRun
        val started = runningId != null && runningId == launchCard.instanceId
        val ended = run != null && run.instanceId == launchCard.instanceId && run.finished
        val stopped = launchCard.sawRunning && (runningId == null || ended)
        when {
            started -> launchCard.markRunning()
            stopped -> launchCard.clear()
        }
    }
    return cardJob
}

/** 当前游戏卡的展示状态。 */
internal data class CurrentGameState(
    val selected: GameInstance?,
    val installState: String,
    val busy: Boolean,
    val launchProgress: Float?,
    val running: Boolean,
    val filesPresent: Boolean,
    val hasAccount: Boolean,
)

/** 当前游戏卡的操作入口。 */
internal data class CurrentGameCallbacks(
    val onLaunch: (GameInstance) -> Unit,
    val onCreate: () -> Unit,
    val onOpenInstances: () -> Unit,
    val onManage: (String) -> Unit,
    val onOpenAccounts: () -> Unit,
)

/** 「当前游戏」卡的内容：信息在上、中间弹性留白、启动与版本入口贴底。 */
@Composable
internal fun ColumnScope.CurrentGameCardBody(
    actions: UiActions,
    game: CurrentGameState,
    callbacks: CurrentGameCallbacks,
) {
    val strings = LocalStrings.current
    val instance = game.selected
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (instance != null) {
            InstanceArtwork(instance, actions, Modifier.size(40.dp))
        } else {
            GrassBlockIcon(size = 40.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // 名称行右侧是隔离标签；简介行整宽留给「版本 · 加载器 · 安装状态」。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    instance?.name ?: strings.t("pages.home.state.noVersion"),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (instance != null) {
                    Spacer(Modifier.width(6.dp))
                    if (instance.broken) {
                        Tag(strings.t("pages.home.state.brokenTag"), MaterialTheme.colorScheme.error)
                    } else {
                        Tag(
                            if (actions.controller.store.isolated(instance)) {
                                strings.t("pages.home.isolation.enabled")
                            } else {
                                strings.t("pages.home.isolation.shared")
                            },
                        )
                    }
                }
            }
            if (instance != null) {
                Text(
                    instance.gameVersion +
                        " · " +
                        instance.loader.title +
                        " · " +
                        game.installState,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    strings.t("pages.home.noInstanceHint"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        instance?.let {
            actions.controller.store
                .gamePath(it)
                .toString()
        }
            ?: actions.controller.store.minecraftRoot
                .toString(),
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    // 中间弹性留白：窗口变矮时先吃掉这里，不挤压按钮、不截断文字。
    Spacer(Modifier.weight(1f))
    // 整行「启动游戏」按钮；再往下是贴底的版本入口。
    CurrentGameLaunchButton(instance, game, actions, callbacks)
    Spacer(Modifier.height(12.dp))
    CurrentGameBottomRow(instance, game.busy, callbacks)
}

/** 启动按钮：与实例页、实例详情页共用同一条启动路径，只有确实没有可用的游戏时才跳安装页。 */
@Composable
private fun CurrentGameLaunchButton(
    instance: GameInstance?,
    game: CurrentGameState,
    actions: UiActions,
    callbacks: CurrentGameCallbacks,
) {
    LaunchActionButton(
        progress = game.launchProgress,
        // 没有实例时也让按钮可点：点击后给出原因并去安装页，而不是留下一个没有解释的灰按钮。
        enabled = !game.busy,
    ) {
        when {
            instance == null -> {
                actions.controller.store.notify(tr("pages.home.notify.noGame"))
                callbacks.onCreate()
            }
            instance.broken -> {
                actions.controller.store.notify(
                    tr("pages.home.notify.broken", instance.name, instance.brokenReason),
                )
                callbacks.onCreate()
            }
            !instance.installed -> {
                // 修复任务在后台推进，进度可从左侧「日志」入口查看。
                actions.controller.store.notify(
                    tr("pages.home.notify.notInstalled", instance.name),
                )
                actions.run { actions.controller.repair(instance.id) }
            }
            // 外部目录的版本文件被移走或删掉时无法自动补全，直接启动只会报错。
            instance.externalProfile && !game.filesPresent -> {
                actions.controller.store.notify(
                    tr("pages.home.notify.filesMissing", instance.name),
                )
                callbacks.onCreate()
            }
            !game.hasAccount -> {
                actions.controller.store.notify(tr("pages.home.notify.noAccount"))
                callbacks.onOpenAccounts()
            }
            else -> callbacks.onLaunch(instance)
        }
    }
}

/** 底部一行两个等宽入口：实例管理（进实例列表）与版本设置（进实例详情）。 */
@Composable
private fun CurrentGameBottomRow(
    instance: GameInstance?,
    busy: Boolean,
    callbacks: CurrentGameCallbacks,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 「实例管理」进实例列表页：顶部导航不含「实例」，这里是进入实例列表的入口。
        InstanceManageButton(
            modifier = Modifier.weight(1f),
            onClick = callbacks.onOpenInstances,
        )
        // 「版本设置」进入该实例的详情 / 设置页。
        SecondaryButton(
            onClick = { instance?.let { callbacks.onManage(it.id) } },
            modifier = Modifier.weight(1f),
            enabled = instance != null && !busy,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        ) {
            // 只留文字并居中：两侧内边距对称，不再保留任何图标或图标间距。
            Text(
                LocalStrings.current.t("pages.home.versionSettings"),
                Modifier.fillMaxWidth(),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 右栏世界 / 服务器面板：折叠状态与正文类别都写进开始页偏好。 */
@Composable
internal fun ColumnScope.HomeRecentPlaySection(
    state: LauncherState,
    instance: GameInstance,
    actions: UiActions,
    busy: Boolean,
    onLaunch: (GameInstance, GameLaunchTarget?) -> Unit,
) {
    val preferences = state.data.settings.pages.home
    RecentPlayPanel(
        instance = instance,
        actions = actions,
        busy = busy,
        expanded = preferences.recentPlayExpanded,
        onExpandedChange = { value ->
            // 折叠状态写进开始页偏好：与设置页同一条保存路径，重启后按记住的值渲染。
            applySetting(actions) {
                it.copy(
                    pages =
                        it.pages.copy(
                            home =
                                it.pages.home.copy(recentPlayExpanded = value),
                        ),
                )
            }
        },
        // 正文只显示一类：存档或服务器，选择同样写入开始页偏好。
        kind =
            if (preferences.recentPlayKind == "server") {
                EntryKind.Server
            } else {
                EntryKind.World
            },
        onKindChange = { value ->
            applySetting(actions) {
                it.copy(
                    pages =
                        it.pages.copy(
                            home =
                                it.pages.home.copy(
                                    recentPlayKind =
                                        if (value == EntryKind.Server) {
                                            "server"
                                        } else {
                                            "world"
                                        },
                                ),
                        ),
                )
            }
        },
        onLaunch = onLaunch,
        // fill = false：折叠后卡片按标题行高度收起。
        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
    )
}

/** 启动浮窗的四个操作入口。 */
internal data class HomeLaunchCardCallbacks(
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
    val onViewLogs: () -> Unit,
    val onDismiss: () -> Unit,
)

/** 右下角启动浮窗：只读真实任务进度，点击页面其他区域不会关闭它。 */
@Composable
internal fun HomeLaunchCard(
    card: LaunchCardState,
    job: DownloadJob?,
    lastLog: String,
    callbacks: HomeLaunchCardCallbacks,
    modifier: Modifier = Modifier,
) {
    LaunchProgressCard(
        instanceName = card.instanceName,
        job = job,
        error = card.localError,
        startedAtMillis = card.startedAtMillis,
        lastLog = lastLog,
        modifier = modifier,
        onCancel = callbacks.onCancel,
        onRetry = callbacks.onRetry,
        onViewLogs = callbacks.onViewLogs,
        onDismiss = callbacks.onDismiss,
    )
}
