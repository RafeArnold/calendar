package uk.co.rafearnold.calendar

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.http4k.base64Encode
import org.http4k.core.Uri
import org.http4k.core.findSingle
import org.http4k.core.queries
import org.http4k.routing.ResourceLoader
import org.http4k.server.Http4kServer
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.readBytes
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class EndToEndTests {
    companion object {
        private lateinit var playwright: Playwright

        @BeforeAll
        @JvmStatic
        fun startup() {
            playwright = Playwright.create()
        }

        @AfterAll
        @JvmStatic
        fun tearAllDown() {
            playwright.close()
        }
    }

    private val browser: Browser = playwright.chromium().launch()
    private lateinit var server: Http4kServer
    private lateinit var dbUrl: String

    @BeforeEach
    fun startupEach() {
        val dbFile = Files.createTempFile("calendar", ".db").also { it.toFile().deleteOnExit() }
        dbUrl = "jdbc:sqlite:${dbFile.toAbsolutePath()}"
    }

    @AfterEach
    fun tearEachDown() {
        browser.close()
        server.stop()
    }

    @Test
    fun `navigate from calendar to a day and back to calendar`() {
        val dayText = "something sweet"
        server = startServer { dayText }
        val page = browser.newPage()
        page.navigateHome(server.port())
        page.clickDay(1)
        page.assertThatDayTextIs(text = dayText)
        page.clickBack()
    }

    @Test
    fun `clicking different days shows different messages`() {
        val message1 = "this is the first message"
        val message2 = "this is another message"
        val message3 = "this one is different"
        val clock = LocalDate.of(2024, 5, 31).toClock()
        val messageLoader =
            MapBackedMessageLoader(
                mapOf(
                    LocalDate.of(2024, 5, 1) to message1,
                    LocalDate.of(2024, 5, 13) to message2,
                    LocalDate.of(2024, 5, 27) to message3,
                ),
            )
        server = startServer(clock = clock, messageLoader = messageLoader)
        val page = browser.newPage()
        page.navigateHome(server.port())
        page.clickDay(1)
        page.assertThatDayTextIs(text = message1)
        page.clickBack()
        page.clickDay(13)
        page.assertThatDayTextIs(text = message2)
        page.clickBack()
        page.clickDay(27)
        page.assertThatDayTextIs(text = message3)
        page.clickBack()
        page.clickDay(1)
        page.assertThatDayTextIs(text = message1)
    }

    @Test
    fun `calendar shows the current month by default`() {
        val message1 = "this is the first message"
        val message2 = "this is another message"
        val message3 = "this one is different"
        val message4 = "another one?"
        val clock = Clock.systemUTC().mutable()
        val messageLoader =
            MapBackedMessageLoader(
                mapOf(
                    LocalDate.of(2024, 5, 1) to message1,
                    LocalDate.of(2024, 6, 1) to message2,
                    LocalDate.of(2024, 2, 1) to message3,
                    LocalDate.of(2023, 2, 1) to message4,
                ),
            )
        server = startServer(clock = clock, messageLoader = messageLoader)
        val page = browser.newPage()

        clock.del = LocalDate.of(2024, 5, Random.nextInt(1, 32)).toClock()
        page.navigateHome(server.port())
        page.assertNumOfDaysInCurrentMonthIs(31)
        page.clickDay(1)
        page.assertThatDayTextIs(text = message1)

        clock.del = LocalDate.of(2024, 6, Random.nextInt(1, 31)).toClock()
        page.navigateHome(server.port())
        page.assertNumOfDaysInCurrentMonthIs(30)
        page.clickDay(1)
        page.assertThatDayTextIs(text = message2)

        clock.del = LocalDate.of(2024, 2, Random.nextInt(1, 30)).toClock()
        page.navigateHome(server.port())
        page.assertNumOfDaysInCurrentMonthIs(29)
        page.clickDay(1)
        page.assertThatDayTextIs(text = message3)

        clock.del = LocalDate.of(2023, 2, Random.nextInt(1, 29)).toClock()
        page.navigateHome(server.port())
        page.assertNumOfDaysInCurrentMonthIs(28)
        page.clickDay(1)
        page.assertThatDayTextIs(text = message4)
    }

    @Test
    fun `trailing and following dates of the surrounding months are displayed`() {
        val clock = Clock.systemUTC().mutable()
        server = startServer(clock = clock) { "whatever" }
        val page = browser.newPage()

        clock.del = LocalDate.of(2024, 5, Random.nextInt(1, 32)).toClock()
        page.navigateHome(server.port())
        page.assertDisplayedDaysOfPreviousMonthAre((29..30).toList())
        page.assertDisplayedDaysOfNextMonthAre((1..2).toList())

        clock.del = LocalDate.of(2024, 6, Random.nextInt(1, 31)).toClock()
        page.navigateHome(server.port())
        page.assertDisplayedDaysOfPreviousMonthAre((27..31).toList())
        page.assertDisplayedDaysOfNextMonthAre(emptyList())

        clock.del = LocalDate.of(2023, 5, Random.nextInt(1, 32)).toClock()
        page.navigateHome(server.port())
        page.assertDisplayedDaysOfPreviousMonthAre(emptyList())
        page.assertDisplayedDaysOfNextMonthAre((1..4).toList())
    }

    @Test
    fun `can navigate to a specific month`() {
        val message1 = "this is the first message"
        val message2 = "this is another message"
        val message3 = "this one is different"
        val message4 = "another one?"
        val messageLoader =
            MapBackedMessageLoader(
                mapOf(
                    LocalDate.now(ZoneOffset.UTC) to message1,
                    LocalDate.of(2024, 5, 1) to message2,
                    LocalDate.of(2024, 2, 1) to message3,
                    LocalDate.of(2023, 2, 1) to message4,
                ),
            )
        server = startServer(clock = Clock.systemUTC(), messageLoader = messageLoader)
        val page = browser.newPage()

        val date = LocalDate.now(ZoneOffset.UTC)
        page.navigateHome(port = server.port(), monthQuery = YearMonth.from(date))
        page.assertNumOfDaysInCurrentMonthIs(date.lengthOfMonth())
        page.clickDay(date.dayOfMonth)
        page.assertThatDayTextIs(text = message1)

        page.navigateHome(port = server.port(), monthQuery = YearMonth.of(2024, 5))
        page.assertNumOfDaysInCurrentMonthIs(31)
        page.clickDay(1)
        page.assertThatDayTextIs(text = message2)

        page.navigateHome(port = server.port(), monthQuery = YearMonth.of(2024, 2))
        page.assertNumOfDaysInCurrentMonthIs(29)
        page.clickDay(1)
        page.assertThatDayTextIs(text = message3)

        page.navigateHome(port = server.port(), monthQuery = YearMonth.of(2023, 2))
        page.assertNumOfDaysInCurrentMonthIs(28)
        page.clickDay(1)
        page.assertThatDayTextIs(text = message4)
    }

    @Test
    fun `back button navigates to month of current date`() {
        val clock = YearMonth.of(2024, 6).toClock()
        server = startServer(clock = clock) { "whatever" }
        val page = browser.newPage()

        page.navigateHome(port = server.port(), monthQuery = YearMonth.of(2024, 5))
        page.clickDay(1)
        page.clickBack()
        page.assertNumOfDaysInCurrentMonthIs(31)
        page.assertDisplayedDaysOfPreviousMonthAre((29..30).toList())
        page.assertDisplayedDaysOfNextMonthAre((1..2).toList())

        page.navigateHome(port = server.port(), monthQuery = YearMonth.of(2024, 6))
        page.clickDay(1)
        page.clickBack()
        page.assertNumOfDaysInCurrentMonthIs(30)
        page.assertDisplayedDaysOfPreviousMonthAre((27..31).toList())
        page.assertDisplayedDaysOfNextMonthAre(emptyList())

        page.navigateHome(port = server.port(), monthQuery = YearMonth.of(2023, 5))
        page.clickDay(1)
        page.clickBack()
        page.assertNumOfDaysInCurrentMonthIs(31)
        page.assertDisplayedDaysOfPreviousMonthAre(emptyList())
        page.assertDisplayedDaysOfNextMonthAre((1..4).toList())
    }

    @Test
    fun `can move between months`() {
        val message1 = "this is the first message"
        val message2 = "this is another message"
        val message3 = "this one is different"
        val messageLoader =
            MapBackedMessageLoader(
                mapOf(
                    LocalDate.of(2024, 4, 1) to message1,
                    LocalDate.of(2024, 5, 1) to message2,
                    LocalDate.of(2024, 6, 1) to message3,
                ),
            )
        server = startServer(clock = YearMonth.of(2024, 6).toClock(), messageLoader = messageLoader)
        val page = browser.newPage()

        page.navigateHome(port = server.port())
        page.assertCurrentMonthIs(YearMonth.of(2024, 6))

        page.clickPreviousMonth()
        page.assertCurrentMonthIs(YearMonth.of(2024, 5))
        page.clickDay(1)
        page.assertThatDayTextIs(message2)

        page.clickBack()
        page.clickPreviousMonth()
        page.assertCurrentMonthIs(YearMonth.of(2024, 4))
        page.clickDay(1)
        page.assertThatDayTextIs(message1)

        page.clickBack()
        assertThat(page.nextMonthButton()).isVisible()
        page.clickNextMonth()
        page.assertCurrentMonthIs(YearMonth.of(2024, 5))
        page.clickDay(1)
        page.assertThatDayTextIs(message2)

        page.clickBack()
        assertThat(page.nextMonthButton()).isVisible()
        page.clickNextMonth()
        page.assertCurrentMonthIs(YearMonth.of(2024, 6))
        page.clickDay(1)
        page.assertThatDayTextIs(message3)
    }

    @Test
    fun `can navigate to current month`() {
        val message1 = "this is the first message"
        val message2 = "this is another message"
        val message3 = "this one is different"
        val messageLoader =
            MapBackedMessageLoader(
                mapOf(
                    LocalDate.of(2024, 4, 1) to message1,
                    LocalDate.of(2024, 5, 1) to message2,
                    LocalDate.of(2024, 6, 1) to message3,
                ),
            )
        val clock = LocalDate.of(2024, 5, 9).toMutableClock()
        server = startServer(clock = clock, messageLoader = messageLoader)
        val page = browser.newPage()

        page.navigateHome(port = server.port())
        page.assertCurrentMonthIs(YearMonth.of(2024, 5))
        page.clickDay(1)
        page.assertThatDayTextIs(message2)

        clock.del = LocalDate.of(2024, 4, 13).toClock()
        page.clickBack()
        page.clickToday()
        page.assertCurrentMonthIs(YearMonth.of(2024, 4))
        page.clickDay(1)
        page.assertThatDayTextIs(message1)

        clock.del = LocalDate.of(2024, 4, 25).toClock()
        page.clickBack()
        // Clicking today while already on the current month should do nothing.
        page.clickToday()
        page.assertCurrentMonthIs(YearMonth.of(2024, 4))
        page.clickDay(1)
        page.assertThatDayTextIs(message1)

        clock.del = LocalDate.of(2024, 6, 1).toClock()
        page.clickBack()
        page.clickToday()
        page.assertCurrentMonthIs(YearMonth.of(2024, 6))
        page.clickDay(1)
        page.assertThatDayTextIs(message3)
    }

    @Test
    fun `days that have already been opened are differentiated from unopened days`() {
        val clock = LocalDate.of(2024, 7, 31).toClock()
        server = startServer(clock = clock) { "whatever" }
        val page = browser.newPage()

        page.navigateHome(port = server.port())
        page.assertCurrentMonthIs(YearMonth.of(2024, 7))
        page.assertOpenedDaysAre(emptyList(), YearMonth.of(2024, 7))

        page.clickDay(1)
        page.clickBack()
        page.assertOpenedDaysAre(listOf(1), YearMonth.of(2024, 7))

        page.clickDay(5)
        page.clickBack()
        page.assertOpenedDaysAre(listOf(1, 5), YearMonth.of(2024, 7))

        // Clicking an already opened day changes nothing.
        page.clickDay(1)
        page.clickBack()
        page.assertOpenedDaysAre(listOf(1, 5), YearMonth.of(2024, 7))

        // Restarting the server changes nothing.
        server.stop()
        server = startServer(clock = clock) { "whatever" }
        page.navigateHome(port = server.port())
        page.assertOpenedDaysAre(listOf(1, 5), YearMonth.of(2024, 7))
    }

    @Test
    fun `cannot open days in the future`() {
        val clock = LocalDate.of(2024, 7, 21).toClock()
        server = startServer(clock = clock) { "whatever" }
        val page = browser.newPage()

        page.navigateHome(port = server.port())

        page.assertDaysAreEnabled(1..21)
        page.assertDaysAreDisabled(22..31)

        page.clickDay(21)
        page.assertThatDayTextIs("whatever")
        page.clickBack()
    }

    @Test
    @OptIn(ExperimentalPathApi::class)
    fun `the image of the currently viewed month is displayed`(
        @TempDir assetsDir: Path,
    ) {
        val clock = LocalDate.of(2024, 7, 21).toClock()
        Path("src/main/resources/assets").copyToRecursively(assetsDir, followLinks = false, overwrite = true)
        val monthImagesDir = assetsDir.resolve("month-images").apply { Files.createDirectories(this) }
        copyImage("cat-1.jpg", monthImagesDir.resolve("2024-07.jpg"))
        copyImage("cat-2.jpg", monthImagesDir.resolve("2024-06.jpg"))
        copyImage("cat-3.jpg", monthImagesDir.resolve("2024-08.jpg"))
        val assetLoader = ResourceLoader.Directory(baseDir = assetsDir.toString())
        server = startServer(clock = clock, assetLoader = assetLoader) { "whatever" }
        val page = browser.newPage()

        page.navigateHome(port = server.port())

        page.assertMonthImageIs(assetsDir, YearMonth.of(2024, 7))

        page.clickNextMonth()

        page.assertMonthImageIs(assetsDir, YearMonth.of(2024, 8))

        page.clickPreviousMonth()
        page.clickPreviousMonth()

        page.assertMonthImageIs(assetsDir, YearMonth.of(2024, 6))
    }

    @Test
    fun `calendar is accessible after authenticating`() {
        val today = LocalDate.of(2024, 8, 10)
        val clock = today.toClock()
        GoogleOAuthServer(clock = clock).use { authServer ->
            val allowedEmail = "test@example.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(allowedEmail))
            server = startServer(clock = clock, auth = auth) { "something sweet" }
            val page = browser.newPage()

            // Authenticate
            val authCode = UUID.randomUUID().toString()
            page.login(email = allowedEmail, authCode = authCode, authServer = authServer)

            // Client was redirected to the home page after authenticating.
            assertThat(page).hasURL(server.uri("/").toASCIIString())

            authServer.verifyTokenWasExchanged(
                authCode = authCode,
                redirectUri = server.oauthUri(code = null, state = null).toASCIIString(),
            )
            // Only one token exchange request was sent to the auth server.
            assertEquals(1, authServer.allTokenExchangeServedRequests().size)
            authServer.resetRequests()

            // Verify that the app works as expected.
            page.assertCurrentMonthIs(today.toYearMonth())
            page.clickDay(1)
            page.assertThatDayTextIs("something sweet")
            page.clickBack()
            page.clickNextMonth()
            page.assertCurrentMonthIs(today.toYearMonth().plusMonths(1))

            // No more requests made to the auth server after the token is exchanged.
            assertEquals(0, authServer.allNonCertRequests().size)
        }
    }

    @Test
    fun `user data is persisted across sessions`() {
        val today = LocalDate.of(2024, 8, 31)
        val clock = today.toClock()
        GoogleOAuthServer(clock = clock).use { authServer ->
            val userEmail = "test@example.com"
            val googleSubjectId = UUID.randomUUID().toString()
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail))
            server = startServer(clock = clock, auth = auth) { "something sweet" }

            val page1 = browser.newPage()
            page1.login(email = userEmail, googleSubjectId = googleSubjectId, authServer = authServer)
            page1.assertCurrentMonthIs(today.toYearMonth())
            page1.assertOpenedDaysAre(emptyList(), today.toYearMonth())
            page1.clickDay(1)
            page1.clickBack()
            page1.clickDay(14)
            page1.clickBack()
            page1.assertOpenedDaysAre(listOf(1, 14), today.toYearMonth())

            val page2 = browser.newPage()
            page2.login(email = userEmail, googleSubjectId = googleSubjectId, authServer = authServer)
            page2.assertCurrentMonthIs(today.toYearMonth())
            page2.assertOpenedDaysAre(listOf(1, 14), today.toYearMonth())
            page2.clickDay(3)
            page2.clickBack()
            page2.clickDay(25)
            page2.clickBack()
            page2.assertOpenedDaysAre(listOf(1, 3, 14, 25), today.toYearMonth())
        }
    }

    @Test
    fun `users have individual data`() {
        val today = LocalDate.of(2024, 8, 31)
        val clock = today.toClock()
        GoogleOAuthServer(clock = clock).use { authServer ->
            val userEmail1 = "test@example.com"
            val userEmail2 = "me@gmail.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail1, userEmail2))
            server = startServer(clock = clock, auth = auth) { "something sweet" }

            val page1 = browser.newPage()
            page1.login(email = userEmail1, authServer = authServer)
            page1.assertCurrentMonthIs(today.toYearMonth())
            page1.assertOpenedDaysAre(emptyList(), today.toYearMonth())
            page1.clickDay(1)
            page1.clickBack()
            page1.clickDay(14)
            page1.clickBack()
            page1.assertOpenedDaysAre(listOf(1, 14), today.toYearMonth())

            val page2 = browser.newPage()
            page2.login(email = userEmail2, authServer = authServer)
            page2.assertCurrentMonthIs(today.toYearMonth())
            page2.assertOpenedDaysAre(emptyList(), today.toYearMonth())
            page2.clickDay(3)
            page2.clickBack()
            page2.clickDay(25)
            page2.clickBack()
            page2.assertOpenedDaysAre(listOf(3, 25), today.toYearMonth())

            page1.clickDay(6)
            page1.clickBack()
            page1.assertOpenedDaysAre(listOf(1, 6, 14), today.toYearMonth())
        }
    }

    @Test
    fun `user can logout`() {
        GoogleOAuthServer().use { authServer ->
            val userEmail = "test@gmail.com"
            val authConfig = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail))
            server = startServer(auth = authConfig) { "whatever" }
            val page = browser.newPage()
            page.login(email = userEmail, authCode = UUID.randomUUID().toString(), authServer = authServer)
            assertThat(page.calendar()).isVisible()
            page.clickLogout()
            page.assertIsOnAuthenticationPage(authServer)
            page.navigate(server.uri("/").toASCIIString())
            page.assertIsOnAuthenticationPage(authServer)
        }
    }

    @Test
    fun `previously opened days are displayed`() {
        val today = LocalDate.of(2024, 8, 21)
        val dayTexts =
            mapOf(
                LocalDate.of(2024, 8, 21) to UUID.randomUUID().toString(),
                LocalDate.of(2024, 8, 20) to UUID.randomUUID().toString(),
                LocalDate.of(2024, 8, 19) to UUID.randomUUID().toString(),
                LocalDate.of(2024, 8, 10) to UUID.randomUUID().toString(),
                LocalDate.of(2024, 7, 20) to UUID.randomUUID().toString(),
                LocalDate.of(2024, 7, 10) to UUID.randomUUID().toString(),
            )
        server = startServer(clock = today.toClock(), messageLoader = MapBackedMessageLoader(dayTexts))
        val page = browser.newPage()

        val previousDays = mutableListOf<PreviousDay>()

        page.navigateHome(port = server.port())
        page.assertPreviousDaysAre(previousDays)

        page.clickDay(dayNum = 20)
        page.assertPreviousDaysAre(previousDays)

        page.clickBack()
        previousDays.add(PreviousDay(text = dayTexts[LocalDate.of(2024, 8, 20)]!!, date = "Tue, 20 Aug 2024"))
        page.assertPreviousDaysAre(previousDays)

        page.reload()
        page.assertPreviousDaysAre(previousDays)

        page.clickDay(dayNum = 19)
        page.clickBack()
        previousDays.add(PreviousDay(text = dayTexts[LocalDate.of(2024, 8, 19)]!!, date = "Mon, 19 Aug 2024"))
        page.assertPreviousDaysAre(previousDays)

        page.clickDay(dayNum = 21)
        page.clickBack()
        previousDays.add(0, PreviousDay(text = dayTexts[LocalDate.of(2024, 8, 21)]!!, date = "Wed, 21 Aug 2024"))
        page.assertPreviousDaysAre(previousDays)

        page.clickDay(dayNum = 10)
        page.clickBack()
        previousDays.add(PreviousDay(text = dayTexts[LocalDate.of(2024, 8, 10)]!!, date = "Sat, 10 Aug 2024"))
        page.assertPreviousDaysAre(previousDays)

        page.clickNextMonth()
        page.assertCurrentMonthIs(today.plusMonths(1).toYearMonth())
        page.assertPreviousDaysAre(previousDays)

        page.reload()
        page.assertPreviousDaysAre(previousDays)

        page.clickPreviousMonth()
        page.clickPreviousMonth()
        page.assertCurrentMonthIs(today.minusMonths(1).toYearMonth())
        page.assertPreviousDaysAre(previousDays)

        page.reload()
        page.assertPreviousDaysAre(previousDays)

        page.clickDay(dayNum = 10)
        page.clickBack()
        previousDays.add(PreviousDay(text = dayTexts[LocalDate.of(2024, 7, 10)]!!, date = "Wed, 10 Jul 2024"))
        page.assertPreviousDaysAre(previousDays)

        page.clickDay(dayNum = 20)
        page.clickBack()
        previousDays.add(4, PreviousDay(text = dayTexts[LocalDate.of(2024, 7, 20)]!!, date = "Sat, 20 Jul 2024"))
        page.assertPreviousDaysAre(previousDays)
    }

    @Test
    fun `previous days are loaded lazily`() {
        val today = LocalDate.of(2024, 8, 21)
        val dayTexts = List(100) { today.minusDays(it.toLong()) to UUID.randomUUID().toString() }.toMap()
        server = startServer(clock = today.toClock(), messageLoader = MapBackedMessageLoader(dayTexts))
        val dateTimeFormatter = DateTimeFormatter.ofPattern("eee, d MMM yyyy")

        fun LocalDate.toPreviousDay(): PreviousDay =
            PreviousDay(text = dayTexts[this]!!, date = format(dateTimeFormatter))

        val openedDays = (0 until 100).shuffled().take(37).sorted().map { today.minusDays(it.toLong()) }

        DSL.using(dbUrl).use { ctx ->
            val daysRepository = DaysRepository(ctx, today.toClock())
            openedDays.forEach { daysRepository.markDayAsOpened(User(0, "", ""), it) }
        }

        val page = browser.newPage()
        page.navigateHome(port = server.port())
        val previousDays = openedDays.take(20).map { it.toPreviousDay() }.toMutableList()
        assertEquals(20, previousDays.size)
        page.assertPreviousDaysAre(previousDays)

        page.previousDayTexts().nth(19).scrollIntoViewIfNeeded()
        previousDays.addAll(openedDays.subList(20, 30).map { it.toPreviousDay() })
        page.assertPreviousDaysAre(previousDays)

        page.previousDayTexts().nth(29).scrollIntoViewIfNeeded()
        previousDays.addAll(openedDays.subList(30, 37).map { it.toPreviousDay() })
        page.assertPreviousDaysAre(previousDays)
    }

    @Test
    fun `month navigation only updates the calendar`() {
        val today = LocalDate.now()
        val openedDays = List(50) { today.minusDays(it.toLong()) }
        val dayTexts = openedDays.associateWith { UUID.randomUUID().toString() }
        server = startServer(clock = today.toClock(), messageLoader = MapBackedMessageLoader(dayTexts))

        DSL.using(dbUrl).use { ctx ->
            val daysRepository = DaysRepository(ctx, today.toClock())
            openedDays.forEach { daysRepository.markDayAsOpened(User(0, "", ""), it) }
        }

        val page = browser.newPage()
        page.navigateHome(port = server.port())
        page.previousDayTexts().nth(19).scrollIntoViewIfNeeded()
        page.previousDayTexts().nth(29).scrollIntoViewIfNeeded()
        page.previousDayTexts().nth(39).scrollIntoViewIfNeeded()
        assertThat(page.previousDayTexts()).hasCount(50)

        page.clickNextMonth()
        page.assertCurrentMonthIs(today.plusMonths(1).toYearMonth())

        assertThat(page.previousDayTexts()).hasCount(50)
        assertThat(page.calendar()).hasCount(1)
        assertThat(page.logoutButton()).hasCount(1)
    }

    @Test
    fun `back to top button is displayed when calendar is scrolled out of view`() {
        val today = LocalDate.now()
        val openedDays = List(5) { today.minusDays(it.toLong()) }
        val dayTexts = openedDays.associateWith { UUID.randomUUID().toString() }
        server = startServer(clock = today.toClock(), messageLoader = MapBackedMessageLoader(dayTexts))

        DSL.using(dbUrl).use { ctx ->
            val daysRepository = DaysRepository(ctx, today.toClock())
            openedDays.forEach { daysRepository.markDayAsOpened(User(0, "", ""), it) }
        }

        val page = browser.newPage()
        page.navigateHome(port = server.port())

        assertThat(page.backToTopButton()).not().isVisible()

        page.previousDayTexts().nth(4).scrollIntoViewIfNeeded()

        assertThat(page.backToTopButton()).isVisible()

        page.backToTopButton().click()

        assertEquals(0, page.evaluate("window.scrollY"))
    }

    @Test
    fun `users with permission can impersonate other users`() {
        val clock = LocalDate.of(2024, 5, 31).toClock()
        GoogleOAuthServer(clock = clock).use { authServer ->
            val impersonatorEmail = "admin@example.com"
            val otherUserEmail = "other@example.com"
            val allowedUserEmails = listOf(impersonatorEmail, otherUserEmail)
            val auth = authServer.toAuthConfig(allowedUserEmails = allowedUserEmails)
            server =
                startServer(clock = clock, auth = auth, impersonatorEmails = listOf(impersonatorEmail)) { "whatever" }

            val otherUserPage = browser.newPage()
            otherUserPage.login(email = otherUserEmail, authServer = authServer)
            otherUserPage.assertCurrentMonthIs(YearMonth.of(2024, 5))
            otherUserPage.clickDay(1)
            otherUserPage.clickBack()
            otherUserPage.assertOpenedDaysAre(listOf(1), YearMonth.of(2024, 5))
            otherUserPage.clickPreviousMonth()
            otherUserPage.assertCurrentMonthIs(YearMonth.of(2024, 4))
            otherUserPage.clickDay(13)
            otherUserPage.clickBack()
            otherUserPage.assertOpenedDaysAre(listOf(13), YearMonth.of(2024, 4))

            val impersonatorPage = browser.newPage()
            impersonatorPage.login(email = impersonatorEmail, authServer = authServer)
            impersonatorPage.assertCurrentMonthIs(YearMonth.of(2024, 5))
            impersonatorPage.assertOpenedDaysAre(emptyList(), YearMonth.of(2024, 5))

            impersonatorPage.impersonate(emailToImpersonate = otherUserEmail)
            assertThat(impersonatorPage.stopImpersonatingButton()).isVisible()
            assertThat(impersonatorPage.impersonateEmailInput()).not().isVisible()
            assertThat(impersonatorPage.impersonateButton()).not().isVisible()
            impersonatorPage.assertCurrentMonthIs(YearMonth.of(2024, 5))
            impersonatorPage.assertOpenedDaysAre(listOf(1), YearMonth.of(2024, 5))
            impersonatorPage.clickPreviousMonth()
            impersonatorPage.assertCurrentMonthIs(YearMonth.of(2024, 4))
            impersonatorPage.assertOpenedDaysAre(listOf(13), YearMonth.of(2024, 4))
        }
    }

    @Test
    fun `opening unopened days while impersonating does not update anyone's opened days`() {
        val month = YearMonth.of(2024, 5)
        val clock = LocalDate.of(2024, 5, 31).toClock()
        GoogleOAuthServer(clock = clock).use { authServer ->
            val impersonatorEmail = "admin@example.com"
            val otherUserEmail = "other@example.com"
            val allowedUserEmails = listOf(impersonatorEmail, otherUserEmail)
            val auth = authServer.toAuthConfig(allowedUserEmails = allowedUserEmails)
            server =
                startServer(clock = clock, auth = auth, impersonatorEmails = listOf(impersonatorEmail)) { "whatever" }

            val otherUserPage = browser.newPage()
            otherUserPage.login(email = otherUserEmail, authServer = authServer)
            otherUserPage.assertCurrentMonthIs(month)
            otherUserPage.clickDay(1)
            otherUserPage.clickBack()
            otherUserPage.assertOpenedDaysAre(listOf(1), month)

            val impersonatorPage = browser.newPage()
            impersonatorPage.login(email = impersonatorEmail, authServer = authServer)
            impersonatorPage.assertCurrentMonthIs(month)
            impersonatorPage.clickDay(25)
            impersonatorPage.clickBack()
            impersonatorPage.assertOpenedDaysAre(listOf(25), month)

            impersonatorPage.impersonate(emailToImpersonate = otherUserEmail)
            impersonatorPage.assertCurrentMonthIs(month)
            impersonatorPage.assertOpenedDaysAre(listOf(1), month)

            impersonatorPage.clickDay(20)
            impersonatorPage.clickBack()
            impersonatorPage.assertOpenedDaysAre(listOf(1), month)

            otherUserPage.reload()
            otherUserPage.assertCurrentMonthIs(month)
            otherUserPage.assertOpenedDaysAre(listOf(1), month)
            otherUserPage.clickDay(20)
            otherUserPage.clickBack()
            otherUserPage.assertOpenedDaysAre(listOf(1, 20), month)

            impersonatorPage.reload()
            impersonatorPage.assertCurrentMonthIs(month)
            impersonatorPage.assertOpenedDaysAre(listOf(1, 20), month)

            impersonatorPage.stopImpersonating()
            impersonatorPage.assertCurrentMonthIs(month)
            impersonatorPage.assertOpenedDaysAre(listOf(25), month)

            impersonatorPage.clickDay(5)
            impersonatorPage.clickBack()
            impersonatorPage.assertOpenedDaysAre(listOf(5, 25), month)
            otherUserPage.reload()
            otherUserPage.assertCurrentMonthIs(month)
            otherUserPage.assertOpenedDaysAre(listOf(1, 20), month)
        }
    }

    @Test
    fun `previous days of impersonated user are displayed while impersonating`() {
        val today = LocalDate.of(2024, 5, 25)
        GoogleOAuthServer(clock = today.toClock()).use { authServer ->
            val impersonatorEmail = "admin@example.com"
            val otherUserEmail = "other@example.com"
            val allowedUserEmails = listOf(impersonatorEmail, otherUserEmail)
            val auth = authServer.toAuthConfig(allowedUserEmails = allowedUserEmails)
            val dayTexts = List(30) { today.minusDays(it.toLong()) to UUID.randomUUID().toString() }.toMap()
            server =
                startServer(
                    clock = today.toClock(),
                    auth = auth,
                    impersonatorEmails = listOf(impersonatorEmail),
                    messageLoader = MapBackedMessageLoader(dayTexts),
                )
            val dateTimeFormatter = DateTimeFormatter.ofPattern("eee, d MMM yyyy")

            fun LocalDate.toPreviousDay(): PreviousDay =
                PreviousDay(text = dayTexts[this]!!, date = format(dateTimeFormatter))

            val openedDays = List(30) { today.minusDays(it.toLong()) }

            val otherUserPage = browser.newPage()
            otherUserPage.login(email = otherUserEmail, authServer = authServer)

            DSL.using(dbUrl).use { ctx ->
                val otherUser = UserRepository(ctx).getByEmail(otherUserEmail)!!
                val daysRepository = DaysRepository(ctx, today.toClock())
                openedDays.forEach { daysRepository.markDayAsOpened(otherUser, it) }
            }

            var expectedPreviousDays = openedDays.take(20).map { it.toPreviousDay() }.toMutableList()
            otherUserPage.reload()
            otherUserPage.assertPreviousDaysAre(expectedPreviousDays)
            otherUserPage.previousDayTexts().nth(19).scrollIntoViewIfNeeded()
            expectedPreviousDays.addAll(openedDays.subList(20, 30).map { it.toPreviousDay() })
            otherUserPage.assertPreviousDaysAre(expectedPreviousDays)

            val impersonatorPage = browser.newPage()
            impersonatorPage.login(email = impersonatorEmail, authServer = authServer)
            impersonatorPage.assertPreviousDaysAre(emptyList())

            impersonatorPage.impersonate(emailToImpersonate = otherUserEmail)
            expectedPreviousDays = openedDays.take(20).map { it.toPreviousDay() }.toMutableList()
            impersonatorPage.assertPreviousDaysAre(expectedPreviousDays)
            impersonatorPage.previousDayTexts().nth(19).scrollIntoViewIfNeeded()
            expectedPreviousDays.addAll(openedDays.subList(20, 30).map { it.toPreviousDay() })
            impersonatorPage.assertPreviousDaysAre(expectedPreviousDays)
        }
    }

    @Test
    fun `non-impersonators cannot impersonate`() {
        val today = LocalDate.of(2024, 5, 25)
        GoogleOAuthServer(clock = today.toClock()).use { authServer ->
            val impersonatorEmail1 = "admin@example.com"
            val impersonatorEmail2 = "impersonator@gmail.com"
            val otherUserEmail1 = "other@example.com"
            val otherUserEmail2 = "someone@gmail.com"
            val allowedUserEmails = listOf(impersonatorEmail1, impersonatorEmail2, otherUserEmail1, otherUserEmail2)
            val auth = authServer.toAuthConfig(allowedUserEmails = allowedUserEmails)
            val dayTexts = List(30) { today.minusDays(it.toLong()) to UUID.randomUUID().toString() }.toMap()
            server =
                startServer(
                    clock = today.toClock(),
                    auth = auth,
                    impersonatorEmails = listOf(impersonatorEmail1, impersonatorEmail2),
                    messageLoader = MapBackedMessageLoader(dayTexts),
                )

            val impersonator1Page = browser.newPage()
            impersonator1Page.login(email = impersonatorEmail1, authServer = authServer)
            assertThat(impersonator1Page.impersonateEmailInput()).isVisible()
            assertThat(impersonator1Page.impersonateButton()).isVisible()
            assertThat(impersonator1Page.stopImpersonatingButton()).not().isVisible()

            val otherUser1Page = browser.newPage()
            otherUser1Page.login(email = otherUserEmail1, authServer = authServer)
            assertThat(otherUser1Page.impersonateEmailInput()).not().isVisible()
            assertThat(otherUser1Page.impersonateButton()).not().isVisible()
            assertThat(otherUser1Page.stopImpersonatingButton()).not().isVisible()

            val impersonator2Page = browser.newPage()
            impersonator2Page.login(email = impersonatorEmail2, authServer = authServer)
            assertThat(impersonator2Page.impersonateEmailInput()).isVisible()
            assertThat(impersonator2Page.impersonateButton()).isVisible()
            assertThat(impersonator2Page.stopImpersonatingButton()).not().isVisible()

            val otherUser2Page = browser.newPage()
            otherUser2Page.login(email = otherUserEmail2, authServer = authServer)
            assertThat(otherUser2Page.impersonateEmailInput()).not().isVisible()
            assertThat(otherUser2Page.impersonateButton()).not().isVisible()
            assertThat(otherUser2Page.stopImpersonatingButton()).not().isVisible()
        }
    }

    @Test
    fun `logging out also stop impersonating`() {
        val today = LocalDate.of(2024, 5, 25)
        GoogleOAuthServer(clock = today.toClock()).use { authServer ->
            val impersonatorSubjectId = UUID.randomUUID().toString()
            val impersonatorEmail = "admin@example.com"
            val otherUserSubjectId = UUID.randomUUID().toString()
            val otherUserEmail = "other@example.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(impersonatorEmail, otherUserEmail))
            server =
                startServer(
                    clock = today.toClock(),
                    auth = auth,
                    impersonatorEmails = listOf(impersonatorEmail),
                ) { "whatever" }

            val otherUserPage = browser.newPage()
            otherUserPage.login(
                email = otherUserEmail,
                googleSubjectId = otherUserSubjectId,
                authServer = authServer,
            )
            otherUserPage.assertCurrentMonthIs(today.toYearMonth())
            otherUserPage.clickDay(20)
            otherUserPage.clickBack()
            otherUserPage.assertOpenedDaysAre(listOf(20), today.toYearMonth())
            assertThat(otherUserPage.previousDayTexts()).hasCount(1)

            val impersonatorPage = browser.newPage()
            impersonatorPage.login(
                email = impersonatorEmail,
                googleSubjectId = impersonatorSubjectId,
                authServer = authServer,
            )
            impersonatorPage.assertCurrentMonthIs(today.toYearMonth())
            impersonatorPage.assertOpenedDaysAre(emptyList(), today.toYearMonth())
            assertThat(impersonatorPage.previousDayTexts()).hasCount(0)

            impersonatorPage.impersonate(emailToImpersonate = otherUserEmail)
            impersonatorPage.assertCurrentMonthIs(today.toYearMonth())
            impersonatorPage.assertOpenedDaysAre(listOf(20), today.toYearMonth())
            assertThat(impersonatorPage.previousDayTexts()).hasCount(1)

            impersonatorPage.clickLogout()
            impersonatorPage.assertIsOnAuthenticationPage(authServer)
            impersonatorPage.login(
                email = impersonatorEmail,
                googleSubjectId = impersonatorSubjectId,
                authServer = authServer,
            )
            impersonatorPage.assertCurrentMonthIs(today.toYearMonth())
            impersonatorPage.assertOpenedDaysAre(emptyList(), today.toYearMonth())
            assertThat(impersonatorPage.previousDayTexts()).hasCount(0)
        }
    }

    @Test
    fun `the user being impersonated is displayed`() {
        val today = LocalDate.of(2024, 5, 25)
        GoogleOAuthServer(clock = today.toClock()).use { authServer ->
            val impersonatorEmail = "admin@example.com"
            val otherUserEmail = "other@example.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(impersonatorEmail, otherUserEmail))
            server =
                startServer(
                    clock = today.toClock(),
                    auth = auth,
                    impersonatorEmails = listOf(impersonatorEmail),
                ) { "whatever" }

            browser.newPage().login(email = otherUserEmail, authServer = authServer)

            val impersonatorPage = browser.newPage()
            impersonatorPage.login(email = impersonatorEmail, authServer = authServer)
            assertThat(impersonatorPage.impersonatingMessage()).not().isVisible()

            impersonatorPage.impersonate(emailToImpersonate = otherUserEmail)
            assertThat(impersonatorPage.impersonatingMessage()).isVisible()
            assertThat(impersonatorPage.impersonatingMessage()).hasText("impersonating $otherUserEmail")

            impersonatorPage.stopImpersonating()
            assertThat(impersonatorPage.impersonatingMessage()).not().isVisible()
        }
    }

    @Test
    fun `impersonating a non-existent user shows an error`() {
        val today = LocalDate.of(2024, 5, 25)
        GoogleOAuthServer(clock = today.toClock()).use { authServer ->
            val impersonatorEmail = "admin@example.com"
            val otherUserEmail = "other@example.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(impersonatorEmail, otherUserEmail))
            server =
                startServer(
                    clock = today.toClock(),
                    auth = auth,
                    impersonatorEmails = listOf(impersonatorEmail),
                ) { "whatever" }

            val impersonatorPage = browser.newPage()
            impersonatorPage.clock().install()
            impersonatorPage.login(email = impersonatorEmail, authServer = authServer)
            assertThat(impersonatorPage.error()).not().isVisible()

            impersonatorPage.impersonate(emailToImpersonate = otherUserEmail)
            assertThat(impersonatorPage.error()).isVisible()
            val expectedError = "user $otherUserEmail not found"
            assertThat(impersonatorPage.error()).hasText(expectedError)
            assertThat(impersonatorPage.impersonatingMessage()).not().isVisible()
            assertThat(impersonatorPage.stopImpersonatingButton()).not().isVisible()
            assertThat(impersonatorPage.impersonateEmailInput()).isVisible()
            assertThat(impersonatorPage.impersonateButton()).isVisible()

            impersonatorPage.clock().fastForward(4500)
            assertThat(impersonatorPage.error()).isVisible()
            assertThat(impersonatorPage.error()).hasText(expectedError)

            impersonatorPage.clock().fastForward(1000)
            assertThat(impersonatorPage.error()).not().isVisible()

            impersonatorPage.reload()
            assertThat(impersonatorPage.error()).not().isVisible()
        }
    }

    @Test
    fun `impersonation is stopped when user ceases to exist while impersonating`() {
        val today = LocalDate.of(2024, 5, 25)
        GoogleOAuthServer(clock = today.toClock()).use { authServer ->
            val impersonatorEmail = "admin@example.com"
            val otherUserEmail = "other@example.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(impersonatorEmail, otherUserEmail))
            server =
                startServer(
                    clock = today.toClock(),
                    auth = auth,
                    impersonatorEmails = listOf(impersonatorEmail),
                ) { "whatever" }

            val otherUserPage = browser.newPage()
            otherUserPage.login(email = otherUserEmail, authServer = authServer)
            otherUserPage.clickDay(1)
            otherUserPage.clickBack()
            otherUserPage.assertOpenedDaysAre(listOf(1), today.toYearMonth())
            assertThat(otherUserPage.previousDayTexts()).hasCount(1)

            val impersonatorPage = browser.newPage()
            impersonatorPage.clock().install()
            impersonatorPage.login(email = impersonatorEmail, authServer = authServer)
            assertThat(impersonatorPage.impersonatingMessage()).not().isVisible()
            impersonatorPage.assertOpenedDaysAre(emptyList(), today.toYearMonth())
            assertThat(impersonatorPage.previousDayTexts()).hasCount(0)

            impersonatorPage.impersonate(emailToImpersonate = otherUserEmail)
            assertThat(impersonatorPage.impersonatingMessage()).isVisible()
            impersonatorPage.assertOpenedDaysAre(listOf(1), today.toYearMonth())
            assertThat(impersonatorPage.previousDayTexts()).hasCount(1)

            executeStatement(dbUrl = dbUrl) { stmt ->
                stmt.executeUpdate("DELETE FROM users WHERE email_address = '$otherUserEmail'")
            }

            impersonatorPage.clickPreviousMonth()
            assertThat(impersonatorPage.impersonatingMessage()).not().isVisible()
            impersonatorPage.assertOpenedDaysAre(emptyList(), today.toYearMonth())
            assertThat(impersonatorPage.previousDayTexts()).hasCount(0)

            impersonatorPage.reload()
            assertThat(impersonatorPage.impersonatingMessage()).not().isVisible()
            impersonatorPage.assertOpenedDaysAre(emptyList(), today.toYearMonth())
            assertThat(impersonatorPage.previousDayTexts()).hasCount(0)
        }
    }

    @Test
    fun `user is redirected when id token becomes invalid while using the calendar`() {
        val now = LocalDateTime.of(2024, 8, 26, 15, 25, 30)
        val clock = now.toClock().mutable()
        GoogleOAuthServer(clock = clock).use { authServer ->
            val userEmail = "test@gmail.com"
            val auth = authServer.toAuthConfig(allowedUserEmails = listOf(userEmail))
            server = startServer(clock = clock, auth = auth) { "whatever" }

            val page = browser.newPage()
            page.login(email = userEmail, authServer = authServer)
            page.assertCurrentMonthIs(now.toYearMonth())
            page.clickPreviousMonth()
            page.assertCurrentMonthIs(now.minusMonths(1).toYearMonth())

            clock.del = now.plusHours(2).toClock()

            page.clickNextMonth()
            page.assertIsOnAuthenticationPage(authServer)
        }
    }

    private fun Page.login(
        email: String,
        googleSubjectId: String = UUID.randomUUID().toString(),
        authCode: String = UUID.randomUUID().toString(),
        authServer: GoogleOAuthServer,
    ) {
        authServer.stubAuthenticationPage(redirectUri = server.oauthUri(code = null, state = null), authCode = authCode)
        authServer.stubTokenExchange(authCode = authCode, email = email, subject = googleSubjectId)
        navigate(server.uri("/").toASCIIString())
        assertIsOnAuthenticationPage(authServer)
        val loginButton = getByTestId(GoogleOAuthServer.LOGIN_BUTTON_TEST_ID)
        assertThat(loginButton).isVisible()
        loginButton.click()
    }

    private fun Page.assertMonthImageIs(
        assetsDir: Path,
        month: YearMonth,
    ) {
        val monthImage = getByTestId("month-image")
        val formattedMonth = month.format(DateTimeFormatter.ofPattern("yyyy-MM"))
        assertThat(monthImage).hasAttribute("src", "/assets/month-images/$formattedMonth.jpg")
        request().get(server.uri(monthImage.getAttribute("src")).toASCIIString()).let { response ->
            assertThat(response).isOK()
            assertContentEquals(assetsDir.resolve("month-images/$formattedMonth.jpg").readBytes(), response.body())
        }
    }

    private fun startServer(
        clock: Clock = Clock.systemUTC(),
        assetLoader: ResourceLoader = ResourceLoader.Classpath(basePackagePath = "/assets"),
        auth: AuthConfig = NoAuth,
        impersonatorEmails: List<String> = emptyList(),
        messageLoader: MessageLoader,
    ): Http4kServer =
        Config(
            port = 0,
            clock = clock,
            dbUrl = dbUrl,
            assetLoader = assetLoader,
            hotReloading = false,
            auth = auth,
            impersonatorEmails = impersonatorEmails,
            tokenHashKeyBase64 = Random.nextBytes(32).base64Encode(),
            messageLoader = messageLoader,
        ).startServer()
}

