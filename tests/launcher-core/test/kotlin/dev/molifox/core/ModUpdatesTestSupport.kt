// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 模组更新测试共用的夹具：临时目录、假 jar、假更新源与实例。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

/**
 * 两个更新测试类共用的夹具。
 *
 * 只放构造数据的辅助方法，不放断言与 @Test，避免测试被继承后重复执行。
 */
abstract class ModUpdatesTestSupport {
    @TempDir lateinit var temporary: Path

    internal fun jar(
        path: Path,
        entries: Map<String, String>,
    ): Path =
        path.also { target ->
            target.parent.createDirectories()
            ZipOutputStream(target.outputStream()).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    internal fun jarBytes(version: String): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zip.putNextEntry(ZipEntry("fabric.mod.json"))
            zip.write("""{"id":"fox","name":"Fox","version":"$version"}""".toByteArray())
            zip.closeEntry()
        }
        return buffer.toByteArray()
    }

    internal fun sha512(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }

    internal fun project(
        id: String,
        title: String = id,
        provider: String = "modrinth",
    ) = RemoteModProject(id, id, title, provider)

    internal fun version(
        project: RemoteModProject,
        number: String,
        bytes: ByteArray,
        gameVersions: List<String> = listOf("1.20.1"),
        loaders: List<String> = listOf("fabric"),
        filename: String = "new-$number.jar",
        versionType: String = "release",
    ) = RemoteModVersion(
        project,
        "${project.id}-$number",
        number,
        "Fox $number",
        gameVersions,
        loaders,
        versionType,
        "https://cdn.modrinth.com/data/fox/versions/$number.jar",
        filename,
        bytes.size.toLong(),
        sha512(bytes),
        "",
    )

    internal class FakeSource(
        override val provider: String,
        private val fingerprints: Map<String, RemoteModVersion> = emptyMap(),
        private val projects: Map<String, RemoteModProject> = emptyMap(),
        private val searchHits: List<RemoteModProject> = emptyList(),
        private val versionMap: Map<String, List<RemoteModVersion>> = emptyMap(),
        /** 非空表示该源支持批量最新版本查询：按文件名返回结算结果。 */
        private val batchLatest: Map<String, RemoteModVersion>? = null,
    ) : ModUpdateSource {
        val searchCalls = mutableListOf<String>()
        var batchCalls = 0
            private set
        var versionCalls = 0
            private set

        override suspend fun fingerprint(files: List<LocalModFile>) =
            files
                .mapNotNull { file ->
                    fingerprints[file.filename]?.let { file.filename to it }
                }.toMap()

        override suspend fun latestVersions(
            files: List<LocalModFile>,
            instance: GameInstance,
            allowPrerelease: Boolean,
        ): Map<String, RemoteModVersion>? {
            val known = batchLatest ?: return null
            batchCalls++
            return files
                .mapNotNull { file ->
                    known[file.filename]?.let { file.filename to it }
                }.toMap()
        }

        override suspend fun projectBySlug(
            slug: String,
            instance: GameInstance,
        ) = projects[slug]

        override suspend fun search(
            name: String,
            instance: GameInstance,
        ): List<RemoteModProject> {
            searchCalls += name
            return searchHits
        }

        override suspend fun versions(
            project: RemoteModProject,
            instance: GameInstance,
        ): List<RemoteModVersion> {
            versionCalls++
            return versionMap[project.id].orEmpty()
        }
    }

    internal fun fixture(
        loader: LoaderType = LoaderType.Fabric,
        gameVersion: String = "1.20.1",
    ): Triple<LauncherStore, GameInstance, Path> {
        val platform =
            object : PlatformServices by DesktopPlatform() {
                override val defaultDataDirectory = temporary.resolve("profile")

                override fun moveToTrash(file: Path) {
                    val target = temporary.resolve("trash/${UUID.randomUUID()}-${file.fileName}")
                    target.parent.createDirectories()
                    Files.move(file, target)
                }
            }
        val store = LauncherStore(platform)
        val instance =
            GameInstance(
                name = "Mods",
                gameVersion = gameVersion,
                versionId = "fixture",
                loader = loader,
                installed = true,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        val root = store.instancePath(instance.id)
        root.resolve("mods").createDirectories()
        return Triple(store, instance, root)
    }

    /** 写出一个带 fabric.mod.json 的假模组 jar（默认 1.0.0）。 */
    internal fun writeFoxJar(
        path: Path,
        version: String = "1.0.0",
    ): Path =
        jar(
            path,
            mapOf(
                "fabric.mod.json" to """{"id":"fox","name":"Fox","version":"$version"}""",
            ),
        )

    internal fun modrinthVersion(
        project: String,
        number: String,
        filename: String,
    ): JsonObject =
        JsonObject().apply {
            addProperty("id", "v-$project-$number")
            addProperty("project_id", project)
            addProperty("version_number", number)
            addProperty("name", "$project $number")
            addProperty("version_type", "release")
            add("game_versions", JsonArray().apply { add("1.20.1") })
            add("loaders", JsonArray().apply { add("fabric") })
            add(
                "files",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            addProperty("primary", true)
                            addProperty("url", "https://cdn.example/$filename")
                            addProperty("filename", filename)
                            addProperty("size", 1L)
                            add(
                                "hashes",
                                JsonObject().apply {
                                    addProperty("sha512", "0".repeat(128))
                                    addProperty("sha1", "0".repeat(40))
                                },
                            )
                        },
                    )
                },
            )
        }
}
