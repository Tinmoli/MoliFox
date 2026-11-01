// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 提供整合包格式测试共用的清单、归档与存储夹具。

package dev.molifox.core

import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes

/** 归档条目的 SHA-1 十六进制摘要，供清单与夹具共用。 */
internal fun packSha1(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

/** 把文本条目打成 zip 字节，供整合包夹具使用。 */
internal fun packZipBytes(entries: Map<String, String>): ByteArray =
    ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, value) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

/**
 * 整合包格式测试共用的夹具：清单构造、归档打包、临时存储与服务装配。
 * 需要临时目录的子类自己声明 [temporary]，这里只声明约定。
 */
abstract class PackFormatFixtures {
    abstract val temporary: Path

    protected fun hash(bytes: ByteArray) = packSha1(bytes)

    protected fun addon(
        path: String = "config/test.txt",
        content: String = "setting",
    ) = gson
        .toJsonTree(
            mapOf(
                "type" to "addon",
                "path" to path,
                "hash" to hash(content.toByteArray()),
                "force" to true,
            ),
        ).asJsonObject

    protected fun mcbbs(
        loader: String = "",
        loaderVersion: String = "",
    ) = gson
        .toJsonTree(
            mapOf(
                "manifestType" to "minecraftModpack",
                "manifestVersion" to 2,
                "name" to "茉狐测试包",
                "version" to "1.0",
                "description" to "test description",
                "addons" to
                    (
                        listOf(mapOf("id" to "game", "version" to "1.20.1")) +
                            if (loader.isBlank()) {
                                emptyList()
                            } else {
                                listOf(mapOf("id" to loader, "version" to loaderVersion))
                            }
                    ),
                "files" to listOf(addon()),
                "libraries" to emptyList<String>(),
                "launchInfo" to
                    mapOf(
                        "javaArgument" to emptyList<String>(),
                        "launchArgument" to emptyList<String>(),
                        "minMemory" to 2048,
                    ),
            ),
        ).asJsonObject

    /** 旧版纯归档的清单：只有名字、游戏版本和包版本。 */
    protected fun legacyManifest() =
        gson
            .toJsonTree(mapOf("name" to "Old pack", "gameVersion" to "1.20.1", "version" to "1.0"))
            .asJsonObject

    protected fun companion(
        version: String = "1.20.1",
        loader: String = "",
    ) = gson
        .toJsonTree(
            mapOf(
                "manifestType" to "minecraftModpack",
                "manifestVersion" to 1,
                "name" to "Companion",
                "overrides" to "overrides",
                "minecraft" to
                    mapOf<String, Any>(
                        "version" to version,
                        "modLoaders" to
                            (
                                if (loader.isBlank()) {
                                    emptyList<Map<String, Any>>()
                                } else {
                                    listOf(mapOf<String, Any>("id" to loader, "primary" to true))
                                }
                            ),
                    ),
                "files" to emptyList<String>(),
            ),
        ).asJsonObject

    protected fun zipBytes(entries: Map<String, String>): ByteArray = packZipBytes(entries)

    protected fun pack(
        info: JsonObject = mcbbs(),
        filename: String = "mcbbs.packmeta",
        prefix: String = "",
        files: Map<String, String> = mapOf("config/test.txt" to "setting"),
        extra: Map<String, String> = emptyMap(),
    ): Path =
        temporary.resolve("${UUID.randomUUID()}.zip").apply {
            val content = if (filename == "modpack.json") "minecraft/" else "overrides/"
            writeBytes(
                zipBytes(
                    mapOf("$prefix$filename" to gson.toJson(info)) +
                        files.mapKeys { "$prefix$content${it.key}" } +
                        extra,
                ),
            )
        }

    protected fun store() =
        LauncherStore(
            object : PlatformServices by DesktopPlatform() {
                override fun moveToTrash(file: Path) {
                    val target = temporary.resolve("trash/${UUID.randomUUID()}")
                    target.parent.createDirectories()
                    Files.move(file, target)
                }
            },
            temporary.resolve("profile"),
        )

    protected fun service(
        store: LauncherStore,
        network: NetworkClient,
    ) = ContentService(
        store,
        network,
        MinecraftInstaller(store, network, JavaService(store, network)),
    )

    internal fun parsed(
        source: Path,
        action: (PackManifest) -> Unit,
    ) = ZipFile(source.toFile()).use {
        action(readPackManifest(it, temporary.resolve("validation"), "windows"))
    }
}
