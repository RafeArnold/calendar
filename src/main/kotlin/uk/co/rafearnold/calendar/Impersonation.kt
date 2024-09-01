package uk.co.rafearnold.calendar

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.http4k.base64DecodedArray
import org.http4k.base64Encode
import org.http4k.core.Body
import org.http4k.core.Filter
import org.http4k.core.Method.POST
import org.http4k.core.RequestContext
import org.http4k.core.Response
import org.http4k.core.Status.Companion.FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Store
import org.http4k.core.cookie.Cookie
import org.http4k.core.cookie.SameSite
import org.http4k.core.cookie.cookie
import org.http4k.core.cookie.invalidate
import org.http4k.core.cookie.replaceCookie
import org.http4k.core.with
import org.http4k.lens.Cookies
import org.http4k.lens.FormField
import org.http4k.lens.RequestContextKey
import org.http4k.lens.RequestContextLens
import org.http4k.lens.Validator
import org.http4k.lens.webForm
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import java.time.Clock
import java.time.temporal.ChronoUnit

private const val IMPERSONATION_TOKEN_COOKIE_NAME = "impersonation_token"
private val impersonationTokenCookie = Cookies.optional(name = IMPERSONATION_TOKEN_COOKIE_NAME)

private val emailToImpersonate = FormField.required("email")
private val impersonateForm = Body.webForm(Validator.Strict, emailToImpersonate).toLens()

fun impersonatedUserLens(contexts: Store<RequestContext>) =
    RequestContextKey.optional<User>(contexts, name = "impersonated-user")

fun impersonateRoute(
    clock: Clock,
    userRepository: UserRepository,
    user: RequestContextLens<User>,
    tokens: ImpersonationTokens,
): RoutingHttpHandler =
    "/impersonate" bind POST to { request ->
        val user0 = user(request)
        if (!user0.isAdmin) throw ForbiddenException()
        val emailToImpersonate = emailToImpersonate(impersonateForm(request))
        userRepository.getByEmail(email = emailToImpersonate)
            ?: throw DisplayErrorException(errorMessage = "user $emailToImpersonate not found")
        val expirationTimeSeconds = clock.instant().plus(1, ChronoUnit.HOURS).epochSecond
        val impersonationPayload =
            ImpersonationPayload(
                impersonatorEmail = user0.email,
                impersonatedEmail = emailToImpersonate,
                expirationTimeSeconds = expirationTimeSeconds,
            )
        Response(OK)
            .with(htmxRedirect(location = "/"))
            .cookie(
                Cookie(
                    name = IMPERSONATION_TOKEN_COOKIE_NAME,
                    value = tokens.sign(impersonationPayload),
                    path = "/",
                    secure = true,
                    httpOnly = true,
                    sameSite = SameSite.Strict,
                    maxAge = 1800,
                ),
            )
    }

val stopImpersonating: (Response) -> Response = {
    it.replaceCookie(Cookie(IMPERSONATION_TOKEN_COOKIE_NAME, "", path = "/").invalidate())
}

fun stopImpersonatingRoute(): RoutingHttpHandler =
    "/impersonate/stop" bind POST to { Response(FOUND).header("location", "/").with(stopImpersonating) }

fun impersonatedUserFilter(
    userRepository: UserRepository,
    user: RequestContextLens<User>,
    impersonatedUser: RequestContextLens<User?>,
    tokens: ImpersonationTokens,
): Filter =
    Filter { next ->
        { request ->
            val impersonationCookie = impersonationTokenCookie(request)
            if (impersonationCookie != null) {
                try {
                    val token =
                        runCatching { tokens.parse(impersonationCookie.value) }
                            .getOrElse { throw InvalidImpersonationTokenException() }
                    val tokenIsValid =
                        runCatching { tokens.verify(token, impersonatorEmail = user(request).email) }
                            .getOrElse { throw InvalidImpersonationTokenException() }
                    if (!tokenIsValid) {
                        throw InvalidImpersonationTokenException()
                    }
                    val impersonatedUser0 =
                        userRepository.getByEmail(email = token.payload.impersonatedEmail)
                            ?: throw InvalidImpersonationTokenException()
                    next(impersonatedUser(impersonatedUser0, request))
                } catch (e: InvalidImpersonationTokenException) {
                    Response(OK).with(htmxRedirect(location = "/")).with(stopImpersonating)
                }
            } else {
                next(request)
            }
        }
    }

private class InvalidImpersonationTokenException : RuntimeException()

data class ImpersonationToken(
    val payload: ImpersonationPayload,
    val encodedSignature: String,
    val encodedPayload: String,
)

data class ImpersonationPayload(
    @JsonProperty(value = "impersonator", required = true)
    val impersonatorEmail: String,
    @JsonProperty(value = "impersonated", required = true)
    val impersonatedEmail: String,
    @JsonProperty(value = "exp", required = true)
    val expirationTimeSeconds: Long,
)

class ImpersonationTokens(
    private val tokenHashKey: ByteArray,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) {
    fun sign(payload: ImpersonationPayload): String {
        val encodedPayload = objectMapper.writeValueAsString(payload).base64Encode()
        val encodedSignature = hmacSha256(encodedPayload.toByteArray(), tokenHashKey).base64Encode()
        return "$encodedPayload.$encodedSignature"
    }

    fun parse(token: String): ImpersonationToken {
        val parts = token.split(".")
        if (parts.size != 2) throw IllegalArgumentException()
        val (encodedPayload, encodedSignature) = parts
        val payload = objectMapper.readValue(encodedPayload.base64DecodedArray(), ImpersonationPayload::class.java)
        return ImpersonationToken(
            payload = payload,
            encodedSignature = encodedSignature,
            encodedPayload = encodedPayload,
        )
    }

    fun verify(
        token: ImpersonationToken,
        impersonatorEmail: String,
    ): Boolean = token.payload.impersonatorEmail == impersonatorEmail && !token.isExpired() && token.verifySignature()

    private fun ImpersonationToken.isExpired(): Boolean = payload.expirationTimeSeconds < clock.millis() / 1000

    private fun ImpersonationToken.verifySignature(): Boolean =
        encodedSignature.base64DecodedArray()
            .contentEquals(hmacSha256(data = encodedPayload.toByteArray(), key = tokenHashKey))
}
