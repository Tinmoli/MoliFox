// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 读取并校验随启动器分发的运行时清单；损坏时给出可读错误，而不是空指针。

package dev.molifox.bootstrap

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.molifox.core.i18n.I18n
import java.util.Locale

/**
 * 六个受支持目标各自使用一份专用清单；无法精确匹配时返回 null，
 * 由调用方明确报“不支持”，不能回退到另一架构。
 */
internal fun runtimeManifestResource(
    osName: String,
    architecture: String,
): String? {
    val name = osName.lowercase(Locale.ROOT)
    val arm64 =
        architecture.lowercase(Locale.ROOT) in
            setOf("aarch64", "arm64")
    val macos = name.contains("mac") || name.contains("darwin") || name.contains("osx")
    return when {
        name.contains("windows") && arm64 -> "/molifox-runtime-windows-arm64.json"
        name.contains("windows") -> "/molifox-runtime-windows-x64.json"
        name.contains("linux") && arm64 -> "/molifox-runtime-linux-arm64.json"
        name.contains("linux") -> "/molifox-runtime-linux-x64.json"
        macos && arm64 -> "/molifox-runtime-macos-arm64.json"
        macos -> "/molifox-runtime-macos-x64.json"
        else -> null
    }
}

/** 读取并校验自带清单；清单损坏时给出可读的错误，而不是空指针。 */
internal fun readManifest(): RuntimeManifest {
    val osName = System.getProperty("os.name")
    val architecture = System.getProperty("os.arch")
    val resource =
        runtimeManifestResource(osName, architecture)
            ?: error(I18n.t("bootstrapB.startup.unsupportedPlatform", osName, architecture))
    val json = readManifestJson(resource)
    val version = json.string("version")
    require(version.matches(Regex("[A-Za-z0-9._-]+"))) {
        I18n.t("bootstrapB.manifest.invalidVersion", version)
    }
    return RuntimeManifest(version, manifestDependencies(json))
}

private fun readManifestJson(resource: String): JsonObject {
    val text =
        BootstrapMainMarker::class
            .java
            .getResourceAsStream(resource)
            ?.reader(Charsets.UTF_8)
            ?.use { it.readText() } ?: error(I18n.t("bootstrap.runtimeManifestReader.1"))
    return Gson().fromJson(text, JsonObject::class.java) ?: error(I18n.t("bootstrap.runtimeManifestReader.2"))
}

private fun manifestDependencies(json: JsonObject): List<RuntimeDependency> {
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
    dependencies.forEach(::validateDependency)
    return dependencies
}

private fun validateDependency(dependency: RuntimeDependency) {
    require(dependency.filename.matches(Regex("[A-Za-z0-9_.+-]+\\.jar"))) {
        I18n.t("bootstrap.runtimeManifestReader.5", dependency.filename)
    }
    require(dependency.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
        I18n.t("bootstrap.runtimeManifestReader.6", dependency.filename)
    }
    require(dependency.size > 0) { I18n.t("bootstrap.runtimeManifestReader.7", dependency.filename) }
}

private fun JsonObject.string(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        ?: error(I18n.t("bootstrap.runtimeManifestReader.8", key))
