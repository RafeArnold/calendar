package uk.co.rafearnold.calendar

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat
import org.http4k.server.Http4kServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

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
        server = startServer(dayText)
        val page = browser.newPage()
        page.navigate("http://localhost:${server.port()}")
        assertThat(page.getByTestId("calendar")).isVisible()
        assertThat(page.getByTestId("day-text")).not().isVisible()
        page.getByTestId("day-1").click()
        assertThat(page.getByTestId("calendar")).not().isVisible()
        assertThat(page.getByTestId("day-text")).isVisible()
        assertThat(page.getByTestId("day-text")).hasText(dayText)
        page.getByTestId("back").click()
        assertThat(page.getByTestId("calendar")).isVisible()
        assertThat(page.getByTestId("day-text")).not().isVisible()
    }
}
