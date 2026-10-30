// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏 Java 运行时安装包的选择：从 Azul / Adoptium 查询安装包，自动模式测速排序，手动模式只使用所选厂商。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

private const val ZULU_X86_BITS = 32
private const val ZULU_X64_BITS = 64

/**
 * 一个可安装的运行时包。[urls] 已按尝试顺序排好（第一个优先），每个地址都是同一份文件、 同一个 sha256；[size] 来自厂商
 * API，用作下载后的大小校验。
 */
internal data class JavaRuntimePackage(
    val urls: List<String>,
    val sha256: String,
    val size: Long,
    /** 展示用厂商：Zulu / Temurin。 */
    val vendor: String,
    /** jre / jdk；API 没有 JRE 构建时回退 JDK 并保留真实类型。 */
    val imageType: String,
    val version: String,
    val fileName: String,
    val sourceUrl: String,
    /** 实际解析出的来源（用于安装进度行）：官方源（Azul）／官方源（Temurin）。 */
    val sourceLabel: String = "",
) {
    val primaryUrl: String
        get() = urls.first()

    /** 兼容旧调用方（测试/脚本）的字段名。 */
    val url: String
        get() = primaryUrl

    /** 目录前缀：zulu / temurin；实际目录由安装器拼成 `<供应商>-<主版本>-<os>-<arch>`。 */
    val directoryPrefix: String
        get() = if (vendor.equals("Zulu", true)) "zulu" else "temurin"
}

