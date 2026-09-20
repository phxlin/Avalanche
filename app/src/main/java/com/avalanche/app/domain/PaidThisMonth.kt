package com.avalanche.app.domain

import com.avalanche.app.data.DebtEntity
import com.avalanche.app.data.PAID_OFF_EPSILON
import com.avalanche.app.data.PaymentEntity
import com.avalanche.app.data.isAdjustment
import com.avalanche.app.data.isPaidOff
import java.time.YearMonth

/** A calendar month as one number, for storing which month a debt was marked as paid. */
fun monthCode(month: YearMonth): Int = month.year * 12 + month.monthValue - 1

/** What has been logged as paid on the debt in [month]. Adjustments aren't payments. */
fun loggedPaidInMonth(debt: DebtEntity, payments: List<PaymentEntity>, month: YearMonth): Double {
    val first = month.atDay(1).toEpochDay()
    val last = month.atEndOfMonth().toEpochDay()
    return payments.filter { it.debtId == debt.id && !it.isAdjustment && it.day in first..last }.sumOf { it.amount }
}

/** True when the payments logged in [month] already add up to the debt's minimum. */
fun paidByLoggedPayments(debt: DebtEntity, payments: List<PaymentEntity>, month: YearMonth): Boolean =
    !debt.isPaidOff && debt.minPayment > 0.0 && loggedPaidInMonth(debt, payments, month) >= debt.minPayment - PAID_OFF_EPSILON

/**
 * Whether this month's payment on the debt is already made, either because the user said so (the checkbox, which
 * only counts for the month it was set in) or because the payments logged this month cover the minimum.
 */
fun isPaidThisMonth(debt: DebtEntity, payments: List<PaymentEntity>, month: YearMonth): Boolean =
    !debt.isPaidOff && (debt.paidMonth == monthCode(month) || paidByLoggedPayments(debt, payments, month))

/**
 * How much has been paid on each debt this month, for every debt with anything paid: the payments logged this month,
 * or (if the checkbox is ticked and the debt is still open) its minimum, whichever is larger. This includes part
 * payments that don't yet cover the minimum, and debts already cleared this month, because that money is spent
 * either way. Debts with nothing paid aren't in the map.
 */
fun paidThisMonthAmounts(debts: List<DebtEntity>, payments: List<PaymentEntity>, month: YearMonth = YearMonth.now()): Map<Long, Double> =
    debts.mapNotNull { d ->
        val logged = loggedPaidInMonth(d, payments, month)
        val ticked = if (!d.isPaidOff && d.paidMonth == monthCode(month)) d.minPayment else 0.0
        val amount = maxOf(logged, ticked)
        if (amount > 0.0) d.id to amount else null
    }.toMap()

/** The debts whose payment for the month is fully made (ticked, or logged payments cover the minimum). */
fun paidThisMonthIds(debts: List<DebtEntity>, payments: List<PaymentEntity>, month: YearMonth = YearMonth.now()): Set<Long> =
    debts.filter { isPaidThisMonth(it, payments, month) }.map { it.id }.toSet()