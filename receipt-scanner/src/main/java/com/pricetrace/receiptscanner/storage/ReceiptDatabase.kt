package com.pricetrace.receiptscanner.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ScanSessionEntity::class, ReceiptPageEntity::class, ReviewEditEntity::class, PriceObservationQueueEntity::class, IngestionSessionEntity::class, IngestionProjectionEntity::class, IngestionAttachmentEntity::class],
    version = 7,
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

        /** Existing receipt sessions remain PriceTrace sessions; new workflows get their own draft key. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE scan_sessions ADD COLUMN workflow_type TEXT NOT NULL " +
                        "DEFAULT 'pricetrace_receipt'",
                )
                db.execSQL("ALTER TABLE scan_sessions ADD COLUMN display_title TEXT")
                db.execSQL("ALTER TABLE scan_sessions ADD COLUMN workflow_draft_storage_key TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `price_observation_queue` (
                        `queue_id` TEXT NOT NULL,
                        `local_document_id` TEXT,
                        `local_line_item_id` TEXT,
                        `idempotency_key` TEXT NOT NULL,
                        `store_id` TEXT NOT NULL,
                        `observed_on` TEXT NOT NULL,
                        `catalog_product_id` TEXT NOT NULL,
                        `unit_price_krw` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `attempt_count` INTEGER NOT NULL,
                        `last_error` TEXT,
                        `observation_id` TEXT,
                        `replayed` INTEGER,
                        `applied_action` TEXT,
                        `created_at` TEXT NOT NULL,
                        `updated_at` TEXT NOT NULL,
                        PRIMARY KEY(`queue_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_price_observation_queue_status` " +
                        "ON `price_observation_queue` (`status`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_price_observation_queue_local_document_id_local_line_item_id` " +
                        "ON `price_observation_queue` (`local_document_id`, `local_line_item_id`)",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scan_sessions ADD COLUMN input_origin TEXT NOT NULL DEFAULT 'android_ocr'")
                db.execSQL("ALTER TABLE scan_sessions ADD COLUMN upstream_document_id TEXT")
                db.execSQL("ALTER TABLE scan_sessions ADD COLUMN import_fingerprint TEXT")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_sessions_input_origin` " +
                        "ON `scan_sessions` (`input_origin`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_sessions_upstream_document_id` " +
                        "ON `scan_sessions` (`upstream_document_id`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_scan_sessions_import_fingerprint` " +
                        "ON `scan_sessions` (`import_fingerprint`)",
                )
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `ingestion_sessions` (`ingestion_id` TEXT NOT NULL, `local_document_id` TEXT NOT NULL, `envelope_storage_key` TEXT NOT NULL, `canonical_fingerprint` TEXT NOT NULL, `review_status` TEXT NOT NULL, `created_at` TEXT NOT NULL, `updated_at` TEXT NOT NULL, PRIMARY KEY(`ingestion_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ingestion_sessions_local_document_id` ON `ingestion_sessions` (`local_document_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ingestion_sessions_canonical_fingerprint` ON `ingestion_sessions` (`canonical_fingerprint`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `ingestion_projections` (`ingestion_id` TEXT NOT NULL, `projection` TEXT NOT NULL, `status` TEXT NOT NULL, `idempotency_key` TEXT, `remote_id` TEXT, `attempt_count` INTEGER NOT NULL, `last_error` TEXT, `updated_at` TEXT NOT NULL, PRIMARY KEY(`ingestion_id`, `projection`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ingestion_projections_status` ON `ingestion_projections` (`status`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `ingestion_attachments` (`ingestion_id` TEXT NOT NULL, `attachment_id` TEXT NOT NULL, `type` TEXT NOT NULL, `page_id` TEXT, `file_readable` INTEGER NOT NULL, PRIMARY KEY(`ingestion_id`, `attachment_id`))")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ingestion_attachments_ingestion_id` ON `ingestion_attachments` (`ingestion_id`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_ingestion_attachments_page_id` ON `ingestion_attachments` (`page_id`)")
            }
        }

        fun getInstance(context: Context): ReceiptDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ReceiptDatabase::class.java,
                "receipt-scanner.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build().also { database -> instance = database }
        }
    }
}
