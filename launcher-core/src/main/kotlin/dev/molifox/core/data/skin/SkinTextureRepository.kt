// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 在 IO 调度器按需读取皮肤原图，不保存额外的派生头像文件。

package dev.molifox.core.data.skin

import dev.molifox.core.platform.files.readBoundedImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Path

private const val MAX_SKIN_TEXTURE_BYTES = 4 * 1024 * 1024

class SkinTextureRepository(
    private val read: (Path, Int) -> Result<ByteArray> = ::readBoundedImage,
) {
    suspend fun read(path: Path): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            read(path, MAX_SKIN_TEXTURE_BYTES)
        }
}
