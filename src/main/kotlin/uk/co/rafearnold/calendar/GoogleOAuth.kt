package uk.co.rafearnold.calendar

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.googleapis.auth.oauth2.GooglePublicKeysManager
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.http4k.base64DecodedArray
import org.http4k.base64Encode
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.SameSite
import org.http4k.core.cookie.cookie
import org.http4k.core.cookie.invalidateCookie
import org.http4k.core.with
import org.http4k.lens.Cookies
import org.http4k.lens.Header
import org.http4k.lens.Query
import org.http4k.lens.RequestContextLens
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import java.net.URI
import java.time.Clock

data class GoogleOauth(
    val serverBaseUrl: URI?,
    val authServerUrl: URI?,
    val tokenServerUrl: URI?,
    val publicCertsUrl: URI?,
    val clientId: String,
    val clientSecret: String,
    val allowedUserEmails: Collection<String>,
) : AuthConfig {
    override fun createHandlerFactory(
        userRepository: UserRepository,
        userLens: RequestContextLens<User>,
        clock: Clock,
        tokenHashKey: ByteArray,
    ): RoutingHandlerFactory =
        RoutingHandlerFactory { list ->
            val oauth =
                OAuth(
                    serverBaseUrl = serverBaseUrl,
                    authServerUrl = authServerUrl,
                    tokenServerUrl = tokenServerUrl,
                    publicCertsUrl = publicCertsUrl,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    tokenHashKey = tokenHashKey,
                    clock = clock,
                    allowedUserEmails = allowedUserEmails,
                )
            routes(
                GoogleOAuthCallback(oauth, userRepository),
                routes(*list).withFilter(AuthenticateViaGoogle(oauth, userRepository, userLens)),
            )
        }

    override fun logoutHandler(): HttpHandler = logoutHandler

    companion object
}

val HOST = Header.required("host")

private const val ID_TOKEN_COOKIE_NAME = "id_token"
private val idTokenCookie = Cookies.optional(name = ID_TOKEN_COOKIE_NAME)

private const val AUTH_CSRF_TOKEN_COOKIE_NAME = "auth_csrf_token"
private val authCsrfTokenCookie = Cookies.optional(name = AUTH_CSRF_TOKEN_COOKIE_NAME)

private class AuthenticateViaGoogle(
    private val oauth: OAuth,
    private val userRepository: UserRepository,
    private val user: RequestContextLens<User>,
) : Filter {
    override fun invoke(next: HttpHandler): HttpHandler =
        { request ->
            val idToken =
                idTokenCookie(request)?.let {
                    runCatching { GoogleIdToken.parse(GsonFactory.getDefaultInstance(), it.value) }
                        .getOrElse { throw ForbiddenException() }
                }
            try {
                if (idToken != null && idToken.verify(oauth.tokenVerifier)) {
                    val user = userRepository.getByGoogleId(subjectId = idToken.payload.subject)
                    if (user != null &&
                        idToken.payload.email == user.email &&
                        oauth.emailIsAllowed(email = idToken.payload.email)
                    ) {
                        next(user(user, request))
                    } else {
                        throw InvalidIdTokenException()
                    }
                } else {
                    throw InvalidIdTokenException()
                }
            } catch (e: InvalidIdTokenException) {
                val csrfToken = randomBytes(numBytes = 32)
                val tokenHash = oauth.hashToken(token = csrfToken)
                val authUrl =
                    oauth.authFlow.newAuthorizationUrl()
                        .setRedirectUri(oauth.redirectUri(request))
                        .setState(tokenHash.base64Encode())
                        .build()
                if (request.isHtmx()) {
                    Response(Status.OK).with(htmxRedirect(location = authUrl))
                } else {
                    Response(Status.FOUND).header("location", authUrl)
                }
                    .cookie(
                        Cookie(
                            name = AUTH_CSRF_TOKEN_COOKIE_NAME,
                            value = csrfToken.base64Encode(),
                            path = "/",
                            secure = true,
                            httpOnly = true,
                            sameSite = SameSite.Lax,
                            maxAge = 300,
                        ),
                    )
            }
        }
}

