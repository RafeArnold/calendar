package uk.co.rafearnold.calendar

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

fun LocalDate.toMutableClock(): MutableClock = toClock().mutable()

fun YearMonth.toClock(): Clock = atDay(Random.nextInt(1, lengthOfMonth())).toClock()

fun LocalDate.toClock(): Clock = Clock.fixed(atTime(LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

fun Clock.mutable() = MutableClock(this)

class MutableClock(var del: Clock) : Clock() {
    override fun instant(): Instant = del.instant()

    override fun millis(): Long = del.millis()

    override fun withZone(zone: ZoneId): Clock = del.withZone(zone)

    override fun getZone(): ZoneId = del.zone
}

class MapBackedMessageLoader(private val messages: Map<LocalDate, String>) : MessageLoader {
    override fun get(date: LocalDate): String? = messages[date]
}
