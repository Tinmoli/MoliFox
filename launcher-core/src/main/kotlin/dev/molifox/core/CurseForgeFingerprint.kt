// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责CurseForge 模组文件指纹计算。

package dev.molifox.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.file.Files
import java.nio.file.Path

private const val READ_BUFFER_BYTES = 65536
private const val BYTE_BITS = 8
private const val WORD_BITS = 32
private const val MIX_SHIFT = 24
private const val FINAL_MIX_SHIFT_HIGH = 13
private const val FINAL_MIX_SHIFT_LOW = 15
private const val UNSIGNED_INT_MASK = 0xffffffffL

/** CurseForge fingerprint: MurmurHash2, seed 1, excluding ASCII space, tab, CR and LF. */
internal suspend fun curseForgeFingerprint(path: Path): Long {
    var length = 0
    forEachFingerprintByte(path) { length = Math.addExact(length, 1) }
    val multiplier = 0x5bd1e995
    var hash = 1 xor length
    var word = 0
    var shift = 0
    forEachFingerprintByte(path) { value ->
        word = word or (value shl shift)
        shift += BYTE_BITS
        if (shift == WORD_BITS) {
            hash = mixFingerprintWord(hash, word, multiplier)
            word = 0
            shift = 0
        }
    }
    if (shift != 0) hash = (hash xor word) * multiplier
    hash = hash xor (hash ushr FINAL_MIX_SHIFT_HIGH)
    hash *= multiplier
    hash = hash xor (hash ushr FINAL_MIX_SHIFT_LOW)
    return hash.toLong() and UNSIGNED_INT_MASK
}

/** 每凑满一个 32 位字就按 MurmurHash2 的方式混入当前哈希。 */
private fun mixFingerprintWord(
    hash: Int,
    word: Int,
    multiplier: Int,
): Int {
    var mixed = word * multiplier
    mixed = (mixed xor (mixed ushr MIX_SHIFT)) * multiplier
    return hash * multiplier xor mixed
}

/** 逐字节读取文件；每读一块前检查协程是否已取消。 */
private suspend fun forEachFingerprintByte(
    path: Path,
    consume: (Int) -> Unit,
) {
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            consumeFingerprintBytes(buffer, count, consume)
        }
    }
}

/** 只把非空白的 ASCII 字节交给指纹计算。 */
private fun consumeFingerprintBytes(
    buffer: ByteArray,
    count: Int,
    consume: (Int) -> Unit,
) {
    for (index in 0 until count) {
        val value = buffer[index].toInt() and 255
        // 指纹算法要求跳过 ASCII 空白：TAB、LF、CR 与空格。
        val whitespace = value == 9 || value == 10 || value == 13 || value == 32
        if (!whitespace) consume(value)
    }
}
