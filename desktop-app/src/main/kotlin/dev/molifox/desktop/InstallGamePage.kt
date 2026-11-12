// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 原版安装页：版本与加载器版本的数据加载，以及安装选项与底部的组装。

package dev.molifox.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.gson.JsonParseException
import dev.molifox.core.LauncherState
import dev.molifox.core.LoaderType
import dev.molifox.core.LoaderVersion
import dev.molifox.core.ReleaseVersion
import dev.molifox.core.effectiveVersionIsolationPolicy
import dev.molifox.core.i18n.Strings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun ColumnScope.InstallGamePage(
    state: LauncherState,
    actions: UiActions,
    onInstalled: () -> Unit,
) {
    var versions by remember { mutableStateOf<List<ReleaseVersion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("release") }
    var selected by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf(LoaderType.Vanilla) }
    var loaders by remember { mutableStateOf<List<LoaderVersion>>(emptyList()) }
    var loaderVersion by remember { mutableStateOf("") }
    var loaderLoading by remember { mutableStateOf(false) }
    var loaderError by remember { mutableStateOf<String?>(null) }
    var supportedGameVersions by remember { mutableStateOf<Set<String>?>(null) }
    var name by remember { mutableStateOf("") }
    VersionListEffect(
        actions = actions,
        refresh = refresh,
        currentSelected = { selected },
        onLoadingChange = { loading = it },
        onErrorChange = { error = it },
        onVersions = { versions = it },
        onSelect = { selected = it },
    )
    LoaderVersionEffect(
        actions = actions,
        selected = selected,
        loader = loader,
        currentSelected = { selected },
        currentLoader = { loader },
        onLoaders = { loaders = it },
        onLoaderVersion = { loaderVersion = it },
        onError = { loaderError = it },
        onLoading = { loaderLoading = it },
    )
    SupportedVersionsEffect(
        actions = actions,
        loader = loader,
        currentLoader = { loader },
        supported = supportedGameVersions,
        versions = versions,
        selected = selected,
        onSupported = { supportedGameVersions = it },
        onSelect = { selected = it },
        onResetQuery = {
            query = ""
            channel = "release"
        },
    )
    val visibleVersions =
        supportedGameVersions?.let { supported -> versions.filter { it.id in supported } }
            ?: versions
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        VersionListPanel(
            versions = visibleVersions,
            loading = loading,
            error = error,
            query = query,
            onQueryChange = { query = it },
            channel = channel,
            onChannelChange = { channel = it },
            selected = selected,
            onSelect = { selected = it },
            busy = rememberLauncherBusy(actions),
            onRefresh = { refresh++ },
        )
        InstallOptionsPanel(
            actions = actions,
            isolated =
                state.data.settings.effectiveVersionIsolationPolicy.isolates(
                    loader,
                    versions
                        .firstOrNull {
                            it.id ==
                                selected
                        }?.type
                        .orEmpty(),
                ),
            selected = selected,
            name = name,
            onNameChange = { name = it.take(80) },
            loader = loader,
            onLoaderChange = { loader = it },
            loaders = loaders,
            loaderVersion = loaderVersion,
            onLoaderVersionChange = { loaderVersion = it },
            loaderLoading = loaderLoading,
            loaderError = loaderError,
        )
    }
    InstallFooter(
        state = state,
        actions = actions,
        loading = loading,
        error = error,
        selected = selected,
        loaderLoading = loaderLoading,
        loader = loader,
        loaderVersion = loaderVersion,
        name = name,
        onInstalled = onInstalled,
    )
}

