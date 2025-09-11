package uk.co.rafearnold.calendar

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.http4k.base64Decoded
import org.http4k.base64DecodedArray
import org.http4k.base64Encode
import org.http4k.core.Uri
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.SameSite
import org.http4k.core.queries
import org.http4k.core.toParametersMap
import org.http4k.core.toUrlFormEncoded
import org.http4k.routing.ResourceLoader
import org.http4k.server.Http4kServer
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Statement
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.jvm.optionals.getOrNull
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    @CsvSource(
        """
            /, GET
            /day/2024-08-08, GET
            /days, GET
            /previous-days, GET
            /impersonate, POST
        """,
    )
    fun `user is redirected to login on entry`(
        url: String,
        method: String,
    ) {
        val clientId = UUID.randomUUID().toString()
        server = startServer(auth = googleOauth(clientId = clientId))

        val request = HttpRequest.newBuilder(server.uri(url)).method(method, BodyPublishers.noBody()).build()
        val response = httpClient.send(request, BodyHandlers.discarding())
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
    fun `authenticated users are given a session cookie on login`() {
        GoogleOAuthServer().use { authServer ->
            val userEmail = "test@example.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail))
            server = startServer(auth = auth)

            val authCode = UUID.randomUUID().toString()
            val response = login(email = userEmail, authCode = authCode, authServer)
            assertEquals(302, response.statusCode())
            assertEquals("", response.body())
            assertEquals(listOf("/"), response.headers().allValues("location"))
            authServer.verifyTokenWasExchanged(
                authCode = authCode,
                redirectUri = server.oauthUri(code = null, state = null).toASCIIString(),
            )
            val sessionCookie = response.sessionCookie()
            assertEquals(SameSite.Lax, sessionCookie.sameSite)
            assertTrue(sessionCookie.httpOnly)
            assertTrue(sessionCookie.secure)
            assertNull(sessionCookie.domain)
            assertEquals("/", sessionCookie.path)
            assertNull(sessionCookie.expires)
            assertNull(sessionCookie.maxAge)
            assertTrue(sessionCookie.value.matches("[a-zA-Z0-9+/=]{24}".toRegex()))

            val request =
                HttpRequest.newBuilder(server.uri("/"))
                    .header("cookie", Cookie("user_session", sessionCookie.value).keyValueCookieString())
                    .build()
            assertEquals(200, httpClient.send(request, BodyHandlers.ofString()).statusCode())
        }
    }

    @Test
    fun `re-authenticating regenerates session cookie`() {
        GoogleOAuthServer().use { authServer ->
            val userEmail = "test@example.com"
            val tokenKey = Random.nextBytes(ByteArray(32))
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail))
            server = startServer(auth = auth, tokenHashKey = tokenKey)

            fun sendRequest(session: String): HttpResponse<String> {
                val request =
                    HttpRequest.newBuilder(server.uri("/"))
                        .header("cookie", Cookie("user_session", session).keyValueCookieString())
                        .build()
                return httpClient.send(request, BodyHandlers.ofString())
            }

            val loginResponse1 = login(email = userEmail, authServer = authServer)
            assertEquals(302, loginResponse1.statusCode())
            assertEquals(listOf("/"), loginResponse1.headers().allValues("location"))
            val session1 = loginResponse1.sessionCookie().value

            assertEquals(200, sendRequest(session = session1).statusCode())

            val csrfToken = Random.nextBytes(ByteArray(32))
            val tokenHash = hmacSha256(tokenKey = tokenKey, tokenBytes = csrfToken)

            // Login again and provide the previous session cookie.
            val loginResponse2 =
                login(
                    email = userEmail,
                    authCode = UUID.randomUUID().toString(),
                    csrfToken = csrfToken.base64Encode(),
                    state = tokenHash.base64Encode(),
                    session = session1,
                    authServer = authServer,
                )
            assertEquals(302, loginResponse2.statusCode())
            assertEquals(listOf("/"), loginResponse2.headers().allValues("location"))
            val session2 = loginResponse2.sessionCookie().value

            assertNotEquals(session1, session2)

            val response = sendRequest(session = session1)
            assertEquals(302, response.statusCode())
            val location = response.headers().firstValue("location").map { Uri.of(it) }.getOrNull()
            assertEquals(authServer.authenticationPageUrl, location?.copy(query = "")?.toString())
            assertEquals(200, sendRequest(session = session2).statusCode())
        }
    }

    @Test
    fun `logging out removes session cookie`() {
        GoogleOAuthServer().use { authServer ->
            val userEmail = "test@example.com"
            val tokenKey = Random.nextBytes(ByteArray(32))
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail))
            server = startServer(auth = auth, tokenHashKey = tokenKey)

            fun sendRequest(session: String): HttpResponse<String> {
                val request =
                    HttpRequest.newBuilder(server.uri("/"))
                        .header("cookie", Cookie("user_session", session).keyValueCookieString())
                        .build()
                return httpClient.send(request, BodyHandlers.ofString())
            }

            val loginResponse1 = login(email = userEmail, authServer = authServer)
            assertEquals(302, loginResponse1.statusCode())
            assertEquals(listOf("/"), loginResponse1.headers().allValues("location"))
            val session = loginResponse1.sessionCookie().value

            assertEquals(200, sendRequest(session = session).statusCode())

            val logoutResponse = logout(session = session)
            assertEquals("", logoutResponse.sessionCookie().value)

            val response = sendRequest(session = session)
            assertEquals(302, response.statusCode())
            val location = response.headers().firstValue("location").map { Uri.of(it) }.getOrNull()
            assertEquals(authServer.authenticationPageUrl, location?.copy(query = "")?.toString())
        }
    }

    @Test
    fun `sessions expire if no requests are received for awhile`() {
        val startTime = LocalDateTime.of(2024, 12, 1, 15, 7, 32)
        val clock = startTime.toClock().mutable()
        GoogleOAuthServer(clock).use { authServer ->
            val userEmail = "test@example.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail))
            server = startServer(clock = clock, auth = auth)

            fun sendRequest(session: String): HttpResponse<String> {
                val request =
                    HttpRequest.newBuilder(server.uri("/"))
                        .header("cookie", Cookie("user_session", session).keyValueCookieString())
                        .build()
                return httpClient.send(request, BodyHandlers.ofString())
            }

            val loginResponse1 = login(email = userEmail, authServer = authServer)
            assertEquals(302, loginResponse1.statusCode())
            assertEquals(listOf("/"), loginResponse1.headers().allValues("location"))
            val session1 = loginResponse1.sessionCookie().value

            assertEquals(200, sendRequest(session = session1).statusCode())

            clock.fastForward(Duration.ofDays(7))

            run {
                val response = sendRequest(session = session1)
                assertEquals(302, response.statusCode())
                val location = response.headers().firstValue("location").map { Uri.of(it) }.getOrNull()
                assertEquals(authServer.authenticationPageUrl, location?.copy(query = "")?.toString())
            }

            val loginResponse2 = login(email = userEmail, authServer = authServer)
            assertEquals(302, loginResponse2.statusCode())
            assertEquals(listOf("/"), loginResponse2.headers().allValues("location"))
            val session2 = loginResponse2.sessionCookie().value

            assertEquals(200, sendRequest(session = session2).statusCode())

            clock.fastForward(Duration.ofDays(7).minusMinutes(10))

            assertEquals(200, sendRequest(session = session2).statusCode())

            clock.fastForward(Duration.ofDays(7).minusMinutes(10))

            assertEquals(200, sendRequest(session = session2).statusCode())

            clock.fastForward(Duration.ofDays(7))

            run {
                val response = sendRequest(session = session2)
                assertEquals(302, response.statusCode())
                val location = response.headers().firstValue("location").map { Uri.of(it) }.getOrNull()
                assertEquals(authServer.authenticationPageUrl, location?.copy(query = "")?.toString())
            }
        }
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
                    setOf("user_session", "auth_csrf_token"),
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
                    setOf("user_session", "auth_csrf_token"),
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
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail))
            server = startServer(auth = auth, tokenHashKey = tokenKey)

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
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail))
            server = startServer(auth = auth, tokenHashKey = tokenKey)

            fun assertAuthenticationFails(
                csrfToken: String?,
                state: String?,
                authServer: GoogleOAuthServer,
            ) {
                val authCode = UUID.randomUUID().toString()
                val response =
                    login(
                        email = userEmail,
                        authCode = authCode,
                        csrfToken = csrfToken,
                        state = state,
                        authServer = authServer,
                    )
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
                    login(
                        email = userEmail,
                        authCode = authCode,
                        csrfToken = csrfToken,
                        state = state,
                        authServer = authServer,
                    )
                assertEquals(302, response.statusCode())
                assertEquals("", response.body())
                assertEquals(listOf("/"), response.headers().allValues("location"))
                val setCookies = response.headers().allValues("set-cookie").map { Cookie.parse(it)!! }
                assertEquals(setOf("user_session", "auth_csrf_token"), setCookies.map { it.name }.toSet())
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
            val auth = googleOauth(allowedUserEmails = listOf(userEmail))
            server = startServer(auth = auth, tokenHashKey = tokenKey)
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
    fun `assets can be loaded from a hierarchy of directories`(
        @TempDir assetsDir1: Path,
        @TempDir assetsDir2: Path,
    ) {
        val monthImagesDir1 = assetsDir1.resolve("month-images").apply { Files.createDirectories(this) }
        val monthImagesDir2 = assetsDir2.resolve("month-images").apply { Files.createDirectories(this) }
        copyImage("cat-1.jpg", monthImagesDir1.resolve("2024-07.jpg"))
        copyImage("cat-2.jpg", monthImagesDir2.resolve("2024-07.jpg"))
        copyImage("cat-3.jpg", monthImagesDir2.resolve("2024-08.jpg"))
        // Create a file with the same path as one in the classpath to test they can be overridden.
        assertNotNull(javaClass.getResource("/assets/index.min.css"))
        val indexCssContent = UUID.randomUUID().toString()
        assetsDir1.resolve("index.min.css").writeText(indexCssContent)
        val assetLoader =
            ChainResourceLoader(
                listOf(
                    ResourceLoader.Directory(baseDir = assetsDir1.toString()),
                    ResourceLoader.Directory(baseDir = assetsDir2.toString()),
                    ResourceLoader.Classpath(basePackagePath = "/assets"),
                ),
            )
        server = startServer(assetLoader = assetLoader)

        fun assertAssetIsFrom(
            path: String,
            directory: Path,
        ) {
            val request = HttpRequest.newBuilder(server.uri("/assets/$path")).GET().build()
            val response = httpClient.send(request, BodyHandlers.ofByteArray())
            val expectedBytes = directory.resolve(path).readBytes()
            assertContentEquals(expectedBytes, response.body())
        }

        assertAssetIsFrom("month-images/2024-07.jpg", assetsDir1)
        assertAssetIsFrom("month-images/2024-08.jpg", assetsDir2)
        assertAssetIsFrom("index.min.css", assetsDir1)
    }

    @Test
    fun `oauth callback base url is configurable`() {
        val serverBaseUrl = URI("https://example.com/test")
        server = startServer(auth = googleOauth(serverBaseUrl = serverBaseUrl))

        val response = httpClient.send(HttpRequest.newBuilder(server.uri("/")).GET().build(), BodyHandlers.discarding())
        assertEquals(302, response.statusCode())
        val location = response.headers().firstValue("location").map { Uri.of(it) }.getOrNull()
        assertNotNull(location)
        val queryParameters = location.queries().toParametersMap()
        assertEquals(listOf(serverBaseUrl.resolve("/oauth/code").toASCIIString()), queryParameters["redirect_uri"])
    }

    @Test
    fun `impersonating sets cookie`() {
        val now = LocalDateTime.of(2024, 8, 26, 10, 43, 27)
        GoogleOAuthServer(clock = now.toClock()).use { authServer ->
            val impersonatorEmail = "admin@gmail.com"
            val impersonatedUserEmail = "test@gmail.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(impersonatorEmail, impersonatedUserEmail))
            val tokenHashKey = Random.nextBytes(32)
            server =
                startServer(
                    clock = now.toClock(),
                    auth = auth,
                    adminEmails = listOf(impersonatorEmail),
                    tokenHashKey = tokenHashKey,
                )

            // Make sure the impersonated user already exists in the db.
            DSL.using(dbUrl).use {
                UserRepository(it).createUserIfNoneExists(
                    email = impersonatedUserEmail,
                    googleSubjectId = UUID.randomUUID().toString(),
                )
            }

            val sessionCookie = login(email = impersonatorEmail, authServer = authServer).sessionCookie()
            val response = impersonate(emailToImpersonate = impersonatedUserEmail, sessionCookie = sessionCookie)
            assertEquals(200, response.statusCode())
            assertEquals("", response.body())
            assertEquals(listOf("/"), response.headers().allValues("hx-redirect"))
            val setCookies = response.headers().allValues("set-cookie").map { Cookie.parse(it)!! }
            assertEquals(setOf("impersonation_token"), setCookies.map { it.name }.toSet())
            assertEquals(1, setCookies.filter { it.name == "impersonation_token" }.size)
            val impersonationCookie = setCookies.first { it.name == "impersonation_token" }
            val impersonationToken = impersonationCookie.value
            assertEquals(2, impersonationToken.split('.').size)
            val (tokenPayload, tokenSignature) = impersonationToken.split('.')
            val parsedPayload = ObjectMapper().readValue<Map<String, Any>>(tokenPayload.base64Decoded())
            val expectedExpirySeconds = now.plusHours(1).toEpochSecond(ZoneOffset.UTC)
            assertEquals(
                mapOf(
                    "impersonator" to impersonatorEmail,
                    "impersonated" to impersonatedUserEmail,
                    "exp" to expectedExpirySeconds.toInt(),
                ),
                parsedPayload,
            )
            assertContentEquals(
                hmacSha256(tokenKey = tokenHashKey, tokenBytes = tokenPayload.toByteArray()),
                tokenSignature.base64DecodedArray(),
            )
            assertEquals(1800, impersonationCookie.maxAge)
            assertNull(impersonationCookie.expires)
            assertNull(impersonationCookie.domain)
            assertEquals("/", impersonationCookie.path)
            assertTrue(impersonationCookie.secure)
            assertTrue(impersonationCookie.httpOnly)
            assertEquals(SameSite.Strict, impersonationCookie.sameSite)
        }
    }

    @Test
    fun `non-impersonators cannot impersonate`() {
        val impersonatorEmail = "admin@example.com"
        val otherUserEmail1 = "test@gmail.com"
        val otherUserEmail2 = "me@example.com"
        GoogleOAuthServer().use { authServer ->
            val auth =
                authServer.toAuthConfig(allowedUserEmails = listOf(impersonatorEmail, otherUserEmail1, otherUserEmail2))
            server = startServer(auth = auth, adminEmails = listOf(impersonatorEmail))

            val impersonatedSessionCookie = login(email = otherUserEmail1, authServer = authServer).sessionCookie()
            val impersonatorSessionCookie = login(email = impersonatorEmail, authServer = authServer).sessionCookie()

            run {
                val response =
                    impersonate(emailToImpersonate = otherUserEmail2, sessionCookie = impersonatedSessionCookie)
                assertEquals(403, response.statusCode())
                assertEquals("", response.body())
                val setCookies = response.headers().allValues("set-cookie").map { Cookie.parse(it)!! }
                assertEquals(setOf(), setCookies.map { it.name }.toSet())
            }

            run {
                val response =
                    impersonate(emailToImpersonate = otherUserEmail1, sessionCookie = impersonatorSessionCookie)
                assertEquals(200, response.statusCode())
                assertEquals("", response.body())
                assertEquals(listOf("/"), response.headers().allValues("hx-redirect"))
                val setCookies = response.headers().allValues("set-cookie").map { Cookie.parse(it)!! }
                assertEquals(setOf("impersonation_token"), setCookies.map { it.name }.toSet())
            }
        }
    }

    @Test
    fun `impersonating cookie is validated`() {
        val now = LocalDateTime.of(2024, 8, 26, 10, 43, 27)
        val impersonatorEmail = "admin@example.com"
        val otherUserEmail = "test@gmail.com"
        GoogleOAuthServer(clock = now.toClock()).use { authServer ->
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(impersonatorEmail, otherUserEmail))
            val tokenHashKey = Random.nextBytes(32)
            server =
                startServer(
                    clock = now.toClock(),
                    auth = auth,
                    adminEmails = listOf(impersonatorEmail),
                    tokenHashKey = tokenHashKey,
                )

            // Make sure other user exists.
            val impersonatedSession = login(email = otherUserEmail, authServer = authServer).sessionCookie().value

            val impersonatorSession = login(email = impersonatorEmail, authServer = authServer).sessionCookie().value

            fun sendRequest(
                impersonationToken: String,
                session: String,
            ): HttpResponse<String> {
                val request =
                    HttpRequest.newBuilder(server.uri("/"))
                        .header("cookie", Cookie("user_session", session).keyValueCookieString())
                        .header("cookie", Cookie("impersonation_token", impersonationToken).keyValueCookieString())
                        .build()
                return httpClient.send(request, BodyHandlers.ofString())
            }

            fun assertRequestFails(
                impersonationToken: String,
                session: String = impersonatorSession,
            ) {
                val response = sendRequest(impersonationToken = impersonationToken, session = session)
                assertEquals(200, response.statusCode())
                assertEquals("", response.body())
                assertEquals(listOf("/"), response.headers().allValues("hx-redirect"))
                val setCookies = response.headers().allValues("set-cookie").map { Cookie.parse(it)!! }
                assertEquals(setOf("impersonation_token"), setCookies.map { it.name }.toSet())
                assertEquals(1, setCookies.filter { it.name == "impersonation_token" }.size)
                val impersonationTokenCookie = setCookies.first { it.name == "impersonation_token" }
                assertEquals(0, impersonationTokenCookie.maxAge)
                assertEquals(Instant.EPOCH, impersonationTokenCookie.expires)
                assertEquals("", impersonationTokenCookie.value)
            }

            fun assertRequestSucceeds(
                impersonationToken: String,
                session: String = impersonatorSession,
            ) {
                val response = sendRequest(impersonationToken = impersonationToken, session = session)
                assertEquals(200, response.statusCode())
                assertNotEquals("", response.body())
                assertEquals(emptyList(), response.headers().allValues("hx-redirect"))
                assertEquals(emptyList(), response.headers().allValues("set-cookie"))
            }

            val objectMapper = ObjectMapper()

            fun encodePayload(
                impersonator: String? = impersonatorEmail,
                impersonated: String? = otherUserEmail,
                expirySeconds: Long? = now.plusHours(1).toEpochSecond(ZoneOffset.UTC),
            ): String {
                val payload =
                    mapOf("impersonator" to impersonator, "impersonated" to impersonated, "exp" to expirySeconds)
                        .filterValues { it != null }
                return objectMapper.writeValueAsString(payload).base64Encode()
            }

            fun sign(
                encodedPayload: String,
                hashKey: ByteArray = tokenHashKey,
            ): ByteArray = hmacSha256(tokenKey = hashKey, tokenBytes = encodedPayload.toByteArray())

            fun signBase64(
                encodedPayload: String,
                hashKey: ByteArray = tokenHashKey,
            ): String = sign(encodedPayload = encodedPayload, hashKey = hashKey).base64Encode()

            fun impersonationToken(
                payload: String = encodePayload(),
                signature: String = signBase64(payload),
            ): String = "$payload.$signature"

            assertRequestFails("")
            assertRequestFails("not base64")
            assertRequestFails("base64".base64Encode())
            assertRequestFails("not base64.also not base64")
            assertRequestFails("base64".base64Encode() + "." + "base64".base64Encode())
            assertRequestFails(impersonationToken(payload = "{}".base64Encode()))
            assertRequestFails(impersonationToken(payload = encodePayload(impersonator = null)))
            assertRequestFails(impersonationToken(payload = encodePayload(impersonated = null)))
            assertRequestFails(impersonationToken(payload = encodePayload(expirySeconds = null)))
            assertRequestFails(
                impersonationToken(
                    payload = encodePayload(expirySeconds = now.minusSeconds(1).toEpochSecond(ZoneOffset.UTC)),
                ),
            )
            assertRequestFails(
                impersonationToken =
                    impersonationToken(
                        payload = encodePayload(impersonator = otherUserEmail, impersonated = impersonatorEmail),
                    ),
                session = impersonatorSession,
            )
            assertRequestFails(impersonationToken = impersonationToken(), session = impersonatedSession)
            assertRequestFails(impersonationToken(signature = "invalid signature"))
            assertRequestFails(impersonationToken(signature = "invalid signature".base64Encode()))
            run {
                val encodedPayload = encodePayload()
                val signature = signBase64(encodedPayload = encodedPayload, hashKey = Random.nextBytes(32))
                assertRequestFails(impersonationToken(payload = encodedPayload, signature = signature))
            }
            assertRequestSucceeds(impersonationToken())
            assertRequestSucceeds(
                impersonationToken(
                    payload = encodePayload(expirySeconds = now.plusSeconds(1).toEpochSecond(ZoneOffset.UTC)),
                ),
            )
        }
    }

    @Test
    fun `future months cannot be navigated to`() {
        val now = LocalDate.of(2024, 8, 24)
        server = startServer(clock = now.toClock())

        fun monthRequest(month: YearMonth): HttpRequest.Builder =
            HttpRequest.newBuilder(server.uri("/?month=$month")).GET()

        val response1 = httpClient.send(monthRequest(now.minusMonths(1).toYearMonth()).build(), BodyHandlers.ofString())
        assertEquals(200, response1.statusCode())

        val response2 = httpClient.send(monthRequest(now.toYearMonth()).build(), BodyHandlers.ofString())
        assertEquals(200, response2.statusCode())

        val response3 = httpClient.send(monthRequest(now.plusMonths(1).toYearMonth()).build(), BodyHandlers.ofString())
        assertEquals(302, response3.statusCode())
        assertEquals(listOf("/"), response3.headers().allValues("location"))
        assertEquals("", response3.body())

        @Suppress("UastIncorrectHttpHeaderInspection")
        val request4 = monthRequest(now.plusMonths(1).toYearMonth()).header("hx-request", "true").build()
        val response4 = httpClient.send(request4, BodyHandlers.ofString())
        assertEquals(403, response4.statusCode())
        assertEquals("", response4.body())
    }

    @Test
    fun `months after latest date cannot be navigated to`() {
        val now = LocalDate.of(2024, 8, 24)
        val latestDate = LocalDate.of(2024, 7, 13)
        server = startServer(clock = now.toClock(), latestDate = latestDate)

        fun getRequest(path: String): HttpRequest.Builder = HttpRequest.newBuilder(server.uri(path)).GET()

        fun monthRequest(month: YearMonth): HttpRequest.Builder = getRequest("/?month=$month")

        @Suppress("UastIncorrectHttpHeaderInspection")
        fun HttpRequest.Builder.addHtmxHeader(): HttpRequest.Builder = header("hx-request", "true")

        val response1 = httpClient.send(monthRequest(now.minusMonths(1).toYearMonth()).build(), BodyHandlers.ofString())
        assertEquals(200, response1.statusCode())

        val response2 = httpClient.send(monthRequest(now.toYearMonth()).build(), BodyHandlers.ofString())
        assertEquals(302, response2.statusCode())
        assertEquals(listOf("/?month=${latestDate.toYearMonth()}"), response2.headers().allValues("location"))
        assertEquals("", response2.body())

        val response3 = httpClient.send(monthRequest(now.plusMonths(1).toYearMonth()).build(), BodyHandlers.ofString())
        assertEquals(302, response3.statusCode())
        assertEquals(listOf("/?month=${latestDate.toYearMonth()}"), response3.headers().allValues("location"))
        assertEquals("", response3.body())

        val response4 = httpClient.send(getRequest("/").build(), BodyHandlers.ofString())
        assertEquals(302, response4.statusCode())
        assertEquals(listOf("/?month=${latestDate.toYearMonth()}"), response4.headers().allValues("location"))
        assertEquals("", response4.body())

        val request5 = monthRequest(now.minusMonths(1).toYearMonth()).addHtmxHeader().build()
        val response5 = httpClient.send(request5, BodyHandlers.ofString())
        assertEquals(200, response5.statusCode())

        val request6 = monthRequest(now.toYearMonth()).addHtmxHeader().build()
        val response6 = httpClient.send(request6, BodyHandlers.ofString())
        assertEquals(403, response6.statusCode())
        assertEquals("", response6.body())

        val request7 = monthRequest(now.plusMonths(1).toYearMonth()).addHtmxHeader().build()
        val response7 = httpClient.send(request7, BodyHandlers.ofString())
        assertEquals(403, response7.statusCode())
        assertEquals("", response7.body())

        val request8 = getRequest("/").addHtmxHeader().build()
        val response8 = httpClient.send(request8, BodyHandlers.ofString())
        assertEquals(403, response8.statusCode())
        assertEquals("", response8.body())
    }

    private fun getDay(day: LocalDate): HttpResponse<String> =
        httpClient.send(HttpRequest.newBuilder(server.dayUri(day)).GET().build(), BodyHandlers.ofString())

    private fun login(
        email: String,
        authCode: String = UUID.randomUUID().toString(),
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
        session: String? = null,
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
                .run {
                    if (session != null) {
                        header("cookie", Cookie(name = "user_session", value = session).keyValueCookieString())
                    } else {
                        this
                    }
                }
                .GET()
                .build(),
            BodyHandlers.ofString(),
        )
    }

    private fun logout(session: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder(server.uri("/logout"))
                .header("cookie", Cookie(name = "user_session", value = session).keyValueCookieString())
                .GET()
                .build(),
            BodyHandlers.ofString(),
        )

    private fun impersonate(
        emailToImpersonate: String,
        sessionCookie: Cookie,
    ): HttpResponse<String> {
        val form = listOf("email" to emailToImpersonate)
        val request =
            HttpRequest.newBuilder(server.uri("/impersonate"))
                .header("cookie", sessionCookie.keyValueCookieString())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(form.toUrlFormEncoded()))
                .build()
        return httpClient.send(request, BodyHandlers.ofString())
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
        assetLoader: ResourceLoader = ResourceLoader.Classpath(basePackagePath = "/assets"),
        adminEmails: List<String> = emptyList(),
        tokenHashKey: ByteArray = Random.nextBytes(ByteArray(32)),
        latestDate: LocalDate = LocalDate.MAX,
    ): Http4kServer =
        Config(
            port = 0,
            clock = clock,
            dbUrl = dbUrl,
            assetLoader = assetLoader,
            hotReloading = false,
            auth = auth,
            adminEmails = adminEmails,
            tokenHashKeyBase64 = tokenHashKey.base64Encode(),
            earliestDate = LocalDate.EPOCH,
            latestDate = latestDate,
        ) { "whatever" }.startServer()

    private fun googleOauth(
        serverBaseUrl: URI? = null,
        tokenServerUrl: URI? = null,
        clientId: String = UUID.randomUUID().toString(),
        clientSecret: String = UUID.randomUUID().toString(),
        allowedUserEmails: Collection<String> = emptyList(),
    ) = GoogleOauth(
        serverBaseUrl = serverBaseUrl,
        authServerUrl = null,
        tokenServerUrl = tokenServerUrl,
        publicCertsUrl = null,
        clientId = clientId,
        clientSecret = clientSecret,
        allowedUserEmails = allowedUserEmails,
    )

    private fun <T> executeStatement(execute: (Statement) -> T): T = executeStatement(dbUrl = dbUrl, execute)
}

private fun HttpResponse<String>.sessionCookie(): Cookie =
    headers().allValues("set-cookie")
        .map { Cookie.parse(it)!! }
        .filter { it.name == "user_session" }
        .apply { assertEquals(1, size) }
        .first()
