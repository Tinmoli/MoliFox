// 陶瓦（Terracotta）联机核心的版本、平台与校验信息：与 HMCL 内置清单一致，不联网也能确定下载地址。
package dev.molifox.multiplayer.data

/** 一个平台上的联机核心安装包；[files] 是压缩包内文件及其 SHA-512。 */
data class TerracottaPackage(
    val version: String,
    val classifier: String,
    val archiveSha512: String,
    val downloadBytes: Long,
    val files: Map<String, String>,
) {
    val executable: String
        get() =
            if (classifier.startsWith("windows")) {
                "terracotta-$version-$classifier.exe"
            } else {
                "terracotta-$version-$classifier"
            }
}

object TerracottaCatalog {
    const val VERSION = "0.4.2"

    private val packages =
        mapOf(
            "windows-x86_64" to
                TerracottaPackage(
                    version = VERSION,
                    classifier = "windows-x86_64",
                    archiveSha512 =
                        "6a98f524d4f00373696517306af8aa50d01d55ce4eadb27e9e4bc2f882707a0b" +
                            "5f20d5d4c33371d1459dcf5bf144ffed9beb414202d9ccf32b11dbbfcf19d650",
                    downloadBytes = 8_135_914L,
                    files =
                        mapOf(
                            "VCRUNTIME140.DLL" to
                                "3d4b24061f72c0e957c7b04a0c4098c94c8f1afb4a7e159850b9939c7210d733" +
                                "98be6f27b5ab85073b4e8c999816e7804fef0f6115c39cd061f4aaeb4dcda8cf",
                            "terracotta-$VERSION-windows-x86_64.exe" to
                                "6e98d1f2380ed22fb5a2dd4aafce6c773e9cf69100c8bb8e49e7d6983756bdb9" +
                                "a31f80e06bcfbe5a2742144fe806d3d687dec54d8f09d87c659341f99dd9fd80",
                        ),
                ),
            "linux-x86_64" to
                TerracottaPackage(
                    version = VERSION,
                    classifier = "linux-x86_64",
                    archiveSha512 =
                        "d326ad95815d04568d485b5038e40ffc47ca54292fa0925eee6f5cea014024f9" +
                            "01d661708aac2a743037b990882ad82b4d0b7bb03dc3b2fe720dbf0f3efe1c98",
                    downloadBytes = 10_165_199L,
                    files =
                        mapOf(
                            "terracotta-$VERSION-linux-x86_64" to
                                "fac328ba8957a711b03557bb913940f22d61b76608cd203fdf51024b6f94b19f" +
                                "5bc91c9b8a9fa80baf6968e1e6873c1880fd4cf54a2f8e3c6cf1e6ac161f8d0c",
                        ),
                ),
        )

    /** 当前系统与架构对应的安装包；不支持的平台返回 null。 */
    fun current(): TerracottaPackage? = packages[currentClassifier()]

    fun classify(
        os: String,
        arch: String,
    ): String? {
        val normalizedOs = os.lowercase()
        val normalizedArch = arch.lowercase()
        if (normalizedArch != "x86_64" && normalizedArch != "amd64") return null
        return when {
            normalizedOs.startsWith("windows") -> "windows-x86_64"
            normalizedOs.contains("linux") -> "linux-x86_64"
            else -> null
        }
    }

    /** 下载地址：中国网络先试镜像，其余平台先试 GitHub；镜像顺序与 HMCL 清单一致。 */
    fun downloadUrls(preferMirror: Boolean): List<String> {
        val pkg = current() ?: return emptyList()
        val name = "terracotta-${pkg.version}-${pkg.classifier}-pkg.tar.gz"
        val tag = "v${pkg.version}"
        val github = "https://github.com/burningtnt/Terracotta/releases/download/$tag/$name"
        val mirrors =
            listOf(
                "https://gitee.com/burningtnt/Terracotta/releases/download/$tag/$name",
                "https://cnb.cool/HMCL-Terracotta/Terracotta/-/releases/download/$tag/$name",
                "https://alist.8mi.tech/d/mirror/HMCL-Terracotta/Auto/$tag/$name",
            )
        return if (preferMirror) mirrors + github else listOf(github) + mirrors
    }

    fun currentClassifier(): String? {
        val os = System.getProperty("os.name").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()
        return classify(os, arch)
    }

    /** 中国网络（中国内地时区/区域）优先镜像；与启动器下载源策略保持一致。 */
    fun prefersMirror(): Boolean {
        val language =
            java.util.Locale
                .getDefault()
                .country
        if (language.equals("CN", ignoreCase = true)) return true
        return java.time.ZoneId
            .systemDefault()
            .id in
            setOf(
                "Asia/Shanghai",
                "Asia/Chongqing",
                "Asia/Harbin",
                "Asia/Urumqi",
                "Asia/Kashgar",
                "PRC",
                "CTT",
            )
    }
}
