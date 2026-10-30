// 联机公共模型：陶瓦（Terracotta）核心提供的房间状态，不引用界面、磁盘或进程类型。
package dev.molifox.multiplayer.common

/** 联机页当前阶段；与陶瓦核心 `/state` 返回的 `state` 字段一一对应。 */
enum class MultiplayerPhase {
    /** 核心未启动或已退出，可下载/启动。 */
    Idle,

    /** 正在下载联机核心。 */
    Downloading,

    /** 正在启动联机核心。 */
    Launching,

    /** 核心就绪，可创建或加入房间。 */
    Waiting,

    /** 正在扫描局域网世界。 */
    HostScanning,

    /** 正在创建房间。 */
    HostStarting,

    /** 房间已创建，显示邀请码。 */
    HostReady,

    /** 正在核对邀请码并连接房主。 */
    GuestConnecting,

    /** 正在加入房间。 */
    GuestStarting,

    /** 已加入房间，等待进入游戏。 */
    GuestReady,

    /** 出错；[MultiplayerState.error] 说明原因。 */
    Failed,
}

enum class RoomRole { Host, Guest }

/** 陶瓦核心本身无法继续运行的原因；可重试的下载/安装失败与核心通讯失败分开。 */
enum class TerracottaFatality { NETWORK, INSTALL, CORE, UNKNOWN }

/** 陶瓦核心上报的加入失败类型，对应 `state == "exception"`。 */
enum class TerracottaJoinFailure {
    PING_HOST_FAIL,
    PING_HOST_RST,
    GUEST_ET_CRASH,
    HOST_ET_CRASH,
    PING_SERVER_RST,
    SCAFFOLDING_INVALID_RESPONSE,
    UNKNOWN,
}

/** 房主与房客的网络质量估计，仅用于提示。 */
enum class TerracottaDifficulty { UNKNOWN, EASIEST, SIMPLE, MEDIUM, TOUGH }

enum class PlayerKind { HOST, LOCAL, GUEST }

/** 房间里的一名玩家；[machineId] 来自陶瓦核心，用于列表稳定 key。 */
data class RoomPlayer(
    val machineId: String,
    val name: String,
    val kind: PlayerKind,
)

/** 联机核心的本地状态：版本、平台、目录与下载大小。 */
data class CoreStatus(
    val installed: Boolean,
    val version: String,
    val platform: String,
    val downloadBytes: Long,
    val directory: String,
)

/** 联机页的全部可见状态；界面只读，所有写操作由后端完成。 */
data class MultiplayerState(
    val phase: MultiplayerPhase = MultiplayerPhase.Idle,
    val role: RoomRole? = null,
    /** 下载进度 0..1；未知时为 null。 */
    val progress: Float? = null,
    /** 房主邀请码。 */
    val roomCode: String? = null,
    /** 房客的备用联机地址；来自陶瓦核心 `guest-ok`。 */
    val joinUrl: String? = null,
    val difficulty: TerracottaDifficulty? = null,
    val players: List<RoomPlayer> = emptyList(),
    val error: String? = null,
    val failure: TerracottaJoinFailure? = null,
    val fatality: TerracottaFatality? = null,
    val core: CoreStatus? = null,
) {
    val roomOpen: Boolean get() = phase == MultiplayerPhase.HostReady

    val connected: Boolean
        get() = phase == MultiplayerPhase.HostReady || phase == MultiplayerPhase.GuestReady

    val busy: Boolean
        get() =
            phase in
                setOf(
                    MultiplayerPhase.Downloading,
                    MultiplayerPhase.Launching,
                    MultiplayerPhase.HostScanning,
                    MultiplayerPhase.HostStarting,
                    MultiplayerPhase.GuestConnecting,
                    MultiplayerPhase.GuestStarting,
                )
}

data class MultiplayerSettings(
    val enabled: Boolean = true,
    /** 显示给其他玩家的名字；空串时由账户名兜底。 */
    val nickname: String = "",
    val acceptedTermsRevision: String = "",
)

/** 昵称只用于房间列表显示；规范化时去掉首尾空白并校验长度与控制字符。 */
fun MultiplayerSettings.normalized(): MultiplayerSettings {
    val trimmed = nickname.trim()
    require(trimmed.length <= MAX_NICKNAME_LENGTH) { "昵称最多 $MAX_NICKNAME_LENGTH 个字符" }
    require(trimmed.none { it.isISOControl() }) { "昵称不能包含控制字符" }
    return copy(nickname = trimmed)
}

private const val MAX_NICKNAME_LENGTH = 32

object MultiplayerPolicy {
    const val TERMS_REVISION = "2026-10-31.1"
    const val CORE_VERSION = "0.4.2"
}
