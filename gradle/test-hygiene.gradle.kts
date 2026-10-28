// 负责配置测试临时目录、验收摘要和测试数据清理。

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test

// These directories belong to checks only. Application data is never a cleanup target.
val workspaceRoot = rootProject.projectDir.toPath().toAbsolutePath().normalize()
val managedRoot = workspaceRoot.resolve(".work/tests")
val smokeNames =
    setOf(
        "onlineSmoke",
        "contentSmoke",
        "mrpackSmoke",
        "reportedIssuesSmoke",
        "accountSmoke",
        "uiSmoke",
        "reviewSmoke",
        "skinSmoke",
        "storageUiSmoke",
        "iconSmoke",
        "screenshotsSmoke",
        "downloadDetailsSmoke",
        "instanceActionsSmoke",
        "settingsActionsSmoke",
        "downloadActionsSmoke",
        "javaActionsSmoke",
        "accountActionsSmoke",
        "packActionsSmoke",
        "windowActionsSmoke",
        "logActionsSmoke",
        "microsoftActionsSmoke",
        "nativeSmoke",
        "gameSmoke",
    )

fun deleteOwnedTestDirectory(directory: Path) {
    val target = directory.toAbsolutePath().normalize()
    check(target.startsWith(managedRoot) && target != managedRoot) {
        "Refusing to clean outside the test workspace: $target"
    }
    for (parent in listOf(workspaceRoot.resolve(".work"), managedRoot, target)) {
        check(!Files.isSymbolicLink(parent)) {
            "Test workspace must not be a symbolic link: $parent"
        }
    }
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        // walk does not follow links: a link inside a fixture is removed, its target is preserved.
        val failures = mutableListOf<Exception>()
        Files.walk(target).use { paths ->
            for (path in paths.sorted(Comparator.reverseOrder()).toList()) {
                var attempt = 0
                while (true) {
                    try {
                        Files.deleteIfExists(path)
                        break
                    } catch (failure: java.nio.file.FileSystemException) {
                        if (++attempt < 4) Thread.sleep(150L * attempt)
                        else {
                            failures.add(failure)
                            break
                        }
                    }
                }
            }
        }
        if (failures.isNotEmpty()) {
            throw java.io
                .IOException("Some test files are still in use; all other test data was removed")
                .apply {
                    failures.forEach(::addSuppressed)
                }
        }
    }
}

allprojects {
    val owner = this
    afterEvaluate {
        val checkedTasks =
            tasks
                .filter {
                    it is Test || (it is JavaExec && it.name in smokeNames)
                }
                .toList()
        for (checkedTask in checkedTasks) {
            val testDirectory = managedRoot.resolve("${owner.name}-${checkedTask.name}")
            val cleanup =
                owner.tasks.register("${checkedTask.name}Cleanup") {
                    doLast {
                        val status =
                            when {
                                checkedTask.state.failure != null -> "failed"
                                checkedTask.state.upToDate -> "up-to-date (not rerun)"
                                checkedTask.state.skipped -> "skipped (not run)"
                                else -> "passed"
                            }
                        val report =
                            rootProject.file("docs/validation/${owner.name}-${checkedTask.name}.md")
                        var cleanupSucceeded = false
                        try {
                            val screenshots = testDirectory.resolve("screenshots")
                            if (Files.isDirectory(screenshots)) {
                                val destination =
                                    rootProject.file("docs/screenshots/current").toPath()
                                Files.createDirectories(destination)
                                Files.list(screenshots).use { paths ->
                                    paths
                                        .filter {
                                            it.fileName
                                                .toString()
                                                .matches(Regex("MoliFox-[a-z-]+\\.png"))
                                        }
                                        .forEach {
                                            Files.copy(
                                                it,
                                                destination.resolve(it.fileName),
                                                StandardCopyOption.REPLACE_EXISTING,
                                            )
                                        }
                                }
                            }
                        } finally {
                            try {
                                deleteOwnedTestDirectory(testDirectory)
                                cleanupSucceeded = true
                            } finally {
                                report.parentFile.mkdirs()
                                val cleanupStatus =
                                    if (cleanupSucceeded) "removed"
                                    else "cleanup failed; inspect the task error"
                                report.writeText(
                                    "# ${owner.name}:${checkedTask.name}\n\n" +
                                        "- Updated: ${Instant.now()}\n" +
                                        "- Result: $status\n" +
                                        "- Temporary data: $cleanupStatus\n"
                                )
                            }
                        }
                    }
                }
            checkedTask.doFirst {
                // Remove leftovers from an interrupted previous run before starting this one.
                deleteOwnedTestDirectory(testDirectory)
                val temporary = testDirectory.resolve("tmp")
                Files.createDirectories(temporary)
                if (checkedTask is Test) {
                    checkedTask.systemProperty("java.io.tmpdir", temporary.toString())
                    checkedTask.systemProperty("jna.tmpdir", temporary.toString())
                    checkedTask.environment("TEMP", temporary.toString())
                    checkedTask.environment("TMP", temporary.toString())
                }
                if (checkedTask is JavaExec) {
                    checkedTask.systemProperty("java.io.tmpdir", temporary.toString())
                    checkedTask.systemProperty("jna.tmpdir", temporary.toString())
                    checkedTask.systemProperty("molifox.test.dir", testDirectory.toString())
                    checkedTask.environment("TEMP", temporary.toString())
                    checkedTask.environment("TMP", temporary.toString())
                }
            }
            checkedTask.finalizedBy(cleanup)
            rootProject.tasks.named("cleanTestData").configure {
                mustRunAfter(checkedTask, cleanup)
            }
        }
    }
}

tasks.register("cleanTestData") {
    group = "verification"
    description = "Remove project-owned test sandboxes left by an interrupted build"
    doLast {
        if (Files.isDirectory(managedRoot)) {
            Files.list(managedRoot).use { paths -> paths.forEach { deleteOwnedTestDirectory(it) } }
        }
    }
}
