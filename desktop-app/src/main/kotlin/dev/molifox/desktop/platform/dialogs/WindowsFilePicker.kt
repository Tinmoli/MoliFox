// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责通过 Windows Common Item Dialog 选择文件、目录和保存位置。

package dev.molifox.desktop.platform.dialogs

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid.GUID
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import dev.molifox.desktop.tr

internal object WindowsFilePicker {
    private interface ShellItems : StdCallLibrary {
        // JNA 按方法名查找 Windows 导出符号，必须保留系统定义的大小写。
        @Suppress("ktlint:standard:function-naming", "FunctionNaming")
        fun SHCreateItemFromParsingName(
            path: WString,
            context: Pointer?,
            iid: GUID,
            result: PointerByReference,
        ): Int
    }

    private const val CANCELLED = -2147023673 // HRESULT_FROM_WIN32(ERROR_CANCELLED)
    private const val FILE_SYSTEM_PATH = -2147123200 // SIGDN_FILESYSPATH
    private const val FORCE_FILESYSTEM = 0x40
    private const val NO_CHANGE_DIRECTORY = 0x8
    private const val PATH_MUST_EXIST = 0x800
    private const val FILE_MUST_EXIST = 0x1000
    private const val PICK_FOLDERS = 0x20
    private const val ALLOW_MULTISELECT = 0x200
    private const val OVERWRITE_PROMPT = 0x2
    private const val SAVE_DIALOG_CLSID = "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}"
    private const val OPEN_DIALOG_CLSID = "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"
    private const val SAVE_DIALOG_IID = "{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}"
    private const val OPEN_DIALOG_IID = "{D57C7288-D4AD-4768-BE02-9D969532D960}"
    private const val SHELL_ITEM_IID = "{43826D1E-E718-42EE-BC55-A1E261C37BFE}"

    // COM 虚表槽位随接口声明顺序固定，改动会让 Windows 调到错误的函数。
    private const val DIALOG_SLOT_SHOW = 3
    private const val DIALOG_SLOT_SET_FILE_TYPES = 4
    private const val DIALOG_SLOT_SET_FILE_TYPE_INDEX = 5
    private const val DIALOG_SLOT_SET_OPTIONS = 9
    private const val DIALOG_SLOT_SET_FOLDER = 12
    private const val DIALOG_SLOT_SET_FILE_NAME = 15
    private const val DIALOG_SLOT_SET_TITLE = 17
    private const val DIALOG_SLOT_GET_RESULT = 20
    private const val DIALOG_SLOT_SET_DEFAULT_EXTENSION = 22
    private const val DIALOG_SLOT_GET_RESULTS = 27
    private const val SHELL_ITEM_SLOT_GET_DISPLAY_NAME = 5
    private const val ITEM_ARRAY_SLOT_GET_COUNT = 7
    private const val ITEM_ARRAY_SLOT_GET_ITEM_AT = 8

    // COMDLG_FILTERSPEC 是「说明 + 通配符」两对指针，共 4 个指针槽位。
    private const val FILTER_SPEC_POINTER_COUNT = 4
    private const val ALL_FILES_LABEL_INDEX = 2
    private const val ALL_FILES_PATTERN_INDEX = 3

    fun pick(
        request: PickRequest,
        owner: Pointer?,
    ): List<java.nio.file.Path> {
        val ole = Ole32.INSTANCE
        checkResult(ole.CoInitializeEx(null, 2).toInt())
        try {
            val saving = request.mode == PickMode.Save
            val dialog = createDialog(ole, saving)
            try {
                applyRequest(dialog, request, saving)
                val buffers = applyFilters(dialog, request, saving)
                val result = dialog.invoke(DIALOG_SLOT_SHOW, owner)
                if (result == CANCELLED) return emptyList()
                checkResult(result)
                return readSelection(dialog, saving).also {
                    java.lang.ref.Reference
                        .reachabilityFence(buffers)
                }
            } finally {
                dialog.close()
            }
        } finally {
            ole.CoUninitialize()
        }
    }

    /** 创建打开或保存窗口；两个 CLSID/IID 对决定最终使用的对话框实现。 */
    private fun createDialog(
        ole: Ole32,
        saving: Boolean,
    ): ComPointer {
        val output = PointerByReference()
        checkResult(
            ole
                .CoCreateInstance(
                    GUID(if (saving) SAVE_DIALOG_CLSID else OPEN_DIALOG_CLSID),
                    null,
                    1,
                    GUID(if (saving) SAVE_DIALOG_IID else OPEN_DIALOG_IID),
                    output,
                ).toInt(),
        )
        return ComPointer(output.value)
    }

    /** 依次写入选项、标题、初始目录与默认文件名；顺序与原对话框配置一致。 */
    private fun applyRequest(
        dialog: ComPointer,
        request: PickRequest,
        saving: Boolean,
    ) {
        dialog.checked(DIALOG_SLOT_SET_OPTIONS, buildOptions(request, saving))
        dialog.checked(DIALOG_SLOT_SET_TITLE, WString(request.title))
        request.initialDirectory
            ?.takeIf {
                java.nio.file.Files
                    .isDirectory(it)
            }?.let { directory -> applyInitialDirectory(dialog, directory) }
        if (request.defaultName.isNotBlank()) {
            dialog.checked(DIALOG_SLOT_SET_FILE_NAME, WString(request.defaultName))
        }
    }

