// EasyTier sidecar 的版本、平台与官方发布包信息：只描述下载与校验，不联网、不做安装。
package dev.molifox.multiplayer.easytier

import dev.molifox.core.gitHubDownloadCandidates
import dev.molifox.core.i18n.I18n

private val ARM64_ARCHITECTURES = setOf("aarch64", "arm64")
private val X64_ARCHITECTURES = setOf("x86_64", "amd64")

/** 一个平台的 EasyTier 官方发布包；[rootPrefix] 是压缩包内文件的公共目录前缀。 */
data class EasyTierPackage(
    val version: String,
    val classifier: String,
    val url: String,
    val sha256: String,
    val downloadBytes: Long,
    val rootPrefix: String,
    val coreFile: String,
    val cliFile: String,
    /** 与官方文件完全相同的镜像地址；下载器会先测速再决定顺序。 */
    val mirrors: List<String> = emptyList(),
    /**
     * 主程序之外必须随包分发的官方文件；Windows 的 easytier-core 静态导入
     * Packet.dll（缺它启动即退出 0xC0000135 且零输出），wintun.dll 供非 no-tun 模式使用。
     */
    val extraFiles: List<String> = emptyList(),
) {
    /** 解包并剥离 [rootPrefix] 后必须存在的文件；第一个是主程序。 */
    val requiredFiles: List<String>
        get() = listOf(coreFile, cliFile) + extraFiles
}

/**
 * EasyTier 官方发布信息（v2.6.4）。
 *
 * 主地址始终是官方 GitHub Release；镜像只是对同一文件的加速代理（不重新打包），
 * 下载后仍按官方 SHA-256 校验，因此镜像损坏或内容不符时会被拒绝。
 * 许可为 LGPL-3.0；分发安装包时需保留上游许可与源码获取方式。
 */
object EasyTierCatalog {
    const val VERSION = "2.6.4"
    const val LICENSE = "LGPL-3.0"

    private const val RELEASE_BASE = "https://github.com/EasyTier/EasyTier/releases/download"
    private const val WINDOWS_X86_64 = "windows-x86_64"
    private const val WINDOWS_ARM64 = "windows-arm64"
    private const val LINUX_X86_64 = "linux-x86_64"
    private const val LINUX_ARM64 = "linux-arm64"
    private const val MACOS_X86_64 = "macos-x86_64"
    private const val MACOS_ARM64 = "macos-arm64"

    /**
     * GitHub Release 加速代理；两者都实测返回官方文件的完整内容（Content-Range 与官方大小一致）。
     * 用户网络环境不同，下载器会测速后选择最快的一个，失败再换下一个。
     */
    private fun mirrorsOf(officialUrl: String): List<String> =
        gitHubDownloadCandidates(officialUrl).filterNot { it == officialUrl }

