package com.avalanche.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Revolving debt (credit cards) keeps accruing interest on a changing balance and is the
 * aggressive target for avalanche ordering. Everything else is treated as installment debt
 * with a fixed payoff schedule.
 */
enum class DebtType(val label: String, val revolving: Boolean) {
    CREDIT_CARD("Credit card", true),
    PERSONAL_LOAN("Personal loan", false),
    STUDENT_LOAN("Student loan", false),
    AUTO_LOAN("Auto loan", false),
    MEDICAL("Medical", false),
    OTHER("Other", false);

    companion object {
        fun fromName(name: String?): DebtType = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

const val KIND_PAYMENT = "PAYMENT"
const val KIND_ADJUSTMENT = "ADJUSTMENT"

/** Balances at or below this are considered fully paid. */
const val PAID_OFF_EPSILON = 0.005

@Entity(tableName = "debts")
data class DebtEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** [DebtType.name]; stored as text so adding types never needs a migration. */
    val type: String,
    /** Balance when the debt was first added. Progress is measured against this. */
    val originalBalance: Double,
    val currentBalance: Double,
    /** Annual percentage rate, e.g. 19.99. */
    val apr: Double,
    val minPayment: Double,
    /** Epoch day the debt was added. */
    val createdDay: Long,
    /** Epoch day interest has been accrued up to. Advances on payments and balance adjustments. */
    val lastAccrualDay: Long,
    val paidOffDay: Long? = null,
    /**
     * Estimated interest already paid before the debt was added, derived from its starting
     * "already paid off" percentage and minimum payment. Zero when no percentage was given.
     */
    @ColumnInfo(defaultValue = "0") val priorInterestPaid: Double = 0.0,
    /**
     * Set by the "already paid this month" checkbox: the calendar month (see `monthCode`) whose payment the user
     * says is already made. It simply stops matching when the month changes. Payments logged in the app count too,
     * but those are derived from the payment log rather than stored here.
     */
    val paidMonth: Int? = null,
)

/**
 * One row per debt per logged payment (or balance adjustment). Rows saved in the same
 * logging session share a [sessionId]. The "before" fields exist so the latest row can be undone exactly.
 */
@Entity(
    tableName = "payments",
    foreignKeys = [
        ForeignKey(
            entity = DebtEntity::class,
            parentColumns = ["id"],
            childColumns = ["debtId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("debtId"), Index("sessionId")],
)
data class PaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val debtId: Long,
    /** Epoch day the payment was made. */
    val day: Long,
    /** Cash paid. Zero for adjustments. */
    val amount: Double,
    val interestPortion: Double,
    val principalPortion: Double,
    val balanceBefore: Double,
    val balanceAfter: Double,
    val prevAccrualDay: Long,
    val prevOriginalBalance: Double,
    val prevPaidOffDay: Long? = null,
    val kind: String = KIND_PAYMENT,
    /** Set on an adjustment that came with a rate change: the APR before it, restored if the entry is undone. */
    val prevApr: Double? = null,
)

val DebtEntity.debtType: DebtType get() = DebtType.fromName(type)
val DebtEntity.isPaidOff: Boolean get() = currentBalance <= PAID_OFF_EPSILON
val DebtEntity.progress: Float
    get() = if (originalBalance <= 0.0) 1f else (1.0 - currentBalance / originalBalance).coerceIn(0.0, 1.0).toFloat()
val PaymentEntity.isAdjustment: Boolean get() = kind == KIND_ADJUSTMENT
