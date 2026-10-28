package dev.molifox.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.*

@Composable
fun FontPreferences(settings: LauncherSettings, actions: UiActions, apply: AppearanceChange) {
    var families by remember { mutableStateOf<List<String>>(emptyList()) }
    var showPicker by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        families =
            withContext(Dispatchers.IO) {
                GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.sorted()
            }
    }
    val selectedName =
        when {
            settings.fontChoice.isBlank() -> "系统默认"
            settings.fontChoice.startsWith("file:") ->
                settings.customFonts.find { "file:" + it.path == settings.fontChoice }?.name
                    ?: "自定义字体"
            else -> settings.fontChoice.removePrefix("system:")
        }
    SettingsSection("界面字体", "使用本机字体，也可以导入 TTF / OTF；离线可用。", contentGap = 0.dp) {
        SettingRow("当前字体", selectedName) {
            SecondaryButton({ showPicker = true }) { Text("选择字体") }
        }
        SettingDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton({
                actions.run {
                    require(actions.controller.store.data.settings.customFonts.size < 12) {
                        "最多保留 12 种导入字体，请先移除不需要的字体"
                    }
                    val source =
                        actions.choose("导入字体", extensions = listOf("ttf", "otf")).firstOrNull()
                    if (source != null) {
                        val imported =
                            importAppearanceFont(actions.controller.store.dataDirectory, source)
                        apply {
                            it.copy(
                                fontChoice = "file:" + imported.path,
                                customFonts =
                                    (it.customFonts + imported).distinctBy { entry -> entry.path },
                            )
                        }
                    }
                }
            }) {
                Icon(Icons.Outlined.FileUpload, null, Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("导入字体")
            }
            TextButton({ apply { it.copy(fontChoice = "", fontScale = 1f) } }) { Text("恢复系统字体") }
        }
        SettingDivider()
        PreferenceSlider(
            "文字大小",
            settings.fontScale,
            .85f..1.25f,
            { (it * 100).toInt().toString() + "%" },
        ) { value ->
            apply { it.copy(fontScale = value) }
        }
        SettingDivider()
        Panel(Modifier.fillMaxWidth(), padding = 18) {
            Text("MoliFox Minecraft 启动器", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Text("Minecraft Java Edition  Aa Bb Cc  0123456789", fontSize = 15.sp)
            MutedText("这是一段界面字体预览。字体缺少的字符会使用系统回退字体。")
        }
    }
    if (settings.customFonts.isNotEmpty())
        // 每个字体一行，行间用 1dp 细线分隔：N 行 → N-1 条，首末不加。
        SettingsSection(
            "已导入字体",
            "原字体保留在原位置，这里管理启动器内的副本。",
            contentGap = 0.dp,
        ) {
            settings.customFonts.forEachIndexed { index, font ->
                if (index > 0) SettingDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(font.name, Modifier.weight(1f))
                    TextButton({ apply { it.copy(fontChoice = "file:" + font.path) } }) {
                        Text("使用")
                    }
                    SmallIconButton(
                        Icons.Outlined.DeleteOutline,
                        "移除字体 " + font.name,
                        {
                            actions.run {
                                apply {
                                    it.copy(
                                        customFonts =
                                            it.customFonts.filter { candidate ->
                                                candidate.path != font.path
                                            },
                                        fontChoice =
                                            it.fontChoice.takeUnless { choice ->
                                                choice == "file:" + font.path
                                            } ?: "",
                                    )
                                }
                                removeAppearanceFont(actions.controller.store.dataDirectory, font)
                            }
                        },
                    )
                }
            }
        }
    SettingsSection("显示大小", "界面缩放同时调整控件与文字，文字大小只影响字体。", contentGap = 0.dp) {
        PreferenceSlider(
            "界面缩放",
            settings.uiScale,
            .85f..1.2f,
            { (it * 100).toInt().toString() + "%" },
        ) { value ->
            apply { it.copy(uiScale = value) }
        }
        SettingDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(.9f, 1f, 1.1f, 1.2f).forEach { value ->
                SecondaryButton({ apply { it.copy(uiScale = value) } }) {
                    Text((value * 100).toInt().toString() + "%")
                }
            }
        }
    }
    if (showPicker) {
        Modal(
            "选择界面字体",
            families.size.toString() + " 种系统字体 · 支持搜索与预览",
            710.dp,
            { showPicker = false },
        ) {
            OutlinedTextField(
                query,
                { query = it },
                Modifier.fillMaxWidth(),
                label = { Text("搜索字体") },
                singleLine = true,
            )
            val choices =
                listOf("" to "系统默认") +
                    settings.customFonts.map { ("file:" + it.path) to (it.name + " · 已导入") } +
                    families.map { ("system:" + it) to it }
            LazyColumn(
                Modifier.fillMaxWidth().height(340.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                items(choices.filter { it.second.contains(query, true) }, key = { it.first }) {
                    (id, title) ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable {
                                apply { it.copy(fontChoice = id) }
                                showPicker = false
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(settings.fontChoice == id, null)
                        Spacer(Modifier.width(8.dp))
                        Text(title, Modifier.weight(1f))
                        Text(
                            "Aa 示例",
                            fontFamily = remember(id) { launcherFontFamily(id) },
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton({ showPicker = false }, Modifier.align(Alignment.End)) { Text("关闭") }
        }
    }
}
