package com.avalanche.app.ui.payments

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avalanche.app.data.Allocation
import com.avalanche.app.data.BackdatedPaymentException
import com.avalanche.app.data.DebtEntity
import com.avalanche.app.data.DebtRepository
import com.avalanche.app.data.LogResult
import com.avalanche.app.data.isPaidOff
import com.avalanche.app.domain.PaymentMath
import com.avalanche.app.util.editableNumber
import com.avalanche.app.util.formatEpochDay
import com.avalanche.app.util.parseDecimal
import java.time.LocalDate
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Encodes prefilled allocations for navigation as "id:amount,id:amount". */
fun encodeAllocations(allocations: Map<Long, Double>): String =
    allocations.entries.joinToString(",") { "${it.key}:${"%.2f".format(java.util.Locale.US, it.value)}" }

fun decodeAllocations(encoded: String): Map<Long, Double> =
    encoded.split(",").mapNotNull { part ->
        val bits = part.split(":")
        val id = bits.getOrNull(0)?.toLongOrNull()
        val amount = bits.getOrNull(1)?.toDoubleOrNull()
        if (id != null && amount != null && amount > 0) id to amount else null
    }.toMap()

class LogPaymentViewModel(
    private val repo: DebtRepository,
    private val prefillDebtId: Long?,
    private val prefill: Map<Long, Double>,
) : ViewModel() {
    /** Debts that can still receive a payment; null while loading. */
    val debts: StateFlow<List<DebtEntity>?> = repo.debts
        .map { list -> list.filter { !it.isPaidOff } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val amounts = mutableStateMapOf<Long, String>()
    var date by mutableStateOf(LocalDate.now())
    var saving by mutableStateOf(false)
        private set
    var formError by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            val active = repo.debts.first().filter { !it.isPaidOff }
            when {
                prefill.isNotEmpty() -> prefill.forEach { (id, amount) -> if (active.any { it.id == id }) amounts[id] = editableNumber(amount) }
                prefillDebtId != null -> active.firstOrNull { it.id == prefillDebtId }?.let { amounts[it.id] = editableNumber(minOf(it.minPayment, payoffAmount(it))) }
            }
        }
    }

    fun payoffAmount(debt: DebtEntity): Double =
        PaymentMath.payoffAmount(debt.currentBalance, debt.apr, debt.lastAccrualDay, date.toEpochDay())

    fun setAmount(debtId: Long, text: String) {
        amounts[debtId] = text
        formError = null
    }

    fun fillMinimums(active: List<DebtEntity>) {
        active.forEach { amounts[it.id] = editableNumber(minOf(it.minPayment, payoffAmount(it))) }
        formError = null
    }

    fun payOffInFull(debt: DebtEntity) {
        amounts[debt.id] = editableNumber(payoffAmount(debt))
        formError = null
    }

    fun total(): Double = amounts.values.sumOf { parseDecimal(it) ?: 0.0 }

    fun rowError(debt: DebtEntity): String? {
        val v = parseDecimal(amounts[debt.id].orEmpty()) ?: return null
        if (v > 0.0 && date.toEpochDay() < debt.lastAccrualDay) return "Last entry ${formatEpochDay(debt.lastAccrualDay)}: pick that date or later"
        val max = payoffAmount(debt)
        return if (v > max + 0.004) "More than the payoff amount" else null
    }

    fun save(active: List<DebtEntity>, onDone: (LogResult) -> Unit) {
        if (saving) return
        val allocations = active.mapNotNull { d -> parseDecimal(amounts[d.id].orEmpty())?.takeIf { it > 0 }?.let { Allocation(d.id, it) } }
        formError = when {
            allocations.isEmpty() -> "Enter an amount for at least one debt."
            active.any { rowError(it) != null } -> "Fix the amounts marked in red first."
            date.isAfter(LocalDate.now()) -> "The payment date can't be in the future."
            else -> null
        }
        if (formError != null) return
        saving = true
        viewModelScope.launch {
            val result = try {
                repo.logPayments(date, allocations)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackdatedPaymentException) {
                formError = "${e.debtName} already has an entry on ${formatEpochDay(e.lastEntry.toEpochDay())}. Pick that date or later."
                null
            } catch (e: Exception) {
                formError = "Couldn't save the payment. Please try again."
                null
            } finally {
                saving = false
            }
            if (result != null) onDone(result)
        }
    }
}
