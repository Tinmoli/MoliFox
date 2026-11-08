// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 「修改」页签的执行进度浮层：升级五步 / 换加载器两步，失败可重试，运行中可关到任务中心。

package dev.molifox.desktop.instance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.DownloadJob
import dev.molifox.core.GameInstance
import dev.molifox.core.InstanceUpgradePlan
import dev.molifox.core.JobStatus
import dev.molifox.core.i18n.Strings
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.Modal
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.SecondaryButton

/** 浮层里单个步骤的展示状态。 */
private enum class ModifyStepState { Pending, Running, Done, Failed }

/** 升级执行五步的进度阈值：与 InstanceUpgradeService 的锚点保持一致。 */
private const val STEP_INSTALL_END = 0.45f
private const val STEP_MODS_END = 0.72f
private const val STEP_MIGRATION_END = 0.94f

/** 加载器更换两步的进度阈值：0.90 之后进入替换与刷新阶段。 */
private const val LOADER_STEP_SWAP = 0.90f

/** 最后两个升级步骤的序号（迁移=3、校验=4），避免在映射函数里写魔法数字。 */
private const val STEP_MIGRATE_INDEX = 3
private const val STEP_VERIFY_INDEX = 4

/** 执行进度浮层：步骤按 DownloadJob 的进度阈值点亮，完成/失败给出横幅与出口。 */
@Composable
internal fun ModifyProgressDialog(
    mode: ModifyRunMode,
    plan: InstanceUpgradePlan?,
    loaderVersion: String?,
    source: GameInstance,
    jobs: List<DownloadJob>,
    jobId: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val strings = LocalStrings.current
    val job = jobId?.let { id -> jobs.firstOrNull { it.id == id } }
    val status = job?.status ?: JobStatus.Queued
    val progress = job?.progress ?: 0f
    val upgrade = mode == ModifyRunMode.UpgradeVersion
    val steps = modifySteps(strings, upgrade, plan, loaderVersion, source)
    val currentStep = if (upgrade) upgradeStepIndex(progress) else loaderStepIndex(progress)
    val success = status == JobStatus.Completed
    val failed = status == JobStatus.Failed
    val cancelled = status == JobStatus.Cancelled
    // 用户在任务中心取消了任务：浮层直接关闭，结果留在任务中心。
    // onDismiss 经 rememberUpdatedState 取最新值，避免 effect 记住上一次组合的回调。
    val dismiss = rememberUpdatedState(onDismiss)
    LaunchedEffect(cancelled) {
        if (cancelled) dismiss.value()
    }
    Modal(
        title = modifyDialogTitle(strings, upgrade, success, failed),
        subtitle = if (failed || success) "" else job?.detail.orEmpty(),
        onDismiss = onDismiss,
    ) {
        steps.forEachIndexed { index, label ->
            ModifyStepRow(label, modifyStepState(index, currentStep, success, failed))
        }
        if (success) {
            ModifyBanner(modifySuccessText(strings, upgrade, plan, loaderVersion, source), error = false)
        }
        if (failed) {
            ModifyBanner(job?.error ?: strings.t("pages.instance.modify.banner.failed"), error = true)
        }
        ModifyRunActions(strings, success, failed, onRetry, onDismiss)
    }
}

/** 浮层的步骤清单：升级五步（创建/安装/模组/迁移/校验）或换加载器两步。 */
private fun modifySteps(
    strings: Strings,
    upgrade: Boolean,
    plan: InstanceUpgradePlan?,
    loaderVersion: String?,
    source: GameInstance,
): List<String> =
    if (upgrade) {
        listOf(
            strings.t("pages.instance.modify.steps.create", plan?.newName.orEmpty()),
            strings.t(
                "pages.instance.modify.steps.install",
                plan?.targetGameVersion.orEmpty(),
                source.loader.title,
                plan?.loaderVersion.orEmpty(),
            ),
            strings.t("pages.instance.modify.steps.mods"),
            strings.t("pages.instance.modify.steps.migrate", plan?.migrationEntries?.size ?: 0),
            strings.t("pages.instance.modify.steps.verify"),
        )
    } else {
        listOf(
            strings.t(
                "pages.instance.modify.steps.loaderDownload",
                source.loader.title,
                loaderVersion.orEmpty(),
            ),
            strings.t("pages.instance.modify.steps.loaderSwap"),
        )
    }

