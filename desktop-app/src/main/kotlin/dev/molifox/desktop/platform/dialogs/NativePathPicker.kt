// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责串行调用系统路径选择器，并将取消和多选结果统一为绝对路径。

package dev.molifox.desktop.platform.dialogs

import com.sun.jna.Native
import dev.molifox.desktop.tr
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Window
import java.nio.file.Path
import java.util.concurrent.Executors

object NativePathPicker {
    private val mutex = Mutex()
    private val dialogThread =
        Executors
            .newSingleThreadExecutor { task ->
                Thread(task, "MoliFox-native-picker").apply { isDaemon = true }
            }.asCoroutineDispatcher()

    suspend fun pick(
        request: PickRequest,
        owner: Window?,
    ): List<Path> =
        mutex.withLock {
            val ownerPointer =
                if (System.getProperty("os.name").startsWith("Windows", true) && owner != null) {
                    Native.getComponentPointer(owner)
                } else {
                    null
                }
            withContext(dialogThread) {
                if (System.getProperty("os.name").startsWith("Windows", true)) {
                    WindowsFilePicker.pick(request, ownerPointer)
                } else {
                    LinuxFilePicker.pick(request)
                }
            }
        }
}

internal object LinuxFilePicker {
    fun pick(request: PickRequest): List<Path> {
        val kde = System.getenv("XDG_CURRENT_DESKTOP").orEmpty().contains("KDE", true)
        val candidates = if (kde) listOf("kdialog", "zenity") else listOf("zenity", "kdialog")
        for (tool in candidates) {
            val process =
                try {
                    ProcessBuilder(arguments(tool, request)).start()
                } catch (_: java.io.IOException) {
                    continue
                }
            try {
                val errors = StringBuilder()
                val drain =
                    Thread {
                        process.errorStream.bufferedReader().use { errors.append(it.readText()) }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exit = process.waitFor()
                drain.join()
                if (exit == 1) return emptyList()
                check(exit == 0) { tr("pages.picker.failed", tool, exit) }
                return output
                    .trimEnd('\r', '\n')
                    .takeIf { it.isNotEmpty() }
                    ?.split(if (tool == "zenity") "\u001f" else "\n")
                    ?.map { Path.of(it).toAbsolutePath().normalize() }
                    .orEmpty()
            } finally {
                if (process.isAlive) process.destroy()
            }
        }
        error(tr("pages.picker.notFound"))
    }

    internal fun arguments(
        tool: String,
        request: PickRequest,
    ): List<String> {
        val start = startPath(request)
        val patterns = filePatterns(request)
        return buildList {
            add(tool)
            if (tool == "zenity") {
                addAll(zenityArguments(request, start, patterns))
            } else {
                addAll(kdialogArguments(request, start, patterns))
            }
        }
    }

    private fun startPath(request: PickRequest): String {
        val initial = request.initialDirectory
        if (initial != null) return initial.toString().trimEnd('/') + "/" + request.defaultName
        return request.defaultName.ifBlank { System.getProperty("user.home") + "/" }
    }

    private fun filePatterns(request: PickRequest): String =
        request.extensions
            .filter { Regex("[A-Za-z0-9]+").matches(it) }
            .joinToString(" ") { "*.$it" }

    private fun zenityArguments(
        request: PickRequest,
        start: String,
        patterns: String,
    ): List<String> =
        buildList {
            addAll(listOf("--file-selection", "--title=${request.title}", "--filename=$start"))
            if (request.mode == PickMode.Directory) add("--directory")
            if (request.mode == PickMode.Save) addAll(listOf("--save", "--confirm-overwrite"))
            if (request.multiple && request.mode != PickMode.Save) {
                addAll(listOf("--multiple", "--separator=\u001f"))
            }
            if (patterns.isNotEmpty() && request.mode != PickMode.Directory) {
                add("--file-filter=" + tr("pages.picker.fileLabel") + " | $patterns")
            }
            add("--file-filter=" + tr("pages.picker.allFilesLabel") + " | *")
        }

    private fun kdialogArguments(
        request: PickRequest,
        start: String,
        patterns: String,
    ): List<String> =
        buildList {
            addAll(listOf("--title", request.title))
            add(
                when (request.mode) {
                    PickMode.Directory -> "--getexistingdirectory"
                    PickMode.Save -> "--getsavefilename"
                    else -> "--getopenfilename"
                },
            )
            add(start)
            if (request.mode != PickMode.Directory) {
                add(
                    if (patterns.isEmpty()) {
                        "*"
                    } else {
                        "$patterns|" +
                            tr("pages.picker.fileLabel") +
                            "\n*|" +
                            tr("pages.picker.allFilesLabel")
                    },
                )
            }
            if (request.multiple && request.mode != PickMode.Save) {
                addAll(listOf("--multiple", "--separate-output"))
            }
        }
}
