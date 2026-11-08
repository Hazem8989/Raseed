package com.hazem.dailystore.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase as AndroidSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherSQLiteDatabase
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SqlCipherDatabaseMigratorInstrumentedTest {

    @Test
    fun exportPreservesTypedTableContentsSequenceAndSourceBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val source = context.getDatabasePath("migration-plain-$suffix.db")
        val destination = context.getDatabasePath("migration-cipher-$suffix.db")
        val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val binaryValue = byteArrayOf(0, 1, 2, 0x7f, 0xff.toByte())

        try {
            val directory = source.parentFile ?: error("Database directory unavailable")
            check(directory.isDirectory || directory.mkdirs())
            val plain = AndroidSQLiteDatabase.openOrCreateDatabase(source.absolutePath, null)
            try {
                plain.execSQL("PRAGMA foreign_keys=ON")
                plain.execSQL(
                    "CREATE TABLE products(" +
                        "id TEXT PRIMARY KEY, sold_qty INTEGER NOT NULL, description TEXT, " +
                        "unit_cost REAL, payload BLOB, optional_count INTEGER)"
                )
                plain.execSQL(
                    "CREATE TABLE sales(" +
                        "id TEXT PRIMARY KEY, product_id TEXT NOT NULL REFERENCES products(id), qty INTEGER NOT NULL)"
                )
                plain.execSQL(
                    "CREATE TABLE audit_events(id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL)"
                )
                plain.execSQL(
                    "CREATE TRIGGER trg_sales_qty AFTER INSERT ON sales " +
                        "BEGIN UPDATE products SET sold_qty=sold_qty+NEW.qty WHERE id=NEW.product_id; END"
                )
                plain.execSQL(
                    "INSERT INTO products VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>("p1", 0, null, 12.375, binaryValue, null)
                )
                plain.execSQL(
                    "INSERT INTO products VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>("p2", 7, "second row", 0.125, byteArrayOf(9, 8, 7), 42)
                )
                plain.execSQL("INSERT INTO audit_events(payload) VALUES('first')")
                plain.execSQL("INSERT INTO audit_events(payload) VALUES('deleted-but-sequence-retained')")
                plain.execSQL("DELETE FROM audit_events WHERE id=2")
                plain.version = 33
            } finally {
                plain.close()
            }
            val originalBytes = source.readBytes()

            SqlCipherDatabaseMigrator.exportPlainDatabase(source, destination, key)
            assertTrue("Migration must never modify or delete the plaintext source", source.isFile)
            assertTrue("Source bytes must remain identical", originalBytes.contentEquals(source.readBytes()))
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(destination, key, sourceVersion = 33)

            val passphrase = key.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
            val encrypted = CipherSQLiteDatabase.openDatabase(
                destination.absolutePath, passphrase, null,
                CipherSQLiteDatabase.OPEN_READWRITE, null, null
            )
            try {
                assertEquals(33, encrypted.version)
                encrypted.rawQuery(
                    "SELECT description,unit_cost,payload,optional_count FROM products WHERE id='p1'",
                    null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertNull(cursor.getString(0))
                    assertEquals(12.375, cursor.getDouble(1), 0.0000001)
                    assertArrayEquals(binaryValue, cursor.getBlob(2))
                    assertTrue(cursor.isNull(3))
                }
                encrypted.rawQuery("SELECT COUNT(*) FROM sqlite_sequence WHERE name='audit_events' AND seq=2", null)
                    .use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(1, cursor.getInt(0))
                    }
                encrypted.execSQL("INSERT INTO audit_events(payload) VALUES('after migration')")
                encrypted.rawQuery("SELECT last_insert_rowid()", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(3, cursor.getInt(0))
                }
                encrypted.execSQL("INSERT INTO sales VALUES('s1','p1',4)")
                encrypted.rawQuery("SELECT sold_qty FROM products WHERE id='p1'", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(4, cursor.getInt(0))
                }
            } finally {
                encrypted.close()
                passphrase.fill(0)
            }
        } finally {
            key.fill(0)
            source.delete()
            destination.delete()
            listOf(source, destination).forEach {
                File(it.path + "-wal").delete()
                File(it.path + "-shm").delete()
                File(it.path + "-journal").delete()
            }
        }
    }
    @Test
    fun exportsRealProductionV33AppDatabaseWithoutChangingTheOriginalFile() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val source = context.getDatabasePath("migration-real-app-v33-$suffix.db")
        val destination = context.getDatabasePath("migration-real-app-v33-cipher-$suffix.db")
        val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val helper = AppDatabase(context, source.name)

        try {
            val database = helper.writableDatabase
            assertEquals(AppDatabase.DATABASE_VERSION, database.version)
            database.execSQL(
                "INSERT INTO app_settings(setting_key,setting_value,updated_at) VALUES(?,?,?)",
                arrayOf("sqlcipher_migration_audit_marker", "preserve-this-value", "2026-11-08T00:00:00Z")
            )
            helper.checkpointAndCloseForBackup()

            val originalBytes = source.readBytes()
            assertTrue("Production schema fixture must be non-empty", originalBytes.isNotEmpty())
            assertTrue("Unique encrypted destination must not exist", !destination.exists())

            SqlCipherDatabaseMigrator.exportPlainDatabase(source, destination, key)

            assertTrue("The original production SQLite database must remain present", source.isFile)
            assertTrue(
                "The original production SQLite bytes must remain unchanged",
                originalBytes.contentEquals(source.readBytes())
            )
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(
                destination,
                key,
                sourceVersion = AppDatabase.DATABASE_VERSION
            )

            val passphrase = key.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
            val encrypted = CipherSQLiteDatabase.openDatabase(
                destination.absolutePath,
                passphrase,
                null,
                CipherSQLiteDatabase.OPEN_READONLY,
                null,
                null
            )
            try {
                assertEquals(AppDatabase.DATABASE_VERSION, encrypted.version)
                encrypted.rawQuery(
                    "SELECT setting_value FROM app_settings WHERE setting_key=?",
                    arrayOf("sqlcipher_migration_audit_marker")
                ).use { cursor ->
                    assertTrue("Production settings marker must survive export", cursor.moveToFirst())
                    assertEquals("preserve-this-value", cursor.getString(0))
                    assertTrue("Marker must be unique", !cursor.moveToNext())
                }
                encrypted.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master " +
                        "WHERE name NOT LIKE 'sqlite_%' AND type IN ('table','index','trigger','view')",
                    null
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertTrue(
                        "The real application schema must include its production objects",
                        cursor.getInt(0) > 20
                    )
                }
            } finally {
                encrypted.close()
                passphrase.fill(0)
            }
        } finally {
            runCatching { helper.close() }
            key.fill(0)
            listOf(source, destination).forEach { file ->
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                File(file.path + "-journal").delete()
            }
        }
    }


    @Test
    fun exportsPopulatedProductionV33DatabaseWithInventorySalesCashAndWalletDataIntact() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext as Context
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val source = context.getDatabasePath("migration-populated-v33-$suffix.db")
        val destination = context.getDatabasePath("migration-populated-v33-cipher-$suffix.db")
        val key = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val helper = AppDatabase(context, source.name)
        AppDatabase.installForTests(helper)

        try {
            val store = StoreRepository(context)
            store.ensureBaseSetup()

            val auth = AuthRepository(context)
            val actorId = auth.createInitialAdmin(
                "mig$suffix",
                "SQLCipher Migration Test",
                "MigrationAudit123"
            )

            store.saveExchangeRate(BigDecimal("15000"), actorId)
            val unit = store.getUnits().first()
            val category = store.getCategories().first()
            val productId = store.saveProduct(
                null, "صنف اختبار ترحيل مشفر", null, "MIGRATION-$suffix",
                category.id, unit.id, 125, 175, true, 10.0, actorId
            )
            val cashAccount = CashAccountRepository(context).create(
                actorId, "صندوق اختبار الترحيل", "CASH_BOX", "SYP", 100_000L
            )
            CustomerRepository(context).create(actorId, "زبون اختبار الترحيل $suffix")
            ServiceTransferRepository(context).ensureSystemAccounts()

            val invoiceId = "migration-sale-$suffix"
            val saleOperationId = "migration-sale-post-$suffix"
            val saved = InvoiceRepository(context).saveSalesDraft(
                actorId,
                "migration-sale-draft-$suffix",
                invoiceId,
                null,
                "2026-11-08",
                0L,
                "SQLCipher populated production fixture",
                listOf(InvoiceLineInput(productId, BigDecimal("2"), 175L)),
                "CASH"
            )
            assertEquals(invoiceId, saved.invoiceId)
            assertTrue(
                InvoiceRepository(context).postCashSale(
                    actorId, invoiceId, saleOperationId, cashAccount.id,
                    "2026-11-08T12:00:00Z", "2026-11-08"
                )
            )

            helper.checkpointAndCloseForBackup()
            val originalBytes = source.readBytes()
            assertTrue("Populated production database must be non-empty", originalBytes.isNotEmpty())
            assertTrue("Encrypted destination must be unique", !destination.exists())

            SqlCipherDatabaseMigrator.exportPlainDatabase(source, destination, key)

            assertTrue("Original SQLite database must remain present", source.isFile)
            assertTrue(
                "Original SQLite database bytes must remain unchanged",
                originalBytes.contentEquals(source.readBytes())
            )
            SqlCipherDatabaseMigrator.validateEncryptedDatabase(
                destination, key, sourceVersion = AppDatabase.DATABASE_VERSION
            )

            val passphrase = key.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
            val encrypted = CipherSQLiteDatabase.openDatabase(
                destination.absolutePath,
                passphrase,
                null,
                CipherSQLiteDatabase.OPEN_READONLY,
                null,
                null
            )
            try {
                assertEquals(AppDatabase.DATABASE_VERSION, encrypted.version)
                assertEquals(1, encrypted.rawQuery(
                    "SELECT COUNT(*) FROM products WHERE id=? AND name=?",
                    arrayOf(productId, "صنف اختبار ترحيل مشفر")
                ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) })
                assertEquals(8.0, encrypted.rawQuery(
                    "SELECT quantity FROM inventory_balances WHERE product_id=?",
                    arrayOf(productId)
                ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getDouble(0) }, 0.000001)
                assertEquals("PAID", encrypted.rawQuery(
                    "SELECT status FROM sales_invoices WHERE id=?",
                    arrayOf(invoiceId)
                ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) })
                assertEquals(1, encrypted.rawQuery(
                    "SELECT COUNT(*) FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?",
                    arrayOf(invoiceId)
                ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) })
                assertEquals(1, encrypted.rawQuery(
                    "SELECT COUNT(*) FROM cash_accounts WHERE id=? AND opening_balance_minor=100000",
                    arrayOf(cashAccount.id)
                ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) })
                assertTrue(encrypted.rawQuery(
                    "SELECT COUNT(*) FROM service_wallets",
                    null
                ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) >= 2 })
                assertTrue(encrypted.rawQuery(
                    "SELECT COUNT(*) FROM sync_operation_envelopes WHERE entity_id=?",
                    arrayOf(productId)
                ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getInt(0) >= 1 })
            } finally {
                encrypted.close()
                passphrase.fill(0)
            }
        } finally {
            runCatching { helper.close() }
            runCatching { AppDatabase.resetForTests(context, deleteExisting = false) }
            key.fill(0)
            listOf(source, destination).forEach { file ->
                file.delete()
                File(file.path + "-wal").delete()
                File(file.path + "-shm").delete()
                File(file.path + "-journal").delete()
            }
        }
    }

}
