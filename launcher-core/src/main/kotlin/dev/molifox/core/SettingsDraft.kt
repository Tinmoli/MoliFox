// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责设置草稿与已保存配置的合并。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Keep unrelated live changes (for example appearance) when saving a settings form. */
fun mergeSettingsDraft(
    base: LauncherSettings,
    edited: LauncherSettings,
    current: LauncherSettings,
): LauncherSettings =
    gson.fromJson(
        mergeSettingsJson(gson.toJsonTree(base), gson.toJsonTree(edited), gson.toJsonTree(current)),
        LauncherSettings::class.java,
    )

private fun mergeSettingsJson(
    before: JsonElement?,
    change: JsonElement?,
    latest: JsonElement?,
): JsonElement? {
    if (before == change) return latest?.deepCopy()
    if (before is JsonObject && change is JsonObject && latest is JsonObject) {
        return mergeSettingsObject(before, change, latest)
    }
    return change?.deepCopy()
}

private fun mergeSettingsObject(
    before: JsonObject,
    change: JsonObject,
    latest: JsonObject,
): JsonObject =
    latest.deepCopy().apply {
        (before.keySet() + change.keySet()).forEach { key ->
            val value = mergeSettingsJson(before.get(key), change.get(key), latest.get(key))
            if (value == null) remove(key) else add(key, value)
        }
    }
