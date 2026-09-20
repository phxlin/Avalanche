package com.avalanche.app.data

import com.avalanche.app.domain.Strategy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import java.io.Reader
import java.time.Instant

/**
 * JSON backup format. DTO fields are nullable so a malformed or hand-edited file is rejected with
 * a clear message instead of producing half-initialised objects.
 */
data class BackupFile(
    val app: String? = null,
    val version: Int? = null,
    val exportedAt: String? = null,
    val settings: BackupSettings? = null,
    val debts: List<DebtDto>? = null,
    val payments: List<PaymentDto>? = null,
)

data class BackupSettings(
    val currency: String? = null,
    val extraMonthly: Double? = null,
    val strategy: String? = null,
)

data class DebtDto(
    val id: Long? = null,
    val name: String? = null,
    val type: String? = null,
    val originalBalance: Double? = null,
    val currentBalance: Double? = null,
    val apr: Double? = null,
    val minPayment: Double? = null,
    val createdDay: Long? = null,
    val lastAccrualDay: Long? = null,
    val paidOffDay: Long? = null,
    /** Absent in backups made before this field existed; those import as 0. */
    val priorInterestPaid: Double? = null,
    /** Absent in older backups and when the debt isn't marked as paid for a month. */
    val paidMonth: Int? = null,
)

data class PaymentDto(
    val id: Long? = null,
    val sessionId: Long? = null,
    val debtId: Long? = null,
    val day: Long? = null,
    val amount: Double? = null,
    val interestPortion: Double? = null,
    val principalPortion: Double? = null,
    val balanceBefore: Double? = null,
    val balanceAfter: Double? = null,
    val prevAccrualDay: Long? = null,
    val prevOriginalBalance: Double? = null,
    val prevPaidOffDay: Long? = null,
    val kind: String? = null,
    /** Absent in backups made before this field existed, and on entries that didn't change the rate. */
    val prevApr: Double? = null,
)

class BackupException(message: String) : Exception(message)

data class ParsedBackup(val debts: List<DebtEntity>, val payments: List<PaymentEntity>, val settings: BackupSettings?)

object Backup {
    const val APP_ID = "Avalanche"
    const val VERSION = 1
    /** A real backup is a few hundred KB at most; this stops a wrong (huge) file from exhausting memory. */
    const val MAX_CHARS = 10_000_000
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Reads a backup file's text, giving up as soon as it is larger than [MAX_CHARS]. */
    fun readBounded(reader: Reader): String {
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val n = reader.read(buffer)
            if (n < 0) break
            text.append(buffer, 0, n)
            if (text.length > MAX_CHARS) throw BackupException("This file is too large to be an Avalanche backup.")
        }
        return text.toString()
    }

    fun toJson(debts: List<DebtEntity>, payments: List<PaymentEntity>, settings: AppSettings, now: Instant = Instant.now()): String =
        gson.toJson(
            BackupFile(
                app = APP_ID,
                version = VERSION,
                exportedAt = now.toString(),
                settings = BackupSettings(settings.currency, settings.extraMonthly, settings.strategy.name),
                debts = debts.map {
                    DebtDto(
                        it.id, it.name, it.type, it.originalBalance, it.currentBalance, it.apr, it.minPayment,
                        it.createdDay, it.lastAccrualDay, it.paidOffDay, it.priorInterestPaid, it.paidMonth,
                    )
                },
                payments = payments.map {
                    PaymentDto(
                        it.id, it.sessionId, it.debtId, it.day, it.amount, it.interestPortion, it.principalPortion,
                        it.balanceBefore, it.balanceAfter, it.prevAccrualDay, it.prevOriginalBalance, it.prevPaidOffDay, it.kind, it.prevApr,
                    )
                },
            ),
        )

    fun parse(json: String): ParsedBackup {
        val file = try {
            gson.fromJson(json, BackupFile::class.java)
        } catch (e: JsonSyntaxException) {
            throw BackupException("This file isn't valid JSON.")
        } ?: throw BackupException("This file is empty.")

        if (file.app != APP_ID) throw BackupException("This doesn't look like an Avalanche backup.")
        val version = file.version ?: throw BackupException("The backup has no version number.")
        if (version > VERSION) throw BackupException("This backup was made by a newer version of the app.")

        fun money(v: Double?, field: String): Double {
            if (v == null || !v.isFinite() || v < 0) throw BackupException("Invalid value for $field in the backup.")
            return v
        }

        val debts = (file.debts ?: throw BackupException("The backup contains no debt list.")).map { d ->
            DebtEntity(
                id = d.id ?: throw BackupException("A debt is missing its id."),
                name = d.name?.trim()?.takeIf { it.isNotEmpty() } ?: throw BackupException("A debt is missing its name."),
                type = DebtType.fromName(d.type).name,
                originalBalance = money(d.originalBalance, "originalBalance"),
                currentBalance = money(d.currentBalance, "currentBalance"),
                apr = money(d.apr, "apr"),
                minPayment = money(d.minPayment, "minPayment"),
                createdDay = d.createdDay ?: throw BackupException("A debt is missing its creation date."),
                lastAccrualDay = d.lastAccrualDay ?: d.createdDay,
                paidOffDay = d.paidOffDay,
                priorInterestPaid = money(d.priorInterestPaid ?: 0.0, "priorInterestPaid"),
                paidMonth = d.paidMonth,
            )
        }
        if (debts.map { it.id }.toSet().size != debts.size) throw BackupException("The backup has duplicate debt ids.")
        val debtIds = debts.map { it.id }.toSet()

        val payments = (file.payments ?: emptyList()).map { p ->
            val debtId = p.debtId ?: throw BackupException("A payment is missing its debt.")
            if (debtId !in debtIds) throw BackupException("A payment refers to a debt that isn't in the backup.")
            PaymentEntity(
                id = p.id ?: throw BackupException("A payment is missing its id."),
                sessionId = p.sessionId ?: 0L,
                debtId = debtId,
                day = p.day ?: throw BackupException("A payment is missing its date."),
                amount = money(p.amount, "amount"),
                interestPortion = money(p.interestPortion, "interestPortion"),
                principalPortion = money(p.principalPortion, "principalPortion"),
                balanceBefore = money(p.balanceBefore, "balanceBefore"),
                balanceAfter = money(p.balanceAfter, "balanceAfter"),
                prevAccrualDay = p.prevAccrualDay ?: p.day,
                prevOriginalBalance = money(p.prevOriginalBalance ?: p.balanceBefore, "prevOriginalBalance"),
                prevPaidOffDay = p.prevPaidOffDay,
                kind = if (p.kind == KIND_ADJUSTMENT) KIND_ADJUSTMENT else KIND_PAYMENT,
                prevApr = p.prevApr?.let { money(it, "prevApr") },
            )
        }
        if (payments.map { it.id }.toSet().size != payments.size) throw BackupException("The backup has duplicate payment ids.")
        return ParsedBackup(debts, payments, file.settings)
    }

    fun applyTo(current: AppSettings, backup: BackupSettings?): AppSettings {
        if (backup == null) return current
        return current.copy(
            currency = backup.currency?.takeIf { SettingsStore.isValidCurrency(it) } ?: current.currency,
            extraMonthly = backup.extraMonthly?.takeIf { it.isFinite() && it >= 0 } ?: current.extraMonthly,
            strategy = runCatching { Strategy.valueOf(backup.strategy ?: "") }.getOrDefault(current.strategy),
        )
    }
}
