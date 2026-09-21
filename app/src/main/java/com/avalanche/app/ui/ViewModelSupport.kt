package com.avalanche.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.avalanche.app.AppContainer
import com.avalanche.app.AvalancheApplication
import com.avalanche.app.data.AppSettings
import com.avalanche.app.data.DebtEntity
import com.avalanche.app.domain.LumpSum
import com.avalanche.app.domain.PayoffCalculator
import com.avalanche.app.domain.PayoffPlan
import com.avalanche.app.domain.Strategy
import com.avalanche.app.domain.toPlanDebt

@Composable
fun rememberContainer(): AppContainer = (LocalContext.current.applicationContext as AvalancheApplication).container

/** Gets (or creates, from the app's container) the screen's ViewModel. [key] separates instances of the same class. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = rememberContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** Builds the payoff plan for the user's current debts and settings (recomputed whenever either changes). */
/**
 * What goes to debt each month: every debt's minimum (a cleared debt's minimum keeps rolling to the rest, exactly as the payoff
 * calculator budgets it) plus the extra amount.
 */
fun monthlyDebtBudget(debts: List<DebtEntity>, extraMonthly: Double): Double =
    debts.sumOf { it.minPayment } + maxOf(0.0, extraMonthly)

fun buildPlan(
    debts: List<DebtEntity>,
    settings: AppSettings,
    strategy: Strategy = settings.strategy,
    lump: LumpSum? = null,
    /** For debts whose payment this month is already made, how much has been paid (see `paidThisMonthAmounts`). */
    paidThisMonth: Map<Long, Double> = emptyMap(),
): PayoffPlan = PayoffCalculator.simulate(debts.map { it.toPlanDebt(paidThisMonth[it.id]) }, settings.extraMonthly, strategy, lump)
