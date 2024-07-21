package uk.co.rafearnold.calendar

import org.slf4j.Logger
import org.slf4j.LoggerFactory

val logger: Logger = LoggerFactory.getLogger("main")

fun main() {
    startServer { "something sweet" }.block()
}
