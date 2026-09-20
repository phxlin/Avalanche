package com.avalanche.app.ui.debts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avalanche.app.data.DebtEntity
import com.avalanche.app.data.DebtRepository
import com.avalanche.app.data.DebtType
import com.avalanche.app.data.PaymentEntity
import com.avalanche.app.data.SettingsStore
import com.avalanche.app.data.debtType
import com.avalanche.app.data.isPaidOff
import com.avalanche.app.domain.BalancePoint
import com.avalanche.app.domain.InterestEstimate
import com.avalanche.app.domain.PaymentMath
import com.avalanche.app.domain.PayoffCalculator
import com.avalanche.app.domain.Strategy
import com.avalanche.app.domain.balanceHistory
import com.avalanche.app.domain.estimateInterestPaid
import com.avalanche.app.domain.monthCode
import com.avalanche.app.domain.originalFromPercentPaid
import com.avalanche.app.domain.paidByLoggedPayments
import com.avalanche.app.domain.paidThisMonthAmounts
import com.avalanche.app.domain.paidThisMonthIds
import com.avalanche.app.domain.percentPaidOf
import com.avalanche.app.domain.round2
import com.avalanche.app.domain.toPlanDebt
import com.avalanche.app.ui.buildPlan
import com.avalanche.app.util.parseDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DebtListState(
    val loaded: Boolean = false,
    val items: List<DebtEntity> = emptyList(),
    /** The debt extra money is aimed at first; only shown when there's more than one active debt. */
    val focusId: Long? = null,
    /** Debts whose payment for the current month is already made. */
    val paidThisMonth: Set<Long> = emptySet(),
)

class DebtListViewModel(repo: DebtRepository, settings: SettingsStore) : ViewModel() {
    val state: StateFlow<DebtListState> = combine(repo.debts, repo.payments, settings.settings) { debts, payments, s ->
        val active = debts.filter { !it.isPaidOff }
        val byId = active.associateBy { it.id }
        val order = PayoffCalculator.order(active.map { it.toPlanDebt() }, emptyMap(), s.strategy).map { it.id }
        DebtListState(
            loaded = true,
            items = order.map { byId.getValue(it) } + debts.filter { it.isPaidOff },
            focusId = if (active.size >= 2) order.first() else null,
            paidThisMonth = paidThisMonthIds(debts, payments),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtListState())
}

data class DebtDetailState(
    val loaded: Boolean = false,
    val debt: DebtEntity? = null,
    val payments: List<PaymentEntity> = emptyList(),
    val interestPaid: Double = 0.0,
    val history: List<BalancePoint> = emptyList(),
    val strategy: Strategy = Strategy.AVALANCHE,
    val planPayoff: YearMonth? = null,
    val planConverges: Boolean = true,
    val minOnlyMonths: Int? = null,
    val minOnlyPayoff: YearMonth? = null,
    /** Interest that one month at the current balance would add. */
    val monthlyInterest: Double = 0.0,
    val latestPaymentId: Long? = null,
)

class DebtDetailViewModel(private val repo: DebtRepository, settings: SettingsStore, private val debtId: Long) : ViewModel() {
    val state: StateFlow<DebtDetailState> = combine(
        repo.debt(debtId), repo.paymentsFor(debtId), repo.debts, repo.payments, settings.settings,
    ) { debt, payments, allDebts, allPayments, s ->
        if (debt == null) return@combine DebtDetailState(loaded = true)
        val paid = paidThisMonthAmounts(allDebts, allPayments)
        val plan = buildPlan(allDebts, s, paidThisMonth = paid)
        val minOnly = PayoffCalculator.simulate(listOf(debt.toPlanDebt(paid[debt.id])), 0.0, Strategy.AVALANCHE)
        DebtDetailState(
            loaded = true,
            debt = debt,
            payments = payments,
            interestPaid = payments.sumOf { it.interestPortion } + debt.priorInterestPaid,
            history = balanceHistory(listOf(debt), payments, LocalDate.now().toEpochDay()),
            strategy = s.strategy,
            planPayoff = plan.payoffMonth(debt.id),
            planConverges = debt.isPaidOff || plan.payoffMonthIndex.containsKey(debt.id),
            minOnlyMonths = minOnly.monthsToDebtFree,
            minOnlyPayoff = minOnly.debtFreeMonth,
            monthlyInterest = debt.currentBalance * debt.apr / 1200.0,
            latestPaymentId = payments.maxOfOrNull { it.id },
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebtDetailState())

    fun delete() {
        viewModelScope.launch { repo.deleteDebt(debtId) }
    }

    fun undoLatest() {
        viewModelScope.launch { repo.undoLatest(debtId) }
    }
}

/** Form state for adding (debtId == null) or editing a debt. Lives in the ViewModel so it survives rotation. */
class EditDebtViewModel(private val repo: DebtRepository, private val debtId: Long?) : ViewModel() {
    var name by mutableStateOf("")
    var type by mutableStateOf(DebtType.CREDIT_CARD)
    var balance by mutableStateOf("")
    var apr by mutableStateOf("")
    var minPayment by mutableStateOf("")
    /** Optional: how much of the original balance is already paid off, in percent. */
    var percentPaid by mutableStateOf("")
    /** The checkbox: this month's payment on the debt is already made (so the balance above is after it). */
    var paidThisMonth by mutableStateOf(false)
    /** True when payments logged this month already cover the minimum, so the checkbox isn't needed. */
    var coveredByLoggedPayments by mutableStateOf(false)
        private set
    var loading by mutableStateOf(debtId != null)
        private set
    var saving by mutableStateOf(false)
        private set
    private var hasHistory by mutableStateOf(false)
    private var loadedDebt by mutableStateOf<DebtEntity?>(null)

