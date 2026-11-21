package com.asrcodes.dontmissmom

import org.junit.Assert.*
import org.junit.Test

class AccountResponsesTest {
    @Test fun missingEndpointDiffersFromAnUnknownPhoneNumber() {
        assertEquals(R.string.backend_update_required, AccountResponses.problem("/v2/requests", 404, false, "")!!.resource)
        assertEquals(R.string.user_not_found, AccountResponses.problem("/v2/requests", 404, false, "user_not_found")!!.resource)
    }

    @Test fun duplicatesAndPhoneConflictsKeepTheirSpecificMessages() {
        assertEquals(R.string.duplicate_request, AccountResponses.problem("/v2/requests", 409, false, "duplicate_request")!!.resource)
        assertEquals(R.string.duplicate_contact, AccountResponses.problem("/v2/requests", 409, false, "duplicate_contact")!!.resource)
        assertEquals(R.string.phone_taken, AccountResponses.problem("/v2/profile/phone", 409, false, "")!!.resource)
        assertEquals(R.string.operation_error, AccountResponses.problem("/v2/requests", 409, false, "")!!.resource)
    }

    @Test fun onlyAnExplicitSuccessConfirmsTheOperation() {
        for (path in listOf("/v2/profile/phone", "/v2/requests", "/v2/requests/incoming/accept")) {
            assertNull(AccountResponses.problem(path, 200, true, ""))
            for (status in listOf(200, 408, 500, 502, 503, 504)) {
                assertEquals(R.string.operation_pending, AccountResponses.problem(path, status, false, "")!!.resource)
            }
            assertEquals(R.string.session_expired, AccountResponses.problem(path, 401, false, "")!!.resource)
            assertEquals(R.string.request_rate_limit, AccountResponses.problem(path, 429, false, "")!!.resource)
        }
    }
}
