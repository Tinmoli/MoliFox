// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证 5 态依赖结论、冲突、内嵌树、版本/游戏版本筛选、缓存指纹与取消。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JarInJarAnalysisTest {
    @TempDir lateinit var temporary: Path

    private val modsDirectory: Path get() = temporary.resolve("mods")
    private val stagingDirectory: Path get() = temporary.resolve("staging")

    private fun lib(
        id: String,
        version: String,
        depends: Map<String, String> = emptyMap(),
    ): ByteArray = jarBytes(mapOf("fabric.mod.json" to fabricBytes(id, version, depends)))

    private fun host(
        id: String,
        depends: Map<String, String> = emptyMap(),
        jars: List<String> = emptyList(),
        breaks: Map<String, String> = emptyMap(),
        conflicts: Map<String, String> = emptyMap(),
    ): ByteArray =
        jarBytes(
            mapOf(
                "fabric.mod.json" to
                    fabricBytes(
                        id,
                        depends = depends,
                        jars = jars,
                        breaks = breaks,
                        conflicts = conflicts,
                    ),
            ),
        )

    private fun analyze(
        mods: List<ModFile>,
        loader: LoaderType = LoaderType.Fabric,
        gameVersion: String = "1.20.1",
        analyzer: JarInJarAnalyzer = JarInJarAnalyzer(),
        isCancelled: () -> Boolean = { false },
    ): JarInJarReport =
        runBlocking {
            analyzer.analyze(
                instanceId = "instance",
                gameVersion = gameVersion,
                loader = loader,
                mods = mods,
                modsDirectory = modsDirectory,
                temporaryDirectory = stagingDirectory,
                isCancelled = isCancelled,
            )
        }

    private fun statusOf(
        report: JarInJarReport,
        filename: String,
        dependencyId: String,
    ): JarInJarStatus =
        report.rows
            .first {
                it.hostFilename == filename && it.dependency.id == dependencyId
            }.status

    @Test
    fun `embedded provider reports bundled for the host and installed for the dependent`() {
        val modA =
            createModFile(
                modsDirectory,
                "a.jar",
                host("hosta", depends = mapOf("libb" to "1.0"), jars = listOf("libb.jar")),
                enabled = true,
            )
        writeEmbedded(modA, "libb.jar", lib("libb", "1.0"))
        val modC =
            createModFile(
                modsDirectory,
                "c.jar",
                host("hostc", depends = mapOf("libb" to "1.0")),
                enabled = true,
            )
        val report = analyze(listOf(modA, modC))
        assertEquals(JarInJarStatus.Bundled, statusOf(report, "a.jar", "libb"))
        assertEquals(JarInJarStatus.Installed, statusOf(report, "c.jar", "libb"))
        assertTrue(report.warnings.isEmpty())
        assertEquals(1, report.embeddedCount)
        assertFalse(report.incomplete)
    }

    @Test
    fun `disabled host turns its embedded provider into a disabled warning`() {
        val modA =
            createModFile(
                modsDirectory,
                "a.jar",
                host("hosta", depends = mapOf("libb" to "1.0"), jars = listOf("libb.jar")),
                enabled = false,
            )
        writeEmbedded(modA, "libb.jar", lib("libb", "1.0"))
        val modC =
            createModFile(
                modsDirectory,
                "c.jar",
                host("hostc", depends = mapOf("libb" to "1.0")),
                enabled = true,
            )
        val report = analyze(listOf(modA, modC))
        assertEquals(JarInJarStatus.Disabled, statusOf(report, "c.jar", "libb"))
        val warning = report.warnings.single()
        assertEquals(JarInJarStatus.Disabled, warning.status)
        assertEquals(listOf("1.0"), warning.providerVersions)
        // 宿主自己的内嵌依赖不因禁用而丢失（文件名保留 .disabled 后缀，与 ModFile 一致）。
        assertEquals(JarInJarStatus.Bundled, statusOf(report, "a.jar.disabled", "libb"))
    }

    @Test
    fun `missing dependency warns with search terms`() {
        val modC =
            createModFile(
                modsDirectory,
                "c.jar",
                host("hostc", depends = mapOf("nothere" to "*")),
                enabled = true,
            )
        val report = analyze(listOf(modC))
        assertEquals(JarInJarStatus.Missing, statusOf(report, "c.jar", "nothere"))
        val warning = report.warnings.single()
        assertEquals(JarInJarStatus.Missing, warning.status)
        assertEquals(listOf("nothere"), warning.searchTerms)
        assertTrue(warning.key.startsWith("c.jar|"))
    }

    @Test
    fun `incomplete scan suppresses missing and mismatch warnings`() {
        val broken =
            createModFile(
                modsDirectory,
                "broken.jar",
                "not a jar".toByteArray(),
                enabled = true,
            )
        val modC =
            createModFile(
                modsDirectory,
                "c.jar",
                host("hostc", depends = mapOf("nothere" to "*")),
                enabled = true,
            )
        val report = analyze(listOf(broken, modC))
        assertTrue(report.incomplete)
        assertTrue(report.incompleteNote.isNotBlank())
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun `active conflicts keep hard declaration for the same unordered pair`() {
        val modA =
            createModFile(
                modsDirectory,
                "a.jar",
                host("hosta", breaks = mapOf("hostc" to "*")),
                enabled = true,
            )
        val modC =
            createModFile(
                modsDirectory,
                "c.jar",
                host("hostc", conflicts = mapOf("hosta" to "*")),
                enabled = true,
            )
        val report = analyze(listOf(modA, modC))
        val pair = report.conflicts.single()
        assertTrue(pair.hard)
        assertEquals("a.jar", pair.declarerHost)
        assertEquals("c.jar", pair.targetHost)
        assertEquals("hostc", pair.targetId)
    }

    @Test
    fun `version mismatch reports provider versions`() {
        val modA =
            createModFile(
                modsDirectory,
                "a.jar",
                host("hosta", jars = listOf("libb.jar")),
                enabled = true,
            )
        writeEmbedded(modA, "libb.jar", lib("libb", "1.0"))
        val modC =
            createModFile(
                modsDirectory,
                "c.jar",
                host("hostc", depends = mapOf("libb" to ">=2.0")),
                enabled = true,
            )
        val report = analyze(listOf(modA, modC))
        assertEquals(JarInJarStatus.VersionMismatch, statusOf(report, "c.jar", "libb"))
        val warning = report.warnings.single()
        assertEquals(JarInJarStatus.VersionMismatch, warning.status)
        assertEquals(listOf("1.0"), warning.providerVersions)
    }

    @Test
    fun `nested embedded tree is counted and exposes depth`() {
        val modA =
            createModFile(
                modsDirectory,
                "a.jar",
                host("hosta", jars = listOf("layer3.jar")),
                enabled = true,
            )
        writeEmbedded(modA, "layer3.jar", layeredJar(3))
        val report = analyze(listOf(modA))
        val tree =
            report.hosts
                .single()
                .embedded
                .single()
        assertEquals(3, report.embeddedCount)
        assertEquals(1, tree.depth)
        assertEquals(2, tree.children.single().depth)
        assertEquals(
            3,
            tree.children
                .single()
                .children
                .single()
                .depth,
        )
    }

    @Test
    fun `forge jarjar selects the artifact version satisfying every range`() {
        val modHost =
            createModFile(
                modsDirectory,
                "host.jar",
                jarBytes(mapOf("META-INF/mods.toml" to forgeHostToml().toByteArray())),
                enabled = true,
            )
        writeEmbedded(modHost, "lib-1.jar", forgeLib("1.0", "only-in-1"))
        writeEmbedded(modHost, "lib-2.jar", forgeLib("2.0", "only-in-2"))
        writeEmbedded(
            modHost,
            "META-INF/jarjar/metadata.json",
            jarJarBytes(
                listOf(
                    JarJarEntry("lib-1.jar", "g", "a", "[1.0,2.0)", "1.0"),
                    JarJarEntry("lib-2.jar", "g", "a", "[1.0,2.0)", "2.0"),
                ),
            ),
        )
        val report = analyze(listOf(modHost), loader = LoaderType.Forge)
        assertEquals(listOf("only-in-1"), report.warnings.map { it.dependencyId })
    }

    @Test
    fun `nodes targeting another minecraft version are not treated as loadable`() {
        val nested =
            fabricBytes(
                "nested",
                depends = mapOf("minecraft" to ">=1.21", "nestedmissing" to "*"),
            )
        val modA =
            createModFile(
                modsDirectory,
                "a.jar",
                host("hosta", jars = listOf("nested.jar")),
                enabled = true,
            )
        writeEmbedded(modA, "nested.jar", jarBytes(mapOf("fabric.mod.json" to nested)))
        val report = analyze(listOf(modA), gameVersion = "1.20.1")
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun `replacing a mod file refreshes the cached scan`() {
        val analyzer = JarInJarAnalyzer()
        val first =
            createModFile(
                modsDirectory,
                "a.jar",
                host("hosta", depends = mapOf("libb" to "1.0"), jars = listOf("libb.jar")),
                enabled = true,
            )
        writeEmbedded(first, "libb.jar", lib("libb", "1.0"))
        val initial = analyze(listOf(first), analyzer = analyzer)
        assertEquals(JarInJarStatus.Bundled, statusOf(initial, "a.jar", "libb"))
        val second =
            createModFile(
                modsDirectory,
                "a.jar",
                host("hosta", depends = mapOf("libb" to ">=2.0"), jars = listOf("libb.jar")),
                enabled = true,
            )
        writeEmbedded(second, "libb.jar", lib("libb", "2.0"))
        val refreshed = analyze(listOf(second), analyzer = analyzer)
        assertEquals(JarInJarStatus.Bundled, statusOf(refreshed, "a.jar", "libb"))
    }

    @Test
    fun `cancelled analysis returns incomplete report without scanning hosts`() {
        val modC =
            createModFile(
                modsDirectory,
                "c.jar",
                host("hostc", depends = mapOf("nothere" to "*")),
                enabled = true,
            )
        val report = analyze(listOf(modC), isCancelled = { true })
        assertTrue(report.incomplete)
        assertTrue(report.incompleteNote.contains("取消"))
        assertTrue(report.hosts.isEmpty())
        assertTrue(report.warnings.isEmpty())
    }

    private fun writeEmbedded(
        mod: ModFile,
        entryName: String,
        content: ByteArray,
    ) {
        val path = modsDirectory.resolve(mod.filename)
        val entries = mutableMapOf<String, ByteArray>()
        readEntries(path).forEach { (name, bytes) -> entries[name] = bytes }
        entries[entryName] = content
        path.writeBytes(jarBytes(entries))
    }

    private fun readEntries(path: Path): Map<String, ByteArray> =
        java.util.zip.ZipFile(path.toFile()).use { zip ->
            zip.entries().asSequence().associate { entry ->
                entry.name to zip.getInputStream(entry).use { it.readBytes() }
            }
        }

    private fun forgeHostToml(): String =
        """
        modLoader="javafml"
        [[mods]]
        modId="host"
        version="1"
        """.trimIndent()

    private fun forgeLib(
        version: String,
        dependencyId: String,
    ): ByteArray =
        jarBytes(
            mapOf(
                "META-INF/mods.toml" to
                    """
                    modLoader="javafml"
                    [[mods]]
                    modId="libver"
                    version="$version"
                    [[dependencies.libver]]
                    modId="$dependencyId"
                    versionRange="[1.0,)"
                    """.trimIndent().toByteArray(),
            ),
        )
}
