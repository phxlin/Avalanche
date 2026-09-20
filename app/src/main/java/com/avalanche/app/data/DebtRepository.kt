package com.avalanche.app.data

import androidx.room.withTransaction
import com.avalanche.app.domain.PaymentMath
import com.avalanche.app.domain.estimateInterestPaid
import com.avalanche.app.domain.monthCode
import com.avalanche.app.domain.originalFromPercentPaid
import com.avalanche.app.domain.percentPaidOf
import com.avalanche.app.domain.round2
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.Flow

data class Allocation(val debtId: Long, val amount: Double)

data class LogResult(val paymentCount: Int, val totalPaid: Double, val paidOffNames: List<String>)

/** A payment was dated before [lastEntry], the debt's most recent entry. */
class BackdatedPaymentException(val debtName: String, val lastEntry: LocalDate) :
    Exception("$debtName already has an entry on $lastEntry; payments can't be dated before it.")

class DebtRepository(
    private val db: AppDatabase,
    private val settings: SettingsStore,
    private val onDebtPaidOff: (String) -> Unit,
    /** Injectable so date-dependent behaviour (interest accrual, rate changes) can be tested. */
    private val clock: () -> LocalDate = { LocalDate.now() },
) {
    private val debtDao = db.debtDao()
    private val paymentDao = db.paymentDao()

    val debts: Flow<List<DebtEntity>> = debtDao.observeAll()
    val payments: Flow<List<PaymentEntity>> = paymentDao.observeAll()

    fun debt(id: Long): Flow<DebtEntity?> = debtDao.observe(id)
    fun paymentsFor(debtId: Long): Flow<List<PaymentEntity>> = paymentDao.observeForDebt(debtId)

    suspend fun getDebt(id: Long): DebtEntity? = debtDao.get(id)
    suspend fun hasHistory(debtId: Long): Boolean = paymentDao.countForDebt(debtId) > 0
    suspend fun hasActiveDebts(): Boolean = debtDao.countActive() > 0

    /**
     * [percentPaid] lets someone add a debt they have already been paying down: the original balance
     * is derived from the current one, so progress starts at that percentage instead of 0%. The interest
     * already paid on the way there is estimated from the minimum payment and APR.
     */
    suspend fun addDebt(
        name: String, type: DebtType, balance: Double, apr: Double, minPayment: Double, percentPaid: Double = 0.0,
        paidThisMonth: Boolean = false,
    ): Long {
        val today = clock().toEpochDay()
        val start = round2(balance)
        val original = originalFromPercentPaid(start, percentPaid)
        return debtDao.insert(
            DebtEntity(
                name = name.trim(),
                type = type.name,
                originalBalance = original,
                currentBalance = start,
                apr = apr,
                minPayment = round2(minPayment),
                createdDay = today,
                lastAccrualDay = today,
                priorInterestPaid = priorInterest(original, start, apr, minPayment),
                paidMonth = if (paidThisMonth) thisMonthCode() else null,
            ),
        )
    }

    private fun thisMonthCode(): Int = monthCode(YearMonth.from(clock()))

    /** Estimated interest paid while the balance fell from [original] to [startBalance]; 0 if it can't be estimated. */
    private fun priorInterest(original: Double, startBalance: Double, apr: Double, minPayment: Double): Double =
        estimateInterestPaid(original, startBalance, apr, minPayment)?.interest?.let(::round2) ?: 0.0

    /**
     * Edits a debt. Correcting the balance of a debt with no history simply fixes the original entry
     * (progress restarts from the corrected figure). Once payments exist, a balance change is recorded
     * as an adjustment row so history stays truthful, and the original balance only ever grows
     * (new charges shouldn't produce negative progress).
     *
     * [percentPaid] is the value of the form's "already paid off %" field (null if there is none). On a debt
     * with no history it always defines the original balance. Once history exists it only does so if it
     * differs from the percentage the debt currently shows, so re-saving an untouched form leaves the usual
     * adjustment rules in charge. When it applies it wins over both of those rules, and the estimated interest
     * paid before tracking began is recomputed too, up to the balance the app started tracking from.
     *
     * Changing the APR first settles the interest accrued at the *old* rate into the balance (recorded as
     * an adjustment), so the next payment doesn't reprice the whole unpaid stretch at the new rate. If the
     * caller also changes the balance, their figure wins: it is assumed to come from a statement. APR
     * differences below the form's precision ([APR_EPSILON]) aren't rate changes and leave the stored value alone.
     *
     * [paidThisMonth] sets or clears the "this month's payment is already made" marker; null leaves it as it is.
     */
    suspend fun updateDebt(
        id: Long, name: String, type: DebtType, balance: Double, apr: Double, minPayment: Double,
        percentPaid: Double? = null,
        paidThisMonth: Boolean? = null,
    ) {
        db.withTransaction {
            val d = debtDao.get(id) ?: return@withTransaction
            val today = clock().toEpochDay()
            val newBalance = round2(balance)
            val newMin = round2(minPayment)
            val hasHistory = paymentDao.countForDebt(id) > 0
            val startBalance = paymentDao.earliestForDebt(id)?.balanceBefore ?: newBalance
            val balanceChanged = abs(newBalance - d.currentBalance) > 0.004
            val aprChanged = abs(apr - d.apr) > APR_EPSILON
            val newApr = if (aprChanged) apr else d.apr
            // Every rate change on a live debt is recorded (even when nothing accrued, such as the end of a 0%
            // promo): the entry moves the accrual point to today, so the new rate only applies from now on, and
            // it lets undo step back through rate changes and payments in order.
            val rateChangedWhileActive = aprChanged && !d.isPaidOff
            val settledInterest =
                if (!balanceChanged && rateChangedWhileActive) {
                    PaymentMath.accruedInterest(d.currentBalance, d.apr, max(0L, today - d.lastAccrualDay))
                } else {
                    0.0
                }
            val originalOverride = percentPaid
                ?.takeIf { newBalance > 0.0 && (!hasHistory || abs(it - shownPercent(d)) > SHOWN_PERCENT_TOLERANCE) }
                ?.let { originalFromPercentPaid(newBalance, it) }

            var updated = d.copy(
                name = name.trim(), type = type.name, apr = newApr, minPayment = newMin,
                paidMonth = when (paidThisMonth) {
                    null -> d.paidMonth
                    true -> thisMonthCode()
                    false -> null
                },
            )
            if (originalOverride != null) {
                updated = updated.copy(
                    originalBalance = originalOverride,
                    priorInterestPaid = if ((percentPaid ?: 0.0) > 0.0) priorInterest(originalOverride, startBalance, newApr, newMin) else 0.0,
                )
            }
            if (balanceChanged && !hasHistory) {
                // A typed balance is "as of today" (often a statement figure that already includes accrued
                // interest), so interest accrues from today rather than from when the debt was added.
                updated = updated.copy(
                    originalBalance = originalOverride ?: newBalance,
                    currentBalance = newBalance,
                    lastAccrualDay = today,
                )
            } else if (balanceChanged || settledInterest >= 0.005 || rateChangedWhileActive) {
                val adjusted = if (balanceChanged) newBalance else round2(d.currentBalance + settledInterest)
                paymentDao.insert(
                    PaymentEntity(
                        sessionId = System.currentTimeMillis(),
                        debtId = id,
                        day = today,
                        amount = 0.0,
                        interestPortion = 0.0,
                        principalPortion = 0.0,
                        balanceBefore = d.currentBalance,
                        balanceAfter = adjusted,
                        prevAccrualDay = d.lastAccrualDay,
                        prevOriginalBalance = d.originalBalance,
                        prevPaidOffDay = d.paidOffDay,
                        kind = KIND_ADJUSTMENT,
                        prevApr = if (aprChanged) d.apr else null,
                    ),
                )
                updated = updated.copy(
                    currentBalance = adjusted,
                    originalBalance = originalOverride ?: max(d.originalBalance, adjusted),
                    lastAccrualDay = today,
                    paidOffDay = if (adjusted <= PAID_OFF_EPSILON) d.paidOffDay ?: today else null,
                )
            }
            debtDao.update(updated)
        }
    }

    suspend fun deleteDebt(id: Long) = debtDao.delete(id)

    suspend fun deleteAll() = db.withTransaction {
        paymentDao.deleteAll()
        debtDao.deleteAll()
    }

    /**
     * Logs one payment session, possibly split across several debts. Amounts above a debt's payoff amount are capped.
     *
     * A payment can't be dated before a debt's last entry (its accrual point): balances and interest are applied in
     * the order entries are logged, so an earlier date would put the running balance and the dated history out of
     * step. Throws [BackdatedPaymentException] for the first such debt; nothing is saved in that case.
     */
    suspend fun logPayments(day: LocalDate, allocations: List<Allocation>): LogResult {
        val epochDay = day.toEpochDay()
        val sessionId = System.currentTimeMillis()
        val paidOff = mutableListOf<String>()
        var count = 0
        var total = 0.0
        db.withTransaction {
            for ((debtId, requested) in allocations) {
                if (requested <= 0.0) continue
                val d = debtDao.get(debtId) ?: continue
                if (d.isPaidOff) continue
                if (epochDay < d.lastAccrualDay) throw BackdatedPaymentException(d.name, LocalDate.ofEpochDay(d.lastAccrualDay))
                val a = PaymentMath.apply(d.currentBalance, d.apr, d.lastAccrualDay, epochDay, requested)
                if (a.amount <= 0.0) continue
                paymentDao.insert(
                    PaymentEntity(
                        sessionId = sessionId,
                        debtId = debtId,
                        day = epochDay,
                        amount = a.amount,
                        interestPortion = a.interest,
                        principalPortion = a.principal,
                        balanceBefore = d.currentBalance,
                        balanceAfter = a.balanceAfter,
                        prevAccrualDay = d.lastAccrualDay,
                        prevOriginalBalance = d.originalBalance,
                        prevPaidOffDay = d.paidOffDay,
                    ),
                )
                val cleared = a.balanceAfter <= PAID_OFF_EPSILON
                debtDao.update(
                    d.copy(
                        currentBalance = a.balanceAfter,
                        lastAccrualDay = a.newAccrualDay,
                        paidOffDay = if (cleared) epochDay else null,
                    ),
                )
                count++
                total += a.amount
                if (cleared) paidOff += d.name
            }
        }
        if (settings.settings.value.celebrationsEnabled) paidOff.forEach(onDebtPaidOff)
        return LogResult(count, round2(total), paidOff)
    }

    /**
     * Reverts the most recently inserted payment or adjustment for a debt, restoring its prior balance,
     * accrual point and paid-off state.
     *
     * The original balance is only rolled back if an adjustment itself moved it (new charges raise it) and
     * nothing has changed it since. Payments never touch it, and an explicit "already paid off %" edit made
     * after the entry must survive the undo.
     *
     * An adjustment that came with a rate change also puts the previous APR back. Otherwise the interest it
     * settled at the old rate would be charged again, at the new one, for the stretch it covered.
     */
    suspend fun undoLatest(debtId: Long): Boolean = db.withTransaction {
        val p = paymentDao.latestForDebt(debtId) ?: return@withTransaction false
        val d = debtDao.get(debtId) ?: return@withTransaction false
        val originalLeftByEntry = max(p.prevOriginalBalance, p.balanceAfter)
        val original = if (p.isAdjustment && abs(d.originalBalance - originalLeftByEntry) < 0.005) p.prevOriginalBalance else d.originalBalance
        debtDao.update(
            d.copy(
                currentBalance = p.balanceBefore,
                originalBalance = original,
                apr = p.prevApr ?: d.apr,
                lastAccrualDay = p.prevAccrualDay,
                paidOffDay = p.prevPaidOffDay,
            ),
        )
        paymentDao.delete(p.id)
        true
    }

    /** The percentage the debt shows in the edit form: rounded to two decimals, and never 100. */
    private fun shownPercent(d: DebtEntity): Double = Math.round(min(99.99, percentPaidOf(d.originalBalance, d.currentBalance)) * 100) / 100.0

    companion object {
        /** The APR field has three decimals; anything closer than half of that is the same rate. */
        const val APR_EPSILON = 0.0005

        /** A typed percentage within this of the shown (rounded) one hasn't been edited. */
        private const val SHOWN_PERCENT_TOLERANCE = 0.005 + 1e-9
    }

    suspend fun exportJson(): String = Backup.toJson(debtDao.getAll(), paymentDao.getAll(), settings.settings.value)

    /** Replaces all local data with the backup's contents. Throws [BackupException] if the file is invalid; nothing changes in that case. */
    suspend fun importJson(json: String) {
        val parsed = Backup.parse(json)
        db.withTransaction {
            paymentDao.deleteAll()
            debtDao.deleteAll()
            debtDao.insertAll(parsed.debts)
            paymentDao.insertAll(parsed.payments)
        }
        settings.update { Backup.applyTo(it, parsed.settings) }
    }
}
