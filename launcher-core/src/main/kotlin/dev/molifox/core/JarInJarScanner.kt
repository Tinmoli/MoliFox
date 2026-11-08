// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责有界、可取消的内嵌 JAR 递归扫描：只把条目流式复制到临时文件，从不写入实例目录。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.io.path.deleteIfExists

private const val DEFAULT_MAX_DEPTH = 5
private const val DEFAULT_MAX_NODES = 512
private const val DEFAULT_MAX_ENTRY_MIB = 256
private const val DEFAULT_MAX_HOST_MIB = 512
private const val DEFAULT_MAX_SCAN_MIB = 2048
private const val DEFAULT_MAX_ARCHIVE_ENTRIES = 20_000
private const val DEFAULT_MAX_MILLIS = 60_000L
private const val BYTES_PER_MIB = 1024L * 1024
private const val NANOS_PER_MILLI = 1_000_000L

/** 扫描上限；测试会注入更小的值，线上使用默认值。 */
internal data class JarInJarLimits(
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxNodes: Int = DEFAULT_MAX_NODES,
    val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_MIB * BYTES_PER_MIB,
    val maxHostBytes: Long = DEFAULT_MAX_HOST_MIB * BYTES_PER_MIB,
    val maxScanBytes: Long = DEFAULT_MAX_SCAN_MIB * BYTES_PER_MIB,
    val maxArchiveEntries: Int = DEFAULT_MAX_ARCHIVE_ENTRIES,
    val maxMillis: Long = DEFAULT_MAX_MILLIS,
)

/** 一次列表扫描共享的总预算；单个宿主在 [resetHost] 后重新计算自己的上限。 */
internal class JarInJarScanBudget(
    private val limits: JarInJarLimits,
) {
    private val startedAt = System.nanoTime()
    private var remainingBytes = limits.maxScanBytes
    private var hostBytes = 0L
    private var nodes = 0
    private var hostComplete = true
    private var hostNote = ""
    private var globalNote = ""

    /** 任一宿主失败或超限后为 false；报告据此抑制缺失类结论。 */
    var complete: Boolean = true
        private set

    val hostFailureText: String
        get() = hostNote

    /** 本次扫描里第一条失败原因；报告用它说明为什么结论不完整。 */
    val failureText: String
        get() = globalNote

    val hostUsable: Boolean
        get() = hostComplete

    fun resetHost() {
        hostBytes = 0
        nodes = 0
        hostComplete = true
        hostNote = ""
    }

    fun hostFailure(reason: String) {
        hostComplete = false
        complete = false
        if (hostNote.isBlank()) hostNote = reason
        if (globalNote.isBlank()) globalNote = reason
    }

    fun timedOut(): Boolean = (System.nanoTime() - startedAt) / NANOS_PER_MILLI > limits.maxMillis

    fun canAddNode(): Boolean = nodes < limits.maxNodes

    fun addNode() {
        nodes++
    }

    /** 解压前先按声明大小扣预算；不信任压缩包给出的实际大小，复制时还会按实际字节再扣一次。 */
    fun canCopy(declaredSize: Long): Boolean {
        if (declaredSize > limits.maxEntryBytes) return false
        if (declaredSize < 0) return true
        return hostBytes + declaredSize <= limits.maxHostBytes && declaredSize <= remainingBytes
    }

    fun addBytes(count: Long): Boolean {
        hostBytes += count
        remainingBytes -= count
        return hostBytes <= limits.maxHostBytes && remainingBytes >= 0
    }

    fun addArchiveEntries(count: Int): Boolean = count <= limits.maxArchiveEntries
}

internal data class JarInJarHostScan(
    val declaration: JarDeclaration,
    val embedded: List<EmbeddedJar>,
    val complete: Boolean,
    val note: String,
)

