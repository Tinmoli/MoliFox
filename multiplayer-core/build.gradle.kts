// 负责联机模块的编译与离线单元测试，不引入桌面界面依赖。
plugins { kotlin("multiplatform") }

kotlin {
    jvm {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
        testRuns["test"].executionTask.configure { useJUnitPlatform() }
    }
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        commonTest {
            kotlin.srcDirs(rootProject.file("tests/multiplayer-core/commonTest/kotlin"))
            dependencies { implementation(kotlin("test")) }
        }
        // jvm 端与 launcher-core 共用语言资源与 I18n 运行时；common 端没有界面文案。
        jvmMain.dependencies {
            implementation(project(":launcher-core"))
            implementation("com.google.code.gson:gson:2.13.2")
        }
        jvmTest {
            kotlin.srcDirs(rootProject.file("tests/multiplayer-core/jvmTest/kotlin"))
            dependencies {
                implementation(kotlin("test-junit5"))
                implementation("org.junit.jupiter:junit-jupiter:5.13.4")
                runtimeOnly("org.junit.platform:junit-platform-launcher")
            }
        }
    }
}
