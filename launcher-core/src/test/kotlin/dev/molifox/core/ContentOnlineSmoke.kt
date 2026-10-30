// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责验证真实资源下载、哈希、版本与更新。

package dev.molifox.core

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.name

/** Public API + small JAR acceptance, no credentials and no game or mod execution. */
fun main() =
    runBlocking {
        val root = Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize()
        val report =
            linkedMapOf<String, Any>(
                "platform" to System.getProperty("os.name"),
                "gameExecuted" to false,
            )
        try {
            LauncherController(dataDirectory = root.resolve("profile")).use { controller ->
                val instance = createContentSmokeInstance(controller)
                val details = installHistoricalSodium(controller, instance, report)
                reportInstalledMetadata(controller, instance, report)
                verifyLockAndDisabledPreservation(controller, instance, details, report)
                applyAvailableUpdate(controller, instance, details, report)
                verifyDisabledSodium(controller, instance)
                report["lockAndDisabledPreservation"] = "passed"
                report["result"] = "passed"
            }
        } catch (failure: Throwable) {
            report["result"] = "failed"
            report["error"] = failure.message.orEmpty().take(500)
            throw failure
        } finally {
            writeJson(Path.of(System.getProperty("molifox.content.report")), report)
            println(gson.toJson(report))
        }
    }

private fun createContentSmokeInstance(controller: LauncherController): GameInstance {
    val instance =
        GameInstance(
            name = "Modrinth acceptance",
            gameVersion = "1.20.1",
            versionId = "fixture",
            loader = LoaderType.Fabric,
            installed = true,
        )
    controller.store.mutate { it.copy(instances = listOf(instance)) }
    return instance
}

/** 读取已安装模组的元数据并禁用一次，验证开关不会破坏识别结果。 */
private fun reportInstalledMetadata(
    controller: LauncherController,
    instance: GameInstance,
    report: MutableMap<String, Any>,
) {
    val mod =
        controller.content.listMods(instance.id).first { file ->
            file.metadata.entries.any { it.id == "sodium" }
        }
    report["metadata"] =
        mod.metadata.entries.map { mapOf("id" to it.id, "version" to it.version) }
    controller.content.toggleMod(instance.id, mod.filename)
}

/** 锁定期间不应给出更新；解锁后更新重新出现，说明锁标记生效。 */
private suspend fun verifyLockAndDisabledPreservation(
    controller: LauncherController,
    instance: GameInstance,
    details: ContentDetails,
    report: MutableMap<String, Any>,
) {
    val checked = controller.content.catalog.checkUpdates(instance.id)
    val item = checked.items.first { it.source?.projectId == details.hit.id }
    val source = requireNotNull(item.source)
    check(source.filename.endsWith(".disabled"))
    report["recognizedVersion"] = source.versionId
    controller.content.catalog.setLocked(instance.id, source, true)
    check(
        controller.content.catalog
            .checkUpdates(instance.id)
            .items
            .first { it.source?.projectId == details.hit.id }
            .let { checkNotNull(it.source).locked && it.update == null },
    )
    controller.content.catalog.setLocked(instance.id, source, false)
}

/** 解锁后必须能算出新版本并完成安装，供最后的禁用状态检查使用。 */
private suspend fun applyAvailableUpdate(
    controller: LauncherController,
    instance: GameInstance,
    details: ContentDetails,
    report: MutableMap<String, Any>,
) {
    val updates = controller.content.catalog.checkUpdates(instance.id)
    val updated = updates.items.first { it.source?.projectId == details.hit.id }
    requireNotNull(updated.update) {
        "Expected a newer Sodium release for historical compatible sample"
    }
    report["updatedVersion"] = checkNotNull(updated.update).id
    controller.content.installPlan(
        controller.content.planUpdates(updates, setOf(updated.key)),
        JobContext(controller.store, "content-smoke"),
    )
}

private suspend fun installHistoricalSodium(
    controller: LauncherController,
    instance: GameInstance,
    report: MutableMap<String, Any>,
): ContentDetails {
    val details =
        controller.content.catalog.details(
            "AANobbMI",
            instance.id,
        ) // Sodium public project ID.
    val release = details.versions.last { it.channel == "release" }
    report["project"] = details.hit.title
    report["compatibleVersions"] = details.versions.size
    controller.content.installPlan(
        controller.content.planContent(
            details.hit,
            instance.id,
            versionId = release.id,
        ),
        JobContext(controller.store, "content-smoke"),
    )
    return details
}

private fun verifyDisabledSodium(
    controller: LauncherController,
    instance: GameInstance,
) {
    val finalMods = controller.content.listMods(instance.id)
    check(
        finalMods.count { file ->
            file.metadata.entries.any { it.id == "sodium" }
        } == 1,
    )
    check(
        finalMods
            .first { file -> file.metadata.entries.any { it.id == "sodium" } }
            .enabled
            .not(),
    )
}
