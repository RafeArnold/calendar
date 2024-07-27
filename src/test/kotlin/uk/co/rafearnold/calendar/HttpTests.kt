package uk.co.rafearnold.calendar

import org.http4k.server.Http4kServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals

class HttpTests {
    private lateinit var server: Http4kServer
    private lateinit var dbUrl: String
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun startupEach() {
        val dbFile = Files.createTempFile("calendar", ".db").also { it.toFile().deleteOnExit() }
        dbUrl = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
    }

    @AfterEach
    fun tearEachDown() {
        server.stop()
    }

    @Test
    fun `only allows retrieval of days in the past`() {
        val today = LocalDate.of(2024, 7, 15)
        server = startServer(port = 0, clock = today.toClock(), dbUrl = dbUrl) { "whatever" }
        assertEquals(200, getDay(today).statusCode())
        assertEquals(200, getDay(today.minusDays(1)).statusCode())
        assertEquals(200, getDay(today.minusMonths(1).plusDays(1)).statusCode())
        assertEquals(200, getDay(today.minusYears(1).plusDays(1)).statusCode())
        getDay(today.plusDays(1)).also {
            assertEquals(403, it.statusCode())
            assertEquals("", it.body())
        }
        getDay(today.plusMonths(1).minusDays(1)).also {
            assertEquals(403, it.statusCode())
            assertEquals("", it.body())
        }
        getDay(today.plusYears(1).minusDays(1)).also {
            assertEquals(403, it.statusCode())
            assertEquals("", it.body())
        }
    }

    private fun getDay(day: LocalDate): HttpResponse<String> =
        httpClient.send(HttpRequest.newBuilder(server.dayUri(day)).GET().build(), BodyHandlers.ofString())
}

private fun Http4kServer.dayUri(day: LocalDate): URI = uri("/day/${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}")

private fun Http4kServer.uri(path: String): URI = URI.create("http://localhost:${port()}").resolve(path)
