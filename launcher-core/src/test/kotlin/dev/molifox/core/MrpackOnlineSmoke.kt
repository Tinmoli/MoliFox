// 负责验证真实整合包导出与 HMCL 清单读取。

package dev.molifox.core

import java.net.URLClassLoader
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlinx.coroutines.runBlocking

/** Explicit public-resource/third-party parser check; never starts a launcher window or game. */
fun main() = runBlocking {
    val root = Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize()
    val report =
        linkedMapOf<String, Any>(
            "platform" to System.getProperty("os.name"),
            "gameExecuted" to false,
            "hmclGuiStarted" to false,
        )
    try {
        val store = LauncherStore(DesktopPlatform(), root.resolve("profile"))
        val instance =
            GameInstance(
                name = "mrpack acceptance",
                gameVersion = "1.20.1",
                versionId = "fixture",
                loader = LoaderType.Fabric,
                loaderVersion = "0.16.14",
                installed = true,
                isolation = VersionIsolation.Enabled,
            )
        store.mutate { it.copy(instances = listOf(instance)) }
        NetworkClient(systemProxyEnabled = { true }).use { network ->
            val service =
                ContentService(
                    store,
                    network,
                    MinecraftInstaller(store, network, JavaService(store, network)),
                )
            val details = service.catalog.details("AANobbMI", instance.id)
            val version = details.versions.first { it.channel == "release" }
            service.installPlan(
                service.planContent(details.hit, instance.id, versionId = version.id),
                JobContext(store, "install"),
            )
            val mod =
                service.listMods(instance.id).first {
                    it.metadata.entries.any { entry -> entry.id == "sodium" }
                }
            service.toggleMod(instance.id, mod.filename)
            val gameRoot = store.instancePath(instance.id)
            gameRoot.resolve("config").createDirectories()
            gameRoot.resolve("config/molifox-smoke.txt").writeText("中文配置")
            val plan =
                service.previewMrpackExport(
                    instance.id,
                    MrpackExportMetadata("茉狐兼容测试", "1.0", "public Sodium reference"),
                )
            check(plan.warnings.isEmpty())
            check(plan.files.any { it.downloadUrl != null && it.path.endsWith(".disabled") })
            val archive = root.resolve("acceptance.mrpack")
            service.exportMrpack(plan, archive, JobContext(store, "export"))
            ZipFile(archive.toFile()).use { zip ->
                check(
                    zip.entries().asSequence().none {
                        it.name.endsWith(".jar") || it.name.endsWith(".disabled")
                    }
                )
                val parsed = readPackManifest(zip, root.resolve("imported"), "windows")
                val transaction =
                    PackFileTransaction(
                        store,
                        root.resolve("imported").apply { createDirectories() },
                    )
                try {
                    stagePackFiles(
                        parsed,
                        zip,
                        transaction,
                        root.resolve("imported"),
                        null,
                        false,
                        JobContext(store, "import"),
                    ) {
                        network.download(it)
                    }
                    transaction.commit(JobContext(store, "commit"))
                    for (file in plan.files.filter { it.path in plan.defaultSelection }) {
                        check(
                            digest(root.resolve("imported").resolve(file.path), "SHA-512") ==
                                file.sha512
                        )
                    }
                    report["publicModrinthRoundTrip"] = "passed"
                } finally {
                    transaction.cleanup()
                }
                val reference = Path.of(System.getProperty("molifox.mrpack.hmcl"))
                require(reference.isRegularFile()) {
                    "Provide -PhmclReference=<HMCL executable or JAR>"
                }
                URLClassLoader(
                        arrayOf(reference.toUri().toURL()),
                        ClassLoader.getPlatformClassLoader(),
                    )
                    .use { loader ->
                        val type =
                            loader.loadClass("org.jackhuang.hmcl.modpack.modrinth.ModrinthManifest")
                        val json =
                            zip.getInputStream(zip.getEntry("modrinth.index.json"))
                                .bufferedReader(Charsets.UTF_8)
                                .use { it.readText() }
                        val parsedHmcl =
                            loader
                                .loadClass("org.jackhuang.hmcl.util.gson.JsonUtils")
                                .getMethod("fromNonNullJson", String::class.java, Class::class.java)
                                .invoke(null, json, type)
                        type.getMethod("validate").invoke(parsedHmcl)
                        check(type.getMethod("getName").invoke(parsedHmcl) == plan.metadata.name)
                        check(type.getMethod("getGameVersion").invoke(parsedHmcl) == "1.20.1")
                        val dependencies =
                            type.getMethod("getDependencies").invoke(parsedHmcl) as Map<*, *>
                        check(dependencies["fabric-loader"] == "0.16.14")
                        val files = type.getMethod("getFiles").invoke(parsedHmcl) as List<*>
                        check(files.size == parsed.requiredFiles)
                        report["hmclParser"] = "passed"
                        report["hmclReferenceSha256"] = digest(reference, "SHA-256")
                    }
            }
            report["sodiumVersion"] = version.id
            report["result"] = "passed"
        }
    } catch (failure: Throwable) {
        report["result"] = "failed"
        report["error"] = failure.toString().take(500)
        throw failure
    } finally {
        writeJson(Path.of(System.getProperty("molifox.mrpack.report")), report)
        println(gson.toJson(report))
    }
}
