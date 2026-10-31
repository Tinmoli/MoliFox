// 运行时下载相关的冒烟场景：取消下载、失败后重试、本地 zip 兜底入口。
package dev.molifox.desktop.multiplayer

import dev.molifox.desktop.checks.UiProbe
import dev.molifox.multiplayer.common.LobbyPhase

/** 下载成功后观察一小段时间，确认没有自动重试导致下载次数继续增长。 */
private const val POST_DOWNLOAD_SETTLE_MILLIS = 400L

/** 取消+失败+重试共三次下载尝试；失败后重试成功时下载次数应停在这里。 */
private const val DOWNLOAD_ATTEMPTS_AFTER_RETRY = 3

/** 下载阶段显示「取消下载」；取消走 backend.cancelRuntimeInstall，回大厅且无失败错误、不自动重下。 */
internal fun verifyDownloadCancel(
    ui: UiProbe,
    fixture: MultiplayerUiFixture,
) {
    ui.await("下载中显示取消按钮") { ui.exists("取消下载") }
    check(!ui.enabled("下载官方 EasyTier"))
    ui.click("取消下载")
    ui.await("取消后回到大厅") { fixture.backend.state.value.phase == LobbyPhase.Idle }
    // 停止下载由后端负责，界面必须调用 cancelRuntimeInstall 而不是只取消等待。
    check(fixture.cancelInstallCalls.get() == 1)
    ui.await("取消后按钮消失") { !ui.exists("取消下载") }
    ui.await("可再次手动下载") { ui.enabled("下载官方 EasyTier") }
    check(fixture.backend.state.value.error == null)
    // 取消不触发自动重下：观察一小段时间，下载次数保持 1。
    Thread.sleep(POST_DOWNLOAD_SETTLE_MILLIS)
    check(fixture.downloads.get() == 1)
    check(fixture.backend.state.value.phase == LobbyPhase.Idle)
}

/** 下载失败区提供本地 zip 兜底入口与导入说明；冒烟里只验证入口存在，不弹系统选择器。 */
internal fun verifyArchiveFallback(ui: UiProbe) {
    check(ui.exists("从本地 zip 安装…"))
    check(
        ui.exists(
            "下载一直卡住时，可以用浏览器或下载工具下载官方 EasyTier 2.6.4 的 zip，" +
                "再从这里导入；导入会校验大小与 SHA-256。",
        ),
    )
}

/** 失败后手动重试成功；之后观察一段时间，确认不会自动重复下载。 */
internal fun verifyRetryAfterFailure(
    ui: UiProbe,
    fixture: MultiplayerUiFixture,
) {
    ui.click("重试下载")
    ui.await("重试后运行时就绪") {
        val runtime = fixture.backend.state.value.runtime
        runtime?.installed == true
    }
    check(fixture.downloads.get() == DOWNLOAD_ATTEMPTS_AFTER_RETRY)
    Thread.sleep(POST_DOWNLOAD_SETTLE_MILLIS)
    check(fixture.downloads.get() == DOWNLOAD_ATTEMPTS_AFTER_RETRY)
    ui.await("错误提示随重试成功清除") { !ui.exists("模拟 EasyTier 下载失败") }
    ui.await("进入联机大厅") { ui.exists("联机大厅") }
    check(!ui.exists("下载官方 EasyTier"))
    check(!ui.exists("重试下载"))
}
