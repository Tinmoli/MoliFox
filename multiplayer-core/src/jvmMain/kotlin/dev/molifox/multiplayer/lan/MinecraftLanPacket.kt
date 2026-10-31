// Minecraft LAN 广播报文的共享常量与容错解析：端口非法或报文残缺时返回 null。
package dev.molifox.multiplayer.lan

/** Minecraft 客户端默认的 LAN 广播端口。 */
internal const val DEFAULT_LAN_PORT = 4445

/** Minecraft 局域网世界使用的 IPv4 组播地址。 */
internal const val DEFAULT_MULTICAST_ADDRESS = "224.0.2.60"

internal const val MIN_PORT = 1
internal const val MAX_PORT = 65_535

internal const val MOTD_START = "[MOTD]"
internal const val MOTD_END = "[/MOTD]"
internal const val AD_START = "[AD]"
internal const val AD_END = "[/AD]"

/** 一条 LAN 广播里的最小信息；MOTD 只用于诊断，世界名仍以 status ping 为准。 */
internal data class LanPacket(
    val motd: String?,
    val port: Int,
)

/**
 * 解析 `[MOTD]<文本>[/MOTD][AD]<端口>[/AD]`。
 *
 * 只要求 AD 端口合法：MOTD 缺失时照样返回，端口无法解析或越界时返回 null，
 * 由调用方静默忽略这条广播（局域网里可能混入其它程序的 UDP 包）。
 */
internal fun parseLanPacket(raw: String): LanPacket? {
    val adStart = raw.indexOf(AD_START)
    val adEnd = raw.indexOf(AD_END)
    val port =
        if (adStart < 0 || adEnd <= adStart + AD_START.length) {
            null
        } else {
            raw.substring(adStart + AD_START.length, adEnd).trim().toIntOrNull()
        }
    return if (port == null || port !in MIN_PORT..MAX_PORT) {
        null
    } else {
        LanPacket(motd = parseMotd(raw), port = port)
    }
}

private fun parseMotd(raw: String): String? {
    val start = raw.indexOf(MOTD_START)
    val contentStart = start + MOTD_START.length
    val end = if (start < 0) -1 else raw.indexOf(MOTD_END, contentStart)
    return if (start < 0 || end < 0) null else raw.substring(contentStart, end)
}
