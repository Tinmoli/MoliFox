// 负责验证真实资源下载、哈希、版本与更新。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.coroutines.runBlocking

/** Public API + small JAR acceptance, no credentials and no game or mod execution. */
fun main() = runBlocking {
    val root = Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize()
    val report =
        linkedMapOf<String, Any>(
            "platform" to System.getProperty("os.name"),
            "gameExecuted" to false,
        )
    try {
        LauncherController(dataDirectory = root.resolve("profile")).use { controller ->
            val instance =
                GameInstance(
                    name = "Modrinth acceptance",
                    gameVersion = "1.20.1",
                    versionId = "fixture",
                    loader = LoaderType.Fabric,
                    installed = true,
                )
            controller.store.mutate { it.copy(instances = listOf(instance)) }
            val details =
                controller.content.catalog.details(
                    "AANobbMI",
                    instance.id,
                ) // Sodium public project ID.
            val release = details.versions.last { it.channel == "release" }
            report["project"] = details.hit.title
            report["compatibleVersions"] = details.versions.size
            controller.content.installPlan(
                controller.content.planContent(details.hit, instance.id, versionId = release.id),
                JobContext(controller.store, "content-smoke"),
            )
            val mod =
                controller.content.listMods(instance.id).first { file ->
                    file.metadata.entries.any { it.id == "sodium" }
                }
            report["metadata"] =
                mod.metadata.entries.map { mapOf("id" to it.id, "version" to it.version) }
            controller.content.toggleMod(instance.id, mod.filename)
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
                    .let { it.source!!.locked && it.update == null }
            )
            controller.content.catalog.setLocked(instance.id, source, false)
            val updates = controller.content.catalog.checkUpdates(instance.id)
            val updated = updates.items.first { it.source?.projectId == details.hit.id }
            requireNotNull(updated.update) {
                "Expected a newer Sodium release for historical compatible sample"
            }
            report["updatedVersion"] = updated.update!!.id
            controller.content.installPlan(
                controller.content.planUpdates(updates, setOf(updated.key)),
                JobContext(controller.store, "content-smoke"),
            )
            val finalMods = controller.content.listMods(instance.id)
            check(finalMods.count { file -> file.metadata.entries.any { it.id == "sodium" } } == 1)
            check(
                finalMods
                    .first { file -> file.metadata.entries.any { it.id == "sodium" } }
                    .enabled
                    .not()
            )
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
