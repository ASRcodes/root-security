package org.suraksha.kavach

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.suraksha.core.*
import org.suraksha.kavach.ui.*

class AlertActivity : LocalizedActivity() {
    private var result by mutableStateOf(ScanResult("", Verdict(Level.SUSPICIOUS, 0.0, emptySet()), 0L))
    private var originalResult = result
    private var historicalGoogle by mutableStateOf(false)
    private var expiryJob: Job? = null
    private val graph get() = application as KavachApplication
    companion object {
        fun intent(context: Context, result: ScanResult, speak: Boolean = true) = Intent(context, AlertActivity::class.java)
            .putExtra("domain", result.domain.take(253)).putExtra("level", result.verdict.level.name)
            .putExtra("reasons", result.verdict.reasonCodes.map { it.name }.toTypedArray())
            .putExtra("timestamp", result.timestamp).putExtra("speak", speak)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .also { if (context !is Activity) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        private fun read(intent: Intent): ScanResult = ScanResult(intent.getStringExtra("domain").orEmpty().take(253),
            Verdict(runCatching { Level.valueOf(intent.getStringExtra("level").orEmpty()) }.getOrDefault(Level.SUSPICIOUS),
                0.0, intent.getStringArrayExtra("reasons").orEmpty()
                    .mapNotNull { runCatching { ReasonCode.valueOf(it) }.getOrNull() }.toSet()),
            intent.getLongExtra("timestamp", 0L))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (graph.secureWindows) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        acceptResult(read(intent))
        setContent {
            KavachTheme {
                val settings by graph.settings.state.collectAsStateWithLifecycle()
                val guardian by graph.guardian.state.collectAsStateWithLifecycle()
                BackHandler { dismiss() }
                WarningScreen(result, settings.language, onUnderstand = ::dismiss,
                    onGuardian = { graph.dismissWarnings(); graph.callGuardian(this); finish() },
                    onReplay = {
                        refreshFreshness()
                        if (result.verdict.level == Level.DANGEROUS) { graph.alerts.cancel(); graph.previewWarning() }
                    },
                    guardianStatus = if (guardian.enabled) stringResource(guardianStatusLabel(guardian.status)) else null,
                    historicalGoogle = historicalGoogle)
            }
        }
        if (savedInstanceState == null) speakIfNeeded()
    }
    private fun updateBars() {
        val clear = android.graphics.Color.TRANSPARENT
        val danger = result.verdict.level == Level.DANGEROUS
        val style = if (danger) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear)
        enableEdgeToEdge(style, style)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(danger); setTurnScreenOn(danger) }
        else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (danger) window.addFlags(flags) else window.clearFlags(flags)
        }
    }
    private fun acceptResult(incoming: ScanResult) {
        originalResult = incoming
        expiryJob?.cancel()
        refreshFreshness()
        if (ReasonCode.GOOGLE_REPUTATION in result.verdict.reasonCodes) {
            expiryJob = lifecycleScope.launch {
                val remaining = ReputationPolicy.MAX_AGE_MILLIS - (System.currentTimeMillis() - result.timestamp)
                delay(remaining.coerceAtLeast(0L))
                refreshFreshness()
            }
        }
    }
    private fun refreshFreshness() {
        val current = ReputationPolicy.expire(originalResult, System.currentTimeMillis())
        historicalGoogle = ReasonCode.GOOGLE_REPUTATION in originalResult.verdict.reasonCodes &&
            ReasonCode.GOOGLE_REPUTATION !in current.verdict.reasonCodes
        if (result.verdict.level == Level.DANGEROUS && current.verdict.level != Level.DANGEROUS) {
            graph.audio.stop()
            graph.alerts.cancel()
        }
        result = current
        updateBars()
    }
    private fun speakIfNeeded() {
        refreshFreshness()
        if (result.verdict.level == Level.DANGEROUS && ReasonCode.EICAR_TEST !in result.verdict.reasonCodes && intent.getBooleanExtra("speak", true)) graph.speakWarning()
    }
    private fun dismiss() { graph.dismissWarnings(); finish() }
    override fun onResume() { super.onResume(); refreshFreshness() }
    override fun onStop() { graph.audio.stop(); super.onStop() }
    override fun onDestroy() { expiryJob?.cancel(); super.onDestroy() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); acceptResult(read(intent)); speakIfNeeded()
    }
}

