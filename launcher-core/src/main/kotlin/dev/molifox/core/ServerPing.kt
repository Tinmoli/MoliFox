// 负责服务器地址解析、状态查询和 MOTD 转换。

package dev.molifox.core

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MOTD 的一段文字与它最终生效的样式。
 *
 * [color] 是 `0xRRGGBB`（不含 alpha）；null 表示沿用界面默认文字色。 [bold] / [italic] / [underlined] / [struck]
 * 是「这一段是否开启」，不是增量。
 */
data class MotdSegment(
    val text: String,
    val color: Int?,
    val bold: Boolean,
    val italic: Boolean,
    val underlined: Boolean,
    val struck: Boolean,
)

/**
 * 一次服务器列表查询的结果。
 *
 * [ping] 是握手到状态响应读完的毫秒数（不含 DNS 解析）。查询失败时不会构造本对象， 而是返回 null；因此 [online] 目前对任何非 null 结果都是
 * true；该字段描述缓存及协议降级后的在线状态。
 */
data class ServerStatus(
    val online: Boolean,
    val players: Int,
    val maxPlayers: Int,
    val motd: List<MotdSegment>,
    val version: String,
    val ping: Int,
)

/** 从 `servers.dat` 的 `ip` 字符串解析出的地址；[port] 缺省 [DEFAULT_SERVER_PORT]。 */
data class ServerAddress(val host: String, val port: Int = DEFAULT_SERVER_PORT)

const val DEFAULT_SERVER_PORT = 25565

/** 现代握手使用的协议号：0x7F 表示「未指定版本」，服务端仍会返回状态 JSON。 */
private const val STATUS_PROTOCOL_VERSION = 0x7F

/** 状态响应上限：正常只有几 KB，部分服务端会把整张 favicon 塞进来，留余量但不无限读。 */
private const val MAX_PACKET_BYTES = 2 * 1024 * 1024

/** 旧版（0xFE）响应的字符数上限；协议本身用 unsigned short 表示长度。 */
private const val MAX_LEGACY_CHARS = 32_768

private const val DEFAULT_TIMEOUT_MILLIS = 4_000L

/** 两次尝试共用一个总预算：旧版兜底只剩不到这个时间就不再发第二次连接。 */
private const val MIN_TIMEOUT_MILLIS = 250

/**
 * 解析服务器地址：`example.com` / `example.com:25566` / `[::1]:25565`。
 *
 * 只做字符串解析，**不查询 SRV 记录**：`_minecraft._tcp` 的 SRV 查询未在此实现， 直接使用用户填写的端口（缺省
 * [DEFAULT_SERVER_PORT]）。空串、含空白或控制字符、 端口越界、括号不成对都返回 null，从不抛异常。
 */
