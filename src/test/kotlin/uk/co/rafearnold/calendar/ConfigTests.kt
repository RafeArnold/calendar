package uk.co.rafearnold.calendar

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class ConfigTests {
    private val env = mapOf("DB_URL" to UUID.randomUUID().toString())
    private val noAuthEnv = env + mapOf("ENABLE_AUTH" to "false")

    @Test
    fun `config can be loaded from map`() {
        val port = Random.nextInt()
        val dbUrl = UUID.randomUUID().toString()
        val hotReloading = Random.nextBoolean()
        val serverBaseUrl = "https://${UUID.randomUUID()}.com/calendar"
        val oauthClientId = UUID.randomUUID().toString()
        val oauthClientSecret = UUID.randomUUID().toString()
        val allowedEmail1 = "${UUID.randomUUID()}@example.com"
        val allowedEmail2 = "${UUID.randomUUID()}@gmail.com"
        val impersonatorEmail2 = "${UUID.randomUUID()}@gmail.com"
        val env =
            mapOf(
                "PORT" to port.toString(),
                "DB_URL" to dbUrl,
                "ASSET_DIRS" to "",
                "HOT_RELOADING" to hotReloading.toString(),
                "SERVER_BASE_URL" to serverBaseUrl,
                "GOOGLE_OAUTH_CLIENT_ID" to oauthClientId,
                "GOOGLE_OAUTH_CLIENT_SECRET" to oauthClientSecret,
                "ALLOWED_USERS" to "$allowedEmail1 $allowedEmail2",
                "IMPERSONATORS" to "$allowedEmail1 $impersonatorEmail2",
            )
        val config = Config.fromEnv(env)
        assertEquals(port, config.port)
        assertEquals(Clock.systemUTC(), config.clock)
        assertEquals(dbUrl, config.dbUrl)
        val assetLoader = config.assetLoader
        val expectedHtmxBytes = javaClass.getResource("/assets/htmx/htmx.min.js")?.readBytes()
        val htmxBytes = assetLoader.load("htmx/htmx.min.js")?.readBytes()
        assertContentEquals(expectedHtmxBytes, htmxBytes)
        assertEquals(hotReloading, config.hotReloading)
        val auth = config.auth
        assertIs<GoogleOauth>(auth)
        assertEquals(URI(serverBaseUrl), auth.serverBaseUrl)
        assertNull(auth.authServerUrl)
        assertNull(auth.tokenServerUrl)
        assertNull(auth.publicCertsUrl)
        assertEquals(oauthClientId, auth.clientId)
        assertEquals(oauthClientSecret, auth.clientSecret)
        assertEquals(listOf(allowedEmail1, allowedEmail2), auth.allowedUserEmails)
        assertEquals(listOf(allowedEmail1, impersonatorEmail2), config.impersonatorEmails)
    }

    @Test
    fun `no auth can be configured from map`() {
        assertEquals(NoAuth, Config.fromEnv(env + mapOf("ENABLE_AUTH" to "false")).auth)
    }

    @Test
    fun `port defaults to 8080`() {
        assertEquals(8080, Config.fromEnv(noAuthEnv).port)
    }

    @Test
    fun `asset loader defaults to just classpath`() {
        val config = Config.fromEnv(noAuthEnv)
        val expectedCssBytes = javaClass.getResource("/assets/index.min.css")?.readBytes()
        val cssBytes = config.assetLoader.load("index.min.css")?.readBytes()
        assertContentEquals(expectedCssBytes, cssBytes)
    }

    @Test
    fun `hot reloading is disabled by default`() {
        assertFalse(Config.fromEnv(noAuthEnv).hotReloading)
    }

    @Test
    fun `server base url defaults to null`() {
        val env =
            mapOf(
                "GOOGLE_OAUTH_CLIENT_ID" to UUID.randomUUID().toString(),
                "GOOGLE_OAUTH_CLIENT_SECRET" to UUID.randomUUID().toString(),
                "ALLOWED_USERS" to "",
            )
        val auth = Config.fromEnv(this.env + env).auth
        assertIs<GoogleOauth>(auth)
        assertNull(auth.serverBaseUrl)
    }

    @Test
    fun `messages are loaded from asset dirs`(
        @TempDir assetDir: Path,
    ) {
        val messagesDir = assetDir.resolve("messages").createDirectories()
        val jan1Text = UUID.randomUUID().toString()
        val may2Text = UUID.randomUUID().toString()
        val dec3Text = UUID.randomUUID().toString()
        val aug4Text = UUID.randomUUID().toString()
        messagesDir.resolve("2024-01-01").createFile().writeText(jan1Text)
        messagesDir.resolve("2024-05-02").createFile().writeText(may2Text)
        messagesDir.resolve("2024-12-03").createFile().writeText(dec3Text)
        messagesDir.resolve("2024-08-04").createFile().writeText(aug4Text)
        val config = Config.fromEnv(noAuthEnv + mapOf("ASSET_DIRS" to assetDir.absolutePathString()))
        val messageLoader = config.messageLoader
        assertEquals(jan1Text, messageLoader[LocalDate.of(2024, 1, 1)])
        assertEquals(may2Text, messageLoader[LocalDate.of(2024, 5, 2)])
        assertEquals(dec3Text, messageLoader[LocalDate.of(2024, 12, 3)])
        assertEquals(aug4Text, messageLoader[LocalDate.of(2024, 8, 4)])
    }

    @Test
    fun `impersonating emails default to empty list`() {
        assertEquals(emptyList(), Config.fromEnv(noAuthEnv).impersonatorEmails)
    }
}
