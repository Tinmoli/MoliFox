// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 「修改」页签：同版本更换加载器，或升级游戏版本——新建实例并带入模组与迁移内容，旧实例原样保留。
// 结构：本文件是主组合与数据加载效果；卡片在 InstanceModifyVersions/Upgrade，浮层在 InstanceModifyProgress，
// 状态束在 ModifyTabStates，开始条件与计划组装在 InstanceModifyPlan。

package dev.molifox.desktop.instance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonParseException
import dev.molifox.core.DownloadJob
import dev.molifox.core.GameInstance
import dev.molifox.core.InstanceModUpgradeItem
import dev.molifox.core.InstanceModUpgradeState
import dev.molifox.core.InstanceUpgradePlan
import dev.molifox.core.LoaderType
import dev.molifox.core.LoaderVersion
import dev.molifox.core.compareLoaderVersions
import dev.molifox.core.defaultMigrationEntries
import dev.molifox.core.instanceDirectoryName
import dev.molifox.core.launchOnly
import dev.molifox.desktop.CompactTextField
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.ScrollColumn
import dev.molifox.desktop.SettingsSection
import dev.molifox.desktop.UiActions
import dev.molifox.desktop.rememberInstanceBusy
import dev.molifox.desktop.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 「修改」页签：升级加载器卡片 + 版本与加载器卡片 +（升级模式）名称/模组/迁移卡片 + 执行浮层。 */
@Composable
internal fun InstanceModifyTab(
    instance: GameInstance,
    actions: UiActions,
    modifier: Modifier = Modifier,
) {
    val states = remember(instance.id) { ModifyTabStates() }
    // 外部实例能否合并进原 JSON（继承链缺失等）由核心预检给出具体原因；只读，不改文件。
    val blocker = modifyBlocker(instance, states.externalBlocker.value)

    // 目标游戏版本为 null 表示保持当前版本（仅更换加载器）；选择更高版本即进入升级模式。
    val pickedGameVersion = states.pickedGameVersion.value
    val upgradeMode = pickedGameVersion != null

    var nameInput by remember(instance.id) { mutableStateOf("") }
    // 用户手动选过的加载器版本；null 表示还没选，按列表里最新的算。
    var pickedLoaderVersion by remember(instance.id) { mutableStateOf<String?>(null) }

    var runMode by remember(instance.id) { mutableStateOf<ModifyRunMode?>(null) }
    var runningJobId by remember(instance.id) { mutableStateOf<String?>(null) }
    var runPlan by remember(instance.id) { mutableStateOf<InstanceUpgradePlan?>(null) }
    var runLoaderVersion by remember(instance.id) { mutableStateOf<String?>(null) }

    val liveState by actions.controller.state.collectAsState()
    // 主组合只读预扫结果；写入都在效果组合里。
    val scanResult by states.scan

    LaunchedEffect(instance.id, instance.externalProfile) {
        states.externalBlocker.value =
            if (instance.externalProfile) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        actions.controller.installer.externalLoaderUpgradeBlocker(instance)
                    }.getOrElse { it.message ?: tr("pages.instance.install.externalConfigUnreadable") }
                }
            } else {
                null
            }
    }

    ModifyVersionEffects(instance, actions, blocker, states)
    ModifyScanEffects(instance, actions, blocker, states)

    ScrollColumn(modifier, gap = 8.dp) {
        if (blocker != null) {
            SettingsSection(tr("pages.instance.install.upgrade.title")) {
                Text(blocker, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val loaderOptions = loaderModifyOptions(instance, states.loaderVersions.value)
            // 当前版本一定非空（blocker 已拦截），所以候选列表至少包含它，选中值不会真的为 null。
            val selectedLoader = selectedLoaderVersion(pickedLoaderVersion, loaderOptions)
            val sameLoader = selectedLoader == instance.loaderVersion
            val mods = scanResult?.mods.orEmpty()
            val windows = actions.controller.platform.osName == "windows"
            val nameCheck =
                checkNewInstanceName(nameInput, liveState.data.instances.map { it.name }, windows)
            val busy = rememberInstanceBusy(actions, instance.id)
            // 实例正在游戏中（含启动器重启前遗留、仍在运行的进程）：迁移要读 mods 与存档、
            // 换加载器要写版本目录，运行期间一律禁止修改；没有运行则一切照常。
            val gameRunning = actions.controller.game.isRunning(instance.id)
            val migrationDefaults = defaultMigrationEntries(scanResult?.migration.orEmpty())
            val modDefaults = mods.filter { defaultModChecked(it) }.map { it.filename }.toSet()
            val effectiveMods = states.checkedMods.value ?: modDefaults
            val effectiveMigration = states.checkedMigration.value ?: migrationDefaults
            val canStart =
                modifyCanStart(states, upgradeMode, sameLoader, busy, gameRunning, nameCheck.ok)

            fun startUpgrade() {
                val plan =
                    buildUpgradePlan(
                        nameCheck.sanitized,
                        states.pickedGameVersion.value,
                        instance.gameVersion,
                        selectedLoader,
                        mods,
                        effectiveMods,
                        scanResult?.migration.orEmpty(),
                        effectiveMigration,
                        states.checkedMigrationExcluded.value,
                    )
                runMode = ModifyRunMode.UpgradeVersion
                runPlan = plan
                actions.run {
                    runningJobId = actions.controller.upgradeInstanceVersion(instance.id, plan)
                }
                actions.taskQueued()
            }

            fun startLoaderUpgrade() {
                runMode = ModifyRunMode.UpgradeLoader
                runLoaderVersion = selectedLoader
                actions.run {
                    actions.controller.upgradeInstanceLoader(instance.id, selectedLoader)
                }
                actions.taskQueued()
            }

            fun start() {
                // 按钮已置灰，这里再拦一次：覆盖执行浮层里「重试」这条不经过按钮的入口。
                if (gameRunning) return
                if (upgradeMode) startUpgrade() else startLoaderUpgrade()
            }

            ModifyHeaderCard(instance, upgradeMode, sameLoader, selectedLoader, nameInput, nameCheck) {
                nameInput = it
            }
            ModifyVersionsCard(
                instance,
                upgradeMode,
                states.gameVersions.value,
                states.supportedGameVersions.value,
                pickedGameVersion,
                onPickGameVersion = { states.pickedGameVersion.value = it },
                states.gameVersionLoading.value,
                states.gameVersionError.value,
                onGameVersionRetry = { states.gameVersionRetry.value++ },
                loaderOptions,
                selectedLoader,
                onPickLoader = { pickedLoaderVersion = it },
                states.loaderLoading.value,
                states.loaderError.value,
                onLoaderRetry = { states.loaderRetry.value++ },
                states.loaderListEmpty(),
            )
            if (upgradeMode) {
                ModsCard(
                    mods,
                    states.scanning.value,
                    states.scanError.value,
                    effectiveMods,
                    onToggle = { filename, checked ->
                        states.checkedMods.value =
                            toggledSelection(states.checkedMods.value, modDefaults, filename, checked)
                    },
                    onRescan = { states.scanRetry.value++ },
                )
                MigrationCard(
                    scanResult?.migration.orEmpty(),
                    effectiveMigration,
                    states.checkedMigrationExcluded.value,
                    onToggle = { key, checked ->
                        states.checkedMigration.value =
                            toggledSelection(states.checkedMigration.value, migrationDefaults, key, checked)
                    },
                ) { path, checked ->
                    // 回调第二参是「新的勾选状态」：取消勾选 = 加入排除集，恢复勾选 = 连同子路径一并移除。
                    states.checkedMigrationExcluded.value =
                        toggledExclusion(states.checkedMigrationExcluded.value, path, !checked)
                }
            }
            ModifyFooter(upgradeMode, canStart, gameRunning) { start() }
            // 执行浮层放在能拿到 start() 的作用域里：失败重试就是按当前表单原样再执行一次。
            ModifyRunOverlay(
                runMode,
                runPlan,
                runLoaderVersion,
                instance,
                liveState.jobs,
                runningJobId,
                onDismiss = {
                    runMode = null
                    runningJobId = null
                    runPlan = null
                    runLoaderVersion = null
                },
                onRetry = {
                    runMode = null
                    runningJobId = null
                    runPlan = null
                    runLoaderVersion = null
                    start()
                },
            )
        }
    }
}

/** 游戏版本与加载器版本清单：切换目标版本或重试时重新拉取。 */
@Composable
private fun ModifyVersionEffects(
    instance: GameInstance,
    actions: UiActions,
    blocker: String?,
    states: ModifyTabStates,
) {
    var picked by states.pickedGameVersion
    var versions by states.gameVersions
    var supported by states.supportedGameVersions
    var versionLoading by states.gameVersionLoading
    var versionError by states.gameVersionError
    var versionRetry by states.gameVersionRetry
    var loaderList by states.loaderVersions
    var loaderBusy by states.loaderLoading
    var loaderFailure by states.loaderError
    var loaderRetries by states.loaderRetry
    val targetGameVersion = picked ?: instance.gameVersion

    // 游戏版本清单：全量发布版本 + 受限加载器（Forge 系）支持集合，用于过滤可升级目标。
    LaunchedEffect(instance.id, instance.gameVersion, blocker, versionRetry) {
        versions = emptyList()
        supported = null
        versionError = null
        if (blocker != null) return@LaunchedEffect
        versionLoading = true
        try {
            versions = withContext(Dispatchers.IO) { actions.controller.installer.versions() }
            supported =
                withContext(Dispatchers.IO) {
                    actions.controller.installer.supportedGameVersions(instance.loader)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            versionError = failure.message
        } catch (failure: IllegalStateException) {
            versionError = failure.message
        } catch (failure: JsonParseException) {
            versionError = failure.message
        } finally {
            versionLoading = false
        }
    }

    // 加载器版本清单跟随目标游戏版本重新拉取。
    LaunchedEffect(instance.id, instance.loader, targetGameVersion, blocker, loaderRetries) {
        loaderList = emptyList()
        loaderFailure = null
        if (blocker != null) return@LaunchedEffect
        loaderBusy = true
        try {
            loaderList =
                withContext(Dispatchers.IO) {
                    actions.controller.installer.loaderVersions(instance.loader, targetGameVersion)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            loaderFailure = failure.message
        } catch (failure: IllegalStateException) {
            loaderFailure = failure.message
        } catch (failure: JsonParseException) {
            loaderFailure = failure.message
        } finally {
            loaderBusy = false
        }
    }
}

/** 预扫效果：模组跨版本判定 + 迁移内容统计；只在升级模式且能修改时执行。 */
@Composable
private fun ModifyScanEffects(
    instance: GameInstance,
    actions: UiActions,
    blocker: String?,
    states: ModifyTabStates,
) {
    var picked by states.pickedGameVersion
    var scanResult by states.scan
    var scanBusy by states.scanning
    var scanFailure by states.scanError
    var scanRetries by states.scanRetry
    var modChecks by states.checkedMods
    var migrationChecks by states.checkedMigration
    var migrationExcluded by states.checkedMigrationExcluded
    val targetGameVersion = picked ?: instance.gameVersion

    LaunchedEffect(instance.id, picked, blocker, scanRetries) {
        scanResult = null
        scanFailure = null
        modChecks = null
        migrationChecks = null
        migrationExcluded = emptySet()
        if (blocker != null || picked == null) return@LaunchedEffect
        scanBusy = true
        try {
            scanResult =
                withContext(Dispatchers.IO) {
                    actions.controller.scanInstanceUpgrade(instance.id, targetGameVersion)
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            scanFailure = failure.message
        } catch (failure: IllegalStateException) {
            scanFailure = failure.message
        } catch (failure: JsonParseException) {
            scanFailure = failure.message
        } finally {
            scanBusy = false
        }
    }
}

/** 头部卡片：升级模式是名称卡（带可用性提示），换加载器模式是目标状态卡。 */
@Composable
private fun ModifyHeaderCard(
    instance: GameInstance,
    upgradeMode: Boolean,
    sameLoader: Boolean,
    selectedLoader: String,
    nameInput: String,
    nameCheck: NameCheck,
    onNameInput: (String) -> Unit,
) {
    val strings = LocalStrings.current
    if (upgradeMode) {
        SettingsSection(
            strings.t("pages.instance.modify.name.title"),
            strings.t("pages.instance.modify.name.detail"),
        ) {
            CompactTextField(nameInput, onNameInput, Modifier.fillMaxWidth())
            Text(
                nameCheck.message,
                fontSize = 11.sp,
                color =
                    if (nameCheck.ok) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }
    } else {
        // 卡片只有标题 + 目标状态，不放说明文字；加载中/失败的状态在下方版本卡片里体现。
        ModifyTitleCard(strings.t("pages.instance.install.upgrade.title")) {
            ModifyStateChip(
                if (sameLoader) {
                    strings.t("pages.instance.modify.sameVersion", instance.loaderVersion)
                } else {
                    instance.loader.title + " " + instance.loaderVersion + " → " + selectedLoader
                },
                warn = sameLoader,
            )
        }
    }
}

/** 页脚：左侧提示文案随模式与运行状态切换，右侧开始按钮。 */
@Composable
private fun ModifyFooter(
    upgradeMode: Boolean,
    canStart: Boolean,
    gameRunning: Boolean,
    onStart: () -> Unit,
) {
    val strings = LocalStrings.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            strings.t(
                when {
                    gameRunning -> "pages.instance.modify.hint.running"
                    upgradeMode -> "pages.instance.modify.hint.upgrade"
                    else -> "pages.instance.modify.hint.same"
                },
            ),
            Modifier.weight(1f),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PrimaryButton(strings.t("pages.instance.modify.start"), enabled = canStart) { onStart() }
    }
}

/** 执行浮层：runMode 非空时展示；重试按当前表单原样再执行一次。 */
@Composable
private fun ModifyRunOverlay(
    runMode: ModifyRunMode?,
    runPlan: InstanceUpgradePlan?,
    runLoaderVersion: String?,
    instance: GameInstance,
    jobs: List<DownloadJob>,
    runningJobId: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    runMode?.let { mode ->
        ModifyProgressDialog(
            mode,
            runPlan,
            runLoaderVersion,
            instance,
            jobs,
            runningJobId,
            onDismiss,
            onRetry,
        )
    }
}

/** 当前加载器全部可选版本：合并现有版本，去重后按版本号新到旧排；不加正式/测试标注。 */
private fun loaderModifyOptions(
    instance: GameInstance,
    versions: List<LoaderVersion>,
): List<LoaderVersion> =
    (versions + LoaderVersion(instance.loaderVersion))
        .distinctBy { it.version }
        .sortedWith { left, right -> compareLoaderVersions(right.version, left.version) }

/** 不能更换加载器的原因；顺序与界面展示优先级一致，可以修改时返回 null。 */
private fun modifyBlocker(
    instance: GameInstance,
    externalBlocker: String?,
): String? =
    when {
        instance.broken && instance.externalProfile ->
            tr(
                "pages.instance.install.blocker.externalBroken",
                instance.brokenReason.ifBlank { tr("pages.instance.install.externalBrokenDefault") },
            )

        instance.loader == LoaderType.Vanilla -> tr("pages.instance.install.blocker.vanilla")

        instance.packFile.isNotBlank() -> tr("pages.instance.install.blocker.modpack")

        instance.loader.launchOnly -> tr("pages.instance.modify.blocker.launchOnly")

        instance.externalProfile && externalBlocker != null -> externalBlocker.orEmpty()

        !instance.installed -> tr("pages.instance.install.blocker.notInstalled")

        instance.loaderVersion.isBlank() -> tr("pages.instance.install.blocker.versionUnknown")

        else -> null
    }

/** 名称校验结论：[ok] 为真时 [sanitized] 是真正会创建的目录名。 */
private data class NameCheck(
    val ok: Boolean,
    val message: String,
    val sanitized: String,
)

/** 实例名预校验：非空、可落盘（复用核心的目录名清洗）、与现有实例不重名（Windows 不区分大小写）。 */
private fun checkNewInstanceName(
    input: String,
    existingNames: List<String>,
    windows: Boolean,
): NameCheck {
    if (input.isBlank()) return NameCheck(false, tr("pages.instance.modify.name.required"), "")
    val sanitized =
        try {
            instanceDirectoryName(input)
        } catch (expected: IllegalArgumentException) {
            return NameCheck(false, expected.message ?: tr("pages.instance.modify.name.required"), "")
        }
    val clash =
        existingNames.firstOrNull { it.equals(sanitized, ignoreCase = windows) }
    return if (clash != null) {
        NameCheck(false, tr("pages.instance.modify.name.taken"), "")
    } else {
        NameCheck(true, tr("pages.instance.modify.name.available"), sanitized)
    }
}

/**
 * 模组勾选默认值：有新版本默认安装，无需更新默认按原样带入；
 * 查询失败与不兼容不可勾选，也不会进升级计划。
 */
private fun defaultModChecked(item: InstanceModUpgradeItem): Boolean =
    when (item.state) {
        InstanceModUpgradeState.HasNewVersion -> true
        InstanceModUpgradeState.CarryAsIs -> true
        else -> false
    }