/** 拉取游戏版本列表；还没有选中版本时自动选中最新的正式版。 */
@Composable
private fun VersionListEffect(
    actions: UiActions,
    refresh: Int,
    currentSelected: () -> String,
    onLoadingChange: (Boolean) -> Unit,
    onErrorChange: (String?) -> Unit,
    onVersions: (List<ReleaseVersion>) -> Unit,
    onSelect: (String) -> Unit,
) {
    // lambda 参数与 strings 每次重组都是新实例（strings 随语言切换变化）；rememberUpdatedState
    // 让 effect 只随 refresh 重启，执行时读到最新回调与当前语言文案，避免一直显示旧翻译。
    val latestSelected by rememberUpdatedState(currentSelected)
    val latestOnLoadingChange by rememberUpdatedState(onLoadingChange)
    val latestOnErrorChange by rememberUpdatedState(onErrorChange)
    val latestOnVersions by rememberUpdatedState(onVersions)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestStrings by rememberUpdatedState(LocalStrings.current)
    LaunchedEffect(refresh) {
        latestOnLoadingChange(true)
        latestOnErrorChange(null)
        try {
            val loaded =
                withContext(Dispatchers.IO) { actions.controller.installer.versions(refresh > 0) }
            latestOnVersions(loaded)
            if (latestSelected().isBlank()) {
                latestOnSelect(loaded.firstOrNull { it.type == "release" }?.id.orEmpty())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            latestOnErrorChange(failure.message ?: latestStrings.t("deskE.install.versionLoadFailed"))
        } catch (failure: JsonParseException) {
            latestOnErrorChange(failure.message ?: latestStrings.t("deskE.install.versionLoadFailed"))
        } catch (failure: IllegalStateException) {
            latestOnErrorChange(failure.message ?: latestStrings.t("deskE.install.versionLoadFailed"))
        } finally {
            latestOnLoadingChange(false)
        }
    }
}

/** 受限加载器只展示其支持的游戏版本；当前选中版本不支持时自动切到最新的支持版本。 */
@Composable
private fun SupportedVersionsEffect(
    actions: UiActions,
    loader: LoaderType,
    currentLoader: () -> LoaderType,
    supported: Set<String>?,
    versions: List<ReleaseVersion>,
    selected: String,
    onSupported: (Set<String>?) -> Unit,
    onSelect: (String) -> Unit,
    onResetQuery: () -> Unit,
) {
    // lambda 参数每次重组都是新实例；用 rememberUpdatedState 持有最新值，
    // 让两个 effect 只随各自的 key（loader / supported+versions+selected）重启，执行时读到最新回调。
    val latestLoader by rememberUpdatedState(currentLoader)
    val latestOnSupported by rememberUpdatedState(onSupported)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnResetQuery by rememberUpdatedState(onResetQuery)
    LaunchedEffect(loader) {
        latestOnSupported(null)
        if (loader == LoaderType.Vanilla) return@LaunchedEffect
        val loaded = loadSupportedGameVersions(actions, loader)
        if (latestLoader() == loader) latestOnSupported(loaded)
    }
    LaunchedEffect(supported, versions, selected) {
        val allowed = supported ?: return@LaunchedEffect
        if (allowed.isEmpty() || selected in allowed) return@LaunchedEffect
        val fallback =
            versions
                .filter { it.id in allowed }
                .maxByOrNull { it.releaseTime }
        latestOnSelect(fallback?.id.orEmpty())
        latestOnResetQuery()
    }
}

/** 拉取受限加载器支持的游戏版本；加载失败时返回 null，由调用方决定回退行为，取消继续向上传播。 */
private suspend fun loadSupportedGameVersions(
    actions: UiActions,
    loader: LoaderType,
): Set<String>? =
    try {
        withContext(Dispatchers.IO) {
            actions.controller.installer.supportedGameVersions(loader)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

/** 跟随选中的游戏版本与加载器重新拉取加载器版本；切换过快时只保留最后一次结果。 */
@Composable
private fun LoaderVersionEffect(
    actions: UiActions,
    selected: String,
    loader: LoaderType,
    currentSelected: () -> String,
    currentLoader: () -> LoaderType,
    onLoaders: (List<LoaderVersion>) -> Unit,
    onLoaderVersion: (String) -> Unit,
    onError: (String?) -> Unit,
    onLoading: (Boolean) -> Unit,
) {
    // lambda 参数与 strings 每次重组都是新实例；rememberUpdatedState 让 effect 只随 (selected, loader) 重启并读到最新回调与文案。
    val latestSelected by rememberUpdatedState(currentSelected)
    val latestLoader by rememberUpdatedState(currentLoader)
    val latestOnLoaders by rememberUpdatedState(onLoaders)
    val latestOnLoaderVersion by rememberUpdatedState(onLoaderVersion)
    val latestOnError by rememberUpdatedState(onError)
    val latestOnLoading by rememberUpdatedState(onLoading)
    val latestStrings by rememberUpdatedState(LocalStrings.current)
    LaunchedEffect(selected, loader) {
        latestOnLoaders(emptyList())
        latestOnLoaderVersion("")
        latestOnError(null)
        latestOnLoading(false)
        if (loader == LoaderType.Vanilla || selected.isBlank()) return@LaunchedEffect
        latestOnLoading(true)
        try {
            val loaded = loadLoaderVersions(actions, loader, selected, latestStrings, latestOnError)
            if (loaded != null) {
                latestOnLoaders(loaded)
                latestOnLoaderVersion(
                    (loaded.firstOrNull { it.stable } ?: loaded.firstOrNull())?.version.orEmpty(),
                )
            }
        } finally {
            if (latestSelected() == selected && latestLoader() == loader) latestOnLoading(false)
        }
    }
}

/** 拉取加载器版本列表；失败时用 onError 上报本地化文案并返回 null，取消继续向上传播。 */
private suspend fun loadLoaderVersions(
    actions: UiActions,
    loader: LoaderType,
    gameVersion: String,
    strings: Strings,
    onError: (String?) -> Unit,
): List<LoaderVersion>? =
    try {
        withContext(Dispatchers.IO) {
            actions.controller.installer.loaderVersions(loader, gameVersion)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        onError(failure.message ?: strings.t("deskE.install.loaderLoadFailed"))
        null
    } catch (failure: JsonParseException) {
        onError(failure.message ?: strings.t("deskE.install.loaderLoadFailed"))
        null
    } catch (failure: IllegalStateException) {
        onError(failure.message ?: strings.t("deskE.install.loaderLoadFailed"))
        null
    }
