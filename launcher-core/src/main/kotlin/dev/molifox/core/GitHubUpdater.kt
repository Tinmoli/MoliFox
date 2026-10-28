// 负责读取茉狐的 GitHub Releases、比较版本并校验下载的更新包。

package dev.molifox.core

import com.google.gson.JsonObject
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

data class LauncherUpdateState(
    val checking: Boolean = false,
    val checked: Boolean = false,
    val release: LauncherRelease? = null,
    val available: Boolean = false,
    val error: String? = null,
    val downloadedFile: String? = null,
)

data class LauncherRelease(
    val version: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val pageUrl: String,
    val asset: LauncherReleaseAsset? = null,
)

data class LauncherReleaseAsset(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String,
)

class GitHubUpdater
internal constructor(
    private val store: LauncherStore,
    private val network: NetworkClient,
    private val api: String = "https://api.github.com/repos/Tinmoli/MoliFox",
) {
    private val checkLock = Mutex()

    suspend fun check(manual: Boolean = true) {
        if (!checkLock.tryLock()) return
        store.updateState { it.copy(update = it.update.copy(checking = true, error = null)) }
        try {
            val releases = network.githubJson("$api/releases?per_page=100").asJsonArray
            val latest =
                releases
                    .mapNotNull { item ->
                        val release = item.asJsonObject
                        if (
                            release.get("draft")?.asBoolean == true ||
                                release.get("prerelease")?.asBoolean == true
                        )
                            null
                        else VersionNumber.parse(release.string("tag_name"))?.let { it to release }
                    }
                    .maxByOrNull { it.first }
                    ?.second
            val release = latest?.let { parseRelease(it) }
            val available =
                release != null &&
                    VersionNumber.parse(release.version)!! >
                        (VersionNumber.parse(LAUNCHER_VERSION) ?: error("当前版本格式无效"))
            store.updateState {
                it.copy(
                    update =
                        LauncherUpdateState(
                            checked = true,
                            release = release,
                            available = available,
                            downloadedFile =
                                it.update.downloadedFile.takeIf { _ ->
                                    it.update.release == release
                                },
                        )
                )
            }
            if (manual || available)
                store.notify(
                    when {
                        release == null -> "项目尚未发布正式版本"
                        available -> "发现 MoliFox ${release.version}，可在设置 → 通用下载"
                        else -> "当前已是最新版本 $LAUNCHER_VERSION"
                    }
                )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val message = failure.message ?: "GitHub 更新检查失败"
            store.updateState {
                it.copy(update = it.update.copy(checking = false, error = message))
            }
            if (manual) store.notify(message)
            store.log(message, true)
        } finally {
            store.updateState { it.copy(update = it.update.copy(checking = false)) }
            checkLock.unlock()
        }
    }

    internal fun parseRelease(json: JsonObject): LauncherRelease {
        val version = json.string("tag_name")
        val page = json.string("html_url")
        require(page.startsWith("${MoliFoxIdentity.releases}/tag/")) { "更新发布页不属于茉狐项目" }
        val name =
            when (store.platform.osName) {
                "windows" -> "MoliFox.exe"
                "linux" -> "MoliFox-linux-x64.run"
                else -> ""
            }
        val asset =
            json
                .getAsJsonArray("assets")
                ?.map { it.asJsonObject }
                ?.firstOrNull { it.string("name") == name && store.platform.architecture == "x64" }
                ?.let {
                    val url = it.string("browser_download_url")
                    requireValidDownloadUrl(url)
                    val digest = it.string("digest").removePrefix("sha256:")
                    LauncherReleaseAsset(
                        name,
                        url,
                        it.get("size")?.asLong ?: 0,
                        digest.takeIf { hash -> hash.matches(Regex("[a-fA-F0-9]{64}")) }.orEmpty(),
                    )
                }
        return LauncherRelease(
            version,
            json.string("name").ifBlank { version },
            json.string("body").take(20_000),
            json.string("published_at"),
            page,
            asset,
        )
    }

    suspend fun download(release: LauncherRelease, context: JobContext): Path {
        val asset = requireNotNull(release.asset) { "该版本没有适合此系统架构的更新包，请前往发布页查看" }
        requireValidDownloadUrl(asset.url)
        require(asset.sha256.isNotEmpty()) { "发布包缺少 GitHub SHA-256 摘要，请前往发布页手动下载" }
        require(
            VersionNumber.parse(release.version) != null && asset.size in 1..(512L * 1024 * 1024)
        ) {
            "更新包信息无效"
        }
        val directory =
            store.downloadDirectory.resolve("updates").resolve(safeFilename(release.version))
        createStorageDirectory(directory)
        val target = directory.resolve(asset.name)
        context.progress(0f, "下载 MoliFox ${release.version}…")
        network.downloadAll(
            listOf(DownloadArtifact(asset.url, target, asset.sha256, asset.size, "SHA-256")),
            1,
            context,
            "下载更新包",
        )
        store.updateState { it.copy(update = it.update.copy(downloadedFile = target.toString())) }
        store.notify("更新包已校验；退出启动器后替换原文件，保留旁边的 MoliFox 文件夹")
        return target
    }

    private fun requireValidDownloadUrl(url: String) {
        val uri = URI(url)
        val fixture =
            api.startsWith("http://127.0.0.1:") && uri.host == "127.0.0.1" && uri.scheme == "http"
        require(
            fixture ||
                (uri.scheme == "https" &&
                    uri.host == "github.com" &&
                    uri.userInfo == null &&
                    uri.port in setOf(-1, 443) &&
                    uri.path.startsWith("/Tinmoli/MoliFox/releases/download/"))
        ) {
            "更新包必须来自茉狐自己的 GitHub Releases"
        }
    }
}

internal data class VersionNumber(val numbers: List<Int>, val qualifier: String) :
    Comparable<VersionNumber> {
    override fun compareTo(other: VersionNumber): Int {
        numbers.zip(other.numbers).forEach { (left, right) ->
            if (left != right) return left.compareTo(right)
        }
        if (qualifier.isEmpty() != other.qualifier.isEmpty())
            return if (qualifier.isEmpty()) 1 else -1
        val left = qualifier.split('.')
        val right = other.qualifier.split('.')
        for (index in 0 until minOf(left.size, right.size)) {
            val a = left[index]
            val b = right[index]
            val ai = a.toIntOrNull()
            val bi = b.toIntOrNull()
            val compared =
                when {
                    ai != null && bi != null -> ai.compareTo(bi)
                    ai != null -> -1
                    bi != null -> 1
                    else -> a.compareTo(b)
                }
            if (compared != 0) return compared
        }
        return left.size.compareTo(right.size)
    }

    companion object {
        fun parse(value: String): VersionNumber? {
            val match =
                Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
                    .matchEntire(value) ?: return null
            val numbers = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            return VersionNumber(numbers, match.groupValues[4])
        }
    }
}
