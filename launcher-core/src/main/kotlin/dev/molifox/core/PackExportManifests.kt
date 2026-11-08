// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责导出包各格式清单的写后断言。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
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
        PackExportFormat.MultiMc -> validateMultiMcManifest(zip, layout, plan)
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
        I18n.t("packsA1.packExportManifests.modrinthHeaderInvalid")
    }
    require(
        index.string("name") == plan.metadata.name &&
            index.string("versionId") == plan.metadata.version,
    ) {
        I18n.t("packsA1.packExportManifests.modrinthNameVersionMismatch")
    }
    require(
        index.obj("dependencies").string("minecraft") == plan.components.gameVersion,
    ) {
        I18n.t("packsA1.packExportManifests.modrinthGameVersionMismatch")
    }
    val listed =
        index.array("files").map { it.asJsonObject.string("path") }.toSet()
    require(listed.size == index.array("files").size()) { I18n.t("packsA1.packExportManifests.modrinthDuplicateFiles") }
    require(listed.intersect(layout.payload.map { it.second.path }.toSet()).isEmpty()) {
        I18n.t("packsA1.packExportManifests.downloadListOverlap")
    }
    require(
        index.array("files").all {
            it.asJsonObject.string("path").isNotBlank() &&
                it.asJsonObject.array("downloads").size() == 1
        },
    ) {
        I18n.t("packsA1.packExportManifests.modrinthEntryWithoutDownload")
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
        I18n.t("packsA1.packExportManifests.curseForgeManifestInvalid")
    }
    require(
        manifest.string("name") == plan.metadata.name &&
            manifest.string("version") == plan.metadata.version,
    ) {
        I18n.t("packsA1.packExportManifests.curseForgeNameVersionMismatch")
    }
    require(
        manifest.string("overrides") == "overrides",
    ) { I18n.t("packsA1.packExportManifests.curseForgeOverridesInvalid") }
    require(
        manifest.obj("minecraft").string("version") == plan.components.gameVersion,
    ) {
        I18n.t("packsA1.packExportManifests.curseForgeGameVersionMismatch")
    }
    val entries = manifest.array("files").map { it.asJsonObject }
    require(entries.map { it.string("projectID") }.distinct().size == entries.size) {
        I18n.t("packsA1.packExportManifests.curseForgeDuplicateProject")
    }
    require(
        entries.all {
            it.string("projectID").toLongOrNull() in 1L..MAX_CURSEFORGE_IDENTIFIER &&
                it.string("fileID").toLongOrNull() in 1L..MAX_CURSEFORGE_IDENTIFIER &&
                it.get("required")?.asBoolean == true
        },
    ) {
        I18n.t("packsA1.packExportManifests.curseForgeEntryInvalid")
    }
}

private fun validateMultiMcManifest(
    zip: ZipFile,
    layout: PackExportLayout,
    plan: MrpackExportPlan,
) {
    val pack = requireNotNull(packJson(zip, "mmc-pack.json"))
    require(pack.get("formatVersion")?.asInt == 1) {
        I18n.t("packsA1.packExportManifests.mmcPackFormatInvalid")
    }
    val components = pack.array("components").map { it.asJsonObject }
    require(components.all { it.string("version").isNotBlank() }) {
        I18n.t("packsA1.packExportManifests.mmcPackComponentVersionMissing")
    }
    require(
        components.any {
            it.string("uid") == "net.minecraft" &&
                it.string("version") == plan.components.gameVersion
        },
    ) {
        I18n.t("packsA1.packExportManifests.mmcPackMinecraftComponentMissing")
    }
    multimcLoaderUid(plan.components.loader)?.let { uid ->
        require(
            components.any {
                it.string("uid") == uid &&
                    it.string("version") == plan.components.loaderVersion
            },
        ) {
            I18n.t("packsA1.packExportManifests.mmcPackLoaderComponentMissing")
        }
    }
    val config = instanceConfigText(zip)
    require(
        "[General]" in config &&
            "InstanceType=OneSix" in config &&
            config.lineSequence().any {
                it.trim().startsWith("name=") &&
                    it.trim().removePrefix("name=").isNotBlank()
            },
    ) {
        I18n.t("packsA1.packExportManifests.instanceCfgNameMissing")
    }
    validateMultiMcInstanceConfig(zip, config, layout)
}

/** 逐键核对导出写入的实例设置：名称长度、内存/JVM 覆盖与图标键必须自洽。 */
private fun validateMultiMcInstanceConfig(
    zip: ZipFile,
    config: String,
    layout: PackExportLayout,
) {
    val values =
        config
            .lineSequence()
            .map { it.trim() }
            .filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    val name = values["name"].orEmpty()
    require(name.isNotBlank() && name.length <= MAX_MULTIMC_INSTANCE_NAME_CHARS) {
        I18n.t("packsA1.packExportManifests.instanceCfgNameTooLong", MAX_MULTIMC_INSTANCE_NAME_CHARS)
    }
    require((values["OverrideJavaArgs"] == "true") == values.containsKey("JvmArgs")) {
        I18n.t("packsA1.packExportManifests.instanceCfgJavaArgsMismatch")
    }
    require((values["OverrideMemory"] == "true") == values.containsKey("MaxMemAlloc")) {
        I18n.t("packsA1.packExportManifests.instanceCfgMemoryMismatch")
    }
    val iconKey = values["iconKey"].orEmpty()
    if (layout.icon == null) {
        require(iconKey == "default") {
            I18n.t("packsA1.packExportManifests.instanceCfgIconNotWritten")
        }
    } else {
        require(
            iconKey == MULTIMC_ICON_KEY && zip.getEntry(layout.iconPath) != null,
        ) {
            I18n.t("packsA1.packExportManifests.instanceCfgIconKeyMismatch")
        }
    }
}

private fun instanceConfigText(zip: ZipFile): String =
    zip
        .getInputStream(requireNotNull(zip.getEntry("instance.cfg")))
        .use { it.readBytes().toString(Charsets.UTF_8) }

private fun validateMcbbsManifest(
    zip: ZipFile,
    layout: PackExportLayout,
    plan: MrpackExportPlan,
) {
    val info = requireNotNull(packJson(zip, "mcbbs.packmeta"))
    require(info.string("manifestType") == "minecraftModpack") {
        I18n.t("packsA1.packExportManifests.mcbbsManifestInvalid")
    }
    require(
        info.array("addons").any {
            it.asJsonObject.string("id") == "game" &&
                it.asJsonObject.string("version") == plan.components.gameVersion
        },
    ) {
        I18n.t("packsA1.packExportManifests.mcbbsGameComponentMissing")
    }
    require(info.array("files").size() == layout.payload.size) {
        I18n.t("packsA1.packExportManifests.mcbbsFileCountMismatch")
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
        I18n.t("packsA1.packExportManifests.backupContainsForeignManifest")
    }
    val info = requireNotNull(packJson(zip, "molifox-backup.json"))
    require(
        info.string(
            "type",
        ) == "molifox-backup" && info.get("formatVersion")?.asInt == 1,
    ) {
        I18n.t("packsA1.packExportManifests.backupManifestInvalid")
    }
    require(info.array("files").size() == layout.payload.size) {
        I18n.t("packsA1.packExportManifests.backupFileCountMismatch")
    }
}
