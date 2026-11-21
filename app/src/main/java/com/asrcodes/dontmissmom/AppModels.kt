package com.asrcodes.dontmissmom

data class UserProfile(val uid: String, val name: String, val phone: String, val email: String)

/** Captured once per send so callbacks cannot lose or replace the attached recording. */
data class AlertAttachment(val latitude: Double? = null, val longitude: Double? = null, val voiceUrl: String? = null)

data class HistoryPage(val items: List<EmergencyHistory>, val hasMore: Boolean)

object PhoneNumbers {
    fun normalize(raw: String): String? {
        val compact = raw.trim().replace(Regex("[\\s()\\-]"), "")
        val local = when {
            compact.startsWith("+91") -> compact.substring(3)
            compact.length == 12 && compact.startsWith("91") -> compact.substring(2)
            else -> compact
        }
        return if (local.matches(Regex("[6-9][0-9]{9}"))) "+91$local" else null
    }
}

object SirenPolicy {
    const val TIMEOUT_MS = 5 * 60_000L
    const val MUTE_DURATION_MS = 6 * 60 * 60 * 1000L
    fun isEnabled(enabled: Boolean, permanent: Boolean, mutedAt: Long, now: Long): Boolean =
        enabled || (!permanent && (mutedAt <= 0 || now - mutedAt >= MUTE_DURATION_MS || now < mutedAt))
}

class AppProblem(val resource: Int) : Exception()
fun interface Subscription { fun remove() }

interface AppRepository {
    val currentUid: String?
    fun observeProfile(callback: (Result<UserProfile>) -> Unit): Subscription
    fun observeContacts(callback: (Result<List<TrustedContact>>) -> Unit): Subscription
    fun observeRequests(callback: (Result<List<RequestItem>>) -> Unit): Subscription
    fun observeHistory(limit: Int, callback: (Result<HistoryPage>) -> Unit): Subscription
    fun addContact(phone: String, callback: (Result<Unit>) -> Unit)
    fun updatePhone(phone: String, callback: (Result<Unit>) -> Unit)
    fun setBlocked(contact: TrustedContact, blocked: Boolean, callback: (Result<Unit>) -> Unit)
    fun removeContact(contact: TrustedContact, callback: (Result<Unit>) -> Unit)
    fun acceptRequest(request: RequestItem, callback: (Result<Unit>) -> Unit)
    fun rejectRequest(request: RequestItem, callback: (Result<Unit>) -> Unit)
    fun reportRequest(request: RequestItem, reason: String, callback: (Result<Unit>) -> Unit)
    fun sendAlert(contact: TrustedContact, attachment: AlertAttachment, alertId: String, callback: (Result<Unit>) -> Unit): Subscription
    fun syncToken()
}

object AppDependencies {
    // Instrumentation supplies a fake repository. Release behavior always uses Firebase.
    internal var repositoryFactory: (() -> AppRepository)? = null
    fun repository(): AppRepository = repositoryFactory?.invoke() ?: FirebaseAppRepository()
}
