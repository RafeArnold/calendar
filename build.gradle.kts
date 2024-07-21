import com.github.gradle.node.npm.task.NpxTask
import org.jmailen.gradle.kotlinter.tasks.LintTask
import java.nio.file.Files
import java.nio.file.Path

buildscript {
    dependencies {
        classpath("org.xerial:sqlite-jdbc:3.46.0.0")
    }
}

plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jmailen.kotlinter") version "4.4.1"
    id("com.github.node-gradle.node") version "7.0.2"
    id("org.flywaydb.flyway") version "10.0.0"
    id("org.jooq.jooq-codegen-gradle") version "3.19.10"
    application
}

group = "uk.co.rafearnold"

application.mainClass = "uk.co.rafearnold.calendar.MainKt"

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.http4k:http4k-bom:5.26.0.0"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-server-jetty")
    implementation("org.http4k:http4k-template-core")

    implementation("io.pebbletemplates:pebble:3.2.2")

    implementation("org.jooq:jooq:3.19.10")
    implementation("org.flywaydb:flyway-core:10.16.0")
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")

    implementation("ch.qos.logback:logback-classic:1.5.6")

    testImplementation(kotlin("test"))
    testImplementation("com.microsoft.playwright:playwright:1.45.0")
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
}
