// Телефон → сервер: сон, пульс, шаги из Health Connect (ADR 0015). Kotlin — встроенный в AGP 9.
// Без Android SDK модуль не включают (settings.gradle.kts)
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "train.health"
    compileSdk = 37

    defaultConfig {
        applicationId = "train.health"
        // Health Connect — с Android 9 (как приложение), встроен с Android 14
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = project.version.toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Своё приложение на свой телефон: подпись отладочным ключом, ставится APK напрямую
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // core и client собраны под Java 21: инлайн их функций требует того же
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/*.kotlin_module", "META-INF/io.netty.versions.properties")
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":client"))
    implementation(libs.ktor.client.okhttp)
    implementation(libs.health.connect)
    implementation(libs.work.runtime)
    implementation(libs.activity)
}
