package com.asrcodes.dontmissmom

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.runner.AndroidJUnitRunner
import org.suraksha.kavach.data.SecretStore
import org.suraksha.kavach.family.SmsSender
import org.suraksha.kavach.reputation.OnlineUrlChecker
import org.suraksha.kavach.reputation.ReputationTransport
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Device tests never send carrier SMS or contact Google. Production secure windows remain enabled. */
class DmmTestApplication : DmmApplication() {
    override val secureWindows = false
    val sms = CopyOnWriteArrayList<Pair<String, String>>()
    override fun createSmsSender() = SmsSender { destination, message, _ ->
        sms += destination to message
    }

    val reputationRequests = CopyOnWriteArrayList<Set<String>>()
    @Volatile var reputationResponder: suspend (Set<String>) -> String = {
        throw IOException("No reputation test response configured")
    }
    private val reputationPrefix = "root_security_test_online_" + UUID.randomUUID().toString() + "_"
    /** A separate namespace prevents instrumentation from reading a user's saved provider key. */
    val reputationTestContext: Context by lazy {
        object : ContextWrapper(this) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(reputationPrefix + name, mode)
        }
    }
    override fun createOnlineChecker() = OnlineUrlChecker(reputationTestContext,
        SecretStore(reputationTestContext), scope, ReputationTransport { prefixes, key ->
            check(key == REPUTATION_FIXTURE_KEY) { "Only an emulator fixture key is permitted in instrumentation" }
            reputationRequests += prefixes.toSet()
            reputationResponder(prefixes)
        })

    companion object {
        const val REPUTATION_FIXTURE_KEY = "root_security_emulator_fixture_key_not_real"
    }
}

class DmmTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, DmmTestApplication::class.java.name, context)
}