    private fun buildOptions(
        request: PickRequest,
        saving: Boolean,
    ): Int {
        var options = FORCE_FILESYSTEM or NO_CHANGE_DIRECTORY or PATH_MUST_EXIST
        options =
            options or
            when (request.mode) {
                PickMode.Save -> OVERWRITE_PROMPT
                PickMode.Directory -> PICK_FOLDERS
                PickMode.File -> FILE_MUST_EXIST
            }
        if (request.multiple && !saving) options = options or ALLOW_MULTISELECT
        return options
    }

    /** 把初始目录交给窗口；创建 Shell 项失败时保持窗口默认目录，不打断选择流程。 */
    private fun applyInitialDirectory(
        dialog: ComPointer,
        directory: java.nio.file.Path,
    ) {
        val item = PointerByReference()
        val code =
            Native
                .load("shell32", ShellItems::class.java)
                .SHCreateItemFromParsingName(
                    WString(directory.toAbsolutePath().toString()),
                    null,
                    GUID(SHELL_ITEM_IID),
                    item,
                )
        if (code >= 0) {
            ComPointer(item.value).use { dialog.checked(DIALOG_SLOT_SET_FOLDER, it.pointer) }
        }
    }

    /**
     * 设置文件类型过滤，并返回必须在 Show 返回前保持存活的本地缓冲区。
     *
     * COMDLG_FILTERSPEC holds two UTF-16 pointers; retain the native buffers until Show returns.
     */
    private fun applyFilters(
        dialog: ComPointer,
        request: PickRequest,
        saving: Boolean,
    ): MutableList<Memory> {
        val extensions = request.extensions.filter { Regex("[A-Za-z0-9]+").matches(it) }
        val buffers = mutableListOf<Memory>()
        if (extensions.isEmpty() || request.mode == PickMode.Directory) return buffers

        fun pointerOffset(index: Int): Long = index.toLong() * Native.POINTER_SIZE

        fun wide(value: String): Memory =
            Memory((value.length + 1L) * Native.WCHAR_SIZE).also {
                it.setWideString(0, value)
                buffers.add(it)
            }
        val patterns = extensions.joinToString(";") { "*.$it" }
        val filters = Memory(FILTER_SPEC_POINTER_COUNT.toLong() * Native.POINTER_SIZE)
        filters.setPointer(0, wide(tr("pages.picker.files", patterns)))
        filters.setPointer(pointerOffset(1), wide(patterns))
        filters.setPointer(pointerOffset(ALL_FILES_LABEL_INDEX), wide(tr("pages.picker.allFiles")))
        filters.setPointer(pointerOffset(ALL_FILES_PATTERN_INDEX), wide("*.*"))
        buffers.add(filters)
        dialog.checked(DIALOG_SLOT_SET_FILE_TYPES, 2, filters)
        dialog.checked(DIALOG_SLOT_SET_FILE_TYPE_INDEX, 1)
        if (saving) dialog.checked(DIALOG_SLOT_SET_DEFAULT_EXTENSION, WString(extensions.first()))
        return buffers
    }

    /** 读取用户确认的路径：保存模式取单个结果，打开模式读取全部选中项。 */
    private fun readSelection(
        dialog: ComPointer,
        saving: Boolean,
    ): List<java.nio.file.Path> =
        if (saving) {
            val item = PointerByReference()
            dialog.checked(DIALOG_SLOT_GET_RESULT, item)
            ComPointer(item.value).use { listOf(it.filePath()) }
        } else {
            val items = PointerByReference()
            dialog.checked(DIALOG_SLOT_GET_RESULTS, items)
            ComPointer(items.value).use { array ->
                val count = IntByReference()
                array.checked(ITEM_ARRAY_SLOT_GET_COUNT, count)
                (0 until count.value).map { index ->
                    val item = PointerByReference()
                    array.checked(ITEM_ARRAY_SLOT_GET_ITEM_AT, index, item)
                    ComPointer(item.value).use { it.filePath() }
                }
            }
        }

    private fun checkResult(value: Int) {
        check(value >= 0) {
            tr("deskI.picker.windowsFailed", Integer.toHexString(value))
        }
    }

    private class ComPointer(
        val pointer: Pointer,
    ) : AutoCloseable {
        fun invoke(
            slot: Int,
            vararg args: Any?,
        ): Int {
            val function = pointer.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)
            return Function
                .getFunction(function, Function.ALT_CONVENTION)
                .invokeInt(arrayOf(pointer, *args))
        }

        fun checked(
            slot: Int,
            vararg args: Any?,
        ) = checkResult(invoke(slot, *args))

        fun filePath(): java.nio.file.Path {
            val name = PointerByReference()
            checked(SHELL_ITEM_SLOT_GET_DISPLAY_NAME, FILE_SYSTEM_PATH, name)
            try {
                return java.nio.file.Path
                    .of(name.value.getWideString(0))
                    .toAbsolutePath()
                    .normalize()
            } finally {
                Ole32.INSTANCE.CoTaskMemFree(name.value)
            }
        }

        override fun close() {
            invoke(2)
        }
    }
}
