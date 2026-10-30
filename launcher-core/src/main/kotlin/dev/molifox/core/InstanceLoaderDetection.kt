// 加载器识别：以 libraries 坐标为第一依据（与 HMCL 的做法一致），mainClass 前缀与目录名只作兜底。
package dev.molifox.core

/** Forge 的库版本形如 `1.20.1-47.2.0`，这里剥掉游戏版本只保留加载器版本。 */
internal fun loaderVersionOf(coordinate: String): String {
    val version = coordinate.split(':').getOrElse(2) { "" }
    val forgeStyle = Regex("""^\d+(?:\.\d+)*-(\d+(?:\.\d+)*(?:-\w+)*)$""")
    return forgeStyle.find(version)?.groupValues?.get(1) ?: version
}

internal fun detectLoader(
    id: String,
    libraries: List<String>,
    mainClass: String = "",
): Pair<LoaderType, String> =
    detectLoaderByLibrary(libraries)
        ?: detectLoaderByMainClass(mainClass)
        ?: (detectLoaderByName(id) to "")

private fun detectLoaderByLibrary(libraries: List<String>): Pair<LoaderType, String>? {
    val prefixes =
        listOf(
            "net.fabricmc:fabric-loader:" to LoaderType.Fabric,
            "org.quiltmc:quilt-loader:" to LoaderType.Quilt,
            "net.neoforged:neoforge:" to LoaderType.NeoForge,
            "net.neoforged.fancymodloader:loader:" to LoaderType.NeoForge,
            "net.minecraftforge:forge:" to LoaderType.Forge,
            "net.minecraftforge:fmlloader:" to LoaderType.Forge,
            "net.minecraftforge:minecraftforge:" to LoaderType.Forge,
        )
    prefixes.forEach { (prefix, loader) ->
        libraries.firstOrNull { it.startsWith(prefix) }?.let { coordinate ->
            return loader to loaderVersionOf(coordinate)
        }
    }
    return null
}

private fun detectLoaderByMainClass(mainClass: String): Pair<LoaderType, String>? {
    val loader =
        when {
            mainClass.startsWith("net.neoforged.") -> LoaderType.NeoForge
            mainClass.startsWith("cpw.mods.") -> LoaderType.Forge
            mainClass.startsWith("net.minecraftforge.") -> LoaderType.Forge
            mainClass.startsWith("net.fabricmc.") -> LoaderType.Fabric
            mainClass.startsWith("org.quiltmc.") -> LoaderType.Quilt
            else -> null
        } ?: return null
    return loader to ""
}

private fun detectLoaderByName(id: String): LoaderType {
    val lower = id.lowercase()
    return when {
        "neoforge" in lower -> LoaderType.NeoForge
        "fabric" in lower -> LoaderType.Fabric
        "quilt" in lower -> LoaderType.Quilt
        "forge" in lower -> LoaderType.Forge
        else -> LoaderType.Vanilla
    }
}
