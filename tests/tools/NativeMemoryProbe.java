// 只观察本次正式包测试创建的 Java 子进程，记录空闲内存及 JVM 堆，不读取玩家数据。
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class NativeMemoryProbe {
    private static final long[] SAMPLE_DELAYS_MS = {5_000, 10_000, 15_000};
    private static final long COMMAND_TIMEOUT_SECONDS = 10;

    static void observe(Process launcher, Path root, long windowVisibleMillis) throws Exception {
        String profile = System.getProperty("molifox.native.memoryProfile", "");
        if (profile.isBlank()) return;
        if (!Set.of("baseline", "tuned").contains(profile)) throw new IllegalArgumentException("Unknown memory profile");
        long javaPid = launcher.descendants().filter(child -> child.info().command()
                .map(command -> command.endsWith("javaw.exe") || command.endsWith("java.exe")).orElse(false))
                .mapToLong(ProcessHandle::pid).findFirst().orElseThrow();
        JsonArray samples = new JsonArray();
        long started = System.nanoTime();
        for (long delay : SAMPLE_DELAYS_MS) {
            Thread.sleep(delay);
            JsonObject sample = memory(javaPid, root);
            sample.addProperty("idleMillis", (System.nanoTime() - started) / 1_000_000);
            samples.add(sample);
        }
        JsonObject report = new JsonObject();
        report.addProperty("profile", profile);
        report.addProperty("javaPid", javaPid);
        report.addProperty("windowVisibleMillis", windowVisibleMillis);
        report.add("samples", samples);
        if (Boolean.getBoolean("molifox.native.attachDiagnostics")) {
            report.addProperty("vmFlags", optionalJcmd(javaPid, root, "VM.flags"));
            report.addProperty("heap", optionalJcmd(javaPid, root, "GC.heap_info"));
        } else {
            report.addProperty("attachDiagnostics", "not requested; unavailable in baseline on this host");
        }
        Path destination = Path.of(System.getProperty("molifox.native.report"))
                .resolveSibling("native-memory-" + profile + ".json");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, report.toString());
        System.out.println("NATIVE_MEMORY_REPORT " + report);
    }

    private static JsonObject memory(long pid, Path root) throws Exception {
        String command = "$p=Get-Process -Id " + pid + ";[pscustomobject]@{workingSetBytes=$p.WorkingSet64;privateBytes=$p.PrivateMemorySize64}|ConvertTo-Json -Compress";
        Path output = root.resolve("memory-sample.txt");
        Process query = new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", command)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        await(query);
        return JsonParser.parseString(Files.readString(output).strip()).getAsJsonObject();
    }

    private static String optionalJcmd(long pid, Path root, String operation) {
        try {
            return jcmd(pid, root, operation);
        } catch (Exception failure) {
            String unavailable = "UNAVAILABLE: " + failure;
            System.out.println("NATIVE_MEMORY_DIAGNOSTIC " + unavailable);
            return unavailable;
        }
    }

    private static String jcmd(long pid, Path root, String operation) throws Exception {
        Path executable = Path.of(System.getenv("JAVA_HOME"), "bin", "jcmd.exe");
        Path output = root.resolve(operation.replace('.', '-') + ".txt");
        Process query = new ProcessBuilder(executable.toString(), Long.toString(pid), operation)
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        await(query);
        return Files.readString(output);
    }

    private static void await(Process query) throws Exception {
        if (!query.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            query.destroyForcibly();
            query.waitFor();
            throw new IllegalStateException("Memory query timed out");
        }
        if (query.exitValue() != 0) throw new IllegalStateException("Memory query failed");
    }
}
