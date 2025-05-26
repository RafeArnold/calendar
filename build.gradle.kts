import com.github.gradle.node.npm.task.NpxTask
import org.jmailen.gradle.kotlinter.tasks.LintTask
import java.nio.file.Files
import java.nio.file.Path

buildscript {
    dependencies {
        classpath("org.xerial:sqlite-jdbc:3.49.1.0")
    }
}

plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jmailen.kotlinter") version "5.1.0"
    id("com.github.node-gradle.node") version "7.1.0"
    id("org.flywaydb.flyway") version "11.8.2"
    id("org.jooq.jooq-codegen-gradle") version "3.20.4"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "uk.co.rafearnold"

application.mainClass = "uk.co.rafearnold.calendar.MainKt"

tasks.run<JavaExec> {
    environment("DB_URL", "jdbc:sqlite:calendar.db")
    environment("ASSET_DIRS", "data/assets,src/main/resources/assets")
    environment("HOT_RELOADING", "true")
    environment("ENABLE_AUTH", "false")
    environment("EARLIEST_DATE", "2024-07-15")
    File(".env").readLines().forEach { line ->
        val (key, value) = line.split('=')
        environment(key, value)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.http4k:http4k-bom:6.9.0.0"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-server-jetty")
    implementation("org.http4k:http4k-template-core")

    implementation("io.pebbletemplates:pebble:3.2.4")

    implementation("org.jooq:jooq:3.20.4")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    implementation("com.google.api-client:google-api-client:2.8.0")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.19.0")

    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("com.microsoft.playwright:playwright:1.52.0")
    testImplementation("org.wiremock:wiremock-jetty12:3.13.0")
    testImplementation("com.auth0:java-jwt:4.5.0")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.80")
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
    command = "@tailwindcss/cli"
    args = listOf(
        "-i", "./src/main/resources/input.css",
        "-o", "./src/main/resources/assets/index.min.css",
        "-m",
    )
    inputs.dir("./src/main/resources")
    outputs.dir("./src/main/resources/index.min.css")
    dependsOn("npmInstall")
}
