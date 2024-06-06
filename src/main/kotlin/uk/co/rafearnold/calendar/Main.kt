package uk.co.rafearnold.calendar

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.error.LoaderException
import io.pebbletemplates.pebble.loader.FileLoader
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
import org.http4k.lens.localDate
import org.http4k.routing.ResourceLoader
import org.http4k.routing.RoutingHttpHandler
import org.http4k.routing.bind
import org.http4k.routing.routes
import org.http4k.routing.static
import org.http4k.server.Http4kServer
import org.http4k.server.Jetty
import org.http4k.server.asServer
import org.http4k.template.TemplateRenderer
import org.http4k.template.ViewModel
import org.http4k.template.viewModel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.StringWriter
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val logger: Logger = LoggerFactory.getLogger("main")

fun main() {
    startServer { "something sweet" }.block()
}

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

class Index(
    view: BiDiBodyLens<ViewModel>,
    clock: Clock,
) : RoutingHttpHandler by "/" bind GET to {
        val date = clock.instant().atZone(clock.zone).toLocalDate()
        val viewModel =
            HomeViewModel(
                datePrefix = date.format(datePrefixFormatter),
                currentMonthDayCount = date.lengthOfMonth(),
                previousMonthDays = date.previousMonthDays(),
                nextMonthDays = date.nextMonthDays(),
            )
        Response(OK).with(view of viewModel)
    }

val datePrefixFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-")

class Day(
    view: BiDiBodyLens<ViewModel>,
    messageLoader: MessageLoader,
) : RoutingHttpHandler by "/day/{date}" bind GET to {
        val date = Path.localDate().of("date")(it)
        val message = messageLoader[date]
        if (message != null) {
            val viewModel =
                DayViewModel(
                    text = message,
                    currentMonthDayCount = date.lengthOfMonth(),
                    previousMonthDays = date.previousMonthDays(),
                    nextMonthDays = date.nextMonthDays(),
                )
            Response(OK).with(view of viewModel)
        } else {
            Response(NOT_FOUND)
        }
    }

private fun LocalDate.previousMonthDays(): List<Int> {
    val previousMonthDaysCount = withDayOfMonth(1).dayOfWeek.value - 1
    val previousMonth = minusMonths(1)
    val previousMonthLength = previousMonth.lengthOfMonth()
    return ((previousMonthLength - previousMonthDaysCount + 1)..previousMonthLength).toList()
}

private fun LocalDate.nextMonthDays(): List<Int> {
    val nextMonthDaysCount = 7 - withDayOfMonth(lengthOfMonth()).dayOfWeek.value
    return (1..nextMonthDaysCount).toList()
}

@Suppress("unused")
class HomeViewModel(
    val datePrefix: String,
    val currentMonthDayCount: Int,
    val previousMonthDays: List<Int>,
    val nextMonthDays: List<Int>,
) : ViewModel {
    private val rotated: Boolean = false

    override fun template(): String = "home"
}

@Suppress("unused")
class DayViewModel(
    val text: String,
    val currentMonthDayCount: Int,
    val previousMonthDays: List<Int>,
    val nextMonthDays: List<Int>,
) : ViewModel {
    val rotated: Boolean = true

    override fun template(): String = "calendar"
}

class PebbleTemplateRenderer(
    private val engine: PebbleEngine =
        PebbleEngine.Builder().cacheActive(false).loader(FileLoader().apply { prefix = "src/main/resources" }).build(),
) : TemplateRenderer {
    override fun invoke(viewModel: ViewModel): String =
        try {
            val writer = StringWriter()
            engine.getTemplate(viewModel.template() + ".html")
                .evaluate(writer, mapOf("model" to viewModel))
            writer.toString()
        } catch (e: LoaderException) {
            throw RuntimeException("Template ${viewModel.template()} not found", e)
        }
}
