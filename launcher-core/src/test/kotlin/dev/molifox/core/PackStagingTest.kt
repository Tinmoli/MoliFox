// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证整合包内容落盘、事务回滚与私有文件过滤的行为与边界。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class PackStagingTest : PackFormatFixtures() {
    @TempDir override lateinit var temporary: Path

    @Test
    fun `remote addons stage transactionally and verify sha1`() =
        runBlocking {
            val store = store()
            val root = temporary.resolve("remote-game").apply { createDirectories() }
            val source =
                pack(
                    mcbbs().apply {
                        addProperty("fileApi", "https://raw.githubusercontent.com/team/pack/main")
                    },
                    files = mapOf("config/other.txt" to "present"),
                )
            for (corrupt in listOf(true, false)) {
                val transaction = PackFileTransaction(store, root)
                try {
                    ZipFile(source.toFile()).use { zip ->
                        val manifest = readPackManifest(zip, root, "windows")

                        suspend fun stage() =
                            stagePackFiles(
                                manifest,
                                zip,
                                transaction,
                                root,
                                null,
                                false,
                                JobContext(store, "test"),
                            ) {
                                it.target.parent.createDirectories()
                                it.target.writeText(if (corrupt) "wrong" else "setting")
                            }
                        if (corrupt) {
                            assertFails { stage() }
                            assertFalse(root.resolve("config/test.txt").exists())
                        } else {
                            stage()
                            transaction.commit(JobContext(store, "test"))
                            assertEquals("setting", root.resolve("config/test.txt").readText())
                        }
                    }
                } finally {
                    transaction.cleanup()
                }
            }
        }

    @Test
    fun `old plain archives support offline preview and game content staging`() =
        runBlocking {
            val store = store()
            for (prefix in listOf("", "Old pack/")) {
                val source =
                    pack(
                        legacyManifest(),
                        "modpack.json",
                        prefix,
                        files =
                            mapOf(
                                "options.txt" to "options",
                                "saves/world/level.dat" to "world",
                            ),
                    )
                stageLegacyArchive(store, source)
            }
        }

    /** 旧版纯归档只做离线预检与内容落盘，不发起任何下载。 */
    private suspend fun stageLegacyArchive(
        store: LauncherStore,
        source: Path,
    ) {
        val root =
            temporary
                .resolve(
                    UUID.randomUUID().toString(),
                ).apply { createDirectories() }
        val transaction = PackFileTransaction(store, root)
        try {
            ZipFile(source.toFile()).use { zip ->
                val manifest = readPackManifest(zip, root, "windows")
                assertEquals("Old pack", manifest.request.name)
                assertEquals(LoaderType.Vanilla, manifest.request.loader)
                stagePackFiles(
                    manifest,
                    zip,
                    transaction,
                    root,
                    null,
                    false,
                    JobContext(store, "test"),
                ) {
                    error("No download expected")
                }
            }
            transaction.commit(JobContext(store, "test"))
            assertEquals("world", root.resolve("saves/world/level.dat").readText())
        } finally {
            transaction.cleanup()
        }
    }

    @Test
    fun `MCBBS staging excludes private files and preserves settings during repair`() =
        runBlocking {
            val store = store()
            val root =
                temporary.resolve("private-game").apply { resolve("config").createDirectories() }
            root.resolve("config/test.txt").writeText("user setting")
            val source =
                pack(
                    extra =
                        mapOf(
                            "overrides/accounts.json" to "private",
                            "overrides/hmclversion.cfg" to "commands",
                            "overrides/logs/latest.log" to "log",
                        ),
                )
            val transaction = PackFileTransaction(store, root)
            try {
                ZipFile(source.toFile()).use { zip ->
                    stagePackFiles(
                        readPackManifest(zip, root, "windows"),
                        zip,
                        transaction,
                        root,
                        null,
                        true,
                        JobContext(store, "test"),
                    ) {
                        error("No download expected")
                    }
                }
                transaction.commit(JobContext(store, "test"))
                assertEquals("user setting", root.resolve("config/test.txt").readText())
                assertFalse(root.resolve("accounts.json").exists())
                assertFalse(root.resolve("hmclversion.cfg").exists())
                assertFalse(root.resolve("logs").exists())
            } finally {
                transaction.cleanup()
            }
        }
}
