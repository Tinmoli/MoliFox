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
private const val SETTINGS_SCROLL_ATTEMPTS = 16
private const val SETTINGS_SCROLL_AMOUNT = 3

/** 下载成功后观察一小段时间，确认没有自动重试导致下载次数继续增长。 */
private const val POST_DOWNLOAD_SETTLE_MILLIS = 400L

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
                        "MULTIPLAYER_UI_PASSED: consent, auto runtime download, host, join, " +
                            "guides, settings, advanced switches, nat diagnostics",
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
    verifyRoomFlow(ui, fixture)
    verifySettings(ui, fixture)
}

/**
 * 首次进入：拒绝后只留下说明入口；再次同意并勾选后直接开始下载运行时，不需要再点一次下载按钮。
 *
 * 先故意让第一次下载失败，验证界面只留下错误提示与“重试下载”，且不会自动无限重试。
 */
private fun confirmConsent(
    ui: UiProbe,
    fixture: MultiplayerUiFixture,
) {
    ui.await("首次联机说明") { ui.exists("同意并开始下载") }
    check(!ui.enabled("同意并开始下载"))
    check(!ui.exists("联机设置"))
    ui.click("暂不使用")
    ui.await("拒绝后仅显示说明入口") { ui.exists("阅读并同意联机说明") }
    check(!ui.exists("房主教程"))
    check(!ui.exists("创建房间"))
    check(!ui.exists("联机设置"))
    check(!ui.exists("下载官方 EasyTier"))
    check(fixture.downloads.get() == 0)
    ui.click("阅读并同意联机说明")
    repeat(SETTINGS_SCROLL_ATTEMPTS) {
        if (!ui.exists("我已阅读并了解以上说明")) ui.scroll(SETTINGS_SCROLL_AMOUNT)
    }
    ui.click("我已阅读并了解以上说明", role = AccessibleRole.CHECK_BOX)
    fixture.failNextDownload = true
    check(fixture.downloads.get() == 0)
    ui.click("同意并开始下载")
    // 只需要点“同意”：下载已经自动开始并失败，界面给出错误与“重试下载”。
    ui.await("首次下载失败可重试") { ui.exists("重试下载") }
    check(fixture.downloads.get() == 1)
    check(ui.exists("模拟 EasyTier 下载失败"))
    ui.click("重试下载")
    ui.await("重试后运行时就绪") {
        val runtime = fixture.backend.state.value.runtime
        runtime?.installed == true
    }
    check(fixture.downloads.get() == 2)
    // 失败后不再自动重试：成功之后再观察一段时间，下载次数不应增长。
    Thread.sleep(POST_DOWNLOAD_SETTLE_MILLIS)
    check(fixture.downloads.get() == 2)
    ui.await("错误提示随重试成功清除") { !ui.exists("模拟 EasyTier 下载失败") }
    ui.await("进入联机大厅") { ui.exists("联机大厅") }
    check(!ui.exists("下载官方 EasyTier"))
    check(!ui.exists("重试下载"))
}

/** 刷新世界、创建房间、复制邀请码、关闭房间、用邀请码加入与退出房间。 */
private fun verifyRoomFlow(
    ui: UiProbe,
    fixture: MultiplayerUiFixture,
) {
    ui.capture("multiplayer-lobby")
    ui.await("房主入口可用") { ui.exists("刷新世界") }
    check(ui.exists("创建房间"))
    check(ui.exists("还没有发现局域网世界；开放后点“刷新世界”。"))
    ui.click("刷新世界")
    ui.await("发现局域网世界") { ui.exists("茉狐的小屋") }
    ui.click("创建房间")
    ui.await("房主已启动房间") { ui.exists("房间已开启") && ui.exists(FIXTURE_ROOM_CODE) }
    check(fixture.hostedPort == FIXTURE_FIRST_WORLD_PORT)
    ui.capture("multiplayer-room")
    ui.click("复制邀请码")
    ui.click("关闭房间")
    ui.await("恢复加入入口") { ui.exists("邀请码") }
    ui.type("邀请码", FIXTURE_ROOM_CODE)
    ui.click("加入房间", role = AccessibleRole.PUSH_BUTTON)
    ui.await("房客已加入") { ui.exists("已加入房间") }
    check(ui.exists(FIXTURE_GUEST_ADDRESS))
    ui.capture("multiplayer-guest")
    ui.click("退出房间")
    ui.await("退出后回到大厅") { ui.exists("创建房间") }
}

/** 教程页可切换；网络诊断、高级开关与昵称/中继保存在设置页内完成。 */
private fun verifySettings(
    ui: UiProbe,
    fixture: MultiplayerUiFixture,
) {
    ui.tab("房主教程")
    ui.capture("multiplayer-host-guide")
    ui.tab("房客教程")
    ui.capture("multiplayer-guest-guide")
    ui.tab("联机设置")
    ui.await("设置页可见") { ui.exists("联机昵称") }
    ui.type("联机昵称", "小狐")
    ui.await("中继设置可见") { ui.exists("自定义中继") }
    ui.type("自定义中继", "tcp://relay.example.com:11010")
    verifyNatDiagnostics(ui, fixture)
    verifyAdvancedSettings(ui, fixture)
}

/** 网络诊断：按钮存在、点击后按中文映射展示 UDP/TCP/IPv6 与建议，且防抖只调用一次。 */
private fun verifyNatDiagnostics(
    ui: UiProbe,
    fixture: MultiplayerUiFixture,
) {
    repeat(SETTINGS_SCROLL_ATTEMPTS) {
        if (!ui.exists("测试网络类型（NAT）")) ui.scroll(SETTINGS_SCROLL_AMOUNT)
    }
    ui.click("测试网络类型（NAT）")
    ui.await("网络诊断结果") { ui.exists("UDP 类型：对称 NAT") }
    check(ui.exists("TCP 类型：端口受限锥形"))
    check(ui.exists("IPv6：不支持"))
    check(
        ui.exists(
            "对称 NAT 下跨网络联机可能连不上，建议双方可连中继或换网络；同一局域网通常仍可直连。",
        ),
    )
    check(fixture.natTests.get() == 1)
    ui.capture("multiplayer-settings-diagnostics")
}

/** 高级开关默认关，打开“低延迟优先”后与昵称、中继一起保存写回后端设置。 */
private fun verifyAdvancedSettings(
    ui: UiProbe,
    fixture: MultiplayerUiFixture,
) {
    repeat(SETTINGS_SCROLL_ATTEMPTS) {
        if (!ui.exists("低延迟优先 开关")) ui.scroll(SETTINGS_SCROLL_AMOUNT)
    }
    check(ui.exists("高级联机开关"))
    check(ui.exists("关闭 IPv6 开关"))
    check(ui.exists("关闭对称 NAT 打洞 开关"))
    ui.click("低延迟优先 开关")
    ui.capture("multiplayer-settings-advanced")
    repeat(SETTINGS_SCROLL_ATTEMPTS) {
        if (!ui.exists("保存联机设置")) ui.scroll(SETTINGS_SCROLL_AMOUNT)
    }
    ui.click("保存联机设置")
    ui.await("设置保存提示") { ui.exists("已保存。昵称与自定义中继在下次创建或加入房间时生效。") }
    val saved = fixture.backend.settings.value
    check(saved.nickname == "小狐")
    check(saved.relayServer == "tcp://relay.example.com:11010")
    check(saved.latencyFirst)
    check(!saved.disableIpv6)
    check(!saved.disableSymHolePunching)
}
