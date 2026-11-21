package org.suraksha.kavach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.suraksha.core.ScanResult
import org.suraksha.kavach.*
import org.suraksha.kavach.R
import org.suraksha.kavach.data.HistoryEntry

@Composable
fun HomeScreen(graph: KavachApplication, permissions: PermissionStatus, history: List<HistoryEntry>,
               navigate: (Page) -> Unit, openAlert: (ScanResult) -> Unit) {
    val model by graph.modelState.collectAsStateWithLifecycle()
    val storage by graph.storageState.collectAsStateWithLifecycle()
    val settings by graph.settings.state.collectAsStateWithLifecycle()
    val online by graph.online.state.collectAsStateWithLifecycle()
    val active = model == LoadState.READY && permissions.listener && permissions.connected &&
        permissions.notifications && settings.protectionEnabled
    Text(stringResource(R.string.sk_welcome), style = MaterialTheme.typography.headlineLarge)
    Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(
        containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(when {
                model == LoadState.LOADING -> R.string.sk_loading_protection
                model == LoadState.FAILED -> R.string.sk_engine_unavailable
                !settings.protectionEnabled -> R.string.sk_protection_paused
                active -> R.string.sk_protection_on
                else -> R.string.sk_setup_title
            }), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(if (active) R.string.sk_protection_detail else R.string.sk_manual_still_works))
            Text(stringResource(when {
                !online.keyConfigured -> R.string.sk_online_needs_key
                !online.enabled -> R.string.sk_online_disabled
                else -> R.string.sk_online_enabled
            }), style = MaterialTheme.typography.bodyMedium)
            if (model == LoadState.FAILED) Button(onClick = graph::retryModels) { Text(stringResource(R.string.sk_retry)) }
            else if (!settings.protectionEnabled) Button(onClick = { graph.settings.setProtection(true) }) {
                Text(stringResource(R.string.sk_resume_protection))
            }
            else if (!active && model == LoadState.READY && settings.protectionEnabled)
                Button(onClick = { navigate(Page.SETUP) }) { Text(stringResource(R.string.sk_finish_setup)) }
        }
    }
    OutlinedButton(onClick = { navigate(Page.CALL) }, modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger)) { Text(stringResource(R.string.sk_call_button)) }
    Button(onClick = { navigate(Page.CHECK) }, modifier = Modifier.fillMaxWidth().heightIn(min = 68.dp)) {
        Text(stringResource(R.string.sk_check_a_message))
    }
    OutlinedButton(onClick = { navigate(Page.FILES) }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.sk_check_file)) }
    if (BuildConfig.DEBUG) OutlinedButton(onClick = { navigate(Page.DEBUG) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(stringResource(R.string.sk_demo_lab)) }
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.sk_family_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.sk_family_home_detail), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { navigate(Page.GUARDIAN) }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.sk_family_setup))
            }
        }
    }
    if (storage == LoadState.FAILED) Text(stringResource(R.string.sk_history_unavailable), color = MaterialTheme.colorScheme.error)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.sk_recent_activity), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { navigate(Page.ACTIVITY) }) { Text(stringResource(R.string.sk_see_all)) }
    }
    if (history.isEmpty()) EmptyHistory() else history.take(3).forEach { ActivityCard(it, openAlert) }
    Text(stringResource(R.string.sk_privacy_note), style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun PermissionCard(title: String, allowed: Boolean, detail: String, onClick: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(if (allowed) R.string.sk_status_ready else R.string.sk_status_needs_attention),
                color = if (allowed) Forest else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 52.dp)) {
                Text(stringResource(if (allowed) R.string.sk_review_settings else R.string.sk_open_settings))
            }
        }
    }
}
