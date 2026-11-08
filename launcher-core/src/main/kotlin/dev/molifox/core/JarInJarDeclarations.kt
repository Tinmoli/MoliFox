// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责探测 JAR 的加载器并收集四类内嵌依赖清单；只读 zip 条目，不解压到磁盘。

package dev.molifox.core

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import dev.molifox.core.i18n.I18n
import java.util.zip.ZipFile

internal const val FABRIC_MOD = "fabric.mod.json"
internal const val QUILT_MOD = "quilt.mod.json"
internal const val NEOFORGE_TOML = "META-INF/neoforge.mods.toml"
internal const val FORGE_TOML = "META-INF/mods.toml"
internal const val LEGACY_INFO = "mcmod.info"
internal const val MANIFEST = "META-INF/MANIFEST.MF"
private const val JARJAR_METADATA = "META-INF/jarjar/metadata.json"

/** 内嵌路径只是 zip 条目名，超过这个长度按异常输入拒绝。 */
private const val MAX_EMBEDDED_PATH_LENGTH = 4096

/** 清单里声明的一条内嵌 JAR；Forge JarJar 额外带 Maven 坐标。 */
internal data class EmbeddedDeclaration(
    val path: String,
    val identifier: String = "",
    val versionRange: String = "",
    val artifactVersion: String = "",
)

/** 一个 JAR 的完整声明：加载器方言、模组元数据与内嵌清单。 */
internal data class JarDeclaration(
    val loader: JarInJarLoader,
    val mods: List<JarModDeclaration> = emptyList(),
    val embedded: List<EmbeddedDeclaration> = emptyList(),
    val complete: Boolean = true,
    val note: String = "",
)

/** 只读取声明内容，不加载类、不解压文件、不执行构建表达式。 */
internal object JarInJarDeclarations {
    /** 解析顺序固定：加载器探测 → 模组元数据 → 四类内嵌清单（同时收集，不按加载器二选一）。 */
    fun read(
        zip: ZipFile,
        preferred: JarInJarLoader,
    ): JarDeclaration {
        val loader = detectLoader(zip, preferred)
        val embedded = collectEmbedded(zip)
        val mods = JarInJarModDeclarations.read(zip, loader)
        val notes = listOf(embedded.note, mods.note).filter { it.isNotBlank() }
        return JarDeclaration(
            loader = loader,
            mods = mods.mods,
            embedded = embedded.declarations,
            complete = embedded.complete && mods.complete,
            note = notes.joinToString("；"),
        )
    }

    /**
     * 判断 JAR 声明属于哪个加载器。
     *
     * 混合清单优先用实例加载器消歧；没有匹配时固定顺序 Fabric → Quilt → NeoForge → Forge。
     */
    fun detectLoader(
        zip: ZipFile,
        preferred: JarInJarLoader,
    ): JarInJarLoader {
        val hasFabric = zip.getEntry(FABRIC_MOD) != null
        val hasQuilt = zip.getEntry(QUILT_MOD) != null
        val hasNeoForge = zip.getEntry(NEOFORGE_TOML) != null
        val hasForge = zip.getEntry(FORGE_TOML) != null || zip.getEntry(LEGACY_INFO) != null
        val preferredMatches =
            when (preferred) {
                JarInJarLoader.Fabric -> hasFabric
                JarInJarLoader.Quilt -> hasQuilt
                JarInJarLoader.NeoForge -> hasNeoForge
                JarInJarLoader.Forge, JarInJarLoader.Legacy -> hasForge
                JarInJarLoader.Unknown -> false
            }
        if (preferredMatches) return preferred
        return when {
            hasFabric -> JarInJarLoader.Fabric
            hasQuilt -> JarInJarLoader.Quilt
            hasNeoForge -> JarInJarLoader.NeoForge
            hasForge -> JarInJarLoader.Forge
            else -> JarInJarLoader.Unknown
        }
    }

    private fun collectEmbedded(zip: ZipFile): EmbeddedCollection {
        val sources =
            listOf(
                guarded(FABRIC_MOD) { fabricEmbedded(zip) },
                guarded(QUILT_MOD) { quiltEmbedded(zip) },
                guarded(JARJAR_METADATA) { jarJarEmbedded(zip) },
                guarded(MANIFEST) { manifestEmbedded(zip) },
            )
        val notes = sources.mapNotNull { it.failure.takeIf(String::isNotBlank) }.toMutableList()
        val merged = merge(sources.flatMap { it.declarations })
        val safe = merged.filter { isSafeEmbeddedPath(it.path) }
        if (safe.size != merged.size) {
            notes += I18n.t("diagA1.jarInJar.unsafeEmbeddedPath")
        }
        return EmbeddedCollection(
            declarations = safe,
            complete = notes.isEmpty(),
            note = notes.joinToString("；"),
        )
    }

