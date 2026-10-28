// 为正式包验收准备逐文件验证过的本地依赖缓存，避免重复下载相同的界面依赖。

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

final class NativeRuntimeCache {
    static int seed(Path root) throws Exception {
        Path expected =
                Path.of(System.getProperty("molifox.test.dir")).toAbsolutePath().normalize();
        if (!root.toAbsolutePath().normalize().equals(expected))
            throw new IllegalArgumentException("Not the managed native test directory");
        Path repository =
                Path.of(System.getenv("GRADLE_USER_HOME")).resolve("caches/modules-2/files-2.1");
        try (var input = NativeRuntimeCache.class.getResourceAsStream("/molifox-runtime.json")) {
            if (input == null) throw new IllegalStateException("Missing packaged runtime manifest");
            var manifest =
                    com.google.gson.JsonParser.parseReader(
                                    new java.io.InputStreamReader(
                                            input, java.nio.charset.StandardCharsets.UTF_8))
                            .getAsJsonObject();
            Path destination =
                    root.resolve("MoliFox/runtime").resolve(manifest.get("version").getAsString());
            Files.createDirectories(destination);
            var dependencies = manifest.getAsJsonArray("dependencies");
            for (var item : dependencies) {
                var dependency = item.getAsJsonObject();
                String name = dependency.get("filename").getAsString();
                Path version =
                        repository
                                .resolve(dependency.get("group").getAsString())
                                .resolve(dependency.get("artifact").getAsString())
                                .resolve(dependency.get("version").getAsString());
                Path source;
                try (var files = Files.walk(version)) {
                    source =
                            files.filter(
                                            path ->
                                                    path.getFileName().toString().equals(name)
                                                            && Files.isRegularFile(path))
                                    .findFirst()
                                    .orElseThrow();
                }
                byte[] bytes = Files.readAllBytes(source);
                String hash =
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                if (bytes.length != dependency.get("size").getAsLong()
                        || !hash.equals(dependency.get("sha256").getAsString()))
                    throw new IllegalStateException(
                            "Cached dependency failed verification: " + name);
                Files.copy(source, destination.resolve(name));
            }
            Files.writeString(destination.resolve("ready.marker"), "verified native test cache");
            return dependencies.size();
        }
    }
}
