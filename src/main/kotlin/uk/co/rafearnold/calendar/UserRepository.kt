package uk.co.rafearnold.calendar

import org.jooq.DSLContext
import uk.co.rafearnold.calendar.jooq.tables.records.UsersRecord
import uk.co.rafearnold.calendar.jooq.tables.references.USERS

class UserRepository(private val ctx: DSLContext) {
    fun getByUserId(userId: Int): User? = ctx.selectFrom(USERS).where(USERS.USER_ID.eq(userId)).fetchOne()?.toUser()

    fun getByEmail(email: String): User? =
        ctx.selectFrom(USERS).where(USERS.EMAIL_ADDRESS.eq(email)).fetchOne()?.toUser()

    fun createUserIfNoneExists(
        email: String,
        googleSubjectId: String,
    ): User {
        ctx.insertInto(USERS)
            .set(USERS.EMAIL_ADDRESS, email)
            .set(USERS.GOOGLE_SUBJECT_ID, googleSubjectId)
            .onConflictDoNothing()
            .execute()
        return getByEmail(email = email)!!
    }

    private fun UsersRecord.toUser(): User =
        User(id = userId!!, googleSubjectId = googleSubjectId!!, email = emailAddress!!)
}

data class User(val id: Int, val googleSubjectId: String, val email: String, val isAdmin: Boolean = false)
