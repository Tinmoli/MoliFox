// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在单个临时窗口运行当前页面流程，始终记录结果并关闭测试自己的窗口。

package dev.molifox.desktop.checks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.molifox.desktop.AppHeader
import dev.molifox.desktop.MoliApp
import dev.molifox.desktop.MoliTheme
import dev.molifox.desktop.UiActions
import java.awt.EventQueue
import java.awt.event.WindowEvent
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val WINDOW_POLL_LIMIT = 200
private const val WINDOW_POLL_MILLIS = 50L

fun main() {
    val root = Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize()
    check(root.startsWith(Path.of(".work/tests").toAbsolutePath().normalize()))
    val outcome = AtomicReference<Result<Unit>>()
    UiFixture(root).use { fixture ->
        val window = AtomicReference<ComposeWindow>()
        val driver =
            thread(name = "MoliFox UI scenarios") {
                drive(window, fixture, outcome)
            }
        application(exitProcessOnExit = false) {
            Window(
                onCloseRequest = ::exitApplication,
                title = "MoliFox UI Checks",
                undecorated = true,
                state =
                    rememberWindowState(
                        width = 1180.dp,
                        height = 720.dp,
                        position = WindowPosition(40.dp, 40.dp),
                    ),
            ) {
                SideEffect { window.set(this.window) }
                val state by fixture.controller.state.collectAsState()
                val actions =
                    remember { UiActions(fixture.controller, this.window, fixture.picker) }
                MoliTheme(state.data.settings) {
                    Column(Modifier.fillMaxSize()) {
                        AppHeader(actions.navigation, state) {}
                        Box(Modifier.weight(1f).fillMaxSize()) { MoliApp(state, actions) }
                    }
                }
            }
        }
        driver.join()
        checkNotNull(outcome.get()).getOrThrow()
    }
}

private fun drive(
    window: AtomicReference<ComposeWindow>,
    fixture: UiFixture,
    outcome: AtomicReference<Result<Unit>>,
) {
    val passed = mutableListOf<String>()
    val result =
        runCatching {
            val target = awaitWindow(window)
            val ui = UiProbe(target, fixture.root.resolve("screenshots"))
            runCatching { runScenarios(ui, fixture, passed) }
                .onFailure {
                    runCatching { ui.capture("failure") }
                        .onFailure { failure -> println("UI_CAPTURE_FAILED ${failure.message}") }
                }.getOrThrow()
        }
    try {
        val reporting = runCatching { writeReport(fixture, passed, result.exceptionOrNull()) }
        val reportFailure = reporting.exceptionOrNull()
        if (reportFailure == null) {
            outcome.set(result)
        } else {
            result.exceptionOrNull()?.let(reportFailure::addSuppressed)
            outcome.set(Result.failure(reportFailure))
        }
    } finally {
        window.get()?.let { target ->
            EventQueue.invokeLater {
                target.dispatchEvent(
                    WindowEvent(target, WindowEvent.WINDOW_CLOSING),
                )
            }
        }
    }
}

private fun awaitWindow(window: AtomicReference<ComposeWindow>): ComposeWindow {
    repeat(WINDOW_POLL_LIMIT) {
        window.get()?.takeIf { it.isShowing }?.let { return it }
        Thread.sleep(WINDOW_POLL_MILLIS)
    }
    error("测试窗口没有显示")
}

private fun runScenarios(
    ui: UiProbe,
    fixture: UiFixture,
    passed: MutableList<String>,
) {
    ui.await("首页可操作") { ui.exists("版本设置") }
    ui.capture("home")
    val scenarios =
        listOf<Pair<String, () -> Unit>>(
            "account-input-and-persistence" to { checkAccount(ui, fixture) },
            "settings-and-theme" to { checkSettings(ui, fixture) },
            "instance-folders-tabs-export" to { checkInstance(ui, fixture) },
            "instance-move-cancel-and-confirm" to { checkMove(ui, fixture) },
            "download-categories-and-manifest" to { checkDownloads(ui, fixture) },
        )
    for ((name, scenario) in scenarios) {
        println("UI_SCENARIO_BEGIN $name")
        scenario()
        passed += name
        println("UI_SCENARIO_PASSED $name")
    }
    check(fixture.network.unexpected.isEmpty()) { fixture.network.unexpected.toString() }
    check(fixture.platform.openedUrls.isEmpty()) { "测试不应打开外部网页" }
}

private fun writeReport(
    fixture: UiFixture,
    passed: List<String>,
    failure: Throwable?,
) {
    val report = Path.of("docs/validation/uiSmoke-actions.json")
    writeFixtureJson(
        report,
        mapOf(
            "at" to Instant.now().toString(),
            "status" to if (failure == null) "passed" else "failed",
            "passed" to passed,
            "failure" to failure?.toString(),
            "externalNetworkConnections" to 0,
            "fixtureRequests" to fixture.network.requested.size,
            "unhandledFixtureRequests" to fixture.network.unexpected.toList(),
            "scope" to
                "Current AppHeader and MoliApp; accessibility buttons and native text input; " +
                "fake platform and transport; " +
                "no native picker or real game",
        ),
    )
}
