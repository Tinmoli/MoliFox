// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责游戏进程日志的编码识别与流式解码。

package dev.molifox.core

internal data class DecodedGameLog(val message: String, val error: Boolean)

/** Mojang's console layout emits XML fragments; keep message and severity together. */
internal class GameLogDecoder(private val output: (DecodedGameLog) -> Unit) {
    private var event: StringBuilder? = null
    private val start = Regex("<log4j:event\\b", RegexOption.IGNORE_CASE)
    private val end = Regex("</log4j:event\\s*>", RegexOption.IGNORE_CASE)

    fun accept(line: String) {
        if (start.containsMatchIn(line)) {
            flush()
            event = StringBuilder()
        }
        val buffer = event
        if (buffer == null) {
            output(DecodedGameLog(line, isGameError(line)))
            return
        }
        buffer.appendLine(line)
        if (end.containsMatchIn(line)) {
            val text = buffer.toString()
            event = null
            val attributes =
                Regex("(level|thread)=[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                    .findAll(text.substringBefore('>'))
                    .associate { it.groupValues[1].lowercase() to xmlText(it.groupValues[2]) }
            val message = body(text, "Message")
            if (message == null) {
                raw(text)
                return
            }
            val level = attributes["level"].orEmpty().uppercase()
            val thread = attributes["thread"].orEmpty()
            val throwable = body(text, "Throwable")?.takeIf { it.isNotBlank() }
            val prefix =
                if (level.isBlank()) "" else "[${if (thread.isBlank()) "" else "$thread/"}$level] "
            val rendered = prefix + message + (throwable?.let { "\n$it" } ?: "")
            output(
                DecodedGameLog(
                    rendered,
                    if (level.isBlank()) isGameError(rendered)
                    else level in setOf("ERROR", "FATAL"),
                )
            )
        } else if (buffer.length > 128 * 1024) {
            flush()
        }
    }

    fun flush() {
        val pending = event ?: return
        event = null
        raw(pending.toString())
    }

    private fun raw(text: String) =
        text.trimEnd('\r', '\n').lineSequence().forEach {
            output(DecodedGameLog(it, isGameError(it)))
        }

    private fun body(text: String, tag: String): String? {
        val opening =
            Regex("<log4j:$tag(?:\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(text) ?: return null
        val closing =
            Regex("</log4j:$tag\\s*>", RegexOption.IGNORE_CASE).find(text, opening.range.last + 1)
                ?: return null
        val content = text.substring(opening.range.last + 1, closing.range.first).trim()
        return if (content.startsWith("<![CDATA[") && content.endsWith("]]>")) {
            content.removePrefix("<![CDATA[").removeSuffix("]]>").replace("]]]]><![CDATA[>", "]]>")
        } else xmlText(content)
    }

    private fun xmlText(text: String): String =
        Regex("&(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);").replace(text) { match ->
            when (val entity = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else ->
                    runCatching {
                            val code =
                                if (entity.startsWith("#x")) entity.drop(2).toInt(16)
                                else entity.drop(1).toInt()
                            String(Character.toChars(code))
                        }
                        .getOrDefault(match.value)
            }
        }
}
