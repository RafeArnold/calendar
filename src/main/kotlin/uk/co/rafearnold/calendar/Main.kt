package uk.co.rafearnold.calendar

import org.http4k.base64Encode
import org.http4k.routing.ResourceLoader
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Clock
import java.time.LocalDate

val logger: Logger = LoggerFactory.getLogger("main")

fun main() {
    Config.fromEnv(System.getenv()).startServer().block()
}

fun Config.Companion.fromEnv(env: Map<String, String>): Config {
    val assetDirs = env["ASSET_DIRS"]?.split(',') ?: emptyList()
    val assetLoader =
        ChainResourceLoader(assetDirs.map { ResourceLoader.Directory(it) } + ResourceLoader.Classpath("/assets"))
    return Config(
        port = env["PORT"]?.toInt() ?: 8080,
        clock = Clock.systemUTC(),
        dbUrl = env.getValue("DB_URL"),
        assetLoader = assetLoader,
        hotReloading = env["HOT_RELOADING"] == "true",
        auth = AuthConfig.fromEnv(env),
        adminEmails = env["ADMIN_USERS"]?.split(" ") ?: emptyList(),
        tokenHashKeyBase64 = randomBytes(numBytes = 32).base64Encode(),
        earliestDate = LocalDate.parse(env.getValue("EARLIEST_DATE")),
        latestDate = env["LATEST_DATE"]?.let { LocalDate.parse(it) } ?: LocalDate.MAX,
        messageLoader = AssetMessageLoader(assetLoader),
    )
}

fun AuthConfig.Companion.fromEnv(env: Map<String, String>): AuthConfig =
    if (env["ENABLE_AUTH"]?.toBooleanStrict() != false) GoogleOauth.fromEnv(env) else NoAuth

fun GoogleOauth.Companion.fromEnv(env: Map<String, String>): GoogleOauth =
    GoogleOauth(
        serverBaseUrl = env["SERVER_BASE_URL"]?.let { URI(it) },
        authServerUrl = null,
        tokenServerUrl = null,
        publicCertsUrl = null,
        clientId = env.getValue("GOOGLE_OAUTH_CLIENT_ID"),
        clientSecret = env.getValue("GOOGLE_OAUTH_CLIENT_SECRET"),
        allowedUserEmails = env.getValue("ALLOWED_USERS").split(" "),
    )