    private val packages =
        mapOf(
            WINDOWS_X86_64 to
                EasyTierPackage(
                    version = VERSION,
                    classifier = WINDOWS_X86_64,
                    url = "$RELEASE_BASE/v$VERSION/easytier-$WINDOWS_X86_64-v$VERSION.zip",
                    sha256 = "27af91e270e554709b048bd32327fefd2dfce5062ae1e8701af7550c6f525f84",
                    downloadBytes = 32_657_720L,
                    rootPrefix = "easytier-$WINDOWS_X86_64",
                    coreFile = "easytier-core.exe",
                    cliFile = "easytier-cli.exe",
                    extraFiles = listOf("Packet.dll", "wintun.dll"),
                    mirrors =
                        mirrorsOf(
                            "$RELEASE_BASE/v$VERSION/easytier-$WINDOWS_X86_64-v$VERSION.zip",
                        ),
                ),
            WINDOWS_ARM64 to
                EasyTierPackage(
                    version = VERSION,
                    classifier = WINDOWS_ARM64,
                    url = "$RELEASE_BASE/v$VERSION/easytier-$WINDOWS_ARM64-v$VERSION.zip",
                    sha256 = "37023f8a3451c9234b17ee2089a03dc344ce90d803b5b359cb6c46682b0549b4",
                    downloadBytes = 30_136_892L,
                    rootPrefix = "easytier-$WINDOWS_ARM64",
                    coreFile = "easytier-core.exe",
                    cliFile = "easytier-cli.exe",
                    extraFiles = listOf("Packet.dll", "wintun.dll"),
                    mirrors =
                        mirrorsOf(
                            "$RELEASE_BASE/v$VERSION/easytier-$WINDOWS_ARM64-v$VERSION.zip",
                        ),
                ),
            LINUX_X86_64 to
                EasyTierPackage(
                    version = VERSION,
                    classifier = LINUX_X86_64,
                    url = "$RELEASE_BASE/v$VERSION/easytier-$LINUX_X86_64-v$VERSION.zip",
                    sha256 = "61b659eaedba658fa66fe47d17e1426cdd77e5d02fa15fed447bb4357c09dfd6",
                    downloadBytes = 25_506_523L,
                    rootPrefix = "easytier-$LINUX_X86_64",
                    coreFile = "easytier-core",
                    cliFile = "easytier-cli",
                    mirrors =
                        mirrorsOf(
                            "$RELEASE_BASE/v$VERSION/easytier-$LINUX_X86_64-v$VERSION.zip",
                        ),
                ),
            LINUX_ARM64 to
                EasyTierPackage(
                    version = VERSION,
                    classifier = LINUX_ARM64,
                    // 官方文件名是 aarch64，分类统一用 arm64，避免界面与清单出现两套叫法。
                    url = "$RELEASE_BASE/v$VERSION/easytier-linux-aarch64-v$VERSION.zip",
                    sha256 = "f533ec25a7ea714e09f645615012200278058525795cc3bb690ff011aec1a70f",
                    downloadBytes = 20_487_230L,
                    rootPrefix = "easytier-linux-aarch64",
                    coreFile = "easytier-core",
                    cliFile = "easytier-cli",
                    mirrors =
                        mirrorsOf(
                            "$RELEASE_BASE/v$VERSION/easytier-linux-aarch64-v$VERSION.zip",
                        ),
                ),
            MACOS_X86_64 to
                EasyTierPackage(
                    version = VERSION,
                    classifier = MACOS_X86_64,
                    url = "$RELEASE_BASE/v$VERSION/easytier-$MACOS_X86_64-v$VERSION.zip",
                    sha256 = "89fc28a6e6995259d76ce3f11775220e8a21c760e94df91a6a9db30a69b6982e",
                    downloadBytes = 33_733_545L,
                    rootPrefix = "easytier-$MACOS_X86_64",
                    coreFile = "easytier-core",
                    cliFile = "easytier-cli",
                    mirrors =
                        mirrorsOf(
                            "$RELEASE_BASE/v$VERSION/easytier-$MACOS_X86_64-v$VERSION.zip",
                        ),
                ),
            MACOS_ARM64 to
                EasyTierPackage(
                    version = VERSION,
                    classifier = MACOS_ARM64,
                    url = "$RELEASE_BASE/v$VERSION/easytier-macos-aarch64-v$VERSION.zip",
                    sha256 = "4be1882d1aa36d31c1d6ba0596f2cf8a097e371f8da124212324b2e0f8df7e4b",
                    downloadBytes = 30_174_017L,
                    rootPrefix = "easytier-macos-aarch64",
                    coreFile = "easytier-core",
                    cliFile = "easytier-cli",
                    mirrors =
                        mirrorsOf(
                            "$RELEASE_BASE/v$VERSION/easytier-macos-aarch64-v$VERSION.zip",
                        ),
                ),
        )

    /** 指定平台分类的安装包；未知分类返回 null。 */
    fun packageFor(classifier: String): EasyTierPackage? = packages[classifier]

    /** 当前平台的安装包；不支持的平台返回 null。 */
    fun current(): EasyTierPackage? = currentClassifier()?.let(::packageFor)

    /** 不支持当前平台时的统一提示，安装器与界面共用同一措辞。 */
    fun unsupportedPlatformMessage(): String =
        I18n.t(
            "multiplayer.easyTierCatalog.unsupportedPlatform",
            System.getProperty("os.name").orEmpty(),
            System.getProperty("os.arch").orEmpty(),
        )

    /** 把系统与架构归一化为平台分类；Windows / Linux / macOS 的 x64 与 ARM64 都支持。 */
    fun classify(
        os: String,
        arch: String,
    ): String? {
        val normalizedOs = os.lowercase()
        val normalizedArch = arch.lowercase()
        val arm64 = normalizedArch in ARM64_ARCHITECTURES
        if (!arm64 && normalizedArch !in X64_ARCHITECTURES) return null
        return when {
            normalizedOs.startsWith("windows") ->
                if (arm64) WINDOWS_ARM64 else WINDOWS_X86_64

            normalizedOs.contains("linux") ->
                if (arm64) LINUX_ARM64 else LINUX_X86_64

            normalizedOs.startsWith("mac") || normalizedOs.contains("darwin") ->
                if (arm64) MACOS_ARM64 else MACOS_X86_64

            else -> null
        }
    }

    /** 运行环境对应的平台分类；不支持的平台返回 null。 */
    fun currentClassifier(): String? {
        val os = System.getProperty("os.name").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()
        return classify(os, arch)
    }
}
