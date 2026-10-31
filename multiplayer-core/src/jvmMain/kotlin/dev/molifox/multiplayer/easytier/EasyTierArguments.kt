// EasyTier 启动参数集中构建：角色、网络码、hostname 与端口白名单只在这里拼装。
package dev.molifox.multiplayer.easytier

import dev.molifox.multiplayer.protocol.ScaffoldingCode
import java.util.UUID

/** EasyTier 节点角色；决定是否设置固定虚拟 IP、端口白名单与 DHCP。 */
enum class EasyTierRole { HOST, GUEST }

/** 与 PCL CE 一致的附加开关；全部关闭时保持默认行为，不额外传参数。 */
data class EasyTierTuning(
    val latencyFirst: Boolean = false,
    val disableIpv6: Boolean = false,
    val disableSymHolePunching: Boolean = false,
) {
    /** 追加到双方共用参数里的开关；关闭的不出现，避免改变 EasyTier 默认值。 */
    fun flags(): List<String> =
        buildList {
            if (latencyFirst) add("--latency-first")
            if (disableIpv6) add("--disable-ipv6")
            if (disableSymHolePunching) add("--disable-sym-hole-punching")
        }
}

/**
 * EasyTier 节点发现与保底中继。
 *
 * - [NODE_LIST_URL]：PCL CE 使用的活动节点接口；启动器在创建/加入房间前实时拉取，
 *   只取 `is_active && allow_relay` 的地址（字段名见 PCL CE `PublicNodeDto`）。
 * - [HTTP_NODE_LISTS]：EasyTier 原生支持的 HTTP 节点列表入口，由核心在连接时实时解析；
 *   启动器只把它们当作 `-p` 兜底，不自己解析。
 * - [DEFAULT]：前面两项都不可用时仍会尝试的固定节点，保证参数列表不为空。
 *
 * 没有这些节点时跨 NAT 基本无法连通；用户可在设置里追加自定义中继。
 */
object EasyTierRelays {
    const val NODE_LIST_URL =
        "https://uptime.easytier.cn/api/nodes?page=1&per_page=50&is_active=true"

    /** 官方固定节点；域名或网络不可用时由 EasyTier 自行重试，不会阻断房间创建。 */
    private val OFFICIAL: List<String> =
        listOf(
            "tcp://public.easytier.top:11010",
            "tcp://public2.easytier.cn:54321",
        )

    /** EasyTier 自己支持 HTTP 节点列表；内容变化会在连接时重新拉取。 */
    private val HTTP_NODE_LISTS: List<String> =
        listOf(
            "https://etnode.zkitefly.eu.org/node1",
            "https://etnode.zkitefly.eu.org/node2",
            "https://etnode.zkitefly.eu.org/-node1",
            "https://etnode.zkitefly.eu.org/-node2",
        )

    val DEFAULT: List<String> = OFFICIAL + HTTP_NODE_LISTS
}

/** 启动一个 EasyTier 节点所需的全部业务参数；RPC 端口由运行时分配。 */
data class EasyTierNodeRequest(
    val role: EasyTierRole,
    /** 来自 21 字符房间码的 EasyTier 网络名。 */
    val networkName: String,
    /** 来自房间码的网络密钥。 */
    val networkSecret: String,
    /** 本机在虚拟网络里的稳定标识，避免多开时相互覆盖。 */
    val machineId: String,
    /** EasyTier 主机名；房主必须是 `scaffolding-mc-server-<端口>`。 */
    val hostname: String,
    /** 房主需要放行的本机端口（Scaffolding 与 Minecraft）；房客为空。 */
    val whitelistedPorts: List<Int> = emptyList(),
    /** 叠加在默认节点之后的自定义中继。 */
    val extraRelays: List<String> = emptyList(),
    /** 用户附加开关；默认全部关闭。 */
    val tuning: EasyTierTuning = EasyTierTuning(),
) {
    companion object {
        /** 房主节点：固定虚拟 IP 与 hostname，白名单只放 Scaffolding 和 MC 端口。 */
        fun host(
            code: ScaffoldingCode,
            machineId: String,
            scaffoldingPort: Int,
            minecraftPort: Int,
            extraRelays: List<String> = emptyList(),
            tuning: EasyTierTuning = EasyTierTuning(),
        ): EasyTierNodeRequest =
            EasyTierNodeRequest(
                role = EasyTierRole.HOST,
                networkName = code.networkName,
                networkSecret = code.networkSecret,
                machineId = machineId,
                hostname = EasyTierArguments.hostHostname(scaffoldingPort),
                whitelistedPorts = listOf(scaffoldingPort, minecraftPort),
                extraRelays = extraRelays,
                tuning = tuning,
            )

        /** 房客节点：随机 hostname，避免与房主的固定格式冲突。 */
        fun guest(
            code: ScaffoldingCode,
            machineId: String,
            hostname: String = UUID.randomUUID().toString(),
            extraRelays: List<String> = emptyList(),
            tuning: EasyTierTuning = EasyTierTuning(),
        ): EasyTierNodeRequest =
            EasyTierNodeRequest(
                role = EasyTierRole.GUEST,
                networkName = code.networkName,
                networkSecret = code.networkSecret,
                machineId = machineId,
                hostname = hostname,
                extraRelays = extraRelays,
                tuning = tuning,
            )
    }
}

/**
 * 把 [EasyTierNodeRequest] 转成 `easytier-core` 参数列表。
 *
 * 关键互通约定（与 PCL CE 一致，改动前必须核对）：
 * - 房主虚拟 IP 固定 `10.114.51.41`，hostname `scaffolding-mc-server-<Scaffolding 端口>`；
 * - 双方都 `--no-tun`，不安装驱动、不要求管理员；
 * - RPC 门户只绑 `127.0.0.1`，避免局域网内其他人控制本机 EasyTier。
 */
