// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证语言资源的 key 对齐、占位符一致、回退与语言解析。

package dev.molifox.core.i18n

import dev.molifox.core.gameLanguageCode
import dev.molifox.core.legacyGameLanguageCode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class I18nTest {
    private val placeholder = Regex("""\{(\d+)\}""")

    @Test
    fun `english and taiwanese resources cover every simplified key with same placeholders`() {
        val simplified = I18n.catalog("zh-CN")
        val simplifiedKeys = simplified.ownKeys()
        assertTrue(simplifiedKeys.size >= 40, "语言资源太少，可能没有加载到：${simplifiedKeys.size}")
        for (tag in listOf("en-US", "zh-TW")) {
            val translated = I18n.catalog(tag)
            val missing = simplifiedKeys - translated.ownKeys()
            assertTrue(missing.isEmpty(), "$tag 缺少 ${missing.size} 个 key：${missing.take(5)}")
            val extra = translated.ownKeys() - simplifiedKeys
            assertTrue(extra.isEmpty(), "$tag 多了 ${extra.size} 个 key：${extra.take(5)}")
            for (key in simplifiedKeys) {
                val expected = placeholder.findAll(simplified.ownValues().getValue(key)).map { it.value }.toSet()
                val actual = placeholder.findAll(translated.ownValues().getValue(key)).map { it.value }.toSet()
                assertEquals(expected, actual, "$tag 的 $key 占位符不一致")
            }
        }
    }

    @Test
    fun `hong kong overrides stay a subset of taiwanese and fall back to it`() {
        val taiwanese = I18n.catalog("zh-TW")
        val hongKong = I18n.catalog("zh-HK")
        val extra = hongKong.ownKeys() - taiwanese.ownKeys()
        assertTrue(extra.isEmpty(), "zh-HK 只能覆盖 zh-TW 已有的 key：${extra.take(5)}")
        // 空的香港文件也要能回退到台湾文案，而不是显示 {key}。
        assertEquals(taiwanese.t("nav.home"), hongKong.t("nav.home"))
    }

    @Test
    fun `values are non blank and missing keys are marked for tests`() {
        for (tag in I18n.availableTags()) {
            val strings = I18n.catalog(tag)
            strings.ownValues().forEach { (key, value) ->
                assertTrue(value.isNotBlank(), "$tag 的 $key 是空值")
            }
        }
        assertEquals("{unknown.key}", I18n.catalog("zh-CN").t("unknown.key"))
    }

    @Test
    fun `language resolution follows setting then system locale`() {
        assertEquals("zh-CN", AppLanguage.resolveTag("zh-CN"))
        assertEquals("en-US", AppLanguage.resolveTag("en-us"))
        assertEquals("zh-TW", AppLanguage.resolveTag("AUTO", "zh-TW"))
        assertEquals("zh-HK", AppLanguage.resolveTag("auto", "zh_HK"))
        assertEquals("zh-CN", AppLanguage.resolveTag("auto", "zh-Hans-CN"))
        assertEquals("zh-TW", AppLanguage.resolveTag("auto", "zh-Hant-TW"))
        assertEquals("en-US", AppLanguage.resolveTag("auto", "de-DE"))
        // 旧配置里的未知值按跟随系统处理，不把界面变成空白。
        assertEquals(AppLanguage.resolveTag("auto", "zh-CN"), AppLanguage.resolveTag("fr-FR"))
    }

    @Test
    fun `switching language changes global translation and keeps catalogs cached`() {
        val original = I18n.currentTag
        try {
            val english = I18n.setLanguage("en-US")
            assertEquals("en-US", I18n.currentTag)
            assertEquals("Home", english.t("nav.home"))
            assertEquals("Home", I18n.t("nav.home"))
            assertSame(english, I18n.catalog("en-US"))
            val chinese = I18n.setLanguage("zh-CN")
            assertEquals("开始", chinese.t("nav.home"))
            assertSame(chinese, I18n.catalog("zh-CN"))
        } finally {
            I18n.setLanguage(original)
        }
    }

    @Test
    fun `placeholders are replaced in order and untouched when missing`() {
        assertEquals("正在整理「测试」的目录…", I18n.catalog("zh-CN").t("pages.tidy.running", "测试"))
        assertEquals("A {1}", format("A {1}", arrayOf("x")))
    }

    @Test
    fun `trailing separator survives properties loading`() {
        assertTrue(I18n.catalog("en-US").t("settings.unsavedPrefix").endsWith(" "))
        assertFalse(I18n.catalog("zh-CN").t("settings.unsavedPrefix").endsWith(" "))
    }

    @Test
    fun `game language codes follow launcher language`() {
        assertEquals("zh_cn", gameLanguageCode("zh-CN"))
        assertEquals("zh_tw", gameLanguageCode("zh-TW"))
        assertEquals("zh_tw", gameLanguageCode("zh-HK"))
        assertEquals("en_us", gameLanguageCode("en-US"))
        // 1.10 及更早要求区域大写。
        assertEquals("zh_CN", legacyGameLanguageCode("zh-CN"))
        assertEquals("en_US", legacyGameLanguageCode("en-US"))
        assertEquals("zh_TW", legacyGameLanguageCode("zh-TW"))
    }
}
