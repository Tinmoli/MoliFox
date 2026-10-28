// 负责验证便携目录分层和旧数据迁移的行为与边界。

package dev.molifox.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.*
import kotlin.test.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StorageLayoutTest {
    @TempDir lateinit var temporary: Path

    private fun configuration(root: Path): LauncherData {
        val instance =
            GameInstance(
                name = "Existing pack",
                gameVersion = "26.3",
                versionId = "fabric-26.3",
                installed = true,
                loader = LoaderType.Fabric,
                packFile = root.resolve("packs/old.mrpack").toString(),
            )
        val account = Account(name = "Existing player", uuid = offlineUuid("Existing player"))
        return LauncherData(
            settings =
                LauncherSettings(
                    gameRoot = temporary.resolve("external-game").toString(),
                    gameRootSemantics = 1,
                    backgroundPath = root.resolve("appearance/background.img").toString(),
                ),
            accounts = listOf(account),
            activeAccountId = account.id,
            instances = listOf(instance),
            selectedInstanceId = instance.id,
        )
    }

    private fun put(root: Path, relative: String, text: String) =
        root.resolve(relative).apply {
            parent.createDirectories()
            writeText(text)
        }

    @Test
    fun `legacy configuration assets pack references and credentials survive migration`() {
        val root = temporary.resolve("MoliFox").createDirectory()
        val original = configuration(root)
        writeJson(root.resolve("launcher.json"), original)
        put(root, "packs/old.mrpack", "pack bytes")
        put(root, "credentials/account.bin", "private bytes")
        put(root, "skins/library.json", "user skin library")
        put(root, "icons/user.png", "user icon")
        put(root, "appearance/background.img", "user background")
        put(root, "appearance/fonts/imported.font", "user font")
        put(root, "launcher.log", "old log")
        put(root, "backup/old-move/marker", "migration backup")
        val game = temporary.resolve("external-game").createDirectory()
        game.resolve("marker.txt").writeText("do not move")
        val store = LauncherStore(DesktopPlatform(), root)
        assertEquals(original.accounts, store.data.accounts)
        assertEquals(original.activeAccountId, store.data.activeAccountId)
        assertEquals(original.selectedInstanceId, store.data.selectedInstanceId)
        assertEquals(original.settings.gameRoot, store.data.settings.gameRoot)
        assertEquals(
            root.resolve("backups/packs/old.mrpack").toString(),
            store.data.instances.single().packFile,
        )
        assertEquals(
            root.resolve("appearance/backgrounds/background.img").toString(),
            store.data.settings.backgroundPath,
        )
        for ((file, value) in
            mapOf(
                "credentials/account.bin" to "private bytes",
                "skins/library.json" to "user skin library",
                "icons/user.png" to "user icon",
                "appearance/fonts/imported.font" to "user font",
                "backups/packs/old.mrpack" to "pack bytes",
                "backups/instances/old-move/marker" to "migration backup",
                "appearance/backgrounds/background.img" to "user background",
                "logs/launcher.log" to "old log",
            )) assertEquals(value, root.resolve(file).readText())
        assertFalse(root.resolve("launcher.json").exists())
        assertFalse(root.resolve("packs").exists())
        assertEquals("do not move", game.resolve("marker.txt").readText())
        assertFalse(root.resolve("external-game").exists())
        assertTrue(root.resolve("config/launcher.json").isRegularFile())
    }

    @Test
    fun `interruption before configuration rewrite resumes pack and background references`() {
        val root = temporary.resolve("profile").createDirectory()
        writeJson(root.resolve("launcher.json"), configuration(root))
        put(root, "packs/old.mrpack", "pack")
        put(root, "appearance/background.img", "background")
        LauncherDirectories(root).initialize()
        assertEquals(
            root.resolve("packs/old.mrpack").toString(),
            readObject(root.resolve("config/launcher.json"))
                .array("instances")
                .first()
                .asJsonObject
                .string("packFile"),
        )
        val recovered = LauncherStore(DesktopPlatform(), root)
        assertEquals("pack", Path.of(recovered.data.instances.single().packFile).readText())
        assertEquals("background", Path.of(recovered.data.settings.backgroundPath).readText())
        val journal = root.resolve("config/storage-layout.json").readBytes()
        val again = LauncherStore(DesktopPlatform(), root)
        assertEquals(recovered.data, again.data)
        assertContentEquals(journal, root.resolve("config/storage-layout.json").readBytes())
    }

    @Test
    fun `current config wins while conflicting legacy content is retained by hash`() {
        val root = temporary.resolve("profile").createDirectory()
        val old = configuration(root)
        val current = old.copy(accounts = emptyList(), activeAccountId = null)
        root.resolve("config").createDirectory()
        writeJson(root.resolve("config/launcher.json"), current)
        writeJson(root.resolve("launcher.json"), old)
        put(root, "packs/old.mrpack", "legacy pack")
        put(root, "backups/packs/old.mrpack", "current pack")
        val store = LauncherStore(DesktopPlatform(), root)
        assertTrue(store.data.accounts.isEmpty())
        assertEquals("current pack", root.resolve("backups/packs/old.mrpack").readText())
        assertEquals("legacy pack", Path.of(store.data.instances.single().packFile).readText())
        val archive = root.resolve("backups/legacy").listDirectoryEntries()
        assertEquals(2, archive.size)
        assertTrue(archive.any { it.readText() == "legacy pack" })
        assertTrue(
            archive.any {
                it.name.endsWith("launcher.json") && readObject(it).array("accounts").size() == 1
            }
        )
        LauncherStore(DesktopPlatform(), root)
        assertEquals(2, root.resolve("backups/legacy").listDirectoryEntries().size)
    }

    @Test
    fun `damaged legacy configuration recovers from old backup without losing failed file`() {
        val root = temporary.resolve("profile").createDirectory()
        val data = configuration(root)
        put(root, "launcher.json", "{broken")
        writeJson(root.resolve("launcher.json.bak"), data)
        val store = LauncherStore(DesktopPlatform(), root)
        assertEquals(data.accounts, store.data.accounts)
        assertTrue(
            store.directories.configurationBackups
                .listDirectoryEntries("launcher.damaged-*.json")
                .any { it.readText() == "{broken" }
        )
        for (index in 1..5) {
            store.directories.configuration.writeText("broken-$index")
            LauncherStore(DesktopPlatform(), root)
        }
        assertEquals(
            3,
            store.directories.configurationBackups
                .listDirectoryEntries("launcher.damaged-*.json")
                .size,
        )
        assertEquals(data.accounts, LauncherStore(DesktopPlatform(), root).data.accounts)
    }

    @Test
    fun `custom cache retains downloaded files partials and unrelated files`() {
        val root = temporary.resolve("profile").createDirectory()
        val cache = temporary.resolve("chosen-cache").createDirectory()
        val original = configuration(root)
        writeJson(
            root.resolve("launcher.json"),
            original.copy(settings = original.settings.copy(downloadCachePath = cache.toString())),
        )
        put(cache, "versions.json", "version metadata")
        put(cache, "java-21-x64.zip.part", "partial download")
        put(cache, "pack-downloads/project/old.mrpack", "downloaded pack")
        put(cache, "other-application.txt", "keep")
        val store = LauncherStore(DesktopPlatform(), root)
        assertEquals(cache, store.cacheDirectory)
        assertEquals(
            "version metadata",
            store.metadataDirectory.resolve("versions.json").readText(),
        )
        assertEquals(
            "partial download",
            store.downloadDirectory.resolve("java/java-21-x64.zip.part").readText(),
        )
        assertEquals(
            "downloaded pack",
            store.downloadDirectory.resolve("packs/project/old.mrpack").readText(),
        )
        assertEquals("keep", cache.resolve("other-application.txt").readText())
        assertFalse(cache.resolve("versions.json").exists())
    }

    @Test
    fun `portable token remains decryptable after moving only configuration`() {
        val root = temporary.resolve("profile")
        val store = LauncherStore(DesktopPlatform(), root)
        val id = java.util.UUID.randomUUID().toString()
        TokenVault(store).save(id, "synthetic-refresh-token")
        val credential = root.resolve("credentials/$id.json").readBytes()
        Files.move(store.directories.configuration, root.resolve("launcher.json"))
        val migrated = LauncherStore(DesktopPlatform(), root)
        assertEquals("synthetic-refresh-token", TokenVault(migrated).read(id))
        assertContentEquals(credential, root.resolve("credentials/$id.json").readBytes())
    }

    @Test
    fun `diagnostics reads new logs while excluding configuration credentials and backups`() {
        val root = temporary.resolve("profile").createDirectory()
        put(root, "launcher.log", "old startup log")
        val store = LauncherStore(DesktopPlatform(), root)
        store.log("new log line")
        put(root, "credentials/secret.json", "DO_NOT_EXPORT")
        put(root, "backups/private.json", "DO_NOT_EXPORT")
        val exported = temporary.resolve("diagnostics.zip")
        exportDiagnosticBundle(store, exported)
        ZipFile(exported.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf("logs/launcher.log"), names)
            val log = zip.getInputStream(zip.getEntry("logs/launcher.log")).reader().readText()
            assertTrue(log.contains("old startup log") && log.contains("new log line"))
            assertFalse(log.contains("DO_NOT_EXPORT"))
        }
        assertFalse(root.resolve("launcher.log").exists())
    }

    @Test
    fun `second controller cannot migrate or change data under an active profile lock`() {
        val root = temporary.resolve("profile")
        LauncherController(DesktopPlatform(), root).use { first ->
            val current = first.store.directories.configuration.readBytes()
            put(root, "launcher.json", "legacy file must stay while locked")
            assertFails { LauncherController(DesktopPlatform(), root) }
            assertContentEquals(current, first.store.directories.configuration.readBytes())
            assertEquals(
                "legacy file must stay while locked",
                root.resolve("launcher.json").readText(),
            )
        }
    }

    @Test
    fun `malformed relocation journal cannot escape the profile or delete original data`() {
        val root = temporary.resolve("profile").createDirectory()
        put(root, "launcher.json", "keep original")
        put(
            root,
            "config/storage-layout.json",
            """{"layoutVersion":1,"relocations":{"launcher.json":"../outside.json"}}""",
        )
        val outside = put(temporary, "outside.json", "keep outside")
        assertFails { LauncherStore(DesktopPlatform(), root) }
        assertEquals("keep original", root.resolve("launcher.json").readText())
        assertEquals("keep outside", outside.readText())
    }

    @Test
    fun `migration refuses symbolic directory targets and preserves external files`() {
        val root = temporary.resolve("profile").createDirectory()
        val outside = temporary.resolve("external").createDirectory()
        put(outside, "marker", "untouched")
        assumeTrue(
            runCatching { Files.createSymbolicLink(root.resolve("config"), outside) }.isSuccess,
            "Creating symbolic links requires platform permission",
        )
        put(root, "launcher.json", "keep old")
        assertFails { LauncherStore(DesktopPlatform(), root) }
        assertEquals("keep old", root.resolve("launcher.json").readText())
        assertEquals(listOf("marker"), outside.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `fresh profile has portable roots and never creates a game installation`() {
        val root = temporary.resolve("MoliFox")
        val store = LauncherStore(DesktopPlatform(), root)
        assertEquals(temporary.resolve(".minecraft").toString(), store.data.settings.gameRoot)
        assertFalse(temporary.resolve(".minecraft").exists())
        for (directory in
            listOf(
                "config",
                "credentials",
                "appearance/backgrounds",
                "appearance/fonts",
                "appearance/music",
                "appearance/themes",
                "skins",
                "icons",
                "runtimes",
                "dependencies",
                "logs",
                "backups/config",
                "cache/downloads",
                "cache/metadata",
                "cache/skins",
                "cache/tmp",
            )) assertTrue(root.resolve(directory).isDirectory(), directory)
        assertFalse(root.resolve("launcher.json").exists())
        assertTrue(root.resolve("config/launcher.json").isRegularFile())
    }
}
