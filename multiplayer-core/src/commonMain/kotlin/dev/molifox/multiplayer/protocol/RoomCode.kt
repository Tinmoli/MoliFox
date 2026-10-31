// Scaffolding 标准房间码：自实现生成与解析，不依赖平台随机数、网络或参考项目源码。
package dev.molifox.multiplayer.protocol

/** 密码学安全的随机字节来源；平台层用 SecureRandom 实现，测试注入固定值以复现向量。 */
fun interface RoomCodeRandom {
    /** 返回恰好 [size] 字节的随机数据。 */
    fun nextBytes(size: Int): ByteArray
}

/** 合法房间码及其推导出的 EasyTier 网络名称与密钥。 */
data class ScaffoldingCode(
    /** 21 字符房间码，如 `U/YNZE-U61D-2206-HXRG`，用于展示与复制。 */
    val code: String,
    /** EasyTier 网络名称，如 `MoliFox-YNZE-U61D`；EasyTier 区分大小写。 */
    val networkName: String,
    /** EasyTier 网络密钥，如 `2206-HXRG`。 */
    val networkSecret: String,
)

/**
 * Scaffolding 房间码编解码：生成 21 字符 `U/XXXX-XXXX-XXXX-XXXX`，并解析用户粘贴的文本。
 *
 * 生成规则与协议一致：128 位随机数对 34^16 取模后减去对 7 的余数，按 34 进制小端展开为
 * 16 位数字；字母表去掉易混淆的 I/O。解析时大小写不敏感，并把输入的 I/O 归一化为 1/0。
 */
object RoomCode {
    /** 房间码总长度，含 `U/` 前缀与 3 个连字符。 */
    const val CODE_LENGTH = 21

    /** 房间码的有效数字位数。 */
    const val DIGIT_COUNT = 16

    private const val PREFIX = "U/"
    private const val GROUP_SEPARATOR = "-"
    private const val GROUP_SIZE = 4
    private const val GROUP_COUNT = DIGIT_COUNT / GROUP_SIZE
    private const val NETWORK_GROUP_COUNT = 2
    private const val BASE = 34
    private const val RANDOM_BYTES = 16
    private const val INT_BYTES = 4
    private const val INT_BITS = 32
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF
    private const val UNSIGNED_INT_MASK = 0xFFFFFFFFL
    private const val CHECK_MODULUS = 7
    private const val NETWORK_NAME_PREFIX = "MoliFox-"

    /** 用注入的随机源生成房间码；随机源必须返回 16 字节密码学安全随机数。 */
    fun generate(random: RoomCodeRandom): ScaffoldingCode {
        val digits = lowBase34Digits(random.nextBytes(RANDOM_BYTES))
        subtractCheckRemainder(digits)
        return buildCode(digits)
    }

    /** 解析包含完整 21 字符码的文本；失败返回 null，不抛异常。 */
    fun parse(input: String): ScaffoldingCode? {
        val normalized = normalizeRoomCode(input)
        var start = normalized.indexOf(PREFIX)
        while (start >= 0) {
            decodeAt(normalized, start)?.let { return it }
            start = normalized.indexOf(PREFIX, start + 1)
        }
        return null
    }

    /** 取随机数的低 16 位 34 进制数字：反复除以 34，余数即从低位到高位的数字。 */
    private fun lowBase34Digits(bytes: ByteArray): IntArray {
        require(bytes.size == RANDOM_BYTES) {
            "room code random source must return $RANDOM_BYTES bytes, got ${bytes.size}"
        }
        val limbs = IntArray(INT_BYTES) { index -> readUnsignedInt(bytes, index * INT_BYTES) }
        val digits = IntArray(DIGIT_COUNT)
        for (index in digits.indices) {
            var remainder = 0L
            for (limbIndex in limbs.indices) {
                val current =
                    (remainder shl INT_BITS) or (limbs[limbIndex].toLong() and UNSIGNED_INT_MASK)
                limbs[limbIndex] = (current / BASE).toInt()
                remainder = current % BASE
            }
            digits[index] = remainder.toInt()
        }
        return digits
    }

