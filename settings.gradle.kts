// 负责声明 Gradle 模块和依赖仓库。

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MoliFox"

include(":launcher-core", ":desktop-app", ":launcher-bootstrap")