private fun Page.navigateHome(
    port: Int,
    monthQuery: YearMonth,
) {
    navigateHome(port = port, query = "?month=" + monthQuery.format(DateTimeFormatter.ofPattern("yyyy-MM")))
}

private fun Page.navigateHome(
    port: Int,
    query: String = "",
) {
    navigate("http://localhost:$port$query")
    assertThat(calendar()).isVisible()
}

private fun Page.assertDaysAreEnabled(days: Iterable<Int>) {
    for (dayNum in days) assertThat(day(dayNum = dayNum)).not().isDisabled()
}

private fun Page.assertDaysAreDisabled(days: Iterable<Int>) {
    for (dayNum in days) assertThat(day(dayNum = dayNum)).isDisabled()
}

private fun Page.assertOpenedDaysAre(
    days: Iterable<Int>,
    month: YearMonth,
) {
    for (dayNum in (1..month.lengthOfMonth())) {
        val day = day(dayNum = dayNum)
        if (dayNum in days) {
            assertThat(day).hasClass("day-opened")
            assertThat(day).not().hasClass("day-ready")
        } else {
            assertThat(day).hasClass("day-ready")
            assertThat(day).not().hasClass("day-opened")
        }
    }
}

private fun Page.assertCurrentMonthIs(month: YearMonth) {
    assertThat(monthYear()).isVisible()
    assertThat(monthYear()).hasText(month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.UK) + month.year)
    assertNumOfDaysInCurrentMonthIs(month.lengthOfMonth())
    val previousMonth = month.minusMonths(1)
    val previousMonthDays =
        (previousMonth.lengthOfMonth() - (month.atDay(1).dayOfWeek.value - 1) + 1)..previousMonth.lengthOfMonth()
    assertDisplayedDaysOfPreviousMonthAre(previousMonthDays.toList())
    assertDisplayedDaysOfNextMonthAre((1..(7 - month.atDay(month.lengthOfMonth()).dayOfWeek.value)).toList())
    assertMonthQueryIs(month)
}

