package uk.co.rafearnold.calendar

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.http4k.base64DecodedArray
import org.http4k.base64Encode
import org.http4k.core.Uri
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.SameSite
import org.http4k.core.queries
import org.http4k.core.toParametersMap
import org.http4k.server.Http4kServer
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.security.interfaces.RSAPrivateKey
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.jvm.optionals.getOrNull
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun `user is redirected to login on entry`(url: String) {
        val clientId = UUID.randomUUID().toString()
        server = startServer(auth = googleOauth(clientId = clientId))

        val response = httpClient.send(HttpRequest.newBuilder(server.uri(url)).GET().build(), BodyHandlers.discarding())
        assertEquals(302, response.statusCode())
        val location = response.headers().firstValue("location").map { Uri.of(it) }.getOrNull()
        assertNotNull(location)
        assertEquals("https", location.scheme)
        assertEquals("accounts.google.com", location.authority)
        assertEquals("/o/oauth2/auth", location.path)
        val queryParameters = location.queries().toParametersMap()
        assertEquals(setOf("response_type", "redirect_uri", "client_id", "scope", "state"), queryParameters.keys)
        assertEquals(listOf("code"), queryParameters["response_type"])
        assertEquals(
            listOf(server.oauthUri(code = null, state = null).toASCIIString()),
            queryParameters["redirect_uri"],
        )
        assertEquals(listOf(clientId), queryParameters["client_id"])
        assertEquals(listOf("openid profile email"), queryParameters["scope"])
    }

    @Test
    fun `only allowed users can authenticate`() {
        GoogleOAuthServer().use { authServer ->
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
                    setOf("id_token", "auth_csrf_token"),
                    response.headers().allValues("set-cookie").map { Cookie.parse(it)!!.name }.toSet(),
                )
                authServer.verifyTokenWasExchanged(
                    authCode = authCode,
                    redirectUri = server.oauthUri(code = null, state = null).toASCIIString(),
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
                    redirectUri = server.oauthUri(code = null, state = null).toASCIIString(),
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
        GoogleOAuthServer().use { authServer ->
            val userEmail = "test@gmail.com"
            server = startServer(auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail)))

            fun assertLoginSucceeds() {
                val response =
                    login(email = userEmail, authCode = UUID.randomUUID().toString(), authServer = authServer)
                assertEquals(302, response.statusCode())
                assertEquals(
                    setOf("id_token", "auth_csrf_token"),
                    response.headers().allValues("set-cookie").map { Cookie.parse(it)!!.name }.toSet(),
                )
            }

            assertEquals(0, dbUserCount())
            assertLoginSucceeds()
            assertEquals(1, dbUserCount())
            assertLoginSucceeds()
            assertEquals(1, dbUserCount())
        }
    }

    @Test
    fun `auth redirect sets csrf token and sets state to token hash`() {
        GoogleOAuthServer().use { authServer ->
            val userEmail = "test@gmail.com"
            val tokenKey = Random.nextBytes(ByteArray(32))
            val auth =
                authServer.toAuthConfig(allowedUserEmails = listOf(userEmail), tokenHashKey = tokenKey)
            server = startServer(auth = auth)

            val redirectResponse =
                httpClient.send(HttpRequest.newBuilder(server.uri("/")).GET().build(), BodyHandlers.discarding())
            assertEquals(302, redirectResponse.statusCode())

            // A CSRF token is set.
            val tokenCookies =
                redirectResponse.headers().allValues("set-cookie")
                    .map { Cookie.parse(it)!! }.filter { it.name == "auth_csrf_token" }
            assertEquals(1, tokenCookies.size)
            val tokenCookie = tokenCookies[0]
            val token = tokenCookie.value
            val tokenBytes = assertDoesNotThrow { token.base64DecodedArray() }
            assertEquals(32, tokenBytes.size)
            assertEquals(300, tokenCookie.maxAge)
            assertTrue(tokenCookie.httpOnly)
            assertEquals(SameSite.Lax, tokenCookie.sameSite)

            val tokenHash = hmacSha256(tokenKey = tokenKey, tokenBytes = tokenBytes).base64Encode()

            // Redirect contains token hash.
            val location = redirectResponse.headers().firstValue("location").map { Uri.of(it) }.getOrNull()
            assertNotNull(location)
            val queryParameters = location.queries().toParametersMap()
            val state = queryParameters["state"]?.filterNotNull()
            assertNotNull(state)
            assertEquals(1, state.size)
            assertEquals(tokenHash, state[0])
        }
    }

    @Test
    fun `state value is validated during authentication`() {
        GoogleOAuthServer().use { authServer ->
            val userEmail = "test@gmail.com"
            val tokenKey = Random.nextBytes(ByteArray(32))
            val auth =
                authServer.toAuthConfig(allowedUserEmails = listOf(userEmail), tokenHashKey = tokenKey)
            server = startServer(auth = auth)

            fun assertAuthenticationFails(
                csrfToken: String?,
                state: String?,
                authServer: GoogleOAuthServer,
            ) {
                val authCode = UUID.randomUUID().toString()
                val response =
                    login(email = userEmail, authCode = authCode, csrfToken = csrfToken, state = state, authServer)
                assertEquals(403, response.statusCode())
                assertEquals("", response.body())
                assertEquals(emptyList(), response.headers().allValues("set-cookie"))
                assertEquals(0, authServer.allTokenExchangeServedRequests().size)
                assertEquals(0, dbUserCount())
            }

            fun assertAuthenticationSucceeds(
                csrfToken: String,
                state: String,
                authServer: GoogleOAuthServer,
            ) {
                val authCode = UUID.randomUUID().toString()
                val response =
                    login(email = userEmail, authCode = authCode, csrfToken = csrfToken, state = state, authServer)
                assertEquals(302, response.statusCode())
                assertEquals("", response.body())
                assertEquals(listOf("/"), response.headers().allValues("location"))
                val setCookies = response.headers().allValues("set-cookie").map { Cookie.parse(it)!! }
                assertEquals(setOf("id_token", "auth_csrf_token"), setCookies.map { it.name }.toSet())
                assertEquals(1, setCookies.filter { it.name == "auth_csrf_token" }.size)
                val csrfTokenCookie = setCookies.first { it.name == "auth_csrf_token" }
                assertEquals(0, csrfTokenCookie.maxAge)
                assertEquals(Instant.EPOCH, csrfTokenCookie.expires)
                assertEquals("", csrfTokenCookie.value)
                authServer.verifyTokenWasExchanged(
                    authCode = authCode,
                    redirectUri = server.oauthUri(code = null, state = null).toASCIIString(),
                )
                assertEquals(1, dbUserCount())
            }

            val csrfToken = Random.nextBytes(ByteArray(32))
            val tokenHash = hmacSha256(tokenKey = tokenKey, tokenBytes = csrfToken)

            // Auth code request without state is rejected.
            assertAuthenticationFails(csrfToken = csrfToken.base64Encode(), state = null, authServer)
            // Auth code request with empty state is rejected.
            assertAuthenticationFails(csrfToken = csrfToken.base64Encode(), state = "", authServer)
            // Auth code request with state containing invalid base64 is rejected.
            assertAuthenticationFails(csrfToken = csrfToken.base64Encode(), state = "not base64", authServer)
            // Auth code request with state containing token hash signed with incorrect key is rejected.
            assertAuthenticationFails(
                csrfToken = csrfToken.base64Encode(),
                state = hmacSha256(tokenKey = Random.nextBytes(ByteArray(32)), tokenBytes = csrfToken).base64Encode(),
                authServer,
            )
            // Auth code request with state containing incorrect token hash signed with correct key is rejected.
            assertAuthenticationFails(
                csrfToken = csrfToken.base64Encode(),
                state = hmacSha256(tokenKey = tokenKey, tokenBytes = Random.nextBytes(ByteArray(32))).base64Encode(),
                authServer,
            )
            // Auth code request without token cookie is rejected.
            assertAuthenticationFails(csrfToken = null, state = tokenHash.base64Encode(), authServer)
            // Auth code request with empty token cookie is rejected.
            assertAuthenticationFails(csrfToken = "", state = tokenHash.base64Encode(), authServer)
            // Auth code request with token cookie containing invalid base64 is rejected.
            assertAuthenticationFails(csrfToken = "not base64", state = tokenHash.base64Encode(), authServer)
            // Auth code request with token cookie containing incorrect token is rejected.
            assertAuthenticationFails(
                csrfToken = Random.nextBytes(ByteArray(32)).base64Encode(),
                state = tokenHash.base64Encode(),
                authServer,
            )
            // Auth code request with correct token and state succeeds.
            assertAuthenticationSucceeds(
                csrfToken = csrfToken.base64Encode(),
                state = tokenHash.base64Encode(),
                authServer,
            )
        }
    }

    @Test
    fun `auth code requests that contain no code are rejected`() {
        GoogleOAuthServer().use { authServer ->
            val userEmail = "test@example.com"
            val tokenKey = Random.nextBytes(ByteArray(32))
            val auth = googleOauth(allowedUserEmails = listOf(userEmail), tokenHashKey = tokenKey)
            server = startServer(auth = auth)
            val csrfToken = Random.nextBytes(ByteArray(32))
            val tokenHash = hmacSha256(tokenKey = tokenKey, tokenBytes = csrfToken)
            val response =
                httpClient.send(
                    HttpRequest.newBuilder(server.oauthUri(code = null, state = tokenHash.base64Encode()))
                        .header("cookie", Cookie("auth_csrf_token", csrfToken.base64Encode()).keyValueCookieString())
                        .GET()
                        .build(),
                    BodyHandlers.ofString(),
                )
            assertEquals(403, response.statusCode())
            assertEquals("", response.body())
            assertEquals(emptyList(), response.headers().allValues("set-cookie"))
            assertEquals(0, authServer.allTokenExchangeServedRequests().size)
            assertEquals(0, dbUserCount())
        }
    }

    @Test
    fun `invalid id tokens are rejected`() {
        val clientId = UUID.randomUUID().toString()
        GoogleOAuthServer(clientId = clientId).use { authServer ->
            val userEmail = "test@example.com"
            val userGoogleSubjectId = UUID.randomUUID().toString()
            val otherUserEmail = "other@example.com"
            val otherUserGoogleSubjectId = UUID.randomUUID().toString()
            server = startServer(auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail)))

            // Make sure the users already exists in the db.
            DSL.using(dbUrl).use {
                val userRepo = UserRepository(it)
                userRepo.createUserIfNoneExists(email = userEmail, googleSubjectId = userGoogleSubjectId)
                userRepo.createUserIfNoneExists(email = otherUserEmail, googleSubjectId = otherUserGoogleSubjectId)
            }

            fun sendRequest(idToken: String): HttpResponse<String> {
                val request =
                    HttpRequest.newBuilder(server.uri("/"))
                        .header("cookie", Cookie("id_token", idToken).keyValueCookieString())
                        .build()
                return httpClient.send(request, BodyHandlers.ofString())
            }

            fun assertRequestReturnsRedirect(idToken: String) {
                val response = sendRequest(idToken)
                assertEquals(302, response.statusCode())
                val location = response.headers().firstValue("location").getOrNull()
                assertNotNull(location)
                assertTrue(location.startsWith(authServer.authenticationPageUrl))
            }

            fun assertRequestIsForbidden(idToken: String) {
                val response = sendRequest(idToken)
                assertEquals(403, response.statusCode())
                assertEquals("", response.body())
            }

            fun assertRequestSucceeds(idToken: String) {
                val response = sendRequest(idToken)
                assertEquals(200, response.statusCode())
            }

            fun idToken(
                issuedAt: Instant = Instant.now(),
                expiresAt: Instant = Instant.now().plusSeconds(3920),
                issuer: String = listOf("accounts.google.com", "https://accounts.google.com").shuffled()[0],
                audience: String = clientId,
                subject: String = userGoogleSubjectId,
                email: String = userEmail,
                signingKey: RSAPrivateKey = authServer.certPrivateKey,
            ): String =
                JWT.create()
                    .withIssuedAt(issuedAt)
                    .withExpiresAt(expiresAt)
                    .withIssuer(issuer)
                    .withAudience(audience)
                    .withSubject(subject)
                    .withClaim("email", email)
                    .sign(Algorithm.RSA256(signingKey))

            // Empty ID token.
            assertRequestIsForbidden(idToken = "")
            // Invalid base64.
            assertRequestIsForbidden(idToken = "not base64")
            // Invalid JSON.
            assertRequestIsForbidden(
                idToken = "not json".base64Encode() + "." + "not json".base64Encode() + "." + "whatever".base64Encode(),
            )
            // Invalid JWT format.
            assertRequestIsForbidden(
                idToken = "{}".base64Encode() + "." + "{}".base64Encode() + "." + "whatever".base64Encode(),
            )
            // Invalid signature.
            assertRequestReturnsRedirect(idToken = idToken().replaceAfterLast('.', "invalid signature".base64Encode()))
            // Signed with incorrect key.
            assertRequestReturnsRedirect(idToken = idToken(signingKey = generateRsaKeyPair().private))
            // Expired ID token.
            assertRequestReturnsRedirect(idToken = idToken(expiresAt = Instant.now().minusSeconds(300)))
            // Future issued-at time.
            assertRequestReturnsRedirect(idToken = idToken(issuedAt = Instant.now().plusSeconds(301)))
            // Incorrect issuer.
            assertRequestReturnsRedirect(idToken = idToken(issuer = "https://not.google.com"))
            // Incorrect audience.
            assertRequestReturnsRedirect(idToken = idToken(audience = UUID.randomUUID().toString()))
            // Unrecognised subject.
            assertRequestIsForbidden(idToken = idToken(subject = UUID.randomUUID().toString()))
            // Unrecognised email.
            assertRequestIsForbidden(idToken = idToken(email = "whoami@example.com"))
            // Disallowed email.
            assertRequestIsForbidden(idToken = idToken(subject = otherUserGoogleSubjectId, email = otherUserEmail))
            // Valid ID token.
            assertRequestSucceeds(idToken = idToken())
        }
    }

    private fun getDay(day: LocalDate): HttpResponse<String> =
        httpClient.send(HttpRequest.newBuilder(server.dayUri(day)).GET().build(), BodyHandlers.ofString())

    private fun login(
        email: String,
        authCode: String,
        authServer: GoogleOAuthServer,
    ): HttpResponse<String> {
        val response = httpClient.send(HttpRequest.newBuilder(server.uri("/")).GET().build(), BodyHandlers.discarding())
        assertEquals(302, response.statusCode())
        val tokenCookies =
            response.headers().allValues("set-cookie")
                .map { Cookie.parse(it)!! }.filter { it.name == "auth_csrf_token" }
        assertEquals(1, tokenCookies.size)
        val tokenCookie = tokenCookies[0]
        val csrfToken = tokenCookie.value
        val state =
            response.headers().firstValue("location").map { Uri.of(it) }.get()
                .queries().toParametersMap()["state"]?.first()!!
        return login(email = email, authCode = authCode, csrfToken = csrfToken, state = state, authServer = authServer)
    }

    private fun login(
        email: String,
        authCode: String,
        csrfToken: String?,
        state: String?,
        authServer: GoogleOAuthServer,
    ): HttpResponse<String> {
        authServer.stubTokenExchange(authCode = authCode, email = email, subject = UUID.randomUUID().toString())
        return httpClient.send(
            HttpRequest.newBuilder(server.oauthUri(code = authCode, state = state))
                .run {
                    if (csrfToken != null) {
                        header("cookie", Cookie(name = "auth_csrf_token", value = csrfToken).keyValueCookieString())
                    } else {
                        this
                    }
                }
                .GET()
                .build(),
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
        clientId: String = UUID.randomUUID().toString(),
        clientSecret: String = UUID.randomUUID().toString(),
        allowedUserEmails: Collection<String> = emptyList(),
        tokenHashKey: ByteArray = Random.nextBytes(ByteArray(32)),
    ) = GoogleOauth(
        serverBaseUrl = null,
        authServerUrl = null,
        tokenServerUrl = tokenServerUrl,
        publicCertsUrl = null,
        clientId = clientId,
        clientSecret = clientSecret,
        allowedUserEmails = allowedUserEmails,
        tokenHashKeyBase64 = tokenHashKey.base64Encode(),
    )

    private fun <T> executeStatement(execute: (Statement) -> T): T = executeStatement(dbUrl = dbUrl, execute)
}
