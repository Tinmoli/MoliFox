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
    val showRecent: Boolean = true,
    val recentCount: Int = 5,
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
