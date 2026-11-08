// 网络类型诊断的公共模型：界面只依赖枚举与诊断结果，不接触 EasyTier CLI。
package dev.molifox.multiplayer.common

/**
 * EasyTier STUN 返回的 NAT 类型；声明顺序与协议编码一致（0..8），
 * [fromCode] 直接用下标映射，未知或缺失编码归为 [Unknown]。
 */
enum class NatType {
    OpenInternet,
    NoPat,
    FullCone,
    Restricted,
    PortRestricted,
    SymmetricEasy,
    Symmetric,
    SymmetricFirewall,
    UdpBlocked,
    Unknown,
    ;

    companion object {
        fun fromCode(code: Int?): NatType = code?.let { entries.getOrNull(it) } ?: Unknown
    }
}

/**
 * 一次网络诊断结果。
 *
 * [publicIps] 用于判断 IPv6 与排查，界面默认只展示 NAT 类型与 IPv6 支持。
 */
data class NatDiagnostics(
    val udpNatType: NatType,
    val tcpNatType: NatType,
    val supportsIpv6: Boolean,
    val publicIps: List<String>,
)
