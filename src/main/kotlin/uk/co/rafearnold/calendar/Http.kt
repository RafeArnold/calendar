package uk.co.rafearnold.calendar

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.flywaydb.core.Flyway
import org.http4k.base64DecodedArray
import org.http4k.core.Body
import org.http4k.core.ContentType
import org.http4k.core.Filter
import org.http4k.core.HttpHandler
import org.http4k.core.Method.GET
import org.http4k.core.RequestContext
import org.http4k.core.RequestContexts
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.Status.Companion.FORBIDDEN
import org.http4k.core.Status.Companion.FOUND
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Store
import org.http4k.core.cookie.invalidateCookie
import org.http4k.core.then
import org.http4k.core.with
import org.http4k.filter.ServerFilters
import org.http4k.lens.BiDiBodyLens
import org.http4k.lens.Cookies
import org.http4k.lens.Path
import org.http4k.lens.Query
import org.http4k.lens.RequestContextKey
import org.http4k.lens.RequestContextLens
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
import java.net.URL
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class Config(
    val port: Int,
    val clock: Clock,
    val dbUrl: String,
    val assetLoader: ResourceLoader,
    val hotReloading: Boolean,
    val auth: AuthConfig,
    val impersonatorEmails: List<String>,
    val tokenHashKeyBase64: String,
    val messageLoader: MessageLoader,
) {
    companion object
}

interface AuthConfig {
    fun createHandlerFactory(
        userRepository: UserRepository,
        userLens: RequestContextLens<User>,
        clock: Clock,
        tokenHashKey: ByteArray,
        additionalFilters: List<Filter>,
    ): RoutingHandlerFactory

    fun logoutHandler(): HttpHandler

    companion object
}

data object NoAuth : AuthConfig {
    override fun createHandlerFactory(
        userRepository: UserRepository,
        userLens: RequestContextLens<User>,
        clock: Clock,
        tokenHashKey: ByteArray,
        additionalFilters: List<Filter>,
    ): RoutingHandlerFactory =
        RoutingHandlerFactory { list ->
            routes(*list)
                .run { additionalFilters.fold(this) { handler, filter -> handler.withFilter(filter) } }
                .withFilter { next -> { next(userLens(User(0, "", ""), it)) } }
        }

    override fun logoutHandler(): HttpHandler = { _ -> Response(FOUND).header("location", "/") }
}

fun interface RoutingHandlerFactory {
    fun routes(vararg list: RoutingHttpHandler): RoutingHttpHandler
}

fun Config.startServer(): Http4kServer {
    val dataSource = SQLiteDataSource().apply { url = dbUrl }
    migrateDb(dataSource)
    val dbCtx = DSL.using(dataSource, SQLDialect.SQLITE)
    val userRepository = UserRepository(dbCtx)
    val daysRepository = DaysRepository(dbCtx, clock)

    val templateRenderer = PebbleTemplateRenderer(PebbleEngineFactory.create(hotReloading = hotReloading))
    val view: BiDiBodyLens<ViewModel> = Body.viewModel(templateRenderer, ContentType.TEXT_HTML).toLens()

    val requestContexts = RequestContexts()
    val userLens = userLens(requestContexts)
    val impersonatedUserLens = impersonatedUserLens(requestContexts)

    val calendarModelHelper = CalendarModelHelper(messageLoader, clock, daysRepository)

    val tokenHashKey = tokenHashKeyBase64.base64DecodedArray()
    val objectMapper = jacksonObjectMapper()
    val impersonationTokens =
        ImpersonationTokens(tokenHashKey = tokenHashKey, clock = clock, objectMapper = objectMapper)
    val impersonatedFilter = impersonatedUserFilter(userRepository, userLens, impersonatedUserLens, impersonationTokens)

    val router =
        routes(
            Assets(assetLoader = assetLoader),
            auth.createHandlerFactory(userRepository, userLens, clock, tokenHashKey, listOf(impersonatedFilter))
                .routes(
                    Index(view, clock, userLens, impersonatedUserLens, impersonatorEmails, calendarModelHelper),
                    DaysRoute(view, clock, userLens, impersonatedUserLens, calendarModelHelper),
                    DayRoute(view, messageLoader, clock, daysRepository, userLens, impersonatedUserLens),
                    previousDaysRoute(view, messageLoader, clock, daysRepository, userLens, impersonatedUserLens),
                    impersonateRoute(view, clock, userRepository, userLens, impersonatorEmails, impersonationTokens),
                )
                .withFilter(ServerFilters.InitialiseRequestContext(requestContexts)),
            logoutRoute(auth),
            stopImpersonatingRoute(),
        ).withFilter(forbiddenFilter)
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

class AssetMessageLoader(private val resourceLoader: ResourceLoader) : MessageLoader {
    override fun get(date: LocalDate): String? =
        resourceLoader.load("messages/" + date.format(DateTimeFormatter.ISO_LOCAL_DATE))
            ?.openStream()?.readAllBytes()?.decodeToString()
}

class Assets(assetLoader: ResourceLoader) : RoutingHttpHandler by static(assetLoader).withBasePath("/assets")

class ChainResourceLoader(private val loaders: List<ResourceLoader>) : ResourceLoader {
    override fun load(path: String): URL? {
        for (loader in loaders) loader.load(path)?.let { return it }
        return null
    }
}

const val ERROR_COOKIE_NAME: String = "error"
private val errorCookie = Cookies.optional(ERROR_COOKIE_NAME)

private fun userLens(contexts: Store<RequestContext>): RequestContextLens<User> =
    RequestContextKey.required(contexts, name = "user")

val monthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")

val monthQuery = Query.map(StringBiDiMappings.yearMonth(monthFormatter)).optional("month")

val previousDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("eee, d MMM yyyy")

class Index(
    view: BiDiBodyLens<ViewModel>,
    clock: Clock,
    user: RequestContextLens<User>,
    impersonatedUser: RequestContextLens<User?>,
    impersonatorEmails: List<String>,
    calendarModelHelper: CalendarModelHelper,
) : RoutingHttpHandler by "/" bind GET to { request ->
        val month = monthQuery(request) ?: clock.toDate().toYearMonth()
        val impersonatedUser0 = impersonatedUser(request)
        val user0 = user(request)
        val errorCookie0 = errorCookie(request)
        val viewModel =
            HomeViewModel(
                justCalendar = request.isHtmx(),
                previousMonthLink = monthLink(month.minusMonths(1)),
                nextMonthLink = monthLink(month.plusMonths(1)),
                todayLink = "/",
                month = month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.UK),
                year = month.year,
                monthImageLink = monthImageLink(month),
                canImpersonate = user0.email in impersonatorEmails,
                impersonatingEmail = impersonatedUser0?.email,
                error = errorCookie0?.value,
                calendarBaseModel = calendarModelHelper.create(month, impersonatedUser0 ?: user0),
            )
        Response(OK).with(view of viewModel)
            .run { if (errorCookie0 != null) invalidateCookie(ERROR_COOKIE_NAME) else this }
    }

