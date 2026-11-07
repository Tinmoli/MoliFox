// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// Linux 桌面集成：把启动器注册进应用列表（.desktop），启动路径、图标或语言文案变化时自动更新。

package dev.molifox.core.platform

import dev.molifox.core.i18n.I18n
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Compose Desktop（AWT）窗口的 WM_CLASS 按主类名生成；2026-11-06 在 WSL Xvfb 下对真实窗口实测取得。 */
private const val WINDOW_CLASS = "dev-molifox-bootstrap-BootstrapMainKt"

/** 应用列表里的条目固定名；更新只覆盖同一文件，不会在菜单里堆积旧条目。 */
private const val ENTRY_FILE_NAME = "molifox.desktop"

/** 数据目录里随包图标的目标位置；.desktop 的 Icon 需要真实文件，不能引用 JAR 内资源。 */
private const val ICON_DIRECTORY = "brand"
private const val ICON_FILE_NAME = "app-icon.png"

/** 运行时单类路径下与窗口、安装包同源的内置狐狸图标。 */
private const val APP_ICON_RESOURCE = "/icons/app/icon.png"

/** .desktop 的语言行使用下划线格式（zh_CN），与界面语言的连字符标签一一对应。 */
private val ENTRY_LOCALES =
    listOf(
        "en-US" to "en",
        "zh-CN" to "zh_CN",
        "zh-TW" to "zh_TW",
        "zh-HK" to "zh_HK",
    )

/** Exec 值按 shell 语义解析，双引号串内这些字符必须转义。 */
private val EXEC_ESCAPE_CHARACTERS = charArrayOf('\\', '"', '`', '$')

private const val KEY_GENERIC_NAME = "desktopEntry.genericName"
private const val KEY_COMMENT = "desktopEntry.comment"
private const val XDG_DATA_HOME = "XDG_DATA_HOME"
private const val MOLIFOX_BUNDLE_PATH = "MOLIFOX_BUNDLE_PATH"
private const val RUN_BUNDLE_SUFFIX = ".run"

private object IconResource {
    val stream: InputStream?
        get() = IconResource::class.java.getResourceAsStream(APP_ICON_RESOURCE)
}

/**
 * 确保 Linux 应用菜单里有 MoliFox；只处理 `.run` 便携包模式。
 *
 * 已注册且内容一致时不写盘，避免每次启动都动文件；安装位置挪动、图标更新或语言文案变化时整体重写。
 * 返回 .desktop 路径；不适用（非 Linux、没有 `.run` 包路径、拿不到内置图标）时返回 null，由调用方安静跳过。
 */
fun ensureLinuxDesktopEntry(
    dataDirectory: Path,
    iconSource: () -> InputStream? = { IconResource.stream },
    env: (String) -> String? = System::getenv,
    osName: String = System.getProperty("os.name").orEmpty(),
): Path? {
    if (!osName.startsWith("linux", ignoreCase = true)) return null
    // Exec 直接用环境变量里的原始路径：Path.toString 会按平台改写分隔符，Linux 路径不能经过它。
    val bundlePath = env(MOLIFOX_BUNDLE_PATH)?.takeIf { it.endsWith(RUN_BUNDLE_SUFFIX) } ?: return null
    val iconPath = ensureIconFile(dataDirectory, iconSource) ?: return null
    val target = applicationsRoot(env).resolve("applications").resolve(ENTRY_FILE_NAME)
    writeIfChanged(target, desktopEntryContent(bundlePath, iconPath))
    return target
}

/** 图标释放到数据目录：用临时文件整体替换，缺失或与内置版本不同时更新，并发首次启动也不会写坏。 */
private fun ensureIconFile(
    dataDirectory: Path,
    iconSource: () -> InputStream?,
): Path? {
    val bytes = iconSource()?.use { it.readBytes() } ?: return null
    val target = dataDirectory.resolve(ICON_DIRECTORY).resolve(ICON_FILE_NAME)
    if (isUsableFile(target) && Files.readAllBytes(target).contentEquals(bytes)) return target
    Files.createDirectories(target.parent)
    val staging = Files.createTempFile(target.parent, ICON_FILE_NAME, ".tmp")
    try {
        Files.write(staging, bytes)
        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        Files.deleteIfExists(staging)
    }
    return target
}

/** 应用列表根目录：优先 XDG_DATA_HOME（规范要求绝对路径，忽略相对值），回退用户主目录。 */
private fun applicationsRoot(env: (String) -> String?): Path {
    val override =
        env(XDG_DATA_HOME)
            ?.let { runCatching { Path.of(it) }.getOrNull() }
            ?.takeIf(Path::isAbsolute)
    return override ?: Path.of(System.getProperty("user.home"), ".local", "share")
}

/** `.desktop` 正文：条目按固定顺序输出，内容稳定是“只在变化时重写”的前提。 */
internal fun desktopEntryContent(
    bundlePath: String,
    iconPath: Path,
): String =
    buildString {
        appendLine("[Desktop Entry]")
        appendLine("Type=Application")
        // 品牌名不随界面语言变化，无语言匹配时的缺省行直接用英文词条。
        appendLine("Name=MoliFox")
        appendLine("GenericName=${entryText("en-US", KEY_GENERIC_NAME)}")
        appendLine("Comment=${entryText("en-US", KEY_COMMENT)}")
        for ((tag, locale) in ENTRY_LOCALES) {
            appendLine("GenericName[$locale]=${entryText(tag, KEY_GENERIC_NAME)}")
            appendLine("Comment[$locale]=${entryText(tag, KEY_COMMENT)}")
        }
        appendLine("Exec=${quotedExec(bundlePath)}")
        appendLine("Icon=$iconPath")
        appendLine("Terminal=false")
        appendLine("Categories=Game;Network;")
        appendLine("Keywords=minecraft;")
        appendLine("StartupWMClass=$WINDOW_CLASS")
        appendLine("StartupNotify=false")
    }

/** 读指定语言的词条；该语言缺词条时 I18n 内部按既有回退链补齐，不会输出空行。 */
private fun entryText(
    tag: String,
    key: String,
): String = I18n.catalog(tag).t(key)

/** Exec 里的路径按 shell 规则加双引号并转义特殊字符，含空格等常见目录名才能直接使用。 */
internal fun quotedExec(bundlePath: String): String {
    val escaped =
        buildString {
            for (character in bundlePath) {
                if (character in EXEC_ESCAPE_CHARACTERS) append('\\')
                append(character)
            }
        }
    return "\"$escaped\""
}

/** 内容一致时不写盘：跳过时要与写入一样返回成功，调用方不区分两种结果。 */
private fun writeIfChanged(
    target: Path,
    content: String,
) {
    if (isUsableFile(target) && Files.readString(target) == content) return
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
}

private fun isUsableFile(file: Path): Boolean =
    runCatching { Files.isRegularFile(file) && Files.size(file) > 0 }.getOrDefault(false)
