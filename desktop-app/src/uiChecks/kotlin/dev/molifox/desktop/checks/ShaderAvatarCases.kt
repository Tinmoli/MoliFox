// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验收光影头像的三种来源与切换实例后不串图。

package dev.molifox.desktop.checks

private const val ROW_CLICK_OFFSET = 80
private const val AVATAR_RELOAD_MILLIS = 800L
private val SHADER_ROWS = listOf("Aurora.zip", "Cloud.zip", "Plain.zip")

/** 三个光影包分别覆盖：包内 icon.png、已登记 iconUrl、两者都没有（占位）。 */
internal fun checkShaderAvatars(
    ui: UiProbe,
    fixture: UiFixture,
) {
    openDetails(ui, fixture, "UI-Main")
    ui.tab("光影")
    val firstRequestCount = checkMainInstanceAvatars(ui, fixture)
    checkOtherInstanceIsolated(ui, fixture)
    checkRestoredAfterReturn(ui, fixture, firstRequestCount)
    ui.click("返回")
    ui.await("回到开始页") { ui.exists("版本设置") }
}

/** 主实例：三种头像来源各就各位，登记头像每次渲染只请求一次。 */
private fun checkMainInstanceAvatars(
    ui: UiProbe,
    fixture: UiFixture,
): Int {
    ui.await("三个光影包都列出") { SHADER_ROWS.all { ui.exists(it) } }
    assertShaderRowOrder(ui)
    ui.await("包内头像已解码") { ui.exists("本地包头像 Aurora.zip") }
    ui.await("登记头像解码且只请求一次") {
        fixture.network.requestCount(fixture.networkIconUrl) == 1 &&
            ui.exists("本地包头像 Cloud.zip")
    }
    // 没有来源的包会按 SHA-512 识别一次；夹具明确未收录，因此应回退占位图。
    ui.await("未登记包走摘要识别") {
        fixture.network.requested.any { it.contains("/v2/version_file/") }
    }
    check(!ui.exists("本地包头像 Plain.zip")) {
        "既无包内图标也无登记来源的光影包不应显示解码头像"
    }
    ui.capture("instance-shader-avatars")
    return fixture.network.requestCount(fixture.networkIconUrl)
}

/** 另一个实例：只剩自己的光影条目，看不到上一个实例的包或头像。 */
private fun checkOtherInstanceIsolated(
    ui: UiProbe,
    fixture: UiFixture,
) {
    openDetails(ui, fixture, "UI-Other")
    ui.tab("光影")
    ui.await("另一个实例的头像") {
        ui.exists("Other.zip") && ui.exists("本地包头像 Other.zip")
    }
    check(!ui.exists("Aurora.zip")) { "切换实例后不应看到前一个实例的光影列表" }
    check(!ui.exists("本地包头像 Aurora.zip")) { "切换实例后不应看到前一个实例的头像" }
    ui.capture("instance-shader-other-instance")
}

/** 切回主实例：头像恢复原样，不做磁盘缓存，因此重新进入会再取一次。 */
private fun checkRestoredAfterReturn(
    ui: UiProbe,
    fixture: UiFixture,
    firstRequestCount: Int,
) {
    openDetails(ui, fixture, "UI-Main")
    ui.tab("光影")
    ui.await("切回后仍是本实例头像") {
        ui.exists("本地包头像 Aurora.zip") &&
            ui.exists("本地包头像 Cloud.zip") &&
            !ui.exists("本地包头像 Other.zip")
    }
    check(!ui.exists("Other.zip")) { "切回后不应残留另一个实例的光影条目" }
    Thread.sleep(AVATAR_RELOAD_MILLIS)
    val restored = fixture.network.requestCount(fixture.networkIconUrl)
    check(restored == firstRequestCount + 1) {
        "每次进入光影页应只请求一次登记头像，实际 $firstRequestCount → $restored"
    }
    ui.capture("instance-shader-avatars-restored")
}

/** 两个实例同在一个游戏目录：从开始页进列表选中后，再用「版本设置」打开详情。 */
private fun openDetails(
    ui: UiProbe,
    fixture: UiFixture,
    name: String,
) {
    if (ui.exists("管理")) {
        ui.click("返回")
    }
    ui.await("开始页") { ui.exists("版本设置") }
    ui.click("实例管理")
    ui.await("实例列表") { ui.exists("搜索实例名称") }
    // 行内名称被 clickable 合并进整行语义，不能用文字定位；用搜索收窄到一行后按坐标点整行左侧。
    val searchArea = checkNotNull(ui.bounds("搜索实例名称")) { "找不到实例搜索框" }
    ui.type("搜索实例名称", name)
    ui.await("列表只剩 $name") {
        ui.exists("更多操作 $name") && ui.names().count { it.startsWith("更多操作 ") } == 1
    }
    val moreArea = checkNotNull(ui.bounds("更多操作 $name")) { "找不到 $name 的行尾操作" }
    ui.clickAtPoint(searchArea.x + ROW_CLICK_OFFSET, moreArea.y + moreArea.height / 2)
    ui.await("已选中 $name") { fixture.selectedInstance().name == name && ui.exists("版本设置") }
    ui.click("版本设置")
    ui.await("实例详情：$name") { ui.exists("管理") }
}

private fun assertShaderRowOrder(ui: UiProbe) {
    val areas =
        SHADER_ROWS.map { row ->
            checkNotNull(ui.bounds(row)) { "找不到光影行：$row" }
        }
    areas.zipWithNext().forEachIndexed { index, (above, below) ->
        check(above.y < below.y) {
            "光影行顺序应为 ${SHADER_ROWS[index]} → ${SHADER_ROWS[index + 1]}：${above.y} / ${below.y}"
        }
    }
}
