// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 只读检查公开更新与游戏元数据服务，记录直连和系统代理下的真实 TLS 结果。

package dev.molifox.core

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

fun main(arguments: Array<String>) = runBlocking {
    val records = mutableListOf<Map<String, Any>>()
    for (system in listOf(false, true)) {
        NetworkClient(systemProxyEnabled = { system }).use { network ->
            for (url in
                listOf(
                    "https://api.github.com/repos/Tinmoli/MoliFox/releases?per_page=1",
                    "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
                )) {
                val result = runCatching { withTimeout(20_000) { network.json(url, "official") } }
                val record =
                    mutableMapOf<String, Any>(
                        "systemProxy" to system,
                        "host" to java.net.URI(url).host,
                        "passed" to result.isSuccess,
                    )
                result.exceptionOrNull()?.let { failure ->
                    record["causes"] =
                        generateSequence(failure) { it.cause }
                            .take(8)
                            .map { "${it.javaClass.simpleName}: ${it.message?.take(220)}" }
                            .toList()
                }
                records.add(record)
                println(gson.toJson(record))
            }
        }
    }
    writeJson(Path.of(arguments.single()), records)
}
