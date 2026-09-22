package com.avalanche.app.domain

import kotlin.math.abs

/**
 * How the strategy in use compares with the other one on total interest. [saved] is what [chosen] saves against [other]:
 * positive when it costs less interest, negative when it costs more.
 */
data class InterestVerdict(val chosen: Strategy, val other: Strategy, val saved: Double) {
    val isAboutTheSame: Boolean get() = abs(saved) < ABOUT_THE_SAME

    companion object {
        /** Differences under this many currency units read as "about the same". */
        const val ABOUT_THE_SAME = 0.5
    }
}

/** Null when either plan never reaches zero, since their interest totals can't be compared. */
fun interestVerdict(chosen: Strategy, avalanche: PayoffPlan, snowball: PayoffPlan): InterestVerdict? {
    if (!avalanche.converges || !snowball.converges) return null
    val (mine, theirs) = if (chosen == Strategy.AVALANCHE) avalanche to snowball else snowball to avalanche
    return InterestVerdict(chosen, theirs.strategy, theirs.totalInterest - mine.totalInterest)
}
