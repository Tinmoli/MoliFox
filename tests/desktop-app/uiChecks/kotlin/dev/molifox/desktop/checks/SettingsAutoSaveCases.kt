// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验收实例设置的自动保存、非法输入回退和改名目录只执行一次。

package dev.molifox.desktop.checks

import com.google.gson.JsonObject
import dev.molifox.core.MAX_GAME_WINDOW_SIZE
import dev.molifox.core.MIN_GAME_WINDOW_SIZE
import dev.molifox.core.VersionIsolation
import dev.molifox.core.i18n.I18n
import java.nio.file.Files
import java.nio.file.Path

private val MEMORY_LABEL = Regex("""^(\d+) MB（[\d.]+ GB）$""")
private val WINDOW_WIDTH_ERROR = "游戏窗口宽度需为 0 或 $MIN_GAME_WINDOW_SIZE–$MAX_GAME_WINDOW_SIZE"
private val nameField: String
    get() = I18n.t("instA1.settings.nameLabel")
private const val WIDTH_FIELD = "窗口宽度（px）"
private const val HEIGHT_FIELD = "窗口高度（px）"
private const val MEMORY_SLIDER = "实例最大内存滑块"
private const val MANUAL_MEMORY = "手动分配"
private const val MANUAL_MEMORY_MB = 2048
private const val MEMORY_STEP_MB = 256
private const val VALID_WIDTH_PX = 1280
private const val INVALID_WIDTH = "99999"
private const val SLIDER_POSITION = .6f
private const val AUTOSAVE_MILLIS = 600L
private const val SCROLL_ATTEMPTS = 4
private const val SCROLL_NOTCHES = 6
private const val SCROLL_TO_TOP_NOTCHES = -30
private const val MIN_VISIBLE_HEIGHT = 10
private val TOP_NAME_RANGE = 200..420
private const val RENAMED_FOLDER = "UI-Renamed"

internal fun checkInstanceSettingsAutoSave(
    ui: UiProbe,
    fixture: UiFixture,
) {
    ui.click("版本设置")
    ui.await("实例设置页") { ui.exists("启动设置") && ui.exists(MEMORY_SLIDER) }
    val instance = fixture.selectedInstance()
    val folder = instance.name
    check(instance.autoMemory == null && instance.memoryMb == 0) { "夹具应从未分配内存开始" }
    check((fixture.persistedInstanceSettings(folder)?.get("memoryMb")?.asInt ?: 0) == 0)

    checkInheritedMemory(ui, fixture, folder)
    checkManualMemory(ui, fixture, folder)
    checkWidthAndIsolation(ui, fixture, folder)
    checkInvalidWidthRevert(ui, fixture, folder)
    checkRename(ui, fixture, folder)
}

/** 继承全局时滑块不可调：点击后不应把数值写进实例。 */
private fun checkInheritedMemory(
    ui: UiProbe,
    fixture: UiFixture,
    folder: String,
) {
    ui.clickAt(MEMORY_SLIDER, SLIDER_POSITION)
    Thread.sleep(AUTOSAVE_MILLIS)
    check(fixture.selectedInstance().memoryMb == 0) { "继承全局内存策略时滑块不应写入实例内存" }
    check((fixture.persistedInstanceSettings(folder)?.get("memoryMb")?.asInt ?: 0) == 0)
}

