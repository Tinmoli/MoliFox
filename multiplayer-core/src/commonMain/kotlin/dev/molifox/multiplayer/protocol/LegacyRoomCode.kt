// 旧版 Terracotta 的 25 字符房间码：只做兼容解析，不用于生成或展示；当前协议已不再使用。
package dev.molifox.multiplayer.protocol

/** 旧版 25 字符房间码的解析结果；仅用于加入仍在使用旧格式的房间。 */
data class LegacyRoomCode(
    /** 规范化后的 29 字符展示码，如 `12345-6789A-BCDEF-GHJKL-MNPQU`。 */
    val code: String,
    /** 旧版网络名称，如 `123456789abcdef`。 */
    val networkName: String,
    /** 旧版网络密钥，如 `ghjklmnpqu`。 */
    val networkSecret: String,
    /** 房间编码在码内的 Minecraft 端口。 */
    val port: Int,
)

/**
 * 旧版 25 字符房间码解析：5 组、每组 5 位，去掉连字符后共 25 位；前 24 位为小端 34 进制值，
 * 末位是前 24 位数字和除以 34 的余数。解析失败返回 null，不抛异常。
 */
object LegacyRoomCodeParser {
    /** 去掉连字符后的有效数字位数。 */
    const val DIGIT_COUNT = 25

    private const val GROUP_SIZE = 5
    private const val HYPHENATED_LENGTH = 29
    private const val CHECK_DIGIT_COUNT = 24
    private const val NETWORK_NAME_DIGITS = 15
    private const val BASE = 34
    private const val CHECK_MODULUS = 34
    private const val PORT_MODULUS = 65536
    private const val MIN_PORT = 100
    private const val GROUP_SEPARATOR = "-"
    private const val HYPHEN = '-'

    /** 解析规范分组或纯 25 位数字的旧版房间码；也接受文本中夹带的一组规范码。 */
    fun parse(input: String): LegacyRoomCode? {
        val compact = extractDigits(normalizeRoomCode(input)) ?: return null
        val digits = toDigits(compact) ?: return null
        if (checksum(digits) != digits[CHECK_DIGIT_COUNT]) return null
        val port = portOf(digits)
        if (port < MIN_PORT) return null
        return LegacyRoomCode(
            code = compact.chunked(GROUP_SIZE).joinToString(GROUP_SEPARATOR),
            networkName = textOf(digits, 0, NETWORK_NAME_DIGITS).lowercase(),
            networkSecret = textOf(digits, NETWORK_NAME_DIGITS, DIGIT_COUNT).lowercase(),
            port = port,
        )
    }

    /** 接受纯 25 位数字或规范 29 字符分组；失败返回 null。 */
    private fun extractDigits(text: String): String? {
        val trimmed = text.trim()
        val direct =
            when (trimmed.length) {
                DIGIT_COUNT -> if (trimmed.contains(HYPHEN)) null else trimmed
                HYPHENATED_LENGTH -> compactHyphenated(trimmed)
                else -> null
            }
        return direct ?: findHyphenatedWindow(trimmed)
    }

    private fun findHyphenatedWindow(text: String): String? {
        if (text.length < HYPHENATED_LENGTH) return null
        for (start in 0..(text.length - HYPHENATED_LENGTH)) {
            val window = text.substring(start, start + HYPHENATED_LENGTH)
            val compact = compactHyphenated(window)
            if (compact != null) return compact
        }
        return null
    }

    private fun compactHyphenated(text: String): String? {
        if (text.length != HYPHENATED_LENGTH) return null
        val builder = StringBuilder(DIGIT_COUNT)
        val valid =
            text.indices.all { position ->
                appendLegacyDigit(text[position], position, builder)
            }
        return if (valid) builder.toString() else null
    }

    /** 连字符位置必须是分组分隔符，其余位置必须是字母表字符；非法时不写入并返回 false。 */
    private fun appendLegacyDigit(
        character: Char,
        position: Int,
        builder: StringBuilder,
    ): Boolean =
        if (position % (GROUP_SIZE + 1) == GROUP_SIZE) {
            character == HYPHEN
        } else if (ROOM_CODE_ALPHABET.indexOf(character) < 0) {
            false
        } else {
            builder.append(character)
            true
        }

    private fun toDigits(compact: String): IntArray? {
        val digits = IntArray(compact.length)
        for (index in compact.indices) {
            val value = ROOM_CODE_ALPHABET.indexOf(compact[index])
            if (value < 0) return null
            digits[index] = value
        }
        return digits
    }

    private fun checksum(digits: IntArray): Int {
        var sum = 0
        for (index in 0 until CHECK_DIGIT_COUNT) sum += digits[index]
        return sum % CHECK_MODULUS
    }

    /** 小端 34 进制整数对 65536 取模；从最高位做 Horner 展开，避免大整数类型。 */
    private fun portOf(digits: IntArray): Int {
        var value = 0
        for (index in CHECK_DIGIT_COUNT - 1 downTo 0) {
            value = (value * BASE + digits[index]) % PORT_MODULUS
        }
        return value
    }

    private fun textOf(
        digits: IntArray,
        from: Int,
        to: Int,
    ): String {
        val builder = StringBuilder(to - from)
        for (index in from until to) builder.append(ROOM_CODE_ALPHABET[digits[index]])
        return builder.toString()
    }
}
