// 负责游戏及加载器与 Java 版本的兼容判断。

package dev.molifox.core

internal fun normalizedJavaArchitecture(value: String): String =
    when (value.lowercase()) {
        "amd64",
        "x86_64",
        "x64" -> "x64"
        "aarch64",
        "arm64" -> "arm64"
        "x86",
        "i386",
        "i486",
        "i586",
        "i686" -> "x86"
        else -> value.lowercase()
    }

/** Metadata is a minimum; legacy LaunchWrapper additionally requires Java 8's class loader. */
internal data class JavaRequirement(
    val minimum: Int,
    val maximum: Int? = null,
    val exactAutomatic: Boolean = false,
) {
    fun problem(runtime: JavaRuntime, architecture: String): String? =
        when {
            normalizedJavaArchitecture(runtime.architecture) !=
                normalizedJavaArchitecture(architecture) ->
                "Java 架构 ${runtime.architecture.ifBlank { "未知" }} 与当前平台 $architecture 不匹配"
            runtime.majorVersion < minimum ->
                "游戏至少需要 Java $minimum，当前是 Java ${runtime.majorVersion}"
            maximum != null && runtime.majorVersion > maximum ->
                "此加载器只支持 Java $minimum–$maximum，当前是 Java ${runtime.majorVersion}"
            else -> null
        }

    companion object {
        fun from(profile: ResolvedProfile): JavaRequirement {
            val libraries = profile.json.array("libraries").map { it.asJsonObject.string("name") }
            val legacy =
                profile.requiredJava <= 8 &&
                    libraries.any { it.startsWith("net.minecraft:launchwrapper:") }
            val modded = libraries.any {
                it.startsWith("net.fabricmc:fabric-loader:") ||
                    it.startsWith("org.quiltmc:quilt-loader:") ||
                    it.startsWith("net.minecraftforge:") ||
                    it.startsWith("net.neoforged:")
            }
            // Use the declared runtime for modded/old games unless the user explicitly overrides
            // it.
            return JavaRequirement(
                profile.requiredJava,
                if (legacy) 8 else null,
                modded || profile.requiredJava <= 8,
            )
        }
    }
}