internal class JavaRuntimePackages(
    private val network: NetworkClient,
) {
    /**
     * 选择安装包。[preferredImageType] 为 "jre" 时优先 JRE、没有该版本 JRE 时回退 JDK 并保留真实类型；
     * 为 "jdk" 时只查 JDK；为空按 JRE → JDK 自动。
     *
     * 自动模式按官方接口测速排序并在厂商间回退；手动选择时只查询所选厂商。
     */
    suspend fun find(
        major: Int,
        os: String,
        architecture: String,
        preferredImageType: String? = null,
        downloadSource: String = "auto",
    ): JavaRuntimePackage {
        val normalized = normalizedJavaArchitecture(architecture)
        val zuluArch =
            when (normalized) {
                "arm64" -> "aarch64"
                "x86" -> "x86"
                else -> "x64"
            }
        val types =
            when (preferredImageType) {
                "jdk" -> listOf("jdk")
                else -> listOf("jre", "jdk")
            }
        val failures = mutableListOf<String>()
        val source = normalizedJavaDownloadSource(downloadSource)
        val vendors =
            when (source) {
                "azul" -> listOf("zulu")
                "adoptium" -> listOf("temurin")
                else -> fastestOfficialVendorOrder()
            }
        val target = JavaPackageTarget(major, os, zuluArch)
        for (vendor in vendors) {
            findFromVendor(target, vendor, types, failures)?.let { return it }
        }
        throw IllegalStateException(
            "没有找到适用于 $os/$zuluArch 的 Java $major 构建（已尝试 " +
                vendors.joinToString("/") { it.replaceFirstChar { c -> c.uppercaseChar() } } +
                " 的 " +
                types.joinToString("/") { it.uppercase() } +
                "，来源：" +
                javaDownloadSourceLabel(source) +
                "）：" +
                failures.joinToString("；") +
                "。请手动选择已有的 Java",
        )
    }

    private suspend fun findFromVendor(
        target: JavaPackageTarget,
        vendor: String,
        types: List<String>,
        failures: MutableList<String>,
    ): JavaRuntimePackage? {
        val (major, os, architecture) = target
        for (type in types) {
            try {
                val packageInfo =
                    if (vendor == "zulu") {
                        zulu(major, os, architecture, type)
                    } else {
                        adoptium(major, os, architecture, type)
                    }
                packageInfo?.let { return it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                recordPackageFailure(error, vendor, type, failures)
            } catch (error: JsonParseException) {
                recordPackageFailure(error, vendor, type, failures)
            } catch (error: IllegalArgumentException) {
                recordPackageFailure(error, vendor, type, failures)
            } catch (error: IllegalStateException) {
                recordPackageFailure(error, vendor, type, failures)
            } catch (error: URISyntaxException) {
                recordPackageFailure(error, vendor, type, failures)
            }
        }
        return null
    }

    private fun recordPackageFailure(
        error: Exception,
        vendor: String,
        type: String,
        failures: MutableList<String>,
    ) {
        val title = if (vendor == "zulu") "Zulu" else "Temurin"
        failures += "$title ${type.uppercase()}：${error.message ?: "查询失败"}"
    }

    private fun selectZuluCandidate(
        packages: List<JsonObject>,
        zuluOs: String,
        arch: String,
        imageType: String,
    ): JsonObject? {
        // Azul 的文件名形如 zulu21.54.107-ca-jre21.0.13-win_x64.zip：平台段用 '-' 与前缀分隔。
        val platformTag =
            when (zuluOs) {
                "windows" -> "win"
                "linux" -> "linux"
                "macos" -> "macosx"
                else -> zuluOs
            }
        return packages.firstOrNull { packageJson ->
            val name = packageJson.string("name").lowercase()
            name.contains("-$imageType") &&
                !name.contains("-fx-") &&
                !name.contains("-musl") &&
                !name.contains("_musl") &&
                !name.contains("-crac-") &&
                name.contains("-$platformTag" + "_$arch.")
        }
    }

    /**
     * 自动模式：只对两个官方元数据接口测一次延迟，把更快的厂商排在前面。测速失败时保持
     * Azul → Adoptium 的默认顺序，不把「测不到延迟」当成「源不可用」。
     */
    private suspend fun fastestOfficialVendorOrder(): List<String> {
        val order =
            try {
                network.fastestFirst(listOf(ZULU_METADATA_PROBE, ADOPTIUM_METADATA_PROBE))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                listOf(ZULU_METADATA_PROBE, ADOPTIUM_METADATA_PROBE)
            }
        val vendors =
            order.map { if (it == ZULU_METADATA_PROBE) "zulu" else "temurin" }.distinct()
        return if (vendors.size == 2) vendors else listOf("zulu", "temurin")
    }

    /** Zulu 元数据：JRE/JDK 分开查询；没有该类型构建时返回 null，网络错误则抛出交给上层回退。 */
    private suspend fun zulu(
        major: Int,
        os: String,
        arch: String,
        imageType: String,
    ): JavaRuntimePackage? {
        val zuluOs = if (os == "osx") "macos" else os
        val archive = if (zuluOs == "windows") "zip" else "tar.gz"
        val query =
            "https://api.azul.com/metadata/v1/zulu/packages/?java_version=$major" +
                "&os=$zuluOs&arch=$arch&archive_type=$archive&java_package_type=$imageType" +
                "&javafx_bundled=false&release_status=ga&availability_types=CA" +
                "&certifications=tck&page_size=100&latest=true"
        val packages = network.json(query).asJsonArray.map { it.asJsonObject }
        val candidate = selectZuluCandidate(packages, zuluOs, arch, imageType) ?: return null
        val uuid = candidate.string("package_uuid")
        require(uuid.matches(Regex("[a-fA-F0-9-]{36}"))) { "Zulu 返回的包标识无效" }
        val details =
            network.json("https://api.azul.com/metadata/v1/zulu/packages/$uuid").asJsonObject
        val url = details.string("download_url")
        val checksum = details.string("sha256_hash")
        val size = details.longOrZero("size")
        val bits = details.int("hw_bitness")
        val version = details.array("java_version").joinToString(".") { it.asInt.toString() }
        require(URI(url).scheme == "https" && URI(url).host == "cdn.azul.com") {
            "Zulu 下载地址不是官方 CDN：${URI(url).host}"
        }
        require(URI(url).rawPath.startsWith("/zulu/bin/")) { "Zulu 下载地址路径异常" }
        require(checksum.matches(Regex("[a-fA-F0-9]{64}"))) { "Zulu 未提供有效的 SHA-256" }
        require(size > 0) { "Zulu 未提供安装包大小" }
        require(bits == if (arch == "x86") ZULU_X86_BITS else ZULU_X64_BITS) { "Zulu 安装包位数与平台不符" }
        return JavaRuntimePackage(
            urls = listOf(url),
            sha256 = checksum,
            size = size,
            vendor = "Zulu",
            imageType = details.string("java_package_type", imageType),
            version = version,
            fileName = details.string("name"),
            sourceUrl = url,
            sourceLabel = "Azul",
        )
    }

    /** 从 Adoptium 官方 API 查询 Temurin 构建。 */
    private suspend fun adoptium(
        major: Int,
        os: String,
        arch: String,
        imageType: String,
    ): JavaRuntimePackage? {
        val adoptiumOs = if (os == "osx") "mac" else os
        val query =
            "https://api.adoptium.net/v3/assets/latest/$major/hotspot" +
                "?architecture=$arch&image_type=$imageType&os=$adoptiumOs&vendor=eclipse"
        val packages = network.json(query).asJsonArray
        if (packages.size() == 0) return null
        val binary = packages[0].asJsonObject.obj("binary")
        val artifact = binary.obj("package")
        val url = artifact.string("link")
        val checksum = artifact.string("checksum")
        val size = artifact.longOrZero("size")
        val name = artifact.string("name")
        val version = binary.obj("version").string("semver").substringBefore('+')
        require(
            URI(url).scheme == "https" &&
                URI(url).host in
                setOf("github.com", "objects.githubusercontent.com", "api.adoptium.net"),
        ) {
            "Temurin 下载地址不在允许的域名：${URI(url).host}"
        }
        require(checksum.matches(Regex("[a-fA-F0-9]{64}"))) { "Temurin 未提供有效的 SHA-256" }
        require(size > 0) { "Temurin 未提供安装包大小" }
        return JavaRuntimePackage(
            urls = listOf(url),
            sha256 = checksum,
            size = size,
            vendor = "Temurin",
            imageType = imageType,
            version = version,
            fileName = name,
            sourceUrl = url,
            sourceLabel = "Adoptium",
        )
    }

    private companion object {
        /** 只用于测延迟：两个官方元数据接口，各取极小响应。 */
        const val ZULU_METADATA_PROBE =
            "https://api.azul.com/metadata/v1/zulu/packages/?java_version=21&os=windows" +
                "&arch=x64&archive_type=zip&java_package_type=jre&javafx_bundled=false" +
                "&release_status=ga&availability_types=CA&certifications=tck" +
                "&page_size=1&latest=true"

        const val ADOPTIUM_METADATA_PROBE = "https://api.adoptium.net/v3/info/available_releases"
    }
}

/** 进度与设置使用同一套提供商名称。 */
fun javaDownloadSourceLabel(value: String): String =
    when (normalizedJavaDownloadSource(value)) {
        "azul" -> "Azul"
        "adoptium" -> "Adoptium"
        else -> "自动"
    }

/** 下载进度按当前真正尝试的地址显示来源；只剩 Azul / Temurin 两个官方主机。 */
internal fun javaDownloadUrlLabel(url: String): String =
    when (runCatching { URI(url).host.orEmpty() }.getOrDefault("")) {
        "cdn.azul.com",
        "api.azul.com",
        -> "Azul"
        "github.com",
        "objects.githubusercontent.com",
        "api.adoptium.net",
        -> "Adoptium"
        else -> "官方源"
    }

private fun com.google.gson.JsonObject.longOrZero(key: String): Long =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0L }.getOrDefault(0L)

private data class JavaPackageTarget(
    val major: Int,
    val os: String,
    val architecture: String,
)
