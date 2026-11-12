// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责读取茉狐的 GitHub Releases、比较版本并校验下载的更新包。

package dev.molifox.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path

private const val MAX_RELEASE_NOTES_CHARS = 20_000
private const val MAX_UPDATE_PACKAGE_BYTES = 512L * 1024 * 1024
private const val RELEASE_HTTPS_PORT = 443
private const val VERSION_QUALIFIER_GROUP = 4
private const val UPDATE_DOWNLOAD_PROGRESS = 0.95f

data class LauncherUpdateState(
    val checking: Boolean = false,
    val checked: Boolean = false,
    val release: LauncherRelease? = null,
    val available: Boolean = false,
    val error: String? = null,
    val downloadedFile: String? = null,
    val promptSerial: Long = 0,
)

data class LauncherRelease(
    val version: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val pageUrl: String,
    val asset: LauncherReleaseAsset? = null,
    val prerelease: Boolean = false,
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
        private val jarMode: Boolean = LauncherBundlePath.currentJar() != null,
    ) {
        private val checkLock = Mutex()

        suspend fun check(manual: Boolean = true) {
            if (!checkLock.tryLock()) {
                if (manual) {
                    // 手动检查复用正在进行的请求结果，避免丢弃点击或重复请求。
                    checkLock.withLock {
                        val update = store.state.value.update
                        if (update.error != null) {
                            store.notify(update.error)
                        } else if (update.checked) {
                            store.notify(update.release.updateNotification(update.available))
                        }
                    }
                }
                return
            }
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
            val releases =
                network.gitHubReleaseList(
                    "$api/releases?per_page=100",
                    store.data.settings.gitHubSourceOptions(),
                ) { list ->
                    selectRelease(list)?.let { isNewer(it.version) } == true
                }
            return selectRelease(releases)
        }

        private fun selectRelease(releases: JsonArray): LauncherRelease? {
            val latest =
                releases
                    .mapNotNull { item ->
                        val release = item.asJsonObject
                        if (
                            release.get("draft")?.asBoolean == true ||
                            (!store.data.settings.allowBetaUpdates && isPrereleaseRelease(release))
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
            val available = release != null && isNewer(release.version)
            store.updateState {
                it.copy(
                    update =
                        LauncherUpdateState(
                            checked = true,
                            release = release,
                            available = available,
                            promptSerial = it.update.promptSerial + 1,
                            downloadedFile =
                                it.update.downloadedFile.takeIf { _ ->
                                    it.update.release == release
                                },
                        ),
                )
            }
            if (manual || available) store.notify(release.updateNotification(available))
        }

        private fun isNewer(version: String): Boolean =
            requireNotNull(VersionNumber.parse(version)) { I18n.t("core.gitHubUpdater.1") } >
                (VersionNumber.parse(LAUNCHER_VERSION) ?: error(I18n.t("core.gitHubUpdater.2")))

        private fun reportCheckFailure(
            failure: Exception,
            manual: Boolean,
        ) {
            val message = failure.message ?: I18n.t("core.gitHubUpdater.6")
            store.updateState {
                it.copy(update = it.update.copy(checking = false, error = message))
            }
            if (manual) store.notify(message)
            store.log(message, true)
        }

        internal fun parseRelease(json: JsonObject): LauncherRelease {
            val version = json.string("tag_name")
            val page = json.string("html_url")
            require(page.startsWith("${MoliFoxIdentity.RELEASES}/tag/")) {
                I18n.t("networkB1.gitHubUpdater.invalidReleasePage")
            }
            // 新 release 只发固定名；旧包可能还在同名回退上，所以把历史名也留在候选里。
            // JAR 模式只用通用 JAR，不带架构后缀；原生包继续按平台/架构区分。
            val arm64 = store.platform.architecture == "arm64"
            val archTag = if (arm64) "arm64" else "x86_64"
            val normalizedVersion = version.removePrefix("v")
            val (versioned, fixedNames) =
                if (jarMode) {
                    "MoliFox-$normalizedVersion.jar" to listOf("MoliFox.jar")
                } else {
                    val versionedName =
                        when (store.platform.osName) {
                            "windows" -> "MoliFox-$normalizedVersion-windows-$archTag.exe"
                            "linux" -> "MoliFox-$normalizedVersion-linux-$archTag.run"
                            else -> ""
                        }
                    val fixed =
                        when (store.platform.osName) {
                            "windows" -> listOf("MoliFox.exe")
                            "linux" -> listOf("MoliFox.run", "MoliFox-linux-x64.run", "MoliFox-linux-arm64.run")
                            else -> emptyList()
                        }
                    versionedName to fixed
                }
            val asset = releaseAssetOf(json, listOf(versioned) + fixedNames)
            return LauncherRelease(
                version,
                json.string("name").ifBlank { version },
                json.string("body").take(MAX_RELEASE_NOTES_CHARS),
                json.string("published_at"),
                page,
                asset,
                json.get("prerelease")?.asBoolean == true ||
                    VersionNumber.parse(version)?.qualifier?.isNotEmpty() == true,
            )
        }

        /** 从 release 的 assets 里挑出当前平台文件；找不到返回 null，允许无附件发布。 */
        private fun releaseAssetOf(
            json: JsonObject,
            names: List<String>,
        ): LauncherReleaseAsset? =
            json
                .getAsJsonArray("assets")
                ?.map { it.asJsonObject }
                ?.filter { it.string("name") in names }
                ?.minByOrNull { names.indexOf(it.string("name")) }
                ?.let {
                    val url = it.string("browser_download_url")
                    requireValidDownloadUrl(url)
                    val digest = it.string("digest").removePrefix("sha256:")
                    LauncherReleaseAsset(
                        it.string("name"),
                        url,
                        it.get("size")?.asLong ?: 0,
                        digest
                            .takeIf { hash ->
                                hash.matches(Regex("[a-fA-F0-9]{64}"))
                            }.orEmpty(),
                    )
                }

        suspend fun download(
            release: LauncherRelease,
            context: JobContext,
        ): Path {
            val asset = requireNotNull(release.asset) { I18n.t("core.gitHubUpdater.7") }
            require(isNewer(release.version)) { I18n.t("settings.update.latest") }
            requireValidDownloadUrl(asset.url)
            require(asset.sha256.isNotEmpty()) { I18n.t("core.gitHubUpdater.8") }
            require(
                VersionNumber.parse(release.version) != null &&
                    asset.size in 1..(MAX_UPDATE_PACKAGE_BYTES),
            ) {
                I18n.t("core.gitHubUpdater.9")
            }
            val directory =
                store.downloadDirectory.resolve("updates").resolve(safeFilename(release.version))
            createStorageDirectory(directory)
            val target = directory.resolve(asset.name)
            context.progressImmediate(0f, I18n.t("core.gitHubUpdater.10", release.version))
            downloadReleaseAsset(asset, target, context)
            context.progressImmediate(UPDATE_DOWNLOAD_PROGRESS, I18n.t("core.gitHubUpdater.11"))
            store.updateState {
                it.copy(
                    update = it.update.copy(downloadedFile = target.toString()),
                )
            }
            store.notify(I18n.t("core.gitHubUpdater.11"))
            return target
        }

        private suspend fun downloadReleaseAsset(
            asset: LauncherReleaseAsset,
            target: Path,
            context: JobContext,
        ) {
            val candidates = gitHubDownloadCandidates(asset.url, store.data.settings.gitHubSourceOptions())
            val ordered =
                if (candidates.size >
                    1
                ) {
                    network.rankDownloadSources(candidates)
                } else {
                    candidates
                }
            try {
                network.downloadTracked(
                    context,
                    DownloadArtifact(asset.url, target, asset.sha256, asset.size, "SHA-256"),
                    ordered,
                ) { done, _ ->
                    context.progress(
                        (done.toDouble() / asset.size).coerceIn(0.0, 1.0).toFloat() * UPDATE_DOWNLOAD_PROGRESS,
                        I18n.t("core.gitHubUpdater.12"),
                    )
                }
            } catch (failure: IOException) {
                throw IOException(I18n.t("core.gitHubUpdater.13", failure.message), failure)
            }
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
                I18n.t("core.gitHubUpdater.14")
            }
        }
    }

private fun isPrereleaseRelease(release: JsonObject): Boolean {
    val preview =
        release.get("prerelease")?.asBoolean == true ||
            VersionNumber.parse(release.string("tag_name"))?.qualifier?.isNotEmpty() == true
    return preview
}

private fun LauncherRelease?.updateNotification(available: Boolean): String {
    val message =
        when {
            this == null -> I18n.t("core.gitHubUpdater.3")
            available -> I18n.t("core.gitHubUpdater.4", version)
            else -> I18n.t("core.gitHubUpdater.5", LAUNCHER_VERSION)
        }
    return message
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
