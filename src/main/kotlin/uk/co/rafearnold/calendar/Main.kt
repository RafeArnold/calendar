package uk.co.rafearnold.calendar

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Clock

val logger: Logger = LoggerFactory.getLogger("main")

fun main() {
    Config(
        port = 8080,
        clock = Clock.systemUTC(),
        dbUrl = "jdbc:sqlite:calendar.db",
        assetsDir = "src/main/resources/assets",
    ) { "something sweet" }
        .startServer().block()
}