/** 只读扫描器：每个内嵌条目复制到临时文件、解析完立刻删除，实例目录不留任何文件。 */
internal class JarInJarScanner(
    temporaryDirectory: Path,
    private val limits: JarInJarLimits = JarInJarLimits(),
) {
    private val copier = JarInJarArchiveCopier(temporaryDirectory)

    fun scan(
        host: Path,
        preferred: JarInJarLoader,
        isCancelled: () -> Boolean,
        budget: JarInJarScanBudget,
    ): JarInJarHostScan {
        budget.resetHost()
        if (!Files.isRegularFile(host, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(host)) {
            return failedScan(budget, I18n.t("diagA2.jarInJarScanner.modUnreadable"))
        }
        return try {
            ZipFile(host.toFile()).use { zip ->
                readHost(zip, preferred, isCancelled, budget)
            }
        } catch (failure: ZipException) {
            failedScan(budget, describeFailure(I18n.t("diagA2.jarInJarScanner.invalidJar"), failure))
        } catch (failure: IOException) {
            failedScan(budget, describeFailure(I18n.t("diagA2.jarInJarScanner.modFileUnreadable"), failure))
        }
    }

    private fun readHost(
        zip: ZipFile,
        preferred: JarInJarLoader,
        isCancelled: () -> Boolean,
        budget: JarInJarScanBudget,
    ): JarInJarHostScan {
        val declaration = JarInJarDeclarations.read(zip, preferred)
        val embedded =
            if (declaration.embedded.isEmpty() || isCancelled()) {
                emptyList()
            } else {
                resolve(zip, declaration.embedded, 1, preferred, isCancelled, budget)
            }
        val notes = listOf(declaration.note, budget.hostFailureText).filter { it.isNotBlank() }
        return JarInJarHostScan(
            declaration = declaration,
            embedded = embedded,
            complete = declaration.complete && budget.hostUsable,
            note = notes.joinToString("；"),
        )
    }

    private fun resolve(
        zip: ZipFile,
        declarations: List<EmbeddedDeclaration>,
        depth: Int,
        preferred: JarInJarLoader,
        isCancelled: () -> Boolean,
        budget: JarInJarScanBudget,
    ): List<EmbeddedJar> {
        val nodes = mutableListOf<EmbeddedJar>()
        for (declaration in declarations) {
            val limitReason =
                when {
                    isCancelled() -> I18n.t("diagA2.jarInJarScanner.scanCancelled")
                    budget.timedOut() -> I18n.t("diagA2.jarInJarScanner.scanTimedOut")
                    !budget.canAddNode() -> I18n.t("diagA2.jarInJarScanner.tooManyNodes")
                    else -> null
                }
            if (limitReason != null) {
                budget.hostFailure(limitReason)
                break
            }
            if (!isSafeEmbeddedPath(declaration.path)) {
                budget.hostFailure(I18n.t("diagA2.jarInJarScanner.unsafeEmbeddedPath"))
            } else {
                // 先扣节点预算再读出内容：子节点会在递归中优先被读出，事后计数会漏掉嵌套层级。
                budget.addNode()
                val node = readNodeSafely(zip, declaration, depth, preferred, isCancelled, budget)
                if (node != null) nodes += node
            }
        }
        return nodes
    }

    private fun readNode(
        parent: ZipFile,
        declaration: EmbeddedDeclaration,
        depth: Int,
        preferred: JarInJarLoader,
        isCancelled: () -> Boolean,
        budget: JarInJarScanBudget,
    ): EmbeddedJar? {
        val entry = parent.getEntry(declaration.path)
        if (entry == null || entry.isDirectory) {
            budget.hostFailure(I18n.t("diagA2.jarInJarScanner.embeddedMissing"))
            return null
        }
        if (!budget.canCopy(entry.size)) {
            budget.hostFailure(I18n.t("diagA2.jarInJarScanner.embeddedOverBudget"))
            return null
        }
        val temporary = copier.createTemporaryFile()
        return try {
            if (!copier.copyToTemporary(parent, entry, temporary, isCancelled, budget)) {
                null
            } else {
                openNode(temporary, declaration, depth, preferred, isCancelled, budget)
            }
        } finally {
            temporary.deleteIfExists()
        }
    }

    private fun readNodeSafely(
        zip: ZipFile,
        declaration: EmbeddedDeclaration,
        depth: Int,
        preferred: JarInJarLoader,
        isCancelled: () -> Boolean,
        budget: JarInJarScanBudget,
    ): EmbeddedJar? =
        try {
            readNode(zip, declaration, depth, preferred, isCancelled, budget)
        } catch (failure: ZipException) {
            budget.hostFailure(describeFailure(I18n.t("diagA2.jarInJarScanner.embeddedParseFailed"), failure))
            null
        } catch (failure: IOException) {
            budget.hostFailure(describeFailure(I18n.t("diagA2.jarInJarScanner.embeddedUnreadable"), failure))
            null
        }

    private fun openNode(
        temporary: Path,
        declaration: EmbeddedDeclaration,
        depth: Int,
        preferred: JarInJarLoader,
        isCancelled: () -> Boolean,
        budget: JarInJarScanBudget,
    ): EmbeddedJar? =
        ZipFile(temporary.toFile()).use { zip ->
            if (!budget.addArchiveEntries(copier.countEntries(zip, limits.maxArchiveEntries + 1))) {
                budget.hostFailure(I18n.t("diagA2.jarInJarScanner.embeddedTooManyEntries"))
                return@use null
            }
            val child = JarInJarDeclarations.read(zip, preferred)
            val children =
                when {
                    child.embedded.isEmpty() -> emptyList()
                    depth >= limits.maxDepth -> {
                        budget.hostFailure(I18n.t("diagA2.jarInJarScanner.depthExceeded"))
                        emptyList()
                    }
                    else -> resolve(zip, child.embedded, depth + 1, preferred, isCancelled, budget)
                }
            buildNode(declaration, child, children, depth)
        }

    private fun buildNode(
        declaration: EmbeddedDeclaration,
        child: JarDeclaration,
        children: List<EmbeddedJar>,
        depth: Int,
    ): EmbeddedJar {
        val primary = child.mods.firstOrNull()
        val siblings = child.mods.drop(1).map { JarInJarProvided(it.id, it.version) }
        val targetMc =
            child.mods
                .firstOrNull { it.targetMc.isNotBlank() }
                ?.targetMc
                .orEmpty()
        return EmbeddedJar(
            path = declaration.path,
            depth = depth,
            loader = child.loader,
            modId = primary?.id.orEmpty(),
            name = primary?.name.orEmpty(),
            version = primary?.version.orEmpty(),
            targetMc = targetMc,
            dependencies = child.mods.flatMap { it.dependencies }.distinct(),
            conflicts = child.mods.flatMap { it.conflicts }.distinct(),
            provides = child.mods.flatMap { it.provides } + siblings,
            identifier = declaration.identifier,
            versionRange = declaration.versionRange,
            artifactVersion = declaration.artifactVersion,
            children = children,
            complete = child.complete,
        )
    }

    private fun failedScan(
        budget: JarInJarScanBudget,
        note: String,
    ): JarInJarHostScan {
        budget.hostFailure(note)
        return JarInJarHostScan(
            declaration = JarDeclaration(JarInJarLoader.Unknown, complete = false, note = note),
            embedded = emptyList(),
            complete = false,
            note = budget.hostFailureText.ifBlank { note },
        )
    }
}