    /** 同一路径被多种格式声明时优先带 identifier 的条目，否则按收集顺序取第一个。 */
    private fun merge(declarations: List<EmbeddedDeclaration>): List<EmbeddedDeclaration> =
        declarations
            .filter { it.path.isNotBlank() }
            .groupBy { it.path }
            .values
            .map { group -> group.firstOrNull { it.identifier.isNotBlank() } ?: group.first() }

    private fun fabricEmbedded(zip: ZipFile): List<EmbeddedDeclaration> {
        val text = zip.declarationText(FABRIC_MOD) ?: return emptyList()
        val jars = JsonParser.parseString(text).asJsonObject.array("jars")
        return jars.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            item.string("file").takeIf { it.isNotBlank() }?.let { EmbeddedDeclaration(it) }
        }
    }

    private fun quiltEmbedded(zip: ZipFile): List<EmbeddedDeclaration> {
        val text = zip.declarationText(QUILT_MOD) ?: return emptyList()
        val jars =
            JsonParser
                .parseString(text)
                .asJsonObject
                .obj("quilt_loader")
                .array("jars")
        return jars.mapNotNull { element ->
            if (!element.isJsonPrimitive) return@mapNotNull null
            element.asString.takeIf { it.isNotBlank() }?.let { EmbeddedDeclaration(it) }
        }
    }

    private fun jarJarEmbedded(zip: ZipFile): List<EmbeddedDeclaration> {
        val text = zip.declarationText(JARJAR_METADATA) ?: return emptyList()
        val jars = JsonParser.parseString(text).asJsonObject.array("jars")
        return jars.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val path = item.string("path").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val identifier = item.obj("identifier")
            val group = identifier.string("group").trim()
            val artifact = identifier.string("artifact").trim()
            val version = item.obj("version")
            EmbeddedDeclaration(
                path = path,
                identifier =
                    if (group.isNotEmpty() && artifact.isNotEmpty()) {
                        "$group:$artifact"
                    } else {
                        ""
                    },
                versionRange = version.string("range"),
                artifactVersion = version.string("artifactVersion"),
            )
        }
    }

    /** MANIFEST 头 `Embedded-Dependencies-Mod`：先按续行折叠（行首空格拼到上一行），只取第一个命中头。 */
    private fun manifestEmbedded(zip: ZipFile): List<EmbeddedDeclaration> {
        val text = zip.declarationText(MANIFEST) ?: return emptyList()
        val folded = mutableListOf<String>()
        text.split('\n').map { it.trimEnd('\r') }.forEach { line ->
            if (line.startsWith(" ") && folded.isNotEmpty()) {
                folded[folded.lastIndex] = folded.last() + line.substring(1)
            } else {
                folded += line
            }
        }
        val header =
            folded.firstOrNull {
                it.startsWith("Embedded-Dependencies-Mod:", ignoreCase = true)
            } ?: return emptyList()
        val value = header.substringAfter(':').trim()
        return if (value.isEmpty()) emptyList() else listOf(EmbeddedDeclaration(value))
    }

    private fun guarded(
        source: String,
        block: () -> List<EmbeddedDeclaration>,
    ): EmbeddedSource =
        try {
            EmbeddedSource(block())
        } catch (failure: JsonParseException) {
            EmbeddedSource(failure = I18n.t("diagA1.jarInJar.parseFailed", source))
        } catch (failure: IllegalStateException) {
            EmbeddedSource(failure = I18n.t("diagA1.jarInJar.unrecognizedStructure", source))
        } catch (failure: IllegalArgumentException) {
            EmbeddedSource(failure = I18n.t("diagA1.jarInJar.unreadable", source))
        }
}

/** 收集单个清单的结果；失败时保留原因，供报告标记「不完整」。 */
private class EmbeddedSource(
    val declarations: List<EmbeddedDeclaration> = emptyList(),
    val failure: String = "",
)

private class EmbeddedCollection(
    val declarations: List<EmbeddedDeclaration>,
    val complete: Boolean,
    val note: String,
)

/** 保留中文结论，同时把底层异常信息并入 note，避免异常被静默丢弃。 */
internal fun describeFailure(
    summary: String,
    failure: Exception,
): String {
    val detail = failure.message?.takeIf(String::isNotBlank) ?: return summary
    return "$summary（$detail）"
}

/**
 * 内嵌路径是否可安全用作 zip 条目名。
 *
 * 这里的目标根是「zip 虚拟根」而不是文件系统：路径永远只交给 [ZipFile.getEntry]，但仍然拒绝
 * 穿越、盘符、反斜杠与 Windows 保留名，避免将来被误用成落盘路径。
 */
internal fun isSafeEmbeddedPath(path: String): Boolean {
    val unsafeCharacters =
        path.isBlank() ||
            path.length >= MAX_EMBEDDED_PATH_LENGTH ||
            path.any { it == '\u0000' || it == ':' || it == '\\' } ||
            path.startsWith('/')
    return !unsafeCharacters &&
        path.split('/').none { segment ->
            segment.isEmpty() ||
                segment == "." ||
                segment == ".." ||
                segment.endsWith('.') ||
                segment.endsWith(' ') ||
                isWindowsReservedFileName(segment)
        }
}
