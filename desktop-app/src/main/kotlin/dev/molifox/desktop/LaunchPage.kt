// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责开始页账户摘要、实例选择和启动操作。

package dev.molifox.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.visible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.molifox.desktop.MoliButton as Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.GameInstance
import dev.molifox.core.GameLaunchTarget
import dev.molifox.core.JobStatus
import dev.molifox.core.LauncherState
import dev.molifox.core.LauncherTaskKind

/** 开始页：左栏是账户摘要与撑满列高的「当前游戏」卡（信息在上、启动按钮与「实例管理 / 版本设置」贴底）， 右栏是可折叠的世界 / 服务器面板。 */
@Composable
fun ColumnScope.HomePage(
    state: LauncherState,
    actions: UiActions,
    onNew: () -> Unit,
    navigate: (Page) -> Unit,
    onManage: (String) -> Unit,
    onOpenAccounts: () -> Unit,
) {
    // 选中记录可能为空或指向已删除的实例：先按「当前游戏目录 → 最近游玩」回退，再把回退结果
    // 写回状态。这样自动识别目录后开始页不会停在「未选择版本」，其他页面读到的也是同一个实例。
    val stored =
        state.data.instances.find {
            it.id == state.data.selectedInstanceId &&
                it.gameDirectoryId == state.data.activeGameDirectoryId
        }
    val selected =
        stored
            ?: state.data.instances
                .filter { it.gameDirectoryId == state.data.activeGameDirectoryId }
                .maxByOrNull { it.lastPlayed ?: it.createdAt }
    HomePageSync(state, actions, stored, selected)
    // 版本配置与客户端 jar 是否还在：只在选中项变化时于 IO 线程检查一次，缺失时如实说明原因。
    val filesPresent = rememberHomeFilesPresent(actions, selected)
    val hasAccount = state.data.accounts.any { it.id == state.data.activeAccountId }
    val busy =
        if (selected == null) {
            actions.controller.busy
        } else {
            actions.controller.isInstanceBusy(selected.id)
        }
    // 启动进度只读真实的排队/运行任务，不凭空造一份「launching」状态。
    val launchJob =
        state.jobs.firstOrNull {
            it.status == JobStatus.Running && it.kind == LauncherTaskKind.LaunchGame
        }
    val running = selected != null && state.runningGames.any { it.instanceId == selected.id }
    val launchCard = remember { LaunchCardState() }
    val cardJob = HomeLaunchCardTracking(state, launchCard)
    // 开始页的启动路径：先把选中实例写回状态，再把任务交给控制器；进度、取消与失败原因都落在浮窗里。
    val launcher = remember(actions, launchCard) { HomeLaunchHandlers(launchCard, actions) }
    val visible = rememberEntranceOnce()
    val installState = homeInstallState(selected, filesPresent)
    HomeStatisticsRow(state)
    // 浮窗叠在内容之上；开始页保持可见，点击浮窗外部不会关闭它。
    Box(Modifier.weight(1f).fillMaxWidth()) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(Metrics.cardGap),
        ) {
            // 左栏：账户摘要 + 撑满剩余高度的「当前游戏」卡（窗口最小 1000×640，内容不会被截断）。
            Entrance(visible, Modifier.width(296.dp).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.cardGap),
                ) {
                    // 账户摘要卡提供头像、名字和账户列表入口。
                    HomeCard(LocalStrings.current.t("pages.home.accounts.title")) {
                        HomeAccountSummary(state, actions, onOpenAccounts)
                    }
                    // 「当前游戏」撑到左栏底部：信息在上、中间弹性留白、启动与版本入口贴底。
                    HomeCard(
                        title = LocalStrings.current.t("pages.home.current.title"),
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        fillContent = true,
                    ) {
                        CurrentGameCardBody(
                            actions = actions,
                            game =
                                CurrentGameState(
                                    selected = selected,
                                    installState = installState,
                                    busy = busy,
                                    launchProgress = launchJob?.progress,
                                    running = running,
                                    filesPresent = filesPresent,
                                    hasAccount = hasAccount,
                                ),
                            callbacks =
                                CurrentGameCallbacks(
                                    onLaunch = { launcher.begin(it) },
                                    onCreate = onNew,
                                    onOpenInstances = { navigate(Page.Instances) },
                                    onManage = onManage,
                                    onOpenAccounts = onOpenAccounts,
                                ),
                        )
                    }
                }
            }
            Entrance(visible, Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Metrics.cardGap),
                ) {
                    // 右栏的世界 / 服务器面板：折叠后只有标题行，「查看全部」从标题行打开。
                    if (selected != null) {
                        HomeRecentPlaySection(state, selected, actions, busy) { game, target ->
                            launcher.begin(game, target)
                        }
                    }
                }
            }
        }
        // 启动浮窗固定在右下角：不遮挡左栏的「启动游戏」按钮，点击页面其他区域也不会关闭它。
        if (launchCard.visible) {
            HomeLaunchCard(
                card = launchCard,
                job = cardJob,
                lastLog =
                    state.logs
                        .lastOrNull()
                        ?.message
                        .orEmpty(),
                callbacks =
                    HomeLaunchCardCallbacks(
                        onCancel = { launcher.cancel() },
                        onRetry = {
                            state.data.instances
                                .firstOrNull { it.id == launchCard.instanceId }
                                ?.let { launcher.begin(it, launchCard.target) }
                        },
                        onViewLogs = {
                            launchCard.clear()
                            navigate(Page.Downloads)
                        },
                        onDismiss = { launcher.close(state) },
                    ),
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 2.dp, bottom = 2.dp),
            )
        }
    }
}

