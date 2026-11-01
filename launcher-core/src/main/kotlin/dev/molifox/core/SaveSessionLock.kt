// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责判断世界是否正被游戏打开：以 `session.lock` 能否独占锁为准。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/** `saves/` 下有没有世界正被游戏打开：`session.lock` 被占用即视为打开。 */
internal fun requireSavesUnlocked(saves: Path) {
    if (!saves.isDirectory(LinkOption.NOFOLLOW_LINKS) || isLinkPath(saves)) return
    val open =
        runCatching {
            saves
                .listDirectoryEntries()
                .filter { it.isDirectory(LinkOption.NOFOLLOW_LINKS) && !isLinkPath(it) }
                .firstOrNull { sessionLockHeld(it) }
        }.getOrNull()
    require(open == null) {
        I18n.t("saveA2.sessionLock.locked", open?.fileName)
    }
}

/**
 * 世界是否正被游戏打开：对 `session.lock` 尝试独占锁，拿不到就认为被占用。
 *
 * 原版游戏打开世界时会独占 `session.lock`；文件存在但不被锁是上次退出的残留，不算占用。 读不到/打不开锁文件时保守地按「占用」处理，宁可不写
 * `level.dat` 也不冒损坏世界的风险。
 */
internal fun sessionLockHeld(world: Path): Boolean {
    val lock = world.resolve("session.lock")
    if (!lock.exists(LinkOption.NOFOLLOW_LINKS)) return false
    if (!lock.isRegularFile(LinkOption.NOFOLLOW_LINKS)) return false
    return runCatching {
        FileChannel.open(lock, StandardOpenOption.WRITE).use { channel ->
            val held = channel.tryLock()
            if (held == null) {
                true
            } else {
                runCatching { held.release() }
                false
            }
        }
    }.getOrElse { true }
}
