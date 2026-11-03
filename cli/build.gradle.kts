plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":client"))
    implementation(libs.clikt)
    implementation(libs.ktor.client.java)
    // Ktor пишет через SLF4J; без провайдера — предупреждение в stderr на каждый запуск
    runtimeOnly(libs.slf4j.nop)
}

kotlin {
    jvmToolchain(21)
}

// installDist → cli/build/install/gym/bin/gym
application {
    applicationName = "gym"
    mainClass = "train.cli.MainKt"
    // Иначе на Windows вывод в кодировке консоли, и кириллица в JSON бьётся
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}
