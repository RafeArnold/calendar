package uk.co.rafearnold.calendar

import org.http4k.base64Encode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Clock

val logger: Logger = LoggerFactory.getLogger("main")

fun main() {
    Config.fromEnv(System.getenv()).startServer().block()
}

fun Config.Companion.fromEnv(env: Map<String, String>): Config =
    Config(
        port = env["PORT"]?.toInt() ?: 8080,
        clock = Clock.systemUTC(),
        dbUrl = env.getValue("DB_URL"),
        assetDirs = env.getValue("ASSET_DIRS").split(','),
        hotReloading = env["HOT_RELOADING"] == "true",
        auth = GoogleOauth.fromEnv(env),
    ) { "something sweet" }

fun GoogleOauth.Companion.fromEnv(env: Map<String, String>): GoogleOauth =
    GoogleOauth(
        serverBaseUrl = env["SERVER_BASE_URL"]?.let { URI(it) },
        authServerUrl = null,
        tokenServerUrl = null,
        publicCertsUrl = null,
        clientId = env.getValue("GOOGLE_OAUTH_CLIENT_ID"),
        clientSecret = env.getValue("GOOGLE_OAUTH_CLIENT_SECRET"),
        allowedUserEmails = env.getValue("ALLOWED_USERS").split(" "),
        tokenHashKeyBase64 = randomBytes(numBytes = 32).base64Encode(),
    )