/** 手动分配后滑块按 256 MB 步进，数值同时写进内存状态和实例设置。 */
private fun checkManualMemory(
    ui: UiProbe,
    fixture: UiFixture,
    folder: String,
) {
    ui.click("继承全局内存策略")
    ui.await("内存策略菜单") { ui.exists(MANUAL_MEMORY) }
    ui.click(MANUAL_MEMORY)
    ui.await("手动分配已保存") {
        fixture.selectedInstance().let { it.autoMemory == false && it.memoryMb == MANUAL_MEMORY_MB }
    }
    ui.clickAt(MEMORY_SLIDER, SLIDER_POSITION)
    ui.await("滑块写入新内存") {
        fixture.selectedInstance().let { it.memoryMb > 0 && it.memoryMb != MANUAL_MEMORY_MB }
    }
    val memoryLabel =
        ui.awaitValue("滑块旁的数值") {
            ui.namesMatching(MEMORY_LABEL).firstOrNull()
        }
    val memoryMb = memoryLabel.substringBefore(" MB").toInt()
    check(memoryMb % MEMORY_STEP_MB == 0) { "滑块应按 $MEMORY_STEP_MB MB 步进：$memoryLabel" }
    check(fixture.selectedInstance().memoryMb == memoryMb) {
        "界面显示 $memoryMb MB，内存状态却是 ${fixture.selectedInstance().memoryMb}"
    }
    val saved = awaitSettings(ui, fixture, folder, "最大内存落盘") { it["memoryMb"]?.asInt == memoryMb }
    check(saved["autoMemory"]?.asBoolean == false)
}

/** 窗口宽度直接落盘；版本隔离下拉在折叠区下方，滚进视口后改成共用目录。 */
private fun checkWidthAndIsolation(
    ui: UiProbe,
    fixture: UiFixture,
    folder: String,
) {
    // 夹具目录自带 mods/config，扫描会记为显式版本隔离（见 GameDirectoryService），下拉初始值即「版本隔离」。
    check(fixture.selectedInstance().isolation == VersionIsolation.Enabled) { "夹具实例应为版本隔离" }
    ui.type(WIDTH_FIELD, VALID_WIDTH_PX.toString())
    awaitSettings(ui, fixture, folder, "窗口宽度落盘") { it["width"]?.asInt == VALID_WIDTH_PX }
    check(fixture.selectedInstance().width == VALID_WIDTH_PX)

    scrollUntilVisible(ui, "版本隔离")
    ui.click("版本隔离")
    ui.await("隔离下拉菜单") { ui.exists("共用游戏目录") }
    ui.click("共用游戏目录")
    awaitSettings(ui, fixture, folder, "版本隔离落盘") { it["isolation"]?.asString == "Disabled" }
    check(fixture.selectedInstance().isolation == VersionIsolation.Disabled)
    scrollToTop(ui)
}

/** 数字框只收数字，这里用超范围的数字触发同一套校验；非数字会在输入时被过滤掉。 */
private fun checkInvalidWidthRevert(
    ui: UiProbe,
    fixture: UiFixture,
    folder: String,
) {
    ui.type(WIDTH_FIELD, INVALID_WIDTH)
    ui.await("输入框保留超范围数字") { ui.exists(INVALID_WIDTH) }
    // 错误提示在启动设置卡片底部，可能落在视口外；先确认已渲染，再滚过去截图取证。
    ui.await("非法宽度内联错误") { ui.names().any { it.contains(WINDOW_WIDTH_ERROR) } }
    Thread.sleep(AUTOSAVE_MILLIS)
    check(fixture.selectedInstance().width == VALID_WIDTH_PX) { "非法宽度不应进入内存状态" }
    check(!configurationText(fixture).contains(INVALID_WIDTH)) { "非法宽度不应写入配置文件" }
    scrollUntilVisible(ui, WINDOW_WIDTH_ERROR)
    ui.capture("instance-settings-invalid-width")
    scrollToTop(ui)
    ui.click(HEIGHT_FIELD)
    ui.await("宽度回退到上次有效值") { ui.exists(VALID_WIDTH_PX.toString()) }
    check(fixture.selectedInstance().width == VALID_WIDTH_PX) { "失焦后应回退到上次有效宽度" }
    awaitSettings(ui, fixture, folder, "回退后仍保存有效宽度") { it["width"]?.asInt == VALID_WIDTH_PX }
    check(!configurationText(fixture).contains(INVALID_WIDTH)) { "回退后配置里仍残留非法宽度" }
    ui.capture("instance-settings-autosave")
}

