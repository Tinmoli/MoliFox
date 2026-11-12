// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

package dev.molifox.core

internal const val MINECRAFT_VERSION_TYPE_MARKER = "_minecraftVersionType"
private val NON_RELEASE_VERSION_TYPES = setOf("snapshot", "old_beta", "old_alpha")

enum class VersionIsolationPolicy {
    Disabled,
    Modded,
    NonRelease,
    ModdedOrNonRelease,
    All,
    ;

    val usesVersionType: Boolean
        get() = this == NonRelease || this == ModdedOrNonRelease

    fun isolates(
        loader: LoaderType,
        versionType: String,
    ): Boolean {
        val modded = loader != LoaderType.Vanilla && loader != LoaderType.OptiFine
        val nonRelease = versionType in NON_RELEASE_VERSION_TYPES
        return when (this) {
            Disabled -> false
            Modded -> modded
            NonRelease -> nonRelease
            ModdedOrNonRelease -> modded || nonRelease
            All -> true
        }
    }
}

/** 两个传统模式继续读取旧布尔字段，条件模式另存为枚举。 */
val LauncherSettings.effectiveVersionIsolationPolicy: VersionIsolationPolicy
    get() =
        versionIsolationPolicy
            ?: if (isolateVersions) VersionIsolationPolicy.All else VersionIsolationPolicy.Disabled

fun LauncherSettings.withVersionIsolationPolicy(policy: VersionIsolationPolicy): LauncherSettings =
    copy(
        isolateVersions = policy != VersionIsolationPolicy.Disabled,
        versionIsolationPolicy =
            policy.takeUnless {
                it == VersionIsolationPolicy.All ||
                    it == VersionIsolationPolicy.Disabled
            },
    )