private class InvalidIdTokenException : RuntimeException()

private val codeQuery = Query.optional("code")
private val stateQuery = Query.optional("state")

private const val CALLBACK_PATH = "/oauth/code"

private class GoogleOAuthCallback(
    private val oauth: OAuth,
    private val userRepository: UserRepository,
) : RoutingHttpHandler by CALLBACK_PATH bind GET to { request ->
        val state = stateQuery(request).decodeBase64()
        val csrfToken = authCsrfTokenCookie(request)?.value.decodeBase64()
        val expectedHash = oauth.hashToken(token = csrfToken)
        if (!state.contentEquals(expectedHash)) throw ForbiddenException()
        val authCode = codeQuery(request) ?: throw ForbiddenException()
        val tokenResponse =
            oauth.authFlow.newTokenRequest(authCode).setRedirectUri(oauth.redirectUri(request)).execute()
        val idToken = tokenResponse.parseIdToken()
        if (!oauth.emailIsAllowed(email = idToken.payload.email)) throw ForbiddenException()
        userRepository.createUserIfNoneExists(email = idToken.payload.email, googleSubjectId = idToken.payload.subject)
        Response(Status.FOUND).header("location", "/")
            .cookie(
                Cookie(
                    name = ID_TOKEN_COOKIE_NAME,
                    value = tokenResponse.idToken,
                    path = "/",
                    secure = true,
                    httpOnly = true,
                    sameSite = SameSite.Lax,
                ),
            )
            .invalidateCookie(name = AUTH_CSRF_TOKEN_COOKIE_NAME)
    }

private fun String?.decodeBase64(): ByteArray =
    try {
        this?.base64DecodedArray() ?: throw ForbiddenException()
    } catch (e: IllegalArgumentException) {
        throw ForbiddenException()
    }

private val logoutHandler: HttpHandler = {
    Response(Status.FOUND).header("location", "/").invalidateCookie(ID_TOKEN_COOKIE_NAME)
}

private class OAuth(
    serverBaseUrl: URI?,
    authServerUrl: URI?,
    tokenServerUrl: URI?,
    publicCertsUrl: URI?,
    clientId: String,
    clientSecret: String,
    private val tokenHashKey: ByteArray,
    private val clock: Clock,
    private val allowedUserEmails: Collection<String>,
) {
    val authFlow: GoogleAuthorizationCodeFlow
    val tokenVerifier: GoogleIdTokenVerifier

    init {
        val httpTransport = NetHttpTransport()

        authFlow =
            GoogleAuthorizationCodeFlow
                .Builder(
                    httpTransport,
                    GsonFactory.getDefaultInstance(),
                    clientId,
                    clientSecret,
                    listOf("openid", "profile", "email"),
                )
                .run { if (tokenServerUrl != null) setTokenServerUrl(GenericUrl(tokenServerUrl)) else this }
                .run {
                    if (authServerUrl != null) setAuthorizationServerEncodedUrl(authServerUrl.toASCIIString()) else this
                }
                .build()

        tokenVerifier =
            GoogleIdTokenVerifier.Builder(
                GooglePublicKeysManager.Builder(httpTransport, GsonFactory.getDefaultInstance())
                    .setClock { clock.millis() }
                    .run {
                        if (publicCertsUrl != null) setPublicCertsEncodedUrl(publicCertsUrl.toASCIIString()) else this
                    }
                    .build(),
            )
                .setClock { clock.millis() }
                .setAudience(listOf(clientId))
                .build()
    }

    private val redirectUri: URI? = serverBaseUrl?.redirectUri()

    @Suppress("HttpUrlsUsage")
    fun redirectUri(request: Request): String =
        (redirectUri ?: URI("http://${HOST(request)}").redirectUri()).toASCIIString()

    fun URI.redirectUri(): URI = resolve(CALLBACK_PATH)

    fun hashToken(token: ByteArray): ByteArray = hmacSha256(data = token, key = tokenHashKey)

    fun emailIsAllowed(email: String) = allowedUserEmails.contains(email)
}
