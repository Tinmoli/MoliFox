// 负责从游戏客户端输出观察实际服务器连接，更新最近游玩时间。

package dev.molifox.core

/** 只有连接尝试之后观察到客户端进入游戏的日志，才更新记录；不解析聊天中的伪造连接文本。 */
internal class GameActivityTracker(
    private val store: LauncherStore,
    private val instance: GameInstance,
) {
    private var pendingServer: String? = null

    fun accept(line: String) {
        val message = INFO_LINE.matchEntire(line)?.groupValues?.get(1) ?: return
        CONNECTING.matchEntire(message)?.let { match ->
            pendingServer = null
            val host = match.groupValues[1].trim().removeSurrounding("[", "]")
            val port = match.groupValues[2].toIntOrNull() ?: return
            if (port !in 1..65535 || host.isBlank() || host.any { it.isWhitespace() }) return
            val address = ServerAddress(host, port)
            pendingServer =
                listServers(store.gamePath(instance))
                    .firstOrNull {
                        sameServerAddress(parseServerAddress(it.ip), address)
                    }
                    ?.ip ?: formatServerAddress(address)
            return
        }
        if (DISCONNECTED.containsMatchIn(message)) {
            pendingServer = null
            return
        }
        val server = pendingServer ?: return
        if (ENTERED.matches(message)) {
            store.recentUsage.record(EntryKind.Server, instance.id, server)
            pendingServer = null
        }
    }

    private companion object {
        val INFO_LINE = Regex("^\\[[^\\]]+]\\s*\\[[^\\]]+/(?:INFO|WARN|ERROR)]\\s*:?\\s*(.*)$")
        val CONNECTING = Regex("^Connecting to (.+), (\\d{1,5})$")
        val DISCONNECTED =
            Regex(
                "^(?:Disconnected|Lost connection|Stopping!|Connecting to Realms|Couldn't connect|Failed to connect|Starting integrated|Starting minecraft server)"
            )
        val ENTERED = Regex("(?:Loaded \\d+ advancements|\\[(?:System|CHAT)](?: \\[CHAT])?.+)")
    }
}

fun sameServerAddress(left: ServerAddress?, right: ServerAddress?): Boolean =
    left != null && right != null && left.port == right.port && left.host.equals(right.host, true)

private fun formatServerAddress(address: ServerAddress): String {
    val host = if (':' in address.host) "[${address.host}]" else address.host
    return if (address.port == DEFAULT_SERVER_PORT) host else "$host:${address.port}"
}
