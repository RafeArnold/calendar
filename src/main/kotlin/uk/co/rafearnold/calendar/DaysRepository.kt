package uk.co.rafearnold.calendar

import org.jooq.DSLContext
import uk.co.rafearnold.calendar.jooq.tables.references.OPENED_DAYS
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

class DaysRepository(private val ctx: DSLContext, private val clock: Clock) {
    fun getOpenedDaysOfMonth(month: YearMonth): List<Int> =
        ctx.selectFrom(OPENED_DAYS)
            .where(OPENED_DAYS.DATE.greaterOrEqual(month.atDay(1)))
            .and(OPENED_DAYS.DATE.lessOrEqual(month.atDay(month.lengthOfMonth())))
            .fetch()
            .map { it.date!!.dayOfMonth }

    fun markDayAsOpened(date: LocalDate) {
        ctx.insertInto(OPENED_DAYS)
            .set(OPENED_DAYS.DATE, date)
            .set(OPENED_DAYS.OPENED, clock.now())
            .onConflictDoNothing()
            .execute()
    }
}
