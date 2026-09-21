package com.avalanche.app.ui.plan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avalanche.app.data.AppSettings
import com.avalanche.app.data.DebtEntity
import com.avalanche.app.data.DebtRepository
import com.avalanche.app.data.SettingsStore
import com.avalanche.app.data.isPaidOff
import com.avalanche.app.domain.LumpSum
import com.avalanche.app.domain.PayoffPlan
import com.avalanche.app.domain.SavingsProjection
import com.avalanche.app.domain.SavingsProjector
import com.avalanche.app.domain.SavingsSetup
import com.avalanche.app.domain.Strategy
import com.avalanche.app.domain.paidThisMonthAmounts
import com.avalanche.app.ui.buildPlan
import com.avalanche.app.ui.monthlyDebtBudget
import com.avalanche.app.util.editableNumber
import com.avalanche.app.util.parseDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What a one-time lump sum would change, compared with the plan without it. */
data class LumpPreview(
    val amount: Double,
    /** Total actually applied; less than [amount] if it exceeds everything owed. */
    val applied: Double,
    val targetDebtId: Long?,
    val plan: PayoffPlan,
    val monthsSooner: Int?,
    val interestSaved: Double?,
    val allocation: Map<Long, Double>,
    val putsPlanOnTrack: Boolean,
)

data class PlanUiState(
    val loaded: Boolean = false,
    val activeDebts: List<DebtEntity> = emptyList(),
    val hasAnyDebt: Boolean = false,
    val settings: AppSettings? = null,
    val selected: PayoffPlan? = null,
    val avalanche: PayoffPlan? = null,
    val snowball: PayoffPlan? = null,
    val lump: LumpPreview? = null,
    /** The savings account lined up with [selected]; null until savings are set up. */
    val savings: SavingsProjection? = null,
)

class PlanViewModel(repo: DebtRepository, private val settingsStore: SettingsStore) : ViewModel() {
    var extraText by mutableStateOf(editableNumber(settingsStore.settings.value.extraMonthly))
        private set
    var lumpText by mutableStateOf("")
        private set
    /** null = auto-apply to the highest-APR debt. */
    var lumpTarget by mutableStateOf<Long?>(null)
        private set

    init {
        // The amount can also change outside this screen (a backup import). Follow it, but leave the field alone
        // while its text already means the same amount, so typing "12." isn't rewritten to "12".
        viewModelScope.launch {
            settingsStore.settings.map { it.extraMonthly }.distinctUntilChanged().collect { extra ->
                if (abs((parseDecimal(extraText) ?: 0.0) - extra) > 0.005) extraText = editableNumber(extra)
            }
        }
    }

    val state: StateFlow<PlanUiState> = combine(
        repo.debts, repo.payments, settingsStore.settings, snapshotFlow { lumpText }, snapshotFlow { lumpTarget },
    ) { debts, payments, s, lumpInput, target ->
        val active = debts.filter { !it.isPaidOff }
        if (active.isEmpty()) return@combine PlanUiState(loaded = true, hasAnyDebt = debts.isNotEmpty(), settings = s)
        val paid = paidThisMonthAmounts(debts, payments)
        val selected = buildPlan(debts, s, paidThisMonth = paid)
        val avalanche = if (s.strategy == Strategy.AVALANCHE) selected else buildPlan(debts, s, Strategy.AVALANCHE, paidThisMonth = paid)
        val snowball = if (s.strategy == Strategy.SNOWBALL) selected else buildPlan(debts, s, Strategy.SNOWBALL, paidThisMonth = paid)
        PlanUiState(
            loaded = true,
            activeDebts = active,
            hasAnyDebt = true,
            settings = s,
            selected = selected,
            avalanche = avalanche,
            snowball = snowball,
            lump = lumpPreview(debts, s, selected, parseDecimal(lumpInput) ?: 0.0, target, paid),
            savings = s.savings?.let { SavingsProjector.project(it, selected, monthlyDebtBudget(debts, s.extraMonthly)) },
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanUiState())

    private fun lumpPreview(
        debts: List<DebtEntity>, s: AppSettings, base: PayoffPlan, amount: Double, target: Long?, paidThisMonth: Map<Long, Double>,
    ): LumpPreview? {
        if (amount <= 0.0) return null
        val validTarget = target?.takeIf { id -> debts.any { it.id == id && !it.isPaidOff } }
        val plan = buildPlan(debts, s, s.strategy, LumpSum(amount, validTarget), paidThisMonth)
        val allocation = plan.lumpAllocation()
        val bothConverge = base.converges && plan.converges
        return LumpPreview(
            amount = amount,
            applied = allocation.values.sum(),
            targetDebtId = validTarget,
            plan = plan,
            monthsSooner = if (bothConverge) (base.monthsToDebtFree ?: 0) - (plan.monthsToDebtFree ?: 0) else null,
            interestSaved = if (bothConverge) base.totalInterest - plan.totalInterest else null,
            allocation = allocation,
            putsPlanOnTrack = !base.converges && plan.converges,
        )
    }

    fun setStrategy(strategy: Strategy) = settingsStore.update { it.copy(strategy = strategy) }

    fun onExtraChanged(text: String) {
        extraText = text
        settingsStore.update { it.copy(extraMonthly = parseDecimal(text)?.coerceAtLeast(0.0) ?: 0.0) }
    }

    fun saveSavings(setup: SavingsSetup) = settingsStore.update { it.copy(savings = setup) }

    fun removeSavings() = settingsStore.update { it.copy(savings = null) }

    fun onLumpChanged(text: String) {
        lumpText = text
    }

    fun onLumpTargetChanged(debtId: Long?) {
        lumpTarget = debtId
    }
}
