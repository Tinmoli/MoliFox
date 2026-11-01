// 联机公共模型：中性的房间阶段、世界与运行时状态，不引用界面、磁盘或进程类型。
package dev.molifox.multiplayer.common

import dev.molifox.core.i18n.I18n

/**
 * 联机页阶段；房主与房客共用，名称不再绑定任何具体联机核心。
 *
 * 与旧陶瓦状态机不同：没有“扫描/启动核心”这类核心语义，发现世界与下载运行时分开。
 */
enum class LobbyPhase {
    /** 空闲：可发现局域网世界、创建或加入房间。 */
    Idle,

    /** 正在下载并安装 EasyTier 运行时。 */
    DownloadingRuntime,

    /** 正在启动 EasyTier 节点并等待命令行可用。 */
    StartingRuntime,

    /** 正在监听 Minecraft 局域网广播并探测候选世界。 */
    DiscoveringWorld,

    /** 已选世界，正在建立 Scaffolding 服务端与虚拟网络。 */
    CreatingRoom,

    /** 房间已开启：可展示邀请码与玩家列表。 */
    RoomOpen,

    /** 正在解析邀请码、启动房客节点并等待房主节点出现。 */
    JoiningRoom,

    /** 已加入房间并完成本地端口转发与广播。 */
    Connected,

    /** 出错；[MultiplayerState.error] 为可读原因，技术细节只进日志。 */
    Failed,
}

/** 房客到房主的实际数据路径；用于连接成功后展示“打洞直连/中继转发”。 */
enum class ConnectionRoute { Direct, Relay, Unknown }

/** 本次联机中本机的角色。 */
enum class LobbyRole { Host, Guest }

/** 房间里的玩家来源；[LOCAL] 用于界面上标记“你”。 */
enum class PlayerKind { HOST, LOCAL, GUEST }

/** 房间里的一名玩家；[machineId] 来自 Scaffolding 客户端，用作稳定 key。 */
data class RoomPlayer(
    val machineId: String,
    val name: String,
    val kind: PlayerKind,
)

/** 联机运行时的本地状态：版本、平台、目录与下载大小。 */
data class CoreStatus(
    val installed: Boolean,
    val version: String,
    val platform: String,
    val downloadBytes: Long,
    val directory: String,
)

/** 联机页的全部可见状态；界面只读，所有写操作由后端完成。 */
data class MultiplayerState(
    val phase: LobbyPhase = LobbyPhase.Idle,
    val role: LobbyRole? = null,
    /** 下载进度 0..1；未知时为 null。 */
    val progress: Float? = null,
    /** 房主邀请码（21 字符）。 */
    val roomCode: String? = null,
    /** 房客本地可加入地址（`127.0.0.1:<端口>`），广播之外的兜底入口。 */
    val localAddress: String? = null,
    /** 房客连接成功后的实际路径；房主或未连接时为 null。 */
    val connectionRoute: ConnectionRoute? = null,
    /** 本机 Minecraft 局域网世界；发现流程结束后仍可保留到开始建房。 */
    val worlds: List<LanWorld> = emptyList(),
    val players: List<RoomPlayer> = emptyList(),
    val error: String? = null,
    val runtime: CoreStatus? = null,
) {
    val roomOpen: Boolean get() = phase == LobbyPhase.RoomOpen

    val connected: Boolean get() = phase == LobbyPhase.RoomOpen || phase == LobbyPhase.Connected

    /** 正在执行不能被打断的步骤；界面据此禁用会改变房间的操作。 */
    val busy: Boolean
        get() =
            phase in
                setOf(
                    LobbyPhase.DownloadingRuntime,
                    LobbyPhase.StartingRuntime,
                    LobbyPhase.DiscoveringWorld,
                    LobbyPhase.CreatingRoom,
                    LobbyPhase.JoiningRoom,
                )
}

/** 联机设置；[relayServer] 用于覆盖默认 EasyTier 中继节点（分号分隔）。 */
data class MultiplayerSettings(
    val enabled: Boolean = true,
    /** 显示给其他玩家的名字；空串时由账户名兜底。 */
    val nickname: String = "",
    val acceptedTermsRevision: String = "",
    val relayServer: String = "",
    /** 优先低延迟链路；对应 EasyTier `--latency-first`。 */
    val latencyFirst: Boolean = false,
    /** 关闭 IPv6；对应 EasyTier `--disable-ipv6`，网络不支持 IPv6 时可减少干扰。 */
    val disableIpv6: Boolean = false,
    /** 关闭对称 NAT 打洞；对应 EasyTier `--disable-sym-hole-punching`。 */
    val disableSymHolePunching: Boolean = false,
)

/** 昵称与自定义中继只影响展示与网络配置，保存前统一规范化并校验。 */
fun MultiplayerSettings.normalized(): MultiplayerSettings {
    val trimmedNickname = nickname.trim()
    require(trimmedNickname.length <= MAX_NICKNAME_LENGTH) {
        I18n.t("multiplayer.multiplayerModels.1", MAX_NICKNAME_LENGTH)
    }
    require(trimmedNickname.none { it.isISOControl() }) { I18n.t("multiplayer.multiplayerModels.2") }
    val relays = relayServer.trim()
    require(relays.length <= MAX_RELAY_LENGTH) { I18n.t("multiplayer.multiplayerModels.3") }
    require(relays.none { it.isISOControl() }) { I18n.t("multiplayer.multiplayerModels.4") }
    parseRelays(relays).forEach { relay ->
        require(RELAY_PATTERN.matches(relay)) {
            I18n.t("multiplayer.multiplayerModels.5")
        }
    }
    return copy(nickname = trimmedNickname, relayServer = relays)
}

/** 把分号/空白分隔的自定义中继拆成去重列表；无效条目已由 [normalized] 拒绝。 */
fun MultiplayerSettings.customRelays(): List<String> = parseRelays(relayServer.trim())

private fun parseRelays(value: String): List<String> =
    value
        .split(';', ' ', '\n', '\t')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

private const val MAX_NICKNAME_LENGTH = 32
private const val MAX_RELAY_LENGTH = 512
private val RELAY_PATTERN = Regex("^(tcp|udp|ws|wss|quic|ring|faketcp)://\\S+$")

object MultiplayerPolicy {
    /** 同意文案版本；更改技术方案或数据用途时必须升版，让旧同意记录失效。 */
    const val TERMS_REVISION = "2026-10-31.2"

    /** 当前锁定的 EasyTier 运行时版本；与 `EasyTierCatalog.VERSION` 保持一致。 */
    const val CORE_VERSION = "2.6.4"
}
