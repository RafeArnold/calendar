import com.github.gradle.node.npm.task.NpxTask
import org.jmailen.gradle.kotlinter.tasks.LintTask
import java.nio.file.Files
import java.nio.file.Path

buildscript {
    dependencies {
        classpath("org.xerial:sqlite-jdbc:3.46.1.0")
    }
}

plugins {
    kotlin("jvm") version "2.0.20"
    id("org.jmailen.kotlinter") version "4.4.1"
    id("com.github.node-gradle.node") version "7.0.2"
    id("org.flywaydb.flyway") version "10.17.2"
    id("org.jooq.jooq-codegen-gradle") version "3.19.11"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "uk.co.rafearnold"

application.mainClass = "uk.co.rafearnold.calendar.MainKt"

tasks.run<JavaExec> {
    environment("DB_URL", "jdbc:sqlite:calendar.db")
    environment("ASSET_DIRS", "src/main/resources/assets")
    environment("HOT_RELOADING", "true")
    environment("ENABLE_AUTH", "false")
    File(".env").readLines().forEach { line ->
        val (key, value) = line.split('=')
        environment(key, value)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.http4k:http4k-bom:5.29.0.0"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-server-jetty")
    implementation("org.http4k:http4k-template-core")

    implementation("io.pebbletemplates:pebble:3.2.2")

    implementation("org.jooq:jooq:3.19.11")
    implementation("org.flywaydb:flyway-core:10.17.2")
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")

    implementation("com.google.api-client:google-api-client:2.6.0")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.2")

    implementation("ch.qos.logback:logback-classic:1.5.7")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("com.microsoft.playwright:playwright:1.46.0")
    testImplementation("org.wiremock:wiremock-jetty12:3.9.1")
    testImplementation("com.auth0:java-jwt:4.4.0")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
}

val dbFile: Path = Files.createTempFile("calendar", ".db")
    .also { it.toFile().deleteOnExit() }
val dbUrl: String = "jdbc:sqlite:${dbFile.toAbsolutePath()}"

flyway {
    url = dbUrl
}

jooq {
    configuration {
        jdbc {
            url = dbUrl
        }
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            database {
                excludes = "flyway_schema_history"
            }
            target {
                packageName = "uk.co.rafearnold.calendar.jooq"
            }
        }
    }
}

tasks.jooqCodegen {
    dependsOn("flywayMigrate")
}

tasks.compileKotlin {
    dependsOn("jooqCodegen")
}

tasks.withType<LintTask> {
    dependsOn("jooqCodegen")
    exclude("/uk/co/rafearnold/calendar/jooq")
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
    dependsOn("npmInstall")
}
