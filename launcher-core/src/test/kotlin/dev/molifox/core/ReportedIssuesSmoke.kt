// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证外部实例版本搜索和官方皮肤 CDN。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.coroutines.runBlocking

/** Read-only external profiles + public APIs. No login, game execution or user-file mutation. */
fun main() = runBlocking {
    val temporary = Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize()
    val report =
        linkedMapOf<String, Any>(
            "platform" to System.getProperty("os.name"),
            "gameExecuted" to false,
            "userFilesModified" to false,
        )
    try {
        val store = LauncherStore(DesktopPlatform(), temporary.resolve("profile"))
        val root = Path.of(System.getProperty("molifox.reference.games"))
        GameDirectoryService(store).add(root)
        require(store.data.instances.isNotEmpty()) { "No reference profiles found" }
        NetworkClient(systemProxyEnabled = { true }).use { network ->
            val content =
                ContentService(
                    store,
                    network,
                    MinecraftInstaller(store, network, JavaService(store, network)),
                )
            val results = mutableListOf<Map<String, Any>>()
            for (instance in store.data.instances.filter { it.loader == LoaderType.Fabric }) {
                check(instance.gameVersion != instance.versionId) {
                    "Custom profile name leaked into game version filter"
                }
                val blank = content.search("", "mod", instance.id)
                val search = content.search("Sodium", "mod", instance.id)
                val versions = content.catalog.details("AANobbMI", instance.id).versions
                results +=
                    mapOf(
                        "gameVersion" to instance.gameVersion,
                        "blankSearchTotal" to blank.total,
                        "sodiumSearchTotal" to search.total,
                        "compatibleSodiumVersions" to versions.size,
                    )
                println("SEARCH_CHECK ${instance.gameVersion}: ${blank.total} / ${search.total}")
            }
            check(results.isNotEmpty())
            report["searchResults"] = results
            val accounts =
                readObject(Path.of(System.getProperty("molifox.reference.config")))
                    .array("accounts")
            val account =
                accounts
                    .map { gson.fromJson(it, Account::class.java) }
                    .firstOrNull { it.type == AccountType.Microsoft && it.skinUrl.isNotBlank() }
            requireNotNull(account) { "No public Microsoft skin URL in reference metadata" }
            val cache = AccountSkinCache(store, network)
            val texture =
                requireNotNull(cache.load(account, false)) { "Official skin CDN request failed" }
            check(validSkinTexture(texture))
            report["officialSkinTexture"] = "passed"
            report["result"] = "passed"
        }
    } catch (failure: Throwable) {
        report["result"] = "failed"
        report["error"] = failure.message.orEmpty().take(300)
        throw failure
    } finally {
        writeJson(Path.of(System.getProperty("molifox.issues.report")), report)
        println(gson.toJson(report))
    }
}
