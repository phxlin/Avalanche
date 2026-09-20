package com.avalanche.app.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avalanche.app.data.AppSettings
import com.avalanche.app.domain.PayoffPlan
import com.avalanche.app.ui.components.BalanceHistoryChart
import com.avalanche.app.ui.components.EmptyState
import com.avalanche.app.ui.components.MountainRidge
import com.avalanche.app.ui.components.PlanLine
import com.avalanche.app.ui.components.ProgressBar
import com.avalanche.app.ui.components.ProjectedBalanceChart
import com.avalanche.app.ui.components.ScreenScaffold
import com.avalanche.app.ui.components.SectionCard
import com.avalanche.app.ui.components.StatTile
import com.avalanche.app.ui.components.money
import com.avalanche.app.ui.appViewModel
import com.avalanche.app.util.formatDuration
import com.avalanche.app.util.formatMonth
import com.avalanche.app.util.formatPercent

@Composable
fun DashboardScreen(onAddDebt: () -> Unit, onLogPayment: () -> Unit, onOpenPlan: () -> Unit) {
    val vm: DashboardViewModel = appViewModel { DashboardViewModel(it.repository, it.settings) }
    val state by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = "Avalanche", titleIcon = Icons.Filled.Terrain) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                !state.loaded -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.isEmpty -> EmptyState(
                    icon = Icons.Filled.Terrain,
                    title = "Every avalanche starts small",
                    body = "Add the debts you want to pay off and Avalanche builds your path to zero. Everything stays on this phone: no account, no sync.",
                    actionLabel = "Add your first debt",
                    onAction = onAddDebt,
                )
                else -> DashboardContent(state, onAddDebt, onLogPayment, onOpenPlan)
            }
        }
    }
}

@Composable
private fun DashboardContent(state: DashboardState, onAddDebt: () -> Unit, onLogPayment: () -> Unit, onOpenPlan: () -> Unit) {
    val settings = state.settings ?: return
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HeroCard(state) }
        if (state.allPaidOff) {
            item { DebtFreeCard() }
        } else {
            state.plan?.let { plan -> item { DebtFreeDateCard(plan, settings, onOpenPlan) } }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InterestCard(
                    "Interest paid so far",
                    money(state.totals.interestPaid),
                    Modifier.weight(1f),
                    caption = if (state.totals.estimatedInterest > 0.0) "incl. ~${money(state.totals.estimatedInterest)} estimated" else null,
                )
                InterestCard(
                    "Interest still to come",
                    state.plan?.takeIf { it.converges }?.let { money(it.totalInterest) } ?: if (state.allPaidOff) money(0.0) else "n/a",
                    Modifier.weight(1f),
                )
            }
        }
        item {
            SectionCard(title = "Balance over time", subtitle = "From your logged payments") {
                BalanceHistoryChart(state.history)
            }
        }
        state.plan?.let { plan ->
            item {
                SectionCard(title = "Projected payoff", subtitle = "${settings.strategy.label} plan, month by month") {
                    ProjectedBalanceChart(listOf(PlanLine(settings.strategy.label, plan, MaterialTheme.colorScheme.primary)))
                    TextButton(onClick = onOpenPlan) { Text("Open plan and calculator") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.allPaidOff) {
                    Button(onClick = onLogPayment, Modifier.weight(1f)) {
                        Icon(Icons.Filled.Payments, contentDescription = null)
                        Text("  Log payment")
                    }
                }
                FilledTonalButton(onClick = onAddDebt, Modifier.weight(1f)) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Add debt")
                }
            }
        }
    }
}

@Composable
private fun HeroCard(state: DashboardState) {
    val t = state.totals
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            Column(Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Total debt remaining", style = MaterialTheme.typography.labelLarge)
                Text(money(t.remaining), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                ProgressBar(t.percentPaid, MaterialTheme.colorScheme.primary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${formatPercent(t.percentPaid)} paid off", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("of ${money(t.original)} original", style = MaterialTheme.typography.bodyMedium)
                }
            }
            MountainRidge(
                rock = MaterialTheme.colorScheme.onPrimaryContainer,
                snow = Color.White,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            )
        }
    }
}

@Composable
private fun DebtFreeCard() {
    SectionCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Column {
                Text("You're debt free!", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("Every debt is paid off. Add a new one to keep tracking, or delete the paid-off ones.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun DebtFreeDateCard(plan: PayoffPlan, settings: AppSettings, onOpenPlan: () -> Unit) {
    val extra = money(settings.extraMonthly)
    val planLine = settings.strategy.label +
        if (settings.extraMonthly > 0) " + $extra/mo extra" else ", minimum payments only"
    SectionCard(title = "Projected debt-free date") {
        if (plan.converges) {
            Text(
                plan.debtFreeMonth?.let { formatMonth(it) } ?: "-",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${formatDuration(plan.monthsToDebtFree ?: 0)} from now · $planLine",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Not on track yet",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Minimum payments don't cover the interest on at least one debt. Raise a minimum or add an extra monthly amount.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenPlan) { Text("Adjust the plan") }
        }
    }
}

@Composable
private fun InterestCard(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp)) {
            StatTile(label, value)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
