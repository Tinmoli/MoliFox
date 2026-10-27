import groovy.json.JsonOutput
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

evaluationDependsOn(":launcher-bootstrap")

val bootstrapProject = project(":launcher-bootstrap")
val bootstrapSourceSets = bootstrapProject.extensions.getByType<SourceSetContainer>()
val runtimeManifestFile = layout.buildDirectory.file("portable/molifox-runtime.json")
val usedIconPaths =
    fileTree("src/main/kotlin")
        .matching { include("**/*.kt") }
        .files
        .flatMap { source ->
            Regex("Icons\\s*\\.\\s*Outlined\\s*\\.\\s*(\\w+)")
                .findAll(source.readText())
                .map { "androidx/compose/material/icons/outlined/${it.groupValues[1]}Kt*.class" }
                .toList()
        }
        .distinct()
val extendedIcons =
    configurations.runtimeClasspath.map { configuration ->
        configuration.files.first { it.name.startsWith("material-icons-extended-desktop-") }
    }
val portableUiArtifacts = providers.provider {
    val embedded =
        bootstrapProject.configurations
            .getByName("runtimeClasspath")
            .incoming
            .artifacts
            .artifacts
            .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
            .map { "${it.group}:${it.module}:${it.version}" }
            .toSet()
    configurations.runtimeClasspath.get().incoming.artifacts.artifacts.filter { artifact ->
        val module = artifact.id.componentIdentifier as? ModuleComponentIdentifier
        module != null &&
            "${module.group}:${module.module}:${module.version}" !in embedded &&
            !module.module.startsWith("material-icons-extended")
    }
}

val preparePortableRuntime by tasks.registering {
    dependsOn(":launcher-bootstrap:classes", tasks.classes)
    inputs.files(configurations.runtimeClasspath)
    outputs.file(runtimeManifestFile)
    doLast {
        val dependencies =
            portableUiArtifacts
                .get()
                .map { artifact ->
                    val file = artifact.file
                    val digest =
                        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(
                            ""
                        ) {
                            "%02x".format(it)
                        }
                    mapOf("filename" to file.name, "sha256" to digest, "size" to file.length())
                }
                .distinctBy { it["filename"] }
        val output = runtimeManifestFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            JsonOutput.prettyPrint(
                JsonOutput.toJson(
                    mapOf("version" to project.version.toString(), "dependencies" to dependencies)
                )
            )
        )
    }
}

