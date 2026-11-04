package com.hazem.dailystore.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class StoreTimeTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
        SyncDeviceTestKeyProvider.install()
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
    }

    @After fun tearDown() { database.close() }

    @Test fun defaultsToSyrianStoreTimeZone() {
        assertEquals(StoreTime.DEFAULT_ZONE_ID, StoreTime.zoneId(database.readableDatabase))
    }

    @Test fun serviceLedgersPersistStoreOperatingDate() {
        fun hasColumn(table: String, column: String): Boolean =
            database.readableDatabase.rawQuery("PRAGMA table_info($table)", null).use { c ->
                var found = false
                while (c.moveToNext()) if (c.getString(1) == column) found = true
                found
            }
        assertTrue(hasColumn("service_transfers", "operating_date"))
        assertTrue(hasColumn("service_wallet_transactions", "operating_date"))
    }

    @Test fun operatingDateUsesStoreZoneRatherThanUtcOrDeviceZone() {
        StoreTime.setZoneId(database.writableDatabase, "America/Los_Angeles", "2026-11-03T00:00:00.000Z")
        assertEquals(
            "2026-11-03",
            StoreTime.operatingDate(database.readableDatabase, "2026-11-04T02:30:00.000Z")
        )
        assertEquals(
            "2026-11-03 18:30:00",
            StoreTime.formatTimestamp(database.readableDatabase, "2026-11-04T02:30:00.000Z")
        )
    }

    @Test fun selectedZoneAppliesDaylightSavingRules() {
        StoreTime.setZoneId(database.writableDatabase, "America/New_York", "2026-03-01T00:00:00.000Z")
        assertEquals("2026-03-08 01:30:00",
            StoreTime.formatTimestamp(database.readableDatabase, "2026-03-08T06:30:00.000Z"))
        assertEquals("2026-03-08 03:30:00",
            StoreTime.formatTimestamp(database.readableDatabase, "2026-03-08T07:30:00.000Z"))
    }

    @Test fun rejectsUnknownZoneInsteadOfSilentlyFallingBackToGmt() {
        assertFalse(StoreTime.isValidZoneId("Not/A_Real_Zone"))
        assertTrue(StoreTime.isValidZoneId("Asia/Damascus"))
    }

    @Test fun todayUsesConfiguredZone() {
        StoreTime.setZoneId(database.writableDatabase, "Asia/Damascus", "2026-11-03T00:00:00.000Z")
        val utc = Date.from(java.time.Instant.parse("2026-11-03T22:30:00Z"))
        assertEquals("2026-11-04", StoreTime.today(database.readableDatabase, utc))
    }
    @Test fun v23MigrationBackfillsServiceOperatingDatesUsingStoreZone() {
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        val legacy = object : SQLiteOpenHelper(context, AppDatabase.DATABASE_NAME, null, 22) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL("CREATE TABLE app_settings(setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL, updated_at TEXT NOT NULL)")
                db.execSQL("INSERT INTO app_settings VALUES('store_time_zone','America/Los_Angeles','2026-11-03T00:00:00.000Z')")
                db.execSQL("CREATE TABLE service_transfers(id TEXT PRIMARY KEY, created_at TEXT NOT NULL, service_type TEXT NOT NULL, status TEXT NOT NULL)")
                db.execSQL("CREATE TABLE service_wallet_transactions(id TEXT PRIMARY KEY, created_at TEXT NOT NULL, service_key TEXT NOT NULL, transaction_type TEXT NOT NULL)")
                db.execSQL("INSERT INTO service_transfers(id,created_at,service_type,status) VALUES('transfer-1','2026-11-04T02:30:00.000Z','SYRIATEL_BALANCE','POSTED')")
                db.execSQL("INSERT INTO service_wallet_transactions(id,created_at,service_key,transaction_type) VALUES('wallet-1','2026-11-04T02:30:00.000Z','SYRIATEL_SHARED','TRANSFER_OUT')")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        legacy.writableDatabase
        legacy.close()
        AppDatabase.resetForTests(context, deleteExisting = false)
        database = AppDatabase.getInstance(context)
        val transferDate = database.readableDatabase.rawQuery(
            "SELECT operating_date FROM service_transfers WHERE id='transfer-1'", null
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        val walletDate = database.readableDatabase.rawQuery(
            "SELECT operating_date FROM service_wallet_transactions WHERE id='wallet-1'", null
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        assertEquals("2026-11-03", transferDate)
        assertEquals("2026-11-03", walletDate)
        assertEquals(23, database.readableDatabase.version)
    }

}
