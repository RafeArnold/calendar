plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jmailen.kotlinter") version "4.2.0"
}

group = "uk.co.rafearnold"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
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
