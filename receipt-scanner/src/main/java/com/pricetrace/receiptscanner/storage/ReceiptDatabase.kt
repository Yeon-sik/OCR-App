package com.pricetrace.receiptscanner.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScanSessionEntity::class, ReceiptPageEntity::class, ReviewEditEntity::class],
    version = 3,
    exportSchema = false,
)
internal abstract class ReceiptDatabase : RoomDatabase() {
    abstract fun receiptSessionDao(): ReceiptSessionDao

    companion object {
        @Volatile
        private var instance: ReceiptDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_sessions ADD COLUMN reviewed_at TEXT")
            }
        }

        /** Sessions reviewed before this column existed keep a null value and stay out of timing stats. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_sessions ADD COLUMN ocr_completed_at TEXT")
            }
        }

        fun getInstance(context: Context): ReceiptDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ReceiptDatabase::class.java,
                "receipt-scanner.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { database -> instance = database }
        }
    }
}
