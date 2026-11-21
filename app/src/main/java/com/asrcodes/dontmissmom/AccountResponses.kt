package com.asrcodes.dontmissmom

internal object AccountResponses {
    fun problem(path: String, status: Int, success: Boolean, code: String): AppProblem? {
        if (status in 200..299 && success) return null
        val resource = when (code) {
            "user_not_found" -> R.string.user_not_found
            "self_request" -> R.string.self_request
            "duplicate_contact" -> R.string.duplicate_contact
            "duplicate_request" -> R.string.duplicate_request
            else -> when {
                status == 401 -> R.string.session_expired
                status == 403 -> R.string.contact_unavailable
                status == 404 -> R.string.backend_update_required
                status == 409 && path == "/v2/profile/phone" -> R.string.phone_taken
                status == 429 -> R.string.request_rate_limit
                status in 200..299 || status == 408 || status >= 500 -> R.string.operation_pending
                else -> R.string.operation_error
            }
        }
        return AppProblem(resource)
    }
}
