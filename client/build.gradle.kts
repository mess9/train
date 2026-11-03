import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// API-клиент для CLI и приложения. Отдельно от core, чтобы Ktor не попадал на сервер
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { enabled = false } }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.ktor.client.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.core)
        }
    }
}
