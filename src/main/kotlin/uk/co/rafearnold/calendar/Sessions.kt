package uk.co.rafearnold.calendar

import org.http4k.base64Encode
import java.time.Clock
import java.time.LocalDateTime

class Sessions(private val clock: Clock, private val sessionRepository: SessionRepository) {
    fun getUserIdBySession(session: String): Int? {
        val now = clock.now()
        sessionRepository.deleteExpiredSessions(now)
        return sessionRepository.updateSessionExpiryTo(session = session, expiresAt = now.plusExpiryTime())
    }

    fun createSession(userId: Int): String {
        val session = randomBytes(numBytes = 16).base64Encode()
        sessionRepository.addSession(session = session, userId = userId, expiresAt = clock.now().plusExpiryTime())
        return session
    }

    fun deleteSession(session: String) {
        sessionRepository.deleteSession(session = session)
    }

    private fun LocalDateTime.plusExpiryTime(): LocalDateTime = plusWeeks(1)
}