@Composable
fun WarningScreen(result: ScanResult, language: String, onUnderstand: () -> Unit,
                  onGuardian: (() -> Unit)? = null, onReplay: (() -> Unit)? = null, guardianStatus: String? = null,
                  historicalGoogle: Boolean = false) {
    val danger = result.verdict.level == Level.DANGEROUS
    val reasons = result.verdict.reasonCodes
    val test = ReasonCode.DEMO_ONLY in reasons || ReasonCode.PROVIDER_TEST in reasons ||
        ReasonCode.EICAR_TEST in reasons || ReasonCode.TEST_INPUT in reasons
    val neutral = result.verdict.level == Level.SAFE
    val background = if (danger) Color(0xFF991B1B) else if (neutral) Color(0xFFE9EEF0) else Color(0xFFFFE5A3)
    val foreground = if (danger) Color.White else if (neutral) Color(0xFF24363D) else Color(0xFF402700)
    Column(Modifier.fillMaxSize().background(background).safeDrawingPadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Text(stringResource(when {
                ReasonCode.EICAR_TEST in reasons -> R.string.sk_file_test_badge
                ReasonCode.DEMO_ONLY in reasons -> R.string.sk_demo_badge
                ReasonCode.TEST_INPUT in reasons -> R.string.sk_demo_reserved_badge
                ReasonCode.PROVIDER_TEST in reasons -> R.string.sk_google_test_badge
                ReasonCode.REPUTATION_NOT_CHECKED in reasons && neutral -> R.string.sk_history_not_checked
                danger -> R.string.sk_danger_badge
                neutral -> R.string.sk_saved_result_badge
                else -> R.string.sk_caution_badge
            }),
                color = foreground, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(warningTitle(result)),
                color = foreground, fontSize = 40.sp, lineHeight = 49.sp, fontWeight = FontWeight.Bold)
            if (result.domain.isNotBlank()) Text(result.domain, color = foreground, style = MaterialTheme.typography.titleLarge)
            HorizontalDivider(color = foreground.copy(alpha = 0.4f))
            if (historicalGoogle) Text(stringResource(R.string.sk_history_expired_threat), color = foreground,
                style = MaterialTheme.typography.bodyLarge)
            ReasonMessages.ordered(reasons.filter { it != ReasonCode.FILE_SCAN })
                .take(4).forEach {
                Text(ReasonMessages.get(it, language), color = foreground, style = MaterialTheme.typography.bodyLarge)
            }
            Text(stringResource(when {
                ReasonCode.DEMO_ONLY in reasons -> R.string.sk_demo_preview_detail
                ReasonCode.TEST_INPUT in reasons -> R.string.sk_demo_reserved_detail
                ReasonCode.PROVIDER_TEST in reasons -> R.string.sk_google_test_detail
                historicalGoogle -> R.string.sk_reputation_expired_detail
                danger && ReasonCode.LOCAL_RISK in reasons -> R.string.sk_alert_advice
                ReasonCode.REPUTATION_NOT_CHECKED in reasons -> R.string.sk_not_checked_advice
                ReasonCode.FILE_SCAN in reasons -> R.string.sk_file_advice
                neutral -> R.string.sk_safe_note
                else -> R.string.sk_alert_advice
            }), color = foreground, style = MaterialTheme.typography.bodyLarge)
            if (ReasonCode.GOOGLE_REPUTATION in reasons || ReasonCode.REPUTATION_NO_MATCH in reasons || ReasonCode.REPUTATION_NOT_CHECKED in reasons) {
                Text(stringResource(R.string.sk_online_redirect_scope), color = foreground, style = MaterialTheme.typography.bodyMedium)
            }
            if (danger && !test) guardianStatus?.let { Text(it, color = foreground, style = MaterialTheme.typography.bodyMedium) }
            onReplay?.takeIf { danger && ReasonCode.EICAR_TEST !in reasons }?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp),
                    border = BorderStroke(1.dp, foreground), colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground)) {
                    Text(stringResource(R.string.sk_replay_warning))
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 22.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            onGuardian?.takeIf { !test }?.let {
                Button(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF991B1B))) {
                    Text(stringResource(R.string.sk_call_guardian))
                }
            }
            OutlinedButton(onClick = onUnderstand, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                border = BorderStroke(2.dp, foreground),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground)) {
                Text(stringResource(R.string.sk_understand))
            }
        }
    }
}

fun warningTitle(result: ScanResult): Int = when {
    ReasonCode.EICAR_TEST in result.verdict.reasonCodes -> R.string.sk_eicar_title
    ReasonCode.DEMO_ONLY in result.verdict.reasonCodes -> R.string.sk_simulation_title
    ReasonCode.TEST_INPUT in result.verdict.reasonCodes -> when (result.verdict.level) {
        Level.DANGEROUS -> R.string.sk_demo_danger_title
        Level.SUSPICIOUS -> R.string.sk_demo_caution_title
        Level.SAFE -> R.string.sk_demo_normal_title
    }
    result.verdict.level == Level.SAFE && ReasonCode.REPUTATION_NOT_CHECKED in result.verdict.reasonCodes -> R.string.sk_online_not_checked
    ReasonCode.PROVIDER_TEST in result.verdict.reasonCodes -> R.string.sk_google_test_title
    result.verdict.level == Level.SAFE && ReasonCode.REPUTATION_NO_MATCH in result.verdict.reasonCodes -> R.string.sk_online_no_known_threat
    result.verdict.level == Level.SAFE -> R.string.sk_level_safe
    result.verdict.level == Level.SUSPICIOUS -> R.string.sk_online_unconfirmed_pattern
    ReasonCode.FILE_SCAN in result.verdict.reasonCodes -> R.string.sk_file_danger_title
    ReasonCode.GOOGLE_REPUTATION in result.verdict.reasonCodes -> R.string.sk_online_possible_threat
    result.domain.isEmpty() -> R.string.sk_alert_message_title
    else -> R.string.sk_alert_title
}