class DaysRoute(
    view: BiDiBodyLens<ViewModel>,
    clock: Clock,
    user: RequestContextLens<User>,
    impersonatedUser: RequestContextLens<User?>,
    calendarModelHelper: CalendarModelHelper,
) : RoutingHttpHandler by "/days" bind GET to { request ->
        val month = monthQuery(request) ?: clock.toDate().toYearMonth()
        val user0 = impersonatedUser(request) ?: user(request)
        Response(OK).with(view of DaysViewModel(calendarModelHelper.create(month, user0)))
    }

class DayRoute(
    view: BiDiBodyLens<ViewModel>,
    messageLoader: MessageLoader,
    clock: Clock,
    daysRepo: DaysRepository,
    user: RequestContextLens<User>,
    impersonatedUser: RequestContextLens<User?>,
) : RoutingHttpHandler by "/day/{date}" bind GET to {
        val date = Path.localDate(DateTimeFormatter.ISO_LOCAL_DATE).of("date")(it)
        if (date.isAfter(clock.now().toLocalDate())) throw ForbiddenException()
        if (impersonatedUser(it) == null) daysRepo.markDayAsOpened(user(it), date)
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

val previousDaysFromQuery = Query.map(StringBiDiMappings.localDate(DateTimeFormatter.ISO_LOCAL_DATE)).optional("from")

fun previousDaysRoute(
    view: BiDiBodyLens<ViewModel>,
    messageLoader: MessageLoader,
    clock: Clock,
    daysRepo: DaysRepository,
    user: RequestContextLens<User>,
    impersonatedUser: RequestContextLens<User?>,
): RoutingHttpHandler =
    "/previous-days" bind GET to { request ->
        val from = previousDaysFromQuery(request) ?: clock.toDate()
        val previousDays =
            daysRepo.getOpenedDaysDescFrom(impersonatedUser(request) ?: user(request), from = from, limit = 10)
        val nextPreviousDaysLink = previousDaysLink(from = previousDays.lastOrNull()?.minusDays(1))
        val previousDaysBaseModel =
            object : PreviousDaysBaseModel {
                override val previousDays: List<PreviousDayModel> = previousDays.toPreviousDayModels(messageLoader)
                override val nextPreviousDaysLink: String = nextPreviousDaysLink
                override val includeNextPreviousDaysLinkOnDay: Int = 9
            }
        Response(OK).with(view of PreviousDaysViewModel(previousDaysBaseModel))
    }

fun logoutRoute(auth: AuthConfig): RoutingHttpHandler =
    "/logout" bind GET to { auth.logoutHandler()(it).with(stopImpersonating) }

class CalendarModelHelper(
    private val messageLoader: MessageLoader,
    private val clock: Clock,
    private val daysRepo: DaysRepository,
) {
    fun create(
        month: YearMonth,
        user: User,
    ): CalendarBaseModel {
        val openedDays = daysRepo.getOpenedDaysOfMonth(user, month)
        val previousDays = daysRepo.getOpenedDaysDescFrom(user, from = clock.toDate(), limit = 20)
        val nextPreviousDaysLink = previousDaysLink(from = previousDays.lastOrNull()?.minusDays(1))
        return month.toCalendarModel(
            openedDays = openedDays,
            previousDays = previousDays.toPreviousDayModels(messageLoader),
            nextPreviousDaysLink = nextPreviousDaysLink,
            clock = clock,
        )
    }
}

fun List<LocalDate>.toPreviousDayModels(messageLoader: MessageLoader) =
    mapNotNull { prevDate ->
        messageLoader[prevDate]?.let { PreviousDayModel(date = prevDate.format(previousDateFormatter), text = it) }
    }

private fun monthLink(month: YearMonth) = "/?month=" + monthFormatter.format(month)

private fun daysLink(month: YearMonth) = "/days?month=" + monthFormatter.format(month)

private fun monthImageLink(month: YearMonth) = "/assets/month-images/${monthFormatter.format(month)}.jpg"

private fun previousDaysLink(from: LocalDate?) =
    "/previous-days" + if (from != null) "?from=" + from.format(DateTimeFormatter.ISO_LOCAL_DATE) else ""

val forbiddenFilter: Filter =
    Filter { next ->
        {
            try {
                next(it)
            } catch (e: ForbiddenException) {
                Response(FORBIDDEN)
            }
        }
    }

class ForbiddenException : RuntimeException()