private fun Page.assertMonthQueryIs(month: YearMonth) {
    val queryValue = Uri.of(url()).queries().findSingle("month")
    if (queryValue != null) {
        assertEquals(month.format(DateTimeFormatter.ofPattern("yyyy-MM")), queryValue)
    }
}

private fun Page.assertDisplayedDaysOfPreviousMonthAre(nums: List<Int>) {
    val nextMonthDays = getByTestId(Pattern.compile("^prev-month-day-\\d{1,2}$"))
    assertThat(nextMonthDays).hasCount(nums.size)
    assertThat(nextMonthDays).hasText(nums.map { it.toString() }.toTypedArray())
}

private fun Page.assertDisplayedDaysOfNextMonthAre(nums: List<Int>) {
    val nextMonthDays = getByTestId(Pattern.compile("^next-month-day-\\d{1,2}$"))
    assertThat(nextMonthDays).hasCount(nums.size)
    assertThat(nextMonthDays).hasText(nums.map { it.toString() }.toTypedArray())
}

private fun Page.assertNumOfDaysInCurrentMonthIs(num: Int) {
    val days = getByTestId(Pattern.compile("^day-\\d{1,2}$"))
    assertThat(days).hasCount(num)
    assertThat(days).hasText((1..num).map { it.toString() }.toTypedArray())
}

