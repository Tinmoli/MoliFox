// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责临时启动目标、版本能力判断和每个游戏进程的进入记录参数。

package dev.molifox.core

import java.nio.file.Path

data class GameLaunchTarget(val kind: EntryKind, val key: String) {
    init {
        require(key.isNotBlank() && key.none { it == '\n' || it == '\r' || it == '\u0000' }) {
            "游戏目标无效"
        }
        when (kind) {
            EntryKind.Server -> require(parseServerAddress(key) != null) { "服务器地址无效" }
            EntryKind.World ->
                require(key !in listOf(".", "..") && '/' !in key && '\\' !in key) { "存档目录无效" }
        }
    }
}

internal fun supportsQuickPlay(profile: ResolvedProfile, version: String): Boolean {
    if (profile.json.obj("arguments").array("game").toString().contains("quickPlay")) return true
    val release = profile.json.string("releaseTime")
    if (release.isNotBlank()) return release >= "2023-04-05"
    val old = Regex("^1\\.(\\d+)(?:[. -].*)?$").matchEntire(version)
    if (old != null) return old.groupValues[1].toInt() >= 20
    return Regex("^2[6-9]\\.\\d+.*$").matches(version)
}

internal fun quickPlayArguments(
    target: GameLaunchTarget?,
    supported: Boolean,
    recording: Path?,
    version: String,
): List<String> {
    if (supported)
        return buildList {
            if (recording != null)
                addAll(listOf("--quickPlayPath", recording.toAbsolutePath().toString()))
            target?.let {
                addAll(
                    listOf(
                        if (it.kind == EntryKind.Server) "--quickPlayMultiplayer"
                        else "--quickPlaySingleplayer",
                        it.key,
                    )
                )
            }
        }
    if (target == null) return emptyList()
    require(
        target.kind == EntryKind.Server &&
            Regex("^1\\.(?:[0-9]|1[0-9])(?:[. -].*)?$").matches(version)
    ) {
        "此游戏版本不支持直接进入该目标，请从游戏菜单进入"
    }
    val address = requireNotNull(parseServerAddress(target.key))
    return listOf("--server", address.host, "--port", address.port.toString())
}

internal fun appendMissingQuickPlay(arguments: List<String>, extra: List<String>): List<String> =
    buildList {
        addAll(arguments)
        extra.chunked(2).forEach { pair -> if (pair.first() !in arguments) addAll(pair) }
    }
