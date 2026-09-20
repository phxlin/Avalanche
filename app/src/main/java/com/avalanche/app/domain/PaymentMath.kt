package com.avalanche.app.domain

import com.avalanche.app.data.PAID_OFF_EPSILON
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

fun round2(value: Double): Double = (value * 100.0).roundToLong() / 100.0

/**
 * Splits a logged payment into interest and principal.
 *
 * Interest accrues simply by the day at the debt's APR since the last accrual point (the last
 * payment, balance adjustment, or the day the debt was added). A payment covers accrued interest
 * first; whatever is left reduces principal. If a payment is smaller than the accrued interest,
 * the shortfall is capitalised into the balance.
 */
object PaymentMath {
    data class Applied(
        val amount: Double,
        val interest: Double,
        val principal: Double,
        val balanceAfter: Double,
        val newAccrualDay: Long,
    )

    fun accruedInterest(balance: Double, aprPercent: Double, days: Long): Double =
        if (days <= 0 || balance <= PAID_OFF_EPSILON || aprPercent <= 0.0) 0.0
        else round2(balance * aprPercent / 100.0 * days / 365.0)

    /** Cash needed on [payDay] to clear the debt completely. */
    fun payoffAmount(balance: Double, aprPercent: Double, lastAccrualDay: Long, payDay: Long): Double {
        val interest = accruedInterest(balance, aprPercent, max(0L, payDay - lastAccrualDay))
        return round2(balance + interest)
    }

    fun apply(balance: Double, aprPercent: Double, lastAccrualDay: Long, payDay: Long, requested: Double): Applied {
        val interestDue = accruedInterest(balance, aprPercent, max(0L, payDay - lastAccrualDay))
        val owed = round2(balance + interestDue)
        val amount = min(round2(requested), owed)
        val interestPaid = min(amount, interestDue)
        val principal = round2(amount - interestPaid)
        val after = round2(owed - amount)
        return Applied(
            amount = amount,
            interest = interestPaid,
            principal = principal,
            balanceAfter = if (after <= PAID_OFF_EPSILON) 0.0 else after,
            newAccrualDay = max(lastAccrualDay, payDay),
        )
    }
}
