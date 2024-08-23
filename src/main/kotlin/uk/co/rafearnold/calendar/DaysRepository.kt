package uk.co.rafearnold.calendar

import org.jooq.DSLContext
import uk.co.rafearnold.calendar.jooq.tables.references.OPENED_DAYS
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

class DaysRepository(private val ctx: DSLContext, private val clock: Clock) {
    fun getOpenedDaysOfMonth(
        user: User,
        month: YearMonth,
    ): List<Int> =
        ctx.selectFrom(OPENED_DAYS)
            .where(OPENED_DAYS.USER_ID.eq(user.id))
            .and(OPENED_DAYS.DATE.greaterOrEqual(month.atDay(1)))
            .and(OPENED_DAYS.DATE.lessOrEqual(month.atDay(month.lengthOfMonth())))
            .fetch()
            .map { it.date!!.dayOfMonth }

    fun getOpenedDaysDescFrom(
        user: User,
        from: LocalDate,
        limit: Int,
    ): List<LocalDate> =
        ctx.selectFrom(OPENED_DAYS)
            .where(OPENED_DAYS.USER_ID.eq(user.id))
            .and(OPENED_DAYS.DATE.lessOrEqual(from))
            .orderBy(OPENED_DAYS.DATE.desc())
            .limit(limit)
            .fetch()
            .map { it.date!! }

    fun markDayAsOpened(
        user: User,
        date: LocalDate,
    ) {
        ctx.insertInto(OPENED_DAYS)
            .set(OPENED_DAYS.DATE, date)
            .set(OPENED_DAYS.USER_ID, user.id)
            .set(OPENED_DAYS.OPENED, clock.now())
            .onConflictDoNothing()
            .execute()
    }
}
