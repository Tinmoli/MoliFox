// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责临时启动目标、版本能力判断和每个游戏进程的进入记录参数。

package dev.molifox.core

import java.nio.file.Path

private const val MIN_QUICK_PLAY_LEGACY_MINOR = 20

internal fun supportsQuickPlay(
    profile: ResolvedProfile,
    version: String,
): Boolean {
    if (profile.json
            .obj("arguments")
            .array("game")
            .toString()
            .contains("quickPlay")
    ) {
        return true
    }
    val release = profile.json.string("releaseTime")
    if (release.isNotBlank()) return release >= "2023-04-05"
    val old = Regex("^1\\.(\\d+)(?:[. -].*)?$").matchEntire(version)
    if (old != null) return old.groupValues[1].toInt() >= MIN_QUICK_PLAY_LEGACY_MINOR
    return Regex("^2[6-9]\\.\\d+.*$").matches(version)
}

internal fun quickPlayArguments(
    target: GameLaunchTarget?,
    supported: Boolean,
    recording: Path?,
    version: String,
): List<String> {
    if (supported) {
        return buildList {
            if (recording != null) {
                addAll(listOf("--quickPlayPath", recording.toAbsolutePath().toString()))
            }
            target?.let {
                addAll(
                    listOf(
                        if (it.kind == EntryKind.Server) {
                            "--quickPlayMultiplayer"
                        } else {
                            "--quickPlaySingleplayer"
                        },
                        it.key,
                    ),
                )
            }
        }
    }
    if (target == null) return emptyList()
    require(
        target.kind == EntryKind.Server &&
            Regex("^1\\.(?:[0-9]|1[0-9])(?:[. -].*)?$").matches(version),
    ) {
        "diagA2.quickPlay.unsupportedTarget"
    }
    val address = requireNotNull(parseServerAddress(target.key))
    return listOf("--server", address.host, "--port", address.port.toString())
}

internal fun appendMissingQuickPlay(
    arguments: List<String>,
    extra: List<String>,
): List<String> =
    buildList {
        val overrides = extra.chunked(2).associate { it.first() to it.last() }
        val modes = setOf("--quickPlaySingleplayer", "--quickPlayMultiplayer", "--quickPlayRealms")
        val explicitTarget = overrides.keys.any { it in modes }
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            val option = argument.substringBefore('=')
            if (option in overrides || (explicitTarget && option in modes)) {
                if ('=' !in argument && arguments.getOrNull(index + 1)?.startsWith("--") == false) index++
            } else {
                add(argument)
            }
            index++
        }
        addAll(extra)
    }
