// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责实例删除与删除前预览。

package dev.molifox.core

import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/** 实例删除与删除确认框需要的只读信息。 */
internal interface LauncherDeletionApi {
    /** 删除实例会永久删除的绝对路径（版本文件夹与游戏文件夹）；无法解析时为空，仅用于对话框展示。 */
    fun deletionTargets(instanceId: String): List<Path>

    /** 删除实例时会因其他实例真实使用而保留的路径与原因；只用于对话框展示。 */
    fun deletionPreserved(instanceId: String): List<String>

    /** 删除前就会被拒绝的原因（例如版本目录仍被另一个实例使用）；为空表示可以尝试删除。 */
    fun deletionBlockReason(instanceId: String): String?

    /** 删除实例会一并删除的存档名单（名称 + 上次修改时间），只用于确认框展示。 */
    fun deletionWorlds(instanceId: String): List<InstanceDeletionWorld>

    /** 实例是否有独立游戏目录；为真时存档、资源包、Mod 都在会被删除的目录里。 */
    fun hasSeparateGameDirectory(instanceId: String): Boolean

    suspend fun deleteInstance(
        instanceId: String,
        permanent: Boolean = false,
    ): Result<InstanceDeletionReport>
}

/** 实现由 [LauncherController] 委托调用；方法体与原实现一致。 */
internal abstract class LauncherDeletionActions(
    core: LauncherControllerCore,
) : LauncherSettingsActions(core),
    LauncherDeletionApi {
    /** 删除实例会永久删除的绝对路径（版本文件夹与游戏文件夹）；无法解析时为空，仅用于对话框展示。 */
    override fun deletionTargets(instanceId: String): List<Path> =
        runCatching {
            deletion.plan(instanceId).targets
        }.getOrElse { emptyList() }

    /** 删除实例时会因其他实例真实使用而保留的路径与原因；只用于对话框展示。 */
    override fun deletionPreserved(instanceId: String): List<String> =
        runCatching {
            deletion.plan(instanceId).preserved
        }.getOrElse { emptyList() }

    /**
     * 删除前就会被拒绝的原因（例如版本目录仍被另一个实例使用）；为空表示可以尝试删除。
     *
     * 确认框据此提前说明「这次删除会被阻止」，而不是让用户点下去才发现；真正执行时 [deleteInstance]
     * 会再校验一次，保证记录与文件不会被拆散。
     */

    override fun deletionBlockReason(instanceId: String): String? =
        runCatching { deletion.plan(instanceId) }
            .exceptionOrNull()
            ?.let { failure ->
                val message = failure.message
                if (message.isNullOrBlank()) I18n.t("instA2.deletion.blockedFallback") else message
            }

    /** 删除实例会一并删除的存档名单（名称 + 上次修改时间），只用于确认框展示。 */
    override fun deletionWorlds(instanceId: String): List<InstanceDeletionWorld> =
        runCatching {
            deletion.worlds(instanceId)
        }.getOrElse { emptyList() }

    /** 实例是否有独立游戏目录；为真时存档、资源包、Mod 都在会被删除的目录里。 */
    override fun hasSeparateGameDirectory(instanceId: String): Boolean =
        runCatching {
            val instance = store.instance(instanceId)
            store.gamePath(instance).toAbsolutePath().normalize() !=
                store.minecraftRoot(instance).toAbsolutePath().normalize()
        }.getOrDefault(false)

    /**
     * 删除实例：先处理实例自己的文件目录，全部真正删除后才移除启动器记录——不提供「只清记录」。
     *
     * [permanent] 为 true（界面上按住 Shift
     * 确认）时永久删除，否则移入系统回收站；回收站不可用时自动回退永久删除， 结果里的 [InstanceDeletionReport.movedToTrash] 会说明实际去向。
     * 实例正在运行、路径无法解析、目标是符号链接或不在实例自己目录下时返回失败，磁盘完全不动； 版本目录仍被其他实例
     * 使用时返回「无法删除：版本目录仍被实例「X」使用；请先处理该实例」并原样保留记录与文件； 删除中途失败时返回失败，已处理的部分不回滚。
     */

    override suspend fun deleteInstance(
        instanceId: String,
        permanent: Boolean,
    ): Result<InstanceDeletionReport> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(removeInstance(instanceId, permanent))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (blocked: InstanceDeletionBlockedException) {
                // 共用版本目录等「整次拒绝」：日志与界面用同一句话，不写「已删除实例记录」。
                store.log(blocked.message ?: I18n.t("instA2.deletion.blockedFallback"), true)
                Result.failure(blocked)
            } catch (failure: IOException) {
                failureResult(failure)
            } catch (failure: IllegalArgumentException) {
                failureResult(failure)
            } catch (failure: IllegalStateException) {
                failureResult(failure)
            } catch (failure: JsonParseException) {
                failureResult(failure)
            }
        }

    /** 删除实例文件与记录；任何一步失败都抛出，由 [deleteInstance] 统一转成失败结果。 */
    private fun removeInstance(
        instanceId: String,
        permanent: Boolean,
    ): InstanceDeletionReport {
        require(!game.isRunning(instanceId)) {
            I18n.t("instA2.deletion.instanceRunning")
        }
        requireIdle(instanceId)
        val instance = store.instance(instanceId)
        val plan = deletion.plan(instanceId)
        plan.preserved.forEach { store.log(I18n.t("instA2.deletion.preserved", it)) }
        // delete() 自己会核对每个目标是否真的消失；它成功返回才允许删记录。
        val report = deletion.delete(plan, permanent)
        forgetInstance(instance)
        // 删除成功后立刻以磁盘为准刷新一次：目录若仍被保留（共用/删除失败）就不会从列表消失。
        store.refreshInstances()
        icons.releaseUnused(instance.iconId)
        icons.releaseUnused(instance.packIconId)
        // 只报告真实发生的事：删了哪些、释放多少、哪些因共用而保留，不再把「保留」写成「已删除」。
        store.log(
            I18n.t("instA2.deletion.recordRemoved", instance.name, deletionSummary(report)),
        )
        return report
    }

    /**
     * 从记录里移除实例；选中项优先回退到同一游戏目录里的其他实例。
     *
     * 选中项存的可能是旧（归化前）id、目录名或显示名——[StoreRecordActions.instance] 都接受，
     * 所以不能只拿它和 `instance.id` 比字符串：那会漏掉别名形式的旧值，留下一个再也解析不到
     * 任何实例的 id，界面下次读取就会抛异常。这里改为在删除后的列表里重新解析：
     * 解析不到（说明指向的正是被删实例）才回退。
     */
    private fun forgetInstance(instance: GameInstance) {
        store.mutate { data ->
            val instances = data.instances.filter { it.id != instance.id }
            // 回退优先同一游戏目录里的实例，开始页与实例列表都不会跳到别的目录。
            val fallback =
                instances.firstOrNull {
                    it.gameDirectoryId == instance.gameDirectoryId
                } ?: instances.firstOrNull()
            val selected = data.selectedInstanceId
            val stillSelected =
                selected
                    ?.let { id ->
                        instances.any {
                            it.id == id || it.instanceSettingsKey() == id || it.name == id
                        }
                    }
                    ?: false
            data.copy(
                instances = instances,
                selectedInstanceId = if (stillSelected) selected else fallback?.id,
            )
        }
    }

    /** 删除结果里的可读摘要：删除范围、释放字节与保留原因。 */
    private fun deletionSummary(report: InstanceDeletionReport): String {
        val outcome =
            when {
                report.removedPaths.isEmpty() -> I18n.t("instA2.deletionSummary.directoryGone")
                report.preserved.isNotEmpty() ->
                    I18n.t(
                        "instA2.deletionSummary.removedAndPreserved",
                        report.removedPaths.size,
                        report.preserved.size,
                    )
                report.movedToTrash ->
                    I18n.t("instA2.deletionSummary.movedToTrash", report.removedPaths.size)
                else ->
                    I18n.t("instA2.deletionSummary.permanentlyDeleted", report.removedPaths.size)
            }
        val kept =
            report.preserved
                .takeIf { it.isNotEmpty() }
                ?.joinToString("；") { it }
                ?.let { I18n.t("instA2.deletionSummary.keptReason", it) }
                .orEmpty()
        val freed =
            if (report.removedPaths.isNotEmpty()) {
                I18n.t("instA2.deletionSummary.freed", report.freedBytes)
            } else {
                I18n.t("instA2.deletionSummary.freedZero")
            }
        return "$outcome$freed$kept"
    }

    private fun failureResult(failure: Exception): Result<InstanceDeletionReport> {
        store.log(
            I18n.t(
                "instA2.deletion.deleteFailed",
                failure.message ?: I18n.t("instA2.common.unknownError"),
            ),
            true,
        )
        return Result.failure(failure)
    }
}