/** 开始页的启动入口：先把选中实例写回状态，再把任务交给控制器，失败原因留在浮窗里。 */
private class HomeLaunchHandlers(
    private val launchCard: LaunchCardState,
    private val actions: UiActions,
) {
    fun begin(
        instance: GameInstance,
        target: GameLaunchTarget? = null,
    ) {
        launchCard.begin(instance, target)
        actions.run { actions.controller.selectInstance(instance.id) }
        try {
            launchCard.attach(actions.controller.launch(instance.id, target))
        } catch (failure: IllegalArgumentException) {
            launchCard.fail(failure.message ?: tr("pages.home.launch.failed"))
        } catch (failure: IllegalStateException) {
            launchCard.fail(failure.message ?: tr("pages.home.launch.failed"))
        }
    }

    fun cancel() {
        launchCard.jobId?.let { actions.controller.jobs.cancel(it) }
    }

    fun close(state: LauncherState) {
        val status = state.jobs.firstOrNull { it.id == launchCard.jobId }?.status
        // 运行中手动关闭等同取消；已结束则只收起浮窗。
        if (status == JobStatus.Queued || status == JobStatus.Running) cancel()
        launchCard.clear()
    }
}

/**
 * 白卡片：与 Panel 同一套尺寸（圆角 6、1dp 细边、无阴影、内边距 16）。 单独实现是因为开始页的卡片需要自己掌控内容内边距，Panel 的内容列是固定 16。 [title]
 * 留空时不渲染标题行与分隔线。 [fillContent] 为 true 时内容列吃掉标题行以下的剩余高度，「当前游戏」卡靠它把启动按钮与版本入口贴到卡片底部； 右栏的
 * [RecentPlayPanel] 复用同一张卡片并传 0 内边距（标题行与内容区各自留白），因此这里是 internal 而不是 private。
 */
@Composable
internal fun HomeCard(
    title: String = "",
    modifier: Modifier = Modifier,
    fillContent: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(Metrics.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier,
        shape = RoundedCornerShape(Metrics.cardRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, hairline()),
    ) {
        Column {
            if (title.isNotBlank()) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = Metrics.cardPadding,
                                end = Metrics.cardPadding,
                                top = 11.dp,
                                bottom = 10.dp,
                            ),
                )
                HorizontalDivider(color = hairline())
            }
            Column(
                Modifier
                    .padding(contentPadding)
                    .then(if (fillContent) Modifier.weight(1f) else Modifier),
                content = content,
            )
        }
    }
}

/** 实例管理与版本设置共用无填充描边按钮。 */
@Composable
internal fun InstanceManageButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SecondaryButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
    ) {
        Text(
            LocalStrings.current.t("pages.home.instances.title"),
            Modifier.fillMaxWidth(),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** 首次组合时置为 true：卡片入场动画只播一次，之后切回本页不再重播。 */
@Composable
private fun rememberEntranceOnce(): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    return visible
}

/** 卡片入场：150ms 淡入 + 8dp 上移；关闭动画时直接呈现。 */
@Composable
private fun Entrance(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val offset = with(LocalDensity.current) { 8.dp.roundToPx() }
    Box(modifier) {
        if (LocalAnimationsEnabled.current) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(150)) + slideInVertically(tween(150)) { offset },
                exit = ExitTransition.None,
            ) {
                content()
            }
        } else {
            content()
        }
    }
}

/** 启动主按钮：整行铺满、44dp，圆角沿用 [Metrics.innerRadius]，15sp 单行文字；真正排队 / 启动时在左侧显示进度环与百分比。 */
@Composable
internal fun LaunchActionButton(
    progress: Float?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val strings = LocalStrings.current
    MoliOutlinedButton(
        onClick,
        Modifier.fillMaxWidth().height(44.dp).pressScale(),
        enabled = enabled,
        shape = RoundedCornerShape(Metrics.innerRadius),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
    ) {
        // 只有「正在启动」才出现进度环：它是任务进度。
        if (progress != null) {
            CircularProgressIndicator(
                Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = LocalContentColor.current,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            // 游戏已在运行时仍显示「开始游戏」，只由启动进度决定是否显示「正在启动」。
            if (progress != null) {
                strings.t("pages.launch.button.starting", (progress * 100).toInt())
            } else {
                strings.t("pages.launch.button.start")
            },
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
