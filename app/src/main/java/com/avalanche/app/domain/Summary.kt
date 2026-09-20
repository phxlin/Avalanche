package com.avalanche.app.domain

import com.avalanche.app.data.DebtEntity
import com.avalanche.app.data.PaymentEntity
import kotlin.math.max

data class DebtTotals(
    val remaining: Double,
    val original: Double,
    val percentPaid: Float,
    /** Logged interest plus any estimate for the time before debts were added. */
    val interestPaid: Double,
    /** The estimated part of [interestPaid]. */
    val estimatedInterest: Double = 0.0,
)

fun computeTotals(debts: List<DebtEntity>, payments: List<PaymentEntity>): DebtTotals {
    val remaining = debts.sumOf { it.currentBalance }
    val original = debts.sumOf { it.originalBalance }
    val pct = if (original <= 0.0) 0.0 else (1.0 - remaining / original).coerceIn(0.0, 1.0)
    val estimated = debts.sumOf { it.priorInterestPaid }
    return DebtTotals(remaining, original, pct.toFloat(), payments.sumOf { it.interestPortion } + estimated, estimated)
}

/**
 * The original balance implied by a debt that is already [percentPaid] percent paid off.
 * A 4,200 balance that is 30% paid off started at 6,000. Clamped to 0..99.99%.
 *
 * Deliberately not rounded to cents: rounding the original nudges the derived percentage
 * (32% would come back as 31.9999...%), and the display rounds it anyway.
 */
fun originalFromPercentPaid(currentBalance: Double, percentPaid: Double): Double =
    if (currentBalance <= 0.0) 0.0 else currentBalance / (1.0 - percentPaid.coerceIn(0.0, 99.99) / 100.0)

/** How far along a debt is, as a percentage (0..100). */
fun percentPaidOf(originalBalance: Double, currentBalance: Double): Double =
    if (originalBalance <= 0.0) 0.0 else ((1.0 - currentBalance / originalBalance) * 100.0).coerceIn(0.0, 100.0)

data class InterestEstimate(
    /** Months of payments it would have taken (fractional). */
    val months: Double,
    val interest: Double,
)

/**
 * Estimates the interest already paid on a debt that started at [original] and now stands at [current],
 * assuming a payment of [payment] every month at [apr] the whole way. It replays the debt month by month
 * until the balance reaches [current]; the interest paid is then `months * payment - (original - current)`.
 *
 * Returns null when [payment] doesn't cover the interest at the starting balance, because the balance
 * couldn't have fallen that way. Real credit-card minimums shrink as the balance does, so treat the
 * result as a rough estimate; for fixed-payment loans it is close.
 */
fun estimateInterestPaid(original: Double, current: Double, apr: Double, payment: Double): InterestEstimate? {
    if (original <= current + 0.005) return InterestEstimate(0.0, 0.0)
    if (payment <= 0.0) return null
    var balance = original
    var interest = 0.0
    var months = 0.0
    for (step in 0 until 1200) {
        val monthlyInterest = balance * apr / 1200.0
        val paid = minOf(payment, balance + monthlyInterest)
        val next = balance + monthlyInterest - paid
        if (next >= balance - 1e-9) return null
        if (next <= current) {
            val fraction = (balance - current) / (balance - next)
            return InterestEstimate(months + fraction, interest + monthlyInterest * fraction)
        }
        interest += monthlyInterest
        months += 1.0
        balance = next
    }
    return null
}

data class BalancePoint(val day: Long, val balance: Double)

/**
 * Historical balance over time, rebuilt from the debts' starting balances and their logged
 * payments/adjustments. Each debt contributes its last known balance at every point in time,
 * and the series always ends at [today] so the line reaches the present.
 */
fun balanceHistory(debts: List<DebtEntity>, payments: List<PaymentEntity>, today: Long): List<BalancePoint> {
    if (debts.isEmpty()) return emptyList()
    val byDebt = payments.groupBy { it.debtId }

    class Track(val created: Long, val initial: Double, val events: List<PaymentEntity>)

    val tracks = debts.map { d ->
        val all = byDebt[d.id].orEmpty()
        Track(
            created = d.createdDay,
            initial = all.minByOrNull { it.id }?.balanceBefore ?: d.currentBalance,
            events = all.sortedWith(compareBy({ it.day }, { it.id })),
        )
    }

    val days = sortedSetOf<Long>()
    for (t in tracks) {
        days += t.created
        t.events.forEach { days += max(it.day, t.created) }
    }
    days += max(today, days.last())

    return days.map { day ->
        val total = tracks.sumOf { t ->
            if (t.created > day) 0.0 else t.events.lastOrNull { it.day <= day }?.balanceAfter ?: t.initial
        }
        BalancePoint(day, max(0.0, total))
    }
}
