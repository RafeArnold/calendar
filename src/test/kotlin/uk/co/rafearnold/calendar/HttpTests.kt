package uk.co.rafearnold.calendar

import org.http4k.core.Uri
import org.http4k.core.queries
import org.http4k.core.toParametersMap
import org.http4k.server.Http4kServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.jvm.optionals.getOrNull
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
        var today = LocalDate.of(2024, 7, 15)
        val clock = today.toMutableClock()
        server = startServer(clock = clock)

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

        today = today.plusMonths(3).minusDays(6)
        clock.del = today.toClock()

        assertEquals(200, getDay(today).statusCode())
        getDay(today.plusDays(1)).also {
            assertEquals(403, it.statusCode())
            assertEquals("", it.body())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["/", "/day/2024-08-08", "/days"])
    fun `user is redirected to login on entry`() {
        val clientId = UUID.randomUUID().toString()
        server = startServer(auth = googleOauth(clientId))

        val response = httpClient.send(HttpRequest.newBuilder(server.uri("/")).GET().build(), BodyHandlers.discarding())
        assertEquals(302, response.statusCode())
        val location = response.headers().firstValue("location").map { Uri.of(it) }.getOrNull()
        assertNotNull(location)
        assertEquals("https", location.scheme)
        assertEquals("accounts.google.com", location.authority)
        assertEquals("/o/oauth2/auth", location.path)
        val queryParameters = location.queries().toParametersMap()
        assertEquals(setOf("response_type", "redirect_uri", "client_id", "scope"), queryParameters.keys)
        assertEquals(listOf("code"), queryParameters["response_type"])
        assertEquals(listOf(server.uri("/oauth/code").toASCIIString()), queryParameters["redirect_uri"])
        assertEquals(listOf(clientId), queryParameters["client_id"])
        assertEquals(listOf("openid profile email"), queryParameters["scope"])
    }

    private fun getDay(day: LocalDate): HttpResponse<String> =
        httpClient.send(HttpRequest.newBuilder(server.dayUri(day)).GET().build(), BodyHandlers.ofString())

    private fun startServer(
        clock: Clock = Clock.systemUTC(),
        auth: AuthConfig = NoAuth,
    ): Http4kServer =
        Config(
            port = 0,
            clock = clock,
            dbUrl = dbUrl,
            assetsDir = "src/main/resources/assets",
            auth = auth,
        ) { "whatever" }.startServer()

    private fun googleOauth(clientId: String) =
        GoogleOauth(
            serverBaseUrl = null,
            tokenServerUrl = null,
            clientId = clientId,
            clientSecret = UUID.randomUUID().toString(),
        )
}
