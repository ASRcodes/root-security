package org.suraksha.kavach.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.suraksha.core.*
import org.suraksha.kavach.*
import org.suraksha.kavach.R

private data class DemoSample(val id: String, val label: Int, val message: String, val expected: Int)

/** These are input examples, never verdict fixtures. Every scan uses the real pipeline. */
private val demoInputs = listOf(
    DemoSample("wikipedia", R.string.sk_demo_normal_label, DemoExamples.NORMAL_URL, R.string.sk_demo_normal_expected),
    DemoSample("airtel", R.string.sk_demo_airtel, DemoExamples.AIRTEL_URL, R.string.sk_demo_normal_expected),
    DemoSample("goodmonk", R.string.sk_demo_goodmonk, DemoExamples.GOOD_MONK_URL, R.string.sk_demo_normal_expected),
    DemoSample("caution", R.string.sk_demo_caution_label, DemoExamples.SUSPICIOUS_URL, R.string.sk_demo_caution_expected),
    DemoSample("local-warning", R.string.sk_demo_danger_label, DemoExamples.HIGH_RISK_MESSAGE, R.string.sk_demo_danger_expected),
    DemoSample("google-test", R.string.sk_demo_google_test, DemoExamples.GOOGLE_TEST_URL, R.string.sk_demo_google_expected)
)

