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
val androidSdk = System.getenv("ANDROID_HOME") != null || file("local.properties").exists()
if (file("health-sync").isDirectory && androidSdk) include("health-sync")
// APK приложения (docs/PLAN-android.md): UI — в `app`, здесь только вход. Тоже только с SDK
if (file("android").isDirectory && androidSdk) include("android")
