// 验收本轮缩放、窗口透明、中文字体、等宽弹层及启动错误窗口，仅操作测试创建的窗口。
package dev.molifox.desktop.checks

import dev.molifox.bootstrap.StartupFailureDialog
import dev.molifox.desktop.canonicalSystemFontName
import dev.molifox.desktop.fontSupportMessage
import java.awt.GraphicsEnvironment
import java.awt.Window
import javax.swing.JDialog
import kotlin.concurrent.thread

private const val SCROLL_ATTEMPTS = 20
private const val SCROLL_AMOUNT = 4
private const val SCROLL_TOP = -30
private const val SCALE_SLIDER_FRACTION = .7f
private const val DEFAULT_UI_SCALE = 1f

internal fun checkAppearanceRefinements(
    ui: UiProbe,
    fixture: UiFixture,
) {
    ui.tab("设置", navigation = true)
    ui.tab("外观")
    ui.await("外观就绪") { ui.exists("浅色") }
    check(!ui.exists("启动游戏")) { "外观页不应再保留启动按钮微型预览" }
    check(!ui.exists("界面缩放滑块")) { "缩放只应放在字体与缩放子页" }
    ui.tab("字体与缩放")
    scrollTo(ui, "界面缩放滑块")
    ui.clickAt("界面缩放滑块", SCALE_SLIDER_FRACTION)
    ui.await("界面缩放保存") { fixture.controller.store.data.settings.uiScale > DEFAULT_UI_SCALE }
    ui.capture("appearance-scale")
    fixture.controller.saveSettings(
        fixture.controller.store.data.settings
            .copy(uiScale = DEFAULT_UI_SCALE),
    )
    checkChineseFonts(fixture, ui)
    ui.tab("主题与效果")
    scrollTo(ui, "窗口透明")
    val initial = fixture.controller.store.data.settings.windowTransparent
    toggleTransparency(ui, fixture, !initial)
    toggleTransparency(ui, fixture, initial)
    ui.capture("appearance-transparent-toggle")
    ui.tab("开始", navigation = true)
}

private fun checkChineseFonts(
    fixture: UiFixture,
    ui: UiProbe,
) {
    val available =
        GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .availableFontFamilyNames
            .toSet()
    val chinese =
        listOf("SimSun", "Microsoft YaHei", "KaiTi", "宋体", "微软雅黑", "楷体")
            .filter { it in available }
            .distinctBy(::canonicalSystemFontName)
    check(chinese.size >= 2) { "本 Windows 验收需要至少两种中文字体" }
    chinese.take(2).forEach { name ->
        check(fontSupportMessage("system:$name") == null) { "中文字体应能显示中文：$name" }
        fixture.controller.saveSettings(
            fixture.controller.store.data.settings
                .copy(fontChoice = "system:$name"),
        )
        ui.await("选择中文字体") { ui.exists(name) }
        ui.capture(
            "appearance-chinese-" + canonicalSystemFontName(name).replace(' ', '-').lowercase(),
        )
    }
    fixture.controller.saveSettings(
        fixture.controller.store.data.settings
            .copy(fontChoice = ""),
    )
}

private fun toggleTransparency(
    ui: UiProbe,
    fixture: UiFixture,
    target: Boolean,
) {
    ui.click("窗口透明开关")
    ui.await("透明设置保存且窗口仍可操作") {
        fixture.controller.store.data.settings.windowTransparent == target && ui.exists("窗口透明")
    }
}

private fun scrollTo(
    ui: UiProbe,
    label: String,
) {
    ui.scroll(SCROLL_TOP)
    repeat(SCROLL_ATTEMPTS) { if (!ui.exists(label)) ui.scroll(SCROLL_AMOUNT) }
    ui.await("显示控件：$label") { ui.exists(label) }
}

internal fun checkStartupErrorDialog(
    ui: UiProbe,
    fixture: UiFixture,
) {
    val before = Window.getWindows().toSet()
    val presenter =
        thread(name = "MoliFox test error presenter") {
            StartupFailureDialog.show(
                IllegalStateException("窗口效果测试错误"),
                fixture.root.resolve("logs/startup.log"),
            )
        }
    val dialog =
        ui.awaitValue("测试错误窗口") {
            Window.getWindows().filterIsInstance<JDialog>().find { it !in before && it.isShowing }
        }
    try {
        val errorUi = UiProbe(dialog, fixture.root.resolve("screenshots"))
        errorUi.click("查看技术详情")
        errorUi.capture("startup-error-details")
        errorUi.click("关闭")
    } finally {
        onTestUiThread { dialog.dispose() }
        presenter.join()
    }
}