private fun Page.assertThatDayTextIs(text: String) {
    assertThat(dayText()).hasText(text)
}

private fun Page.assertIsOnAuthenticationPage(authServer: GoogleOAuthServer) {
    assertThat(this).hasURL("^${authServer.authenticationPageUrl}.*".toPattern())
}

private fun Page.assertPreviousDaysAre(expected: List<PreviousDay>) {
    assertThat(previousDayTexts()).hasText(expected.map { it.text }.toTypedArray())
    assertThat(previousDayDate()).hasText(expected.map { it.date }.toTypedArray())
}

private data class PreviousDay(val text: String, val date: String)

private fun Page.clickDay(dayNum: Int) {
    assertThat(dayText()).not().isVisible()
    assertThat(day(dayNum)).hasText(dayNum.toString())
    day(dayNum).click()
    assertThat(dayText()).isVisible()
}

private fun Page.clickBack() {
    backButton().click()
    assertThat(calendar()).isVisible()
    assertThat(dayText()).not().isVisible()
}

private fun Page.clickPreviousMonth() {
    assertThat(previousMonthButton()).isVisible()
    previousMonthButton().click()
}

private fun Page.clickNextMonth() {
    assertThat(nextMonthButton()).isVisible()
    nextMonthButton().click()
}

private fun Page.clickToday() {
    assertThat(todayButton()).isVisible()
    todayButton().click()
}

