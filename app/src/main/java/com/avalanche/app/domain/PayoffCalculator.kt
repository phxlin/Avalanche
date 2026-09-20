package com.avalanche.app.domain

import com.avalanche.app.data.DebtEntity
import com.avalanche.app.data.PAID_OFF_EPSILON
import com.avalanche.app.data.debtType
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Strategy(val label: String, val blurb: String) {
    AVALANCHE("Avalanche", "Highest APR first. Costs the least interest."),
    SNOWBALL("Snowball", "Smallest balance first. Quicker early wins."),
}

data class PlanDebt(
    val id: Long,
    val name: String,
    val revolving: Boolean,
    val balance: Double,
    val apr: Double,
    val minPayment: Double,
    /**
     * How much has already been paid on this debt this month, or null if nothing has. [balance] already reflects it.
     * Month 1 then schedules only what is left of the minimum (nothing once the minimum is covered), and the amount
     * paid comes out of the month's budget; interest still accrues.
     */
    val alreadyPaidThisMonth: Double? = null,
)

/** A one-time extra payment applied at the start of the plan. [targetDebtId] null means "highest APR". */
data class LumpSum(val amount: Double, val targetDebtId: Long? = null)

data class DebtMonthResult(
    val debtId: Long,
    val minPaid: Double,
    val extraPaid: Double,
    val lumpPaid: Double,
    val interest: Double,
    val endBalance: Double,
) {
    val totalPaid: Double get() = minPaid + extraPaid + lumpPaid
}

data class MonthPlan(
    val index: Int,
    val month: YearMonth,
    val debts: List<DebtMonthResult>,
    val endBalance: Double,
) {
    val totalPaid: Double get() = debts.sumOf { it.totalPaid }
    val totalInterest: Double get() = debts.sumOf { it.interest }
    val lumpPaid: Double get() = debts.sumOf { it.lumpPaid }
}

data class PayoffPlan(
    val strategy: Strategy,
    val startMonth: YearMonth,
    val months: List<MonthPlan>,
    /** debtId -> 1-based index of the month the debt reaches zero. */
    val payoffMonthIndex: Map<Long, Int>,
    /** Debts in the order extra money is aimed at them at the start of the plan. */
    val priorityOrder: List<Long>,
    val startBalance: Double,
    val totalInterest: Double,
    val totalPaid: Double,
    /** Null when the plan never reaches zero within [PayoffCalculator.MAX_MONTHS]. */
    val monthsToDebtFree: Int?,
    val unpaidDebtIds: Set<Long>,
    /** Debts whose month-1 minimum was fully paid already (see [PlanDebt.alreadyPaidThisMonth]); part-paid ones aren't listed. */
    val alreadyPaidIds: Set<Long> = emptySet(),
) {
    val isDebtFree: Boolean get() = startBalance <= PAID_OFF_EPSILON
    val converges: Boolean get() = isDebtFree || monthsToDebtFree != null
    val debtFreeMonth: YearMonth? get() = monthsToDebtFree?.let { monthAt(it) }

    /** Month 1 is the current calendar month. */
    fun monthAt(index: Int): YearMonth = startMonth.plusMonths((index - 1).toLong())
    fun payoffMonth(debtId: Long): YearMonth? = payoffMonthIndex[debtId]?.let { monthAt(it) }

    /**
     * Debts by the month they reach zero, soonest first. Debts paid off in the same month keep their
     * [priorityOrder], and debts the plan never clears come last. (The priority order itself says who
     * gets the extra money first, which is not the same as who finishes first.)
     */
    fun payoffDateOrder(): List<Long> = priorityOrder.sortedBy { payoffMonthIndex[it] ?: Int.MAX_VALUE }

    /** Total balance at the start (index 0) and end of every month. Empty if the plan doesn't converge. */
    fun balanceCurve(): List<Double> =
        if (!converges) emptyList() else listOf(startBalance) + months.map { it.endBalance }

    /**
     * The largest difference between this plan's total balance and [other]'s at any month, treating a plan
     * that has finished as owing 0. Small values mean the two balance curves lie on top of each other.
     */
    fun maxBalanceGap(other: PayoffPlan): Double {
        val a = balanceCurve()
        val b = other.balanceCurve()
        return (0 until max(a.size, b.size)).maxOfOrNull { abs((a.getOrNull(it) ?: 0.0) - (b.getOrNull(it) ?: 0.0)) } ?: 0.0
    }

    /** Cash to hand to each debt from the one-time lump sum (month 1). */
    fun lumpAllocation(): Map<Long, Double> =
        months.firstOrNull()?.debts.orEmpty().filter { it.lumpPaid > 0 }.associate { it.debtId to it.lumpPaid }
}

