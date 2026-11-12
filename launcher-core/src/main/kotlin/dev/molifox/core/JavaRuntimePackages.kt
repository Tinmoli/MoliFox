// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏 Java 运行时安装包的选择：从 Azul / Adoptium 查询安装包，自动模式测速排序，手动模式只使用所选厂商。

package dev.molifox.core

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import dev.molifox.core.i18n.I18n
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
        val source = normalizedJavaDownloadSource(downloadSource)
        val target = JavaPackageTarget(major, os, normalizedJavaArchitecture(architecture))
        val types = if (preferredImageType == "jdk") listOf("jdk") else listOf("jre", "jdk")
        val failures = mutableListOf<String>()
        val vendors = vendorsFor(source)
        for (vendor in vendors) {
            findFromVendor(target, vendor, types, failures)?.let { return it }
        }
        throw IllegalStateException(
            I18n.t(
                "networkB2.javaRuntimePackages.notFound",
                os,
                target.architecture,
                major,
                vendors.joinToString("/") { it.replaceFirstChar { c -> c.uppercaseChar() } },
                types.joinToString("/") { it.uppercase() },
                javaDownloadSourceLabel(source),
                failures.joinToString(
                    I18n.t("networkB2.javaRuntimePackages.failureSeparator"),
                ),
            ),
        )
    }

    /** 手动来源只查所选厂商；自动模式按官方接口测速排序并在厂商间回退。 */
    private suspend fun vendorsFor(source: String): List<String> =
        when (source) {
            "azul" -> listOf("zulu")
            "adoptium" -> listOf("temurin")
            "oracle" -> listOf("oracle")
            else -> fastestOfficialVendorOrder()
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
                    when (vendor) {
                        "zulu" -> zulu(major, os, architecture, type)

                        // Oracle 只有 JDK 构建：JRE 请求也按 JDK 返回并保留真实类型（见 JavaRuntimePackage 契约）。
                        "oracle" -> oracle(major, os, architecture)

                        else -> adoptium(major, os, architecture, type)
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
        val title =
            when (vendor) {
                "zulu" -> "Zulu"
                "oracle" -> "Oracle"
                else -> "Temurin"
            }
        failures +=
            I18n.t(
                "networkB2.javaRuntimePackages.failureLine",
                title,
                type.uppercase(),
                error.message ?: I18n.t("networkB2.javaRuntimePackages.queryFailed"),
            )
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
        require(uuid.matches(Regex("[a-fA-F0-9-]{36}"))) {
            I18n.t("networkB2.javaRuntimePackages.zuluInvalidUuid")
        }
        val details =
            network.json("https://api.azul.com/metadata/v1/zulu/packages/$uuid").asJsonObject
        val url = details.string("download_url")
        val checksum = details.string("sha256_hash")
        val size = details.longOrZero("size")
        val bits = details.int("hw_bitness")
        val version = details.array("java_version").joinToString(".") { it.asInt.toString() }
        requireValidZuluDetails(url, checksum, size, bits, arch)
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

    /** Zulu 下载地址与校验和安全门：只接受官方 CDN 的 https 直链与完整元数据。 */
    private fun requireValidZuluDetails(
        url: String,
        checksum: String,
        size: Long,
        bits: Int,
        arch: String,
    ) {
        require(URI(url).scheme == "https" && URI(url).host == "cdn.azul.com") {
            I18n.t("networkB2.javaRuntimePackages.zuluNotOfficialCdn", URI(url).host)
        }
        require(URI(url).rawPath.startsWith("/zulu/bin/")) {
            I18n.t("networkB2.javaRuntimePackages.zuluUnexpectedPath")
        }
        require(checksum.matches(Regex("[a-fA-F0-9]{64}"))) {
            I18n.t("networkB2.javaRuntimePackages.zuluMissingSha256")
        }
        require(size > 0) { I18n.t("networkB2.javaRuntimePackages.zuluMissingSize") }
        require(bits == if (arch == "x86") ZULU_X86_BITS else ZULU_X64_BITS) {
            I18n.t("networkB2.javaRuntimePackages.zuluBitsMismatch")
        }
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
            I18n.t("networkB2.javaRuntimePackages.temurinHostNotAllowed", URI(url).host)
        }
        require(checksum.matches(Regex("[a-fA-F0-9]{64}"))) {
            I18n.t("networkB2.javaRuntimePackages.temurinMissingSha256")
        }
        require(size > 0) { I18n.t("networkB2.javaRuntimePackages.temurinMissingSize") }
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

    /**
     * Oracle 官方直链。latest 路径只覆盖 25 / 27 两个 NFTC 许可版本（更早版本的 latest 已 404，
     * archive 需要登录接受 OTN 许可），且只有 JDK、没有 Windows ARM64 / x86 构建，这些组合直接返回 null。
     * 直链没有元数据 API：SHA-256 取自官方伴随的 `.sha256` 文件，大小未知按 0 处理（磁盘预检退化为余量判断，
     * 完整性仍由 SHA-256 兜底）。
     */
    private suspend fun oracle(
        major: Int,
        os: String,
        arch: String,
    ): JavaRuntimePackage? {
        if (major !in ORACLE_NFTC_MAJORS || arch == "x86") return null
        if (os == "windows" && arch != "x64") return null
        val oracleOs = if (os == "osx") "macos" else os
        val archive = if (oracleOs == "windows") "zip" else "tar.gz"
        // 官方直链命名：版本号后是下划线（jdk-25_windows-x64），平台与架构之间是连字符。
        val fileName = "jdk-${major}_$oracleOs-${arch}_bin.$archive"
        val url = "https://download.oracle.com/java/$major/latest/$fileName"
        requireOracleUrl(url, major, fileName)
        val checksum = network.text("$url.sha256").trim().lowercase()
        require(checksum.matches(Regex("[a-f0-9]{64}"))) {
            I18n.t("networkB2.javaRuntimePackages.oracleMissingSha256")
        }
        return JavaRuntimePackage(
            urls = listOf(url),
            sha256 = checksum,
            size = 0,
            vendor = "Oracle",
            // Oracle 不再发布单独 JRE：一律按 JDK 处理并保留真实类型。
            imageType = "jdk",
            version = major.toString(),
            fileName = fileName,
            sourceUrl = url,
            sourceLabel = "Oracle",
        )
    }

    /** Oracle 下载地址门：只接受官方 download.oracle.com 的 https latest 直链。 */
    private fun requireOracleUrl(
        url: String,
        major: Int,
        fileName: String,
    ) {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "download.oracle.com") {
            I18n.t("networkB2.javaRuntimePackages.oracleHostNotAllowed", uri.host)
        }
        require(uri.rawPath == "/java/$major/latest/$fileName") {
            I18n.t("networkB2.javaRuntimePackages.oracleUnexpectedPath")
        }
    }

    private companion object {
        /** 只用于测延迟：两个官方元数据接口，各取极小响应。 */
        const val ZULU_METADATA_PROBE =
            "https://api.azul.com/metadata/v1/zulu/packages/?java_version=21&os=windows" +
                "&arch=x64&archive_type=zip&java_package_type=jre&javafx_bundled=false" +
                "&release_status=ga&availability_types=CA&certifications=tck" +
                "&page_size=1&latest=true"

        const val ADOPTIUM_METADATA_PROBE = "https://api.adoptium.net/v3/info/available_releases"

        /** Oracle latest 直链只覆盖这两个 NFTC 版本；21/17 的 latest 已下线（2026-11-07 实测 404）。 */
        val ORACLE_NFTC_MAJORS = setOf(25, 27)
    }
}

