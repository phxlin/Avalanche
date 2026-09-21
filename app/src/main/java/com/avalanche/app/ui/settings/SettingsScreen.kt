package com.avalanche.app.ui.settings

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avalanche.app.data.SettingsStore
import com.avalanche.app.notifications.Notifications
import com.avalanche.app.ui.components.ConfirmDialog
import com.avalanche.app.ui.components.DELETE_CONFIRM_PHRASE
import com.avalanche.app.ui.components.ScreenScaffold
import com.avalanche.app.ui.components.SectionCard
import com.avalanche.app.ui.appViewModel
import java.time.LocalDate
import java.util.Currency

private val CURRENCY_CODES = listOf(
    "USD", "EUR", "GBP", "CAD", "AUD", "NZD", "JPY", "CHF", "SEK", "NOK", "DKK", "INR", "CNY", "HKD", "SGD", "KRW",
    "MXN", "BRL", "ARS", "CLP", "COP", "PEN", "ZAR", "NGN", "KES", "EGP", "PLN", "CZK", "HUF", "TRY", "AED", "SAR",
    "ILS", "PHP", "THB", "MYR", "IDR", "VND", "PKR", "BDT",
)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val vm: SettingsViewModel = appViewModel { SettingsViewModel(it.repository, it.settings, context.applicationContext) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val hasData by vm.hasData.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var showCurrency by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var pendingImport by rememberSaveable { mutableStateOf<Uri?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(context.contentResolver, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    // Notification permission is only needed on Android 13+; older versions post freely.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterPermission?.invoke() else vm.notificationsBlocked()
        afterPermission = null
    }
    fun withNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !Notifications.canPost(context)) {
            afterPermission = action
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    ScreenScaffold(title = "Settings", snackbarHostState = snackbar) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "General") {
                SettingRow(
                    title = "Currency",
                    subtitle = currencyLabel(settings.currency),
                    modifier = Modifier.clickable { showCurrency = true },
                )
            }

            SectionCard(title = "Notifications", subtitle = "Optional. Everything is scheduled on this device.") {
                SettingRow(
                    title = "Monthly reminder",
                    subtitle = "A nudge to log the payments you made",
                    trailing = {
                        Switch(
                            checked = settings.remindersEnabled,
                            onCheckedChange = { on -> if (on) withNotificationPermission { vm.setRemindersEnabled(true) } else vm.setRemindersEnabled(false) },
                        )
                    },
                )
                if (settings.remindersEnabled) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Remind me on day", style = MaterialTheme.typography.bodyLarge)
                            Text("Around 9:00 each month", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.setReminderDay(settings.reminderDay - 1) }, enabled = settings.reminderDay > 1) {
                            Icon(Icons.Filled.Remove, contentDescription = "Earlier day")
                        }
                        Text("${settings.reminderDay}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { vm.setReminderDay(settings.reminderDay + 1) }, enabled = settings.reminderDay < 28) {
                            Icon(Icons.Filled.Add, contentDescription = "Later day")
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "Celebrate paid-off debts",
                    subtitle = "A small notification when a balance reaches zero",
                    trailing = {
                        Switch(
                            checked = settings.celebrationsEnabled,
                            onCheckedChange = { on -> if (on) withNotificationPermission { vm.setCelebrationsEnabled(true) } else vm.setCelebrationsEnabled(false) },
                        )
                    },
                )
            }

            SectionCard(title = "Your data", subtitle = "Stored only on this device. Export a backup so a lost phone doesn't mean lost history.") {
                SettingRow(
                    title = "Export backup",
                    subtitle = "Save a JSON file with all debts and payments",
                    modifier = Modifier.clickable { exportLauncher.launch("avalanche-backup-${LocalDate.now()}.json") },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "Import backup",
                    subtitle = "Replace everything here with a backup file",
                    modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingRow(
                    title = "Delete all data",
                    subtitle = "Remove every debt and payment from this device",
                    titleColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable { showDelete = true },
                )
            }

            SectionCard(title = "About") {
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
                Text("Avalanche $version", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "Fully offline. No account, no network access, no analytics. Projections are estimates and not financial advice.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showCurrency) {
        val options = remember(settings.currency) { (listOf(settings.currency, SettingsStore.deviceCurrency()) + CURRENCY_CODES).distinct() }
        AlertDialog(
            onDismissRequest = { showCurrency = false },
            title = { Text("Currency") },
            text = {
                LazyColumn {
                    items(options, key = { it }) { code ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                vm.setCurrency(code)
                                showCurrency = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = code == settings.currency, onClick = null)
                            Text(currencyLabel(code), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCurrency = false }) { Text("Close") } },
        )
    }
    if (showDelete) {
        ConfirmDialog(
            title = "Delete all data?",
            text = "Every debt and payment on this device will be permanently removed. Export a backup first if you might want it back.",
            confirmLabel = "Delete everything",
            destructive = true,
            confirmPhrase = DELETE_CONFIRM_PHRASE,
            onConfirm = {
                showDelete = false
                vm.deleteEverything()
            },
            onDismiss = { showDelete = false },
        )
    }
    pendingImport?.let { uri ->
        val prompt = importPrompt(hasData)
        ConfirmDialog(
            title = prompt.title,
            text = prompt.text,
            confirmLabel = prompt.confirmLabel,
            destructive = prompt.destructive,
            onConfirm = {
                pendingImport = null
                vm.import(context.contentResolver, uri)
            },
            onDismiss = { pendingImport = null },
        )
    }
}

private fun currencyLabel(code: String): String =
    runCatching { "$code · ${Currency.getInstance(code).displayName}" }.getOrDefault(code)

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = { Text(subtitle) },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = modifier,
    )
}
