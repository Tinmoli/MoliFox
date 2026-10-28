// 负责外观主题、背景、透明度与动画偏好。

package dev.molifox.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import org.jetbrains.skia.Image as SkiaImage

typealias AppearanceChange = ((LauncherSettings) -> LauncherSettings) -> Unit

// 主题预览块里的固定色：只表示「这套主题长什么样」，不跟随当前主题变化。
private val previewLightSurface = Color(0xFFFFFFFF)
private val previewLightLine = Color(0xFFE7DEE3)
private val previewDarkSurface = Color(0xFF262028)
private val previewDarkLine = Color(0xFF3A343A)

/** 窗口背景：无背景图时铺平面底色（规格要求去掉渐变装饰），有图时按浓度叠加。 */
@Composable
fun LauncherBackdrop(settings: LauncherSettings) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val base = MaterialTheme.colorScheme.background
    LaunchedEffect(settings.backgroundPath, settings.backgroundRevision) {
        bitmap =
            if (settings.backgroundPath.isBlank()) null
            else
                withContext(Dispatchers.IO) {
                    runCatching {
                        val file = Path.of(settings.backgroundPath)
                        require(Files.size(file) <= 12L * 1024 * 1024)
                        SkiaImage.makeFromEncoded(Files.readAllBytes(file)).toComposeImageBitmap()
                    }
                        .getOrNull()
                }
    }
    Box(
        Modifier.fillMaxSize()
            .blur(
                if (settings.glassEnabled) settings.backgroundBlur.dp else 0.dp,
                edgeTreatment = BlurredEdgeTreatment.Unbounded,
            )
    ) {
        Box(Modifier.fillMaxSize().background(base))
        bitmap?.let {
            Image(
                it,
                null,
                Modifier.fillMaxSize().alpha(settings.backgroundOpacity.coerceIn(0f, 1f)),
                contentScale =
                    when (settings.backgroundFit) {
                        "fit" -> ContentScale.Fit
                        "stretch" -> ContentScale.FillBounds
                        else -> ContentScale.Crop
                    },
                alignment =
                    when (settings.backgroundAlignment) {
                        "topLeft" -> Alignment.TopStart
                        "topCenter" -> Alignment.TopCenter
                        "topRight" -> Alignment.TopEnd
                        "centerLeft" -> Alignment.CenterStart
                        "centerRight" -> Alignment.CenterEnd
                        "bottomLeft" -> Alignment.BottomStart
                        "bottomCenter" -> Alignment.BottomCenter
                        "bottomRight" -> Alignment.BottomEnd
                        else -> Alignment.Center
                    },
            )
        }
    }
}

/** 设置 → 外观板块：保留主题与效果 / 字体与缩放 / 页面布局三个子分类； 页面标题由设置页统一提供。右上角是只读的实时预览块。 */
@Composable
fun ColumnScope.AppearanceSection(
    state: LauncherState,
    actions: UiActions,
    onResetDefaults: () -> Unit,
) {
    var section by remember { mutableIntStateOf(0) }
    val settings = state.data.settings
    // 外观与其它分区一致：改动当场写入 store，MoliTheme / LauncherBackdrop 随之重绘，
    // 所以预览和窗口效果都是真的「实时」，不存在未保存状态。
    val apply: AppearanceChange = { change -> applySetting(actions, change) }
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(168.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SettingsGroupTitle("外观", topPadding = 2.dp)
            listOf(
                    "主题与效果" to Icons.Outlined.Palette,
                    "字体与缩放" to Icons.Outlined.TextFields,
                    "页面布局" to Icons.Outlined.DashboardCustomize,
                )
                .forEachIndexed { index, (title, icon) ->
                    NavigationEntry(title, icon, section == index, onClick = { section = index })
                }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 右上角实时预览：随主题 / 强调色 / 字体 / 缩放即时变化，只读、无业务回调。
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("外观预览", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "只读预览；随主题、强调色、字体与缩放即时变化。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                AppearanceLivePreview()
            }
            Crossfade(
                section,
                Modifier.weight(1f).fillMaxWidth(),
                animationSpec = tween(if (LocalReducedMotion.current) 0 else 150),
                label = "appearanceSection",
            ) { index ->
                ScrollColumn(Modifier.fillMaxSize()) {
                    when (index) {
                        0 ->
                            ThemePreferences(
                                state.data.settings,
                                actions,
                                apply,
                                onOpenFont = { section = 1 },
                            )
                        1 -> FontPreferences(state.data.settings, actions, apply)
                        else -> PageLayoutPreferences(state.data.settings, apply)
                    }
                    Spacer(Modifier.height(6.dp))
                    SettingsResetFooter(onResetDefaults)
                }
            }
        }
    }
}

