// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 共享表单描述与提交入口，确保各设置分组的保存行为一致。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LauncherSettings

/** 设置卡片里的一行或一组控件：标题与说明参与搜索匹配。 */
internal class SettingBlock(
    val title: String,
    val detail: String,
    val render: @Composable () -> Unit,
)

/** 一张设置卡片：改动即保存，卡片本身不持有「未保存」状态，因此没有重置本区。 */
internal class SettingCard(
    val title: String,
    val detail: String = "",
    val blocks: List<SettingBlock>,
    val collapsible: Boolean = false,
    val startExpanded: Boolean = true,
)

/** 卡片侧写入口：把写盘失败的原因交回页面，在底部提示行如实显示。 */
internal class SettingsWriter(
    val actions: UiActions,
    private val onFailure: (String?) -> Unit,
) {
    fun commit(change: (LauncherSettings) -> LauncherSettings) {
        onFailure(applySetting(actions, change))
    }
}

/** 行内控件：标题与说明在左，控件在右（行高 44 / 带说明 52）。 */
internal fun settingRow(
    title: String,
    detail: String = "",
    control: @Composable () -> Unit,
) = SettingBlock(title, detail) { SettingRow(title, detail, control) }

/** 竖向控件：标题与说明在上，34dp 控件在下方占满整行。 */
internal fun settingField(
    title: String,
    detail: String = "",
    content: @Composable ColumnScope.() -> Unit,
) = SettingBlock(title, detail) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Text(title, fontSize = 14.sp)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        content()
    }
}

/** 只作为可搜索条目的控件块：不额外画标题（控件自带说明）。 */
internal fun settingControl(
    title: String,
    content: @Composable () -> Unit,
) = SettingBlock(title, "") { content() }

/** 只有一行说明的条目：仍可被搜索命中。 */
internal fun settingNote(text: String) = SettingBlock(text, "") { MutedText(text) }

/**
 * 还原默认配置的取值：设置页负责的选项全部回到出厂值。 游戏目录、游戏目录语义与正版登录身份不属于本页，保持原样；
 * 协议同意记录不是偏好设置，重置不能让已同意的用户重新被首启弹窗拦住；
 * 已导入的字体、背景图片与左上角图标属于文件资产，只重置它们的选项，不删除文件本身。
 */
internal fun defaultSettings(
    current: LauncherSettings,
    maximumMemoryMb: Int,
): LauncherSettings {
    val defaults = LauncherSettings()
    val memory = minOf(defaults.memoryMb, maximumMemoryMb)
    return defaults.copy(
        gameRoot = current.gameRoot,
        microsoftClientId = current.microsoftClientId,
        agreementVersion = current.agreementVersion,
        customFonts = current.customFonts,
        backgroundPath = current.backgroundPath,
        backgroundRevision = current.backgroundRevision,
        brandIconPath = current.brandIconPath,
        brandIconRevision = current.brandIconRevision,
        memoryMb = memory,
        initialMemoryMb = minOf(defaults.initialMemoryMb, memory),
    )
}
