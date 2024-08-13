package uk.co.rafearnold.calendar

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.SameSite
import org.http4k.core.cookie.cookie
import org.http4k.lens.Cookies
import org.http4k.lens.Header
import org.http4k.lens.Query
import org.http4k.lens.RequestContextLens
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import java.net.URI
import java.time.Instant

data class GoogleOauth(
    val serverBaseUrl: URI?,
    val tokenServerUrl: URI?,
    val clientId: String,
    val clientSecret: String,
) : AuthConfig {
    override fun createHandlerFactory(
        userRepository: UserRepository,
        userLens: RequestContextLens<User>,
    ): RoutingHandlerFactory =
        object : RoutingHandlerFactory {
            override fun routes(vararg list: RoutingHttpHandler): RoutingHttpHandler {
                val oauth =
                    OAuth(
                        serverBaseUrl = serverBaseUrl,
                        tokenServerUrl = tokenServerUrl,
                        clientId = clientId,
                        clientSecret = clientSecret,
                    )
                return org.http4k.routing.routes(
                    GoogleOAuthCallback(oauth, userRepository),
                    org.http4k.routing.routes(*list).withFilter(AuthenticateViaGoogle(oauth, userRepository, userLens)),
                )
            }
        }
}

val HOST = Header.required("host")

private const val ID_TOKEN_COOKIE_NAME = "id_token"

private val idTokenCookie = Cookies.optional(name = ID_TOKEN_COOKIE_NAME)

private class AuthenticateViaGoogle(
    private val oauth: OAuth,
    private val userRepository: UserRepository,
    private val user: RequestContextLens<User>,
) : Filter {
    override fun invoke(next: HttpHandler): HttpHandler =
        { request ->
            val idToken =
                idTokenCookie(request)?.let { GoogleIdToken.parse(GsonFactory.getDefaultInstance(), it.value) }
            if (idToken != null) {
                val user = userRepository.getByGoogleId(subjectId = idToken.payload.subject)
                if (user == null) Response(Status.FORBIDDEN) else next(user(user, request))
            } else {
                val authUrl = oauth.authFlow.newAuthorizationUrl().setRedirectUri(oauth.redirectUri(request)).build()
                Response(Status.FOUND).header("location", authUrl)
            }
        }
}

private val codeQuery = Query.required("code")

private class GoogleOAuthCallback(
    private val oauth: OAuth,
    private val userRepository: UserRepository,
) : RoutingHttpHandler by "/oauth/code" bind GET to { request ->
        val authCode = codeQuery(request)
        val tokenResponse =
            oauth.authFlow.newTokenRequest(authCode).setRedirectUri(oauth.redirectUri(request)).execute()
        val idToken = tokenResponse.parseIdToken()
        userRepository.createUserIfNoneExists(email = idToken.payload.email, googleSubjectId = idToken.payload.subject)
        Response(Status.FOUND).header("location", "/")
            .cookie(
                Cookie(
                    name = ID_TOKEN_COOKIE_NAME,
                    value = tokenResponse.idToken,
                    expires = Instant.ofEpochSecond(idToken.payload.expirationTimeSeconds),
                    path = "/",
                    secure = true,
                    httpOnly = true,
                    sameSite = SameSite.Strict,
                ),
            )
    }

private class OAuth(serverBaseUrl: URI?, tokenServerUrl: URI?, clientId: String, clientSecret: String) {
    val authFlow: GoogleAuthorizationCodeFlow =
        GoogleAuthorizationCodeFlow
            .Builder(
                NetHttpTransport(),
                GsonFactory.getDefaultInstance(),
                clientId,
                clientSecret,
                listOf("openid profile email"),
            )
            .run { if (tokenServerUrl != null) setTokenServerUrl(GenericUrl(tokenServerUrl)) else this }
            .build()

    private val redirectUri: URI? = serverBaseUrl?.redirectUri()

    @Suppress("HttpUrlsUsage")
    fun redirectUri(request: Request): String =
        (redirectUri ?: URI("http://${HOST(request)}").redirectUri()).toASCIIString()

    fun URI.redirectUri(): URI = resolve("/oauth/code")
}
