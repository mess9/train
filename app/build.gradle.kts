import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.serialization)
    // Android-таргет библиотеки (AGP 9): APK собирает отдельный модуль, здесь — только UI-код
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    android {
        namespace = "train.app"
        compileSdk = 37
        minSdk = 28
        // core и client собраны под Java 21: инлайн их функций требует того же
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        // Имя модуля webpack делает глобальной переменной: свои JS-глобалы — не `app`
        outputModuleName.set("app")
        browser { commonWebpackConfig { outputFileName = "app.js" } }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":client"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // ComponentActivity: выбор фото, «назад», жизненный цикл (AndroidHost)
            implementation(libs.activity)
            // Сон и пульс с браслета → сервер (ADR 0015): чтение Health Connect и фон
            implementation(libs.health.connect)
            implementation(libs.work.runtime)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(libs.ktor.client.js)
        }
    }
}
