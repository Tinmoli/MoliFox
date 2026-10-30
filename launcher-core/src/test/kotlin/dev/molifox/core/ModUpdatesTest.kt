// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证模组更新识别、版本比较与安全替换的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModUpdatesTest : ModUpdatesTestSupport() {
    @Test
    fun `version comparison handles numeric segments and prereleases`() {
        assertTrue(compareModVersions("1.2", "1.10") < 0)
        assertEquals(0, compareModVersions("1.2.0", "1.2"))
        assertEquals(0, compareModVersions("v1.3", "1.3.0"))
        assertEquals(0, compareModVersions("1.2.0+build.5", "1.2.0"))
        assertTrue(compareModVersions("2.0.0-beta", "2.0.0") < 0)
        assertTrue(compareModVersions("2.0.0-beta.2", "2.0.0-beta.10") < 0)
        assertTrue(compareModVersions("1.0.0-rc.1", "1.0.0-beta.9") > 0)
        assertTrue(compareModVersions("1.0.0-alpha", "1.0.0-1") > 0)
        assertTrue(isPrereleaseVersion("1.0.0-beta.2"))
        assertFalse(isPrereleaseVersion("1.0.0+build-1"))
        assertTrue(minecraftConstraintMatches(">=1.20 <1.21", "1.20.1"))
        assertFalse(minecraftConstraintMatches(">=1.20 <1.21", "1.21"))
        assertTrue(minecraftConstraintMatches("[1.20,1.21)", "1.20.1"))
        assertFalse(minecraftConstraintMatches("[1.20,1.21)", "1.21"))
        assertTrue(minecraftConstraintMatches("1.20.x", "1.20.4"))
        assertFalse(minecraftConstraintMatches("1.20.x", "1.21"))
        // CurseForge 的 displayName 混有项目名/游戏版本，比较前要抽出真正的版本段。
        assertEquals("0.92.3+1.20.1", normalizedRemoteVersionNumber("Fabric API 0.92.3+1.20.1"))
        assertEquals("15.2.0.27", normalizedRemoteVersionNumber("JEI 15.2.0.27 for 1.20.1"))
        assertEquals("v11.1.118", normalizedRemoteVersionNumber("Cloth Config v11.1.118"))
    }

    @Test
    fun `metadata constraints and project references parse for all three formats`() {
        val reader = ModMetadataReader()
        assertFabricMetadata(reader)
        assertForgeMetadata(reader)
        assertLegacyMetadata(reader)
    }

    private fun assertFabricMetadata(reader: ModMetadataReader) {
        val fabric =
            reader.read(
                jar(
                    temporary.resolve("fabric.jar"),
                    mapOf(
                        "fabric.mod.json" to
                            """
                            {
                              "id": "fox",
                              "name": "Fox",
                              "version": "1.2.0",
                              "depends": {"minecraft": ">=1.20 <1.21"},
                              "contact": {"homepage": "https://modrinth.com/mod/fox"}
                            }
                            """,
                    ),
                ),
            )
        assertEquals("Fabric", fabric.loader)
        assertEquals(listOf("fabric"), fabric.loaderKeys)
        assertEquals(">=1.20 <1.21", fabric.entries.single().minecraftConstraint)
        assertEquals(
            listOf(ModProjectRef("modrinth", "fox")),
            fabric.entries.single().projectRefs,
        )
    }

    private fun assertForgeMetadata(reader: ModMetadataReader) {
        val forge =
            reader.read(
                jar(
                    temporary.resolve("forge.jar"),
                    mapOf(
                        "META-INF/mods.toml" to
                            """
                            modLoader="javafml"
                            [[mods]]
                            modId="forgefox"
                            displayName="Forge Fox"
                            version="2.0"
                            displayURL="https://www.curseforge.com/minecraft/mc-mods/forge-fox"
                            [[dependencies.forgefox]]
                            modId="minecraft"
                            versionRange="[1.20,1.21)"
                            """.trimIndent(),
                    ),
                ),
            )
        assertEquals("Forge", forge.loader)
        assertEquals("[1.20,1.21)", forge.entries.single().minecraftConstraint)
        assertEquals(
            listOf(ModProjectRef("curseforge", "forge-fox")),
            forge.entries.single().projectRefs,
        )
    }

    private fun assertLegacyMetadata(reader: ModMetadataReader) {
        val legacy =
            reader.read(
                jar(
                    temporary.resolve("legacy.jar"),
                    mapOf(
                        "mcmod.info" to
                            """
                            [
                              {
                                "modid": "oldfox",
                                "name": "Old Fox",
                                "version": "1",
                                "mcversion": "1.20.1",
                                "url": "https://www.curseforge.com/minecraft/mc-mods/old-fox"
                              }
                            ]
                            """,
                    ),
                ),
            )
        assertEquals("1.20.1", legacy.entries.single().minecraftConstraint)
        assertEquals(
            listOf(ModProjectRef("curseforge", "old-fox")),
            legacy.entries.single().projectRefs,
        )
    }

    @Test
    fun `fingerprint match wins over name search`() =
        runBlocking {
            val (store, instance, root) = fixture()
            writeFoxJar(root.resolve("mods/fox.jar"))
            val matched = project("matched", "Fox")
            val named = project("named", "Fox")
            val current = version(matched, "1.0.0", jarBytes("1.0.0"))
            val newer = version(matched, "1.1.0", jarBytes("1.1.0"))
            val source =
                FakeSource(
                    "modrinth",
                    fingerprints = mapOf("fox.jar" to current),
                    searchHits = listOf(named),
                    versionMap =
                        mapOf(
                            matched.id to listOf(current, newer),
                            named.id to listOf(version(named, "2.0.0", jarBytes("2.0.0"))),
                        ),
                )
            val service =
                ModUpdateService(
                    store,
                    ModMetadataReader(),
                    listOf(source),
                    { _, target, _ -> target.writeBytes(jarBytes("1.1.0")) },
                )
            val item = service.check(instance.id).items.single()
            assertEquals(ModMatchSource.Fingerprint, item.matchSource)
            assertEquals("1.1.0", item.candidate?.versionNumber)
            assertEquals(matched.id, item.candidate?.projectId)
            assertTrue(source.searchCalls.isEmpty())
        }

    @Test
    fun `only same game version and loader versions are offered`() =
        runBlocking {
            val (store, instance, root) = fixture()
            writeFoxJar(root.resolve("mods/fox.jar"))
            val remote = project("fox")
            val current = version(remote, "1.0.0", jarBytes("1.0.0"))
            val wrongGame =
                version(remote, "2.0.0", jarBytes("2.0.0"), gameVersions = listOf("1.21"))
            val wrongLoader =
                version(remote, "3.0.0", jarBytes("3.0.0"), loaders = listOf("forge"))
            assertIncompatibleVersionsSkipped(
                store,
                instance,
                remote,
                current,
                wrongGame,
                wrongLoader,
            )
            assertCompatibleVersionOffered(store, instance, remote, current, wrongGame, wrongLoader)
        }

    private suspend fun assertIncompatibleVersionsSkipped(
        store: LauncherStore,
        instance: GameInstance,
        remote: RemoteModProject,
        current: RemoteModVersion,
        wrongGame: RemoteModVersion,
        wrongLoader: RemoteModVersion,
    ) {
        val source =
            FakeSource(
                "modrinth",
                fingerprints = mapOf("fox.jar" to current),
                versionMap = mapOf(remote.id to listOf(current, wrongGame, wrongLoader)),
            )
        val service =
            ModUpdateService(
                store,
                ModMetadataReader(),
                listOf(source),
                { _, target, _ -> target.writeBytes(jarBytes("9.9.9")) },
            )
        val item = service.check(instance.id).items.single()
        assertEquals(ModUpdateStatus.Latest, item.status)
        assertNull(item.candidate)
    }

    private suspend fun assertCompatibleVersionOffered(
        store: LauncherStore,
        instance: GameInstance,
        remote: RemoteModProject,
        current: RemoteModVersion,
        wrongGame: RemoteModVersion,
        wrongLoader: RemoteModVersion,
    ) {
        val compatible = version(remote, "1.1.0", jarBytes("1.1.0"))
        val updatedSource =
            FakeSource(
                "modrinth",
                fingerprints = mapOf("fox.jar" to current),
                versionMap =
                    mapOf(
                        remote.id to listOf(current, wrongGame, wrongLoader, compatible),
                    ),
            )
        val updated =
            ModUpdateService(
                store,
                ModMetadataReader(),
                listOf(updatedSource),
                { _, target, _ -> target.writeBytes(jarBytes("1.1.0")) },
            )
        assertEquals(
            "1.1.0",
            updated
                .check(instance.id)
                .items
                .single()
                .candidate
                ?.versionNumber,
        )
    }

    @Test
    fun `ambiguous name match is flagged and not applied`() =
        runBlocking {
            val (store, instance, root) = fixture()
            val old = writeFoxJar(root.resolve("mods/fox.jar"))
            val original = old.readBytes()
            val first = project("first", "Fox")
            val second = project("second", "Fox")
            val source =
                FakeSource(
                    "modrinth",
                    searchHits = listOf(first, second),
                    versionMap =
                        mapOf(
                            first.id to listOf(version(first, "2.0.0", jarBytes("2.0.0"))),
                            second.id to listOf(version(second, "2.0.0", jarBytes("2.0.0"))),
                        ),
                )
            val service =
                ModUpdateService(
                    store,
                    ModMetadataReader(),
                    listOf(source),
                    { _, target, _ -> target.writeBytes(jarBytes("2.0.0")) },
                )
            val item = service.check(instance.id).items.single()
            assertEquals(ModUpdateStatus.Unknown, item.status)
            assertTrue(item.possibleMatch)
            assertTrue(item.warning.contains("可能匹配"))
            assertNull(item.candidate)
            assertFalse(item.applicable)
            assertTrue(runCatching { service.apply(instance.id, item, null) }.isFailure)
            assertContentEquals(original, old.readBytes())
        }

    @Test
    fun `unique name match is possible and explicitly applicable`() =
        runBlocking {
            val (store, instance, root) = fixture()
            writeFoxJar(root.resolve("mods/fox.jar"))
            val remote = project("fox", "Fox")
            val latest = version(remote, "1.1.0", jarBytes("1.1.0"))
            val source =
                FakeSource(
                    "modrinth",
                    searchHits = listOf(remote),
                    versionMap = mapOf(remote.id to listOf(latest)),
                )
            val service =
                ModUpdateService(
                    store,
                    ModMetadataReader(),
                    listOf(source),
                    { _, target, _ -> target.writeBytes(jarBytes("1.1.0")) },
                )
            val item = service.check(instance.id).items.single()
            assertEquals(ModUpdateStatus.Updatable, item.status)
            assertEquals(ModMatchSource.Name, item.matchSource)
            assertTrue(item.possibleMatch)
            assertTrue(item.applicable)
            assertTrue(item.warning.contains("可能不是同一项目"))
        }
}
