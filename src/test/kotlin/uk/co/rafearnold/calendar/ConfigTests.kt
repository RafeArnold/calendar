package uk.co.rafearnold.calendar

import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
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
        val assetDir1 = createTempDirectory()
        val assetDir2 = "/app/${UUID.randomUUID()}/assets"
        val assetDirs = "${assetDir1.absolutePathString()},$assetDir2"
        val hotReloading = Random.nextBoolean()
        val serverBaseUrl = "https://${UUID.randomUUID()}.com/calendar"
        val oauthClientId = UUID.randomUUID().toString()
        val oauthClientSecret = UUID.randomUUID().toString()
        val allowedEmail1 = "${UUID.randomUUID()}@example.com"
        val allowedEmail2 = "${UUID.randomUUID()}@gmail.com"
        val env =
            mapOf(
                "PORT" to port.toString(),
                "DB_URL" to dbUrl,
                "ASSET_DIRS" to assetDirs,
                "HOT_RELOADING" to hotReloading.toString(),
                "SERVER_BASE_URL" to serverBaseUrl,
                "GOOGLE_OAUTH_CLIENT_ID" to oauthClientId,
                "GOOGLE_OAUTH_CLIENT_SECRET" to oauthClientSecret,
                "ALLOWED_USERS" to "$allowedEmail1 $allowedEmail2",
            )
        val config = Config.fromEnv(env)
        assertEquals(port, config.port)
        assertEquals(Clock.systemUTC(), config.clock)
        assertEquals(dbUrl, config.dbUrl)
        assertEquals(listOf(assetDir1.absolutePathString(), assetDir2), config.assetDirs)
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
    fun `asset dirs defaults to empty list`() {
        assertEquals(emptyList(), Config.fromEnv(noAuthEnv).assetDirs)
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
}
