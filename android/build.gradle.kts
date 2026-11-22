// APK приложения тренировок: UI и вся платформа — в `app` (androidMain), здесь — вход, манифест,
// значок, подпись и версия. KMP и com.android.application в одном модуле AGP 9 не пускает
// (docs/PLAN-android.md). Kotlin встроенный в AGP 9. Без Android SDK модуль не включают
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Подпись release — ключом вне репо: путь и пароли из ~/.gradle/gradle.properties или окружения.
// Нет ключа — release собирается неподписанным (поставить нельзя), отладочным ключом не подменяем
fun secret(name: String): String? =
    providers.gradleProperty("train.signing.$name").orNull ?: System.getenv("TRAIN_SIGNING_" + name.uppercase())

val keystore = secret("store")?.let(::file)?.takeIf { it.exists() }

// Версия: semver проекта + коммит (ADR 0009); versionCode растёт с каждым коммитом — обновление
// встаёт поверх только с большим кодом. В Docker `.git` нет — оба числа передаёт Jenkins окружением
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine(listOf("git") + args) }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val commits = (env("TRAIN_VERSION_CODE") ?: git("rev-list", "--count", "HEAD"))?.toIntOrNull() ?: 1
val commit = env("TRAIN_COMMIT") ?: git("rev-parse", "--short=7", "HEAD")

android {
    namespace = "train.apk"
    compileSdk = 37

    defaultConfig {
        applicationId = "train.app"
        minSdk = 28
        targetSdk = 36
        versionCode = commits
        versionName = listOfNotNull(project.version.toString(), commit).joinToString("+")
        manifestPlaceholders["label"] = "Тренировки"
    }

    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = secret("storePassword")
            keyAlias = secret("keyAlias") ?: "train"
            keyPassword = secret("keyPassword") ?: secret("storePassword")
        }
    }

    buildTypes {
        debug {
            // Ставится рядом с боевой и ей не мешает
            applicationIdSuffix = ".debug"
            manifestPlaceholders["label"] = "Тренировки (отладка)"
        }
        release {
            // Плавность мерить только на release: R8 и без отладки ART (скил jetpack-compose-performance)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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
    implementation(project(":app"))
    implementation(libs.activity.compose)
}