/** 120×80 实时预览块：一张卡片 + 一行列表 + 一枚主按钮，全部读当前主题与字体。 */
@Composable
private fun AppearanceLivePreview() {
    val colors = MaterialTheme.colorScheme
    Surface(
        Modifier.size(width = 120.dp, height = 80.dp),
        shape = RoundedCornerShape(Metrics.cardRadius),
        color = colors.surface,
        border = BorderStroke(1.dp, hairline()),
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(14.dp)
                        .clip(RoundedCornerShape(Metrics.innerRadius))
                        .background(colors.primary.copy(alpha = .12f))
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(
                        Modifier.height(4.dp)
                            .fillMaxWidth(.62f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.onSurface.copy(alpha = .55f))
                    )
                    Box(
                        Modifier.height(3.dp)
                            .fillMaxWidth(.42f)
                            .clip(RoundedCornerShape(2.dp))
                            .background(colors.onSurfaceVariant.copy(alpha = .5f))
                    )
                }
            }
            HorizontalDivider(color = hairline())
            Box(
                Modifier.fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(Metrics.innerRadius))
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("启动游戏", color = colors.onPrimary, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ThemePreferences(
    settings: LauncherSettings,
    actions: UiActions,
    apply: AppearanceChange,
    onOpenFont: () -> Unit,
) {
    val theme = settings.themeMode.ifBlank { if (settings.darkTheme) "dark" else "light" }
    val duration = if (LocalReducedMotion.current) 0 else Motion.fast
    SettingsSection("界面主题", "选择界面明暗；切换后立即生效。") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ThemeOptionCard(
                "light",
                "浅色",
                "始终使用浅色界面",
                theme == "light",
                settings.accentColor,
                duration,
            ) {
                apply { it.copy(themeMode = "light", darkTheme = false) }
            }
            ThemeOptionCard(
                "dark",
                "深色",
                "始终使用深色界面",
                theme == "dark",
                settings.accentColor,
                duration,
            ) {
                apply { it.copy(themeMode = "dark", darkTheme = true) }
            }
            ThemeOptionCard(
                "system",
                "跟随系统",
                "跟随操作系统外观",
                theme == "system",
                settings.accentColor,
                duration,
            ) {
                apply { it.copy(themeMode = "system", darkTheme = false) }
            }
        }
    }
    SettingsSection("主题颜色", "默认樱花粉；可选择预设颜色，或输入十六进制值自定义。") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            accentPresetTitles.forEach { (id, title) ->
                AccentChip(
                    id = id,
                    title = title,
                    dark = settings.darkTheme,
                    selected = settings.accentColor == id,
                    duration = duration,
                ) {
                    apply { it.copy(accentColor = id) }
                }
            }
        }
        Text(
            (accentPresetTitles[settings.accentColor] ?: "自定义颜色") + " · 统一界面配色",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
        var customHex by
            remember(settings.accentColor) {
                mutableStateOf(
                    if (Regex("^#[0-9A-Fa-f]{6}$").matches(settings.accentColor))
                        settings.accentColor
                    else ""
                )
            }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(200.dp)) {
                CompactTextField(
                    customHex,
                    { value ->
                        customHex = value
                        if (Regex("^#[0-9A-Fa-f]{6}$").matches(value))
                            apply { it.copy(accentColor = value.uppercase()) }
                    },
                    placeholder = "#RRGGBB",
                )
            }
            if (customHex.isNotEmpty() && !Regex("^#[0-9A-Fa-f]{6}$").matches(customHex))
                Text(
                    "格式需为 #RRGGBB",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                )
            else
                Text(
                    "输入 #RRGGBB 作为自定义强调色",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
        }
    }
    SettingsSection("字体与缩放", "字体、文字大小与界面缩放集中在「字体与缩放」子页。", contentGap = 0.dp) {
        SettingRow("当前字体", fontLabel(settings)) {
            SecondaryButton({ onOpenFont() }) { Text("字体与缩放") }
        }
        SettingDivider()
        SettingRow("界面缩放", "当前 " + (settings.uiScale * 100).toInt() + "%") {
            Tag((settings.uiScale * 100).toInt().toString() + "%")
        }
        SettingDivider()
        PreferenceSlider(
            "界面圆角",
            settings.cornerRadiusDp.toFloat(),
            0f..24f,
            { "${it.toInt()} dp" },
        ) { value ->
            apply { it.copy(cornerRadiusDp = value.toInt()) }
        }
        MutedText("0 为直角；卡片、按钮、输入框与对话框按统一比例实时调整。")
    }
    SettingsSection("界面行为", "标题栏与动画效果；窗口透明需要重启启动器。", contentGap = 0.dp) {
        SettingRow("标题栏半透明", "让背景透过顶部标题栏，无需开启玻璃效果") {
            CompactSwitch(
                settings.titlebarTransparent,
                { value -> apply { it.copy(titlebarTransparent = value) } },
            )
        }
        SettingDivider()
        SettingRow("窗口透明", "整窗半透明，重启启动器后生效") {
            CompactSwitch(
                settings.windowTransparent,
                { value -> apply { it.copy(windowTransparent = value) } },
            )
        }
        SettingDivider()
        SettingRow("过渡动画", "关闭后页面切换与提示不再淡入淡出") {
            CompactSwitch(
                settings.animationsEnabled,
                { value -> apply { it.copy(animationsEnabled = value) } },
            )
        }
        SettingDivider()
        SettingRow("默认游戏窗口", "实例的窗口设置可以覆盖这里的默认值") {
            Box(Modifier.width(180.dp)) {
                SimpleDropdown(
                    settings.gameWindowMode,
                    listOf(
                        "launcher" to "与启动器一样大",
                        "fullscreen" to "全屏",
                        "custom" to "自定义分辨率",
                    ),
                    { value -> apply { it.copy(gameWindowMode = value) } },
                )
            }
        }
    }
    SettingsSection(
        "透明与磨砂",
        "让背景透过卡片，保留清晰的文字与操作层级。",
        action = { EnabledTag(settings.glassEnabled) },
        contentGap = 0.dp,
    ) {
        SettingRow("玻璃效果", "关闭后使用实色面板") {
            CompactSwitch(
                settings.glassEnabled,
                { value -> apply { it.copy(glassEnabled = value) } },
                Modifier.semantics { contentDescription = "玻璃效果开关" },
            )
        }
        SettingDivider()
        // 关闭玻璃时保留滑块并置灰，避免布局跳变与「设置丢了」的错觉。
        PreferenceSlider(
            "面板不透明度",
            settings.surfaceOpacity,
            .6f..1f,
            { (it * 100).toInt().toString() + "%" },
            enabled = settings.glassEnabled,
        ) { value ->
            apply { it.copy(surfaceOpacity = value) }
        }
        SettingDivider()
        PreferenceSlider(
            "背景磨砂强度",
            settings.backgroundBlur,
            0f..40f,
            { it.toInt().toString() },
            enabled = settings.glassEnabled,
        ) { value ->
            apply { it.copy(backgroundBlur = value) }
        }
        if (!settings.glassEnabled) MutedText("需开启玻璃效果后这些滑块才生效。", Modifier.padding(top = 4.dp))
    }
    var removing by remember { mutableStateOf(false) }
    val backgroundOn = settings.backgroundPath.isNotBlank()
    SettingsSection(
        "窗口背景",
        "图片副本保存在 MoliFox/appearance，可搭配透明与磨砂效果。",
        action = { EnabledTag(backgroundOn) },
        // 卡片内的四个行块之间用 1dp 细线分隔：行数 4 → 分隔线 3 条，首末不加。
        contentGap = 0.dp,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton({
                actions.run {
                    val source =
                        actions
                            .choose("选择窗口背景", extensions = listOf("png", "jpg", "jpeg"))
                            .firstOrNull()
                    if (source != null) {
                        val imported =
                            importAppearanceBackground(
                                actions.controller.store.dataDirectory,
                                source,
                            )
                        applySetting(actions) {
                            it.copy(
                                backgroundPath = imported.toString(),
                                backgroundRevision = System.currentTimeMillis(),
                            )
                        }
                    }
                }
            }) {
                Icon(Icons.Outlined.Image, null, Modifier.size(16.dp))
                Spacer(Modifier.width(7.dp))
                Text("选择背景图片")
            }
            if (backgroundOn) SecondaryButton({ removing = true }) { Text("移除背景") }
        }
        SettingDivider()
        PreferenceSlider(
            "背景浓度",
            settings.backgroundOpacity,
            0f..1f,
            { (it * 100).toInt().toString() + "%" },
            enabled = backgroundOn,
        ) { value ->
            apply { it.copy(backgroundOpacity = value) }
        }
        SettingDivider()
        SettingRow("图片适配", "填满可能裁边；完整显示保留全图；拉伸可能改变比例") {
            SimpleDropdown(
                settings.backgroundFit,
                listOf("crop" to "填满并裁切", "fit" to "完整显示", "stretch" to "拉伸铺满"),
                { value -> apply { it.copy(backgroundFit = value) } },
                Modifier.width(170.dp),
                enabled = true,
            )
        }
        SettingDivider()
        SettingRow("图片位置", "控制裁切中心或完整图片在窗口中的对齐位置") {
            SimpleDropdown(
                settings.backgroundAlignment,
                listOf(
                    "topLeft" to "左上",
                    "topCenter" to "居中靠上",
                    "topRight" to "右上",
                    "centerLeft" to "靠左居中",
                    "center" to "居中",
                    "centerRight" to "靠右居中",
                    "bottomLeft" to "左下",
                    "bottomCenter" to "居中靠下",
                    "bottomRight" to "右下",
                ),
                { value -> apply { it.copy(backgroundAlignment = value) } },
                Modifier.width(170.dp),
                enabled = settings.backgroundFit != "stretch",
            )
        }
        if (!backgroundOn) MutedText("可以先设置图片适配和位置，选择背景图片后生效。", Modifier.padding(top = 4.dp))
        if (settings.backgroundFit == "stretch") MutedText("拉伸铺满时没有空白或裁切区域；切换为填满或完整显示后可调整位置。")
    }
    // 移除背景会删除 MoliFox/appearance 里的图片副本，先确认再执行（C-11）。
    if (removing)
        Modal(
            "移除背景？",
            "图片副本会从 MoliFox/appearance 删除，无法从启动器恢复。",
            460.dp,
            { removing = false },
        ) {
            MutedText("原图若保存在其它位置不受影响；移除后窗口背景恢复为纯色。")
            DialogActions(
                confirmText = "移除背景",
                onConfirm = {
                    actions.run {
                        applySetting(actions) { it.copy(backgroundPath = "") }
                        Files.deleteIfExists(
                            actions.controller.store.directories.backgrounds.resolve(
                                "background.img"
                            )
                        )
                    }
                    removing = false
                },
                cancelText = "保留",
                onCancel = { removing = false },
                danger = true,
            )
        }
}

