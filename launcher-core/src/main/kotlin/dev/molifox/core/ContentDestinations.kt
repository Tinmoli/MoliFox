// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

import java.nio.file.Path

fun contentFolderForType(type: String): String =
    when (type) {
        "resourcepack" -> "resourcepacks"
        "shader" -> "shaderpacks"
        "datapack" -> "datapacks"
        "world" -> "saves"
        else -> "mods"
    }

fun contentDestination(
    instanceRoot: Path,
    type: String,
    worldName: String = "",
): Path =
    if (type == "datapack") {
        instanceRoot.resolve("saves").resolve(worldName).resolve("datapacks")
    } else {
        instanceRoot.resolve(contentFolderForType(type))
    }

fun contentFolderOf(
    instanceRoot: Path?,
    destination: Path?,
): String? {
    if (instanceRoot == null || destination == null) return null
    val root = runCatching { instanceRoot.toAbsolutePath().normalize() }.getOrNull() ?: return null
    val target = runCatching { destination.toAbsolutePath().normalize() }.getOrNull() ?: return null
    val relative = runCatching { root.relativize(target) }.getOrNull() ?: return null
    if (relative.nameCount != 1) return null
    return relative.toString().takeIf { it in setOf("mods", "resourcepacks", "shaderpacks") }
}
