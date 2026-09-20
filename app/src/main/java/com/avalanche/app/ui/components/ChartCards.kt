package com.avalanche.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.avalanche.app.domain.BalancePoint
import com.avalanche.app.domain.PayoffPlan
import com.avalanche.app.util.formatEpochDay
import com.avalanche.app.util.formatMonth

/** Historical balance over time, rebuilt from logged payments. */
@Composable
fun BalanceHistoryChart(history: List<BalancePoint>, modifier: Modifier = Modifier) {
    val money = LocalMoney.current
    if (history.size < 2) {
        Text(
            "Your balance history draws itself as you log payments on different days.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val first = history.first().day
    val series = listOf(
        ChartSeries("Balance", MaterialTheme.colorScheme.primary, history.map { ChartPoint((it.day - first).toFloat(), it.balance.toFloat()) }),
    )
    LineChart(
        series = series,
        xLabel = { formatEpochDay(first + it.toLong()) },
        yLabel = { money.compact(it.toDouble()) },
        description = "Line chart of your total balance from ${formatEpochDay(first)} to ${formatEpochDay(history.last().day)}",
        modifier = modifier,
    )
}

data class PlanLine(val label: String, val plan: PayoffPlan, val color: Color, val dashed: Boolean = false)

/** Projected total balance by month for one or more plans. Plans that never converge are skipped. */
@Composable
fun ProjectedBalanceChart(lines: List<PlanLine>, modifier: Modifier = Modifier) {
    val money = LocalMoney.current
    val drawable = lines.filter { it.plan.converges && it.plan.months.isNotEmpty() }
    if (drawable.isEmpty()) {
        Text(
            "No projection yet: the current payments never clear the balance.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val startPlan = drawable.first().plan
    val series = drawable.map { l ->
        ChartSeries(
            l.label, l.color,
            l.plan.balanceCurve().mapIndexed { i, b -> ChartPoint(i.toFloat(), b.toFloat()) },
            dashed = l.dashed,
        )
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LineChart(
            series = series,
            xLabel = { if (it < 0.5f) "Now" else formatMonth(startPlan.monthAt(it.toInt())) },
            yLabel = { money.compact(it.toDouble()) },
            description = "Projected total balance by month until debt-free",
        )
        if (series.size > 1) ChartLegend(series)
    }
}