object PayoffCalculator {
    const val MAX_MONTHS = 600

    /**
     * Month-by-month payoff simulation.
     *
     * Each month: interest accrues (APR/12), every debt gets its minimum, and whatever is left of
     * the fixed monthly budget (sum of minimums + [extraMonthly]) is aimed at debts in [strategy]
     * order. Minimums freed by paid-off debts roll into the pool, so the budget never shrinks.
     * A [lump] payment lands at the start of month 1, before that month's interest.
     *
     * The monthly budget is the sum of the minimums of *every* debt passed in, paid off or not, plus [extraMonthly].
     * A debt that has been cleared keeps contributing its minimum, so paying one off frees that money for the rest
     * instead of shrinking what you put in each month.
     *
     * Debts with [PlanDebt.alreadyPaidThisMonth] have already had (some of) their month-1 payment: what was paid
     * counts as spent from that month's budget, so it isn't available again, and only the part of the minimum still
     * unpaid is scheduled. That holds even if the debt has since been cleared, by a lump sum or an earlier payoff.
     * Extra money is separate: it still goes to whichever debt the strategy puts first, paid or not. From month 2
     * everything pays normally.
     */
    fun simulate(
        debts: List<PlanDebt>,
        extraMonthly: Double,
        strategy: Strategy,
        lump: LumpSum? = null,
        start: YearMonth = YearMonth.now(),
    ): PayoffPlan {
        val live = debts.filter { it.balance > PAID_OFF_EPSILON }
        val bal = live.associate { it.id to round2(it.balance) }.toMutableMap()
        val startBalance = bal.values.sum()
        // Cleared debts stay in: their minimum keeps flowing to the rest (the rollover), it doesn't vanish from the budget.
        val budget = debts.sumOf { it.minPayment } + max(0.0, extraMonthly)
        val priority = order(live, bal, strategy).map { it.id }

        val months = ArrayList<MonthPlan>()
        val payoff = HashMap<Long, Int>()
        var totalInterest = 0.0
        var totalPaid = 0.0
        var month = 0

        while (bal.values.any { it > PAID_OFF_EPSILON } && month < MAX_MONTHS) {
            month++
            val active = live.filter { bal.getValue(it.id) > PAID_OFF_EPSILON }
            val lumpPaid = HashMap<Long, Double>()
            val interest = HashMap<Long, Double>()
            val minPaid = HashMap<Long, Double>()
            val extraPaid = HashMap<Long, Double>()

            if (month == 1 && lump != null && lump.amount > PAID_OFF_EPSILON) {
                var remaining = round2(lump.amount)
                val byAvalanche = order(active, bal, Strategy.AVALANCHE)
                val target = active.firstOrNull { it.id == lump.targetDebtId }
                val sequence = if (target != null) listOf(target) + byAvalanche.filter { it.id != target.id } else byAvalanche
                for (d in sequence) {
                    if (remaining <= PAID_OFF_EPSILON) break
                    val pay = min(remaining, bal.getValue(d.id))
                    if (pay <= 0.0) continue
                    bal[d.id] = round2(bal.getValue(d.id) - pay)
                    lumpPaid[d.id] = pay
                    remaining = round2(remaining - pay)
                }
            }

            for (d in active) {
                val cur = bal.getValue(d.id)
                if (cur <= PAID_OFF_EPSILON) continue
                val i = round2(cur * d.apr / 1200.0)
                interest[d.id] = i
                bal[d.id] = round2(cur + i)
            }

            var pool = budget
            if (month == 1) {
                // Money already spent this month on debts that were cleared before the plan starts (paid off earlier this
                // month) is gone from the budget too.
                pool -= debts.filter { it.balance <= PAID_OFF_EPSILON }.sumOf { it.alreadyPaidThisMonth ?: 0.0 }
            }
            for (d in active) {
                val cur = bal.getValue(d.id)
                val alreadyPaid = if (month == 1) d.alreadyPaidThisMonth else null
                // Deduct what was already paid before skipping a debt the lump sum has just cleared, or that money would be
                // spent twice.
                if (alreadyPaid != null) pool -= alreadyPaid
                if (cur <= PAID_OFF_EPSILON) continue
                val owed = if (alreadyPaid != null) max(0.0, d.minPayment - alreadyPaid) else d.minPayment
                val pay = min(owed, cur)
                bal[d.id] = round2(cur - pay)
                minPaid[d.id] = pay
                pool -= pay
            }
            pool = round2(pool)

            if (pool > PAID_OFF_EPSILON) {
                val stillOwing = active.filter { bal.getValue(it.id) > PAID_OFF_EPSILON }
                for (d in order(stillOwing, bal, strategy)) {
                    if (pool <= PAID_OFF_EPSILON) break
                    val pay = min(pool, bal.getValue(d.id))
                    bal[d.id] = round2(bal.getValue(d.id) - pay)
                    extraPaid[d.id] = pay
                    pool = round2(pool - pay)
                }
            }

            val results = active.map { d ->
                val end = bal.getValue(d.id).let { if (it <= PAID_OFF_EPSILON) 0.0 else it }
                bal[d.id] = end
                if (end == 0.0) payoff.putIfAbsent(d.id, month)
                DebtMonthResult(
                    debtId = d.id,
                    minPaid = minPaid[d.id] ?: 0.0,
                    extraPaid = extraPaid[d.id] ?: 0.0,
                    lumpPaid = lumpPaid[d.id] ?: 0.0,
                    interest = interest[d.id] ?: 0.0,
                    endBalance = end,
                )
            }
            val plan = MonthPlan(month, start.plusMonths((month - 1).toLong()), results, round2(bal.values.sum()))
            totalInterest += plan.totalInterest
            totalPaid += plan.totalPaid
            months += plan
        }

        val done = bal.values.none { it > PAID_OFF_EPSILON }
        return PayoffPlan(
            strategy = strategy,
            startMonth = start,
            months = months,
            payoffMonthIndex = payoff,
            priorityOrder = priority,
            startBalance = startBalance,
            totalInterest = round2(totalInterest),
            totalPaid = round2(totalPaid),
            monthsToDebtFree = if (done) month else null,
            unpaidDebtIds = bal.filterValues { it > PAID_OFF_EPSILON }.keys,
            alreadyPaidIds = live.filter { (it.alreadyPaidThisMonth ?: -1.0) >= it.minPayment - PAID_OFF_EPSILON }.map { it.id }.toSet(),
        )
    }

    /**
     * Avalanche: highest APR first, revolving debt winning ties, then smaller balance.
     * Snowball: smallest balance first, higher APR winning ties.
     */
    fun order(debts: Collection<PlanDebt>, balances: Map<Long, Double>, strategy: Strategy): List<PlanDebt> {
        fun bal(d: PlanDebt) = balances[d.id] ?: d.balance
        return when (strategy) {
            Strategy.AVALANCHE -> debts.sortedWith(
                compareByDescending<PlanDebt> { it.apr }
                    .thenByDescending { it.revolving }
                    .thenBy { bal(it) }
                    .thenBy { it.id },
            )
            Strategy.SNOWBALL -> debts.sortedWith(
                compareBy<PlanDebt> { bal(it) }
                    .thenByDescending { it.apr }
                    .thenBy { it.id },
            )
        }
    }
}

fun DebtEntity.toPlanDebt(alreadyPaidThisMonth: Double? = null): PlanDebt =
    PlanDebt(id = id, name = name, revolving = debtType.revolving, balance = currentBalance, apr = apr, minPayment = minPayment, alreadyPaidThisMonth = alreadyPaidThisMonth)
