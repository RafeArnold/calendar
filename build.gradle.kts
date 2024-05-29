plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jmailen.kotlinter") version "4.2.0"
}

group = "uk.co.rafearnold"

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
    testImplementation("com.microsoft.playwright:playwright:1.41.2")
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
