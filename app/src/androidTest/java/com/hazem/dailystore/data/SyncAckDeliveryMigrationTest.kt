package com.hazem.dailystore.data

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class SyncAckDeliveryMigrationTest {
    @Test fun upgradesVersion28DatabaseWithoutDroppingExistingSyncTables() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val name = "sync_ack_delivery_migration_test.db"
        context.deleteDatabase(name)

        val old = AppDatabase(context, name)
        val oldSql = old.writableDatabase
        assertTrue(tableExists(oldSql, "sync_inbox"))
        assertTrue(tableExists(oldSql, "sync_queue"))
        oldSql.execSQL("DROP TABLE IF EXISTS sync_ack_delivery")
        oldSql.execSQL("PRAGMA user_version=28")
        old.close()

        val upgraded = AppDatabase(context, name)
        val db = upgraded.writableDatabase
        try {
            assertEquals(AppDatabase.DATABASE_VERSION, db.version)
            assertTrue(tableExists(db, "sync_ack_delivery"))
            assertTrue(indexExists(db, "idx_sync_ack_delivery_retry"))
            assertTrue(tableExists(db, "sync_inbox"))
            assertTrue(tableExists(db, "sync_queue"))
        } finally {
            upgraded.close()
            context.deleteDatabase(name)
        }
    }

    private fun tableExists(db: net.zetetic.database.sqlcipher.SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name))
            .use { it.moveToFirst() }

    private fun indexExists(db: net.zetetic.database.sqlcipher.SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name=?", arrayOf(name))
            .use { it.moveToFirst() }
}
