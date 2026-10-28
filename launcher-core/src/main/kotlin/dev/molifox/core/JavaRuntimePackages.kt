// 负责Java 运行时下载包的选择与解包。

package dev.molifox.core

import kotlinx.coroutines.CancellationException

internal data class JavaRuntimePackage(
    val url: String,
    val sha256: String,
    val distribution: String,
)

/** Official vendor metadata and SHA-256 only; prefer the direct CDN to GitHub-hosted binaries. */
internal class JavaRuntimePackages(private val network: NetworkClient) {
    suspend fun find(major: Int, os: String, architecture: String): JavaRuntimePackage {
        try {
            val normalized = normalizedJavaArchitecture(architecture)
            val arch = if (normalized == "arm64") "arm" else "x86"
            val archive = if (os == "windows") "zip" else "tar.gz"
            val suffix =
                if (normalized == "arm64") "aarch64" else if (normalized == "x86") "i686" else "x64"
            val packages =
                network
                    .json(
                        "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$major&os=$os&arch=$arch&java_package_type=jre&javafx_bundled=false&archive_type=$archive&latest=true&release_status=ga&availability_types=CA&certifications=tck&page_size=100"
                    )
                    .asJsonArray
            val candidate =
                packages
                    .map { it.asJsonObject }
                    .firstOrNull {
                        it.string("name").contains("_$suffix.") &&
                            !it.string("name").contains("musl")
                    } ?: error("没有对应平台的 Zulu Java $major")
            val details =
                network
                    .json(
                        "https://api.azul.com/metadata/v1/zulu/packages/${encoded(candidate.string("package_uuid"))}"
                    )
                    .asJsonObject
            require(details.array("java_version").first().asInt == major)
            require(details.int("hw_bitness") == if (normalized == "x86") 32 else 64)
            val url = details.string("download_url")
            val checksum = details.string("sha256_hash")
            require(java.net.URI(url).host == "cdn.azul.com" && java.net.URI(url).scheme == "https")
            require(checksum.matches(Regex("[a-fA-F0-9]{64}")))
            return JavaRuntimePackage(url, checksum, "Zulu")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val packages =
                network
                    .json(
                        "https://api.adoptium.net/v3/assets/latest/$major/hotspot?architecture=$architecture&image_type=jre&os=$os&vendor=eclipse"
                    )
                    .asJsonArray
            require(packages.size() > 0) { "没有找到对应平台的 Java $major，请手动选择已有 Java" }
            val artifact = packages[0].asJsonObject.obj("binary").obj("package")
            val hash = artifact.string("checksum")
            require(hash.matches(Regex("[a-fA-F0-9]{64}")))
            return JavaRuntimePackage(artifact.string("link"), hash, "Temurin")
        }
    }
}
