// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责为测试构造 NBT 二进制样例。

package dev.molifox.core

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 测试用的最小 NBT 写入器：只覆盖解析器需要的标签，方法名与 NBT 标签号一一对应。
 *
 * 列表元素没有名字，所以复合标签元素用 [compoundElement]，其余原样写标签值即可。
 */
internal class TestNbtWriter {
    private val out = ByteArrayOutputStream()

    fun root(block: TestNbtWriter.() -> Unit): ByteArray {
        header(10, "")
        block()
        u8(0)
        return out.toByteArray()
    }

    fun byte(
        name: String,
        value: Int,
    ) {
        header(1, name)
        u8(value)
    }

    fun short(
        name: String,
        value: Int,
    ) {
        header(2, name)
        u16(value)
    }

    fun int(
        name: String,
        value: Int,
    ) {
        header(3, name)
        i32(value)
    }

    fun long(
        name: String,
        value: Long,
    ) {
        header(4, name)
        i64(value)
    }

    fun float(
        name: String,
        value: Float,
    ) {
        header(5, name)
        i32(value.toRawBits())
    }

    fun double(
        name: String,
        value: Double,
    ) {
        header(6, name)
        i64(value.toRawBits())
    }

    fun byteArray(
        name: String,
        value: ByteArray,
    ) {
        header(7, name)
        i32(value.size)
        out.write(value)
    }

    fun string(
        name: String,
        value: String,
    ) {
        header(8, name)
        text(value)
    }

    /** 元素类型与元素个数由调用方给出，`block` 里写 [count] 个裸元素。 */
    fun list(
        name: String,
        elementType: Int,
        count: Int,
        block: TestNbtWriter.() -> Unit,
    ) {
        header(9, name)
        u8(elementType)
        i32(count)
        block()
    }

    fun nested(
        name: String,
        block: TestNbtWriter.() -> Unit,
    ) {
        header(10, name)
        block()
        u8(0)
    }

    fun intArray(
        name: String,
        value: IntArray,
    ) {
        header(11, name)
        i32(value.size)
        value.forEach(::i32)
    }

    fun longArray(
        name: String,
        value: LongArray,
    ) {
        header(12, name)
        i32(value.size)
        value.forEach(::i64)
    }

    /** 列表元素没有名字：直接写修改版 UTF-8 文本，供字符串列表使用。 */
    fun stringElement(value: String) {
        text(value)
    }

    fun compoundElement(block: TestNbtWriter.() -> Unit) {
        block()
        u8(0)
    }

    private fun header(
        type: Int,
        name: String,
    ) {
        u8(type)
        text(name)
    }

    private fun text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        u16(bytes.size)
        out.write(bytes)
    }

    private fun u8(value: Int) = out.write(value and 0xff)

    private fun u16(value: Int) {
        u8(value ushr 8)
        u8(value)
    }

    private fun i32(value: Int) {
        u16(value ushr 16)
        u16(value)
    }

    private fun i64(value: Long) {
        i32((value ushr 32).toInt())
        i32(value.toInt())
    }
}

/** 把一段 NBT 字节压成 gzip，模拟游戏写出的 `servers.dat`。 */
internal fun gzipBytes(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}
