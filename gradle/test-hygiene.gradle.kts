// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责配置测试临时目录、验收摘要和测试数据清理。

import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

// These directories belong to checks only. Application data is never a cleanup target.
val workspaceRoot =
    rootProject.projectDir
        .toPath()
        .toAbsolutePath()
        .normalize()
val managedRoot = workspaceRoot.resolve("tests/.tmp")
val smokeNames =
    setOf(
        "onlineSmoke",
        "contentSmoke",
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
        "nativePickerSmoke",
        "networkDiagnosticsSmoke",
        "refinementSmoke",
        "recentPlaySmoke",
        "multiplayerUiSmoke",
        "appearanceUiSmoke",
        "gameSmoke",
    )

fun replaceTestScreenshots(source: Path) {
    if (!Files.isDirectory(source)) return
    val generatedName = Regex("MoliFox-[a-z0-9-]+\\.png")
    val screenshots =
        Files.list(source).use { paths ->
            paths.filter { generatedName.matches(it.fileName.toString()) }.toList()
        }
    if (screenshots.isEmpty()) return
    val destination = workspaceRoot.resolve("docs/screenshots/current")
    for (parent in listOf(destination.parent.parent, destination.parent, destination)) {
        check(!Files.isSymbolicLink(parent)) { "截图目录不能是符号链接：$parent" }
    }
    Files.createDirectories(destination)
    // 当前目录只保留本次截图；测试失败也用本次证据替换，避免混入上次成功页面。
    Files.list(destination).use { paths ->
        paths.filter { generatedName.matches(it.fileName.toString()) }.forEach(Files::delete)
    }
    for (screenshot in screenshots) {
        Files.copy(screenshot, destination.resolve(screenshot.fileName))
    }
}

fun deleteOwnedTestDirectory(directory: Path) {
    val target = directory.toAbsolutePath().normalize()
    check(target.startsWith(managedRoot) && target != managedRoot) {
        "Refusing to clean outside the test workspace: $target"
    }
    for (parent in listOf(workspaceRoot.resolve("tests"), managedRoot, target)) {
        check(!Files.isSymbolicLink(parent)) {
            "Test workspace must not be a symbolic link: $parent"
        }
    }
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        // 符号链接与 Windows 目录联接（junction）都只删链接本身：`Files.walk` 会进入 junction 指向
        // 的真实目录，既删不到链接本身（导致「still in use」），又可能碰到测试夹具之外的内容。
        val failures = mutableListOf<Exception>()
        val rootAttributes =
            runCatching {
                Files.readAttributes(target, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
        val deleteWithRetry = { path: java.nio.file.Path ->
            var attempt = 0
            while (true) {
                try {
                    Files.deleteIfExists(path)
                    break
                } catch (failure: java.nio.file.FileSystemException) {
                    if (++attempt < 4) {
                        Thread.sleep(150L * attempt)
                    } else {
                        failures.add(failure)
                        break
                    }
                }
            }
        }
        if (rootAttributes == null || rootAttributes.isSymbolicLink || !rootAttributes.isDirectory) {
            deleteWithRetry(target)
        } else {
            Files.walkFileTree(
                target,
                object : SimpleFileVisitor<java.nio.file.Path>() {
                    override fun preVisitDirectory(
                        dir: java.nio.file.Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        // 普通目录的 isOther 恒为 false；junction / mount point 为 true。
                        if (dir != target && (attributes.isSymbolicLink || attributes.isOther)) {
                            deleteWithRetry(dir)
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: java.nio.file.Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        deleteWithRetry(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: java.nio.file.Path,
                        failure: java.io.IOException,
                    ): FileVisitResult {
                        if (failure is java.nio.file.NoSuchFileException) {
                            return FileVisitResult.CONTINUE
                        }
                        failures.add(failure)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        failure: java.io.IOException?,
                    ): FileVisitResult {
                        if (failure != null) failures.add(failure)
                        deleteWithRetry(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
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
                }.toList()
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
                            rootProject.file(
                                "build/validation/${owner.name}-${checkedTask.name}.md",
                            )
                        var cleanupSucceeded = false
                        try {
                            replaceTestScreenshots(testDirectory.resolve("screenshots"))
                        } finally {
                            try {
                                deleteOwnedTestDirectory(testDirectory)
                                cleanupSucceeded = true
                            } finally {
                                report.parentFile.mkdirs()
                                val cleanupStatus =
                                    if (cleanupSucceeded) {
                                        "removed"
                                    } else {
                                        "cleanup failed; inspect the task error"
                                    }
                                report.writeText(
                                    "# ${owner.name}:${checkedTask.name}\n\n" +
                                        "- Updated: ${Instant.now()}\n" +
                                        "- Result: $status\n" +
                                        "- Temporary data: $cleanupStatus\n",
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
                    checkedTask.systemProperty("molifox.test.dir", testDirectory.toString())
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

tasks.register("verifyLocalTests") {
    group = "verification"
    description = "Run all local test suites and require a result from every module"
    val suites =
        mapOf(
            ":launcher-core" to "test",
            ":desktop-app" to "test",
            ":multiplayer-core" to "jvmTest",
            ":launcher-bootstrap" to "test",
        )
    dependsOn(suites.map { (module, suite) -> "$module:$suite" })
    doLast {
        val executedSuite = Regex("""<testsuite\b[^>]*\btests="[1-9]\d*"""")
        for ((module, suite) in suites) {
            val test = project(module).tasks.named<Test>(suite).get()
            val xml = test.reports.junitXml
            val results = xml.outputLocation.get().asFile
            val hasTests =
                results.listFiles().orEmpty().any { report ->
                    report.name.startsWith("TEST-") && report.extension == "xml" &&
                        report.bufferedReader().useLines { lines -> lines.take(5).any(executedSuite::containsMatchIn) }
                }
            check(!test.state.noSource && hasTests) {
                "No results for ${test.path}. Restore the matching local tests/ before verifying a release."
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
