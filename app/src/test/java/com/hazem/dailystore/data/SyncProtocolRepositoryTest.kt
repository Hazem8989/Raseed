package com.hazem.dailystore.data

import android.content.ContentValues
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import javax.crypto.spec.SecretKeySpec
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncProtocolRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: SyncProtocolRepository
    private lateinit var vault: SyncCredentialVault
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
        vault = SyncCredentialVault(context) { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
        repository = SyncProtocolRepository(context, vault)
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

    @Test fun secondaryEnrollmentStoresCredentialEncryptedAndKeepsAuthorityRemote() {
        val primaryId = UUID.randomUUID().toString()
        val secretBytes = ByteArray(32) { (it + 11).toByte() }
        val credential = android.util.Base64.encodeToString(
            secretBytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        repository.configureAsSecondary(adminId, "store-remote-01", primaryId, credential, "الهاتف الثاني")
        assertTrue(repository.isConfiguredSecondary())
        assertFalse(repository.hasConfiguredPrimary())
        assertTrue(repository.secondaryCredential()!!.contentEquals(secretBytes))
        database.readableDatabase.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='authority_device_id'", null
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(primaryId, c.getString(0)) }
        val storedValues = RuntimeEnvironment.getApplication()
            .getSharedPreferences("the_daily_shop_sync_credentials", android.content.Context.MODE_PRIVATE)
            .all.values
        assertTrue(storedValues.isNotEmpty())
        assertFalse(storedValues.any { it == credential })
    }

    @Test fun cashierCannotConfigurePrimaryOrCreateInvitations() {
        val auth = AuthRepository(RuntimeEnvironment.getApplication())
        val cashierId = auth.createUser("ADMIN", adminId, "sync-cashier", "Cashier", "Cashier12345", "CASHIER")
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
            db, adminId, operationId, "SALE_POSTED", "SALES_INVOICE", "sale-1",
            "CREATE", """{"invoice_id":"sale-1","total_usd_cents":1250}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()
        assertEquals(1L, first)
        assertEquals(1, repository.pendingOutbox().size)

        db.beginTransaction()
        val replay = repository.enqueueLocalOperation(
            db, adminId, operationId, "SALE_POSTED", "SALES_INVOICE", "sale-1",
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
                db, adminId, operationId, "SALE_POSTED", "SALES_INVOICE", "sale-1",
                "CREATE", """{"invoice_id":"sale-1","total_usd_cents":9999}"""
            )
        } catch (_: IllegalArgumentException) { mismatchRejected = true }
        finally { db.endTransaction() }
        assertTrue(mismatchRejected)
        assertEquals(1, repository.pendingOutbox().size)
    }

    @Test fun primarySignsCanonicalOutboxForEnrolledSecondary() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val invitation = repository.createPairingInvitation(adminId)
        val remoteId = UUID.randomUUID().toString()
        repository.enrollSecondaryDevice(adminId, invitation.token, remoteId, "الثانوي")
        val operationId = UUID.randomUUID().toString()
        val db = database.writableDatabase
        db.beginTransaction()
        repository.enqueueLocalOperation(
            db, adminId, operationId, "SALE_POSTED", "SALES_INVOICE", "sale-verified",
            "CREATE", """{"invoice_id":"sale-verified","total_usd_cents":1250}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()

        val envelope = repository.authenticatedOutbox(operationId, remoteId)
        val recipientSecret = vault.load(remoteId)!!
        assertTrue(SyncEnvelopeSecurity.verify(envelope, recipientSecret))
        assertEquals("CREATE", envelope.operationType)
        assertEquals("SALE_POSTED", org.json.JSONObject(envelope.payloadJson).getString("business_operation"))
        recipientSecret.fill(0)
    }

    @Test fun secondaryCommandIsQueuedAndSignedWithItsOwnCredentialWithoutPostingLocally() {
        val primaryId = UUID.randomUUID().toString()
        val secretBytes = ByteArray(32) { (it + 31).toByte() }
        val credential = android.util.Base64.encodeToString(
            secretBytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        repository.configureAsSecondary(adminId, "store-command-01", primaryId, credential, "الثانوي")
        val operationId = UUID.randomUUID().toString()
        val db = database.writableDatabase
        db.beginTransaction()
        val sequence = repository.enqueueSecondaryCommand(
            db, adminId, operationId, "POST_SALES_INVOICE", "SALES_INVOICE", "sale-command-1",
            """{"invoice_id":"sale-command-1","total_usd_cents":2500}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()

        assertEquals(1L, sequence)
        assertEquals(1, repository.pendingOutbox().size)
        assertEquals(0, db.rawQuery("SELECT COUNT(*) FROM sales_invoices", null).use { it.moveToFirst(); it.getInt(0) })
        val envelope = repository.authenticatedOutbox(operationId, primaryId)
        assertEquals(SyncDeviceIdentity.getOrCreate(RuntimeEnvironment.getApplication()), envelope.originDeviceId)
        assertTrue(SyncEnvelopeSecurity.verify(envelope, secretBytes))
        assertEquals("POST_SALES_INVOICE", org.json.JSONObject(envelope.payloadJson).getString("business_operation"))
        secretBytes.fill(0)
    }

    @Test fun primaryDurablyReceivesSecondaryCommandAndHandlesDuplicateAndHashConflict() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val invitation = repository.createPairingInvitation(adminId)
        val secondaryId = UUID.randomUUID().toString()
        repository.enrollSecondaryDevice(adminId, invitation.token, secondaryId, "الثانوي")
        val secret = vault.load(secondaryId)!!
        val operationId = UUID.randomUUID().toString()
        val envelope = SyncEnvelopeSecurity.create(
            database.readableDatabase.rawQuery(
                "SELECT state_value FROM sync_protocol_state WHERE state_key='store_id'", null
            ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) },
            operationId, 1L, secondaryId, adminId, "CREATE", "SALES_INVOICE",
            "sale-incoming-1", 0L, """{"invoice_id":"sale-incoming-1","total_usd_cents":2500}""",
            "2026-11-03T12:00:00Z", secret
        )
        val encoded = SyncEnvelopeSecurity.encode(envelope)
        assertEquals(SyncInboxDisposition.RECEIVED, repository.recordIncomingFromSecondary(encoded))
        assertEquals(SyncInboxDisposition.DUPLICATE, repository.recordIncomingFromSecondary(encoded))
        val changed = SyncEnvelopeSecurity.create(
            envelope.storeId, operationId, 1L, secondaryId, adminId, "CREATE", "SALES_INVOICE",
            "sale-incoming-1", 0L, """{"invoice_id":"sale-incoming-1","total_usd_cents":9900}""",
            "2026-11-03T12:00:01Z", secret
        )
        assertEquals(SyncInboxDisposition.CONFLICT,
            repository.recordIncomingFromSecondary(SyncEnvelopeSecurity.encode(changed)))
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_inbox WHERE operation_id=?", arrayOf(operationId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals("RECEIVED", c.getString(0)) }
        database.readableDatabase.rawQuery(
            "SELECT last_sequence FROM sync_cursors WHERE device_id=?", arrayOf(secondaryId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0)) }
        secret.fill(0)
    }

    @Test fun primaryRejectsSequenceGapWithoutAdvancingDurableCursor() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val invitation = repository.createPairingInvitation(adminId)
        val secondaryId = UUID.randomUUID().toString()
        repository.enrollSecondaryDevice(adminId, invitation.token, secondaryId, "الثانوي")
        val secret = vault.load(secondaryId)!!
        val storeId = database.readableDatabase.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='store_id'", null
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        val gap = SyncEnvelopeSecurity.create(
            storeId, UUID.randomUUID().toString(), 2L, secondaryId, adminId, "CREATE",
            "SALES_INVOICE", "sale-gap", 0L, """{"invoice_id":"sale-gap"}""",
            "2026-11-03T12:00:00Z", secret
        )
        var rejected = false
        try { repository.recordIncomingFromSecondary(SyncEnvelopeSecurity.encode(gap)) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        database.readableDatabase.rawQuery(
            "SELECT last_sequence FROM sync_cursors WHERE device_id=?", arrayOf(secondaryId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(0L, c.getLong(0)) }
        assertEquals(0, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_inbox", null
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        secret.fill(0)
    }

    @Test fun noOutboxIsCreatedBeforePrimaryDeviceIsConfigured() {
        val db = database.writableDatabase
        db.beginTransaction()
        val result = repository.enqueueLocalOperation(
            db, adminId, UUID.randomUUID().toString(), "SALE_POSTED", "SALES_INVOICE", "sale-1",
            "CREATE", """{"invoice_id":"sale-1"}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()
        assertNull(result)
        assertTrue(repository.pendingOutbox().isEmpty())
    }
}