    /** Set when the last save failed (for example a database error); cleared on the next attempt. */
    var saveError by mutableStateOf<String?>(null)
        private set

    /** Errors are only shown once the user has tried to save. */
    var showErrors by mutableStateOf(false)
        private set

    val isEditing: Boolean get() = debtId != null
    val editingWithHistory: Boolean get() = isEditing && hasHistory

    init {
        if (debtId != null) {
            viewModelScope.launch {
                repo.getDebt(debtId)?.let { d ->
                    name = d.name
                    type = d.debtType
                    balance = com.avalanche.app.util.editableNumber(d.currentBalance)
                    apr = com.avalanche.app.util.editableNumber(d.apr, maxDecimals = APR_DECIMALS).ifEmpty { "0" }
                    minPayment = com.avalanche.app.util.editableNumber(d.minPayment).ifEmpty { "0" }
                    percentPaid = com.avalanche.app.util.editableNumber(Math.round(minOf(99.99, percentPaidOf(d.originalBalance, d.currentBalance)) * 100) / 100.0)
                    val month = YearMonth.now()
                    coveredByLoggedPayments = paidByLoggedPayments(d, repo.payments.first(), month)
                    paidThisMonth = coveredByLoggedPayments || d.paidMonth == monthCode(month)
                    hasHistory = repo.hasHistory(debtId)
                    loadedDebt = d
                }
                loading = false
            }
        }
    }

    val nameError: String?
        get() = when {
            name.isBlank() -> "Give this debt a name"
            name.trim().length > 40 -> "Keep the name under 40 characters"
            else -> null
        }

    val balanceError: String?
        get() {
            val v = parseDecimal(balance)
            val min = if (editingWithHistory) 0.0 else 0.01
            return if (v == null || v < min) (if (editingWithHistory) "Enter the current balance (0 if paid off)" else "Enter the current balance") else null
        }

    val aprError: String?
        get() {
            val v = parseDecimal(apr)
            return when {
                v == null -> "Enter the APR (0 if there's no interest)"
                v > 100 -> "APR must be between 0 and 100"
                else -> null
            }
        }

    val minPaymentError: String?
        get() = if (parseDecimal(minPayment) == null) "Enter the minimum payment (0 if none)" else null

    /** Blank means 0%. Anything else must be a number from 0 up to (but not including) 100. */
    val percentPaidError: String?
        get() {
            if (percentPaid.isBlank()) return null
            val v = parseDecimal(percentPaid)
            return if (v == null || v < 0.0 || v >= 100.0) "Enter a percentage from 0 to 99.99" else null
        }

    /** What the original balance works out to, shown as live feedback while typing. */
    val impliedOriginal: Double?
        get() {
            val pct = parseDecimal(percentPaid) ?: return null
            val b = parseDecimal(balance) ?: return null
            return if (pct > 0.0 && pct < 100.0 && b > 0.0) originalFromPercentPaid(b, pct) else null
        }

    /** Live preview of the estimated interest already paid; [estimate] is null when it can't be worked out. */
    class InterestPreview(val estimate: InterestEstimate?)

    /**
     * Only offered once there's a percentage, balance, APR and minimum to work from. For a debt that already
     * has logged history the repository does the calculation against the tracked starting balance, so no preview here.
     */
    val interestPreview: InterestPreview?
        get() {
            val original = impliedOriginal ?: return null
            if (editingWithHistory) return null
            val b = parseDecimal(balance) ?: return null
            val a = parseDecimal(apr) ?: return null
            val m = parseDecimal(minPayment) ?: return null
            return InterestPreview(estimateInterestPaid(original, b, a, m))
        }

    /**
     * When editing, changing only the APR settles the interest accrued so far at the old rate into the balance.
     * This is that amount, so the form can say so before saving. Null when it doesn't apply.
     */
    val aprChangeSettlement: Double?
        get() {
            val d = loadedDebt ?: return null
            if (d.isPaidOff) return null
            val newApr = parseDecimal(apr) ?: return null
            val newBalance = parseDecimal(balance) ?: return null
            if (abs(newApr - d.apr) <= DebtRepository.APR_EPSILON || abs(round2(newBalance) - d.currentBalance) > 0.004) return null
            val days = max(0L, LocalDate.now().toEpochDay() - d.lastAccrualDay)
            return PaymentMath.accruedInterest(d.currentBalance, d.apr, days).takeIf { it >= 0.005 }
        }

    private val isValid: Boolean get() = listOf(nameError, balanceError, aprError, minPaymentError, percentPaidError).all { it == null }

    fun save(onDone: () -> Unit) {
        showErrors = true
        if (!isValid || saving) return
        saving = true
        saveError = null
        viewModelScope.launch {
            val b = parseDecimal(balance) ?: 0.0
            val a = parseDecimal(apr) ?: 0.0
            val m = parseDecimal(minPayment) ?: 0.0
            val pct = parseDecimal(percentPaid) ?: 0.0
            val saved = try {
                if (debtId == null) {
                    repo.addDebt(name, type, b, a, m, pct, paidThisMonth)
                } else {
                    // Logged payments already count on their own; only the manual checkbox is stored.
                    repo.updateDebt(debtId, name, type, b, a, m, pct, paidThisMonth && !coveredByLoggedPayments)
                }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveError = "Couldn't save this debt. Please try again."
                false
            } finally {
                saving = false
            }
            if (saved) onDone()
        }
    }

    companion object {
        /** The APR field accepts this many decimals; prefilling must not round below it. */
        const val APR_DECIMALS = 3
    }
}
