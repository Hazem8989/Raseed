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
import java.util.Date

@RunWith(RobolectricTestRunner::class)
class StoreTimeTest {
    private lateinit var database: AppDatabase

    @Before fun setUp() {
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
}
