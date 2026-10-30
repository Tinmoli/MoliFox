// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 优先读取包内图标或已登记来源，允许时仅用文件摘要精确识别外部包的项目头像。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name

private const val MAX_CONTENT_RECORD_BYTES = 8 * 1024 * 1024
private const val MAX_CONTENT_ICON_DIMENSION = 2048

internal class InstalledContentIcons(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val backend: ContentBackend,
    private val api: String = "https://api.modrinth.com/v2",
    private val identifyUnregistered: Boolean = false,
) {
    suspend fun read(
        instanceId: String,
        path: Path,
        folder: PackFolder,
    ): Result<ByteArray?> =
        withContext(Dispatchers.IO) {
            runCatching { load(instanceId, path, folder) }.onFailure {
                if (it is CancellationException) throw it
            }
        }

    private suspend fun load(
        instanceId: String,
        path: Path,
        folder: PackFolder,
    ): ByteArray? {
        val root = store.instancePath(instanceId).toAbsolutePath().normalize()
        val expected = root.resolve(folder.directory).resolve(safeFilename(path.name))
        require(path.toAbsolutePath().normalize() == expected && !path.isSymbolicLink())
        val embedded =
            if (folder ==
                PackFolder.ShaderPacks
            ) {
                shaderPackIcon(path)
            } else {
                localContentIcon(path, false)
            }
        if (embedded != null) return embedded
        if (!path.isRegularFile()) return null
        val source = registeredSource(root, path, folder)
        val url =
            if (source != null) {
                source.second.string("iconUrl").ifBlank {
                    backend.project(source.first).string("icon_url")
                }
            } else if (identifyUnregistered && folder == PackFolder.ShaderPacks) {
                identifiedIcon(path, folder)
            } else {
                ""
            }
        if (url.isBlank()) return null
        val bytes = network.thumbnail(url)
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return bytes.takeIf {
            image.width in 1..MAX_CONTENT_ICON_DIMENSION &&
                image.height in 1..MAX_CONTENT_ICON_DIMENSION
        }
    }

    private suspend fun identifiedIcon(
        path: Path,
        folder: PackFolder,
    ): String {
        val hash = digest(path, "SHA-512")
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val version =
            network
                .json(
                    "$api/version_file/$hash?algorithm=sha512",
                    "official",
                ).asJsonObject
        if (version.array("files").none {
                it.asJsonObject
                    .obj("hashes")
                    .string("sha512")
                    .equals(hash, true)
            }
        ) {
            return ""
        }
        val id = version.string("project_id")
        if (id.isBlank()) return ""
        val project = backend.project(id)
        if (project.string("project_type") != folder.kind) return ""
        return project.string("icon_url")
    }

    private fun registeredSource(
        root: Path,
        path: Path,
        folder: PackFolder,
    ): Pair<String, JsonObject>? {
        val registry = root.resolve(".molifox-content.json")
        if (!registry.isRegularFile() || registry.isSymbolicLink()) return null
        val bytes =
            Files
                .newInputStream(
                    registry,
                ).use { it.readNBytes(MAX_CONTENT_RECORD_BYTES + 1) }
        require(bytes.size <= MAX_CONTENT_RECORD_BYTES) { "资源安装登记超过大小限制" }
        val entries = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.entrySet()
        val matches =
            entries.filter { (_, value) ->
                value.isJsonObject && value.asJsonObject.string("folder") == folder.directory &&
                    value.asJsonObject.string("file") == path.name
            }
        if (matches.size != 1) return null
        val (id, value) = matches.single()
        val source = value.asJsonObject
        val algorithm = source.string("hashAlgorithm")
        if (algorithm !in setOf("SHA-1", "SHA-512")) return null
        if (!digest(path, algorithm).equals(source.string("hash"), true)) return null
        return id to source
    }
}