object EasyTierArguments {
    /** PCL CE 房主固定虚拟地址；房客从 peer 列表读取，不需要预置。 */
    const val HOST_INTERFACE = "10.114.51.41"

    private const val HOST_HOSTNAME_PREFIX = "scaffolding-mc-server-"
    private const val LOOPBACK_ADDRESS = "127.0.0.1"
    private const val WILDCARD_LISTENER = "0.0.0.0"
    private const val ENCRYPTION_ALGORITHM = "aes-gcm"
    private const val COMPRESSION_ALGORITHM = "zstd"
    private const val DEFAULT_PROTOCOL = "tcp"
    private const val NO_WHITELIST_PORT = "0"
    private const val PRIVATE_MODE_ENABLED = "true"
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
    private const val MAX_RELAYS = 6

    /** 房主 hostname：Scaffolding 端口写在末尾，供房客解析。 */
    fun hostHostname(scaffoldingPort: Int): String = "$HOST_HOSTNAME_PREFIX$scaffoldingPort"

    /** `127.0.0.1:<rpc>` 形式的 RPC 门户地址。 */
    fun rpcPortal(rpcPort: Int): String = "$LOOPBACK_ADDRESS:$rpcPort"

    /** 生成核心进程参数；[rpcPort] 必须是可供 CLI 回环访问的空闲端口。 */
    fun build(
        request: EasyTierNodeRequest,
        rpcPort: Int,
    ): List<String> {
        validate(request, rpcPort)
        val arguments = mutableListOf<String>()
        arguments += COMMON_FLAGS
        arguments += listOf("--network-name", request.networkName)
        arguments += listOf("--network-secret", request.networkSecret)
        arguments += listOf("--machine-id", request.machineId)
        arguments += listOf("--hostname", request.hostname)
        arguments += listOf("--rpc-portal", rpcPortal(rpcPort))
        arguments += COMMON_TUNING
        arguments += request.tuning.flags()
        when (request.role) {
            EasyTierRole.HOST -> arguments += hostArguments(request)
            EasyTierRole.GUEST -> arguments += GUEST_ARGUMENTS
        }
        arguments += listenerArguments()
        relaysOf(request).forEach { relay -> arguments += listOf("-p", relay) }
        return arguments
    }

    private fun hostArguments(request: EasyTierNodeRequest): List<String> {
        val arguments = mutableListOf("-i", HOST_INTERFACE)
        request.whitelistedPorts.forEach { port ->
            arguments += listOf("--tcp-whitelist", port.toString())
            arguments += listOf("--udp-whitelist", port.toString())
        }
        return arguments
    }

    private fun listenerArguments(): List<String> =
        listOf(
            "-l",
            "tcp://$WILDCARD_LISTENER:0",
            "-l",
            "udp://$WILDCARD_LISTENER:0",
        )

    /**
     * 自定义/实时节点优先于固定兜底，完全相同的地址只保留第一次出现。
     *
     * 与 PCL CE 一样最多保留 6 个对端：中继过多会拖慢握手，且 PCL CE 同样 `Take(6)`。
     */
    private fun relaysOf(request: EasyTierNodeRequest): List<String> =
        (request.extraRelays + EasyTierRelays.DEFAULT).distinct().take(MAX_RELAYS)

    private fun validate(
        request: EasyTierNodeRequest,
        rpcPort: Int,
    ) {
        require(request.networkName.isNotBlank()) { "EasyTier 网络名不能为空" }
        require(request.networkSecret.isNotBlank()) { "EasyTier 网络密钥不能为空" }
        require(request.machineId.isNotBlank()) { "EasyTier machine-id 不能为空" }
        require(request.hostname.isNotBlank()) { "EasyTier hostname 不能为空" }
        require(rpcPort in MIN_PORT..MAX_PORT) { "EasyTier RPC 端口必须在 $MIN_PORT..$MAX_PORT 之间" }
        request.whitelistedPorts.forEach { port ->
            require(port in MIN_PORT..MAX_PORT) { "EasyTier 白名单端口非法：$port" }
        }
        if (request.role == EasyTierRole.HOST) {
            require(request.whitelistedPorts.isNotEmpty()) { "房主必须声明需要放行的端口" }
            require(request.hostname.startsWith(HOST_HOSTNAME_PREFIX)) {
                "房主 hostname 必须以 $HOST_HOSTNAME_PREFIX 开头，房客才能解析 Scaffolding 端口"
            }
        }
    }

    // 双方共用（与 PCL CE 一致）：不装 TUN、多线程收发；私有模式拒绝没有相同网络密钥的
    // 外部网络；KCP/QUIC 代理提升部分网络的转发可用性；p2p-only 限制中继只做打洞信令。
    private val COMMON_FLAGS =
        listOf(
            "--no-tun",
            "--multi-thread",
            "--enable-kcp-proxy",
            "--enable-quic-proxy",
            "--p2p-only",
            "--private-mode",
            PRIVATE_MODE_ENABLED,
        )

    private val COMMON_TUNING =
        listOf(
            "--encryption-algorithm",
            ENCRYPTION_ALGORITHM,
            "--compression",
            COMPRESSION_ALGORITHM,
            "--default-protocol",
            DEFAULT_PROTOCOL,
        )

    // 房客走 DHCP 拿虚拟 IP，本机不对外提供任何服务，所以白名单是 0。
    private val GUEST_ARGUMENTS =
        listOf(
            "-d",
            "--tcp-whitelist",
            NO_WHITELIST_PORT,
            "--udp-whitelist",
            NO_WHITELIST_PORT,
        )
}
