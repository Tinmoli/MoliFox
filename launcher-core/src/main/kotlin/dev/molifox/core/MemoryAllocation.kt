// 负责自动和手动游戏内存分配。

package dev.molifox.core

data class GameMemoryAllocation(val maximumMb: Int, val initialMb: Int, val automatic: Boolean)

/** Recomputed at launch; leave room for the OS, launcher and native/off-heap allocations. */
fun automaticMemoryMb(totalMb: Int, availableMb: Int, architecture: String = "x64"): Int {
    val total = totalMb.coerceAtLeast(1024)
    val available = availableMb.coerceIn(0, total)
    val systemReserve = maxOf(1024, total / 8)
    val budget =
        minOf((available - 512).coerceAtLeast(512), (total - systemReserve).coerceAtLeast(512))
    val suggested = minOf(budget * 3L / 5, total / 2L, 12288L).toInt()
    val javaLimit =
        if (architecture.lowercase() in setOf("x86", "i386", "i686", "32")) 1024 else 12288
    return (suggested / 256 * 256).coerceIn(512, javaLimit)
}

fun effectiveGameMemory(
    settings: LauncherSettings,
    instance: GameInstance,
    platform: PlatformServices,
    architecture: String = platform.architecture,
): GameMemoryAllocation {
    // Existing explicit per-instance allocations remain manual unless the user opts in.
    val automatic = instance.autoMemory ?: (instance.memoryMb <= 0 && settings.autoMemory)
    val maximum =
        if (automatic)
            automaticMemoryMb(platform.totalMemoryMb, platform.availableMemoryMb, architecture)
        else instance.memoryMb.takeIf { it > 0 } ?: settings.memoryMb
    val initial =
        (instance.initialMemoryMb.takeIf { it > 0 } ?: settings.initialMemoryMb).coerceIn(
            128,
            maximum,
        )
    return GameMemoryAllocation(maximum, initial, automatic)
}
