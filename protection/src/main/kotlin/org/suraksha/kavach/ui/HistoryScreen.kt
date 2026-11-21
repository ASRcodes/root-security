package org.suraksha.kavach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.suraksha.core.Level
import org.suraksha.core.ReasonCode
import org.suraksha.core.ReputationPolicy
import org.suraksha.core.ScanResult
import kotlinx.coroutines.delay
import org.suraksha.kavach.BrandHeader
import org.suraksha.kavach.R
import org.suraksha.kavach.data.HistoryEntry
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(entries: List<HistoryEntry>, modifier: Modifier, open: (ScanResult) -> Unit) {
    LazyColumn(modifier, contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { BrandHeader() }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sk_activity), style = MaterialTheme.typography.headlineLarge)
                Text(stringResource(R.string.sk_history_detail))
            }
        }
        if (entries.isEmpty()) item { EmptyHistory() }
        items(entries, key = { it.domain + ":" + it.timestamp }) { ActivityCard(it, open) }
    }
}

@Composable
fun EmptyHistory() {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.sk_history_empty), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.sk_history_empty_detail), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ActivityCard(entry: HistoryEntry, open: (ScanResult) -> Unit) {
    val original = remember(entry) { entry.toResult() }
    val result by produceState(ReputationPolicy.expire(original, System.currentTimeMillis()), original) {
        value = ReputationPolicy.expire(original, System.currentTimeMillis())
        if (ReasonCode.GOOGLE_REPUTATION in value.verdict.reasonCodes) {
            val remaining = ReputationPolicy.MAX_AGE_MILLIS - (System.currentTimeMillis() - original.timestamp)
            delay(remaining.coerceAtLeast(0L))
            value = ReputationPolicy.expire(original, System.currentTimeMillis())
        }
    }
    val reasons = result.verdict.reasonCodes
    val historicalThreat = ReasonCode.GOOGLE_REPUTATION in original.verdict.reasonCodes
    val unchecked = ReasonCode.REPUTATION_NOT_CHECKED in reasons
    val color = when {
        unchecked && result.verdict.level == Level.SAFE -> MaterialTheme.colorScheme.onSurfaceVariant
        result.verdict.level == Level.SAFE -> Forest
        result.verdict.level == Level.SUSPICIOUS -> Caution
        else -> Danger
    }
    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, LocalConfiguration.current.locales[0]).format(Date(entry.timestamp))
    // The activity checks freshness again when this original historical record is opened.
    Card(onClick = { open(original) },
        shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White, disabledContainerColor = Color.White,
            disabledContentColor = Ink)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(when {
                ReasonCode.DEMO_ONLY in reasons -> R.string.sk_simulation_title
                ReasonCode.TEST_INPUT in reasons -> when (result.verdict.level) {
                    Level.DANGEROUS -> R.string.sk_demo_danger_title
                    Level.SUSPICIOUS -> R.string.sk_demo_caution_title
                    Level.SAFE -> R.string.sk_demo_normal_title
                }
                unchecked && result.verdict.level == Level.SAFE -> R.string.sk_history_not_checked
                historicalThreat -> R.string.sk_history_threat_at_scan
                result.verdict.level == Level.DANGEROUS -> R.string.sk_online_possible_threat
                result.verdict.level == Level.SUSPICIOUS -> R.string.sk_online_unconfirmed_pattern
                ReasonCode.REPUTATION_NO_MATCH in reasons -> R.string.sk_history_no_match
                else -> levelLabel(result.verdict.level)
            }), color = color, style = MaterialTheme.typography.titleMedium)
            Text(entry.domain.ifBlank { stringResource(if (ReasonCode.FILE_SCAN in result.verdict.reasonCodes) R.string.sk_file_history else R.string.sk_message_warning) }, style = MaterialTheme.typography.bodyLarge)
            Text(time, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (historicalThreat && unchecked) Text(stringResource(R.string.sk_history_expired_threat), style = MaterialTheme.typography.bodyMedium)
            if (historicalThreat && !unchecked) Text(stringResource(R.string.sk_google_history_attribution), style = MaterialTheme.typography.bodyMedium)
            if (ReasonCode.REPUTATION_NO_MATCH in reasons) Text(stringResource(R.string.sk_no_match_guarantee), style = MaterialTheme.typography.bodyMedium)
            if (ReasonCode.DEMO_ONLY in reasons)
                Text(stringResource(R.string.sk_demo_badge), style = MaterialTheme.typography.bodyMedium)
            if (ReasonCode.PROVIDER_TEST in reasons)
                Text(stringResource(R.string.sk_google_test_badge), style = MaterialTheme.typography.bodyMedium)
            if (ReasonCode.TEST_INPUT in reasons)
                Text(stringResource(R.string.sk_demo_reserved_badge), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun levelLabel(level: Level) = when (level) {
    Level.SAFE -> R.string.sk_level_safe
    Level.SUSPICIOUS -> R.string.sk_level_suspicious
    Level.DANGEROUS -> R.string.sk_level_dangerous
}
