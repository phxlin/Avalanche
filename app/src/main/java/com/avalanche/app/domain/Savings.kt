package com.avalanche.app.domain

import kotlin.math.pow

/**
 * The user's savings account (a HYSA) as the app models it: what's in it now, how much of their income goes into it
 * each month, and the rate it pays. All percentages are in points (4.5 means 4.5%).
 */
data class SavingsSetup(
    val balance: Double,
    val monthlyIncome: Double,
    /** Share of [monthlyIncome] saved each month, 0..[SavingsLimits.MAX_PERCENT]. */
    val percentSaved: Double,
    /** Annual percentage yield: what a year of interest adds, compounding included. */
    val apy: Double,
) {
    val monthlyContribution: Double get() = monthlyIncome * percentSaved / 100.0
}

/** The ranges the app accepts, shared by the form, the settings store and backup import. */
object SavingsLimits {
    const val MAX_PERCENT = 100.0
    const val MAX_APY = 25.0
    const val MAX_BALANCE = 1_000_000_000.0
    const val MAX_INCOME = 10_000_000.0

    /** The setup if every value is finite and in range, otherwise null. */
    fun validated(balance: Double?, monthlyIncome: Double?, percentSaved: Double?, apy: Double?): SavingsSetup? {
        if (balance == null || monthlyIncome == null || percentSaved == null || apy == null) return null
        val ok = balance.isFinite() && balance in 0.0..MAX_BALANCE &&
            monthlyIncome.isFinite() && monthlyIncome > 0.0 && monthlyIncome <= MAX_INCOME &&
            percentSaved.isFinite() && percentSaved in 0.0..MAX_PERCENT &&
            apy.isFinite() && apy in 0.0..MAX_APY
        return if (ok) SavingsSetup(balance, monthlyIncome, percentSaved, apy) else null
    }
}

/**
 * A "months of income saved" target on the way to a full cushion (1, 3, 6 or 12 months of what you take home).
 * [monthsUntilReached] counts from now: 0 once the balance is already there, null if it isn't reached within
 * [SavingsProjector.MILESTONE_HORIZON_MONTHS] at this pace.
 */
data class SavingsMilestone(
    val months: Int,
    val target: Double,
    /** Saved so far as a share of [target], 0..1. */
    val progress: Float,
    val monthsUntilReached: Int?,
) {
    val reached: Boolean get() = monthsUntilReached == 0
}

/** What the savings account looks like next to a debt payoff plan. */
data class SavingsProjection(
    val setup: SavingsSetup,
    /** Savings balance now (index 0) and at the end of each plan month; matches [PayoffPlan.balanceCurve]. Empty if the plan has no payoff date. */
    val curve: List<Double>,
    /** True when the savings already cover everything owed today. */
    val alreadyAhead: Boolean,
    /** 1-based plan month in which savings first reach the remaining debt, or null. Not set when [alreadyAhead]. */
    val crossoverMonthIndex: Int?,
    /** Savings balance in the month the plan reaches zero debt. Null if the plan has no payoff date. */
    val savedAtDebtFree: Double?,
    /** Interest the account earns between now and the debt-free month. */
    val interestEarnedAtDebtFree: Double?,
    /** What the minimums plus the extra amount come to, as a share of income (0.22 = 22%). */
    val debtPaymentShare: Double,
)

object SavingsProjector {
    /** The cushion sizes shown as milestones: one, three, six and twelve months of income. */
    val MILESTONE_MONTHS = listOf(1, 3, 6, 12)

    /** How far ahead a milestone is looked for: 50 years. Past that it counts as not reached. */
    const val MILESTONE_HORIZON_MONTHS = 600

    /** Half a cent of slack, so a balance that is exactly at the target isn't missed by rounding. */
    private const val REACHED_TOLERANCE = 0.005

    /**
     * The monthly rate that compounds to [apy] over twelve months. APY already includes compounding, so dividing it
     * by 12 would overstate the yield.
     */
    fun monthlyRate(apy: Double): Double = (1.0 + apy / 100.0).pow(1.0 / 12.0) - 1.0

    /**
     * Balance now (index 0) and at the end of each of the next [months] months. Each month the balance earns interest
     * first, then that month's saving is deposited at its end.
     */
    fun curve(setup: SavingsSetup, months: Int): List<Double> {
        val rate = monthlyRate(setup.apy)
        val out = ArrayList<Double>(months.coerceAtLeast(0) + 1)
        var balance = setup.balance
        out.add(balance)
        repeat(months.coerceAtLeast(0)) {
            balance = balance * (1.0 + rate) + setup.monthlyContribution
            out.add(balance)
        }
        return out
    }

    /** The milestones for [setup], smallest first. They depend only on income and the account, not on the debt plan. */
    fun milestones(setup: SavingsSetup, tiers: List<Int> = MILESTONE_MONTHS): List<SavingsMilestone> = tiers.map { months ->
        val target = setup.monthlyIncome * months
        SavingsMilestone(
            months = months,
            target = target,
            progress = (setup.balance / target).coerceIn(0.0, 1.0).toFloat(),
            monthsUntilReached = monthsUntil(setup, target),
        )
    }

    /**
     * Months from now until the balance reaches [target]: 0 if it already has, otherwise the first month whose end balance
     * does (the same month numbering as [curve]), or null if that takes longer than [MILESTONE_HORIZON_MONTHS].
     */
    fun monthsUntil(setup: SavingsSetup, target: Double): Int? {
        if (setup.balance >= target - REACHED_TOLERANCE) return 0
        val rate = monthlyRate(setup.apy)
        var balance = setup.balance
        for (month in 1..MILESTONE_HORIZON_MONTHS) {
            balance = balance * (1.0 + rate) + setup.monthlyContribution
            if (balance >= target - REACHED_TOLERANCE) return month
        }
        return null
    }

    /**
     * Lines the savings up against [plan] month by month. [monthlyDebtPayment] is what goes to debt each month
     * (minimums plus extra), only used to say how big a share of income it is.
     */
    fun project(setup: SavingsSetup, plan: PayoffPlan, monthlyDebtPayment: Double): SavingsProjection {
        val share = monthlyDebtPayment / setup.monthlyIncome
        val debtCurve = plan.balanceCurve()
        if (debtCurve.isEmpty()) {
            return SavingsProjection(setup, emptyList(), setup.balance >= plan.startBalance && setup.balance > 0.0, null, null, null, share)
        }
        val savings = curve(setup, debtCurve.size - 1)
        val ahead = { i: Int -> savings[i] > 0.0 && savings[i] >= debtCurve[i] }
        val alreadyAhead = ahead(0)
        val crossover = if (alreadyAhead) null else (1 until debtCurve.size).firstOrNull(ahead)
        val last = savings.lastIndex
        return SavingsProjection(
            setup = setup,
            curve = savings,
            alreadyAhead = alreadyAhead,
            crossoverMonthIndex = crossover,
            savedAtDebtFree = savings[last],
            interestEarnedAtDebtFree = savings[last] - setup.balance - setup.monthlyContribution * last,
            debtPaymentShare = share,
        )
    }
}