/** 进度与设置使用同一套提供商名称。 */
fun javaDownloadSourceLabel(value: String): String =
    when (normalizedJavaDownloadSource(value)) {
        "azul" -> "Azul"
        "adoptium" -> "Adoptium"
        "oracle" -> "Oracle"
        else -> I18n.t("networkB2.javaRuntimePackages.downloadSourceAuto")
    }

/**
 * 设置里可见的下载来源：对当前系统/架构没有任何安装包的来源不显示，避免选了就必然失败。
 * 覆盖情况按 2026-11-07 官方清单核验：Azul 与 Adoptium 覆盖全部桌面平台（部分旧版本除外），
 * Oracle 只有 Windows x64 / Linux x64+aarch64 / macOS x64+aarch64 的 JDK，x86 与 Windows ARM64 没有 Oracle 包。
 */
fun availableJavaDownloadSources(
    os: String,
    arch: String,
): List<String> =
    buildList {
        add("auto")
        add("azul")
        add("adoptium")
        if (arch != "x86" && (os != "windows" || arch == "x64")) add("oracle")
    }

private fun com.google.gson.JsonObject.longOrZero(key: String): Long =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asLong ?: 0L }.getOrDefault(0L)

private data class JavaPackageTarget(
    val major: Int,
    val os: String,
    val architecture: String,
)