tasks.register<Jar>("portableJar") {
    group = "distribution"
    description = "Build a single launcher with all UI dependencies for offline first launch"
    dependsOn(preparePortableRuntime, ":launcher-bootstrap:jar", ":launcher-core:jar")
    archiveFileName.set("MoliFox.jar")
    destinationDirectory.set(layout.buildDirectory.dir("portable"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.molifox.bootstrap.BootstrapMainKt",
            "Multi-Release" to "true",
        )
    }
    from(sourceSets.main.get().output)
    from({ portableUiArtifacts.get().map { it.file } }) { into("molifox-dependencies") }
    // Include the actual vector icons, instead of downloading every icon style on first run.
    from({ zipTree(extendedIcons.get()) }) { include(usedIconPaths) }
    from(bootstrapSourceSets.getByName("main").output)
    from({
        bootstrapProject.configurations.getByName("runtimeClasspath").files.map {
            if (it.isDirectory) it else zipTree(it)
        }
    }) {
        exclude(
            "META-INF/*.SF",
            "META-INF/*.RSA",
            "META-INF/*.DSA",
            "module-info.class",
            "META-INF/versions/**/module-info.class",
        )
    }
    from(runtimeManifestFile)
    from(listOf(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY.md"))) {
        into("META-INF/molifox")
    }
    from(rootProject.file("launcher-bootstrap/native/miniz/LICENSE")) {
        into("META-INF/molifox/miniz")
    }
}

val nativeBuildDirectory = layout.buildDirectory.dir("portable/native")
val mingwDirectory = providers.environmentVariable("MOLIFOX_MINGW_BIN").orNull

fun nativeCompiler(name: String) =
    mingwDirectory?.let { file("$it/$name.exe").absolutePath } ?: name

val windowsResource =
    tasks.register("prepareWindowsResource") {
        dependsOn("portableJar")
        outputs.file(nativeBuildDirectory.map { it.file("molifox.rc") })
        doLast {
            val output = nativeBuildDirectory.get().file("molifox.rc").asFile
            output.parentFile.mkdirs()
            val icon = file("src/main/resources/icons/molifox.ico").absolutePath.replace('\\', '/')
            val payload =
                layout.buildDirectory
                    .file("portable/MoliFox.jar")
                    .get()
                    .asFile
                    .absolutePath
                    .replace('\\', '/')
            output.writeText("1 ICON \"$icon\"\n101 RCDATA \"$payload\"\n")
        }
    }

val compileWindowsResource =
    tasks.register<Exec>("compileWindowsResource") {
        dependsOn(windowsResource)
        inputs.files(
            layout.buildDirectory.file("portable/MoliFox.jar"),
            file("src/main/resources/icons/molifox.ico"),
        )
        outputs.file(nativeBuildDirectory.map { it.file("resource.o") })
        commandLine(
            nativeCompiler("windres"),
            nativeBuildDirectory.get().file("molifox.rc").asFile,
            "-O",
            "coff",
            "-o",
            nativeBuildDirectory.get().file("resource.o").asFile,
        )
    }

tasks.register<Exec>("portableWindows") {
    group = "distribution"
    dependsOn(compileWindowsResource)
    inputs.file(rootProject.file("launcher-bootstrap/native/WindowsMain.cpp"))
    inputs.dir(rootProject.file("launcher-bootstrap/native/miniz"))
    inputs.file(nativeBuildDirectory.map { it.file("resource.o") })
    outputs.file(rootProject.file("MoliFox.exe"))
    outputs.file(rootProject.file("docs/validation/windows-package.sha256"))
    commandLine(
        nativeCompiler("g++"),
        "-DMINIZ_NO_DEFLATE_APIS",
        "-DMINIZ_NO_ARCHIVE_WRITING_APIS",
        "-x",
        "c",
        rootProject.file("launcher-bootstrap/native/miniz/miniz.c"),
        rootProject.file("launcher-bootstrap/native/miniz/miniz_tinfl.c"),
        rootProject.file("launcher-bootstrap/native/miniz/miniz_zip.c"),
        "-x",
        "c++",
        rootProject.file("launcher-bootstrap/native/WindowsMain.cpp"),
        "-x",
        "none",
        nativeBuildDirectory.get().file("resource.o").asFile,
        "-std=c++17",
        "-O2",
        "-s",
        "-static",
        "-municode",
        "-mwindows",
        "-lwinhttp",
        "-lbcrypt",
        "-lcomctl32",
        "-ladvapi32",
        "-o",
        rootProject.file("MoliFox.exe"),
    )
    doLast {
        val executable = rootProject.file("MoliFox.exe")
        val digest =
            MessageDigest.getInstance("SHA-256").digest(executable.readBytes()).joinToString("") {
                "%02x".format(it)
            }
        val checksum = rootProject.file("docs/validation/windows-package.sha256")
        checksum.parentFile.mkdirs()
        checksum.writeText("$digest  MoliFox.exe\n")
    }
}

val linuxRuntimeNative =
    configurations.create("linuxRuntimeNative") {
        isCanBeConsumed = false
        isTransitive = false
        defaultDependencies {
            val skiko =
                portableUiArtifacts
                    .get()
                    .mapNotNull { it.id.componentIdentifier as? ModuleComponentIdentifier }
                    .first { it.group == "org.jetbrains.skiko" && it.module == "skiko-awt" }
            add(
                project.dependencies.create(
                    "org.jetbrains.skiko:skiko-awt-runtime-linux-x64:" + skiko.version
                )
            )
        }
    }
val linuxManifestFile = layout.buildDirectory.file("portable/linux/molifox-runtime.json")
val prepareLinuxRuntime =
    tasks.register("prepareLinuxRuntime") {
        dependsOn(preparePortableRuntime)
        inputs.file(runtimeManifestFile)
        inputs.files(linuxRuntimeNative)
        outputs.file(linuxManifestFile)
        doLast {
            val host =
                groovy.json.JsonSlurper().parse(runtimeManifestFile.get().asFile) as Map<*, *>
            val shared =
                (host["dependencies"] as List<*>)
                    .map { it as Map<*, *> }
                    .filterNot { (it["filename"] as String).startsWith("skiko-awt-runtime-") }
            val native =
                linuxRuntimeNative.files.map { file ->
                    val hash =
                        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(
                            ""
                        ) {
                            "%02x".format(it)
                        }
                    mapOf("filename" to file.name, "sha256" to hash, "size" to file.length())
                }
            val output = linuxManifestFile.get().asFile
            output.parentFile.mkdirs()
            output.writeText(
                JsonOutput.prettyPrint(
                    JsonOutput.toJson(
                        mapOf(
                            "version" to project.version.toString(),
                            "platform" to "linux-x64",
                            "dependencies" to shared + native,
                        )
                    )
                )
            )
        }
    }

// JVM UI code is shared. Replace the host Skiko native library when packaging Linux.
tasks.register<Jar>("portableLinuxJar") {
    group = "distribution"
    dependsOn("portableJar", prepareLinuxRuntime)
    archiveFileName.set("MoliFox-linux.jar")
    destinationDirectory.set(layout.buildDirectory.dir("portable/linux"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to "dev.molifox.bootstrap.BootstrapMainKt",
            "Multi-Release" to "true",
        )
    }
    from({ zipTree(layout.buildDirectory.file("portable/MoliFox.jar").get().asFile) }) {
        exclude(
            "molifox-dependencies/skiko-awt-runtime-*.jar",
            "molifox-runtime.json",
            "META-INF/MANIFEST.MF",
        )
    }
    from(linuxRuntimeNative) { into("molifox-dependencies") }
    from(linuxManifestFile)
}

tasks.register("portableLinux") {
    group = "distribution"
    dependsOn("portableLinuxJar")
    val payload = layout.buildDirectory.file("portable/linux/MoliFox-linux.jar")
    inputs.file(rootProject.file("launcher-bootstrap/native/LinuxMain.sh"))
    inputs.file(payload)
    outputs.file(rootProject.file("MoliFox-linux-x64.run"))
    outputs.file(rootProject.file("docs/validation/linux-package.sha256"))
    doLast {
        val jar = payload.get().asFile
        val digest =
            MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") {
                "%02x".format(it)
            }
        val header =
            rootProject
                .file("launcher-bootstrap/native/LinuxMain.sh")
                .readText()
                .replace("\r\n", "\n")
                .replace("@VERSION@", project.version.toString())
                .replace("@PAYLOAD_SHA256@", digest)
        val output = rootProject.file("MoliFox-linux-x64.run")
        output.outputStream().use {
            it.write(header.toByteArray(Charsets.UTF_8))
            it.write(jar.readBytes())
        }
        output.setExecutable(true, false)
        val checksum = rootProject.file("docs/validation/linux-package.sha256")
        checksum.parentFile.mkdirs()
        val hash =
            MessageDigest.getInstance("SHA-256").digest(output.readBytes()).joinToString("") {
                "%02x".format(it)
            }
        checksum.writeText(hash + "  MoliFox-linux-x64.run\n")
    }
}

