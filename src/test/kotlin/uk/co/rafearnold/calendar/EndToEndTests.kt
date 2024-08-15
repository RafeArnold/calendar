package uk.co.rafearnold.calendar

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.http4k.core.Uri
import org.http4k.core.findSingle
import org.http4k.core.queries
import org.http4k.server.Http4kServer
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
        page.assertUnopenedDaysAre(1..31)

        page.clickDay(1)
        page.clickBack()
        page.assertUnopenedDaysAre(2..31)
        page.assertOpenedDaysAre(listOf(1))

        page.clickDay(5)
        page.clickBack()
        page.assertUnopenedDaysAre((2..4) + (6..31))
        page.assertOpenedDaysAre(listOf(1, 5))

        // Clicking an already opened day changes nothing.
        page.clickDay(1)
        page.clickBack()
        page.assertUnopenedDaysAre((2..4) + (6..31))
        page.assertOpenedDaysAre(listOf(1, 5))

        // Restarting the server changes nothing.
        server.stop()
        server = startServer(clock = clock) { "whatever" }
        page.navigateHome(port = server.port())
        page.assertUnopenedDaysAre((2..4) + (6..31))
        page.assertOpenedDaysAre(listOf(1, 5))
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
        server = startServer(clock = clock, assetsDir = assetsDir.toString()) { "whatever" }
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
                redirectUri = server.oauthUri(code = null).toASCIIString(),
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
            assertEquals(0, authServer.serveEvents.requests.size)
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
            page1.assertOpenedDaysAre(emptyList())
            page1.clickDay(1)
            page1.clickBack()
            page1.clickDay(14)
            page1.clickBack()
            page1.assertOpenedDaysAre(listOf(1, 14))

            val page2 = browser.newPage()
            page2.login(email = userEmail, googleSubjectId = googleSubjectId, authServer = authServer)
            page2.assertCurrentMonthIs(today.toYearMonth())
            page2.assertOpenedDaysAre(listOf(1, 14))
            page2.clickDay(3)
            page2.clickBack()
            page2.clickDay(25)
            page2.clickBack()
            page2.assertOpenedDaysAre(listOf(1, 3, 14, 25))
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
            page1.assertOpenedDaysAre(emptyList())
            page1.clickDay(1)
            page1.clickBack()
            page1.clickDay(14)
            page1.clickBack()
            page1.assertOpenedDaysAre(listOf(1, 14))

            val page2 = browser.newPage()
            page2.login(email = userEmail2, authServer = authServer)
            page2.assertCurrentMonthIs(today.toYearMonth())
            page2.assertOpenedDaysAre(emptyList())
            page2.clickDay(3)
            page2.clickBack()
            page2.clickDay(25)
            page2.clickBack()
            page2.assertOpenedDaysAre(listOf(3, 25))

            page1.clickDay(6)
            page1.clickBack()
            page1.assertOpenedDaysAre(listOf(1, 6, 14))
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

    private fun Page.login(
        email: String,
        googleSubjectId: String = UUID.randomUUID().toString(),
        authCode: String = UUID.randomUUID().toString(),
        authServer: GoogleOAuthServer,
    ) {
        authServer.stubAuthenticationPage(redirectUri = server.oauthUri(code = null), authCode = authCode)
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
        assetsDir: String = "src/main/resources/assets",
        auth: AuthConfig = NoAuth,
        messageLoader: MessageLoader,
    ): Http4kServer =
        Config(
            port = 0,
            clock = clock,
            dbUrl = dbUrl,
            assetsDir = assetsDir,
            auth = auth,
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

private fun Page.assertUnopenedDaysAre(days: Iterable<Int>) {
    for (dayNum in days) {
        val day = day(dayNum = dayNum)
        assertThat(day).hasClass("day-ready")
        assertThat(day).not().hasClass("day-opened")
    }
}

private fun Page.assertOpenedDaysAre(days: Iterable<Int>) {
    for (dayNum in days) {
        val day = day(dayNum = dayNum)
        assertThat(day).hasClass("day-opened")
        assertThat(day).not().hasClass("day-ready")
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

private fun Page.calendar(): Locator = getByTestId("calendar")

private fun Page.day(dayNum: Int): Locator = getByTestId("day-$dayNum")

private fun Page.dayText(): Locator = getByTestId("day-text")

private fun Page.backButton(): Locator = getByTestId("back")

private fun Page.previousMonthButton(): Locator = getByTestId("previous-month")

private fun Page.nextMonthButton(): Locator = getByTestId("next-month")

private fun Page.todayButton(): Locator = getByTestId("today")

private fun Page.monthYear(): Locator = getByTestId("month-year")

private fun Page.logoutButton(): Locator = getByTestId("logout")
