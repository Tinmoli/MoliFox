// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证四类嵌入声明、加载器探测、去重与不安全路径拒绝。

package dev.molifox.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JarInJarDeclarationsTest {
    @TempDir lateinit var temporary: Path

    private fun <T> read(
        entries: Map<String, ByteArray>,
        preferred: JarInJarLoader,
        block: (JarDeclaration) -> T,
    ): T {
        val jar = writeJar(temporary.resolve("subject.jar"), entries)
        return ZipFile(jar.toFile()).use { zip -> block(JarInJarDeclarations.read(zip, preferred)) }
    }

    private fun readDeclaration(entries: Map<String, ByteArray>): JarDeclaration =
        read(entries, JarInJarLoader.Unknown) { it }

    @Test
    fun `four declaration sources are collected and deduped by path`() {
        val declaration =
            readDeclaration(
                mapOf(
                    "fabric.mod.json" to
                        fabricBytes("hosta", jars = listOf("a.jar", "same.jar")),
                    "quilt.mod.json" to quiltBytes("hosta", jars = listOf("b.jar")),
                    "META-INF/jarjar/metadata.json" to
                        jarJarBytes(
                            listOf(JarJarEntry("same.jar", "g", "a", "[1.0,2.0)", "1.0")),
                        ),
                    "META-INF/MANIFEST.MF" to manifestBytes("manifest.jar"),
                ),
            )
        assertEquals(
            listOf("a.jar", "same.jar", "b.jar", "manifest.jar"),
            declaration.embedded.map { it.path },
        )
        val deduped = declaration.embedded.first { it.path == "same.jar" }
        assertEquals("g:a", deduped.identifier)
        assertEquals("[1.0,2.0)", deduped.versionRange)
        assertTrue(declaration.complete)
    }

    @Test
    fun `preferred loader wins for mixed jars and fallback order is fixed`() {
        val entries =
            mapOf(
                "fabric.mod.json" to fabricBytes("mixed"),
                "quilt.mod.json" to quiltBytes("mixed"),
                "META-INF/neoforge.mods.toml" to
                    """modLoader="javafml"
                    |[[mods]]
                    |modId="mixed"
                    |version="1"
                    """.trimMargin().toByteArray(),
            )
        assertEquals(
            JarInJarLoader.Quilt,
            read(entries, JarInJarLoader.Quilt) { it.loader },
        )
        assertEquals(
            JarInJarLoader.NeoForge,
            read(entries, JarInJarLoader.NeoForge) { it.loader },
        )
        assertEquals(
            JarInJarLoader.Fabric,
            read(entries, JarInJarLoader.Unknown) { it.loader },
        )
    }

    @Test
    fun `manifest continuation lines are folded before reading the header`() {
        val value = "path/to/embedded-library-1.0.jar"
        val declaration =
            readDeclaration(
                mapOf(
                    "META-INF/MANIFEST.MF" to foldedManifestBytes(value, 10),
                    "fabric.mod.json" to fabricBytes("hosta"),
                ),
            )
        assertEquals(listOf(value), declaration.embedded.map { it.path })
    }

    @Test
    fun `unsafe embedded paths are rejected and marked incomplete`() {
        val declaration =
            readDeclaration(
                mapOf(
                    "fabric.mod.json" to
                        fabricBytes(
                            "hosta",
                            jars =
                                listOf(
                                    "../evil.jar",
                                    "/absolute.jar",
                                    "dir\\windows.jar",
                                    "CON.jar",
                                    "nested/../escape.jar",
                                    "ok.jar",
                                ),
                        ),
                ),
            )
        assertEquals(listOf("ok.jar"), declaration.embedded.map { it.path })
        assertFalse(declaration.complete)
    }

    @Test
    fun `malformed declaration is incomplete instead of crashing`() {
        val declaration =
            readDeclaration(mapOf("fabric.mod.json" to "{not-json".toByteArray()))
        assertFalse(declaration.complete)
        assertTrue(declaration.note.isNotBlank())
        assertTrue(declaration.embedded.isEmpty())
    }

    @Test
    fun `fabric text without schema version is not parsed as mod metadata`() {
        val declaration =
            readDeclaration(
                mapOf(
                    "fabric.mod.json" to fabricBytes("hosta", withSchemaVersion = false),
                ),
            )
        assertEquals(JarInJarLoader.Fabric, declaration.loader)
        assertTrue(declaration.mods.isEmpty())
        assertTrue(declaration.complete)
    }

    @Test
    fun `forge toml registers siblings dependencies and conflicts`() {
        val toml = forgeTomlFixture()
        val declaration =
            read(mapOf("META-INF/mods.toml" to toml.toByteArray()), JarInJarLoader.Forge) {
                it
            }
        assertEquals(JarInJarLoader.Forge, declaration.loader)
        assertEquals(listOf("core", "sibling"), declaration.mods.map { it.id })
        val core = declaration.mods.first()
        assertEquals("[1.20,1.21)", core.targetMc)
        assertEquals(listOf("minecraft", "libx"), core.dependencies.map { it.id })
        assertTrue(core.dependencies.first { it.id == "libx" }.optional)
        // 同 JAR 的兄弟 mod 由自己提供，不登记成外部依赖；冲突单独进 conflicts。
        assertTrue(core.dependencies.none { it.id == "sibling" })
        val conflict =
            declaration.mods
                .first()
                .conflicts
                .single()
        assertEquals("enemy", conflict.id)
        assertTrue(conflict.hard)
        assertEquals("core", conflict.declarerId)
        assertTrue(
            declaration.mods[1]
                .dependencies
                .single { it.id == "liby" }
                .optional,
        )
    }

    @Test
    fun `legacy mcmod info required mods carry their version range`() {
        val legacy =
            """
            [{"modid":"old","name":"Old","version":"3","requiredMods":["libz@[1.0,2.0)"]}]
            """.trimIndent()
        val declaration =
            read(mapOf("mcmod.info" to legacy.toByteArray()), JarInJarLoader.Legacy) { it }
        val mod = declaration.mods.single()
        assertEquals("old", mod.id)
        assertEquals("libz", mod.dependencies.single().id)
        assertEquals("[1.0,2.0)", mod.dependencies.single().constraint)
    }

    private fun forgeTomlFixture(): String =
        """
        modLoader="javafml"
        [[mods]]
        modId="core"
        displayName="Core"
        version="1.0"
        [[mods]]
        modId="sibling"
        version="2.0"
        [[dependencies.core]]
        modId="minecraft"
        versionRange="[1.20,1.21)"
        [[dependencies.core]]
        modId="libx"
        versionRange="[1.0,)"
        type="optional"
        [[dependencies.core]]
        modId="enemy"
        versionRange="[2.0,3.0)"
        type="incompatible"
        [[dependencies.core]]
        modId="sibling"
        versionRange="[1.0,)"
        [[dependencies.sibling]]
        modId="liby"
        versionRange="[1.0]"
        mandatory=false
        """.trimIndent()
}
