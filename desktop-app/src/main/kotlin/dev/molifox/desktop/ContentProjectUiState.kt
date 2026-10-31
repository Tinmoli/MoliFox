// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责资源项目对话框的临时界面状态；按项目 id 记忆，切换项目时重置。

package dev.molifox.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.molifox.core.ContentDetails
import dev.molifox.core.ContentFilter
import dev.molifox.core.ContentVersion
import dev.molifox.core.SaveEntry
import java.nio.file.Path

internal class ContentProjectUiState(
    initialFilter: ContentFilter,
    selectedInstanceId: String?,
) {
    var details by mutableStateOf<ContentDetails?>(null)
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var retry by mutableIntStateOf(0)
    var gameVersion by mutableStateOf(initialFilter.gameVersion)
    var loader by mutableStateOf(initialFilter.loader)
    var channel by mutableStateOf("all")
    var versionId by mutableStateOf("")
    var targetId by mutableStateOf(selectedInstanceId.orEmpty())

    /** 用户在下载对话框里确认过的目标文件夹；为空表示沿用实例内的默认目录。 */
    var destination by mutableStateOf<Path?>(null)
    var installVersion by mutableStateOf<ContentVersion?>(null)
    var tab by mutableStateOf("versions")
    var worlds by mutableStateOf(emptyList<SaveEntry>())
    var worldName by mutableStateOf("")
    var worldError by mutableStateOf<String?>(null)
    var confirmDatapack by mutableStateOf(false)
    var confirmWorld by mutableStateOf(false)
    var changelog by mutableStateOf<ContentVersion?>(null)
    var changelogError by mutableStateOf<String?>(null)
    var changelogLoading by mutableStateOf(false)
    var copied by mutableStateOf(false)

    /** 列表筛选是否被用户改过：空结果时的「显示全部版本」用来恢复默认筛选。 */
    fun resetFilters() {
        gameVersion = ""
        loader = null
        channel = "all"
    }
}
