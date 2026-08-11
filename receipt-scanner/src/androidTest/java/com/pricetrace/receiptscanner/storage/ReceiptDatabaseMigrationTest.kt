package com.pricetrace.receiptscanner.storage

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ReceiptDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrateFromV1AddsReviewTimestampsWithoutLosingSessions() {
        migrationHelper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `scan_sessions` (
                    `document_id` TEXT NOT NULL,
                    `created_at` TEXT NOT NULL,
                    `updated_at` TEXT NOT NULL,
                    `ocr_status` TEXT NOT NULL,
                    `review_status` TEXT NOT NULL,
                    `json_revision` TEXT,
                    `export_status` TEXT NOT NULL,
                    `upload_status` TEXT NOT NULL,
                    `last_error` TEXT,
                    `retry_count` INTEGER NOT NULL,
                    `merchant_name` TEXT,
                    `issued_on` TEXT,
                    `grand_total_amount_minor` INTEGER,
                    `receipt_storage_key` TEXT,
                    `manifest_storage_key` TEXT,
                    PRIMARY KEY(`document_id`)
                )
                """.trimIndent(),
            )
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `receipt_pages` (
                    `page_id` TEXT NOT NULL,
                    `document_id` TEXT NOT NULL,
                    `storage_key` TEXT NOT NULL,
                    `sha256` TEXT NOT NULL,
                    `mime_type` TEXT NOT NULL,
                    `width` INTEGER NOT NULL,
                    `height` INTEGER NOT NULL,
                    `page_index` INTEGER NOT NULL,
                    `created_at` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    PRIMARY KEY(`page_id`),
                    FOREIGN KEY(`document_id`) REFERENCES `scan_sessions`(`document_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_pages_document_id` ON `receipt_pages` (`document_id`)")
            execSQL("CREATE INDEX IF NOT EXISTS `index_receipt_pages_sha256` ON `receipt_pages` (`sha256`)")
            execSQL(
                """
                CREATE TABLE IF NOT EXISTS `review_edits` (
                    `edit_id` TEXT NOT NULL,
                    `document_id` TEXT NOT NULL,
                    `field_path` TEXT NOT NULL,
                    `previous_value` TEXT,
                    `new_value` TEXT,
                    `provenance_json` TEXT,
                    `edited_at` TEXT NOT NULL,
                    PRIMARY KEY(`edit_id`),
                    FOREIGN KEY(`document_id`) REFERENCES `scan_sessions`(`document_id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            execSQL("CREATE INDEX IF NOT EXISTS `index_review_edits_document_id` ON `review_edits` (`document_id`)")
            execSQL(
                """
                INSERT INTO `scan_sessions` (
                    `document_id`,
                    `created_at`,
                    `updated_at`,
                    `ocr_status`,
                    `review_status`,
                    `json_revision`,
                    `export_status`,
                    `upload_status`,
                    `last_error`,
                    `retry_count`,
                    `merchant_name`,
                    `issued_on`,
                    `grand_total_amount_minor`,
                    `receipt_storage_key`,
                    `manifest_storage_key`
                ) VALUES (
                    'doc-migrate',
                    '2026-08-03T09:00:00+09:00',
                    '2026-08-03T09:30:00+09:00',
                    'parsed',
                    'user_verified',
                    'revision-before',
                    'exported',
                    'local_only',
                    NULL,
                    2,
                    '마이그레이션 상점',
                    '2026-08-03',
                    1300,
                    'doc-migrate/draft/receipt.json',
                    'doc-migrate/exports/revision-before/manifest.json'
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            ReceiptDatabase.MIGRATION_1_2,
            ReceiptDatabase.MIGRATION_2_3,
            ReceiptDatabase.MIGRATION_3_4,
        )

        migrated.query(
            SimpleSQLiteQuery(
                "SELECT document_id, merchant_name, review_status, reviewed_at, ocr_completed_at, " +
                    "workflow_type, display_title, workflow_draft_storage_key " +
                    "FROM scan_sessions WHERE document_id = ?",
                arrayOf("doc-migrate"),
            ),
        ).use { cursor ->
            cursor.moveToFirst()
            assertEquals("doc-migrate", cursor.getString(0))
            assertEquals("마이그레이션 상점", cursor.getString(1))
            assertEquals("user_verified", cursor.getString(2))
            assertNull(cursor.getString(3))
            // Sessions reviewed before timing existed stay null and are excluded from duration stats.
            assertNull(cursor.getString(4))
            assertEquals("pricetrace_receipt", cursor.getString(5))
            assertNull(cursor.getString(6))
            assertNull(cursor.getString(7))
        }
    }

    companion object {
        private const val TEST_DB = "receipt-db-migration-test"
    }
}
