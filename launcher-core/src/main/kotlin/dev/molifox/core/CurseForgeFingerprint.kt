// 负责CurseForge 模组文件指纹计算。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** CurseForge fingerprint: MurmurHash2, seed 1, excluding ASCII space, tab, CR and LF. */
internal suspend fun curseForgeFingerprint(path: Path): Long {
    var length = 0
    suspend fun filtered(consume: (Int) -> Unit) {
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    val value = buffer[index].toInt() and 255
                    if (value != 9 && value != 10 && value != 13 && value != 32) consume(value)
                }
            }
        }
    }
    filtered { length = Math.addExact(length, 1) }
    val multiplier = 0x5bd1e995
    var hash = 1 xor length
    var word = 0
    var shift = 0
    filtered { value ->
        word = word or (value shl shift)
        shift += 8
        if (shift == 32) {
            var mixed = word * multiplier
            mixed = (mixed xor (mixed ushr 24)) * multiplier
            hash = hash * multiplier xor mixed
            word = 0
            shift = 0
        }
    }
    if (shift != 0) hash = (hash xor word) * multiplier
    hash = hash xor (hash ushr 13)
    hash *= multiplier
    hash = hash xor (hash ushr 15)
    return hash.toLong() and 0xffffffffL
}
