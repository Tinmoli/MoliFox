// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责导出包各格式清单的写后断言。

package dev.molifox.core

import java.util.zip.ZipFile

private const val MAX_CURSEFORGE_IDENTIFIER = 0xffffffffL

internal fun validateExportManifest(
    zip: ZipFile,
    format: PackExportFormat,
    layout: PackExportLayout,
    plan: MrpackExportPlan,
) {
    when (format) {
        PackExportFormat.Modrinth -> validateModrinthManifest(zip, layout, plan)
        PackExportFormat.CurseForge -> validateCurseForgeManifest(zip, plan)
        PackExportFormat.MultiMc -> validateMultiMcManifest(zip, plan)
        PackExportFormat.Mcbbs -> validateMcbbsManifest(zip, layout, plan)
        PackExportFormat.Backup -> validateBackupManifest(zip, layout)
    }
}

private fun validateModrinthManifest(
    zip: ZipFile,
    layout: PackExportLayout,
    plan: MrpackExportPlan,
) {
    val index = requireNotNull(packJson(zip, "modrinth.index.json"))
    require(
        index.get("formatVersion")?.asInt == 1 && index.string("game") == "minecraft",
    ) {
        "modrinth.index.json 头部无效"
    }
    require(
        index.string("name") == plan.metadata.name &&
            index.string("versionId") == plan.metadata.version,
    ) {
        "modrinth.index.json 名称或版本不符"
    }
    require(
        index.obj("dependencies").string("minecraft") == plan.components.gameVersion,
    ) {
        "modrinth.index.json 游戏版本不符"
    }
    val listed =
        index.array("files").map { it.asJsonObject.string("path") }.toSet()
    require(listed.size == index.array("files").size()) { "modrinth.index.json 文件重复" }
    require(listed.intersect(layout.payload.map { it.second.path }.toSet()).isEmpty()) {
        "下载清单与包内文件重复"
    }
    require(
        index.array("files").all {
            it.asJsonObject.string("path").isNotBlank() &&
                it.asJsonObject.array("downloads").size() == 1
        },
    ) {
        "modrinth.index.json 文件条目缺少下载地址"
    }
}

private fun validateCurseForgeManifest(
    zip: ZipFile,
    plan: MrpackExportPlan,
) {
    val manifest = requireNotNull(packJson(zip, "manifest.json"))
    require(
        manifest.string("manifestType") == "minecraftModpack" &&
            manifest.string("manifestVersion") == "1",
    ) {
        "manifest.json 不是 CurseForge 整合包清单"
    }
    require(
        manifest.string("name") == plan.metadata.name &&
            manifest.string("version") == plan.metadata.version,
    ) {
        "manifest.json 名称或版本不符"
    }
    require(
        manifest.string("overrides") == "overrides",
    ) { "manifest.json overrides 路径无效" }
    require(
        manifest.obj("minecraft").string("version") == plan.components.gameVersion,
    ) {
        "manifest.json 游戏版本不符"
    }
    val entries = manifest.array("files").map { it.asJsonObject }
    require(entries.map { it.string("projectID") }.distinct().size == entries.size) {
        "manifest.json 重复项目"
    }
    require(
        entries.all {
            it.string("projectID").toLongOrNull() in 1L..MAX_CURSEFORGE_IDENTIFIER &&
                it.string("fileID").toLongOrNull() in 1L..MAX_CURSEFORGE_IDENTIFIER &&
                it.get("required")?.asBoolean == true
        },
    ) {
        "manifest.json 文件条目缺少 projectID/fileID 或 required"
    }
}

private fun validateMultiMcManifest(
    zip: ZipFile,
    plan: MrpackExportPlan,
) {
    val pack = requireNotNull(packJson(zip, "mmc-pack.json"))
    require(pack.get("formatVersion")?.asInt == 1) { "mmc-pack.json 版本无效" }
    val components = pack.array("components").map { it.asJsonObject }
    require(
        components.any {
            it.string("uid") == "net.minecraft" &&
                it.string("version") == plan.components.gameVersion
        },
    ) {
        "mmc-pack.json 缺少匹配的 net.minecraft 组件"
    }
    multimcLoaderUid(plan.components.loader)?.let { uid ->
        require(
            components.any {
                it.string("uid") == uid &&
                    it.string("version") == plan.components.loaderVersion
            },
        ) {
            "mmc-pack.json 缺少匹配的加载器组件"
        }
    }
    val config =
        zip
            .getInputStream(requireNotNull(zip.getEntry("instance.cfg")))
            .use { it.readBytes().toString(Charsets.UTF_8) }
    require(
        "[General]" in config &&
            "InstanceType=OneSix" in config &&
            config.lineSequence().any {
                it.trim().startsWith("name=") &&
                    it.trim().removePrefix("name=").isNotBlank()
            },
    ) {
        "instance.cfg 缺少实例名或 OneSix 类型"
    }
}

private fun validateMcbbsManifest(
    zip: ZipFile,
    layout: PackExportLayout,
    plan: MrpackExportPlan,
) {
    val info = requireNotNull(packJson(zip, "mcbbs.packmeta"))
    require(info.string("manifestType") == "minecraftModpack") {
        "mcbbs.packmeta 不是整合包清单"
    }
    require(
        info.array("addons").any {
            it.asJsonObject.string("id") == "game" &&
                it.asJsonObject.string("version") == plan.components.gameVersion
        },
    ) {
        "mcbbs.packmeta 缺少游戏组件"
    }
    require(info.array("files").size() == layout.payload.size) {
        "mcbbs.packmeta 文件数与包内容不符"
    }
}

private fun validateBackupManifest(
    zip: ZipFile,
    layout: PackExportLayout,
) {
    require(
        zip.getEntry("modrinth.index.json") == null &&
            zip.getEntry("manifest.json") == null &&
            zip.getEntry("mmc-pack.json") == null &&
            zip.getEntry("mcbbs.packmeta") == null,
    ) {
        "完整备份不应包含其他启动器的整合包清单"
    }
    val info = requireNotNull(packJson(zip, "molifox-backup.json"))
    require(
        info.string(
            "type",
        ) == "molifox-backup" && info.get("formatVersion")?.asInt == 1,
    ) {
        "备份清单无效"
    }
    require(info.array("files").size() == layout.payload.size) {
        "备份清单文件数与包内容不符"
    }
}
