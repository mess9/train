pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "train"

include("core", "client", "server", "cli", "app")
// Синхронизация сна и пульса с телефона (ADR 0015). Нужен Android SDK; в образ сервера папку
// не копируют — там сборка идёт без модуля
if (file("health-sync").isDirectory && (System.getenv("ANDROID_HOME") != null || file("local.properties").exists())) include("health-sync")
