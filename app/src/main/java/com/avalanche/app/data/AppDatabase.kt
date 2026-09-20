package com.avalanche.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DebtEntity::class, PaymentEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun debtDao(): DebtDao
    abstract fun paymentDao(): PaymentDao

    companion object {
        /** v2 adds the estimated interest paid before a debt was added. Existing debts get 0. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE debts ADD COLUMN priorInterestPaid REAL NOT NULL DEFAULT 0")
            }
        }

        /** v3 lets an undo restore the APR that a rate-change adjustment replaced. Existing rows get NULL (no APR to restore). */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE payments ADD COLUMN prevApr REAL")
            }
        }

        /** v4 adds the optional "this month's payment is already made" marker to debts. Existing debts get NULL (not marked). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE debts ADD COLUMN paidMonth INTEGER")
            }
        }

        // Deliberately no destructive-migration fallback: this is the user's only copy of their data.
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "avalanche.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
