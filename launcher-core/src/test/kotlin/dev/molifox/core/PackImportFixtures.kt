// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 整合包导入测试共用的夹具：清单条目、zip 打包与临时存储。

package dev.molifox.core

import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

internal fun fixtureHash(
    data: ByteArray,
    algorithm: String,
): String = MessageDigest.getInstance(algorithm).digest(data).joinToString("") { "%02x".format(it) }

internal fun fixturePackFile(
    bytes: ByteArray,
    path: String,
    environment: String = "required",
): JsonObject =
    gson
        .toJsonTree(
            mapOf(
                "path" to path,
                "hashes" to
                    mapOf(
                        "sha1" to fixtureHash(bytes, "SHA-1"),
                        "sha512" to fixtureHash(bytes, "SHA-512"),
                    ),
                "fileSize" to bytes.size,
                "env" to mapOf("client" to environment, "server" to "required"),
                "downloads" to listOf("https://cdn.modrinth.com/$path"),
            ),
        ).asJsonObject

internal fun fixtureIndex(files: List<JsonObject> = emptyList()): JsonObject =
    gson
        .toJsonTree(
            mapOf(
                "formatVersion" to 1,
                "game" to "minecraft",
                "versionId" to "1.0",
                "name" to "Fixture pack",
                "summary" to "Pack preview",
                "dependencies" to mapOf("minecraft" to "fixture"),
                "files" to files,
            ),
        ).asJsonObject

internal fun fixtureZipBytes(entries: Map<String, ByteArray>): ByteArray =
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

internal fun fixturePack(
    temporary: Path,
    index: JsonObject,
    extras: Map<String, ByteArray> = emptyMap(),
): Path =
    temporary.resolve("${UUID.randomUUID()}.mrpack").apply {
        val indexBytes = gson.toJson(index).toByteArray()
        writeBytes(fixtureZipBytes(mapOf("modrinth.index.json" to indexBytes) + extras))
    }

internal fun fixtureStore(temporary: Path): LauncherStore =
    LauncherStore(
        object : PlatformServices by DesktopPlatform() {
            override fun moveToTrash(file: Path) {
                val destination = temporary.resolve("trash/${UUID.randomUUID()}")
                destination.parent.createDirectories()
                Files.move(file, destination)
            }
        },
        temporary.resolve("profile"),
    )

/** 两个镜像地址：第一个返回 404，第二个可用。 */
internal fun fixtureMirrorEntry(bytes: ByteArray): JsonObject =
    fixturePackFile(bytes, "a.txt").apply {
        add(
            "downloads",
            gson.toJsonTree(
                listOf(
                    "https://cdn.modrinth.com/missing.txt",
                    "https://gitlab.com/team/a.txt",
                ),
            ),
        )
    }
