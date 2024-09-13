package uk.co.rafearnold.calendar

import org.http4k.core.Request
import org.http4k.core.Response
import java.security.SecureRandom
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun Clock.toDate(): LocalDate = LocalDate.ofInstant(instant(), zone)

fun Clock.now(): LocalDateTime = LocalDateTime.ofInstant(instant(), zone)

fun LocalDate.toYearMonth(): YearMonth = YearMonth.from(this)

val LocalDate.colorIndex: Int get() = (this.toEpochDay() % 17).toInt()

private val random = SecureRandom()

fun randomBytes(numBytes: Int): ByteArray = ByteArray(numBytes).apply { random.nextBytes(this) }

fun hmacSha256(
    data: ByteArray,
    key: ByteArray,
): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)

fun Request.isHtmx(): Boolean = header("hx-request") != null

fun htmxRedirect(location: String): (Response) -> Response = { it.header("hx-redirect", location) }
