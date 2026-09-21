package com.avalanche.app.ui.plan

import com.avalanche.app.domain.SavingsLimits
import com.avalanche.app.domain.SavingsSetup
import com.avalanche.app.util.parseDecimal

/**
 * What the savings form makes of its four text fields. [setup] is set only when everything needed is filled in and in
 * range. An error is given only for a field that has text in it; an empty required field just keeps saving disabled.
 */
data class SavingsFormResult(
    val setup: SavingsSetup?,
    val balanceError: String? = null,
    val incomeError: String? = null,
    val percentError: String? = null,
    val apyError: String? = null,
)

fun validateSavingsForm(balance: String, income: String, percent: String, apy: String): SavingsFormResult {
    // An empty balance means nothing saved yet; the other three have to be entered.
    val balanceValue = if (balance.isBlank()) 0.0 else parseDecimal(balance)
    val incomeValue = parseDecimal(income)
    val percentValue = parseDecimal(percent)
    val apyValue = parseDecimal(apy)

    return SavingsFormResult(
        setup = SavingsLimits.validated(balanceValue, incomeValue, percentValue, apyValue),
        balanceError = balanceValue?.takeIf { it > SavingsLimits.MAX_BALANCE }?.let { "That's more than Avalanche can track" },
        incomeError = when {
            incomeValue == null -> null
            incomeValue <= 0.0 -> "Enter more than 0"
            incomeValue > SavingsLimits.MAX_INCOME -> "That's more than Avalanche can track"
            else -> null
        },
        percentError = percentValue?.takeIf { it > SavingsLimits.MAX_PERCENT }?.let { "Can't be more than 100%" },
        apyError = apyValue?.takeIf { it > SavingsLimits.MAX_APY }?.let { "Can't be more than ${SavingsLimits.MAX_APY.toInt()}%" },
    )
}
