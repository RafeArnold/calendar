package uk.co.rafearnold.calendar

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.matching.UrlPattern
import com.github.tomakehurst.wiremock.verification.LoggedRequest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.openssl.MiscPEMGenerator
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemWriter
import org.http4k.core.toUrlFormEncoded
import org.http4k.server.Http4kServer
import org.intellij.lang.annotations.Language
import java.io.StringWriter
import java.math.BigInteger
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.sql.DriverManager
import java.sql.Statement
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAmount
import java.util.Date
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.Path
import kotlin.random.Random

fun LocalDate.toYearMonth(): YearMonth = YearMonth.from(this)

fun LocalDateTime.toYearMonth(): YearMonth = YearMonth.from(this)

fun LocalDate.toMutableClock(): MutableClock = toClock().mutable()

fun YearMonth.toClock(): Clock = atDay(Random.nextInt(1, lengthOfMonth())).toClock()

fun LocalDate.toClock(): Clock = Clock.fixed(atTime(LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

fun LocalDateTime.toClock(): Clock = Clock.fixed(toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

fun Clock.mutable() = MutableClock(this)

fun MutableClock.fastForward(temporalAmount: TemporalAmount) {
    del = Clock.fixed(instant().plus(temporalAmount), zone)
}

class MutableClock(var del: Clock) : Clock() {
    override fun instant(): Instant = del.instant()

    override fun millis(): Long = del.millis()

    override fun withZone(zone: ZoneId): Clock = del.withZone(zone)

    override fun getZone(): ZoneId = del.zone
}

class MapBackedMessageLoader(private val messages: Map<LocalDate, String>) : MessageLoader {
    override fun get(date: LocalDate): String? = messages[date]
}

fun Http4kServer.oauthUri(
    code: String?,
    state: String?,
): URI = uri("/oauth/code" + listOf("code" to code, "state" to state).toQuery())

private fun List<Pair<String, String?>>.toQuery(): String =
    filter { it.second != null }.toUrlFormEncoded().prefixIfNotEmpty(prefix = "?")

private fun String.prefixIfNotEmpty(prefix: String): String = if (isEmpty()) this else "$prefix$this"

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
    private val clientId: String = UUID.randomUUID().toString(),
    private val clientSecret: String = UUID.randomUUID().toString(),
) : WireMockServer(0), AutoCloseable {
    private val certKeyPair: RsaKeyPair = generateRsaKeyPair()

    init {
        start()
        stubCerts()
    }

    companion object {
        private const val TOKEN_EXCHANGE_PATH = "/token"
        private const val AUTHENTICATION_PAGE_PATH = "/oauth/auth"
        private const val CERTS_PATH = "/oauth2/v1/certs"
        const val LOGIN_BUTTON_TEST_ID: String = "login-button"
    }

    val authenticationPageUrl: String = baseUrl() + AUTHENTICATION_PAGE_PATH

    fun toAuthConfig(allowedUserEmails: Collection<String>): GoogleOauth =
        GoogleOauth(
            serverBaseUrl = null,
            authServerUrl = URI(authenticationPageUrl),
            tokenServerUrl = URI(baseUrl() + TOKEN_EXCHANGE_PATH),
            publicCertsUrl = URI(baseUrl() + CERTS_PATH),
            clientId = clientId,
            clientSecret = clientSecret,
            allowedUserEmails = allowedUserEmails,
        )

    fun stubTokenExchange(
        authCode: String,
        email: String,
        subject: String,
    ) {
        val idToken =
            JWT.create()
                .withAudience(clientId)
                .withExpiresAt(clock.instant().plusSeconds(3920))
                .withIssuedAt(clock.instant())
                .withIssuer("https://accounts.google.com")
                .withSubject(subject)
                .withClaim("email", email)
                .sign(Algorithm.RSA256(certKeyPair.private))

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
            WireMock.post(WireMock.urlPathEqualTo(TOKEN_EXCHANGE_PATH))
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
            WireMock.postRequestedFor(WireMock.urlEqualTo(TOKEN_EXCHANGE_PATH))
                .withHeader("content-type", WireMock.containing("application/x-www-form-urlencoded"))
                .withFormParam("client_id", WireMock.equalTo(clientId))
                .withFormParam("client_secret", WireMock.equalTo(clientSecret))
                .withFormParam("code", WireMock.equalTo(authCode))
                .withFormParam("grant_type", WireMock.equalTo("authorization_code"))
                .withFormParam("redirect_uri", WireMock.equalTo(redirectUri)),
        )
    }

    fun allTokenExchangeServedRequests(): List<LoggedRequest> =
        findAll(WireMock.postRequestedFor(WireMock.urlEqualTo(TOKEN_EXCHANGE_PATH)))

    fun stubAuthenticationPage(
        redirectUri: URI,
        authCode: String,
    ) {
        @Language("HTML")
        val html = """
            <form method="get" action="${redirectUri.toASCIIString()}">
                <input type="hidden" name="code" value="$authCode">
                <input type="hidden" name="state" value="{{request.query.state}}">
                <button data-testid="$LOGIN_BUTTON_TEST_ID" type="submit">log me in</button>
            </form>
        """
        stubFor(
            WireMock.get(WireMock.urlPathEqualTo(AUTHENTICATION_PAGE_PATH))
                .willReturn(
                    ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/html")
                        .withBody(html)
                        .withTransformers("response-template"),
                ),
        )
    }

    private fun stubCerts() {
        val cert = generateX509Cert(certKeyPair)
        stubFor(
            WireMock.get(WireMock.urlEqualTo(CERTS_PATH))
                .willReturn(ResponseDefinitionBuilder.okForJson(mapOf(UUID.randomUUID().toString() to cert))),
        )
    }

    private fun generateX509Cert(keyPair: RsaKeyPair): String {
        val cert =
            X509v3CertificateBuilder(
                X500Name("CN=My Application,O=My Organisation,L=My City,C=DE"),
                BigInteger.ONE,
                Date.from(Instant.EPOCH),
                Date.from(LocalDate.EPOCH.atStartOfDay().plusYears(100).toInstant(ZoneOffset.UTC)),
                X500Name("CN=My Application,O=My Organisation,L=My City,C=DE"),
                SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
            ).build(JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private))
        val stringWriter = StringWriter()
        PemWriter(stringWriter).use { it.writeObject(MiscPEMGenerator(cert)) }
        return stringWriter.toString()
    }

    fun allNonCertRequests(): List<LoggedRequest> =
        findAll(WireMock.anyRequestedFor(UrlPattern(WireMock.not(WireMock.equalTo(CERTS_PATH)), false)))

    override fun close() {
        stop()
    }
}

fun <T> executeStatement(
    dbUrl: String,
    execute: (Statement) -> T,
): T = DriverManager.getConnection(dbUrl).use { connection -> connection.createStatement().use(execute) }

fun hmacSha256(
    tokenKey: ByteArray,
    tokenBytes: ByteArray,
): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(tokenKey, "HmacSHA256")) }.doFinal(tokenBytes)

fun generateRsaKeyPair(): RsaKeyPair =
    KeyPairGenerator.getInstance("RSA").generateKeyPair()
        .let { RsaKeyPair(public = it.public as RSAPublicKey, private = it.private as RSAPrivateKey) }

data class RsaKeyPair(val public: RSAPublicKey, val private: RSAPrivateKey)
