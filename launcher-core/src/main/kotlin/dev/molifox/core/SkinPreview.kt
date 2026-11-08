// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files

/** 给界面预览使用的受限字节读取；皮肤路径与磁盘访问留在 core。 */
suspend fun SkinService.textureBytes(
    account: Account,
    selectedId: String?,
): ByteArray? =
    withContext(Dispatchers.IO) {
        val file =
            if (selectedId == null) {
                texture(account)
            } else {
                library.texture(selectedId)
            } ?: return@withContext null
        Files.newInputStream(file).use { input ->
            input.readNBytes(SKIN_SERVICE_MAX_BYTES + 1).takeIf { it.size <= SKIN_SERVICE_MAX_BYTES }
        }
    }
