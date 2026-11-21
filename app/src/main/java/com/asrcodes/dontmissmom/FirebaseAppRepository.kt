package com.asrcodes.dontmissmom

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import org.json.JSONObject

class FirebaseAppRepository : AppRepository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    override val currentUid get() = auth.currentUser?.uid
    private val main = Handler(Looper.getMainLooper())

    private fun profile(doc: DocumentSnapshot) = UserProfile(doc.id,
        doc.getString("username").orEmpty(), doc.getString("phone").orEmpty(), doc.getString("email").orEmpty())

    override fun observeProfile(callback: (Result<UserProfile>) -> Unit): Subscription {
        val uid = currentUid ?: return Subscription {}.also { callback(Result.failure(AppProblem(R.string.sign_in_error))) }
        val registration = db.collection("users").document(uid).addSnapshotListener { doc, error ->
            if (error != null || doc == null || !doc.exists()) callback(Result.failure(error ?: AppProblem(R.string.profile_error)))
            else callback(Result.success(profile(doc)))
        }
        return Subscription { registration.remove() }
    }

    override fun observeContacts(callback: (Result<List<TrustedContact>>) -> Unit): Subscription {
        val uid = currentUid ?: return Subscription {}
        val registration = db.collection("users").document(uid).collection("trustedContacts")
            .addSnapshotListener { snapshot, error ->
                if (error != null) callback(Result.failure(error))
                else callback(Result.success(snapshot?.documents.orEmpty().map {
                    TrustedContact(it.id, it.getString("username").orEmpty(), it.getString("phone").orEmpty(), it.getBoolean("blocked") == true)
                }.sortedBy { it.username.lowercase() }))
            }
        return Subscription { registration.remove() }
    }

    override fun observeRequests(callback: (Result<List<RequestItem>>) -> Unit): Subscription {
        val uid = currentUid ?: return Subscription {}
        val registration = db.collection("requests").whereEqualTo("toUid", uid).whereEqualTo("status", "pending")
            .addSnapshotListener { snapshot, error ->
                if (error != null) callback(Result.failure(error))
                else callback(Result.success(snapshot?.documents.orEmpty().sortedByDescending { it.getLong("timestamp") ?: 0 }.map {
                    RequestItem(it.id, it.getString("fromUid").orEmpty(), it.getString("fromUsername").orEmpty(),
                        it.getString("fromPhone").orEmpty(), it.getString("toUid").orEmpty(), it.getString("toPhone").orEmpty())
                }))
            }
        return Subscription { registration.remove() }
    }

    private fun <T> finish(task: Task<T>, callback: (Result<Unit>) -> Unit) {
        var finished = false
        val timeout = Runnable {
            if (!finished) { finished = true; callback(Result.failure(AppProblem(R.string.operation_pending))) }
        }
        main.postDelayed(timeout, 30_000)
        task.addOnCompleteListener { result ->
            if (!finished) {
                finished = true
                main.removeCallbacks(timeout)
                callback(if (result.isSuccessful) Result.success(Unit) else Result.failure(result.exception ?: AppProblem(R.string.operation_error)))
            }
        }
    }

    override fun addContact(phone: String, callback: (Result<Unit>) -> Unit) {
        post("/v2/requests", JSONObject().put("phone", phone), callback)
    }

    override fun updatePhone(phone: String, callback: (Result<Unit>) -> Unit) {
        post("/v2/profile/phone", JSONObject().put("phone", phone), callback)
    }

    override fun setBlocked(contact: TrustedContact, blocked: Boolean, callback: (Result<Unit>) -> Unit) {
        val uid = currentUid ?: return callback(Result.failure(AppProblem(R.string.sign_in_error)))
        finish(db.collection("users").document(uid).collection("trustedContacts").document(contact.uid).update("blocked", blocked), callback)
    }

    override fun removeContact(contact: TrustedContact, callback: (Result<Unit>) -> Unit) {
        val uid = currentUid ?: return callback(Result.failure(AppProblem(R.string.sign_in_error)))
        val batch = db.batch()
        batch.delete(db.collection("users").document(uid).collection("trustedContacts").document(contact.uid))
        batch.delete(db.collection("users").document(contact.uid).collection("trustedContacts").document(uid))
        finish(batch.commit(), callback)
    }

    override fun acceptRequest(request: RequestItem, callback: (Result<Unit>) -> Unit) {
        if (!request.id.matches(Regex("[A-Za-z0-9_-]{1,300}"))) return callback(Result.failure(AppProblem(R.string.operation_error)))
        post("/v2/requests/${request.id}/accept", JSONObject(), callback)
    }

    override fun rejectRequest(request: RequestItem, callback: (Result<Unit>) -> Unit) {
        finish(db.collection("requests").document(request.id).update("status", "rejected"), callback)
    }

    override fun reportRequest(request: RequestItem, reason: String, callback: (Result<Unit>) -> Unit) {
        if (!request.id.matches(Regex("[A-Za-z0-9_-]{1,300}"))) return callback(Result.failure(AppProblem(R.string.operation_error)))
        post("/v2/requests/${request.id}/report", JSONObject().put("reason", reason.trim()), callback)
    }

    override fun observeHistory(limit: Int, callback: (Result<HistoryPage>) -> Unit): Subscription {
        val uid = currentUid ?: return Subscription {}.also { callback(Result.failure(AppProblem(R.string.sign_in_error))) }
        // Include both directions and every outcome. Siren/receipt changes never hide an event.
        val registration = db.collection("history").whereEqualTo("ownerUid", uid)
            .orderBy("timestamp", Query.Direction.DESCENDING).limit(limit.toLong() + 1).addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("EmergencyHistory", "History query failed: ${error.code}")
                    callback(Result.failure(error))
                }
                else {
                    val documents = snapshot?.documents.orEmpty()
                    callback(Result.success(HistoryPage(documents.take(limit).map(EmergencyHistory::from), documents.size > limit)))
                }
            }
        return Subscription { registration.remove() }
    }

    override fun sendAlert(contact: TrustedContact, attachment: AlertAttachment, alertId: String, callback: (Result<Unit>) -> Unit): Subscription {
        val user = auth.currentUser ?: return Subscription {}.also { callback(Result.failure(AlertProblem(R.string.session_expired))) }
        val json = JSONObject().put("toUid", contact.uid).put("alertId", alertId)
            .put("hasLocation", attachment.latitude != null && attachment.longitude != null)
            .put("latitude", attachment.latitude ?: 0.0).put("longitude", attachment.longitude ?: 0.0)
        attachment.voiceUrl?.let { json.put("voiceUrl", it) }
        val started = SystemClock.elapsedRealtime()
        // The versioned route requires the authenticated, idempotent backend contract.
        val call = AlertHttpCall(BASE_URL + "/v2/sendEmergency", json.toString()) { event ->
            Log.i("AlertRequest", "id=$alertId elapsedMs=${SystemClock.elapsedRealtime() - started} $event")
        }
        var cancelled = false
        val operation = call.start(token = { done ->
            user.getIdToken(false).addOnSuccessListener { token ->
                done(token.token?.let { Result.success(it) } ?: Result.failure(AppProblem(R.string.session_expired)))
            }.addOnFailureListener { done(Result.failure(it)) }
        }, sessionValid = { currentUid == user.uid }) { response ->
            val result = response.fold(onSuccess = { http ->
                val body = runCatching { JSONObject(http.body) }.getOrNull()
                val error = AlertResponses.problem(http.status, body?.optBoolean("success") == true, body?.optString("code").orEmpty())
                if (error == null) Result.success(Unit) else Result.failure(error)
            }, onFailure = { error ->
                Result.failure(when (error) {
                    is AppProblem -> AlertProblem(error.resource)
                    is AlertTransportException -> if (error.unconfirmed) AlertProblem(R.string.alert_unconfirmed, true, true)
                        else if (error.cause is AlertDeadlineException) AlertProblem(R.string.alert_start_timeout, true)
                        else AlertProblem(R.string.alert_connection_failed, true)
                    is com.google.firebase.FirebaseNetworkException -> AlertProblem(R.string.alert_connection_failed, true)
                    else -> AlertProblem(R.string.session_expired)
                })
            })
            main.post { if (!cancelled && currentUid == user.uid) callback(result) }
        }
        return Subscription { cancelled = true; operation.remove() }
    }

    /** A response can be lost after the server commits; only report confirmed success. */
    private fun post(path: String, body: JSONObject, callback: (Result<Unit>) -> Unit, internalCallback: Boolean = false) {
        val user = auth.currentUser ?: return callback(Result.failure(AppProblem(R.string.sign_in_error)))
        // Profile/contact requests wake the same Render service as SOS. Use the
        // same bounded transport so a cold start does not outlive the UI timer.
        AlertHttpCall(BASE_URL + path, body.toString()).start(token = { done ->
            user.getIdToken(false).addOnSuccessListener { token ->
                done(token.token?.let { Result.success(it) } ?: Result.failure(AppProblem(R.string.session_expired)))
            }.addOnFailureListener { done(Result.failure(it)) }
        }, sessionValid = { currentUid == user.uid }) { response ->
            val result = response.fold(onSuccess = { http ->
                val json = runCatching { JSONObject(http.body) }.getOrNull()
                val problem = AccountResponses.problem(path, http.status, json?.optBoolean("success") == true, json?.optString("code").orEmpty())
                if (problem == null) Result.success(Unit) else Result.failure(problem)
            }, onFailure = { error ->
                Result.failure(when (error) {
                    is AppProblem -> error
                    is AlertTransportException -> AppProblem(if (error.unconfirmed) R.string.operation_pending else R.string.connection_error)
                    is com.google.firebase.FirebaseNetworkException -> AppProblem(R.string.connection_error)
                    else -> AppProblem(R.string.session_expired)
                })
            })
            main.post { if (currentUid == user.uid || internalCallback) callback(result) }
        }
    }

    override fun syncToken() {
        FcmTokenSync.sync()
    }

    fun registerVoice(url: String, callback: (Result<Unit>) -> Unit) {
        post("/v2/voice/register", JSONObject().put("voiceUrl", url), callback, internalCallback = true)
    }

    companion object {
        private const val BASE_URL = "https://dontmissmom-backend.onrender.com"
    }
}
