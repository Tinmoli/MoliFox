// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证模组元数据与依赖声明的读取的行为与边界。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.reader
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModMetadataTest {
    @TempDir lateinit var temporary: Path

    private fun jar(
        name: String,
        entries: Map<String, String>,
    ): Path =
        temporary.resolve(name).also { path ->
            ZipOutputStream(path.outputStream()).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    @Test
    fun `fabric and quilt declarations support authors and dependencies`() {
        val reader = ModMetadataReader()
        val fabric =
            reader.read(
                jar(
                    "fabric.jar",
                    mapOf(
                        "fabric.mod.json" to
                            """{"id":"fox","name":"茉狐","version":"1.2","authors":["Alice",{"name":"Bob"}],"depends":{"minecraft":"1.20.1"}}""",
                    ),
                ),
            )
        assertEquals("茉狐", fabric.title)
        assertEquals("Alice, Bob", fabric.entries.single().authors)
        assertTrue(
            fabric.entries
                .single()
                .dependencies
                .single()
                .contains("minecraft"),
        )
        val quilt =
            reader.read(
                jar(
                    "quilt.jar",
                    mapOf(
                        "quilt.mod.json" to
                            """{"quilt_loader":{"id":"fox","version":"2","metadata":{"name":"Quilt Fox","contributors":{"C":"Developer"}},"depends":[{"id":"quilt_loader"}]}}""",
                    ),
                ),
            )
        assertEquals("Quilt", quilt.loader)
        assertEquals("C", quilt.entries.single().authors)
        assertEquals(listOf("quilt_loader"), quilt.entries.single().dependencies)
    }

    @Test
    fun `forge multi mod TOML and jar version are parsed without executing code`() {
        val result =
            ModMetadataReader()
                .read(
                    jar(
                        "forge.jar.disabled",
                        mapOf(
                            "META-INF/neoforge.mods.toml" to
                                """
                                modLoader="javafml"
                                [[mods]]
                                modId="fox"
                                displayName="Fox #1"
                                version='${'$'}{file.jarVersion}'
                                description='''A multi
                                line description'''
                                [[mods]]
                                modId="core"
                                version="2.0"
                                [[dependencies.fox]]
                                modId="minecraft"
                                versionRange="[1.20,1.21)"
                                """.trimIndent(),
                            "META-INF/MANIFEST.MF" to
                                "Manifest-Version: 1.0\nImplementation-Version: 3.4\n\n",
                        ),
                    ),
                )
        assertEquals("NeoForge", result.loader)
        assertEquals(2, result.entries.size)
        assertEquals("3.4", result.entries.first().version)
        assertTrue(
            result.entries
                .first()
                .description
                .contains("line description"),
        )
        assertEquals(listOf("minecraft [1.20,1.21)"), result.entries.first().dependencies)
    }

    @Test
    fun `legacy malformed and oversized declarations fail per file and cache refreshes`() {
        val reader = ModMetadataReader()
        assertEquals(
            "Old Fox",
            reader
                .read(
                    jar(
                        "old.jar",
                        mapOf(
                            "mcmod.info" to """[{"modid":"old","name":"Old Fox","version":"1"}]""",
                        ),
                    ),
                ).title,
        )
        val bad = jar("bad.jar", mapOf("fabric.mod.json" to "{bad"))
        assertTrue(reader.read(bad).warning.isNotBlank())
        jar("bad.jar", mapOf("fabric.mod.json" to """{"id":"fixed","version":"100"}"""))
        assertEquals("fixed", reader.read(bad).title)
        assertTrue(
            reader
                .read(jar("large.jar", mapOf("fabric.mod.json" to "x".repeat(2 * 1024 * 1024 + 1))))
                .warning
                .isNotBlank(),
        )
        assertTrue(
            reader
                .read(temporary.resolve("broken.jar").apply { writeText("not a jar") })
                .warning
                .isNotBlank(),
        )
    }
}
