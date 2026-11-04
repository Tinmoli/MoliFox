// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责校验多语言资源的 key 对齐、占位符一致与空值，并统计代码里剩余的中文字面量。

import java.io.File
import java.util.Properties

/** 资源根目录：`launcher-core/src/main/resources/i18n/<语言>/<bundle>.properties`。 */
val i18nRoot: File =
    project.rootProject.layout.projectDirectory
        .dir("launcher-core/src/main/resources/i18n")
        .asFile

/** 与 I18n.BUNDLES 保持一致的 bundle 列表；新增 bundle 时两处同时修改。 */
val i18nBundleNames: List<String> =
    listOf(
        "app",
        "settings",
        "pages",
        "dialogs",
        "core",
        "multiplayer",
        "bootstrap",
        "desktop",
        "logs",
        "packs",
        "network",
        "diagnostics",
        "save",
        "instance",
        "content",
        "misc",
    )

/** 必须与简体中文完全对齐的语言。 */
val i18nRequiredLanguages: List<String> = listOf("en-US", "zh-TW")

/** 只覆盖差异、其余回退到 zh-TW 的语言。 */
val i18nOverlayLanguages: List<String> = listOf("zh-HK", "zh-CN-x-meow")

val i18nKeyPattern = Regex("[a-z][a-zA-Z0-9]*(\\.[a-zA-Z0-9]+)+")
val i18nPlaceholderPattern = Regex("""\{(\d+)\}""")
val i18nCjkPattern = Regex("[\\u4e00-\\u9fff]")
val i18nQuotedPattern = Regex("\"[^\"\\r\\n]*[\\u4e00-\\u9fff][^\"\\r\\n]*\"")
val i18nRawBlockPattern = Regex("\"\"\"[\\s\\S]*?\"\"\"")

/** 参与中文扫描的源码目录；新增模块时同步加一行。 */
fun i18nSourceRoots(): List<File> =
    listOf(
        "launcher-core/src/main/kotlin",
        "desktop-app/src/main/kotlin",
        "multiplayer-core/src/commonMain/kotlin",
        "multiplayer-core/src/jvmMain/kotlin",
        "launcher-bootstrap/src/main/kotlin",
    ).map { File(project.rootProject.projectDir, it) }.filter { it.isDirectory }

/** 提取含中文的字符串字面量（单行引号与三引号块），返回 `行号 to 字面量`。 */
fun chineseLiterals(text: String): List<Pair<Int, String>> {
    fun lineOf(offset: Int): Int = text.take(offset).count { it == '\n' } + 1
    val found = mutableListOf<Pair<Int, String>>()
    i18nQuotedPattern.findAll(text).forEach { found += lineOf(it.range.first) to it.value }
    i18nRawBlockPattern
        .findAll(text)
        .filter { i18nCjkPattern.containsMatchIn(it.value) }
        .forEach { found += lineOf(it.range.first) to it.value.take(120) }
    return found.sortedBy { it.first }
}

fun readI18nBundle(
    language: String,
    bundle: String,
): Map<String, String> {
    val file = File(i18nRoot, "$language/$bundle.properties")
    if (!file.isFile) return emptyMap()
    val properties = Properties()
    file.reader(Charsets.UTF_8).use(properties::load)
    return properties.entries.associate { it.key as String to it.value as String }
}

/** 合并一种语言的全部 bundle，同时记录 key 来自哪个文件，方便检出跨文件重复。 */
fun readI18nLanguage(language: String): Map<String, Pair<String, String>> {
    val merged = LinkedHashMap<String, Pair<String, String>>()
    for (bundle in i18nBundleNames) {
        for ((key, value) in readI18nBundle(language, bundle)) {
            merged[key] = value to bundle
        }
    }
    return merged
}

