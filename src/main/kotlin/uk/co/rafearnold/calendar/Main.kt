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
import org.http4k.core.Status.Companion.OK
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.lens.BiDiBodyLens
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

val logger: Logger = LoggerFactory.getLogger("main")

fun main() {
    startServer("something sweet").block()
}

fun startServer(dayText: String): Http4kServer {
    val templateRenderer = PebbleTemplateRenderer()
    val view: BiDiBodyLens<ViewModel> = Body.viewModel(templateRenderer, ContentType.TEXT_HTML).toLens()
    val router = routes(Assets(), Index(view), Day(view, dayText))
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
    val server = app.asServer(Jetty(port = 8080)).start()
    logger.info("Server started")
    return server
}

class Assets(
    loader: ResourceLoader = ResourceLoader.Directory("src/main/resources/assets"),
) : RoutingHttpHandler by static(loader).withBasePath("/assets")

class Index(
    view: BiDiBodyLens<ViewModel>,
) : RoutingHttpHandler by "/" bind GET to { Response(OK).with(view of HomeViewModel) }

class Day(
    view: BiDiBodyLens<ViewModel>,
    text: String,
) : RoutingHttpHandler by "/day/1" bind GET to { Response(OK).with(view of DayViewModel(text = text)) }

object HomeViewModel : ViewModel {
    override fun template(): String = "home"
}

class DayViewModel(val text: String) : ViewModel {
    override fun template(): String = "day"
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
