// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 单次归档的写入计数；延迟清单与普通清单共享相同的进度和取消规则。 */
internal class PackArchiveWriter(
    private val zip: ZipOutputStream,
    private val layout: PackExportLayout,
    private val context: JobContext,
) {
    private var written = 0
    private var copiedBytes = 0L
    private var currentFile = ""
    private val totalWork = layout.payload.sumOf { it.second.size }.toDouble() + layout.totalEntries

    suspend fun writeDirectories() {
        for (directory in layout.directories) {
            writeEntry(directory, byteArrayOf())
        }
    }

    suspend fun writeDocuments(documents: List<Pair<String, ByteArray>>) {
        for ((name, bytes) in documents) {
            context.checkCancelled()
            require(bytes.size <= MAX_EXPORT_DOCUMENT_BYTES) {
                I18n.t("packsA1.mrpackExport.documentsTooLarge")
            }
            progress("packsA2.packExport.progressWritingDocument", name)
            writeEntry(name, bytes)
        }
    }

    suspend fun writeIcon() {
        layout.icon?.let { writeEntry(layout.iconPath, it) }
    }

    suspend fun writePayload(copy: suspend (String, MrpackExportFile) -> MrpackExportFile): List<MrpackExportFile> =
        layout.payload.map { (archivePath, file) ->
            context.checkCancelled()
            currentFile = file.path
            progress("packsA2.packExport.progressExportingFile", file.path)
            copy(archivePath, file).also { written++ }
        }

    fun copied(count: Int) {
        copiedBytes += count
        progress("packsA2.packExport.progressExportingFile", currentFile)
    }

    private suspend fun writeEntry(name: String, bytes: ByteArray) {
        context.checkCancelled()
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
        written++
    }

    private fun progress(key: String, name: String) {
        val total = layout.totalEntries
        val fraction = ((copiedBytes + written) / maxOf(1.0, totalWork)).toFloat() * EXPORT_WRITE_PROGRESS_END
        context.progress(fraction, I18n.t(key, name, written + 1, total))
    }
}

private const val EXPORT_WRITE_PROGRESS_END = 0.95f
