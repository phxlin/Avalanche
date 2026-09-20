package com.avalanche.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avalanche.app.data.AppSettings
import com.avalanche.app.data.DebtRepository
import com.avalanche.app.data.SettingsStore
import com.avalanche.app.data.isPaidOff
import com.avalanche.app.domain.BalancePoint
import com.avalanche.app.domain.DebtTotals
import com.avalanche.app.domain.PayoffPlan
import com.avalanche.app.domain.balanceHistory
import com.avalanche.app.domain.computeTotals
import com.avalanche.app.domain.paidThisMonthAmounts
import com.avalanche.app.ui.buildPlan
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class DashboardState(
    /** False until the database has answered, so the empty state doesn't flash on launch. */
    val loaded: Boolean = false,
    val debtCount: Int = 0,
    val activeCount: Int = 0,
    val totals: DebtTotals = DebtTotals(0.0, 0.0, 0f, 0.0),
    val plan: PayoffPlan? = null,
    val settings: AppSettings? = null,
    val history: List<BalancePoint> = emptyList(),
) {
    val isEmpty: Boolean get() = loaded && debtCount == 0
    val allPaidOff: Boolean get() = debtCount > 0 && activeCount == 0
}

class DashboardViewModel(repo: DebtRepository, settings: SettingsStore) : ViewModel() {
    val state: StateFlow<DashboardState> = combine(repo.debts, repo.payments, settings.settings) { debts, payments, s ->
        DashboardState(
            loaded = true,
            debtCount = debts.size,
            activeCount = debts.count { !it.isPaidOff },
            totals = computeTotals(debts, payments),
            plan = if (debts.any { !it.isPaidOff }) buildPlan(debts, s, paidThisMonth = paidThisMonthAmounts(debts, payments)) else null,
            settings = s,
            history = balanceHistory(debts, payments, LocalDate.now().toEpochDay()),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())
}
