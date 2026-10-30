// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证版本、路径和启动参数的基础规则的行为与边界。

package dev.molifox.core

import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreRulesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `archive paths cannot escape on Windows or Linux`() {
        listOf(
            "../outside",
            "mods/../../outside",
            "C:\\secret",
            "\\\\server\\file",
            "/etc/passwd",
            "mods/test.jar:stream",
            "mods/NUL.jar",
            "mods/con",
            "bad.",
            "bad ",
        ).forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) { safePath(directory, path) }
        }
        assertEquals(directory.resolve("mods/中文 模组.jar"), safePath(directory, "mods/中文 模组.jar"))
    }

    @Test
    fun `offline UUID follows Mojang Java name UUID algorithm`() {
        assertEquals("b50ad385829d3141a2167e7d7539ba7f", offlineUuid("Notch"))
        assertEquals("5627dd98e6be3c21b8a8e92344183641", offlineUuid("Steve"))
    }

    @Test
    fun `maven coordinates retain classifiers and extension`() {
        assertEquals(
            "net/minecraft/client/1.20/client-1.20-mappings.txt",
            mavenPath("net.minecraft:client:1.20:mappings@txt"),
        )
        assertEquals(
            "org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux-arm64.jar",
            mavenPath("org.lwjgl:lwjgl:3.3.3", "natives-linux-arm64"),
        )
        assertFails { mavenPath("a:b:../../escape") }
    }

    @Test
    fun `last matching OS rule wins and feature rules require explicit support`() {
        val rules =
            JsonParser
                .parseString(
                    """[{"action":"allow"},{"action":"disallow","os":{"name":"windows"}},{"action":"allow","os":{"name":"windows","arch":"aarch64"}}]""",
                ).asJsonArray
        assertFalse(rulesAllow(rules, "windows", "amd64", "10.0"))
        assertTrue(rulesAllow(rules, "linux", "amd64", "6.8"))
        assertTrue(rulesAllow(rules, "windows", "aarch64", "10.0"))
        val feature =
            JsonParser
                .parseString(
                    """[{"action":"allow","features":{"has_custom_resolution":true}}]""",
                ).asJsonArray
        assertFalse(rulesAllow(feature, "linux", "amd64", "6.8"))
        assertTrue(
            rulesAllow(feature, "linux", "amd64", "6.8", mapOf("has_custom_resolution" to true)),
        )
    }

    @Test
    fun `quoted JVM arguments preserve spaces and reject unfinished quotes`() {
        assertEquals(
            listOf("-Dmy.path=C:\\游戏 文件", "-XX:+UseG1GC"),
            splitArguments("\"-Dmy.path=C:\\游戏 文件\" -XX:+UseG1GC"),
        )
        assertFails { splitArguments("-Dname=\"unfinished") }
    }

    @Test
    fun `tokens are redacted before entering log state`() {
        assertFalse(
            redactSecrets("--accessToken super-secret-value").contains("super-secret-value"),
        )
        assertFalse(redactSecrets("Authorization: Bearer secret123").contains("secret123"))
        assertEquals("ordinary game output", redactSecrets("ordinary game output"))
    }

    @Test
    fun `version inheritance keeps vanilla jar and replaces library versions`() {
        writeJson(
            directory.resolve("versions/base/base.json"),
            JsonParser.parseString(
                """{"id":"base","mainClass":"Base","downloads":{"client":{"url":"https://example.com/base.jar"}},"libraries":[{"name":"org.example:library:1"}],"arguments":{"game":["--username","${'$'}{auth_player_name}"]}}""",
            ),
        )
        writeJson(
            directory.resolve("versions/modded/modded.json"),
            JsonParser.parseString(
                """{"id":"modded","inheritsFrom":"base","mainClass":"Modded","libraries":[{"name":"org.example:library:2"}],"arguments":{"game":["--tweakClass","Example"]}}""",
            ),
        )
        val resolved = VersionResolver(directory, DesktopPlatform()).resolve("modded")
        assertEquals("base", resolved.jarId)
        assertEquals("Modded", resolved.mainClass)
        assertEquals(
            listOf("org.example:library:2"),
            resolved.json.array("libraries").map { it.asJsonObject.string("name") },
        )
        assertEquals(
            4,
            resolved.json
                .obj("arguments")
                .array("game")
                .size(),
        )
    }

    @Test
    fun `circular inheritance is rejected`() {
        writeJson(directory.resolve("versions/a/a.json"), mapOf("id" to "a", "inheritsFrom" to "b"))
        writeJson(directory.resolve("versions/b/b.json"), mapOf("id" to "b", "inheritsFrom" to "a"))
        assertFails { VersionResolver(directory, DesktopPlatform()).resolve("a") }
    }

    @Test
    fun `native-only libraries do not request a nonexistent ordinary jar`() {
        val library =
            JsonParser
                .parseString(
                    """{"name":"org.lwjgl:lwjgl-platform:2.9","natives":{"windows":"natives-windows","linux":"natives-linux"},"downloads":{"classifiers":{"natives-windows":{"url":"https://example.com/windows.jar","path":"windows.jar"},"natives-linux":{"url":"https://example.com/linux.jar","path":"linux.jar"}}}}""",
                ).asJsonObject
        val resolver = VersionResolver(directory, DesktopPlatform())
        assertFalse(resolver.hasClasspathArtifact(library))
        assertEquals(1, resolver.libraryArtifacts(library).size)
        assertTrue(resolver.libraryArtifacts(library).single().second)
    }

    @Test
    fun `Java major versions support legacy and modern vendor output`() {
        assertEquals(8, parseJavaVersion("java version \"1.8.0_442\""))
        assertEquals(21, parseJavaVersion("openjdk version \"21.0.7\" 2025-04-15"))
        assertEquals(25, parseJavaVersion("java.version = 25.0.3"))
    }

    @Test
    fun `modpack downloads cannot target local services`() {
        listOf(
            "https://localhost/file",
            "https://127.0.0.1/admin",
            "file:///etc/passwd",
            "http://cdn.modrinth.com/test",
            "https://cdn.modrinth.com:8443/test",
        ).forEach { assertFails { validatePackUrl(it) } }
        assertEquals(
            "https://cdn.modrinth.com/data/test.jar",
            validatePackUrl("https://cdn.modrinth.com/data/test.jar"),
        )
    }

    @Test
    fun `loader ordering prefers latest numeric version and stable releases`() {
        assertTrue(compareLoaderVersions("0.29.2", "0.20.0-beta.9") > 0)
        assertTrue(compareLoaderVersions("0.28.0", "0.28.0-beta.10") > 0)
        assertTrue(compareLoaderVersions("0.28.0-beta.10", "0.28.0-beta.9") > 0)
        assertTrue(compareLoaderVersions("21.1.265", "21.1.9") > 0)
    }
}
