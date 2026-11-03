plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.quarkus)
}

version = "0.1.0"

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
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

// Сборка приложения кладётся в META-INF/resources: один процесс отдаёт и API, и PWA
val appDist by tasks.registering(Sync::class) {
    dependsOn(":app:wasmJsBrowserDistribution")
    from(rootProject.layout.projectDirectory.dir("app/build/dist/wasmJs/productionExecutable")) {
        into("META-INF/resources")
    }
    into(layout.buildDirectory.dir("app-dist"))
}

sourceSets.main {
    resources.srcDir(appDist)
}

tasks.test {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
