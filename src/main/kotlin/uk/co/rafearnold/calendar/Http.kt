package uk.co.rafearnold.calendar

import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Method.GET
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.BiDiBodyLens
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.StringBiDiMappings
import org.http4k.lens.localDate
import org.http4k.lens.map
import org.http4k.routing.ResourceLoader
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.Http4kServer
import org.http4k.server.Jetty
import org.http4k.server.asServer
import org.http4k.template.ViewModel
import org.http4k.template.viewModel
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

fun startServer(
    port: Int = 8080,
    clock: Clock = Clock.systemUTC(),
    messageLoader: MessageLoader,
): Http4kServer {
    val templateRenderer = PebbleTemplateRenderer()
    val view: BiDiBodyLens<ViewModel> = Body.viewModel(templateRenderer, ContentType.TEXT_HTML).toLens()
    val router = routes(Assets(), Index(view, clock), Day(view, messageLoader))
    val app =
        Filter { next ->
            {
                try {
                    logger.info("Request received: ${it.method} ${it.uri}")
                    next(it)
                } catch (e: Throwable) {
                    logger.error("Error caught handling request", e)
                    Response(Status.INTERNAL_SERVER_ERROR)
                }
            }
        }.then(router)
    val server = app.asServer(Jetty(port = port)).start()
    logger.info("Server started")
    return server
}

fun interface MessageLoader {
    operator fun get(date: LocalDate): String?
}

class Assets(
    loader: ResourceLoader = ResourceLoader.Directory("src/main/resources/assets"),
) : RoutingHttpHandler by static(loader).withBasePath("/assets")

val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

class Index(
    view: BiDiBodyLens<ViewModel>,
    clock: Clock,
) : RoutingHttpHandler by "/" bind GET to {
        val date =
            Query.map(StringBiDiMappings.yearMonth(monthFormatter)).optional("month")(it)
                ?: clock.instant().atZone(clock.zone).toLocalDate().toYearMonth()
        val viewModel =
            HomeViewModel(
                previousMonthLink = monthLink(date.minusMonths(1)),
                nextMonthLink = monthLink(date.plusMonths(1)),
                todayLink = monthLink(clock.toDate().toYearMonth()),
                month = date.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.UK),
                year = date.year,
                calendarBaseModel = date.toCalendarModel(),
            )
        Response(OK).with(view of viewModel)
    }

class Day(
    view: BiDiBodyLens<ViewModel>,
    messageLoader: MessageLoader,
) : RoutingHttpHandler by "/day/{date}" bind GET to {
        val date = Path.localDate(DateTimeFormatter.ISO_LOCAL_DATE).of("date")(it)
        val message = messageLoader[date]
        if (message != null) {
            val viewModel =
                DayViewModel(
                    text = message,
                    backLink = monthLink(date.toYearMonth()),
                    calendarBaseModel = date.toCalendarModel(),
                )
            Response(OK).with(view of viewModel)
        } else {
            Response(NOT_FOUND)
        }
    }

fun LocalDate.toCalendarModel(): CalendarBaseModel = toYearMonth().toCalendarModel()

private fun Clock.toDate(): LocalDate = LocalDate.ofInstant(instant(), zone)

fun LocalDate.toYearMonth(): YearMonth = YearMonth.from(this)

private fun monthLink(month: YearMonth) = "/?month=" + monthFormatter.format(month)
