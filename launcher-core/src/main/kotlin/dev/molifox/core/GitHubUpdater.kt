// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责读取茉狐的 GitHub Releases、比较版本并校验下载的更新包。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import kotlin.io.path.name

private const val MAX_RELEASE_NOTES_CHARS = 20_000
private const val MAX_UPDATE_PACKAGE_BYTES = 512L * 1024 * 1024
private const val RELEASE_HTTPS_PORT = 443
private const val VERSION_QUALIFIER_GROUP = 4

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
                val release = latestRelease()
                publishUpdate(release, manual)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                reportCheckFailure(failure, manual)
            } catch (failure: JsonParseException) {
                reportCheckFailure(failure, manual)
            } catch (failure: IllegalArgumentException) {
                reportCheckFailure(failure, manual)
            } catch (failure: IllegalStateException) {
                reportCheckFailure(failure, manual)
            } catch (failure: URISyntaxException) {
                reportCheckFailure(failure, manual)
            } finally {
                store.updateState { it.copy(update = it.update.copy(checking = false)) }
                checkLock.unlock()
            }
        }

        private suspend fun latestRelease(): LauncherRelease? {
            val releases = network.gitHubReleaseList("$api/releases?per_page=100")
            val latest =
                releases
                    .mapNotNull { item ->
                        val release = item.asJsonObject
                        if (
                            release.get("draft")?.asBoolean == true ||
                            release.get("prerelease")?.asBoolean == true
                        ) {
                            null
                        } else {
                            VersionNumber
                                .parse(
                                    release.string("tag_name"),
                                )?.let { it to release }
                        }
                    }.maxByOrNull { it.first }
                    ?.second
            return latest?.let { parseRelease(it) }
        }

        private fun publishUpdate(
            release: LauncherRelease?,
            manual: Boolean,
        ) {
            val available =
                release != null &&
                    requireNotNull(VersionNumber.parse(release.version)) { "发布版本格式无效" } >
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
                        ),
                )
            }
            if (manual || available) {
                store.notify(
                    when {
                        release == null -> "项目尚未发布正式版本"
                        available -> "发现 MoliFox ${release.version}，可在设置 → 通用下载"
                        else -> "当前已是最新版本 $LAUNCHER_VERSION"
                    },
                )
            }
        }

        private fun reportCheckFailure(
            failure: Exception,
            manual: Boolean,
        ) {
            val message = failure.message ?: "GitHub 更新检查失败"
            store.updateState {
                it.copy(update = it.update.copy(checking = false, error = message))
            }
            if (manual) store.notify(message)
            store.log(message, true)
        }

        internal fun parseRelease(json: JsonObject): LauncherRelease {
            val version = json.string("tag_name")
            val page = json.string("html_url")
            require(page.startsWith("${MoliFoxIdentity.RELEASES}/tag/")) { "更新发布页不属于茉狐项目" }
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
                    ?.firstOrNull {
                        it.string("name") == name &&
                            store.platform.architecture == "x64"
                    }?.let {
                        val url = it.string("browser_download_url")
                        requireValidDownloadUrl(url)
                        val digest = it.string("digest").removePrefix("sha256:")
                        LauncherReleaseAsset(
                            name,
                            url,
                            it.get("size")?.asLong ?: 0,
                            digest
                                .takeIf { hash ->
                                    hash.matches(Regex("[a-fA-F0-9]{64}"))
                                }.orEmpty(),
                        )
                    }
            return LauncherRelease(
                version,
                json.string("name").ifBlank { version },
                json.string("body").take(MAX_RELEASE_NOTES_CHARS),
                json.string("published_at"),
                page,
                asset,
            )
        }

        suspend fun download(
            release: LauncherRelease,
            context: JobContext,
        ): Path {
            val asset = requireNotNull(release.asset) { "该版本没有适合此系统架构的更新包，请前往发布页查看" }
            requireValidDownloadUrl(asset.url)
            require(asset.sha256.isNotEmpty()) { "发布包缺少 GitHub SHA-256 摘要，请前往发布页手动下载" }
            require(
                VersionNumber.parse(release.version) != null &&
                    asset.size in 1..(MAX_UPDATE_PACKAGE_BYTES),
            ) {
                "更新包信息无效"
            }
            val directory =
                store.downloadDirectory.resolve("updates").resolve(safeFilename(release.version))
            createStorageDirectory(directory)
            val target = directory.resolve(asset.name)
            context.progress(0f, "下载 MoliFox ${release.version}…")
            downloadReleaseAsset(asset, target, context)
            store.updateState {
                it.copy(
                    update = it.update.copy(downloadedFile = target.toString()),
                )
            }
            store.notify("更新包已校验；退出启动器后替换原文件，保留旁边的 MoliFox 文件夹")
            return target
        }

        private suspend fun downloadReleaseAsset(
            asset: LauncherReleaseAsset,
            target: Path,
            context: JobContext,
        ) {
            val candidates = gitHubDownloadCandidates(asset.url)
            val ordered =
                if (candidates.size >
                    1
                ) {
                    network.rankDownloadSources(candidates)
                } else {
                    candidates
                }
            var lastFailure: IOException? = null
            for (url in ordered) {
                try {
                    network.downloadAll(
                        listOf(DownloadArtifact(url, target, asset.sha256, asset.size, "SHA-256")),
                        1,
                        context,
                        "下载更新包",
                    )
                    return
                } catch (failure: IOException) {
                    lastFailure = failure
                }
            }
            throw IOException("更新包下载失败：${lastFailure?.message}", lastFailure)
        }

        private fun requireValidDownloadUrl(url: String) {
            val uri = URI(url)
            val fixture =
                api.startsWith("http://127.0.0.1:") && uri.host == "127.0.0.1" &&
                    uri.scheme == "http"
            require(
                fixture ||
                    (
                        uri.scheme == "https" &&
                            uri.host == "github.com" &&
                            uri.userInfo == null &&
                            uri.port in setOf(-1, RELEASE_HTTPS_PORT) &&
                            uri.path.startsWith("/Tinmoli/MoliFox/releases/download/")
                    ),
            ) {
                "更新包必须来自茉狐自己的 GitHub Releases"
            }
        }
    }

