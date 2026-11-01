// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 验证格式检查只读、格式化幂等和路径边界，测试数据始终位于项目测试目录。

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class KotlinStyleTest {
    private KotlinStyleTest() {}

    public static void main(String[] arguments) throws Exception {
        Path tests = Path.of(arguments[0]).toAbsolutePath().normalize();
        Files.createDirectories(tests);
        Path run = Files.createTempDirectory(tests, "style-");
        try {
            verifyFormatting(run);
            verifyPathBoundary(run);
            System.out.println("Style tool: read-only, formatting, idempotence and path checks passed");
        } finally {
            try (var paths = Files.walk(run)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void verifyFormatting(Path run) throws Exception {
        Path root = Files.createDirectory(run.resolve("project"));
        Files.writeString(root.resolve(".editorconfig"),
                "root = true\n[*.kt]\nindent_size = 4\ninsert_final_newline = true\n"
                        + "ktlint_standard_no-unused-imports = enabled\n");
        Path source = root.resolve("Sample.kt");
        String unformatted = "import java.util.UUID\n\nfun main() { println(\"茉狐\") }";
        Files.writeString(source, unformatted);
        Path sourceList = run.resolve("sources.txt");
        Files.writeString(sourceList, source.toString());
        Path report = run.resolve("report.json");
        require(invoke(root, sourceList, report, false, run) == 1, "Check must reject bad formatting");
        require(Files.readString(source).equals(unformatted), "Check changed a source file");
        require(Files.readString(report).contains("Sample.kt"), "Check did not report the source");
        require(invoke(root, sourceList, report, true, run) == 0, "Format did not fix the fixture");
        String formatted = Files.readString(source);
        require(formatted.contains("茉狐") && formatted.endsWith("\n"), "Text or newline was lost");
        require(!formatted.contains("UUID"), "Unused import was not removed");
        require(invoke(root, sourceList, report, true, run) == 0, "Second format failed");
        require(Files.readString(source).equals(formatted), "Formatting was not idempotent");
        require(invoke(root, sourceList, report, false, run) == 0, "Formatted source failed check");
    }

    private static void verifyPathBoundary(Path run) throws Exception {
        Path source = run.resolve("Outside.kt");
        String original = "val untouched=1";
        Files.writeString(source, original);
        Path sourceList = run.resolve("outside-sources.txt");
        Files.writeString(sourceList, source.toString());
        require(invoke(run.resolve("project"), sourceList, run.resolve("outside.json"), true, run) != 0,
                "Formatting accepted a source outside its root");
        require(Files.readString(source).equals(original), "Rejected source was modified");
    }

    private static int invoke(Path root, Path list, Path report, boolean format, Path run)
            throws IOException, InterruptedException {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(executable,
                "-Dlogback.configurationFile=" + System.getProperty("logback.configurationFile"),
                "-cp", System.getProperty("java.class.path"), "KotlinStyleMain",
                root.toString(), list.toString(), report.toString(), Boolean.toString(format))
                .redirectErrorStream(true).redirectOutput(run.resolve("child.log").toFile()).start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor();
            throw new AssertionError("Style fixture exceeded its time limit");
        }
        return process.exitValue();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
