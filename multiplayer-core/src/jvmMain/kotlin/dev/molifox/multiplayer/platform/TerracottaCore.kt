// 联机核心的进程与控制接口：隔离真实进程，便于单元测试用假实现驱动状态机。
package dev.molifox.multiplayer.platform

/** 陶瓦核心的 HTTP 控制接口；实现为 [TerracottaApi]。 */
interface TerracottaControl {
    fun snapshot(): TerracottaSnapshot

    fun startScanning(
        player: String,
        nodes: List<String>,
    )

    fun startGuesting(
        room: String,
        player: String,
        nodes: List<String>,
    )

    fun idle()
}

/** 联机核心进程：启动后拿到 [api]，退出后 [isAlive] 变为 false。 */
interface TerracottaCore {
    val api: TerracottaControl?

    fun start(): TerracottaControl

    fun isAlive(): Boolean

    fun stop()

    /** 最近的核心输出，用于失败时定位问题。 */
    fun recentOutput(): String
}
