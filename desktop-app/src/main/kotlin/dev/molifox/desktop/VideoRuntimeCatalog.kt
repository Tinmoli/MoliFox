// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// mediamp/MPV 运行库的固定清单：版本、平台、大小与摘要都不随构建变化。
package dev.molifox.desktop

import java.nio.file.Path

internal const val MPV_RUNTIME_VERSION = "0.5.3"
private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
private const val ALIYUN_MAVEN = "https://maven.aliyun.com/repository/public"

internal data class MpvRuntimeAsset(
    val platformId: String,
    val artifactName: String,
    val size: Long,
    val sha256: String,
    val license: String,
) {
    val fileName: String get() = "$artifactName-$MPV_RUNTIME_VERSION.jar"

    fun urls(): List<String> =
        listOf(
            "$MAVEN_CENTRAL/org/openani/mediamp/$artifactName/$MPV_RUNTIME_VERSION/$fileName",
            "$ALIYUN_MAVEN/org/openani/mediamp/$artifactName/$MPV_RUNTIME_VERSION/$fileName",
        )
}

private val RUNTIME_ASSETS =
    listOf(
        MpvRuntimeAsset(
            "windows-x64",
            "mediamp-mpv-runtime-windows-x64",
            22_180_917,
            "228642a9a2f22ab4e8bf8096d935e1bbc2493310d8a47a4a321aef9378cb6c26",
            "LGPL-2.1-or-later",
        ),
        MpvRuntimeAsset(
            "windows-arm64",
            "mediamp-mpv-runtime-windows-arm64",
            17_867_716,
            "bc6222e61b13b890665c3a019d9058a10cfb7f7a815a20905536b13b6b340229",
            "LGPL-2.1-or-later",
        ),
        MpvRuntimeAsset(
            "linux-x64",
            "mediamp-mpv-runtime-linux-x64",
            29_657_424,
            "d1169c752a587f8d96311e773cda057ca8563fc5c8cb5088f299d61c95d0a15a",
            "GPL-3.0-only",
        ),
        MpvRuntimeAsset(
            "macos-x64",
            "mediamp-mpv-runtime-macos-x64",
            25_402_220,
            "7c9bda187618d851cc258378126d1afdb48202561b476e9aaaab45947406edf0",
            "LGPL-2.1-or-later",
        ),
        MpvRuntimeAsset(
            "macos-arm64",
            "mediamp-mpv-runtime-macos-arm64",
            21_753_403,
            "1ed2def130d751d5c14add0995726670de6426bc4cf868353e963a5c363a656f",
            "LGPL-2.1-or-later",
        ),
    )

/** 当前平台可用的 mediamp 运行库；Linux ARM64 没有发布物，返回 null。 */
internal fun mpvRuntimeAsset(
    osName: String = System.getProperty("os.name").orEmpty(),
    architecture: String = System.getProperty("os.arch").orEmpty(),
): MpvRuntimeAsset? {
    val os =
        when {
            osName.startsWith("Windows", ignoreCase = true) -> "windows"
            osName.equals("Linux", ignoreCase = true) -> "linux"
            osName.startsWith("Mac", ignoreCase = true) -> "macos"
            else -> return null
        }
    val arch =
        when (architecture.lowercase()) {
            "x86_64", "amd64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> return null
        }
    return RUNTIME_ASSETS.firstOrNull { it.platformId == "$os-$arch" }
}

internal fun videoRuntimeDirectory(dataDirectory: Path): Path =
    dataDirectory.resolve("dependencies/video/mpv").toAbsolutePath().normalize()
