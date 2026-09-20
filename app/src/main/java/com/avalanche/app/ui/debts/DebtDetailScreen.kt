package com.avalanche.app.ui.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avalanche.app.data.PaymentEntity
import com.avalanche.app.data.debtType
import com.avalanche.app.data.isAdjustment
import com.avalanche.app.data.isPaidOff
import com.avalanche.app.data.progress
import com.avalanche.app.ui.components.BalanceHistoryChart
import com.avalanche.app.ui.components.ConfirmDialog
import com.avalanche.app.ui.components.KindChip
import com.avalanche.app.ui.components.ProgressBar
import com.avalanche.app.ui.components.LoadingBox
import com.avalanche.app.ui.components.ScreenScaffold
import com.avalanche.app.ui.components.SectionCard
import com.avalanche.app.ui.components.StatTile
import com.avalanche.app.ui.components.accent
import com.avalanche.app.ui.components.money
import com.avalanche.app.ui.appViewModel
import com.avalanche.app.util.formatApr
import com.avalanche.app.util.formatDuration
import com.avalanche.app.util.formatEpochDay
import com.avalanche.app.util.formatMonth
import com.avalanche.app.util.formatPercent

@Composable
fun DebtDetailScreen(debtId: Long, onBack: () -> Unit, onEdit: () -> Unit, onLogPayment: () -> Unit) {
    val vm: DebtDetailViewModel = appViewModel(key = "detail-$debtId") { DebtDetailViewModel(it.repository, it.settings, debtId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmUndo by rememberSaveable { mutableStateOf(false) }

    // The debt disappears from the database when deleted (or if the screen is opened for a stale id).
    LaunchedEffect(state.loaded, state.debt) { if (state.loaded && state.debt == null) onBack() }

    val debt = state.debt
    ScreenScaffold(
        title = debt?.name ?: "Debt",
        onBack = onBack,
        actions = {
            IconButton(onClick = onEdit, enabled = debt != null) { Icon(Icons.Filled.Edit, contentDescription = "Edit debt") }
            IconButton(onClick = { confirmDelete = true }, enabled = debt != null) { Icon(Icons.Filled.Delete, contentDescription = "Delete debt") }
        },
    ) { padding ->
        if (debt == null) {
            LoadingBox(padding)
            return@ScreenScaffold
        }
        val type = debt.debtType
        val paid = debt.isPaidOff
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    KindChip(type)
                    Text(
                        if (paid) "Paid off" else money(debt.currentBalance),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (paid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    ProgressBar(debt.progress, if (paid) MaterialTheme.colorScheme.primary else type.accent())
                    Text(
                        "${formatPercent(debt.progress)} paid off of ${money(debt.originalBalance)} original",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!paid) {
                        Button(onClick = onLogPayment, Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Payments, contentDescription = null)
                            Text("  Log a payment")
                        }
                    }
                }
            }
            item {
                SectionCard {
                    Row(Modifier.fillMaxWidth()) {
                        StatTile("APR", formatApr(debt.apr), Modifier.weight(1f))
                        StatTile("Minimum payment", money(debt.minPayment), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        StatTile("Interest per month", money(state.monthlyInterest), Modifier.weight(1f))
                        StatTile("Interest paid so far", money(state.interestPaid), Modifier.weight(1f))
                    }
                    if (debt.priorInterestPaid > 0.0) {
                        Text(
                            "Includes about ${money(debt.priorInterestPaid)} estimated from the starting progress and minimum payment, before you began tracking here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (!paid) {
                item { KindCard(state) }
                item { ProjectionCard(state) }
            }
            item {
                SectionCard(title = "Balance over time") { BalanceHistoryChart(state.history) }
            }
            item {
                Text("History", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            }
            if (state.payments.isEmpty()) {
                item {
                    Text(
                        "No payments logged yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    SectionCard {
                        state.payments.forEachIndexed { i, p ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            PaymentRow(p, canUndo = p.id == state.latestPaymentId, onUndo = { confirmUndo = true })
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete ${debt?.name ?: "debt"}?",
            text = "This removes the debt and its payment history from your totals and charts. This can't be undone.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                confirmDelete = false
                vm.delete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmUndo) {
        ConfirmDialog(
            title = "Undo latest entry?",
            text = "The most recent payment, balance adjustment or rate change is removed and the debt goes back to how it was before.",
            confirmLabel = "Undo",
            onConfirm = {
                confirmUndo = false
                vm.undoLatest()
            },
            onDismiss = { confirmUndo = false },
        )
    }
}

@Composable
private fun KindCard(state: DebtDetailState) {
    val debt = state.debt ?: return
    if (debt.debtType.revolving) {
        SectionCard(title = "Revolving debt", subtitle = "Balance and interest move with every charge and payment") {
            Text(
                "Avalanche treats credit cards as top priority: the highest APR gets the extra money first, and a card wins any tie. " +
                    "At today's balance this card adds about ${money(state.monthlyInterest)} in interest each month.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        SectionCard(title = "Installment loan", subtitle = "Fixed schedule") {
            val text = if (state.minOnlyMonths != null && state.minOnlyPayoff != null) {
                "On the minimum payment alone this is paid off in ${formatDuration(state.minOnlyMonths)} (${formatMonth(state.minOnlyPayoff)}). " +
                    "Extra money shortens that schedule."
            } else {
                "The minimum payment doesn't cover the interest, so the balance won't fall on its own. Check the APR and payment."
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ProjectionCard(state: DebtDetailState) {
    SectionCard(title = "Projected payoff") {
        if (state.planConverges && state.planPayoff != null) {
            Text(
                formatMonth(state.planPayoff),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Under your ${state.strategy.label} plan", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                "Not on track under the current plan",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.minOnlyPayoff != null) {
            Text(
                "Minimum payments only: ${formatMonth(state.minOnlyPayoff)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PaymentRow(p: PaymentEntity, canUndo: Boolean, onUndo: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (p.isAdjustment) {
                val oldRate = p.prevApr?.let { formatApr(it) }
                if (oldRate != null && p.balanceBefore == p.balanceAfter) {
                    Text("Interest rate changed", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text("Was $oldRate APR · ${formatEpochDay(p.day)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Balance adjusted to ${money(p.balanceAfter)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    val was = "Was ${money(p.balanceBefore)}" + (oldRate?.let { ", rate was $it" } ?: "")
                    Text("$was · ${formatEpochDay(p.day)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Text("Payment ${money(p.amount)}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    "${formatEpochDay(p.day)} · ${money(p.principalPortion)} principal · ${money(p.interestPortion)} interest",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canUndo) TextButton(onClick = onUndo) { Text("Undo") }
    }
}
