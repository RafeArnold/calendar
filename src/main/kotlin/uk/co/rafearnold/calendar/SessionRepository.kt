package uk.co.rafearnold.calendar

import org.jooq.DSLContext
import uk.co.rafearnold.calendar.jooq.tables.references.SESSIONS
import java.time.LocalDateTime

class SessionRepository(private val ctx: DSLContext) {
    fun updateSessionExpiryTo(
        session: String,
        expiresAt: LocalDateTime,
    ): Int? =
        ctx.update(SESSIONS)
            .set(SESSIONS.EXPIRES_AT, expiresAt)
            .where(SESSIONS.SESSION.eq(session))
            .returning(SESSIONS.USER_ID)
            .fetchOne(SESSIONS.USER_ID)

    fun addSession(
        session: String,
        userId: Int,
        expiresAt: LocalDateTime,
    ) {
        ctx.insertInto(SESSIONS)
            .set(SESSIONS.SESSION, session)
            .set(SESSIONS.USER_ID, userId)
            .set(SESSIONS.EXPIRES_AT, expiresAt)
            .execute()
    }

    fun deleteSession(session: String) {
        ctx.deleteFrom(SESSIONS).where(SESSIONS.SESSION.eq(session)).execute()
    }

    fun deleteExpiredSessions(now: LocalDateTime) {
        ctx.deleteFrom(SESSIONS).where(SESSIONS.EXPIRES_AT.lessOrEqual(now)).execute()
    }
}
