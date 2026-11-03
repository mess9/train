import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Общее для сервера, CLI и приложения: DTO и логика (ADR 0002). Без HTTP: клиент — в client
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        // Тесты общей логики гоняются на JVM; браузерный прогон потребовал бы Chrome в CI
        browser { testTask { enabled = false } }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.serialization.json)
            api(libs.coroutines.core)
            api(libs.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
