// 按其他实例实际引用的版本、依赖和资源保护共享文件，避免把整个游戏根误判为不可清理。

package dev.molifox.core

import java.nio.file.Path
import kotlin.io.path.*

internal class TaskFileReferences(store: LauncherStore, ownedIds: Set<String>) {
    private data class References(
        val root: Path,
        val files: Set<Path> = emptySet(),
        val trees: Set<Path> = emptySet(),
        val globalGameDirectory: Boolean = false,
        val uncertain: Boolean = false,
    ) {
        fun protects(path: Path): Boolean {
            if (!path.startsWith(root)) return false
            if (uncertain || path in files || trees.any { path.startsWith(it) }) return true
            return globalGameDirectory &&
                root.relativize(path).firstOrNull()?.toString() !in
                    setOf("versions", "libraries", "assets")
        }
    }

    private val references =
        store.data.instances
            .filter {
                it.id !in ownedIds &&
                    (it.installed ||
                        store.state.value.runningGames.any { game -> game.instanceId == it.id })
            }
            .map { instance ->
                val root = store.minecraftRoot(instance).toAbsolutePath().normalize()
                runCatching {
                    val resolver = VersionResolver(root, store.platform)
                    val profile = resolver.resolve(instance.versionId)
                    val files = mutableSetOf<Path>()
                    val trees = mutableSetOf<Path>()
                    var id = instance.versionId
                    val visited = mutableSetOf<String>()
                    while (id.isNotBlank() && visited.add(id)) {
                        val version = safePath(root.resolve("versions"), safeFilename(id))
                        trees.add(version)
                        id = readObject(version.resolve("$id.json")).string("inheritsFrom")
                    }
                    trees.add(safePath(root.resolve("versions"), safeFilename(profile.jarId)))
                    resolver.libraries(profile).forEach { library ->
                        resolver.libraryArtifacts(library).forEach { (artifact, _) ->
                            files.add(artifact.target.toAbsolutePath().normalize())
                        }
                    }
                    val index = profile.json.obj("assetIndex")
                    if (index.string("id").isNotBlank()) {
                        val path =
                            safePath(root.resolve("assets/indexes"), "${index.string("id")}.json")
                        files.add(path)
                        if (path.isRegularFile()) {
                            readObject(path).obj("objects").entrySet().forEach { (_, entry) ->
                                val hash = entry.asJsonObject.string("hash")
                                require(hash.matches(Regex("[a-fA-F0-9]{40}")))
                                files.add(root.resolve("assets/objects/${hash.take(2)}/$hash"))
                            }
                        } else trees.add(root.resolve("assets"))
                    }
                    val logId = profile.json.obj("logging").obj("client").obj("file").string("id")
                    if (logId.isNotBlank())
                        files.add(safePath(root.resolve("assets/log_configs"), logId))
                    val game = store.gamePath(instance).toAbsolutePath().normalize()
                    if (game != root) trees.add(game)
                    References(root, files, trees, globalGameDirectory = game == root)
                }
                    .getOrElse { References(root, uncertain = true) }
            }

    fun protects(path: Path): Boolean = references.any { it.protects(path) }
}
