// 在独立测试窗口点击联机流程，结束时只关闭测试窗口和自己的临时数据。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.molifox.core.LauncherSettings
import dev.molifox.desktop.MoliTheme
import dev.molifox.desktop.checks.UiProbe
import dev.molifox.desktop.checks.scroll
import java.awt.EventQueue
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.accessibility.AccessibleRole
import kotlin.concurrent.thread

private const val WINDOW_POLL_LIMIT = 200
private const val WINDOW_POLL_MILLIS = 50L
private const val SETTINGS_SCROLL_ATTEMPTS = 12
private const val SETTINGS_SCROLL_AMOUNT = 3

fun main() {
    val root = Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize()
    require(root.startsWith(Path.of(".work/tests").toAbsolutePath().normalize()))
    val outcome = AtomicReference<Result<Unit>>()
    val window = AtomicReference<ComposeWindow>()
    val fixture = MultiplayerUiFixture(root)
    val driver =
        thread(name = "MoliFox multiplayer UI scenarios") {
            outcome.set(
                runCatching {
                    val ui = UiProbe(awaitTestWindow(window), root.resolve("screenshots"))
                    runScenarios(ui, fixture)
                    println(
                        "MULTIPLAYER_UI_PASSED: consent, download, host, join, guides, settings",
                    )
                },
            )
            window.get()?.let { target ->
                EventQueue.invokeLater {
                    target.dispatchEvent(WindowEvent(target, WindowEvent.WINDOW_CLOSING))
                }
            }
        }
    showTestWindow(window, fixture)
    driver.join()
    requireNotNull(outcome.get()).getOrThrow()
}

private fun showTestWindow(
    window: AtomicReference<ComposeWindow>,
    fixture: MultiplayerUiFixture,
) {
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "茉狐 · 联机界面验证",
            state =
                rememberWindowState(
                    width = 1080.dp,
                    height = 740.dp,
                    position = WindowPosition(40.dp, 40.dp),
                ),
        ) {
            SideEffect { window.set(this.window) }
            MoliTheme(LauncherSettings(themeMode = "light")) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.padding(16.dp)) {
                        MultiplayerScreen(
                            backend = fixture.backend,
                            onCopy = { copied -> check(copied.isNotBlank()) },
                        )
                    }
                }
            }
        }
    }
}

private fun awaitTestWindow(window: AtomicReference<ComposeWindow>): ComposeWindow {
    repeat(WINDOW_POLL_LIMIT) {
        window.get()?.takeIf { it.isShowing }?.let { return it }
        Thread.sleep(WINDOW_POLL_MILLIS)
    }
    error("联机测试窗口未显示")
}

private fun runScenarios(
    ui: UiProbe,
    fixture: MultiplayerUiFixture,
) {
    confirmConsent(ui, fixture)
    verifyRoomFlow(ui)
    verifySettings(ui)
}

/** 首次进入：拒绝后只留下说明入口，确认并取消一次下载，再真实下载核心。 */
private fun confirmConsent(
    ui: UiProbe,
    fixture: MultiplayerUiFixture,
) {
    ui.await("首次联机说明") { ui.exists("同意并继续") }
    check(!ui.enabled("同意并继续"))
    check(!ui.exists("联机设置"))
    ui.click("暂不使用")
    ui.await("拒绝后仅显示说明入口") { ui.exists("阅读联机说明") }
    check(!ui.exists("房主教程"))
    check(!ui.exists("创建房间"))
    check(!ui.exists("联机设置"))
    ui.click("阅读联机说明")
    repeat(SETTINGS_SCROLL_ATTEMPTS) {
        if (!ui.exists("我已阅读并了解以上说明")) ui.scroll(SETTINGS_SCROLL_AMOUNT)
    }
    ui.click("我已阅读并了解以上说明", role = AccessibleRole.CHECK_BOX)
    ui.click("同意并继续")
    ui.await("进入联机大厅") { ui.exists("联机大厅") }
    ui.click("准备联机核心")
    ui.await("下载确认") { ui.exists("取消") }
    ui.click("取消")
    check(fixture.downloads.get() == 0)
    ui.click("准备联机核心")
    ui.click("下载联机核心")
    ui.await("核心准备完成") {
        fixture.backend.state.value.core
            ?.installed == true
    }
    check(fixture.downloads.get() == 1)
}

/** 创建房间、复制邀请码、关闭房间、用邀请码加入与退出房间。 */
private fun verifyRoomFlow(ui: UiProbe) {
    ui.capture("multiplayer-lobby")
    ui.click("创建房间")
    ui.await("房主已启动房间") { ui.exists("已启动房间") && ui.exists(FIXTURE_ROOM_CODE) }
    ui.capture("multiplayer-room")
    ui.click("复制邀请码")
    ui.click("关闭房间")
    ui.await("恢复加入入口") { ui.exists("邀请码") }
    ui.type("邀请码", FIXTURE_ROOM_CODE)
    ui.click("加入房间", role = AccessibleRole.PUSH_BUTTON)
    ui.await("房客已加入") { ui.exists("已加入房间") }
    ui.capture("multiplayer-guest")
    ui.click("退出房间")
    ui.await("退出后回到大厅") { ui.exists("创建房间") }
}

/** 教程页可切换，联机昵称可保存且有明确提示。 */
private fun verifySettings(ui: UiProbe) {
    ui.tab("房主教程")
    ui.capture("multiplayer-host-guide")
    ui.tab("房客教程")
    ui.capture("multiplayer-guest-guide")
    ui.tab("联机设置")
    ui.await("设置页可见") { ui.exists("联机昵称") }
    ui.type("联机昵称", "小狐")
    ui.capture("multiplayer-settings")
    repeat(SETTINGS_SCROLL_ATTEMPTS) {
        if (!ui.exists("保存联机设置")) ui.scroll(SETTINGS_SCROLL_AMOUNT)
    }
    ui.click("保存联机设置")
    ui.await("设置保存提示") { ui.exists("已保存。昵称在下次创建或加入房间时生效。") }
}
