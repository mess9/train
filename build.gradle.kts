// Версии плагинов — один раз здесь, модули подключают без версии
plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.allopen) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.quarkus) apply false
}

// Сквозные тесты (e2e/, ADR 0012): агент через CLI, владелец — PWA в Chromium, стенд — Postgres
// в Docker и собранный сервер на порту 7413. Долгие, нужны Docker и Node — в build/check не входят
tasks.register<Exec>("e2e") {
    group = "verification"
    description = "Сквозные тесты: CLI агента и PWA владельца против собранного сервера"
    dependsOn(":server:quarkusBuild", ":cli:installDist")
    workingDir = layout.projectDirectory.dir("e2e").asFile
    val windows = System.getProperty("os.name").startsWith("Windows")
    commandLine((if (windows) listOf("cmd", "/c") else emptyList()) + listOf("npm", "run", "e2e"))
}
