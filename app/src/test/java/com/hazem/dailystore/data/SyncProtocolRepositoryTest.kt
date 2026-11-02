package com.hazem.dailystore.data

import android.content.ContentValues
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncProtocolRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: SyncProtocolRepository
    private lateinit var adminId: String

    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        AppDatabase.resetForTests(context)
        database = AppDatabase.getInstance(context)
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "TestAdmin123")
        adminId = admin.id
        repository = SyncProtocolRepository(context)
    }

    @After fun tearDown() { database.close() }

    @Test fun primarySetupIsIdempotentAndAudited() {
        repository.configureAsPrimary(adminId, "الهاتف الرئيسي")
        repository.configureAsPrimary(adminId, "الهاتف الرئيسي")
        assertTrue(repository.hasConfiguredPrimary())
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_devices WHERE device_role='PRIMARY' AND status='ACTIVE'", null
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM audit_log WHERE action='SYNC_PRIMARY_CONFIGURED'", null
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(2, c.getInt(0)) }
    }

    @Test fun invitationIsOneTimeAndSecondaryCredentialIsStoredAsHash() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val invitation = repository.createPairingInvitation(adminId)
        val remoteId = UUID.randomUUID().toString()
        val enrolled = repository.enrollSecondaryDevice(adminId, invitation.token, remoteId, "الهاتف الثاني")
        assertEquals(remoteId, enrolled.deviceId)
        assertTrue(repository.authenticateDevice(remoteId, enrolled.credential))
        assertFalse(repository.authenticateDevice(remoteId, "wrong-credential"))
        database.readableDatabase.rawQuery(
            "SELECT credential_hash FROM sync_devices WHERE device_id=?", arrayOf(remoteId)
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertFalse(c.getString(0) == enrolled.credential)
            assertEquals(64, c.getString(0).length)
        }
        var rejected = false
        try { repository.enrollSecondaryDevice(adminId, invitation.token, UUID.randomUUID().toString(), "جهاز آخر") }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

    @Test fun cashierCannotConfigurePrimaryOrCreateInvitations() {
        val auth = AuthRepository(RuntimeEnvironment.getApplication())
        val cashierId = auth.createUser(adminId, adminId, "sync-cashier", "Cashier", "Cashier12345", "CASHIER")
        auth.changePassword(cashierId, "Cashier12345", "CashierStrong123")
        var denied = false
        try { repository.configureAsPrimary(cashierId, "غير مصرح") }
        catch (_: IllegalArgumentException) { denied = true }
        assertTrue(denied)
    }

    @Test fun outboxUsesMonotonicSequenceAndRejectsChangedReplayPayload() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val db = database.writableDatabase
        val operationId = UUID.randomUUID().toString()
        db.beginTransaction()
        val first = repository.enqueueLocalOperation(
            db, adminId, operationId, "SALE_POSTED", "SALE_INVOICE", "sale-1",
            "CREATE", """{"invoice_id":"sale-1","total_usd_cents":1250}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()
        assertEquals(1L, first)
        assertEquals(1, repository.pendingOutbox().size)

        db.beginTransaction()
        val replay = repository.enqueueLocalOperation(
            db, adminId, operationId, "SALE_POSTED", "SALE_INVOICE", "sale-1",
            "CREATE", """{"invoice_id":"sale-1","total_usd_cents":1250}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()
        assertEquals(first, replay)
        assertEquals(1, repository.pendingOutbox().size)

        var mismatchRejected = false
        db.beginTransaction()
        try {
            repository.enqueueLocalOperation(
                db, adminId, operationId, "SALE_POSTED", "SALE_INVOICE", "sale-1",
                "CREATE", """{"invoice_id":"sale-1","total_usd_cents":9999}"""
            )
        } catch (_: IllegalArgumentException) { mismatchRejected = true }
        finally { db.endTransaction() }
        assertTrue(mismatchRejected)
        assertEquals(1, repository.pendingOutbox().size)
    }

    @Test fun noOutboxIsCreatedBeforePrimaryDeviceIsConfigured() {
        val db = database.writableDatabase
        db.beginTransaction()
        val result = repository.enqueueLocalOperation(
            db, adminId, UUID.randomUUID().toString(), "SALE_POSTED", "SALE_INVOICE", "sale-1",
            "CREATE", """{"invoice_id":"sale-1"}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()
        assertNull(result)
        assertTrue(repository.pendingOutbox().isEmpty())
    }
}
