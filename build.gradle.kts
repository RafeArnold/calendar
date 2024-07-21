import com.github.gradle.node.npm.task.NpxTask

plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jmailen.kotlinter") version "4.2.0"
    id("com.github.node-gradle.node") version "7.0.2"
    application
}

group = "uk.co.rafearnold"

application.mainClass = "uk.co.rafearnold.calendar.MainKt"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.http4k:http4k-bom:5.14.0.0"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-server-jetty")
    implementation("org.http4k:http4k-template-core")

    implementation("io.pebbletemplates:pebble:3.2.2")

    implementation("ch.qos.logback:logback-classic:1.4.12")

    testImplementation(kotlin("test"))
    testImplementation("com.microsoft.playwright:playwright:1.45.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

tasks.check {
    dependsOn("installKotlinterPrePushHook")
}

tasks.processResources {
    dependsOn("buildCss")
}

task("buildCss", NpxTask::class) {
    command = "tailwindcss"
    args = listOf(
        "-i", "./src/main/resources/input.css",
        "-o", "./src/main/resources/assets/index.min.css",
        "-m",
    )
}
