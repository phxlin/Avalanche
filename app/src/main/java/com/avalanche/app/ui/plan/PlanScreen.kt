package com.avalanche.app.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.avalanche.app.data.DebtEntity
import com.avalanche.app.domain.MonthPlan
import com.avalanche.app.domain.PayoffPlan
import com.avalanche.app.domain.Strategy
import com.avalanche.app.ui.components.DecimalField
import com.avalanche.app.ui.components.EmptyState
import com.avalanche.app.ui.components.PayoffTimeline
import com.avalanche.app.ui.components.PlanLine
import com.avalanche.app.ui.components.ProjectedBalanceChart
import com.avalanche.app.ui.components.LoadingBox
import com.avalanche.app.ui.components.ScreenScaffold
import com.avalanche.app.ui.components.SectionCard
import com.avalanche.app.ui.components.TimelineItem
import com.avalanche.app.ui.components.money
import com.avalanche.app.ui.appViewModel
import com.avalanche.app.ui.theme.kindColors
import com.avalanche.app.util.formatApr
import com.avalanche.app.util.formatDuration
import com.avalanche.app.util.formatMonth
import com.avalanche.app.util.joinNames

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlanScreen(onAddDebt: () -> Unit, onLogLumpSum: (Map<Long, Double>) -> Unit) {
    val vm: PlanViewModel = appViewModel { PlanViewModel(it.repository, it.settings) }
    val state by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(title = "Payoff plan") { padding ->
        val settings = state.settings
        val selected = state.selected
        when {
            !state.loaded -> LoadingBox(padding)
            selected == null || settings == null -> Box(Modifier.padding(padding).fillMaxSize()) {
                EmptyState(
                    icon = Icons.Filled.Timeline,
                    title = if (state.hasAnyDebt) "Nothing left to plan" else "Add a debt to build a plan",
                    body = if (state.hasAnyDebt) "Every debt is paid off. Nice work." else "The calculator ranks your debts and projects a month-by-month path to zero.",
                    actionLabel = "Add a debt",
                    onAction = onAddDebt,
                )
            }
            else -> {
                val nameById = state.activeDebts.associate { it.id to it.name }
                val shown = state.lump?.plan ?: selected
                val avalanche = state.avalanche ?: selected
                val snowball = state.snowball ?: selected
                val schedule = remember { mutableStateListOf<Int>() }
                var showAll by rememberSaveable { mutableStateOf(false) }
                val visibleMonths = if (showAll) shown.months else shown.months.take(12)

                LazyColumn(
                    Modifier.padding(padding).fillMaxSize().imePadding(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        SectionCard(title = "Strategy") {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                Strategy.entries.forEachIndexed { i, s ->
                                    SegmentedButton(
                                        selected = settings.strategy == s,
                                        onClick = { vm.setStrategy(s) },
                                        shape = SegmentedButtonDefaults.itemShape(i, Strategy.entries.size),
                                    ) { Text(s.label) }
                                }
                            }
                            Text(settings.strategy.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DecimalField(
                                value = vm.extraText,
                                onValueChange = vm::onExtraChanged,
                                label = "Extra per month",
                                supporting = "On top of your minimums. The plan updates as you type.",
                            )
                        }
                    }
                    item { SummaryCard(selected) }
                    if (selected.converges) {
                        item { ComparisonCard(avalanche, snowball, settings.strategy, onSelect = vm::setStrategy) }
                    }
                    item { LumpSumCard(vm, state, onLogLumpSum) }
                    item {
                        // This is the order extra money is aimed in, not the order debts finish (the timeline below shows that,
                        // soonest first): a small low-APR debt can be cleared by its own minimum long before a big high-APR one.
                        val debtById = state.activeDebts.associateBy { it.id }
                        SectionCard(
                            title = "Where extra money goes",
                            subtitle = if (shown.strategy == Strategy.AVALANCHE) {
                                "Highest APR first. Once a debt is cleared, its payment moves down the list."
                            } else {
                                "Smallest balance first. Once a debt is cleared, its payment moves down the list."
                            },
                        ) {
                            shown.priorityOrder.forEachIndexed { i, id ->
                                val debt = debtById[id]
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
                                    Text(nameById[id] ?: "Debt", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    if (debt != null) {
                                        Text(
                                            if (shown.strategy == Strategy.AVALANCHE) "${formatApr(debt.apr)} APR" else money(debt.currentBalance),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        val lines = buildList {
                            add(PlanLine("Avalanche", avalanche, MaterialTheme.colorScheme.primary))
                            add(PlanLine("Snowball", snowball, MaterialTheme.kindColors.installment, dashed = true))
                            state.lump?.let { add(PlanLine("With lump sum", it.plan, MaterialTheme.colorScheme.tertiary)) }
                        }
                        // Both strategies pay the same total every month, so their balance curves are often almost identical.
                        val gap = if (avalanche.converges && snowball.converges) avalanche.maxBalanceGap(snowball) else null
                        val overlapping = gap != null && gap <= 0.01 * avalanche.startBalance
                        SectionCard(title = "Projected balance", subtitle = "Total debt month by month") {
                            ProjectedBalanceChart(lines)
                            if (gap != null && overlapping) {
                                Text(
                                    "Avalanche and Snowball stay within ${money(gap)} of each other, so their lines overlap. " +
                                        "The difference shows up in interest, not in the balance curve.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (shown.converges) {
                        item {
                            val palette = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.kindColors.installment, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
                            SectionCard(title = "Payoff timeline", subtitle = "When each debt reaches zero") {
                                // Soonest first. Colours stay tied to each debt's place in the payoff order above.
                                val colorById = shown.priorityOrder.withIndex().associate { (i, id) -> id to palette[i % palette.size] }
                                PayoffTimeline(
                                    shown.payoffDateOrder().map { id ->
                                        TimelineItem(nameById[id] ?: "Debt", shown.payoffMonthIndex[id], shown.payoffMonth(id)?.let { formatMonth(it) } ?: "-", colorById.getValue(id))
                                    },
                                )
                            }
                        }
                        item {
                            Column(Modifier.padding(top = 4.dp)) {
                                Text("Month-by-month schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (state.lump != null) {
                                    Text("Includes your lump sum.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                val alreadyPaid = shown.priorityOrder.filter { it in shown.alreadyPaidIds }.mapNotNull { nameById[it] }
                                if (alreadyPaid.isNotEmpty()) {
                                    Text(
                                        "This month's payment is already made on ${joinNames(alreadyPaid)}, so the first month leaves out " +
                                            (if (alreadyPaid.size == 1) "its minimum. " else "their minimums. ") +
                                            "Your extra money still goes by the order above. Edit a debt to change that.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        items(visibleMonths, key = { it.index }) { month ->
                            MonthRow(
                                month = month,
                                names = nameById,
                                expanded = month.index in schedule,
                                onToggle = { if (month.index in schedule) schedule.remove(month.index) else schedule.add(month.index) },
                            )
                        }
                        if (shown.months.size > 12) {
                            item {
                                TextButton(onClick = { showAll = !showAll }, Modifier.fillMaxWidth()) {
                                    Text(if (showAll) "Show fewer months" else "Show all ${shown.months.size} months")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(plan: PayoffPlan) {
    SectionCard(title = "Your ${plan.strategy.label} plan") {
        if (!plan.converges) {
            Text("Not on track yet", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Text(
                "At least one debt's minimum doesn't cover its monthly interest, so it never reaches zero. Raise an extra monthly amount above to see a payoff date.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        Text(
            plan.debtFreeMonth?.let { formatMonth(it) } ?: "-",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Debt-free in ${formatDuration(plan.monthsToDebtFree ?: 0)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Interest still to pay", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(plan.totalInterest), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text("Total you'll pay", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(plan.totalPaid), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun ComparisonCard(avalanche: PayoffPlan, snowball: PayoffPlan, current: Strategy, onSelect: (Strategy) -> Unit) {
    SectionCard(title = "Avalanche vs. snowball", subtitle = "Same monthly budget, different order. Tap one to plan with it.") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ComparisonColumn(avalanche, highlighted = current == Strategy.AVALANCHE, onClick = { onSelect(Strategy.AVALANCHE) }, modifier = Modifier.weight(1f))
            ComparisonColumn(snowball, highlighted = current == Strategy.SNOWBALL, onClick = { onSelect(Strategy.SNOWBALL) }, modifier = Modifier.weight(1f))
        }
        val verdict = when {
            !avalanche.converges || !snowball.converges -> null
            snowball.totalInterest - avalanche.totalInterest >= 0.5 ->
                "Avalanche saves ${money(snowball.totalInterest - avalanche.totalInterest)} in interest."
            else -> "Both cost about the same interest with your debts."
        }
        if (verdict != null) Text(verdict, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        val firstA = avalanche.payoffMonthIndex.values.minOrNull()
        val firstS = snowball.payoffMonthIndex.values.minOrNull()
        if (firstA != null && firstS != null && firstS < firstA) {
            Text(
                "Snowball clears its first debt ${firstA - firstS} month${if (firstA - firstS == 1) "" else "s"} sooner, which can help you stay motivated.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ComparisonColumn(plan: PayoffPlan, highlighted: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    // A radio-style choice: announced as selected when it is the strategy in use.
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            role = Role.RadioButton
            selected = highlighted
        },
        shape = MaterialTheme.shapes.small,
        color = bg,
        contentColor = fg,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(plan.strategy.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (highlighted) Text("In use", style = MaterialTheme.typography.labelSmall)
            }
            Column {
                Text("Total interest", style = MaterialTheme.typography.labelSmall)
                Text(if (plan.converges) money(plan.totalInterest) else "n/a", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column {
                Text("Debt-free", style = MaterialTheme.typography.labelSmall)
                Text(plan.debtFreeMonth?.let { formatMonth(it) } ?: "n/a", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LumpSumCard(vm: PlanViewModel, state: PlanUiState, onLogLumpSum: (Map<Long, Double>) -> Unit) {
    val nameById = state.activeDebts.associate { it.id to it.name }
    SectionCard(title = "One-time lump sum", subtitle = "Bonus, tax refund, gift: see what it does to your plan") {
        DecimalField(value = vm.lumpText, onValueChange = vm::onLumpChanged, label = "Amount")
        Text("Apply it to", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = vm.lumpTarget == null, onClick = { vm.onLumpTargetChanged(null) }, label = { Text("Auto: highest APR") })
            state.activeDebts.forEach { d: DebtEntity ->
                FilterChip(selected = vm.lumpTarget == d.id, onClick = { vm.onLumpTargetChanged(d.id) }, label = { Text(d.name) })
            }
        }
        val lump = state.lump
        if (lump != null) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val base = state.selected
            if (lump.putsPlanOnTrack) {
                Text("This puts you on track to be debt-free by ${lump.plan.debtFreeMonth?.let { formatMonth(it) }}.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            } else if (lump.monthsSooner != null && base != null) {
                Text(
                    "Debt-free ${base.debtFreeMonth?.let { formatMonth(it) }} → ${lump.plan.debtFreeMonth?.let { formatMonth(it) }}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (lump.monthsSooner > 0) "${formatDuration(lump.monthsSooner)} sooner · saves ${money(lump.interestSaved ?: 0.0)} in interest"
                    else "Saves ${money(lump.interestSaved ?: 0.0)} in interest",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lump.allocation.forEach { (id, amount) ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(nameById[id] ?: "Debt", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(money(amount), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                if (lump.applied + 0.005 < lump.amount) {
                    Text("Only ${money(lump.applied)} is needed to clear everything.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Button(onClick = { onLogLumpSum(lump.allocation) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Log it as a payment today")
            }
        }
    }
}

@Composable
private fun MonthRow(month: MonthPlan, names: Map<Long, String>, expanded: Boolean, onToggle: () -> Unit) {
    androidx.compose.material3.Surface(
        Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatMonth(month.month), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Paid ${money(month.totalPaid)} · interest ${money(month.totalInterest)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(money(month.endBalance), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand", modifier = Modifier.padding(start = 8.dp))
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                month.debts.forEach { d ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(names[d.debtId] ?: "Debt", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            val parts = buildList {
                                add("min ${money(d.minPaid)}")
                                if (d.extraPaid > 0) add("extra ${money(d.extraPaid)}")
                                if (d.lumpPaid > 0) add("lump ${money(d.lumpPaid)}")
                                add("interest ${money(d.interest)}")
                            }
                            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            if (d.endBalance == 0.0) "Paid off" else money(d.endBalance),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (d.endBalance == 0.0) MaterialTheme.colorScheme.primary else Color.Unspecified,
                            fontWeight = if (d.endBalance == 0.0) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
