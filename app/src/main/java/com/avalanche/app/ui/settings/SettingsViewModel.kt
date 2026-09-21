package com.avalanche.app.ui.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avalanche.app.data.AppSettings
import com.avalanche.app.data.Backup
import com.avalanche.app.data.BackupException
import com.avalanche.app.data.DebtEntity
import com.avalanche.app.data.DebtRepository
import com.avalanche.app.data.SettingsStore
import com.avalanche.app.notifications.Notifications
import java.io.IOException
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val repo: DebtRepository,
    private val store: SettingsStore,
    private val appContext: Context,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = store.settings

    /** Whether any debt exists, so importing a backup can say whether it would replace anything. Null until known. */
    val hasData: StateFlow<Boolean?> = repo.debts
        .map<List<DebtEntity>, Boolean?> { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun messageShown() {
        _message.value = null
    }

    fun setCurrency(code: String) = store.update { it.copy(currency = code) }

    fun setRemindersEnabled(enabled: Boolean) {
        store.update { it.copy(remindersEnabled = enabled) }
        if (enabled) {
            store.reminderScheduledAt = LocalDateTime.now()
            Notifications.scheduleReminderChecks(appContext)
        } else {
            Notifications.cancelReminderChecks(appContext)
        }
    }

    fun setReminderDay(day: Int) {
        store.update { it.copy(reminderDay = day.coerceIn(1, 28)) }
        store.reminderScheduledAt = LocalDateTime.now()
    }

    fun setCelebrationsEnabled(enabled: Boolean) = store.update { it.copy(celebrationsEnabled = enabled) }

    fun notificationsBlocked() {
        _message.value = "Notifications are turned off for Avalanche in system settings."
    }

    fun export(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val json = repo.exportJson()
                withContext(Dispatchers.IO) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        ?: throw IOException("Couldn't open the file")
                }
                "Backup saved."
            } catch (e: Exception) {
                "Couldn't save the backup."
            }
        }
    }

    fun import(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _message.value = try {
                val json = withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use(Backup::readBounded)
                        ?: throw IOException("Couldn't open the file")
                }
                repo.importJson(json)
                "Backup restored."
            } catch (e: BackupException) {
                "Not restored: ${e.message}"
            } catch (e: Exception) {
                "Couldn't read that file."
            }
        }
    }

    fun deleteEverything() {
        viewModelScope.launch {
            repo.deleteAll()
            _message.value = "All debts, payments and savings details deleted."
        }
    }
}
