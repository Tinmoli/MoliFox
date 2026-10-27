plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

allprojects {
    group = "dev.molifox"
    version = "0.3.0"
}

apply(from = "gradle/test-hygiene.gradle.kts")
