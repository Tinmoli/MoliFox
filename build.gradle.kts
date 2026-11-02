// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责声明项目插件、版本和测试清理规则。

plugins {
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    kotlin("jvm") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

allprojects {
    group = "dev.molifox"
    version = "0.4.3"
}

subprojects { apply(plugin = "org.jetbrains.kotlinx.kover") }
dependencies {
    kover(project(":launcher-core"))
    kover(project(":desktop-app"))
    kover(project(":launcher-bootstrap"))
    kover(project(":multiplayer-core"))
}

apply(from = "gradle/test-hygiene.gradle.kts")
apply(from = "gradle/quality.gradle.kts")
apply(from = "gradle/i18n.gradle.kts")
