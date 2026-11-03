// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 选取与退出相关的异常块，避免模组更新检查等非致命警告污染诊断。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import java.nio.file.Path

private val LOG_EVENT_HEADER = Regex("^.*?\\[[^\\]]+/(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL)]")
private val ERROR_EVENT_HEADER = Regex("^.*?\\[[^\\]]+/(?:ERROR|FATAL)]")
private val FATAL_EVENT =
    Regex(
        "Dear ImGui Assertion Failed:|Assertion failed:|Exception in thread |" +
            "Encountered an unexpected exception|\\[[^\\]]+/FATAL]",
        RegexOption.IGNORE_CASE,
    )

/** 原生断言可能直接退出 JVM 而不写 crash report；这类证据优先于普通 ERROR/WARN。 */
internal fun selectFailureEvidence(text: String): String {
    val decoded = mutableListOf<String>()
    val decoder = GameLogDecoder { decoded += it.message }
    text.lineSequence().forEach(decoder::accept)
    decoder.flush()
    val lines = decoded.flatMap { it.lines() }
    val fatal = lines.indexOfLast { FATAL_EVENT.containsMatchIn(it) }
    if (fatal >= 0) return lines.drop(fatal).joinToString("\n")
    val error = lines.indexOfLast { ERROR_EVENT_HEADER.containsMatchIn(it) }
    return if (error >= 0) {
        lines
            .drop(error)
            .take(1)
            .plus(
                lines.drop(error + 1).takeWhile { !LOG_EVENT_HEADER.containsMatchIn(it) },
            ).joinToString("\n")
    } else {
        // 没有日志事件头的文本仍可能是 Java 在创建日志系统前输出的启动异常。
        if (lines.any { LOG_EVENT_HEADER.containsMatchIn(it) }) "" else text
    }
}

internal fun diagnoseFontAssertion(
    evidence: String,
    directory: Path,
): LaunchIssue? {
    val assertion = evidence.lineSequence().firstOrNull { it.contains("Dear ImGui Assertion Failed:") }
    if (assertion == null || !evidence.contains("ImFontAtlas")) return null
    val flashback = evidence.contains("com.moulberry.flashback.editor.ui.ReplayUI.initFonts")
    val key = if (flashback) "flashbackFont" else "nativeFont"
    val stack = evidence.lineSequence().firstOrNull { it.contains(".initFonts(") }.orEmpty()
    return LaunchIssue(
        title = I18n.t("diagA2.launchDiagnostics.${key}Title"),
        suggestion = I18n.t("diagA2.launchDiagnostics.${key}Suggestion"),
        evidence = redactSecrets("$assertion\n$stack").take(EVIDENCE_MAX_LENGTH),
        directory = directory.toString(),
    )
}
