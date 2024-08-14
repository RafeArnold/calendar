package uk.co.rafearnold.calendar

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock
import org.http4k.lens.RequestContextLens
import org.http4k.routing.RoutingHttpHandler
import org.http4k.server.Http4kServer
import org.intellij.lang.annotations.Language
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.interfaces.RSAKey
import java.sql.DriverManager
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.Path
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

fun Http4kServer.oauthUri(code: String?): URI = uri("/oauth/code" + if (code != null) "?code=$code" else "")

fun Http4kServer.dayUri(day: LocalDate): URI = uri("/day/${day.format(DateTimeFormatter.ISO_LOCAL_DATE)}")

fun Http4kServer.uri(path: String): URI = URI.create("http://localhost:${port()}").resolve(path)

fun copyImage(
    source: String,
    target: Path,
) {
    Files.copy(Path("src/test/resources/images").resolve(source), target, StandardCopyOption.REPLACE_EXISTING)
}

class GoogleOAuthServer(
    private val clock: Clock = Clock.systemUTC(),
    private val clientId: String,
    private val clientSecret: String,
) : WireMockServer(0), AutoCloseable {
    init {
        start()
    }

    fun stubTokenExchange(
        authCode: String,
        email: String,
        subject: String,
    ) {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private as RSAKey
        val idToken =
            JWT.create()
                .withAudience(clientId)
                .withExpiresAt(clock.instant().plusSeconds(3920))
                .withIssuedAt(clock.instant())
                .withIssuer("https://accounts.google.com")
                .withSubject(subject)
                .withClaim("email", email)
                .sign(Algorithm.RSA256(key))

        @Language("JSON")
        val tokenJson = """{
              "access_token": "1/fFAGRNJru1FTz70BzhT3Zg",
              "expires_in": 3920,
              "id_token": "$idToken",
              "token_type": "Bearer",
              "scope": "openid profile email",
              "refresh_token": "1//xEoDL4iW3cxlI7yDbSRFYNG01kVKM2C-259HOF2aQbI"
            }"""
        stubFor(
            WireMock.post(WireMock.urlPathEqualTo("/token"))
                .withFormParam("code", WireMock.equalTo(authCode))
                .willReturn(ResponseDefinitionBuilder.responseDefinition().withStatus(200).withBody(tokenJson)),
        )
    }

    fun verifyTokenWasExchanged(
        authCode: String,
        redirectUri: String,
    ) {
        verify(
            1,
            WireMock.postRequestedFor(WireMock.urlEqualTo("/token"))
                .withHeader("content-type", WireMock.containing("application/x-www-form-urlencoded"))
                .withFormParam("client_id", WireMock.equalTo(clientId))
                .withFormParam("client_secret", WireMock.equalTo(clientSecret))
                .withFormParam("code", WireMock.equalTo(authCode))
                .withFormParam("grant_type", WireMock.equalTo("authorization_code"))
                .withFormParam("redirect_uri", WireMock.equalTo(redirectUri)),
        )
    }

    override fun close() {
        stop()
    }
}

data object NoAuth : AuthConfig {
    override fun createHandlerFactory(
        userRepository: UserRepository,
        userLens: RequestContextLens<User>,
    ): RoutingHandlerFactory =
        object : RoutingHandlerFactory {
            override fun routes(vararg list: RoutingHttpHandler): RoutingHttpHandler =
                org.http4k.routing.routes(*list).withFilter { next -> { next(userLens(User(0, "", ""), it)) } }
        }
}

fun <T> executeStatement(
    dbUrl: String,
    execute: (Statement) -> T,
): T = DriverManager.getConnection(dbUrl).use { connection -> connection.createStatement().use(execute) }
