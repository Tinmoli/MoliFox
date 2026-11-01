// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 读取并校验随启动器分发的运行时清单；损坏时给出可读错误，而不是空指针。

package dev.molifox.bootstrap

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n

/** 读取并校验自带清单；清单损坏时给出可读的错误，而不是空指针。 */
internal fun readManifest(): RuntimeManifest {
    val text =
        BootstrapMainMarker::class
            .java
            .getResourceAsStream("/molifox-runtime.json")
            ?.reader(Charsets.UTF_8)
            ?.use { it.readText() } ?: error(I18n.t("bootstrap.runtimeManifestReader.1"))
    val json = Gson().fromJson(text, JsonObject::class.java) ?: error(I18n.t("bootstrap.runtimeManifestReader.2"))
    val version = json.string("version")
    require(version.matches(Regex("[A-Za-z0-9._-]+"))) {
        I18n.t("bootstrapB.manifest.invalidVersion", version)
    }
    val items = json.getAsJsonArray("dependencies") ?: error(I18n.t("bootstrap.runtimeManifestReader.3"))
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
    require(dependencies.isNotEmpty()) { I18n.t("bootstrap.runtimeManifestReader.4") }
    dependencies.forEach { dependency ->
        require(dependency.filename.matches(Regex("[A-Za-z0-9_.+-]+\\.jar"))) {
            I18n.t("bootstrap.runtimeManifestReader.5", dependency.filename)
        }
        require(dependency.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
            I18n.t("bootstrap.runtimeManifestReader.6", dependency.filename)
        }
        require(dependency.size > 0) { I18n.t("bootstrap.runtimeManifestReader.7", dependency.filename) }
    }
    return RuntimeManifest(version, dependencies)
}

private fun JsonObject.string(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        ?: error(I18n.t("bootstrap.runtimeManifestReader.8", key))
