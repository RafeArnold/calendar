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
import java.security.interfaces.RSAPrivateKey
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.jvm.optionals.getOrNull
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
    fun `authenticated users are given an id token on login`() {
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
            val idTokenCookie = response.idTokenCookie()
            assertEquals(SameSite.Lax, idTokenCookie.sameSite)
            assertTrue(idTokenCookie.httpOnly)
            assertTrue(idTokenCookie.secure)
            assertNull(idTokenCookie.domain)
            assertEquals("/", idTokenCookie.path)
            JWT.require(Algorithm.RSA256(authServer.certKeyPair.public, authServer.certKeyPair.private)).build()
                .verify(idTokenCookie.value)
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
                signingKey: RSAPrivateKey = authServer.certKeyPair.private,
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
        GoogleOAuthServer().use { authServer ->
            val impersonatorEmail = "admin@gmail.com"
            val impersonatedUserEmail = "test@gmail.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(impersonatorEmail, impersonatedUserEmail))
            server = startServer(auth = auth, impersonatorEmails = listOf(impersonatorEmail))

            // Make sure the impersonated user already exists in the db.
            DSL.using(dbUrl).use {
                UserRepository(it).createUserIfNoneExists(
                    email = impersonatedUserEmail,
                    googleSubjectId = UUID.randomUUID().toString(),
                )
            }

            val idTokenCookie = login(email = impersonatorEmail, authServer = authServer).idTokenCookie()
            val form = listOf("email" to impersonatedUserEmail)
            val request =
                HttpRequest.newBuilder(server.uri("/impersonate"))
                    .header("cookie", idTokenCookie.keyValueCookieString())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString(form.toUrlFormEncoded()))
                    .build()
            val response = httpClient.send(request, BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            assertEquals("", response.body())
            assertEquals(listOf("/"), response.headers().allValues("hx-redirect"))
            val setCookies = response.headers().allValues("set-cookie").map { Cookie.parse(it)!! }
            assertEquals(setOf("impersonating_email"), setCookies.map { it.name }.toSet())
            assertEquals(1, setCookies.filter { it.name == "impersonating_email" }.size)
            val impersonatingEmailCookie = setCookies.first { it.name == "impersonating_email" }
            assertEquals(impersonatedUserEmail, impersonatingEmailCookie.value)
            assertEquals(1800, impersonatingEmailCookie.maxAge)
            assertNull(impersonatingEmailCookie.expires)
            assertNull(impersonatingEmailCookie.domain)
            assertEquals("/", impersonatingEmailCookie.path)
            assertTrue(impersonatingEmailCookie.secure)
            assertTrue(impersonatingEmailCookie.httpOnly)
            assertEquals(SameSite.Strict, impersonatingEmailCookie.sameSite)
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
            server = startServer(auth = auth, impersonatorEmails = listOf(impersonatorEmail))

            val impersonatedIdTokenCookie = login(email = otherUserEmail1, authServer = authServer).idTokenCookie()
            val impersonatorIdTokenCookie = login(email = impersonatorEmail, authServer = authServer).idTokenCookie()

            run {
                val form = listOf("email" to otherUserEmail2)
                val request =
                    HttpRequest.newBuilder(server.uri("/impersonate"))
                        .header("cookie", impersonatedIdTokenCookie.keyValueCookieString())
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(BodyPublishers.ofString(form.toUrlFormEncoded()))
                        .build()
                val response = httpClient.send(request, BodyHandlers.ofString())
                assertEquals(403, response.statusCode())
                assertEquals("", response.body())
                val setCookies = response.headers().allValues("set-cookie").map { Cookie.parse(it)!! }
                assertEquals(setOf(), setCookies.map { it.name }.toSet())
            }

            run {
                val form = listOf("email" to otherUserEmail1)
                val request =
                    HttpRequest.newBuilder(server.uri("/impersonate"))
                        .header("cookie", impersonatorIdTokenCookie.keyValueCookieString())
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(BodyPublishers.ofString(form.toUrlFormEncoded()))
                        .build()
                val response = httpClient.send(request, BodyHandlers.ofString())
                assertEquals(200, response.statusCode())
                assertEquals("", response.body())
                assertEquals(listOf("/"), response.headers().allValues("hx-redirect"))
                val setCookies = response.headers().allValues("set-cookie").map { Cookie.parse(it)!! }
                assertEquals(setOf("impersonating_email"), setCookies.map { it.name }.toSet())
            }
        }
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
        assetLoader: ResourceLoader = ResourceLoader.Classpath(basePackagePath = "/assets"),
        impersonatorEmails: List<String> = emptyList(),
    ): Http4kServer =
        Config(
            port = 0,
            clock = clock,
            dbUrl = dbUrl,
            assetLoader = assetLoader,
            hotReloading = false,
            auth = auth,
            impersonatorEmails = impersonatorEmails,
        ) { "whatever" }.startServer()

    private fun googleOauth(
        serverBaseUrl: URI? = null,
        tokenServerUrl: URI? = null,
        clientId: String = UUID.randomUUID().toString(),
        clientSecret: String = UUID.randomUUID().toString(),
        allowedUserEmails: Collection<String> = emptyList(),
        tokenHashKey: ByteArray = Random.nextBytes(ByteArray(32)),
    ) = GoogleOauth(
        serverBaseUrl = serverBaseUrl,
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

private fun HttpResponse<String>.idTokenCookie(): Cookie =
    headers().allValues("set-cookie")
        .map { Cookie.parse(it)!! }
        .filter { it.name == "id_token" }
        .apply { assertEquals(1, size) }
        .first()
