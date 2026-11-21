package org.suraksha.core

import org.junit.Assert.*
import org.junit.Test

class CallRiskTest {
    @Test fun savedContactsAreNeverFlagged() {
        listOf("+923001234567", "", "+919876543210").forEach {
            assertEquals(CallRiskLevel.NONE, CallRisk.evaluate(it, hidden = false, knownContact = true).level)
        }
    }

    @Test fun hiddenNumbersAreOnlyCautions() {
        assertEquals(CallVerdict(CallRiskLevel.CAUTION, setOf(CallReason.HIDDEN_NUMBER)),
            CallRisk.evaluate(null, hidden = true, knownContact = false))
        assertEquals(CallRiskLevel.CAUTION, CallRisk.evaluate("", hidden = false, knownContact = false).level)
    }

    @Test fun countryPrefixesNeverEstablishFraud() {
        listOf("+92 300 1234567", "0092 300 1234567", "+1 876 555 0100", "+234 803 123 4567",
            "+44 20 7946 0958", "+1 415 555 0100", "+971 50 123 4567").forEach {
            assertEquals(it, CallVerdict(CallRiskLevel.CAUTION, setOf(CallReason.INTERNATIONAL)),
                CallRisk.evaluate(it, hidden = false, knownContact = false))
        }
    }

    @Test fun indianNumberSeriesFollowTraiRules() {
        assertEquals(CallRiskLevel.NONE, CallRisk.evaluate("1600123456", hidden = false, knownContact = false).level)
        assertEquals(CallRiskLevel.NONE, CallRisk.evaluate("+91 1600 123 456", hidden = false, knownContact = false).level)
        assertEquals(setOf(CallReason.TELEMARKETING), CallRisk.evaluate("14012345678", hidden = false, knownContact = false).reasons)
        assertEquals(CallVerdict(CallRiskLevel.CAUTION, setOf(CallReason.UNKNOWN_CALLER)),
            CallRisk.evaluate("+91 98765 43210", hidden = false, knownContact = false))
        assertEquals(CallRiskLevel.NONE, CallRisk.evaluate("121", hidden = false, knownContact = false).level)
    }

    @Test fun reportedNumbersMatchInAnyFormat() {
        val reported = setOf("098765 43210")
        listOf("+919876543210", "9876543210", "09876543210", "+91 98765-43210").forEach {
            assertEquals(it, CallVerdict(CallRiskLevel.HIGH, setOf(CallReason.REPORTED_NUMBER)),
                CallRisk.evaluate(it, hidden = false, knownContact = false, reported = reported))
        }
    }

    @Test fun aLocalReportDoesNotOverrideKnownContacts() {
        assertEquals(CallRiskLevel.NONE, CallRisk.evaluate("+919876543210", hidden = false, knownContact = true,
            reported = setOf("09876543210")).level)
    }

    @Test fun reportKeysDeduplicateNationalFormatsAndPreserveInternationalCountries() {
        val key = "+919876543210"
        listOf("+91 98765-43210", "00919876543210", "09876543210", "9876543210", "919876543210").forEach {
            assertEquals(it, key, CallRisk.reportKey(it))
        }
        assertEquals("+449876543210", CallRisk.reportKey("+44 98765 43210"))
        assertNotEquals(CallRisk.reportKey("+449876543210"), CallRisk.reportKey("449876543210"))
    }

    @Test fun malformedNumbersCannotBecomeSavedReports() {
        listOf("", "+", "++919876543210", "hello9876543210", "12", "+1234567890123456", "91+9876543210").forEach {
            assertNull(it, CallRisk.reportKey(it))
        }
    }

    @Test fun aForeignReportDoesNotFlagAnUnrelatedDomesticCaller() {
        assertEquals(CallRiskLevel.CAUTION, CallRisk.evaluate("449876543210", hidden = false, knownContact = false,
            reported = setOf("+449876543210")).level)
        assertEquals(CallRiskLevel.HIGH, CallRisk.evaluate("+449876543210", hidden = false, knownContact = false,
            reported = setOf("00449876543210")).level)
    }
}
