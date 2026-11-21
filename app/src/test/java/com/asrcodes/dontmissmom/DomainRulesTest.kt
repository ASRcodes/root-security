package com.asrcodes.dontmissmom

import org.junit.Assert.*
import org.junit.Test

class DomainRulesTest {
    @Test fun phoneNumbersNormalizeFormattingWithoutAcceptingMalformedInput() {
        listOf("9876543210", "+91 98765 43210", "91-9876543210", "(98765) 43210").forEach {
            assertEquals("+919876543210", PhoneNumbers.normalize(it))
        }
        listOf("", "1234567890", "+19876543210", "987654321", "98765432100", "98765abc10").forEach {
            assertNull(PhoneNumbers.normalize(it))
        }
    }
    @Test fun temporaryMuteExpiresAtSixHoursEvenWithoutOpeningTheApp() {
        val start = 1000L
        assertFalse(SirenPolicy.isEnabled(false, false, start, start + SirenPolicy.MUTE_DURATION_MS - 1))
        assertTrue(SirenPolicy.isEnabled(false, false, start, start + SirenPolicy.MUTE_DURATION_MS))
        assertTrue(SirenPolicy.isEnabled(false, false, start, start - 1))
        assertFalse(SirenPolicy.isEnabled(false, true, start, Long.MAX_VALUE))
    }
    @Test fun locationAcceptsEquatorAndMeridianButRejectsInvalidCoordinates() {
        assertTrue(IncomingAlert.validCoordinates(0.0, 10.0))
        assertTrue(IncomingAlert.validCoordinates(10.0, 0.0))
        assertFalse(IncomingAlert.validCoordinates(Double.NaN, 0.0))
        assertFalse(IncomingAlert.validCoordinates(90.1, 0.0))
        assertFalse(IncomingAlert.validCoordinates(null, 0.0))
    }
    @Test fun bothPayloadGenerationsKeepSenderAndAttachments() {
        val url = "https://res.cloudinary.com/dqrb7jasx/raw/upload/v1/sos_voice_memos/test.3gp"
        val alert = IncomingAlert.fromData(mapOf("senderName" to "Mom", "senderPhone" to "+919876543210",
            "latitude" to "0", "longitude" to "77", "voiceUrl" to url), "message-id")
        assertEquals("Mom", alert.name)
        assertEquals(0.0, alert.latitude!!, 0.0)
        assertEquals(url, alert.voiceUrl)
        val missing = IncomingAlert.fromData(mapOf("latitude" to "0", "longitude" to "0"), "id")
        assertNull(missing.latitude)
    }
    @Test fun voicePlaybackOnlyAcceptsExpectedSecureHosting() {
        assertFalse(IncomingAlert.validVoiceUrl("http://res.cloudinary.com/dqrb7jasx/raw/upload/test"))
        assertFalse(IncomingAlert.validVoiceUrl("https://res.cloudinary.com.attacker.test/dqrb7jasx/raw/upload/test"))
        assertFalse(IncomingAlert.validVoiceUrl("file:///storage/emulated/0/test"))
        assertFalse(IncomingAlert.validVoiceUrl("https://res.cloudinary.com/another-cloud/raw/upload/test"))
    }
}
