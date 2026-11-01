// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供整合包升级测试共用的临时目录、账本与整合包构造。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/**
 * 整合包升级测试的共享基类：账本、实例与自包含整合包构造。
 *
 * 夹具与原 `PackUpgradeTest` 内的私有辅助完全一致，拆出来只为了让各测试类只保留用例。
 */
abstract class PackUpgradeTestBase {
    @TempDir lateinit var temporary: Path

    internal fun hash(
        data: ByteArray,
        algorithm: String,
    ) = MessageDigest.getInstance(algorithm).digest(data).joinToString("") { "%02x".format(it) }

    internal fun sha(data: ByteArray) = hash(data, "SHA-1")

    internal fun sha256(data: ByteArray) = hash(data, "SHA-256")

    internal fun store() = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))

    internal fun instance(
        store: LauncherStore,
        packFile: String = "",
        gameVersion: String = "1.20.1",
    ): GameInstance {
        val instance =
            GameInstance(
                name = "Fixture instance",
                gameVersion = gameVersion,
                versionId = gameVersion,
                installed = true,
                packFile = packFile,
            )
        store.mutate { it.copy(instances = it.instances + instance) }
        store.instancePath(instance.id).createDirectories()
        return store.instance(instance.id)
    }

    internal fun ledger(
        vararg files: Pair<String, ByteArray>,
        versionId: String = "1.0.0",
        gameVersion: String = "1.20.1",
        loader: String = "Vanilla",
        loaderVersion: String = "",
    ) = PackOwnership(
        source = "archive",
        versionId = versionId,
        name = "Fixture pack",
        gameVersion = gameVersion,
        loader = loader,
        loaderVersion = loaderVersion,
        archiveSha256 = sha256("$versionId/$gameVersion/$loader".toByteArray()),
        installedAt = "2026-10-27T00:00:00Z",
        files =
            files
                .map { (path, data) ->
                    PackOwnedFile(
                        path,
                        sha(data),
                        data.size.toLong(),
                        false,
                        PackFileOrigin.Override,
                    )
                }.sortedBy { it.path },
    )

    internal fun zipBytes(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, data) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }

    /** 与 exportInstance 相同的自包含格式：molifox-instance.json + files/。 */
    internal fun exportedPack(
        files: Map<String, ByteArray>,
        gameVersion: String = "1.20.1",
        name: String = "Fixture pack",
    ): Path {
        val metadata =
            gson.toJson(
                mapOf(
                    "formatVersion" to 1,
                    "instance" to
                        mapOf(
                            "name" to name,
                            "gameVersion" to gameVersion,
                            "loader" to "Vanilla",
                            "loaderVersion" to "",
                        ),
                ),
            )
        return temporary.resolve("pack-${UUID.randomUUID()}.zip").apply {
            writeBytes(
                zipBytes(
                    mapOf("molifox-instance.json" to metadata.toByteArray()) +
                        files.mapKeys { "files/${it.key}" },
                ),
            )
        }
    }

    internal fun fileEntry(
        path: String,
        data: ByteArray,
        client: String = "required",
    ) = mapOf(
        "path" to path,
        "hashes" to mapOf("sha1" to sha(data), "sha512" to hash(data, "SHA-512")),
        "fileSize" to data.size,
        "env" to mapOf("client" to client, "server" to "required"),
        "downloads" to listOf("https://cdn.modrinth.com/data/$path"),
    )

    internal fun runWithService(block: suspend (LauncherStore, ContentService) -> Unit) =
        runBlocking {
            NetworkClient().use { network ->
                val store = store()
                val service =
                    ContentService(
                        store,
                        network,
                        MinecraftInstaller(store, network, JavaService(store, network)),
                    )
                block(store, service)
            }
        }

    internal fun upgradePlan(
        changes: List<PackFileChange>,
        toVersion: String = "2.0.0",
    ) = PackUpgradePlan(
        fromVersionId = "1.0.0",
        toVersionId = toVersion,
        requiresComponentChange = false,
        changes = changes,
        archiveSha256 = "f".repeat(64),
    )
}
