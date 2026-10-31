// LAN 世界探测的 ping 抽象：默认实现走 Minecraft status 协议，离线测试可注入假实现。
package dev.molifox.multiplayer.lan

import dev.molifox.multiplayer.common.LanWorld

/**
 * 对一个候选回环端口做一次 Minecraft status 查询。
 *
 * 失败（连接被拒、超时、报文非法）一律返回 null，不抛异常：发现层会把这样的端口
 * 当作“游戏已关闭”直接忽略。
 */
interface MinecraftStatusPinger {
    fun ping(
        host: String,
        port: Int,
        timeoutMillis: Long = DEFAULT_PING_TIMEOUT_MILLIS,
    ): LanWorld?
}

/** 单次 ping 的默认超时；与 PCL CE 的 2 秒一致。 */
const val DEFAULT_PING_TIMEOUT_MILLIS = 2_000L