/** 浮层标题：失败/完成优先，其次按路径区分升级与换加载器。 */
private fun modifyDialogTitle(
    strings: Strings,
    upgrade: Boolean,
    success: Boolean,
    failed: Boolean,
): String =
    when {
        failed -> strings.t("pages.instance.modify.title.failed")
        success -> strings.t("pages.instance.modify.title.done")
        upgrade -> strings.t("pages.instance.modify.title.upgrading")
        else -> strings.t("pages.instance.modify.title.loader")
    }

/** 单个步骤的展示状态：失败停在当前步，已完成走完，其余未开始的保持待办。 */
private fun modifyStepState(
    index: Int,
    currentStep: Int,
    success: Boolean,
    failed: Boolean,
): ModifyStepState =
    when {
        failed && index == currentStep -> ModifyStepState.Failed
        index < currentStep -> ModifyStepState.Done
        index == currentStep && !failed && !success -> ModifyStepState.Running
        else -> ModifyStepState.Pending
    }

/** 成功横幅：说明新实例/新加载器的最终状态。 */
private fun modifySuccessText(
    strings: Strings,
    upgrade: Boolean,
    plan: InstanceUpgradePlan?,
    loaderVersion: String?,
    source: GameInstance,
): String =
    if (upgrade) {
        strings.t(
            "pages.instance.modify.banner.success.upgrade",
            plan?.newName.orEmpty(),
            plan?.targetGameVersion.orEmpty(),
            source.loader.title,
            plan?.loaderVersion.orEmpty(),
            source.name,
        )
    } else {
        strings.t("pages.instance.modify.banner.success.loader", source.loader.title, loaderVersion.orEmpty())
    }

/** 底部操作：失败给重试 + 关闭，完成给关闭，运行中给「收起」到任务中心。 */
@Composable
private fun ModifyRunActions(
    strings: Strings,
    success: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        when {
            failed -> {
                SecondaryButton(onRetry) { Text(strings.t("pages.instance.modify.retry")) }
                Spacer(Modifier.width(8.dp))
                PrimaryButton(strings.t("dialogs.common.close")) { onDismiss() }
            }

            success -> PrimaryButton(strings.t("dialogs.common.close")) { onDismiss() }

            else -> SecondaryButton(onDismiss) { Text(strings.t("pages.instance.modify.cancelRun")) }
        }
    }
}

/** 升级进度 → 步骤序号：创建=0，安装=1..2，模组=2..3，迁移=3..4，校验=4。 */
private fun upgradeStepIndex(progress: Float): Int =
    when {
        progress >= STEP_MIGRATION_END -> STEP_VERIFY_INDEX
        progress >= STEP_MODS_END -> STEP_MIGRATE_INDEX
        progress >= STEP_INSTALL_END -> 2
        progress > 0f -> 1
        else -> 0
    }

/** 加载器进度 → 步骤序号：下载=0，替换与刷新=1。 */
private fun loaderStepIndex(progress: Float): Int = if (progress >= LOADER_STEP_SWAP) 1 else 0

@Composable
private fun ModifyStepRow(
    label: String,
    state: ModifyStepState,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (state) {
            ModifyStepState.Done ->
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp),
                )

            ModifyStepState.Running ->
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)

            ModifyStepState.Failed ->
                Icon(
                    Icons.Outlined.Cancel,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(16.dp),
                )

            ModifyStepState.Pending ->
                Icon(
                    Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
        }
        Text(
            label,
            Modifier.weight(1f),
            fontSize = 13.sp,
            fontWeight = if (state == ModifyStepState.Running) FontWeight.Medium else FontWeight.Normal,
            color =
                if (state == ModifyStepState.Pending) {
                    colors.onSurfaceVariant
                } else {
                    colors.onSurface
                },
        )
    }
}

/** 结果横幅：主题色为成功、错误色为失败。 */
@Composable
private fun ModifyBanner(
    text: String,
    error: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val tint: Color = if (error) colors.error else colors.primary
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(alpha = .12f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        fontSize = 12.sp,
        color = tint,
    )
}
