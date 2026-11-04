import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.quarkus)
}

dependencies {
    // platform, а не enforcedPlatform: BOM не должен опускать версии, с которыми собран core
    implementation(platform(libs.quarkus.bom))
    implementation(project(":core"))
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.kotlin.serialization)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(libs.flyway.postgresql)
    implementation(libs.quarkus.smallrye.openapi)
    implementation(libs.quarkus.smallrye.health)

    testImplementation(libs.quarkus.junit)
    testImplementation(libs.rest.assured)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { javaParameters = true }
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.enterprise.context.RequestScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

// Сборка приложения кладётся в META-INF/resources: один процесс отдаёт и API, и PWA
val appDist by tasks.registering(Sync::class) {
    dependsOn(":app:wasmJsBrowserDistribution")
    from(rootProject.layout.projectDirectory.dir("app/build/dist/wasmJs/productionExecutable")) {
        into("META-INF/resources")
        exclude("**/*.map")
    }
    val out = layout.buildDirectory.dir("app-dist")
    into(out)
    // Cloudflare кэширует всё на 4 часа: кроме index.html и sw.js, у файлов в имени хеш.
    // wasm хеширует webpack; app.js — здесь, а sw.js получает номер сборки и список файлов
    doLast {
        val dir = out.get().asFile.resolve("META-INF/resources")
        fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.take(12)
        val js = dir.resolve("app.js")
        val hashed = "app.${sha(js.readBytes())}.js"
        js.renameTo(dir.resolve(hashed))
        val index = dir.resolve("index.html")
        index.writeText(index.readText().replace("'app.js'", "'$hashed'"))
        val files = dir.walk().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }
            .filter { it != "index.html" && it != "sw.js" && !it.endsWith(".txt") }.sorted().toList()
        val build = sha((files + index.readText()).joinToString("\n").toByteArray())
        val sw = dir.resolve("sw.js")
        sw.writeText(
            sw.readText().replace("'__BUILD__'", "'$build'")
                .replace("__FILES__", files.joinToString(", ", "[", "]") { "'$it'" }),
        )
    }
}

sourceSets.main {
    resources.srcDir(appDist)
}

tasks.test {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
