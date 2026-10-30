// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 读取并校验随启动器分发的运行时清单；损坏时给出可读错误，而不是空指针。

package dev.molifox.bootstrap

import com.google.gson.Gson
import com.google.gson.JsonObject

/** 读取并校验自带清单；清单损坏时给出可读的错误，而不是空指针。 */
internal fun readManifest(): RuntimeManifest {
    val text =
        BootstrapMainMarker::class
            .java
            .getResourceAsStream("/molifox-runtime.json")
            ?.reader(Charsets.UTF_8)
            ?.use { it.readText() } ?: error("启动文件缺少运行时清单 /molifox-runtime.json")
    val json = Gson().fromJson(text, JsonObject::class.java) ?: error("运行时清单不是有效的 JSON")
    val version = json.string("version")
    require(version.matches(Regex("[A-Za-z0-9._-]+"))) { "运行时清单版本号不合法：$version" }
    val items = json.getAsJsonArray("dependencies") ?: error("运行时清单缺少 dependencies")
    val dependencies =
        items.map { element ->
            val item = element.asJsonObject
            RuntimeDependency(
                group = item.string("group"),
                artifact = item.string("artifact"),
                version = item.string("version"),
                filename = item.string("filename"),
                sha256 = item.string("sha256"),
                size = item.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            )
        }
    require(dependencies.isNotEmpty()) { "运行时清单里没有任何依赖" }
    dependencies.forEach { dependency ->
        require(dependency.filename.matches(Regex("[A-Za-z0-9_.+-]+\\.jar"))) {
            "运行时清单文件名不合法：${dependency.filename}"
        }
        require(dependency.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            "运行时清单校验值不合法：${dependency.filename}"
        }
        require(dependency.size > 0) { "运行时清单缺少大小：${dependency.filename}" }
    }
    return RuntimeManifest(version, dependencies)
}

private fun JsonObject.string(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        ?: error("运行时清单字段 $key 无效")