private fun Page.clickLogout() {
    assertThat(logoutButton()).isVisible()
    logoutButton().click()
}

private fun Page.impersonate(emailToImpersonate: String) {
    assertThat(impersonateEmailInput()).isVisible()
    impersonateEmailInput().fill(emailToImpersonate)
    assertThat(stopImpersonatingButton()).not().isVisible()
    assertThat(impersonateButton()).isVisible()
    impersonateButton().click()
}

private fun Page.stopImpersonating() {
    assertThat(stopImpersonatingButton()).isVisible()
    assertThat(impersonateEmailInput()).not().isVisible()
    assertThat(impersonateButton()).not().isVisible()
    stopImpersonatingButton().click()
    assertThat(stopImpersonatingButton()).not().isVisible()
    assertThat(impersonateEmailInput()).isVisible()
    assertThat(impersonateEmailInput()).hasValue("")
    assertThat(impersonateButton()).isVisible()
}

private fun Page.calendar(): Locator = getByTestId("calendar")

private fun Page.day(dayNum: Int): Locator = getByTestId("day-$dayNum")

private fun Page.dayText(): Locator = getByTestId("day-text")

private fun Page.backButton(): Locator = getByTestId("back")

private fun Page.previousMonthButton(): Locator = getByTestId("previous-month")

private fun Page.nextMonthButton(): Locator = getByTestId("next-month")

private fun Page.todayButton(): Locator = getByTestId("today")

private fun Page.monthYear(): Locator = getByTestId("month-year")

private fun Page.logoutButton(): Locator = getByTestId("logout")

private fun Page.previousDayTexts(): Locator = getByTestId("previous-day-text")

private fun Page.previousDayDate(): Locator = getByTestId("previous-day-date")

private fun Page.backToTopButton(): Locator = getByTestId("back-to-top")

private fun Page.impersonateButton(): Locator = getByTestId("impersonate")

private fun Page.impersonateEmailInput(): Locator = getByTestId("impersonate-email")

private fun Page.stopImpersonatingButton(): Locator = getByTestId("stop-impersonating")

private fun Page.impersonatingMessage(): Locator = getByTestId("impersonating-message")

private fun Page.error(): Locator = getByTestId("error")