/** 已启用 / 未启用 Tag：分组卡片标题行右侧的状态标记。 */
@Composable
private fun EnabledTag(enabled: Boolean) {
    val duration = if (LocalReducedMotion.current) 0 else Motion.fast
    val color by
        animateColorAsState(
            if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            tween(duration),
            label = "enabledTag",
        )
    Tag(if (enabled) "已启用" else "未启用", color)
}

/** 主题卡片：64dp 预览块 + 12sp 标题 + 11sp 说明，选中时边框 2dp 主题色。 */
@Composable
private fun RowScope.ThemeOptionCard(
    id: String,
    title: String,
    detail: String,
    selected: Boolean,
    accentSetting: String,
    duration: Int,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val border by
        animateColorAsState(
            if (selected) colors.primary else hairline(),
            tween(duration),
            label = "themeBorder",
        )
    val borderWidth by
        animateFloatAsState(if (selected) 2f else 1f, tween(duration), label = "themeBorderWidth")
    val fill by
        animateColorAsState(
            if (selected) colors.primaryContainer else Color.Transparent,
            tween(duration),
            label = "themeFill",
        )
    Column(
        Modifier.weight(1f)
            .pressScale()
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .hoverHighlight(RoundedCornerShape(Metrics.cardRadius))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .background(fill)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(Metrics.cardRadius))
                .border(borderWidth.dp, border, RoundedCornerShape(Metrics.cardRadius))
        ) {
            when (id) {
                "light" ->
                    ThemeSwatchPane(
                        false,
                        resolveAccent(accentSetting, false),
                        Modifier.fillMaxSize(),
                    )
                "dark" ->
                    ThemeSwatchPane(
                        true,
                        resolveAccent(accentSetting, true),
                        Modifier.fillMaxSize(),
                    )
                else ->
                    Row(Modifier.fillMaxSize()) {
                        ThemeSwatchPane(
                            false,
                            resolveAccent(accentSetting, false),
                            Modifier.weight(1f),
                        )
                        ThemeSwatchPane(
                            true,
                            resolveAccent(accentSetting, true),
                            Modifier.weight(1f),
                        )
                    }
            }
        }
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
        )
        Text(detail, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

/** 主题预览里的一格：一小块卡片 + 两行文字 + 主按钮，颜色固定表示该明暗主题。 */
@Composable
private fun ThemeSwatchPane(dark: Boolean, accent: Color, modifier: Modifier) {
    val surface = if (dark) previewDarkSurface else previewLightSurface
    val line = if (dark) previewDarkLine else previewLightLine
    Column(
        modifier.background(surface).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                Modifier.size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = .9f))
            )
            Box(
                Modifier.height(3.dp)
                    .fillMaxWidth(.55f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(line)
            )
        }
        Box(Modifier.height(3.dp).fillMaxWidth(.8f).clip(RoundedCornerShape(2.dp)).background(line))
        Box(Modifier.width(34.dp).height(11.dp).clip(RoundedCornerShape(3.dp)).background(accent))
    }
}

