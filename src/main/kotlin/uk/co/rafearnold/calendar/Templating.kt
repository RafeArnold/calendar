package uk.co.rafearnold.calendar

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.error.LoaderException
import io.pebbletemplates.pebble.loader.ClasspathLoader
import io.pebbletemplates.pebble.loader.FileLoader
import org.http4k.template.TemplateRenderer
import org.http4k.template.ViewModel
import java.io.StringWriter
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

fun YearMonth.toCalendarModel(
    openedDays: List<Int>,
    previousDays: List<PreviousDayModel>,
    nextPreviousDaysLink: String,
    clock: Clock,
): CalendarBaseModel {
    val days = (1..lengthOfMonth()).map { atDay(it).toDayModel(opened = openedDays.contains(it), now = clock.toDate()) }
    return object : CalendarBaseModel {
        override val days: List<DayModel> = days
        override val previousMonthDays: List<Int> = previousMonthDays()
        override val nextMonthDays: List<Int> = nextMonthDays()
        override val previousDays: List<PreviousDayModel> = previousDays
        override val nextPreviousDaysLink: String = nextPreviousDaysLink
        override val includeNextPreviousDaysLinkOnDay: Int = 10
    }
}

private fun YearMonth.previousMonthDays(): List<Int> {
    val previousMonthDaysCount = atDay(1).dayOfWeek.value - 1
    val previousMonth = minusMonths(1)
    val previousMonthLength = previousMonth.lengthOfMonth()
    return ((previousMonthLength - previousMonthDaysCount + 1)..previousMonthLength).toList()
}

private fun YearMonth.nextMonthDays(): List<Int> {
    val nextMonthDaysCount = 7 - atDay(lengthOfMonth()).dayOfWeek.value
    return (1..nextMonthDaysCount).toList()
}

@Suppress("unused")
class HomeViewModel(
    val justCalendar: Boolean,
    val previousMonthLink: String,
    val nextMonthLink: String,
    val todayLink: String,
    val month: String,
    val year: Int,
    val monthImageLink: String,
    calendarBaseModel: CalendarBaseModel,
) : ViewModel, CalendarBaseModel by calendarBaseModel {
    override fun template(): String = if (justCalendar) "calendar" else "home"
}

@Suppress("unused")
class DaysViewModel(
    calendarBaseModel: CalendarBaseModel,
) : ViewModel, CalendarBaseModel by calendarBaseModel {
    override fun template(): String = "hx-days"
}

@Suppress("unused")
class DayViewModel(
    val text: String,
    val backLink: String,
    val dayOfMonth: Int,
) : ViewModel {
    override fun template(): String = "day"
}

class PreviousDaysViewModel(
    previousDaysBaseModel: PreviousDaysBaseModel,
) : ViewModel, PreviousDaysBaseModel by previousDaysBaseModel {
    override fun template(): String = "previous-days"
}

interface CalendarBaseModel : PreviousDaysBaseModel {
    val days: List<DayModel>
    val previousMonthDays: List<Int>
    val nextMonthDays: List<Int>
}

interface PreviousDaysBaseModel {
    val previousDays: List<PreviousDayModel>
    val nextPreviousDaysLink: String
    val includeNextPreviousDaysLinkOnDay: Int
}

private fun LocalDate.toDayModel(
    opened: Boolean,
    now: LocalDate,
): DayModel =
    DayModel(link = "/day/" + format(DateTimeFormatter.ISO_LOCAL_DATE), opened = opened, disabled = this.isAfter(now))

data class DayModel(val link: String, val opened: Boolean, val disabled: Boolean)

data class PreviousDayModel(val date: String, val text: String)

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

object PebbleEngineFactory {
    fun create(hotReloading: Boolean): PebbleEngine =
        if (hotReloading) {
            PebbleEngine.Builder().cacheActive(false)
                .loader(FileLoader().apply { prefix = "src/main/resources" }).build()
        } else {
            PebbleEngine.Builder().cacheActive(true).loader(ClasspathLoader()).build()
        }
}
