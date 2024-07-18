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
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.regex.Pattern
import kotlin.random.Random
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

    @AfterEach
    fun tearEachDown() {
        browser.close()
        server.stop()
    }

    @Test
    fun `navigate from calendar to a day and back to calendar`() {
        val dayText = "something sweet"
        server = startServer(port = 0) { dayText }
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
        server = startServer(port = 0, clock = clock, messageLoader = messageLoader)
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
        server = startServer(port = 0, clock = clock, messageLoader = messageLoader)
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
        server = startServer(port = 0, clock = clock) { "whatever" }
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
        val clock = LocalDate.EPOCH.toClock()
        val messageLoader =
            MapBackedMessageLoader(
                mapOf(
                    LocalDate.now(ZoneOffset.UTC) to message1,
                    LocalDate.of(2024, 5, 1) to message2,
                    LocalDate.of(2024, 2, 1) to message3,
                    LocalDate.of(2023, 2, 1) to message4,
                ),
            )
        server = startServer(port = 0, clock = clock, messageLoader = messageLoader)
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
        val clock = LocalDate.EPOCH.toClock()
        server = startServer(port = 0, clock = clock) { "whatever" }
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
        server = startServer(port = 0, clock = YearMonth.of(2024, 6).toClock(), messageLoader = messageLoader)
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
        server = startServer(port = 0, clock = clock, messageLoader = messageLoader)
        val page = browser.newPage()

        page.navigateHome(port = server.port())
        page.assertCurrentMonthIs(YearMonth.of(2024, 5))
        page.clickDay(1)
        page.assertThatDayTextIs(message2)

        clock.del = LocalDate.of(2024, 4, 13).toClock()
        page.clickBack()
        assertThat(page.todayButton()).hasText("13")
        page.clickToday()
        page.assertCurrentMonthIs(YearMonth.of(2024, 4))
        page.clickDay(1)
        page.assertThatDayTextIs(message1)

        clock.del = LocalDate.of(2024, 4, 25).toClock()
        page.clickBack()
        assertThat(page.todayButton()).hasText("25")
        // Clicking today while already on the current month should do nothing.
        page.clickToday()
        page.assertCurrentMonthIs(YearMonth.of(2024, 4))
        page.clickDay(1)
        page.assertThatDayTextIs(message1)

        clock.del = LocalDate.of(2024, 6, 1).toClock()
        page.clickBack()
        assertThat(page.todayButton()).hasText("1")
        page.clickToday()
        page.assertCurrentMonthIs(YearMonth.of(2024, 6))
        page.clickDay(1)
        page.assertThatDayTextIs(message3)
    }
}

private fun LocalDate.toMutableClock(): MutableClock = toClock().mutable()

private fun YearMonth.toClock(): Clock = atDay(Random.nextInt(1, lengthOfMonth())).toClock()

private fun LocalDate.toClock(): Clock =
    Clock.fixed(atTime(LocalTime.MIDNIGHT).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

private fun Clock.mutable() = MutableClock(this)

private class MutableClock(var del: Clock) : Clock() {
    override fun instant(): Instant = del.instant()

    override fun millis(): Long = del.millis()

    override fun withZone(zone: ZoneId): Clock = del.withZone(zone)

    override fun getZone(): ZoneId = del.zone
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

private fun Page.calendar(): Locator = getByTestId("calendar")

private fun Page.day(dayNum: Int): Locator = getByTestId("day-$dayNum")

private fun Page.dayText(): Locator = getByTestId("day-text")

private fun Page.backButton(): Locator = getByTestId("back")

private fun Page.previousMonthButton(): Locator = getByTestId("previous-month")

private fun Page.nextMonthButton(): Locator = getByTestId("next-month")

private fun Page.todayButton(): Locator = getByTestId("today")

private fun Page.monthYear(): Locator = getByTestId("month-year")

class MapBackedMessageLoader(private val messages: Map<LocalDate, String>) : MessageLoader {
    override fun get(date: LocalDate): String? = messages[date]
}
