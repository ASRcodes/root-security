package org.suraksha.kavach.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch
import org.suraksha.kavach.*
import org.suraksha.kavach.R
import org.suraksha.kavach.data.SettingsState

@Composable
fun SettingsScreen(graph: KavachApplication, settings: SettingsState, onLanguage: (String) -> Unit,
                   onSetup: () -> Unit, onDebug: () -> Unit, onGuardian: () -> Unit, onCode: () -> Unit,
                   overlayAllowed: Boolean, onOverlay: () -> Unit, onReset: () -> Unit = onSetup) {
    var confirmClear by remember { mutableStateOf(false) }
    var clearFailed by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Text(stringResource(R.string.sk_settings), style = MaterialTheme.typography.headlineLarge)
    Text(stringResource(R.string.sk_language), style = MaterialTheme.typography.titleLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("en", "hi", "pa").forEach { language ->
            FilterChip(selected = settings.language == language, onClick = { onLanguage(language) },
                label = { Text(languageLabel(language)) }, modifier = Modifier.heightIn(min = 56.dp))
        }
    }
    SettingToggle(stringResource(R.string.sk_protection_switch), stringResource(R.string.sk_protection_switch_detail),
        settings.protectionEnabled, graph.settings::setProtection)
    OnlineProtectionSettings(graph)
    CallShieldSettings(graph)
    OutlinedButton(onClick = onSetup, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(stringResource(R.string.sk_permission_settings)) }
    SettingToggle(stringResource(R.string.sk_voice_warnings), stringResource(R.string.sk_voice_detail), settings.voiceEnabled,
        { graph.settings.setVoice(it); if (!it) graph.audio.stop() })
    OutlinedButton(onClick = { graph.previewWarning() }, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(stringResource(R.string.sk_hear_sample)) }
    OutlinedButton(onClick = onGuardian, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(stringResource(R.string.sk_family_setup)) }
    OutlinedButton(onClick = onCode, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(stringResource(R.string.sk_family_code_title)) }
    SettingToggle(stringResource(R.string.sk_overlay_title), stringResource(R.string.sk_overlay_detail), overlayAllowed,
        { if (it) onOverlay() else { graph.settings.setOverlay(false); graph.overlays.dismiss(); org.suraksha.kavach.calls.CallAlerts.dismissBanner(graph) } })
    Text(stringResource(R.string.sk_auto_delete), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.sk_auto_delete_detail), style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(1, 7, 30).forEach { days ->
            FilterChip(selected = settings.retentionDays == days,
                onClick = { graph.settings.setRetention(days); scope.launch { graph.pruneHistory() } },
                label = { Text(stringResource(when (days) { 1 -> R.string.sk_one_day; 7 -> R.string.sk_seven_days; else -> R.string.sk_thirty_days })) },
                modifier = Modifier.heightIn(min = 56.dp))
        }
    }
    if (BuildConfig.DEBUG) OutlinedButton(onClick = onDebug, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) {
        Text(stringResource(R.string.sk_demo_lab))
    }
    Text(stringResource(R.string.sk_your_data), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.sk_online_local_history), style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = { confirmClear = true }, enabled = !clearing, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Text(stringResource(R.string.sk_clear_all)) }
    if (clearFailed) Text(stringResource(R.string.sk_clear_failed), color = Danger)
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.sk_clear_confirm_title)) },
        text = { Text(stringResource(R.string.sk_clear_confirm_detail)) },
        confirmButton = { TextButton(onClick = {
            confirmClear = false
            clearing = true
            scope.launch {
                clearFailed = !graph.clearProtectionData()
                clearing = false
                if (!clearFailed) onReset()
            }
        }) { Text(stringResource(R.string.sk_clear_all), color = Danger) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.sk_keep_data)) } })
}

@Composable
fun SettingToggle(title: String, detail: String, checked: Boolean, change: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodyMedium)
            }
            Switch(checked = checked, onCheckedChange = change, modifier = Modifier.semantics { contentDescription = title })
        }
    }
}
