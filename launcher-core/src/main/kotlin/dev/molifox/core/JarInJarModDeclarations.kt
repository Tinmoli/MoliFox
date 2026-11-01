// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责单个 JAR 的模组元数据模型与按加载器分派；解析细节分散在 Fabric/Forge 两个文件。

package dev.molifox.core

import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
import java.util.zip.ZipFile

/** 一个 JAR 里声明的单个 mod；Forge 多 mod JAR 会有多条。 */
internal data class JarModDeclaration(
    val id: String,
    val name: String,
    val version: String,
    val targetMc: String = "",
    val dependencies: List<JarInJarRequirement> = emptyList(),
    val conflicts: List<JarInJarConflict> = emptyList(),
    /** 该 mod 额外声明的 id（Fabric/Quilt `provides`）。 */
    val provides: List<JarInJarProvided> = emptyList(),
)

internal data class JarModReadResult(
    val mods: List<JarModDeclaration> = emptyList(),
    val complete: Boolean = true,
    val note: String = "",
)

internal val emptyModRead = JarModReadResult()

/** 只读取声明内容，不加载类、不解压文件、不执行构建表达式。 */
internal object JarInJarModDeclarations {
    fun read(
        zip: ZipFile,
        loader: JarInJarLoader,
    ): JarModReadResult =
        when (loader) {
            JarInJarLoader.Fabric -> guarded { JarInJarFabricModDeclarations.fabric(zip) }
            JarInJarLoader.Quilt -> guarded { JarInJarFabricModDeclarations.quilt(zip) }
            JarInJarLoader.NeoForge ->
                guarded {
                    JarInJarForgeModDeclarations.toml(zip, preferNeoForge = true)
                        ?: emptyModRead
                }
            JarInJarLoader.Forge ->
                guarded {
                    JarInJarForgeModDeclarations.toml(zip, preferNeoForge = false)
                        ?: JarInJarForgeModDeclarations.legacy(zip)
                }
            JarInJarLoader.Legacy, JarInJarLoader.Unknown ->
                guarded { JarInJarForgeModDeclarations.legacy(zip) }
        }

    private fun guarded(block: () -> JarModReadResult): JarModReadResult =
        try {
            block()
        } catch (failure: JsonParseException) {
            parseFailure(I18n.t("diagA2.jarInJarModDeclarations.parseFailed"), failure)
        } catch (failure: IllegalStateException) {
            parseFailure(I18n.t("diagA2.jarInJarModDeclarations.unrecognizedStructure"), failure)
        } catch (failure: IllegalArgumentException) {
            parseFailure(I18n.t("diagA2.jarInJarModDeclarations.unreadable"), failure)
        }

    private fun parseFailure(
        summary: String,
        failure: Exception,
    ): JarModReadResult = JarModReadResult(complete = false, note = describeFailure(summary, failure))
}