internal data class VersionNumber(
    val numbers: List<Int>,
    val qualifier: String,
) : Comparable<VersionNumber> {
    override fun compareTo(other: VersionNumber): Int {
        val comparedNumbers =
            numbers.zip(other.numbers).firstNotNullOfOrNull { (left, right) ->
                if (left != right) left.compareTo(right) else null
            }
        return comparedNumbers ?: compareQualifiers(other)
    }

    /** 数字段相同时比较限定符：空的限定符代表正式版，逐段比较数字与文本。 */
    private fun compareQualifiers(other: VersionNumber): Int {
        if (qualifier.isEmpty() != other.qualifier.isEmpty()) {
            return if (qualifier.isEmpty()) 1 else -1
        }
        val left = qualifier.split('.')
        val right = other.qualifier.split('.')
        val compared =
            (0 until minOf(left.size, right.size)).firstNotNullOfOrNull { index ->
                val a = left[index]
                val b = right[index]
                val ai = a.toIntOrNull()
                val bi = b.toIntOrNull()
                val segment =
                    when {
                        ai != null && bi != null -> ai.compareTo(bi)
                        ai != null -> -1
                        bi != null -> 1
                        else -> a.compareTo(b)
                    }
                segment.takeIf { it != 0 }
            }
        return compared ?: left.size.compareTo(right.size)
    }

    companion object {
        fun parse(value: String): VersionNumber? {
            val match =
                Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$")
                    .matchEntire(value) ?: return null
            val numbers = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            return VersionNumber(numbers, match.groupValues[VERSION_QUALIFIER_GROUP])
        }
    }
}
