// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 使用明确的源码清单调用官方 ktlint 引擎，避免遍历用户目录和平台工具中的链接。

import com.pinterest.ktlint.rule.engine.api.Code;
import com.pinterest.ktlint.rule.engine.api.EditorConfigDefaults;
import com.pinterest.ktlint.rule.engine.api.EditorConfigOverride;
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine;
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import kotlin.Unit;

public final class KotlinStyleMain {
    private KotlinStyleMain() {}

    public static void main(String[] arguments) throws IOException {
        Path project = Path.of(arguments[0]).toRealPath();
        Path sourceList = Path.of(arguments[1]);
        Path report = Path.of(arguments[2]);
        boolean format = Boolean.parseBoolean(arguments[3]);
        KtLintRuleEngine engine = new KtLintRuleEngine(
                new StandardRuleSetProvider().getRuleProviders(),
                EditorConfigDefaults.Companion.getEMPTY_EDITOR_CONFIG_DEFAULTS(),
                EditorConfigOverride.Companion.getEMPTY_EDITOR_CONFIG_OVERRIDE(),
                false, FileSystems.getDefault());
        int[] issueCount = {0};
        try (BufferedWriter output = Files.newBufferedWriter(report)) {
            output.write("[\n");
            for (String source : Files.readAllLines(sourceList)) {
                checkSource(engine, project, Path.of(source), format, output, issueCount);
            }
            output.write("\n]\n");
        } finally {
            engine.trimMemory();
        }
        System.out.println("ktlint: " + issueCount[0] + " remaining issues; report: " + report);
        if (issueCount[0] > 0) System.exit(1);
    }

    private static void checkSource(
            KtLintRuleEngine engine, Path project, Path source, boolean format,
            BufferedWriter output, int[] issueCount) throws IOException {
        Path normalized = source.toAbsolutePath().normalize();
        if (!normalized.startsWith(project) || Files.isSymbolicLink(normalized)
                || !normalized.toRealPath().startsWith(project)) {
            throw new IOException("Source path is outside the project or linked: " + source);
        }
        if (format) {
            // fromPath 会把按行读取的内容重新拼接，丢失末尾换行；fromFile 保留原文。
            Code original = Code.Companion.fromFile(normalized.toFile());
            String formatted = engine.format(original, (issue, corrected) -> Unit.INSTANCE);
            if (!formatted.equals(original.getContent())) Files.writeString(normalized, formatted);
        }
        String relative = project.relativize(normalized).toString().replace('\\', '/');
        // 格式化后再检查，报告只包含尚未解决的问题，不把自动修复项算成残留。
        engine.lint(Code.Companion.fromFile(normalized.toFile()), issue -> {
            try {
                if (issueCount[0]++ > 0) output.write(",\n");
                output.write("  {\"file\":" + quoted(relative)
                        + ",\"line\":" + issue.getLine() + ",\"column\":" + issue.getCol()
                        + ",\"rule\":" + quoted(issue.getRuleId().toString())
                        + ",\"message\":" + quoted(issue.getDetail()) + "}");
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
            return Unit.INSTANCE;
        });
    }

    private static String quoted(String text) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '\\': result.append("\\\\"); break;
                case '"': result.append("\\\""); break;
                case '\n': result.append("\\n"); break;
                case '\r': result.append("\\r"); break;
                case '\t': result.append("\\t"); break;
                default:
                    if (character < 32) result.append(String.format("\\u%04x", (int) character));
                    else result.append(character);
            }
        }
        return result.append('"').toString();
    }
}
