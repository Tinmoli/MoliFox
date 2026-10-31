// EasyTier sidecar 的版本、平台与官方发布包信息：只描述下载与校验，不联网、不做安装。
package dev.molifox.multiplayer.easytier

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
) {
    /** 解包并剥离 [rootPrefix] 后必须存在的文件；第一个是主程序。 */
    val requiredFiles: List<String>
        get() = listOf(coreFile, cliFile)
}

/**
 * EasyTier 官方发布信息（v2.6.4）。
 *
 * 只使用官方 GitHub Release 链接：不提供第三方镜像，避免把来源不明的二进制装进用户目录。
 * 许可为 LGPL-3.0；分发安装包时需保留上游许可与源码获取方式。
 */
object EasyTierCatalog {
    const val VERSION = "2.6.4"
    const val LICENSE = "LGPL-3.0"

    private const val RELEASE_BASE = "https://github.com/EasyTier/EasyTier/releases/download"
    private const val WINDOWS_X86_64 = "windows-x86_64"
    private const val LINUX_X86_64 = "linux-x86_64"

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
                ),
        )

    /** 指定平台分类的安装包；未知分类返回 null。 */
    fun packageFor(classifier: String): EasyTierPackage? = packages[classifier]

    /** 当前平台的安装包；不支持的平台返回 null。 */
    fun current(): EasyTierPackage? = currentClassifier()?.let(::packageFor)

    /** 不支持当前平台时的统一提示，安装器与界面共用同一措辞。 */
    fun unsupportedPlatformMessage(): String =
        "当前平台（${System.getProperty("os.name").orEmpty()} " +
            "${System.getProperty("os.arch").orEmpty()}）暂不支持 EasyTier 联机；" +
            "仅支持 Windows 与 Linux 的 x86_64。"

    /** 把系统与架构归一化为平台分类；只支持 x86_64 的 Windows / Linux。 */
    fun classify(
        os: String,
        arch: String,
    ): String? {
        val normalizedOs = os.lowercase()
        val normalizedArch = arch.lowercase()
        if (normalizedArch != "x86_64" && normalizedArch != "amd64") return null
        return when {
            normalizedOs.startsWith("windows") -> WINDOWS_X86_64
            normalizedOs.contains("linux") -> LINUX_X86_64
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