tasks.register("verifyDesktopPackages") {
    group = "verification"
    description =
        "Validate embedded dependency hashes and native platform binaries; does not launch Linux"
    dependsOn("portableWindows", "portableLinux")
    doLast {
        for ((platform, file) in
            listOf(
                "windows" to layout.buildDirectory.file("portable/MoliFox.jar").get().asFile,
                "linux" to
                    layout.buildDirectory.file("portable/linux/MoliFox-linux.jar").get().asFile,
            )) {
            ZipFile(file).use { zip ->
                val json =
                    zip.getInputStream(zip.getEntry("molifox-runtime.json")).reader().use {
                        groovy.json.JsonSlurper().parse(it) as Map<*, *>
                    }
                val dependencies = (json["dependencies"] as List<*>).map { it as Map<*, *> }
                for (dependency in dependencies) {
                    val entry =
                        zip.getEntry("molifox-dependencies/" + dependency["filename"])
                            ?: error("Missing embedded dependency")
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val digest =
                        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                            "%02x".format(it)
                        }
                    check(digest == dependency["sha256"]) { "Dependency hash mismatch" }
                }
                val native = dependencies.single {
                    (it["filename"] as String).startsWith("skiko-awt-runtime-")
                }
                check((native["filename"] as String).contains("-" + platform + "-x64")) {
                    "Wrong platform native library"
                }
                val entry = zip.getEntry("molifox-dependencies/" + native["filename"])
                val signature =
                    zip.getInputStream(entry).use { input ->
                        ZipInputStream(input).use { nested ->
                            var found = false
                            while (true) {
                                val item = nested.nextEntry ?: break
                                if (
                                    item.name.endsWith(if (platform == "linux") ".so" else ".dll")
                                ) {
                                    val prefix = nested.readNBytes(4)
                                    found =
                                        if (platform == "linux")
                                            prefix.contentEquals(
                                                byteArrayOf(0x7f, 0x45, 0x4c, 0x46)
                                            )
                                        else
                                            prefix.size >= 2 &&
                                                prefix[0] == 0x4d.toByte() &&
                                                prefix[1] == 0x5a.toByte()
                                    break
                                }
                            }
                            found
                        }
                    }
                check(signature) { "Native library format does not match " + platform }
                check(zip.getEntry("dev/molifox/desktop/AppearancePageKt.class") != null)
            }
        }
        val report = rootProject.file("docs/validation/desktop-packages.md")
        report.parentFile.mkdirs()
        report.writeText(
            "# Desktop package verification\n\n- Embedded UI dependency hashes: passed\n- Windows native library: PE, passed\n- Linux native library: ELF x64, passed\n- Linux desktop runtime: not executed on this Windows host\n"
        )
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":launcher-core"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

compose.desktop {
    application {
        mainClass = "dev.molifox.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
    }
}

// Compile GUI probes inside their disposable run directory, even if compilation fails.
fun registerGuiSmoke(name: String, probe: String, native: Boolean) =
    tasks.register<JavaExec>(name) {
        group = "verification"
        description = "Windows GUI check with automatic removal of its isolated test data"
        dependsOn(if (native) "portableWindows" else "portableJar")
        val testDirectory =
            rootProject.layout.projectDirectory.dir(".work/tests/${project.name}-$name").asFile
        val portableJar = layout.buildDirectory.file("portable/MoliFox.jar")
        classpath = files(File(testDirectory, "classes"), portableJar)
        mainClass.set(probe)
        timeout.set(Duration.ofMinutes(3))
        systemProperty("java.net.useSystemProxies", "true")
        systemProperty("stdout.encoding", "UTF-8")
        systemProperty("stderr.encoding", "UTF-8")
        doFirst {
            check(System.getProperty("os.name").startsWith("Windows")) {
                "This GUI check currently targets Windows"
            }
            val classes = File(testDirectory, "classes").apply { mkdirs() }
            val compiler =
                javax.tools.ToolProvider.getSystemJavaCompiler()
                    ?: error("A JDK is required for GUI checks")
            check(
                compiler.run(
                    null,
                    null,
                    null,
                    "-encoding",
                    "UTF-8",
                    "-cp",
                    portableJar.get().asFile.absolutePath,
                    "-d",
                    classes.absolutePath,
                    rootProject.file("tools/$probe.java").absolutePath,
                    rootProject.file("tools/TestWindowFocus.java").absolutePath,
                ) == 0
            ) {
                "$probe compilation failed"
            }
            if (native) {
                rootProject
                    .file("MoliFox.exe")
                    .copyTo(File(testDirectory, "MoliFox.exe"), overwrite = true)
                setArgs(
                    listOf(
                        testDirectory.absolutePath,
                        File(testDirectory, "screenshots/MoliFox-native.png").absolutePath,
                        providers.gradleProperty("nativeSmokeMode").getOrElse("offline"),
                    )
                )
            } else {
                setArgs(
                    listOf(File(testDirectory, "screenshots/MoliFox-home.png").absolutePath, when (name) { "storageUiSmoke" -> "storageUi"; "reviewSmoke" -> "review"; "skinSmoke" -> "skins"; "iconSmoke" -> "icons"; "screenshotsSmoke" -> "screenshots"; "downloadDetailsSmoke" -> "downloadDetails"; else -> "flow" })
                )
            }
        }
    }

val uiSmoke = registerGuiSmoke("uiSmoke", "UiSmoke", false)
val reviewSmoke = registerGuiSmoke("reviewSmoke", "UiSmoke", false)
val skinSmoke = registerGuiSmoke("skinSmoke", "UiSmoke", false)
val storageUiSmoke = registerGuiSmoke("storageUiSmoke", "UiSmoke", false)
val iconSmoke = registerGuiSmoke("iconSmoke", "UiSmoke", false)
val screenshotsSmoke = registerGuiSmoke("screenshotsSmoke", "UiSmoke", false)
val downloadDetailsSmoke = registerGuiSmoke("downloadDetailsSmoke", "UiSmoke", false)
// uiSmoke 新增了存档备份/恢复/删除等真实文件步骤；3 分钟在负载高时会误杀。
uiSmoke.configure { timeout.set(Duration.ofMinutes(6)) }
val nativeSmoke = registerGuiSmoke("nativeSmoke", "NativeSmoke", true)

nativeSmoke.configure { mustRunAfter(uiSmoke) }
