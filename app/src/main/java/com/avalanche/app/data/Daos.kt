package com.avalanche.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DebtDao {
    @Query("SELECT * FROM debts ORDER BY id")
    fun observeAll(): Flow<List<DebtEntity>>

    @Query("SELECT * FROM debts WHERE id = :id")
    fun observe(id: Long): Flow<DebtEntity?>

    @Query("SELECT * FROM debts WHERE id = :id")
    suspend fun get(id: Long): DebtEntity?

    @Query("SELECT * FROM debts ORDER BY id")
    suspend fun getAll(): List<DebtEntity>

    @Query("SELECT COUNT(*) FROM debts WHERE currentBalance > 0.005")
    suspend fun countActive(): Int

    @Insert
    suspend fun insert(debt: DebtEntity): Long

    @Insert
    suspend fun insertAll(debts: List<DebtEntity>)

    @Update
    suspend fun update(debt: DebtEntity)

    @Query("DELETE FROM debts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM debts")
    suspend fun deleteAll()
}

@Dao
interface PaymentDao {
    @Query("SELECT * FROM payments ORDER BY day, id")
    fun observeAll(): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments WHERE debtId = :debtId ORDER BY day DESC, id DESC")
    fun observeForDebt(debtId: Long): Flow<List<PaymentEntity>>

    @Query("SELECT * FROM payments ORDER BY day, id")
    suspend fun getAll(): List<PaymentEntity>

    @Query("SELECT COUNT(*) FROM payments WHERE debtId = :debtId")
    suspend fun countForDebt(debtId: Long): Int

    /** The first row logged for a debt; its "balance before" is the balance the app started tracking from. */
    @Query("SELECT * FROM payments WHERE debtId = :debtId ORDER BY id ASC LIMIT 1")
    suspend fun earliestForDebt(debtId: Long): PaymentEntity?

    /** The most recently inserted row; the only one that can be undone safely. */
    @Query("SELECT * FROM payments WHERE debtId = :debtId ORDER BY id DESC LIMIT 1")
    suspend fun latestForDebt(debtId: Long): PaymentEntity?

    @Insert
    suspend fun insert(payment: PaymentEntity): Long

    @Insert
    suspend fun insertAll(payments: List<PaymentEntity>)

    @Query("DELETE FROM payments WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM payments")
    suspend fun deleteAll()
}