tasks.register("checkI18n") {
    group = "verification"
    description = "校验语言文件：key 对齐、占位符一致、空值、跨 bundle 重复 key"
    inputs.dir(i18nRoot)
    doLast {
        val problems = mutableListOf<String>()
        val base = readI18nLanguage("zh-CN")
        if (base.isEmpty()) problems += "简体中文资源为空：$i18nRoot"

        for (language in i18nRequiredLanguages) {
            val translated = readI18nLanguage(language)
            val missing = base.keys - translated.keys
            val extra = translated.keys - base.keys
            missing.take(10).forEach { problems += "$language 缺少 key：$it" }
            extra.take(10).forEach { problems += "$language 多出 key：$it" }
            for (key in base.keys.intersect(translated.keys)) {
                val expected = i18nPlaceholderPattern.findAll(base.getValue(key).first).map { it.value }.toSet()
                val actual = i18nPlaceholderPattern.findAll(translated.getValue(key).first).map { it.value }.toSet()
                if (expected != actual) {
                    problems += "$language 的 $key 占位符不一致：$expected != $actual"
                }
            }
        }

        for (language in listOf("zh-CN") + i18nRequiredLanguages + i18nOverlayLanguages) {
            val values = readI18nLanguage(language)
            for ((key, entry) in values) {
                if (!i18nKeyPattern.matches(key)) problems += "$language 的 key 命名不规范：$key"
                if (entry.first.isBlank()) problems += "$language 的 $key 是空值"
            }
            // 同一语言内不同 bundle 出现同一个 key 时无法判断谁生效，直接报错。
            val duplicates =
                i18nBundleNames
                    .flatMap { bundle -> readI18nBundle(language, bundle).keys.map { it to bundle } }
                    .groupBy({ it.first }, { it.second })
                    .filterValues { it.size > 1 }
            duplicates.keys.take(10).forEach { key ->
                problems += "$language 的 $key 在多个 bundle 重复：${duplicates.getValue(key)}"
            }
        }

        // 覆盖语言只能改已有 key，且占位符必须与被覆盖语言一致。
        val taiwanese = readI18nLanguage("zh-TW")
        for (language in i18nOverlayLanguages) {
            for ((key, entry) in readI18nLanguage(language)) {
                val source = taiwanese[key]
                if (source == null) {
                    problems += "$language 新增了 zh-TW 没有的 key：$key"
                    continue
                }
                val expected = i18nPlaceholderPattern.findAll(source.first).map { it.value }.toSet()
                val actual = i18nPlaceholderPattern.findAll(entry.first).map { it.value }.toSet()
                if (expected != actual) {
                    problems += "$language 的 $key 占位符与 zh-TW 不一致：$expected != $actual"
                }
            }
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "语言资源校验失败（${problems.size} 项）：\n" + problems.joinToString("\n") { " - $it" },
            )
        }
        val translatedKeys = i18nRequiredLanguages.sumOf { readI18nLanguage(it).size }
        logger.lifecycle(
            "checkI18n: ${base.size} 个 key；en-US/zh-TW 各 $translatedKeys；zh-HK 覆盖 ${readI18nLanguage("zh-HK").size}",
        )
    }
}

/** 统计各模块源码里剩余的中文字符串字面量，供分批迁移时查看进度。 */
tasks.register("scanI18n") {
    group = "verification"
    description = "统计源码里剩余的中文字符串字面量（不包含注释）"
    doLast {
        val counts = sortedMapOf<String, Int>()
        for (root in i18nSourceRoots()) {
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val total = chineseLiterals(file.readText()).size
                    if (total > 0) {
                        counts[file.relativeTo(project.rootProject.projectDir).path.replace('\\', '/')] = total
                    }
                }
        }
        val total = counts.values.sum()
        val report =
            File(
                project.rootProject.layout.buildDirectory
                    .get()
                    .asFile,
                "reports/i18n/chinese-literals.txt",
            )
        report.parentFile.mkdirs()
        report.writeText(
            buildString {
                appendLine("剩余中文字面量：$total 条，分布在 ${counts.size} 个文件")
                counts.entries.sortedByDescending { it.value }.forEach { (path, count) ->
                    appendLine("$count\t$path")
                }
            },
        )
        logger.lifecycle("scanI18n: 剩余 $total 条中文字面量（${counts.size} 个文件），明细见 $report")
        counts.entries.sortedByDescending { it.value }.take(20).forEach { (path, count) ->
            logger.lifecycle("  $count  $path")
        }
    }
}

/**
 * 迁移完成后的硬门禁：源码里不允许再出现未进资源的中文字面量。
 *
 * 允许清单 `gradle/i18n-allowlist.txt` 每行 `相对路径|字面量片段`，用于旧配置兼容值等无法翻译的常量。
 */
tasks.register("checkNoHardcodedChinese") {
    group = "verification"
    description = "检查源码里是否还有未迁移的中文字符串字面量（允许清单：gradle/i18n-allowlist.txt）"
    doLast {
        val allowlistFile = project.rootProject.file("gradle/i18n-allowlist.txt")
        val allowed =
            if (allowlistFile.isFile) {
                allowlistFile
                    .readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .map { line ->
                        val separator = line.indexOf('|')
                        require(separator > 0) { "允许清单格式应为 路径|片段：$line" }
                        line.substring(0, separator) to line.substring(separator + 1)
                    }
            } else {
                emptyList()
            }
        val violations = mutableListOf<String>()
        for (root in i18nSourceRoots()) {
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val relative = file.relativeTo(project.rootProject.projectDir).path.replace('\\', '/')
                    val fragments = allowed.filter { it.first == relative }.map { it.second }
                    for ((line, literal) in chineseLiterals(file.readText())) {
                        if (fragments.any { literal.contains(it) }) continue
                        violations += "$relative:$line  $literal"
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "还有 ${violations.size} 条中文字面量未迁移（或未加入允许清单）：\n" +
                    violations.take(40).joinToString("\n") { " - $it" } +
                    if (violations.size > 40) "\n … 其余见 scanI18n 报告" else "",
            )
        }
        logger.lifecycle("checkNoHardcodedChinese: 通过，没有未迁移的中文字面量")
    }
}
