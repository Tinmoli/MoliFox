// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 写入当前测试夹具及单份结果摘要，不依赖核心模块的内部序列化实现。

package dev.molifox.desktop.checks

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path

internal fun writeFixtureJson(
    path: Path,
    value: Any,
) {
    Files.createDirectories(path.parent)
    Files.newBufferedWriter(path).use { output ->
        GsonBuilder().setPrettyPrinting().create().toJson(value, output)
    }
}