fun parseServerAddress(address: String): ServerAddress? {
    val raw = address.trim()
    if (raw.isEmpty() || raw.length > 255) return null
    if (raw.any { it.isWhitespace() || it.code < 0x20 }) return null
    // 带方括号的 IPv6 字面量，端口写在括号之后。
    if (raw.startsWith("[")) {
        val end = raw.indexOf(']')
        if (end <= 1) return null
        val host = raw.substring(1, end)
        val rest = raw.substring(end + 1)
        if (rest.isEmpty()) return ServerAddress(host)
        if (!rest.startsWith(':')) return null
        val port = rest.substring(1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return ServerAddress(host, port)
    }
    // 只有一个冒号才拆端口；多个冒号视为不带端口的 IPv6 字面量。
    if (raw.count { it == ':' } == 1) {
        val host = raw.substringBefore(':')
        val portText = raw.substringAfter(':')
        if (host.isEmpty()) return null
        val port = portText.toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return ServerAddress(host, port)
    }
    return ServerAddress(raw)
}

/**
 * 查询一个 Minecraft 服务器的状态，失败返回 null（从不抛异常）。
 *
 * 先走现代 Server List Ping（握手 next state = 1 + status 请求），失败时退回旧版 `0xFE 0x01` 查询；整段在 [Dispatchers.IO]
 * 上执行，连接与读取共用 [timeoutMillis] （会被限制在 250 ms–30 s）。DNS 解析不受 socket 超时约束，可能额外多花一点时间。
 */
suspend fun ping(
    host: String,
    port: Int = DEFAULT_SERVER_PORT,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
): ServerStatus? {
    val target = host.trim()
    if (target.isEmpty() || port !in 1..65535) return null
    val timeout = timeoutMillis.coerceIn(250L, 30_000L).toInt()
    return withContext(Dispatchers.IO) {
        try {
            val startedAt = System.nanoTime()
            modernPing(target, port, timeout)?.let {
                return@withContext it
            }
            // 旧版兜底共用同一份总预算，避免两次 4 秒超时把一次查询拖到 8 秒以上。
            val used = ((System.nanoTime() - startedAt) / 1_000_000L).toInt()
            val remaining = timeout - used
            if (remaining < MIN_TIMEOUT_MILLIS) null else legacyPing(target, port, remaining)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

/** 直接查询 `servers.dat` 里那种 `host:port` 字符串；地址解析失败返回 null。 */
suspend fun pingAddress(
    address: String,
    timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
): ServerStatus? {
    val parsed = parseServerAddress(address) ?: return null
    return ping(parsed.host, parsed.port, timeoutMillis)
}

// ---------------------------------------------------------------- 协议实现

/** 现代协议：握手 → status 请求 → 状态 JSON；任何一步不符合预期都返回 null 交给旧版兜底。 */
private fun modernPing(host: String, port: Int, timeoutMillis: Int): ServerStatus? {
    val socket = Socket()
    return try {
        socket.tcpNoDelay = true
        socket.soTimeout = timeoutMillis
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        val startedAt = System.nanoTime()
        val output = socket.getOutputStream()
        val handshake =
            ByteArrayOutputStream().apply {
                writeVarInt(0x00)
                writeVarInt(STATUS_PROTOCOL_VERSION)
                writeString(host)
                writeUnsignedShort(port)
                writeVarInt(1)
            }
        output.writePacket(handshake.toByteArray())
        output.writePacket(byteArrayOf(0x00))
        output.flush()
        val input = socket.getInputStream().buffered()
        val packetLength = input.readVarInt()
        if (packetLength <= 0 || packetLength > MAX_PACKET_BYTES) return null
        val packet = ByteArrayInputStream(input.readFully(packetLength))
        if (packet.readVarInt() != 0x00) return null
        val jsonLength = packet.readVarInt()
        if (jsonLength <= 0) return null
        val json = String(packet.readFully(jsonLength), Charsets.UTF_8)
        val latency = ((System.nanoTime() - startedAt) / 1_000_000L).toInt().coerceAtLeast(0)
        statusFromJson(json, latency)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { socket.close() }
    }
}

/**
 * 旧版（1.4–1.6 及更早）查询：`0xFE 0x01`，响应是 `0xFF` + 长度 + UTF-16BE 字符串。 字段用 `\u0000`
 * 分隔：`§1`、协议号、版本名、MOTD、在线人数、上限（更早的版本没有后两项）。
 */
private fun legacyPing(host: String, port: Int, timeoutMillis: Int): ServerStatus? {
    val socket = Socket()
    return try {
        socket.tcpNoDelay = true
        socket.soTimeout = timeoutMillis
        socket.connect(InetSocketAddress(host, port), timeoutMillis)
        val startedAt = System.nanoTime()
        val output = socket.getOutputStream()
        output.write(0xFE)
        output.write(0x01)
        output.flush()
        val input = socket.getInputStream()
        if (input.read() != 0xFF) return null
        val high = input.read()
        val low = input.read()
        if (high < 0 || low < 0) return null
        val length = (high shl 8) or low
        if (length <= 0 || length > MAX_LEGACY_CHARS) return null
        val text = String(input.readFully(length * 2), Charsets.UTF_16BE)
        val latency = ((System.nanoTime() - startedAt) / 1_000_000L).toInt().coerceAtLeast(0)
        statusFromLegacy(text, latency)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { socket.close() }
    }
}

private fun statusFromJson(json: String, latency: Int): ServerStatus? {
    val root = runCatching { JsonParser.parseString(json) }.getOrNull()
    val obj = root?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
    val players = obj.get("players")?.takeIf { it.isJsonObject }?.asJsonObject
    val version = obj.get("version")?.takeIf { it.isJsonObject }?.asJsonObject
    return ServerStatus(
        online = true,
        players = (players?.intValue("online") ?: 0).coerceAtLeast(0),
        maxPlayers = (players?.intValue("max") ?: 0).coerceAtLeast(0),
        motd = parseMotd(obj.get("description")),
        version = version?.stringValue("name").orEmpty(),
        ping = latency,
    )
}

private fun statusFromLegacy(text: String, latency: Int): ServerStatus {
    val parts = text.trimEnd('\u0000').split('\u0000')
    // 至少要有版本名；MOTD 行缺失时退回版本名，避免出现空行。
    val version = parts.getOrNull(2)?.trim().orEmpty()
    val motdText = parts.getOrNull(3) ?: version
    return ServerStatus(
        online = true,
        players = (parts.getOrNull(4)?.trim()?.toIntOrNull() ?: 0).coerceAtLeast(0),
        maxPlayers = (parts.getOrNull(5)?.trim()?.toIntOrNull() ?: 0).coerceAtLeast(0),
        motd = parseMotdText(motdText),
        version = version,
        ping = latency,
    )
}

private fun JsonObject.intValue(key: String): Int =
    runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull() ?: 0

private fun JsonObject.stringValue(key: String): String = runCatching {
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}
    .getOrNull()
    .orEmpty()

// ---------------------------------------------------------------- MOTD 解析

/** 解析过程中的「当前生效样式」；与 [MotdSegment] 的区别是它没有文字。 */
private data class MotdStyle(
    val color: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underlined: Boolean = false,
    val struck: Boolean = false,
) {
    companion object {
        val Default = MotdStyle()
    }
}

private val namedColors: Map<String, Int> =
    mapOf(
        "black" to 0x000000,
        "dark_blue" to 0x0000AA,
        "dark_green" to 0x00AA00,
        "dark_aqua" to 0x00AAAA,
        "dark_red" to 0xAA0000,
        "dark_purple" to 0xAA00AA,
        "gold" to 0xFFAA00,
        "gray" to 0xAAAAAA,
        "dark_gray" to 0x555555,
        "blue" to 0x5555FF,
        "green" to 0x55FF55,
        "aqua" to 0x55FFFF,
        "red" to 0xFF5555,
        "light_purple" to 0xFF55FF,
        "yellow" to 0xFFFF55,
        "white" to 0xFFFFFF,
    )

/** `§0`–`§f` 与原版颜色表的顺序一致。 */
private val legacyColorCodes: List<Int> =
    listOf(
        0x000000,
        0x0000AA,
        0x00AA00,
        0x00AAAA,
        0xAA0000,
        0xAA00AA,
        0xFFAA00,
        0xAAAAAA,
        0x555555,
        0x5555FF,
        0x55FF55,
        0x55FFFF,
        0xFF5555,
        0xFF55FF,
        0xFFFF55,
        0xFFFFFF,
    )

/**
 * 把状态 JSON 的 `description` 解析成扁平的样式片段。
 *
 * 支持纯字符串（其中可以带 `§` 代码）、组件对象（`text` + `extra` + `color` +
 * `bold/italic/underlined/strikethrough`）与组件数组；子组件继承父组件样式， 显式写了 `false`
 * 的开关会覆盖父级。没有可显示文字、或结构无法识别时返回空列表； 本函数从不抛异常。
 */
fun parseMotd(description: JsonElement?): List<MotdSegment> {
    val segments = ArrayList<MotdSegment>()
    runCatching { resolveMotd(description, MotdStyle.Default, segments) }
    return segments
}

/** 解析纯文本 MOTD（旧版协议、或 `description` 本身是字符串时）；`§` 代码规则相同。 */
fun parseMotdText(text: String): List<MotdSegment> {
    val segments = ArrayList<MotdSegment>()
    runCatching { appendStyled(text, MotdStyle.Default, segments) }
    return segments
}

private fun resolveMotd(element: JsonElement?, style: MotdStyle, out: MutableList<MotdSegment>) {
    if (element == null || element.isJsonNull) return
    when {
        element.isJsonPrimitive -> {
            if (element.asJsonPrimitive.isString) appendStyled(element.asString, style, out)
        }
        element.isJsonArray -> {
            val array = element.asJsonArray
            for (index in 0 until array.size()) resolveMotd(array[index], style, out)
        }
        element.isJsonObject -> {
            val obj = element.asJsonObject
            val own = applyStyle(style, obj)
            val text = obj.get("text")
            if (text != null && text.isJsonPrimitive && text.asJsonPrimitive.isString)
                appendStyled(text.asString, own, out)
            val extra = obj.get("extra")
            if (extra != null && extra.isJsonArray) {
                val array = extra.asJsonArray
                for (index in 0 until array.size()) resolveMotd(array[index], own, out)
            } else if (extra != null) {
                resolveMotd(extra, own, out)
            }
        }
    }
}

/** 合并组件自己声明的样式；无法识别的值保持继承，不抛异常。 */
private fun applyStyle(base: MotdStyle, obj: JsonObject): MotdStyle {
    var style = base
    val color = obj.get("color")
    if (color != null && color.isJsonPrimitive && color.asJsonPrimitive.isString) {
        val name = color.asString
        // JSON 组件里 color 只改颜色，不像 § 代码那样清掉格式。
        style =
            if (name.equals("reset", ignoreCase = true)) MotdStyle.Default
            else parseColor(name)?.let { style.copy(color = it) } ?: style
    }
    return style.copy(
        bold = obj.flag("bold") ?: style.bold,
        italic = obj.flag("italic") ?: style.italic,
        underlined = obj.flag("underlined") ?: style.underlined,
        struck = obj.flag("strikethrough") ?: style.struck,
    )
}

private fun JsonObject.flag(key: String): Boolean? {
    val element = get(key) ?: return null
    if (!element.isJsonPrimitive) return null
    val primitive = element.asJsonPrimitive
    return when {
        primitive.isBoolean -> primitive.asBoolean
        primitive.isString ->
            when (primitive.asString.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        else -> null
    }
}

/** 命名颜色或 `#RRGGBB`；认不出来返回 null（调用方保持继承）。 */
private fun parseColor(value: String): Int? {
    namedColors[value.lowercase()]?.let {
        return it
    }
    if (value.length != 7 || value[0] != '#') return null
    val hex = value.substring(1)
    if (hex.any { it.digitToIntOrNull(16) == null }) return null
    return hex.toIntOrNull(16)
}

/** 追加一段可能带 `§` 代码的文字：代码切换样式并切断当前片段，`§r` 复位， 未知代码按普通字符保留。连续同样式的小段会合并，最终得到「每个样式一段」。 */
private fun appendStyled(text: String, style: MotdStyle, out: MutableList<MotdSegment>) {
    if (text.isEmpty()) return
    val normalized = text.replace('\r', ' ').replace('\n', ' ')
    if (normalized.indexOf('§') < 0) {
        appendRun(normalized, style, out)
        return
    }
    val run = StringBuilder()
    var current = style
    var index = 0
    while (index < normalized.length) {
        val char = normalized[index]
        if (char == '§' && index + 1 < normalized.length) {
            val next = codeStyle(normalized[index + 1], current)
            if (next != null) {
                appendRun(run.toString(), current, out)
                run.setLength(0)
                current = next
                index += 2
                continue
            }
        }
        run.append(char)
        index += 1
    }
    appendRun(run.toString(), current, out)
}

/** `§` 代码对应的新样式；不是已知代码时返回 null（按字面量处理）。 */
private fun codeStyle(code: Char, style: MotdStyle): MotdStyle? {
    val lower = code.lowercaseChar()
    val colorIndex = "0123456789abcdef".indexOf(lower)
    if (colorIndex >= 0)
        return MotdStyle(
            color = legacyColorCodes[colorIndex],
            bold = false,
            italic = false,
            underlined = false,
            struck = false,
        )
    return when (lower) {
        // §k 是乱码效果，静态界面无法呈现：吞掉代码、不改其它样式。
        'k' -> style
        'l' -> style.copy(bold = true)
        'm' -> style.copy(struck = true)
        'n' -> style.copy(underlined = true)
        'o' -> style.copy(italic = true)
        'r' -> MotdStyle.Default
        else -> null
    }
}

private fun appendRun(text: String, style: MotdStyle, out: MutableList<MotdSegment>) {
    if (text.isEmpty()) return
    val last = out.lastOrNull()
    if (
        last != null &&
            last.color == style.color &&
            last.bold == style.bold &&
            last.italic == style.italic &&
            last.underlined == style.underlined &&
            last.struck == style.struck
    ) {
        out[out.size - 1] = last.copy(text = last.text + text)
    } else {
        out +=
            MotdSegment(
                text = text,
                color = style.color,
                bold = style.bold,
                italic = style.italic,
                underlined = style.underlined,
                struck = style.struck,
            )
    }
}

// ---------------------------------------------------------------- 字节流工具

private fun InputStream.readVarInt(): Int {
    var result = 0
    var shift = 0
    while (true) {
        val byte = read()
        if (byte < 0) throw EOFException("服务器响应提前结束")
        result = result or ((byte and 0x7F) shl shift)
        if ((byte and 0x80) == 0) return result
        shift += 7
        if (shift > 28) throw IOException("VarInt 过长")
    }
}

private fun InputStream.readFully(length: Int): ByteArray {
    if (length < 0) throw IOException("长度为负")
    val bytes = ByteArray(length)
    var read = 0
    while (read < length) {
        val count = read(bytes, read, length - read)
        if (count < 0) throw EOFException("服务器响应提前结束")
        read += count
    }
    return bytes
}

private fun OutputStream.writeVarInt(value: Int) {
    var remaining = value
    while (true) {
        if ((remaining and 0x7F.inv()) == 0) {
            write(remaining)
            return
        }
        write((remaining and 0x7F) or 0x80)
        remaining = remaining ushr 7
    }
}

private fun OutputStream.writeString(text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    writeVarInt(bytes.size)
    write(bytes)
}

private fun OutputStream.writeUnsignedShort(value: Int) {
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

private fun OutputStream.writePacket(payload: ByteArray) {
    writeVarInt(payload.size)
    write(payload)
}
