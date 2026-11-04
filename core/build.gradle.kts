import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// Общее для сервера, CLI и приложения: DTO и логика (ADR 0002). Без HTTP: клиент — в client
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// TRAIN_VERSION — версия из gradle.properties; коммит знает только сборка образа (ADR 0009)
val versionSource = tasks.register("versionSource") {
    val version = project.version.toString()
    val out = layout.buildDirectory.dir("generated/version")
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val file = out.get().file("train/core/Version.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package train.core
            |
            |/** Версия проекта, semver; генерируется из gradle.properties. */
            |const val TRAIN_VERSION = "$version"
            |""".trimMargin(),
        )
    }
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
        commonMain {
            kotlin.srcDir(versionSource)
        }
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
