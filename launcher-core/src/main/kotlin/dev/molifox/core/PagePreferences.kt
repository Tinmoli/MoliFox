// 负责各页面的显示选项和布局偏好。

package dev.molifox.core

data class LauncherFont(val name: String = "", val path: String = "")

data class PagePreferences(
    val home: HomePagePreferences = HomePagePreferences(),
    val versions: VersionsPagePreferences = VersionsPagePreferences(),
    val downloads: DownloadPagePreferences = DownloadPagePreferences(),
    val logs: LogsPagePreferences = LogsPagePreferences(),
    val showAdvancedLaunchOptions: Boolean = true,
)

data class HomePagePreferences(
    val showStatistics: Boolean = true,
    /** 开始页「最近游玩的存档或服务器」是否展开：默认折叠，展开 / 折叠都写入配置，重启后保持。 */
    val recentPlayExpanded: Boolean = false,
    /**
     * 最近游玩面板显示哪一类：「world」= 存档（默认），「server」= 服务器。
     *
     * 两类内容不再混排，标题行的分段开关切换后立即写入配置，重启后仍停在用户上次选的那一类。
     */
    val recentPlayKind: String = "world",
    val gradientMotd: Boolean = true,
)

data class VersionsPagePreferences(
    val view: String = "list",
    val sort: String = "recent",
    val favoritesFirst: Boolean = true,
    val showNotes: Boolean = true,
)

data class DownloadPagePreferences(
    val compactCards: Boolean = false,
    val showAuthors: Boolean = true,
    val showDescriptions: Boolean = true,
)

data class LogsPagePreferences(
    val defaultTab: String = "logs",
    val fontSize: Int = 12,
    val autoScroll: Boolean = true,
)
