// 负责通过 Windows Common Item Dialog 选择文件、目录和保存位置。

package dev.molifox.desktop

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

internal object WindowsFilePicker {
    private interface ShellItems : StdCallLibrary {
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

    fun pick(request: PickRequest, owner: Pointer?): List<java.nio.file.Path> {
        val ole = Ole32.INSTANCE
        checkResult(ole.CoInitializeEx(null, 2).toInt())
        try {
            val saving = request.mode == PickMode.Save
            val output = PointerByReference()
            checkResult(
                ole.CoCreateInstance(
                        GUID(
                            if (saving) "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}"
                            else "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"
                        ),
                        null,
                        1,
                        GUID(
                            if (saving) "{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}"
                            else "{D57C7288-D4AD-4768-BE02-9D969532D960}"
                        ),
                        output,
                    )
                    .toInt()
            )
            val dialog = ComPointer(output.value)
            try {
                var options = FORCE_FILESYSTEM or NO_CHANGE_DIRECTORY or PATH_MUST_EXIST
                options =
                    options or
                        when (request.mode) {
                            PickMode.Save -> OVERWRITE_PROMPT
                            PickMode.Directory -> PICK_FOLDERS
                            PickMode.File -> FILE_MUST_EXIST
                        }
                if (request.multiple && !saving) options = options or ALLOW_MULTISELECT
                dialog.checked(9, options)
                dialog.checked(17, WString(request.title))
                request.initialDirectory
                    ?.takeIf { java.nio.file.Files.isDirectory(it) }
                    ?.let { directory ->
                        val item = PointerByReference()
                        val code =
                            Native.load("shell32", ShellItems::class.java)
                                .SHCreateItemFromParsingName(
                                    WString(directory.toAbsolutePath().toString()),
                                    null,
                                    GUID("{43826D1E-E718-42EE-BC55-A1E261C37BFE}"),
                                    item,
                                )
                        if (code >= 0) ComPointer(item.value).use { dialog.checked(12, it.pointer) }
                    }
                if (request.defaultName.isNotBlank())
                    dialog.checked(15, WString(request.defaultName))
                val extensions = request.extensions.filter { Regex("[A-Za-z0-9]+").matches(it) }
                // COMDLG_FILTERSPEC holds two UTF-16 pointers; retain the native buffers until Show
                // returns.
                val buffers = mutableListOf<Memory>()
                if (extensions.isNotEmpty() && request.mode != PickMode.Directory) {
                    fun wide(value: String): Memory =
                        Memory((value.length + 1L) * Native.WCHAR_SIZE).also {
                            it.setWideString(0, value)
                            buffers.add(it)
                        }
                    val patterns = extensions.joinToString(";") { "*.$it" }
                    val filters = Memory(4L * Native.POINTER_SIZE)
                    filters.setPointer(0, wide("文件 ($patterns)"))
                    filters.setPointer(Native.POINTER_SIZE.toLong(), wide(patterns))
                    filters.setPointer(2L * Native.POINTER_SIZE, wide("所有文件 (*.*)"))
                    filters.setPointer(3L * Native.POINTER_SIZE, wide("*.*"))
                    buffers.add(filters)
                    dialog.checked(4, 2, filters)
                    dialog.checked(5, 1)
                    if (saving) dialog.checked(22, WString(extensions.first()))
                }
                val result = dialog.invoke(3, owner)
                if (result == CANCELLED) return emptyList()
                checkResult(result)
                return if (saving) {
                        val item = PointerByReference()
                        dialog.checked(20, item)
                        ComPointer(item.value).use { listOf(it.filePath()) }
                    } else {
                        val items = PointerByReference()
                        dialog.checked(27, items)
                        ComPointer(items.value).use { array ->
                            val count = IntByReference()
                            array.checked(7, count)
                            (0 until count.value).map { index ->
                                val item = PointerByReference()
                                array.checked(8, index, item)
                                ComPointer(item.value).use { it.filePath() }
                            }
                        }
                    }
                    .also { java.lang.ref.Reference.reachabilityFence(buffers) }
            } finally {
                dialog.close()
            }
        } finally {
            ole.CoUninitialize()
        }
    }

    private fun checkResult(value: Int) {
        check(value >= 0) { "Windows 文件窗口调用失败（HRESULT 0x${Integer.toHexString(value)}）" }
    }

    private class ComPointer(val pointer: Pointer) : AutoCloseable {
        fun invoke(slot: Int, vararg args: Any?): Int {
            val function = pointer.getPointer(0).getPointer(slot.toLong() * Native.POINTER_SIZE)
            return Function.getFunction(function, Function.ALT_CONVENTION)
                .invokeInt(arrayOf(pointer, *args))
        }

        fun checked(slot: Int, vararg args: Any?) = checkResult(invoke(slot, *args))

        fun filePath(): java.nio.file.Path {
            val name = PointerByReference()
            checked(5, FILE_SYSTEM_PATH, name)
            try {
                return java.nio.file.Path.of(name.value.getWideString(0))
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
