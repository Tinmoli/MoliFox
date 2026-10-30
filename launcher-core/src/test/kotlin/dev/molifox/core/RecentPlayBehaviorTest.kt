// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证直达启动参数、真实进入会话、隐藏持久化及服务器列表删除的数据保护。

package dev.molifox.core

import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isHidden
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecentPlayBehaviorTest {
    @TempDir lateinit var directory: Path

    private fun store(): Pair<LauncherStore, GameInstance> {
        val store = LauncherStore(DesktopPlatform(), directory.resolve("profile"))
        val instance =
            GameInstance(
                name = "Recent",
                gameVersion = "26.3",
                versionId = "recent",
                installed = true,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        store.gamePath(instance).createDirectories()
        return store to instance
    }

    @Test
    fun `hidden entries survive restart without fabricating a visit or changing files`() {
        val (store, instance) = store()
        val usage = store.recentUsage
        usage.setHidden(EntryKind.Server, instance.id, "example.test", true)
        assertNull(usage.lastUsed(EntryKind.Server, instance.id, "example.test"))
        assertTrue(usage.recent(EntryKind.Server, instance.id, 10).isEmpty())
        assertTrue(RecentUsage(store).isHidden(EntryKind.Server, instance.id, "example.test"))
        repeat(110) { usage.record(EntryKind.Server, instance.id, "other-$it.test") }
        assertTrue(usage.isHidden(EntryKind.Server, instance.id, "example.test"))
        usage.record(EntryKind.Server, instance.id, "example.test")
        assertTrue(usage.isHidden(EntryKind.Server, instance.id, "example.test"))
        usage.setHidden(EntryKind.Server, instance.id, "example.test", false)
        assertFalse(usage.isHidden(EntryKind.Server, instance.id, "example.test"))
        assertFalse(store.gamePath(instance).resolve("servers.dat").exists())
    }

    @Test
    fun `parallel sessions accumulate separately and missing leave events are marked estimated`() {
        val (store, instance) = store()
        var now = Instant.parse("2026-10-29T08:00:00Z")
        val usage = RecentUsage(store) { now }
        usage.beginSession("first", EntryKind.Server, instance.id, "a.test")
        now = now.plusSeconds(10)
        usage.beginSession("second", EntryKind.Server, instance.id, "b.test")
        now = now.plusSeconds(20)
        usage.endSession("first")
        now = now.plusSeconds(10)
        usage.endSession("second", true)
        assertEquals(30, usage.summary(EntryKind.Server, instance.id, "a.test").totalSeconds)
        assertEquals(30, usage.summary(EntryKind.Server, instance.id, "b.test").totalSeconds)
        assertFalse(usage.summary(EntryKind.Server, instance.id, "a.test").estimated)
        assertTrue(RecentUsage(store).summary(EntryKind.Server, instance.id, "b.test").estimated)
        assertFalse(usage.summary(EntryKind.Server, instance.id, "b.test").active)
    }

    @Test
    fun `official record tracks manual joins and ignores repeated reads and incomplete files`() {
        val (store, instance) = store()
        val file = directory.resolve("quick-play.json")
        val tracker = GameActivityTracker(store, instance, file)
        file.writeText("[")
        tracker.pollQuickPlay()
        assertTrue(store.recentUsage.recent(EntryKind.Server, instance.id, 10).isEmpty())
        val entered = Instant.now().minusSeconds(3)
        writeJson(file, quickPlayServerEntries(entered))
        tracker.pollQuickPlay()
        assertEquals(
            entered,
            store.recentUsage.lastUsed(EntryKind.Server, instance.id, "PLAY.example.test:25566"),
        )
        val revision = store.state.value.recentUsageRevision
        tracker.pollQuickPlay()
        assertEquals(revision, store.state.value.recentUsageRevision)
        tracker.accept("[18:00:00] [Render thread/INFO]: Disconnected")
        assertFalse(
            store.recentUsage
                .summary(EntryKind.Server, instance.id, "PLAY.example.test:25566")
                .active,
        )
        writeJson(file, quickPlayWorldEntries())
        tracker.pollQuickPlay()
        assertNotNull(store.recentUsage.lastUsed(EntryKind.World, instance.id, "中文 世界"))
        tracker.finish()
    }

    private fun quickPlayServerEntries(entered: Instant): List<Map<String, String>> =
        listOf(
            mapOf(
                "type" to "multiplayer",
                "identifier" to "PLAY.example.test:25566",
                "lastPlayedTime" to entered.toString(),
                "name" to "Example",
                "gamemode" to "survival",
            ),
        )

    private fun quickPlayWorldEntries(): List<Map<String, String>> =
        listOf(
            mapOf(
                "type" to "singleplayer",
                "identifier" to "中文 世界",
                "lastPlayedTime" to Instant.now().toString(),
            ),
        )

    @Test
    fun `NBT deletion preserves unrelated fields compression and a recoverable backup`() {
        for (compressed in listOf(false, true)) {
            val (store, instance) = store()
            val game = store.gamePath(instance)
            val original = encodeNbt(NbtDocument("named-root", serverListTags(), compressed))
            game.resolve("servers.dat").writeBytes(original)
            store.recentUsage.record(EntryKind.Server, instance.id, "example.test")
            store.recentUsage.setHidden(EntryKind.Server, instance.id, "example.test", true)
            val result = ServerListEditor(store).delete(instance.id, "example.test")
            assertEquals(1, result.removed)
            assertContentEquals(original, requireNotNull(result.backup).readBytes())
            assertServerListRewritten(store, instance, game, compressed)
        }
    }

    private fun serverListTags(): NbtCompound =
        NbtCompound(
            linkedMapOf(
                "custom" to NbtLong(42),
                "servers" to
                    NbtList(
                        listOf(
                            NbtCompound(
                                mapOf(
                                    "name" to NbtString("Remove"),
                                    "ip" to NbtString("EXAMPLE.test:25565"),
                                    "extra" to NbtByte(3),
                                ),
                            ),
                            NbtCompound(
                                mapOf(
                                    "name" to NbtString("保留"),
                                    "ip" to NbtString("keep.test"),
                                    "unknown" to NbtIntArray(intArrayOf(1, 2)),
                                ),
                            ),
                        ),
                        10,
                    ),
            ),
        )

    private fun assertServerListRewritten(
        store: LauncherStore,
        instance: GameInstance,
        game: Path,
        compressed: Boolean,
    ) {
        val after = readNbtDocument(game.resolve("servers.dat"))
        assertEquals("named-root", after.rootName)
        assertEquals(compressed, after.compressed)
        assertEquals(NbtLong(42), after.root["custom"])
        assertContentEquals(
            intArrayOf(1, 2),
            (
                checkNotNull(
                    after.root
                        .list("servers"),
                ).compounds()
                    .single()["unknown"] as NbtIntArray
            ).value,
        )
        assertEquals(listOf("keep.test"), listServers(game).map { it.ip })
        assertNull(store.recentUsage.lastUsed(EntryKind.Server, instance.id, "example.test"))
        assertFalse(store.recentUsage.isHidden(EntryKind.Server, instance.id, "example.test"))
    }

    @Test
    fun `corrupt server list cannot be overwritten by deletion`() {
        val (store, instance) = store()
        val file = store.gamePath(instance).resolve("servers.dat")
        val bytes = "not NBT".toByteArray()
        file.writeBytes(bytes)
        assertFails { ServerListEditor(store).delete(instance.id, "example.test") }
        assertContentEquals(bytes, file.readBytes())
    }

    @Test
    fun `modern legacy and unsupported targets use appropriate arguments`() {
        val server = GameLaunchTarget(EntryKind.Server, "[2001:db8::1]:25566")
        assertEquals(
            listOf("--server", "2001:db8::1", "--port", "25566"),
            quickPlayArguments(server, false, null, "1.19.4"),
        )
        assertFailsWith<IllegalArgumentException> {
            quickPlayArguments(GameLaunchTarget(EntryKind.World, "world"), false, null, "1.19.4")
        }
        assertFailsWith<IllegalArgumentException> { GameLaunchTarget(EntryKind.World, "../other") }
        assertFailsWith<IllegalArgumentException> {
            GameLaunchTarget(EntryKind.Server, "invalid address")
        }
        val modern =
            ResolvedProfile(
                "fabric-profile",
                "26.3",
                JsonParser.parseString("""{"releaseTime":"2026-10-28T00:00:00Z"}""").asJsonObject,
            )
        assertTrue(supportsQuickPlay(modern, "fabric-custom"))
        assertEquals(
            listOf("--quickPlayMultiplayer", server.key),
            quickPlayArguments(server, true, null, "26.3"),
        )
    }

    @Test
    fun `real Java receives the quick play target and correct working directory`() =
        runBlocking {
            val (store, instance) = store()
            stageQuickTargetFixture(store)
            val profile = quickTargetProfile()
            val runtime = quickTargetRuntime(store)
            val recording = directory.resolve("run").apply { createDirectories() }
            val before = store.instance(instance.id).extraJvmArgs
            for (target in quickPlayTargets()) {
                assertQuickPlayRun(store, instance, profile, runtime, recording, target)
            }
            assertEquals(before, store.instance(instance.id).extraJvmArgs)
        }

    /** 编译一个只回显参数与工作目录的 Java 夹具，并打进实例的版本 jar。 */
    private fun stageQuickTargetFixture(store: LauncherStore) {
        val source = directory.resolve("QuickTargetFixture.java")
        source.writeText(
            """public class QuickTargetFixture {
                public static void main(String[] args) {
                    System.out.println(String.join("|",args));
                    System.out.println(java.nio.file.Path.of("").toAbsolutePath());
                } }""",
        )
        assertEquals(
            0,
            ToolProvider
                .getSystemJavaCompiler()
                .run(null, null, null, "-d", directory.toString(), source.toString()),
        )
        val jar =
            store.minecraftRoot.resolve("versions/recent/recent.jar").apply {
                parent.createDirectories()
            }
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("QuickTargetFixture.class"))
            it.write(directory.resolve("QuickTargetFixture.class").readBytes())
            it.closeEntry()
        }
    }

    private fun quickTargetProfile(): ResolvedProfile =
        ResolvedProfile(
            "recent",
            "recent",
            JsonParser
                .parseString(
                    """
                    {
                      "mainClass": "QuickTargetFixture",
                      "libraries": [],
                      "arguments": {"jvm": ["-cp", "${'$'}{classpath}"], "game": []}
                    }
                    """,
                ).asJsonObject,
        )

    private fun quickTargetRuntime(store: LauncherStore): JavaRuntime =
        JavaRuntime(
            Path
                .of(
                    System.getProperty("java.home"),
                    "bin",
                    if (store.platform.osName == "windows") "java.exe" else "java",
                ).toString(),
            Runtime.version().feature(),
            "fixture",
        )

    private fun quickPlayTargets(): List<GameLaunchTarget> =
        listOf(
            GameLaunchTarget(EntryKind.Server, "127.0.0.1:25566"),
            GameLaunchTarget(EntryKind.World, "中文 世界"),
        )

    private suspend fun assertQuickPlayRun(
        store: LauncherStore,
        instance: GameInstance,
        profile: ResolvedProfile,
        runtime: JavaRuntime,
        recording: Path,
        target: GameLaunchTarget,
    ) {
        if (target.kind == EntryKind.World) {
            store
                .gamePath(instance)
                .resolve("saves/${target.key}")
                .createDirectories()
                .resolve("level.dat")
                .writeText("fixture")
        }
        val plan =
            LaunchPlanner(store)
                .plan(
                    instance,
                    profile,
                    runtime,
                    GameCredentials("Tester", "0"),
                    runtimeDirectory = recording,
                    target = target,
                )
        val process =
            ProcessBuilder(listOf(plan.executable) + plan.arguments)
                .directory(plan.gameDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertContains(
            output,
            if (target.kind == EntryKind.Server) {
                "--quickPlayMultiplayer|${target.key}"
            } else {
                "--quickPlaySingleplayer|${target.key}"
            },
        )
        assertContains(output, "--quickPlayPath|${recording.resolve("quick-play.json")}")
        assertContains(output, store.gamePath(instance).toString())
        assertFalse(plan.arguments.any { it.contains("${'$'}{quickPlay") })
    }
}
