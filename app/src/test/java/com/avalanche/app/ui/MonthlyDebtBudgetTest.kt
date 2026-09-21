package com.avalanche.app.ui

import com.avalanche.app.data.DebtEntity
import com.avalanche.app.domain.SavingsSetup
import com.avalanche.app.domain.SavingsProjector
import com.avalanche.app.domain.PayoffCalculator
import com.avalanche.app.domain.Strategy
import com.avalanche.app.domain.toPlanDebt
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlyDebtBudgetTest {
    private fun debt(id: Long, balance: Double, min: Double = 100.0) = DebtEntity(
        id = id, name = "Debt $id", type = "CREDIT_CARD", originalBalance = 1000.0, currentBalance = balance,
        apr = 0.0, minPayment = min, createdDay = 20000, lastAccrualDay = 20000,
    )

    @Test
    fun aClearedDebtsMinimumStillCountsBecauseItRollsToTheRest() {
        val debts = listOf(debt(1, balance = 0.0), debt(2, balance = 800.0))

        assertEquals(200.0, monthlyDebtBudget(debts, extraMonthly = 0.0), 1e-9)
    }

    @Test
    fun theExtraAmountIsAddedAndANegativeOneIsIgnored() {
        val debts = listOf(debt(1, 500.0), debt(2, 800.0, min = 150.0))

        assertEquals(500.0, monthlyDebtBudget(debts, 250.0), 1e-9)
        assertEquals(250.0, monthlyDebtBudget(debts, -40.0), 1e-9)
        assertEquals(0.0, monthlyDebtBudget(emptyList(), 0.0), 0.0)
    }

    @Test
    fun theIncomeShareUsesTheSameBudgetTheCalculatorSpends() {
        // Two 100 minimums, one debt already cleared, 1,000 income: 200 a month goes to debt, which is 20%, not 10%.
        val debts = listOf(debt(1, balance = 0.0), debt(2, balance = 800.0))
        val plan = PayoffCalculator.simulate(debts.map { it.toPlanDebt() }, 0.0, Strategy.AVALANCHE)
        val setup = SavingsSetup(balance = 0.0, monthlyIncome = 1000.0, percentSaved = 10.0, apy = 0.0)

        val projection = SavingsProjector.project(setup, plan, monthlyDebtBudget(debts, 0.0))

        assertEquals(0.20, projection.debtPaymentShare, 1e-12)
        // The calculator really does pay 200 in the first month: the cleared debt's 100 goes to the other debt.
        assertEquals(200.0, plan.months.first().totalPaid, 1e-9)
    }
}