@Composable
fun ScanScreen(graph: KavachApplication, debug: Boolean, initialMessage: String? = null,
               onInitialMessageConsumed: () -> Unit = {}, openAlert: (ScanResult) -> Unit) {
    var message by remember { mutableStateOf("") } // Never saved across activity/process recreation.
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<MessageOutcome?>(null) }
    var resumeRevision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeRevision++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun freshOutcome(): MessageOutcome? = outcome?.let { original ->
        original.copy(results = original.results.map { ReputationPolicy.expire(it, System.currentTimeMillis()) })
    }
    val displayedOutcome by produceState(freshOutcome(), outcome, resumeRevision) {
        while (true) {
            value = freshOutcome()
            val nextExpiry = value?.results?.filter { ReasonCode.GOOGLE_REPUTATION in it.verdict.reasonCodes }
                ?.minOfOrNull { it.timestamp + ReputationPolicy.MAX_AGE_MILLIS } ?: break
            delay((nextExpiry - System.currentTimeMillis()).coerceAtLeast(1L))
        }
    }
    var scanFailed by remember { mutableStateOf(false) }
    var selectedSample by remember { mutableStateOf<DemoSample?>(null) }
    var exampleActionFailed by remember { mutableStateOf(false) }
    val model by graph.modelState.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val online by graph.online.state.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val demoLab = debug && BuildConfig.DEBUG
    LaunchedEffect(initialMessage) {
        initialMessage?.let {
            message = it.take(16_384)
            selectedSample = null
            outcome = null
            onInitialMessageConsumed()
        }
    }
    fun scan(input: String) {
        message = ""
        keyboard?.hide()
        busy = true
        outcome = null
        scanFailed = false
        scope.launch {
            try {
                // simulate is the legacy parameter name for an explicit manual scan.
                // It never selects a fake scorer or affects incoming notifications.
                val result = graph.processMessage(input, if (demoLab) "demo-lab-real-scan" else "manual", simulate = true)
                outcome = result
                if (graph.canShowIntrusiveWarnings()) {
                    result.results.filter { it.verdict.level == Level.DANGEROUS }.maxByOrNull { it.verdict.score }?.let(openAlert)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep provider responses, URLs and credentials out of UI error text.
                scanFailed = true
            } finally { busy = false }
        }
    }
    Text(stringResource(if (demoLab) R.string.sk_demo_lab else R.string.sk_check_a_message), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(if (demoLab) R.string.sk_demo_walkthrough_detail else R.string.sk_online_manual_detail))
    Text(stringResource(when {
        !online.keyConfigured -> R.string.sk_online_needs_key
        !online.enabled -> R.string.sk_online_disabled
        else -> R.string.sk_online_enabled
    }), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("scanOnlineStatus"))
    Text(online.lastStatus, style = MaterialTheme.typography.bodySmall)
    if (demoLab) {
        Text(stringResource(R.string.sk_demo_inputs), style = MaterialTheme.typography.titleLarge)
        demoInputs.forEach { sample ->
            OutlinedButton(onClick = {
                message = sample.message
                selectedSample = sample
                outcome = null
                scanFailed = false
                exampleActionFailed = false
            }, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("demo-" + sample.id)) {
                Text(stringResource(sample.label))
            }
        }
        selectedSample?.let { sample ->
            Card(Modifier.fillMaxWidth().testTag("selectedDemoExample"), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.sk_demo_selected_input), style = MaterialTheme.typography.titleMedium)
                    Text(sample.message, modifier = Modifier.testTag("selectedDemoInput"))
                    Text(stringResource(sample.expected), modifier = Modifier.testTag("selectedDemoExpected"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText("root-security example", sample.message))
                        }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("copyDemoExample")) {
                            Text(stringResource(R.string.sk_demo_copy))
                        }
                        OutlinedButton(onClick = {
                            try {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                                    .setType("text/plain").putExtra(Intent.EXTRA_TEXT, sample.message),
                                    context.getString(R.string.sk_demo_share_title)))
                            } catch (_: ActivityNotFoundException) { exampleActionFailed = true }
                        }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("shareDemoExample")) {
                            Text(stringResource(R.string.sk_demo_share))
                        }
                    }
                    if (exampleActionFailed) Text(stringResource(R.string.sk_demo_share_unavailable))
                    Text(stringResource(R.string.sk_demo_select_then_check), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    OutlinedTextField(message, onValueChange = {
        message = it.take(16_384)
        if (selectedSample?.message != message) selectedSample = null
        outcome = null
        scanFailed = false
    }, modifier = Modifier.fillMaxWidth().testTag("messageInput"),
        minLines = 4, maxLines = 8, enabled = !busy, label = { Text(stringResource(R.string.sk_message_label)) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, autoCorrectEnabled = false))
    Button(onClick = { scan(message) }, enabled = message.isNotBlank() && !busy && model == LoadState.READY,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("scanButton")) {
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp)
        else Text(stringResource(R.string.sk_check_message))
    }
    if (scanFailed) Text(stringResource(R.string.sk_online_scan_failed), color = MaterialTheme.colorScheme.error)
    displayedOutcome?.let { result ->
        // A failed lookup must not be hidden by another link's no-match result.
        val highest = result.results.maxWithOrNull(compareBy<ScanResult> { it.verdict.level.ordinal }
            .thenBy { ReasonCode.REPUTATION_NOT_CHECKED in it.verdict.reasonCodes })
        val unchecked = result.results.any { ReasonCode.REPUTATION_NOT_CHECKED in it.verdict.reasonCodes }
        val noMatch = result.results.any { ReasonCode.REPUTATION_NO_MATCH in it.verdict.reasonCodes }
        Card(Modifier.fillMaxWidth().testTag("manualOutcome"), shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor =
                when {
                    !result.available -> MaterialTheme.colorScheme.surfaceVariant
                    highest?.verdict?.level == Level.DANGEROUS -> MaterialTheme.colorScheme.errorContainer
                    highest?.verdict?.level == Level.SUSPICIOUS -> Caution.copy(alpha = 0.14f)
                    unchecked -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.primaryContainer
                })) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(when {
                    !result.available -> R.string.sk_engine_unavailable
                    highest?.verdict?.level == Level.DANGEROUS -> R.string.sk_online_possible_threat
                    highest?.verdict?.level == Level.SUSPICIOUS -> R.string.sk_online_unconfirmed_pattern
                    unchecked -> R.string.sk_online_not_checked
                    noMatch -> R.string.sk_online_no_known_threat
                    else -> R.string.sk_level_safe
                }),
                    style = MaterialTheme.typography.titleLarge)
                if (result.available) {
                    if (highest?.verdict?.reasonCodes?.contains(ReasonCode.TEST_INPUT) == true) {
                        Text(stringResource(R.string.sk_demo_reserved_badge), style = MaterialTheme.typography.labelLarge)
                    }
                    highest?.takeIf { it.domain.isNotBlank() }?.let { Text(it.domain) }
                    highest?.verdict?.reasonCodes?.let(ReasonMessages::ordered)
                        ?.take(4)?.forEach { Text(ReasonMessages.get(it, settings.language)) }
                    if (unchecked && highest?.verdict?.reasonCodes?.contains(ReasonCode.REPUTATION_NOT_CHECKED) != true) {
                        Text(stringResource(R.string.sk_online_some_unchecked))
                    }
                    if (highest == null || highest.verdict.level == Level.SAFE) {
                        Text(stringResource(R.string.sk_demo_local_no_warning))
                    }
                    if (!unchecked && (highest == null || highest.verdict.level == Level.SAFE)) {
                        Text(stringResource(R.string.sk_safe_note))
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.sk_online_redirect_scope), style = MaterialTheme.typography.bodyMedium)
    if (demoLab) {
        Text(stringResource(R.string.sk_demo_notification_help), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.sk_demo_call_help), style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text(stringResource(R.string.sk_demo_preview_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.sk_demo_preview_detail), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = graph::previewTestAlert, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("preview-test-alert")) {
            Text(stringResource(R.string.sk_demo_preview_button))
        }
    }
    Text(stringResource(R.string.sk_online_local_history), style = MaterialTheme.typography.bodyMedium)
}

fun languageLabel(language: String) = when (language) { "hi" -> "हिन्दी"; "hi-Latn" -> "Hinglish"; "pa" -> "ਪੰਜਾਬੀ"; else -> "English" }
