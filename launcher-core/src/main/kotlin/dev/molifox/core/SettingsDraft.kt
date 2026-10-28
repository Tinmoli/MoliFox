// 负责设置草稿与已保存配置的合并。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Keep unrelated live changes (for example appearance) when saving a settings form. */
fun mergeSettingsDraft(
    base: LauncherSettings,
    edited: LauncherSettings,
    current: LauncherSettings,
): LauncherSettings {
    fun merge(before: JsonElement?, change: JsonElement?, latest: JsonElement?): JsonElement? {
        if (before == change) return latest?.deepCopy()
        if (before is JsonObject && change is JsonObject && latest is JsonObject) {
            return latest.deepCopy().apply {
                (before.keySet() + change.keySet()).forEach { key ->
                    val value = merge(before.get(key), change.get(key), latest.get(key))
                    if (value == null) remove(key) else add(key, value)
                }
            }
        }
        return change?.deepCopy()
    }
    return gson.fromJson(
        merge(gson.toJsonTree(base), gson.toJsonTree(edited), gson.toJsonTree(current)),
        LauncherSettings::class.java,
    )
}
