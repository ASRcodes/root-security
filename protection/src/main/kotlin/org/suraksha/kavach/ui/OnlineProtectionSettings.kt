package org.suraksha.kavach.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.suraksha.kavach.KavachApplication
import org.suraksha.kavach.R

@Composable
fun OnlineProtectionSettings(graph: KavachApplication) {
    val online by graph.online.state.collectAsStateWithLifecycle()
    // Never use rememberSaveable or copy the stored key back into a text field.
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    fun change(action: suspend () -> Boolean) {
        busy = true
        failed = false
        saved = false
        scope.launch {
            try {
                val success = action()
                failed = !success
                saved = success
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                key = ""
                busy = false
            }
        }
    }

    HorizontalDivider()
    Text(stringResource(R.string.sk_online_title), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.sk_online_description))
    Text(stringResource(R.string.sk_online_disclosure), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.sk_online_limitations), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.sk_online_noncommercial), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(when {
        !online.keyConfigured -> R.string.sk_online_needs_key
        !online.enabled -> R.string.sk_online_disabled
        else -> R.string.sk_online_enabled
    }), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("onlineConfigurationStatus"))
    Text(online.lastStatus, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("onlineLastStatus"))

    OutlinedTextField(
        value = key,
        onValueChange = { key = it.take(256); saved = false; failed = false },
        label = { Text(stringResource(R.string.sk_online_key_label)) },
        supportingText = { Text(stringResource(R.string.sk_online_key_hint)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        singleLine = true,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag("onlineApiKeyInput")
    )
    Button(onClick = {
        val suppliedKey = key.trim()
        key = ""
        change { graph.online.configure(suppliedKey) }
    }, enabled = !busy && key.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("saveOnlineApiKey")) {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.onPrimary)
        else Text(stringResource(R.string.sk_online_save_enable))
    }
    if (online.keyConfigured) {
        OutlinedButton(onClick = {
            if (online.enabled) change { graph.online.disable(); true }
            else change { graph.online.enable() }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("toggleOnlineChecks")) {
            Text(stringResource(if (online.enabled) R.string.sk_online_disable else R.string.sk_online_enable_saved))
        }
        OutlinedButton(onClick = { change { graph.online.clearKey(); true } }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("clearOnlineApiKey")) {
            Text(stringResource(R.string.sk_online_remove_key))
        }
    }
    if (saved) Text(stringResource(R.string.sk_online_setting_saved), style = MaterialTheme.typography.bodyMedium)
    if (failed) Text(stringResource(R.string.sk_online_configuration_failed), color = MaterialTheme.colorScheme.error)
    TextButton(onClick = {
        try { uriHandler.openUri("https://developers.google.com/safe-browsing/v4/get-started") }
        catch (_: Exception) { failed = true }
    }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("onlineSetupHelp")) {
        Text(stringResource(R.string.sk_online_setup_help))
    }
    HorizontalDivider()
}
