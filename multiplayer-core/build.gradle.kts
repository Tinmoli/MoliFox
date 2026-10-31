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
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmMain.dependencies { implementation("com.google.code.gson:gson:2.13.2") }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation("org.junit.jupiter:junit-jupiter:5.13.4")
            runtimeOnly("org.junit.platform:junit-platform-launcher")
        }
    }
}
