// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责加载语言资源、缓存语言目录并提供全局翻译入口。

package dev.molifox.core.i18n

import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局翻译入口。
 *
 * Compose 界面通过 `LocalStrings` 使用当前语言并可随切换重组；业务层（下载、安装、联机、日志）直接调用 [t]。 语言切换只改内存状态，写入配置由设置页负责。
 */
object I18n {
    /**
     * 资源按功能拆成多个文件，避免多人同时改同一个 properties 造成冲突，也让每个批次只动自己的文件。
     *
     * 新增 bundle 时同步修改 `gradle/i18n.gradle.kts` 里的同名列表与资源校验测试。
     */
    internal val BUNDLES: List<String> =
        listOf(
            "app",
            "settings",
            "pages",
            "dialogs",
            "core",
            "multiplayer",
            "bootstrap",
            "desktop",
            "logs",
            "packs",
            "network",
            "diagnostics",
            "save",
            "instance",
            "content",
            "misc",
        )

    /** 除简体中文外，每种语言都要能回退到源语言，避免漏翻时显示空串。 */
    private fun fallbackTagOf(tag: String): String? =
        when (tag) {
            "zh-CN" -> null
            "zh-TW" -> "zh-CN"
            "zh-HK" -> "zh-TW"
            else -> "zh-CN"
        }

    private val catalogs = ConcurrentHashMap<String, Strings>()
    private val loadLock = Any()

    @Volatile
    private var active: Strings = catalog("zh-CN")

    /** 当前生效的文案集合。 */
    val current: Strings get() = active

    /** 当前实际使用的语言标签，例如 `zh-TW`。 */
    val currentTag: String get() = active.languageTag

    /** 按设置值切换语言并返回新的文案集合；设置写入由调用方负责。 */
    fun setLanguage(settingValue: String): Strings {
        val strings = catalog(AppLanguage.resolveTag(settingValue))
        active = strings
        return strings
    }

    /** 业务层翻译：参数按 `{0}` / `{1}` 顺序填入。 */
    fun t(
        key: String,
        vararg args: Any?,
    ): String = active.t(key, *args)

    /** 当前语言（含回退）是否有这条 key。 */
    fun has(key: String): Boolean = active.has(key)

    /** 加载一种语言；结果按语言缓存，切换语言不重复读文件。 */
    fun catalog(tag: String): Strings =
        catalogs[tag] ?: synchronized(loadLock) {
            catalogs[tag] ?: run {
                val fallback = fallbackTagOf(tag)?.let(::catalog)
                Strings(tag, fallback, loadValues(tag)).also { catalogs[tag] = it }
            }
        }

    /** 设置页与校验脚本使用的可选语言标签。 */
    fun availableTags(): List<String> = AppLanguage.entries.filter { it != AppLanguage.AUTO }.map { it.settingValue }

    private fun loadValues(tag: String): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        for (bundle in BUNDLES) {
            val resource = "/i18n/$tag/$bundle.properties"
            val stream = Strings::class.java.getResourceAsStream(resource) ?: continue
            val properties = Properties()
            InputStreamReader(stream, StandardCharsets.UTF_8).use(properties::load)
            for ((key, value) in properties) {
                merged[key as String] = value as String
            }
        }
        return merged
    }
}
