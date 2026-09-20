package com.avalanche.app.ui.payments

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avalanche.app.data.debtType
import com.avalanche.app.ui.components.DateField
import com.avalanche.app.ui.components.DecimalField
import com.avalanche.app.ui.components.EmptyState
import com.avalanche.app.ui.components.KindChip
import com.avalanche.app.ui.components.LoadingBox
import com.avalanche.app.ui.components.ScreenScaffold
import com.avalanche.app.ui.components.money
import com.avalanche.app.ui.appViewModel

@Composable
fun LogPaymentScreen(prefillDebtId: Long?, prefillAllocations: Map<Long, Double>, onBack: () -> Unit) {
    val vm: LogPaymentViewModel = appViewModel { LogPaymentViewModel(it.repository, prefillDebtId, prefillAllocations) }
    val debts by vm.debts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val active = debts

    ScreenScaffold(title = "Log a payment", onBack = onBack) { padding ->
        when {
            active == null -> LoadingBox(padding)
            active.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    icon = Icons.Filled.CheckCircle,
                    title = "Nothing to pay",
                    body = "Every debt is paid off, or none have been added yet.",
                    actionLabel = "Back",
                    onAction = onBack,
                )
            }
            else -> Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { DateField(vm.date, { vm.date = it }, "Payment date") }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("Split across your debts", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { vm.fillMinimums(active) }) { Text("Fill minimums") }
                        }
                    }
                    items(active, key = { it.id }) { debt ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(debt.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    KindChip(debt.debtType)
                                    Text(
                                        "Owed ${money(debt.currentBalance)} · min ${money(debt.minPayment)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                DecimalField(
                                    value = vm.amounts[debt.id].orEmpty(),
                                    onValueChange = { vm.setAmount(debt.id, it) },
                                    label = "Amount",
                                    modifier = Modifier.width(150.dp),
                                    error = vm.rowError(debt),
                                )
                            }
                            TextButton(onClick = { vm.payOffInFull(debt) }) { Text("Pay off in full (${money(vm.payoffAmount(debt))})") }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.formError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(money(vm.total()), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = {
                                    vm.save(active) { result ->
                                        val msg = when {
                                            result.paidOffNames.isNotEmpty() -> "Paid off: ${result.paidOffNames.joinToString()} 🎉"
                                            else -> "Logged ${result.paymentCount} payment${if (result.paymentCount == 1) "" else "s"}"
                                        }
                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        onBack()
                                    }
                                },
                                enabled = !vm.saving,
                            ) { Text("Log payment") }
                        }
                    }
                }
            }
        }
    }
}