/** 强调色 chip：色点 16dp + 12sp 文字，选中加 2dp 主题色边框与对勾。 */
@Composable
private fun AccentChip(
    id: String,
    title: String,
    dark: Boolean,
    selected: Boolean,
    duration: Int,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val border by
        animateColorAsState(
            if (selected) colors.primary else colors.outlineVariant.copy(alpha = .5f),
            tween(duration),
            label = "chipBorder",
        )
    val borderWidth by
        animateFloatAsState(if (selected) 2f else 1f, tween(duration), label = "chipBorderWidth")
    val fill by
        animateColorAsState(
            if (selected) selectedRowBackground() else Color.Transparent,
            tween(duration),
            label = "chipFill",
        )
    Row(
        Modifier.pressScale()
            .clip(RoundedCornerShape(Metrics.innerRadius))
            .hoverHighlight(RoundedCornerShape(Metrics.innerRadius))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .background(fill)
            .border(borderWidth.dp, border, RoundedCornerShape(Metrics.innerRadius))
            .padding(horizontal = 9.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.size(16.dp)
                .clip(CircleShape)
                .background(resolveAccent(id, dark))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
        )
        Text(
            title,
            fontSize = 12.sp,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
        )
        if (selected)
            Icon(
                Icons.Outlined.Check,
                null,
                Modifier.size(13.dp),
                colors.onPrimaryContainer,
            )
    }
}

/** 当前字体名称：与「字体与缩放」子页的判定保持一致。 */
private fun fontLabel(settings: LauncherSettings): String =
    when {
        settings.fontChoice.isBlank() -> "系统默认"
        settings.fontChoice.startsWith("file:") ->
            settings.customFonts.find { "file:" + it.path == settings.fontChoice }?.name ?: "自定义字体"
        else -> settings.fontChoice.removePrefix("system:")
    }

/** 紧凑滑块行：标题 + 右侧数值 Tag，下方轨道 4dp / 滑块 14dp。 拖动时只改本地值保证跟手，停手 300ms 后写入设置。 */
@Composable
fun PreferenceSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    enabled: Boolean = true,
    onApply: (Float) -> Unit,
) {
    val draft = rememberSliderDraft(value) { committed -> onApply(committed) }
    Column {
        SettingRow(title) { Tag(format(draft.floatValue)) }
        CompactSlider(
            draft.floatValue,
            { draft.floatValue = it },
            Modifier.fillMaxWidth().semantics { contentDescription = title + "滑块" },
            valueRange = range,
            enabled = enabled,
        )
    }
}
