package uk.co.rafearnold.calendar

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val random = SecureRandom()

fun randomBytes(numBytes: Int): ByteArray = ByteArray(numBytes).apply { random.nextBytes(this) }

fun hmacSha256(
    data: ByteArray,
    key: ByteArray,
): ByteArray = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
