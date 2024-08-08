package uk.co.rafearnold.calendar

import org.flywaydb.core.Flyway
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.Method.GET
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.FORBIDDEN
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
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.sqlite.SQLiteDataSource
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class Config(
    val port: Int,
    val clock: Clock,
    val dbUrl: String,
    val assetsDir: String,
    val messageLoader: MessageLoader,
)

fun Config.startServer(): Http4kServer {
    val dataSource = SQLiteDataSource().apply { url = dbUrl }
    migrateDb(dataSource)
    val daysRepository = DaysRepository(DSL.using(dataSource, SQLDialect.SQLITE), clock)
    val templateRenderer = PebbleTemplateRenderer()
    val view: BiDiBodyLens<ViewModel> = Body.viewModel(templateRenderer, ContentType.TEXT_HTML).toLens()
    val router =
        routes(
            Assets(assetsDir = assetsDir),
            Index(view, clock, daysRepository),
            DaysRoute(view, clock, daysRepository),
            DayRoute(view, messageLoader, clock, daysRepository),
        )
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

private fun migrateDb(dataSource: SQLiteDataSource) {
    Flyway.configure().dataSource(dataSource).load().migrate()
}

fun interface MessageLoader {
    operator fun get(date: LocalDate): String?
}

class Assets(
    assetsDir: String,
) : RoutingHttpHandler by static(ResourceLoader.Directory(assetsDir)).withBasePath("/assets")

val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

val monthQuery = Query.map(StringBiDiMappings.yearMonth(monthFormatter)).optional("month")

class Index(
    view: BiDiBodyLens<ViewModel>,
    clock: Clock,
    daysRepo: DaysRepository,
) : RoutingHttpHandler by "/" bind GET to {
        val date = monthQuery(it) ?: clock.now().toLocalDate().toYearMonth()
        val viewModel =
            HomeViewModel(
                previousMonthLink = monthLink(date.minusMonths(1)),
                nextMonthLink = monthLink(date.plusMonths(1)),
                todayLink = "/",
                month = date.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.UK),
                year = date.year,
                monthImageLink = monthImageLink(date),
                calendarBaseModel = date.toCalendarModel(daysRepo, clock),
            )
        Response(OK).with(view of viewModel)
    }

class DaysRoute(
    view: BiDiBodyLens<ViewModel>,
    clock: Clock,
    daysRepo: DaysRepository,
) : RoutingHttpHandler by "/days" bind GET to { request ->
        val date = monthQuery(request) ?: clock.now().toLocalDate().toYearMonth()
        Response(OK).with(view of DaysViewModel(date.toCalendarModel(daysRepo, clock)))
    }

class DayRoute(
    view: BiDiBodyLens<ViewModel>,
    messageLoader: MessageLoader,
    clock: Clock,
    daysRepo: DaysRepository,
) : RoutingHttpHandler by "/day/{date}" bind GET to {
        val date = Path.localDate(DateTimeFormatter.ISO_LOCAL_DATE).of("date")(it)
        if (date.isAfter(clock.now().toLocalDate())) {
            Response(FORBIDDEN)
        } else {
            daysRepo.markDayAsOpened(date)
            val message = messageLoader[date]
            if (message != null) {
                val month = date.toYearMonth()
                val viewModel =
                    DayViewModel(
                        text = message,
                        backLink = daysLink(month),
                        dayOfMonth = date.dayOfMonth,
                    )
                Response(OK).with(view of viewModel)
            } else {
                Response(NOT_FOUND)
            }
        }
    }

fun Clock.toDate(): LocalDate = LocalDate.ofInstant(instant(), zone)

fun Clock.now(): LocalDateTime = LocalDateTime.ofInstant(instant(), zone)

fun LocalDate.toYearMonth(): YearMonth = YearMonth.from(this)

private fun monthLink(month: YearMonth) = "/?month=" + monthFormatter.format(month)

private fun daysLink(month: YearMonth) = "/days?month=" + monthFormatter.format(month)

private fun monthImageLink(month: YearMonth) = "/assets/month-images/${monthFormatter.format(month)}.jpg"
