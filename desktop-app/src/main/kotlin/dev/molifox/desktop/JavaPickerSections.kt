// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责 Java 选择器的区块：当前生效项、候选行、自定义行、检测行，以及选择/移除动作。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.JavaRuntime
import dev.molifox.core.JavaRuntimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** 只列出用户添加过的自定义路径；当前临时指定的路径由「自定义」一行承载。 */
internal fun listedJavaRuntimes(
    runtimes: List<JavaRuntime>,
    knownCustomPaths: List<String>,
    windows: Boolean,
): List<JavaRuntime> =
    runtimes
        .filter {
            it.source != JavaRuntimeSource.Custom ||
                knownCustomPaths.any { path -> sameJavaPath(path, it.path, windows) }
        }.sortedWith(
            compareByDescending<JavaRuntime> { it.usable }
                .thenByDescending { it.majorVersion }
                .thenBy { it.source.ordinal },
        )

/** 当前生效项的标题：能找到运行时就用它的标签，否则退回自动或自定义。 */
internal fun javaPickerHeaderLabel(
    effectiveRuntime: JavaRuntime?,
    effectivePath: String,
): String =
    when {
        effectiveRuntime != null -> javaRuntimeLabel(effectiveRuntime)
        effectivePath.isBlank() -> AUTO_JAVA_LABEL
        else -> "自定义"
    }

/** 自动项的补充说明：实例作用域继承全局时写出实际生效的那一份。 */
internal fun javaPickerAutoDetail(
    inheritedPath: String,
    runtimes: List<JavaRuntime>,
    windows: Boolean,
): String =
    if (inheritedPath.isBlank()) {
        "按游戏版本自动匹配"
    } else {
        "跟随全局设置：" + javaChoiceName(inheritedPath, runtimes, windows)
    }

/** 启动器安装的与用户添加的可以删（自定义只删记录）；系统 Java 只读。 */
internal fun removableJava(
    runtime: JavaRuntime,
    windows: Boolean,
    knownCustomPaths: List<String>,
): Boolean =
    runtime.managed ||
        (
            runtime.source == JavaRuntimeSource.Custom &&
                knownCustomPaths.any { sameJavaPath(it, runtime.path, windows) }
        )

// 所有路径统一交给 JavaService 探测；探测失败只提示，不写回、不清空已保存的选择。
internal fun browseJava(
    actions: UiActions,
    windows: Boolean,
    add: Boolean,
    isPicking: () -> Boolean,
    onPicking: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onAddCustom: ((String) -> Unit)?,
    onRefresh: (() -> Unit)?,
) {
    if (isPicking()) return
    onPicking(true)
    onError(null)
    actions.run {
        try {
            val chosen =
                actions
                    .choose(
                        if (add) "添加 Java 可执行文件" else "选择 Java 可执行文件",
                        extensions = if (windows) listOf("exe") else emptyList(),
                    ).firstOrNull()
            if (chosen != null) {
                val runtime =
                    withContext(Dispatchers.IO) {
                        runCatching { actions.controller.java.inspect(chosen.toString()) }
                            .getOrNull()
                    }
                withContext(Dispatchers.Swing) {
                    if (runtime == null) {
                        onError("这个文件不是可运行的 Java：" + chosen)
                    } else {
                        if (add) {
                            onAddCustom?.invoke(runtime.path)
                            actions.controller.java.invalidate()
                            onRefresh?.invoke()
                        }
                        onSelect(runtime.path)
                    }
                }
            }
        } finally {
            withContext(Dispatchers.Swing) { onPicking(false) }
        }
    }
}

internal fun removeJavaRuntime(
    actions: UiActions,
    value: String,
    runtime: JavaRuntime,
    windows: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: (() -> Unit)?,
) {
    val job = actions.controller.removeJavaRuntime(runtime)
    // 当前选择指向被删的运行时：回到自动，避免留下失效路径。
    if (sameJavaPath(value, runtime.path, windows)) onSelect("")
    actions.run {
        job.join()
        withContext(Dispatchers.Swing) { onRefresh?.invoke() }
    }
}

/** 顶部一行：当前生效的选择、来源与占用（实例作用域可能继承全局设置）。 */
@Composable
internal fun JavaPickerHeader(
    headerLabel: String,
    headerMeta: String,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("当前使用", fontSize = 12.sp, color = colors.onSurfaceVariant)
        Text(headerLabel, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        if (headerMeta.isNotBlank()) {
            Text(
                "（" + headerMeta + "）",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.weight(1f))
        if (canRemove) {
            TextButton(
                onRemove,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("移除", fontSize = 12.sp)
            }
        }
    }
}

/** 一个检测到的运行时：版本标签 + 来源标签 + 路径，右侧可选「移除」。 */
@Composable
internal fun JavaListedChoice(
    runtime: JavaRuntime,
    selected: Boolean,
    removable: Boolean,
    onRemove: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val invalid = !runtime.usable
    JavaChoiceRow(
        selected = selected,
        label = javaRuntimeLabel(runtime),
        tag =
            when {
                invalid -> runtime.invalidReason
                runtime.source == JavaRuntimeSource.System -> null
                else -> javaSourceTitle(runtime.source)
            },
        tagColor = if (invalid) colors.error else sourceColor(runtime.source),
        path = runtime.path,
        onClick = onClick,
        trailing =
            if (removable) {
                {
                    TextButton(
                        onRemove,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("移除", fontSize = 11.sp)
                    }
                }
            } else {
                null
            },
    )
}

/** 「自定义」一行：已指定时可点选，未指定时直接打开文件选择框。 */
@Composable
internal fun JavaCustomChoice(
    customPath: String,
    picking: Boolean,
    onPickCustom: () -> Unit,
    onBrowse: () -> Unit,
) {
    JavaChoiceRow(
        selected = customPath.isNotBlank(),
        label = "自定义",
        detail = if (customPath.isBlank()) "尚未选择 Java 可执行文件" else null,
        path = customPath.ifBlank { null },
        onClick = onPickCustom,
        trailing = {
            SecondaryButton(
                onBrowse,
                enabled = !picking,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (picking) "选择中…" else "选择…", fontSize = 12.sp)
            }
        },
    )
}

/** 添加与重新扫描：左侧「添加 Java」入口，右侧检测状态与重扫按钮。 */
@Composable
internal fun JavaDetectRow(
    detecting: Boolean,
    detectionError: String?,
    onAdd: () -> Unit,
    onRefresh: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(Metrics.innerRadius))
                .hoverHighlight()
                .clickable(role = Role.Button, onClick = onAdd)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Outlined.Add,
                null,
                Modifier.size(16.dp),
                tint = colors.primary,
            )
            Text("添加 Java", fontSize = 13.sp, color = colors.primary)
        }
        Spacer(Modifier.weight(1f))
        Text(
            when {
                detecting -> "正在检测…"
                detectionError != null -> "检测失败，可重试"
                else -> "重新扫描系统与启动器目录"
            },
            fontSize = 11.sp,
            color = if (detectionError != null) colors.error else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (onRefresh != null) {
            SecondaryButton(
                onRefresh,
                enabled = !detecting,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(if (detecting) "检测中…" else "检测 Java", fontSize = 12.sp)
            }
        }
    }
}
