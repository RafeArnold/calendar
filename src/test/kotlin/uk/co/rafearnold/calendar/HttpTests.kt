package uk.co.rafearnold.calendar

import org.http4k.core.Uri
import org.http4k.core.cookie.Cookie
import org.http4k.core.queries
import org.http4k.core.toParametersMap
import org.http4k.server.Http4kServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.sql.Statement
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
        server = startServer(auth = googleOauth(clientId = clientId))

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
        assertEquals(listOf(server.oauthUri(code = null).toASCIIString()), queryParameters["redirect_uri"])
        assertEquals(listOf(clientId), queryParameters["client_id"])
        assertEquals(listOf("openid profile email"), queryParameters["scope"])
    }

    @Test
    fun `only allowed users can authenticate`() {
        val clientId = UUID.randomUUID().toString()
        val clientSecret = UUID.randomUUID().toString()
        GoogleOAuthServer(clientId = clientId, clientSecret = clientSecret).use { authServer ->
            val allowedUserEmail1 = "imgood@example.com"
            val allowedUserEmail2 = "alloweduser@gmail.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(allowedUserEmail1, allowedUserEmail2))
            server = startServer(auth = auth)

            fun reset() {
                executeStatement { statement ->
                    @Suppress("SqlWithoutWhere")
                    statement.executeUpdate("DELETE FROM users")
                }
                assertEquals(0, dbUserCount())
                authServer.resetRequests()
            }

            fun assertAuthenticationSucceeds(email: String) {
                val authCode = UUID.randomUUID().toString()
                val response = login(email = email, authCode = authCode, authServer)
                assertEquals(302, response.statusCode())
                assertEquals("", response.body())
                assertEquals(listOf("/"), response.headers().allValues("location"))
                assertEquals(
                    listOf("id_token"),
                    response.headers().allValues("set-cookie").map { Cookie.parse(it)!!.name },
                )
                authServer.verifyTokenWasExchanged(
                    authCode = authCode,
                    redirectUri = server.oauthUri(code = null).toASCIIString(),
                )
                assertEquals(1, dbUserCount())
            }

            fun assertAuthenticationFails(email: String) {
                val authCode = UUID.randomUUID().toString()
                val response = login(email = email, authCode = authCode, authServer)
                assertEquals(403, response.statusCode())
                assertEquals("", response.body())
                assertEquals(emptyList(), response.headers().allValues("set-cookie"))
                authServer.verifyTokenWasExchanged(
                    authCode = authCode,
                    redirectUri = server.oauthUri(code = null).toASCIIString(),
                )
                assertEquals(0, dbUserCount())
            }

            assertAuthenticationSucceeds(allowedUserEmail1)
            reset()
            assertAuthenticationFails("bademail@example.com")
            reset()
            assertAuthenticationSucceeds(allowedUserEmail2)
            reset()
            assertAuthenticationFails("disalloweduser@gamil.com")
        }
    }

    @Test
    fun `logging in as the same user multiple times doesnt create multiple users`() {
        GoogleOAuthServer(
            clientId = UUID.randomUUID().toString(),
            clientSecret = UUID.randomUUID().toString(),
        ).use { authServer ->
            val userEmail = "test@gmail.com"
            server = startServer(auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail)))

            fun assertLoginSucceeds() {
                val response =
                    login(email = userEmail, authCode = UUID.randomUUID().toString(), authServer = authServer)
                assertEquals(302, response.statusCode())
                assertEquals(
                    listOf("id_token"),
                    response.headers().allValues("set-cookie").map { Cookie.parse(it)!!.name },
                )
            }

            assertEquals(0, dbUserCount())
            assertLoginSucceeds()
            assertEquals(1, dbUserCount())
            assertLoginSucceeds()
            assertEquals(1, dbUserCount())
        }
    }

    private fun getDay(day: LocalDate): HttpResponse<String> =
        httpClient.send(HttpRequest.newBuilder(server.dayUri(day)).GET().build(), BodyHandlers.ofString())

    private fun login(
        email: String,
        authCode: String,
        authServer: GoogleOAuthServer,
    ): HttpResponse<String> {
        authServer.stubTokenExchange(authCode = authCode, email = email, subject = UUID.randomUUID().toString())
        return httpClient.send(
            HttpRequest.newBuilder(server.oauthUri(code = authCode)).GET().build(),
            BodyHandlers.ofString(),
        )
    }

    private fun dbUserCount(): Int =
        executeStatement { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM users").use { result ->
                result.next()
                result.getInt(1)
            }
        }

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

    private fun googleOauth(
        tokenServerUrl: URI? = null,
        clientId: String,
        clientSecret: String = UUID.randomUUID().toString(),
        allowedUserEmails: Collection<String> = emptyList(),
    ) = GoogleOauth(
        serverBaseUrl = null,
        tokenServerUrl = tokenServerUrl,
        clientId = clientId,
        clientSecret = clientSecret,
        allowedUserEmails = allowedUserEmails,
    )

    private fun <T> executeStatement(execute: (Statement) -> T): T = executeStatement(dbUrl = dbUrl, execute)
}