/**
 * 改名只应在离开名称输入框时执行一次，并且目录、配置键、版本 JSON 一起更新。
 * 改名会更换实例 id，详情页要跟着指向新 id 并留在原地，不能退回实例列表。
 */
private fun checkRename(
    ui: UiProbe,
    fixture: UiFixture,
    folder: String,
) {
    val oldDirectory = fixture.instanceDirectory(fixture.selectedInstance())
    ui.type(nameField, RENAMED_FOLDER)
    ui.await("名称输入框已更新") { ui.exists(RENAMED_FOLDER) }
    ui.click(WIDTH_FIELD)
    ui.await("目录已改名") {
        Files.isDirectory(oldDirectory.resolveSibling(RENAMED_FOLDER)) &&
            !Files.exists(oldDirectory)
    }
    ui.await("实例记录已改名") {
        fixture.controller.store.data.instances
            .any { it.name == RENAMED_FOLDER }
    }
    val renamed = fixture.instanceNamed(RENAMED_FOLDER)
    check(renamed.id == fixture.selectedInstance().id) { "改名后应保持选中同一个实例" }
    val renamedDirectories =
        Files.list(oldDirectory.parent).use { paths ->
            paths.filter { it.fileName.toString().startsWith(RENAMED_FOLDER) }.toList()
        }
    check(renamedDirectories.size == 1) { "改名目录应只有一个，实际 $renamedDirectories" }
    val log = fixture.controller.store.directories.launcherLog
    val renameCount = Files.readString(log).lines().count { it.contains("已重命名实例：") }
    check(renameCount == 1) { "重命名应只发生一次，日志出现 $renameCount 次" }
    check(fixture.persistedInstanceSettings(folder) == null) { "旧目录名的设置键应随改名搬走" }
    awaitSettings(ui, fixture, RENAMED_FOLDER, "改名后的设置仍在") { it["width"]?.asInt == VALID_WIDTH_PX }
    val versionJson =
        Files.readString(Path.of(renamedDirectories.single().toString(), "$RENAMED_FOLDER.json"))
    check(versionJson.contains("\"id\"")) { "改名后的版本配置缺少 id" }
    ui.await("改名后仍停留在详情页") {
        ui.exists("游戏文件夹") && ui.exists(nameField)
    }
    ui.await("改名后的实例仍可见") { ui.exists(RENAMED_FOLDER) || ui.exists("更多操作 $RENAMED_FOLDER") }
    check(fixture.selectedInstance().name == RENAMED_FOLDER)
}

private fun awaitSettings(
    ui: UiProbe,
    fixture: UiFixture,
    folder: String,
    description: String,
    predicate: (JsonObject) -> Boolean,
): JsonObject =
    ui.awaitValue(description) {
        fixture.persistedInstanceSettings(folder)?.takeIf(predicate)
    }

private fun configurationText(fixture: UiFixture): String =
    runCatching {
        Files.readString(fixture.controller.store.directories.configuration)
    }.getOrDefault("")

/** 设置页只有一个滚动容器：滚到节点进入视口为止，避免依赖固定滚动量。 */
private fun scrollUntilVisible(
    ui: UiProbe,
    label: String,
) {
    repeat(SCROLL_ATTEMPTS) {
        if (isVisible(ui, label)) return
        ui.scroll(SCROLL_NOTCHES)
    }
    check(isVisible(ui, label)) { "滚动后仍看不到：$label" }
}

/** 只在交界处可见一两个像素的节点不算可读，避免截到被裁掉的提示。 */
private fun isVisible(
    ui: UiProbe,
    label: String,
): Boolean = (ui.bounds(label)?.height ?: 0) >= MIN_VISIBLE_HEIGHT

/** 滚轮在顶部会夹住：一次大幅上滚即可恢复初始位置，再用名称框的纵坐标确认。 */
private fun scrollToTop(ui: UiProbe) {
    ui.scroll(SCROLL_TO_TOP_NOTCHES)
    ui.await("回到设置页顶部") { ui.bounds(nameField)?.y?.let { it in TOP_NAME_RANGE } == true }
}
