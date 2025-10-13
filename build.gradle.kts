import com.github.gradle.node.npm.task.NpxTask
import org.jmailen.gradle.kotlinter.tasks.LintTask
import java.nio.file.Files
import java.nio.file.Path

buildscript {
    dependencies {
        classpath("org.xerial:sqlite-jdbc:3.50.3.0")
    }
}

plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jmailen.kotlinter") version "5.2.0"
    id("com.github.node-gradle.node") version "7.1.0"
    id("org.flywaydb.flyway") version "11.13.2"
    id("org.jooq.jooq-codegen-gradle") version "3.20.8"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

group = "uk.co.rafearnold"

val runningInCI: Boolean = System.getenv("CI") == "true"

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
    implementation(platform("org.http4k:http4k-bom:6.18.0.1"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-server-jetty")
    implementation("org.http4k:http4k-template-core")

    implementation("io.pebbletemplates:pebble:3.2.4")

    implementation("org.jooq:jooq:3.20.7")
    implementation("org.flywaydb:flyway-core:11.14.0")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    implementation("com.google.api-client:google-api-client:2.8.1")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")

    implementation("ch.qos.logback:logback-classic:1.5.18")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("com.microsoft.playwright:playwright:1.55.0")
    testImplementation("org.wiremock:wiremock:4.0.0-beta.15")
    testRuntimeOnly("org.eclipse.jetty.ee10:jetty-ee10-servlet:12.1.1")
    testImplementation("com.auth0:java-jwt:4.5.0")
    testImplementation("org.bouncycastle:bcpkix-jdk18on:1.82")
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

node {
    if (!runningInCI) {
        val nodeBin = System.getenv("NVM_BIN")
        npmCommand = "$nodeBin/npm"
        npxCommand = "$nodeBin/npx"
    }
}

task("buildCss", NpxTask::class) {
    command = "@tailwindcss/cli"
    args = listOf(
        "-i", "./src/main/resources/input.css",
        "-o", "./src/main/resources/assets/index.min.css",
        "-m",
    )
    inputs.files(fileTree("./src/main/resources") { include("*.html", "*.css") })
    outputs.file("./src/main/resources/assets/index.min.css")
    dependsOn("npmInstall")
}
