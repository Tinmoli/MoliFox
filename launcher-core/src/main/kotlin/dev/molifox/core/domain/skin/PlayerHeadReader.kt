// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 从皮肤原图生成当前界面需要的头像，只返回图像字节。

package dev.molifox.core.domain.skin

import dev.molifox.core.SkinModelRenderer
import dev.molifox.core.data.skin.SkinTextureRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

class PlayerHeadReader(
    private val textures: SkinTextureRepository = SkinTextureRepository(),
) {
    suspend fun front(
        path: Path,
        size: Int,
    ): Result<ByteArray?> =
        textures.read(path).map { texture ->
            withContext(Dispatchers.Default) { SkinModelRenderer.renderHeadFront(texture, size) }
        }
}
