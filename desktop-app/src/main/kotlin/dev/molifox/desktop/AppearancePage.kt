// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责外观主题、背景、透明度与动画偏好。

package dev.molifox.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.core.LauncherSettings
import dev.molifox.core.LauncherState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.Image as SkiaImage

private const val WINDOW_BACKGROUND_ALPHA = .82f

typealias AppearanceChange = ((LauncherSettings) -> LauncherSettings) -> Unit

// 主题预览块里的固定色：只表示「这套主题长什么样」，不跟随当前主题变化。
private val previewLightSurface = Color(0xFFFFFFFF)
private val previewLightLine = Color(0xFFE7DEE3)
private val previewDarkSurface = Color(0xFF262028)
private val previewDarkLine = Color(0xFF3A343A)

/** 窗口背景：无背景图时铺平面底色，有图时按浓度叠加。 */
@Composable
fun LauncherBackdrop(settings: LauncherSettings) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val base = MaterialTheme.colorScheme.background
    LaunchedEffect(settings.backgroundPath, settings.backgroundRevision) {
        bitmap =
            if (settings.backgroundPath.isBlank()) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val file = Path.of(settings.backgroundPath)
                        require(Files.size(file) <= 12L * 1024 * 1024)
                        SkiaImage.makeFromEncoded(Files.readAllBytes(file)).toComposeImageBitmap()
                    }.getOrNull()
                }
            }
    }
    Box(
        Modifier
            .fillMaxSize()
            .alpha(if (settings.windowTransparent) WINDOW_BACKGROUND_ALPHA else 1f)
            .blur(
                if (settings.glassEnabled) settings.backgroundBlur.dp else 0.dp,
                edgeTreatment = BlurredEdgeTreatment.Unbounded,
            ),
    ) {
        Box(Modifier.fillMaxSize().background(base))
        bitmap?.let {
            Image(
                it,
                null,
                Modifier.fillMaxSize().alpha(settings.backgroundOpacity.coerceIn(0f, 1f)),
                contentScale = backgroundContentScale(settings.backgroundFit),
                alignment = backgroundContentAlignment(settings.backgroundAlignment),
            )
        }
    }
}

/** 设置 → 外观板块：子页（主题与效果 / 字体与缩放 / 页面布局）由设置页左栏在「外观」下面展开选择， 这里直接渲染当前子页的设置卡片。 */
@Composable
fun ColumnScope.AppearanceSection(
    state: LauncherState,
    actions: UiActions,
    onResetDefaults: () -> Unit,
    subSection: Int,
) {
    val settings = state.data.settings
    // 外观与其它分区一致：改动当场写入 store，MoliTheme / LauncherBackdrop 随之重绘，
    // 主题和窗口效果会随保存的设置即时更新。
    val apply: AppearanceChange = { change -> applySetting(actions, change) }
    Column(Modifier.weight(1f).fillMaxWidth()) {
        Crossfade(
            subSection,
            Modifier.weight(1f).fillMaxWidth(),
            animationSpec = tween(if (LocalReducedMotion.current) 0 else 150),
            label = "appearanceSection",
        ) { index ->
            ScrollColumn(Modifier.fillMaxSize()) {
                when (index) {
                    0 -> ThemePreferences(settings, actions, apply)
                    1 -> FontPreferences(settings, actions, apply)
                    else -> PageLayoutPreferences(settings, apply)
                }
                Spacer(Modifier.height(6.dp))
                SettingsResetFooter(onResetDefaults)
            }
        }
    }
}

/** 外观子页标题：顺序与设置页左栏「外观」下面展开的子条目一致。 */
internal val appearanceSubSections = listOf("主题与效果", "字体与缩放", "页面布局")

@Composable
private fun ThemePreferences(
    settings: LauncherSettings,
    actions: UiActions,
    apply: AppearanceChange,
) {
    ThemeModeSection(settings, apply)
    AccentColorSection(settings, apply)
    BrandIconSection(settings, actions)
    CornerRadiusSection(settings, apply)
    BehaviorSection(settings, apply)
    GlassEffectSection(settings, apply)
    BackgroundImageSection(settings, actions, apply)
}

/** 已启用 / 未启用 Tag：分组卡片标题行右侧的状态标记。 */
@Composable
internal fun EnabledTag(enabled: Boolean) {
    val duration = if (LocalReducedMotion.current) 0 else Motion.FAST
    val color by
        animateColorAsState(
            if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            tween(duration),
            label = "enabledTag",
        )
    Tag(if (enabled) "已启用" else "未启用", color)
}

/** 主题卡片：64dp 预览块 + 12sp 标题 + 11sp 说明，选中时边框 2dp 主题色。 */
@Composable
internal fun RowScope.ThemeOptionCard(
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
        Modifier
            .weight(1f)
            .pressScale()
            .clip(RoundedCornerShape(Metrics.cardRadius))
            .hoverHighlight(RoundedCornerShape(Metrics.cardRadius))
            .clickable(role = Role.RadioButton, onClick = onClick)
            .background(fill)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(Metrics.cardRadius))
                .border(borderWidth.dp, border, RoundedCornerShape(Metrics.cardRadius)),
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
private fun ThemeSwatchPane(
    dark: Boolean,
    accent: Color,
    modifier: Modifier,
) {
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
                Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent.copy(alpha = .9f)),
            )
            Box(
                Modifier
                    .height(3.dp)
                    .fillMaxWidth(.55f)
                    .clip(RoundedCornerShape(2.dp))
                    .background(line),
            )
        }
        Box(
            Modifier
                .height(3.dp)
                .fillMaxWidth(.8f)
                .clip(RoundedCornerShape(2.dp))
                .background(line),
        )
        Box(
            Modifier
                .width(34.dp)
                .height(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
    }
}

/** 强调色 chip：色点 16dp + 12sp 文字，选中加 2dp 主题色边框与对勾。 */
@Composable
internal fun AccentChip(
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
        Modifier
            .pressScale()
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
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(resolveAccent(id, dark))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        Text(
            title,
            fontSize = 12.sp,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
        )
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                null,
                Modifier.size(13.dp),
                colors.onPrimaryContainer,
            )
        }
    }
}

/** 当前字体名称：与「字体与缩放」子页的判定保持一致。 */
internal fun fontLabel(settings: LauncherSettings): String =
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
