package com.avalanche.app.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.avalanche.app.domain.PayoffPlan
import com.avalanche.app.domain.SavingsMilestone
import com.avalanche.app.domain.SavingsProjection
import com.avalanche.app.domain.SavingsProjector
import com.avalanche.app.domain.SavingsSetup
import com.avalanche.app.ui.components.DecimalField
import com.avalanche.app.ui.components.ProgressBar
import com.avalanche.app.ui.components.SectionCard
import com.avalanche.app.ui.components.StatTile
import com.avalanche.app.ui.components.money
import com.avalanche.app.ui.theme.kindColors
import com.avalanche.app.util.editableNumber
import com.avalanche.app.util.formatApr
import com.avalanche.app.util.formatMonth
import com.avalanche.app.util.formatPercentPoints

/**
 * The optional savings account next to the payoff plan. [projection] is null until savings are set up. [plan] is the
 * plan the projection was lined up with.
 */
@Composable
internal fun SavingsCard(projection: SavingsProjection?, plan: PayoffPlan, onEdit: () -> Unit) {
    if (projection == null) {
        SectionCard(title = "Savings", subtitle = "Optional: watch a savings account grow next to your debt") {
            Text(
                "Enter your income, how much of it you save and your account's rate, and Avalanche adds a savings line to the " +
                    "projected balance chart so you can see when your savings pass what you owe.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onEdit, Modifier.fillMaxWidth()) { Text("Set up savings") }
        }
        return
    }

    val setup = projection.setup
    SectionCard(title = "Savings", subtitle = "Your high-yield savings account, next to the plan") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Saved today", money(setup.balance), Modifier.weight(1f))
            StatTile("Saving each month", money(setup.monthlyContribution), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Share of income", formatPercentPoints(setup.percentSaved), Modifier.weight(1f))
            StatTile("Rate", "${formatApr(setup.apy)} APY", Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val debtFree = plan.debtFreeMonth
        val savedAtDebtFree = projection.savedAtDebtFree
        val interest = projection.interestEarnedAtDebtFree
        if (projection.alreadyAhead) {
            Text("You already have more saved than you owe.", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        }
        val crossover = projection.crossoverMonthIndex
        if (crossover != null && crossover < (plan.monthsToDebtFree ?: 0)) {
            Text(
                "Your savings pass what you still owe in ${formatMonth(plan.monthAt(crossover))}.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (debtFree != null && savedAtDebtFree != null) {
            val earned = if (interest != null && interest >= 0.005) ", ${money(interest)} of it interest" else ""
            Text("When you're debt-free in ${formatMonth(debtFree)}, you'd have ${money(savedAtDebtFree)} saved$earned.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                "The savings projection appears once your debt plan has a payoff date.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Your minimums plus the extra amount come to ${formatPercentPoints(projection.debtPaymentShare * 100.0)} of your income.",
            style = MaterialTheme.typography.bodyMedium,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SavingsJourney(remember(setup) { SavingsProjector.milestones(setup) }, setup, plan)
        Text(
            "Estimates: the rate is assumed to stay the same, interest compounds monthly and taxes are ignored. Not financial advice.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onEdit) { Text("Edit savings") }
    }
}

private val MILESTONE_DOT_SIZE = 18.dp
private val MILESTONE_LABEL_WIDTH = 60.dp

/**
 * One progress bar for the whole savings journey, with a marker at each milestone along it (1, 3, 6 and 12 months of
 * income). The bar's fill is the balance as a share of the furthest milestone; each marker fills in once its own,
 * smaller target is reached, so the journey reads left to right like a trail with waypoints.
 */
@Composable
private fun SavingsJourney(milestones: List<SavingsMilestone>, setup: SavingsSetup, plan: PayoffPlan) {
    if (milestones.isEmpty()) return
    val maxMonths = milestones.maxOf { it.months }.toFloat()
    val overallProgress = (setup.balance / milestones.last().target).toFloat().coerceIn(0f, 1f)
    val next = milestones.firstOrNull { !it.reached }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Milestones", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            if (next == null) {
                "You've saved ${milestoneLabel(milestones.last().months)} — nice work."
            } else {
                val until = next.monthsUntilReached
                "${money(setup.balance)} of ${money(next.target)} toward ${milestoneLabel(next.months)}" +
                    if (until != null) " · ~${formatMonth(plan.monthAt(until))}" else " · not at this pace"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 34.dp)
                .semantics { contentDescription = journeyDescription(milestones, plan) },
        ) {
            val trackWidth = maxWidth - MILESTONE_DOT_SIZE
            ProgressBar(overallProgress, MaterialTheme.kindColors.savings, Modifier.align(Alignment.CenterStart))
            milestones.forEach { milestone ->
                val dotX = trackWidth * (milestone.months / maxMonths).coerceIn(0f, 1f)
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = dotX)
                        .size(MILESTONE_DOT_SIZE)
                        .clip(CircleShape)
                        .background(if (milestone.reached) MaterialTheme.kindColors.savings else MaterialTheme.colorScheme.surfaceContainerLow)
                        .border(2.dp, if (milestone.reached) MaterialTheme.kindColors.savings else MaterialTheme.colorScheme.outline, CircleShape),
                )
                val labelX = (dotX + MILESTONE_DOT_SIZE / 2 - MILESTONE_LABEL_WIDTH / 2).coerceIn(0.dp, maxWidth - MILESTONE_LABEL_WIDTH)
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(x = labelX, y = MILESTONE_DOT_SIZE + 4.dp)
                        .width(MILESTONE_LABEL_WIDTH),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        milestoneShortLabel(milestone.months),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (milestone.reached) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                    val until = milestone.monthsUntilReached
                    Text(
                        when {
                            milestone.reached -> "Reached"
                            until != null -> formatMonth(plan.monthAt(until))
                            else -> "—"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (milestone.reached) MaterialTheme.kindColors.savings else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun milestoneLabel(months: Int): String = when {
    months == 1 -> "1 month of income"
    months == 12 -> "1 year of income"
    months % 12 == 0 -> "${months / 12} years of income"
    else -> "$months months of income"
}

/** The tag under each marker on the journey bar: "1mo", "3mo", "1yr". */
private fun milestoneShortLabel(months: Int): String = if (months % 12 == 0) "${months / 12}yr" else "${months}mo"

private fun journeyDescription(milestones: List<SavingsMilestone>, plan: PayoffPlan): String =
    "Savings journey. " + milestones.joinToString(" ") { milestone ->
        val status = when {
            milestone.reached -> "reached"
            milestone.monthsUntilReached != null -> "about ${formatMonth(plan.monthAt(milestone.monthsUntilReached))}"
            else -> "not reached at this pace"
        }
        "${milestoneLabel(milestone.months)}: $status."
    }

private fun zeroAsText(value: Double): String = if (value == 0.0) "0" else editableNumber(value)

/** Asks for the savings details. [onRemove] is offered only when savings are already set up. */
@Composable
internal fun SavingsDialog(initial: SavingsSetup?, onSave: (SavingsSetup) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    var balance by rememberSaveable { mutableStateOf(initial?.let { editableNumber(it.balance) } ?: "") }
    var income by rememberSaveable { mutableStateOf(initial?.let { editableNumber(it.monthlyIncome) } ?: "") }
    // Zero is a real answer for these two (no saving yet, or an account that pays nothing), and they are required, so it has to show as "0", not an empty box.
    var percent by rememberSaveable { mutableStateOf(initial?.let { zeroAsText(it.percentSaved) } ?: "") }
    var apy by rememberSaveable { mutableStateOf(initial?.let { zeroAsText(it.apy) } ?: "") }
    val result = validateSavingsForm(balance, income, percent, apy)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Savings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DecimalField(balance, { balance = it }, "Saved today", error = result.balanceError, supporting = "Leave empty if you're starting from zero.")
                DecimalField(income, { income = it }, "Monthly income", error = result.incomeError, supporting = "What you take home each month, after tax.")
                DecimalField(percent, { percent = it }, "Share of income you save", suffix = "%", error = result.percentError)
                DecimalField(apy, { apy = it }, "Account rate (APY)", suffix = "%", error = result.apyError, supporting = "The yearly rate your bank advertises.")
                if (onRemove != null) {
                    TextButton(onClick = onRemove) { Text("Remove savings", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { result.setup?.let(onSave) }, enabled = result.setup != null) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