    /** 把整个 16 位数字视为小端整数，减去它对 7 的余数，使其满足 `value % 7 == 0`。 */
    private fun subtractCheckRemainder(digits: IntArray) {
        var borrow = checkRemainder(digits)
        for (index in digits.indices) {
            if (borrow == 0) return
            val value = digits[index] - borrow
            digits[index] = if (value < 0) value + BASE else value
            borrow = if (value < 0) 1 else 0
        }
    }

    /** 小端 34 进制整数的余数；从最高位做 Horner 展开，避免大整数类型。 */
    private fun checkRemainder(digits: IntArray): Int {
        var remainder = 0
        for (index in digits.indices.reversed()) {
            remainder = (remainder * BASE + digits[index]) % CHECK_MODULUS
        }
        return remainder
    }

    private fun buildCode(digits: IntArray): ScaffoldingCode {
        val groups = List(GROUP_COUNT) { group -> formatGroup(digits, group) }
        val networkGroups = groups.take(NETWORK_GROUP_COUNT).joinToString(GROUP_SEPARATOR)
        val secretGroups = groups.drop(NETWORK_GROUP_COUNT).joinToString(GROUP_SEPARATOR)
        return ScaffoldingCode(
            code = PREFIX + groups.joinToString(GROUP_SEPARATOR),
            networkName = NETWORK_NAME_PREFIX + networkGroups,
            networkSecret = secretGroups,
        )
    }

    private fun formatGroup(
        digits: IntArray,
        group: Int,
    ): String {
        val start = group * GROUP_SIZE
        return buildString(GROUP_SIZE) {
            for (offset in 0 until GROUP_SIZE) {
                append(ROOM_CODE_ALPHABET[digits[start + offset]])
            }
        }
    }

    private fun decodeAt(
        text: String,
        start: Int,
    ): ScaffoldingCode? {
        if (start + CODE_LENGTH > text.length) return null
        val window = text.substring(start, start + CODE_LENGTH)
        val digits = decodeDigits(window) ?: return null
        if (checkRemainder(digits) != 0) return null
        return buildCode(digits)
    }

    /** 校验连字符分组与字母表；非法字符或错位连字符都返回 null。 */
    private fun decodeDigits(window: String): IntArray? {
        val groups = window.substring(PREFIX.length).split(GROUP_SEPARATOR)
        if (groups.size != GROUP_COUNT) return null
        val digits = IntArray(DIGIT_COUNT)
        var valid = true
        var digitIndex = 0
        for (group in groups) {
            if (group.length != GROUP_SIZE) valid = false
            for (character in group) {
                val value = ROOM_CODE_ALPHABET.indexOf(character)
                if (value < 0) {
                    valid = false
                } else {
                    digits[digitIndex] = value
                    digitIndex++
                }
            }
        }
        return if (valid) digits else null
    }

    private fun readUnsignedInt(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        var value = 0
        for (index in offset until offset + INT_BYTES) {
            value = (value shl BYTE_BITS) or (bytes[index].toInt() and BYTE_MASK)
        }
        return value
    }
}

/** 34 进制字母表：0-9 与去掉 I/O 的 A-Z；顺序必须与 Scaffolding 协议一致。 */
internal const val ROOM_CODE_ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"

/** 统一大写并归一化易混淆字符，供标准码与 legacy 码解析复用。 */
internal fun normalizeRoomCode(text: String): String {
    val upper = text.uppercase()
    val builder = StringBuilder(upper.length)
    for (character in upper) {
        builder.append(normalizeRoomCodeCharacter(character))
    }
    return builder.toString()
}

private fun normalizeRoomCodeCharacter(character: Char): Char =
    when (character) {
        'I' -> '1'
        'O' -> '0'
        else -> character
    }
