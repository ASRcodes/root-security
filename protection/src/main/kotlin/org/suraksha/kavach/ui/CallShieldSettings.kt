package org.suraksha.kavach.ui

import android.app.role.RoleManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.suraksha.kavach.KavachApplication
import org.suraksha.kavach.R
import org.suraksha.kavach.calls.CallAlerts
import org.suraksha.kavach.calls.CallShieldPlatform

/** Pre-answer scam call warnings. Turning it on asks Android for the call-screening role. */
@Composable
fun CallShieldSettings(graph: KavachApplication) {
    val context = LocalContext.current
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    var held by remember { mutableStateOf(CallShieldPlatform.roleHeld(context)) }
    var manage by rememberSaveable { mutableStateOf(false) }
    var preview by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context, graph) {
        fun refreshRole() {
            held = CallShieldPlatform.roleHeld(context)
            if (!held) {
                if (graph.settings.state.value.callShieldEnabled) graph.settings.setCallShield(false)
                CallAlerts.dismissAll(graph)
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refreshRole() }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) refreshRole()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        held = CallShieldPlatform.roleHeld(context)
        graph.settings.setCallShield(held)
        if (!held) CallAlerts.dismissAll(graph)
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.sk_call_shield_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sk_call_shield_detail), style = MaterialTheme.typography.bodyMedium)
            val roles = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java) else null
            val available = Build.VERSION.SDK_INT >= 29 && roles?.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) == true
            when {
                !available ->
                    Text(stringResource(R.string.sk_call_shield_unavailable), style = MaterialTheme.typography.bodyMedium)
                !held -> Button(onClick = {
                    if (Build.VERSION.SDK_INT >= 29 && roles != null)
                        request.launch(roles.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("callShieldEnable")) {
                    Text(stringResource(R.string.sk_call_shield_enable))
                }
                else -> SettingToggle(stringResource(R.string.sk_call_shield_switch),
                    stringResource(if (settings.callShieldEnabled) R.string.sk_call_shield_on else R.string.sk_call_shield_paused),
                    settings.callShieldEnabled, { enabled ->
                        graph.settings.setCallShield(enabled)
                        if (!enabled) CallAlerts.dismissAll(graph)
                    })
            }
            if (settings.reportedNumbers.isNotEmpty()) Text(
                stringResource(R.string.sk_call_reported_count, settings.reportedNumbers.size),
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sk_call_voice_limit), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.sk_call_platform_limit), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { manage = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .testTag("callShieldManage")) { Text(stringResource(R.string.sk_call_manage)) }
            TextButton(onClick = { preview = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .testTag("callShieldPreview")) { Text(stringResource(R.string.sk_call_preview)) }
        }
    }
    if (manage) ReportedCallersDialog(graph, onDismiss = { manage = false })
    if (preview) AlertDialog(onDismissRequest = { preview = false },
        title = { Text(stringResource(R.string.sk_call_preview_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sk_call_preview_detail))
            Text(stringResource(R.string.sk_call_title_caution), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sk_call_reason_unknown))
            Text(stringResource(R.string.sk_call_voice_clone_advice))
        } }, confirmButton = { TextButton(onClick = { preview = false }) { Text(stringResource(R.string.sk_call_close)) } })
}

@Composable
private fun ReportedCallersDialog(graph: KavachApplication, onDismiss: () -> Unit) {
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    var number by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sk_call_manage)) },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.sk_call_reports_private))
            OutlinedTextField(value = number, onValueChange = { number = it; invalid = false },
                label = { Text(stringResource(R.string.sk_call_number_label)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), isError = invalid,
                modifier = Modifier.fillMaxWidth().testTag("callShieldReportInput"))
            if (invalid) Text(stringResource(R.string.sk_call_report_invalid_or_full), color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                invalid = !graph.settings.reportNumber(number)
                if (!invalid) number = ""
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("callShieldAddReport")) {
                Text(stringResource(R.string.sk_call_add_report))
            }
            Text(stringResource(R.string.sk_call_reported_count, settings.reportedNumbers.size))
            if (settings.reportedNumbers.isEmpty()) Text(stringResource(R.string.sk_call_reports_empty))
            else Column {
                settings.reportedNumbers.sorted().forEach { reported ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(reported, Modifier.weight(1f))
                        TextButton(onClick = { graph.settings.removeReportedNumber(reported); CallAlerts.dismissAll(graph) }) {
                            Text(stringResource(R.string.sk_call_remove))
                        }
                    }
                }
            }
            if (settings.reportedNumbers.isNotEmpty()) TextButton(onClick = { confirmClear = true }) {
                Text(stringResource(R.string.sk_call_clear_reports))
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sk_call_close)) } })
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.sk_call_clear_reports)) },
        text = { Text(stringResource(R.string.sk_call_clear_reports_detail)) },
        confirmButton = { TextButton(onClick = {
            graph.settings.clearReportedNumbers(); CallAlerts.dismissAll(graph); confirmClear = false
        }) { Text(stringResource(R.string.sk_call_clear_reports)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.sk_call_keep_reports)) } })
}
