package app.copyphotos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.copyphotos.R
import app.copyphotos.core.DestinationConfig
import app.copyphotos.core.ProbeResult
import app.copyphotos.core.SmbAuth
import app.copyphotos.core.VerifyMode

/**
 * The Destination screen: the SMB server, sign-in, folder, copy and automation settings, and
 * testing the connection.
 * @param vm the screen's controller.
 * @param modifier the screen's modifier.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationScreen(vm: DestinationViewModel, modifier: Modifier) {
    val config by vm.config.collectAsState()
    var browsing by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.destination_title)) })
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ServerSection(vm, config)
            AuthenticationSection(vm, config)
            LocationSection(vm, config, onBrowse = { browsing = true })
            Section(stringResource(R.string.section_copying))
            Text(stringResource(R.string.verify_each_copy), style = MaterialTheme.typography.labelLarge)
            Choice(VerifyMode.entries, config.verifyMode, { it.label() }) { v -> vm.update { it.copy(verifyMode = v) } }
            AutomationSection(vm, config)
            HorizontalDivider()
            TestSection(vm, config)
            Spacer(Modifier.size(24.dp))
        }
    }

    if (browsing) {
        LocationBrowserDialog(vm, onClose = { browsing = false })
    }
}

/**
 * The server's address and port.
 * @param vm the screen's controller.
 * @param config the settings.
 */
@Composable
private fun ServerSection(vm: DestinationViewModel, config: DestinationConfig) {
    var portText by remember { mutableStateOf(config.port?.toString() ?: "") }
    Section(stringResource(R.string.section_smb_server))
    NormalizedField(config.host, DestinationConfig::normalizeHost, stringResource(R.string.field_host), KeyboardType.Uri) { v ->
        vm.update { it.copy(host = v) }
    }
    ConfigField(portText, stringResource(R.string.field_port), KeyboardType.Number) { v ->
        portText = v.filter(Char::isDigit)
        vm.update { it.copy(port = portText.toIntOrNull()) }
    }
}

/**
 * How to sign in, and the username, password and domain.
 * @param vm the screen's controller.
 * @param config the settings.
 */
@Composable
private fun AuthenticationSection(vm: DestinationViewModel, config: DestinationConfig) {
    val hasPassword by vm.hasPassword.collectAsState()
    val passwordNotSaved by vm.passwordNotSaved.collectAsState()
    // Only what is typed here; the saved password is never loaded back into the field.
    var passwordText by remember { mutableStateOf("") }
    Section(stringResource(R.string.section_authentication))
    Choice(SmbAuth.entries, config.smbAuth, { it.label() }) { a -> vm.update { it.copy(smbAuth = a) } }
    if (config.smbAuth == SmbAuth.USER_PASSWORD) {
        ConfigField(config.username, stringResource(R.string.field_username)) { v -> vm.update { it.copy(username = v) } }
        ConfigField(
            passwordText,
            stringResource(if (hasPassword) R.string.field_password_saved else R.string.field_password),
            KeyboardType.Password,
            PasswordVisualTransformation(),
        ) { v -> passwordText = v; vm.setPassword(v) }
        if (passwordNotSaved) {
            Text(
                stringResource(R.string.error_password_not_saved),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ConfigField(config.domain, stringResource(R.string.field_domain)) { v -> vm.update { it.copy(domain = v) } }
    }
    Hint(stringResource(R.string.hint_password))
}

/**
 * The share and folder: chosen in the browser, or typed in.
 * @param vm the screen's controller.
 * @param config the settings.
 * @param onBrowse opens the location browser.
 */
@Composable
private fun LocationSection(vm: DestinationViewModel, config: DestinationConfig, onBrowse: () -> Unit) {
    var manualEntry by remember { mutableStateOf(false) }
    Section(stringResource(R.string.section_location))
    ListItem(
        headlineContent = {
            Text(when {
                config.share.isNotBlank() -> config.location.name
                config.hasServerAndSignIn -> stringResource(R.string.location_choose)
                else -> stringResource(R.string.location_needs_server)
            })
        },
        supportingContent = if (config.share.isNotBlank()) {
            { Text(config.displayUrl, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        } else null,
        leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
        modifier = Modifier.fillMaxWidth().clickable(enabled = config.hasServerAndSignIn, onClick = onBrowse),
    )
    TextButton(onClick = { manualEntry = !manualEntry }) {
        Text(stringResource(if (manualEntry) R.string.manual_entry_hide else R.string.manual_entry_show))
    }
    if (manualEntry) {
        NormalizedField(config.share, DestinationConfig::normalizeShare, stringResource(R.string.field_share)) { v ->
            vm.update { it.copy(share = v) }
        }
        ConfigField(config.path, stringResource(R.string.field_folder)) { v -> vm.update { it.copy(path = v) } }
    }
    Hint(stringResource(R.string.hint_location))
}

/**
 * Automatic copying and its conditions.
 * @param vm the screen's controller.
 * @param config the settings.
 */
@Composable
private fun AutomationSection(vm: DestinationViewModel, config: DestinationConfig) {
    Section(stringResource(R.string.section_automation))
    SwitchRow(stringResource(R.string.switch_auto_copy), config.autoCopy) { v -> vm.update { it.copy(autoCopy = v) } }
    if (config.autoCopy) {
        SwitchRow(stringResource(R.string.switch_wifi_only), config.autoCopyWifiOnly) { v -> vm.update { it.copy(autoCopyWifiOnly = v) } }
        SwitchRow(stringResource(R.string.switch_charging), config.autoCopyWhileCharging) { v -> vm.update { it.copy(autoCopyWhileCharging = v) } }
    }
    Hint(stringResource(R.string.hint_automation))
}

/**
 * Tests the connection, and shows the result.
 * @param vm the screen's controller.
 * @param config the settings.
 */
@Composable
private fun TestSection(vm: DestinationViewModel, config: DestinationConfig) {
    val testing by vm.testing.collectAsState()
    val testResult by vm.testResult.collectAsState()
    Button(
        onClick = vm::testConnection,
        enabled = !testing && config.isComplete,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (testing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.action_test_connection))
    }
    testResult?.let { result ->
        val ok = result is ProbeResult.Succeeded
        val msg = when (result) {
            ProbeResult.Succeeded -> stringResource(R.string.probe_succeeded, config.displayUrl)
            is ProbeResult.Failed -> result.reason.text()
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Default.CheckCircle else Icons.Default.Error, null,
                tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.size(8.dp))
            Text(msg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A one-line settings text field. */
@Composable
private fun ConfigField(
    value: String,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value, onValueChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = visualTransformation, keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

/**
 * Shows exactly what is typed but reports it normalized, so the stored value is always clean
 * without fighting the cursor (e.g. a space typed before the next word).
 */
@Composable
private fun NormalizedField(
    value: String,
    normalize: (String) -> String,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    // Set elsewhere (e.g. chosen in the browser): show the new value.
    LaunchedEffect(value) { if (normalize(text) != value) text = value }
    ConfigField(text, label, keyboardType) {
        text = it
        onValueChange(normalize(it))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column {
        options.forEach { o ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = o == selected, role = Role.RadioButton) { onSelect(o) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(o == selected, null)
                Spacer(Modifier.size(8.dp))
                Text(label(o))
            }
        }
    }
}
