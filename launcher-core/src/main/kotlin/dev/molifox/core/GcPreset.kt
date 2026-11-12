// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// GC 策略：只保存用户选择，启动时再按 Java 版本生成参数。
package dev.molifox.core

import dev.molifox.core.i18n.I18n

private const val ZGC_MINIMUM_JAVA = 11
private const val GENERATIONAL_ZGC_MINIMUM_JAVA = 21
private const val GENERATIONAL_ZGC_MAXIMUM_JAVA = 22
private const val HEAP_FREE_RATIO_MINIMUM_JAVA = 12
private const val PARALLEL_REF_PROC_JAVA = 8
private const val COMPACT_OBJECT_HEADERS_JAVA = 24
private val collectorFlags =
    setOf(
        "UseSerialGC",
        "UseParallelGC",
        "UseParallelOldGC",
        "UseConcMarkSweepGC",
        "UseG1GC",
        "UseZGC",
        "UseShenandoahGC",
        "UseEpsilonGC",
    )

const val DEFAULT_EXTRA_JVM_ARGS =
    "-XX:-OmitStackTraceInFastThrow\n" +
        "-Djdk.lang.Process.allowAmbiguousCommands=True\n" +
        "-Dfml.ignoreInvalidMinecraftCertificates=True\n" +
        "-Dfml.ignorePatchDiscrepancies=True"

/** 高级启动参数里的 GC 策略；默认使用调优 G1。 */
enum class GcPreset(
    val value: String,
    val titleKey: String,
) {
    Zgc("zgc", "settings.game.advanced.gc.zgc"),
    GenerationalZgc("generational_zgc", "settings.game.advanced.gc.generationalZgc"),
    StandardG1("standard_g1", "settings.game.advanced.gc.standardG1"),
    TunedG1("tuned_g1", "settings.game.advanced.gc.tunedG1"),
    Unspecified("unspecified", "settings.game.advanced.gc.unspecified"),
    ;

    /** 按 Java 主版本生成实际 JVM 参数；未知/不支持的组合回退到兼容档位。 */
    fun flagsFor(javaMajor: Int): List<String> =
        when (this) {
            Unspecified -> emptyList()
            Zgc -> zgcFlags(javaMajor)
            GenerationalZgc -> generationalZgcFlags(javaMajor)
            StandardG1 -> listOf("-XX:+UseG1GC")
            TunedG1 -> tunedG1Flags(javaMajor)
        }

    companion object {
        fun from(value: String?): GcPreset = entries.firstOrNull { it.value == value } ?: TunedG1
    }
}

/** 旧配置与版本模板都可显式选择收集器，此时整组预设应让位。 */
internal fun GcPreset.launchFlags(
    javaMajor: Int,
    arguments: List<String>,
): List<String> {
    val enabled = mutableSetOf<String>()
    for (argument in arguments) {
        val flag = argument.drop("-XX:+".length)
        if (flag !in collectorFlags || (!argument.startsWith("-XX:+") && !argument.startsWith("-XX:-"))) continue
        if (argument.startsWith("-XX:+")) enabled.add(flag) else enabled.remove(flag)
    }
    val collectors = enabled.map { if (it == "UseParallelOldGC") "UseParallelGC" else it }.toSet()
    require(collectors.size <= 1) { I18n.t("core.gameLauncher.gcConflict", enabled.joinToString()) }
    return if (enabled.isEmpty()) flagsFor(javaMajor) else emptyList()
}

private fun zgcFlags(javaMajor: Int): List<String> =
    if (javaMajor < ZGC_MINIMUM_JAVA) {
        listOf("-XX:+UseG1GC")
    } else {
        listOf("-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC")
    }

private fun generationalZgcFlags(javaMajor: Int): List<String> =
    when {
        javaMajor < ZGC_MINIMUM_JAVA -> listOf("-XX:+UseG1GC")

        javaMajor in GENERATIONAL_ZGC_MINIMUM_JAVA..GENERATIONAL_ZGC_MAXIMUM_JAVA ->
            listOf(
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+UseZGC",
                "-XX:+ZGenerational",
            )

        else -> zgcFlags(javaMajor)
    }

private fun tunedG1Flags(javaMajor: Int): List<String> =
    buildList {
        add("-XX:+UnlockExperimentalVMOptions")
        add("-XX:+UseG1GC")
        add("-XX:G1NewSizePercent=20")
        add("-XX:G1ReservePercent=20")
        add("-XX:G1HeapRegionSize=32M")
        add("-XX:MaxGCPauseMillis=50")
        add("-XX:+PerfDisableSharedMem")
        if (javaMajor >= HEAP_FREE_RATIO_MINIMUM_JAVA) {
            add("-XX:MinHeapFreeRatio=25")
            add("-XX:MaxHeapFreeRatio=40")
        }
        if (javaMajor == PARALLEL_REF_PROC_JAVA) add("-XX:+ParallelRefProcEnabled")
        if (javaMajor >= COMPACT_OBJECT_HEADERS_JAVA) add("-XX:+UseCompactObjectHeaders")
    }
