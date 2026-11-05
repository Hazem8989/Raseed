package com.hazem.dailystore.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** Real two-database sync path: ECDSA/AES-GCM, domain posting, signed ACK and replay. */
@RunWith(RobolectricTestRunner::class)
class TwoDeviceSyncIntegrationTest {
    private lateinit var context: Context
    private lateinit var primaryDb: AppDatabase
    private lateinit var secondaryDb: AppDatabase
    private lateinit var primaryRepository: SyncProtocolRepository
    private lateinit var secondaryRepository: SyncProtocolRepository
    private lateinit var primaryId: String
    private lateinit var secondaryId: String
    private lateinit var primaryAdminId: String
    private lateinit var secondaryAdminId: String
    private lateinit var operationId: String
    private lateinit var outgoingFrame: String
    private lateinit var cashAccountId: String

    private val vaultKey = SecretKeySpec(ByteArray(32) { 7 }, "AES")

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SyncDeviceTestKeyProvider.install()
        context.deleteDatabase(PRIMARY_DATABASE)
        context.deleteDatabase(SECONDARY_DATABASE)
        context.getSharedPreferences("the_daily_shop_sync_identity", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("the_daily_shop_sync_credentials", Context.MODE_PRIVATE).edit().clear().commit()

        primaryId = UUID.randomUUID().toString()
        secondaryId = UUID.randomUUID().toString()
        setLocalDeviceId(primaryId)
        primaryDb = AppDatabase(context, PRIMARY_DATABASE)
        AppDatabase.installForTests(primaryDb)
        StoreRepository(context).ensureBaseSetup()
        primaryAdminId = createReadyAdmin()
        val primaryVault = SyncCredentialVault(context) { vaultKey }
        primaryRepository = SyncProtocolRepository(context, primaryVault)
        primaryRepository.configureAsPrimary(primaryAdminId, "جهاز التكامل الأساسي")

        val invitation = primaryRepository.createPairingInvitation(primaryAdminId)
        val remoteIdentity = SyncDeviceAuthenticator.getOrCreate(context, secondaryId)
        val remotePublicKey = Base64.encodeToString(remoteIdentity.public.encoded, Base64.NO_WRAP)
        val enrollment = primaryRepository.enrollSecondaryDevice(
            primaryAdminId, invitation.token, secondaryId, "جهاز التكامل الثانوي", remotePublicKey
        )
        cashAccountId = UUID.randomUUID().toString()
        primaryDb.writableDatabase.execSQL(
            """INSERT INTO cash_accounts(id,name,account_type,currency_code,opening_balance_minor,is_active,created_at)
               VALUES(?,?, 'CASH_BOX','USD',50000,1,?)""",
            arrayOf(cashAccountId, "صندوق اختبار التكامل", "2026-11-04T10:00:00Z")
        )
        primaryRepository.mapSecondaryCashAccount(primaryAdminId, secondaryId, "secondary-cash-usd", cashAccountId)

        secondaryDb = AppDatabase(context, SECONDARY_DATABASE)
        AppDatabase.installForTests(secondaryDb)
        StoreRepository(context).ensureBaseSetup()
        secondaryAdminId = createReadyAdmin()
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        primaryRepository.mapSecondaryActor(primaryAdminId, secondaryId, secondaryAdminId, primaryAdminId)
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        secondaryRepository = SyncProtocolRepository(context, SyncCredentialVault(context) { vaultKey })
        secondaryRepository.configureAsSecondary(
            secondaryAdminId, enrollment.storeId, primaryId, enrollment.credential,
            "جهاز التكامل الثانوي", enrollment.storeTimeZoneId,
            enrollment.primaryPublicKeyBase64!!, enrollment.deviceCertificateJson!!
        )

        // Sequence 1 is a harmless authenticated transport probe. The secondary
        // must not originate expense, collection, return, or inventory commands.
        operationId = secondaryRepository.enqueueTransportProbe(secondaryAdminId)
        outgoingFrame = secondaryRepository.encryptedAuthenticatedOutbox(operationId, primaryId)
        assertEquals("PENDING", queueStatus(secondaryDb, operationId))
    }

    @After fun tearDown() {
        runCatching { primaryDb.close() }
        runCatching { secondaryDb.close() }
        context.deleteDatabase(PRIMARY_DATABASE)
        context.deleteDatabase(SECONDARY_DATABASE)
    }

    @Test fun twoIndependentDevicesExchangeAuthenticatedProbeAndSignedAckExactlyOnce() {
        var ack: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            sendFrame = { id, recipient, frame ->
                assertEquals(operationId, id)
                assertEquals(secondaryId, recipient)
                ack = frame
                true
            }
        )
        primaryProcessor.onEncryptedFrame(secondaryId, operationId, outgoingFrame)
        assertEquals("APPLIED", inboxStatus(primaryDb, operationId))
        val firstAck = ack ?: throw AssertionError("Primary did not acknowledge the authenticated probe")
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val secondaryProcessor = SyncRelayProcessor(
            secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true }
        )
        secondaryProcessor.onEncryptedFrame(primaryId, operationId, firstAck)
        assertEquals("SYNCED", queueStatus(secondaryDb, operationId))
        assertEquals(0, primaryDb.readableDatabase.rawQuery("SELECT COUNT(*) FROM operating_expenses", null)
            .use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, scalar(primaryDb, "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?", operationId))

        ack = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        primaryProcessor.onEncryptedFrame(secondaryId, operationId, outgoingFrame)
        val repeatedAck = ack ?: throw AssertionError("Primary did not re-issue the duplicate probe ACK")
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        secondaryProcessor.onEncryptedFrame(primaryId, operationId, repeatedAck)
        assertEquals("SYNCED", queueStatus(secondaryDb, operationId))
        assertEquals(1, scalar(primaryDb, "SELECT COUNT(*) FROM sync_inbox WHERE operation_id=?", operationId))
    }

    @Test fun twoIndependentDevicesExchangeTransportProbeAfterActorMappingIsRemoved() {
        var firstAck: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            sendFrame = { _, recipient, frame -> assertEquals(secondaryId, recipient); firstAck = frame; true }
        )
        primaryProcessor.onEncryptedFrame(secondaryId, operationId, outgoingFrame)
        assertEquals("APPLIED", inboxStatus(primaryDb, operationId))
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val secondaryProcessor = SyncRelayProcessor(
            secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true }
        )
        secondaryProcessor.onEncryptedFrame(primaryId, operationId, firstAck!!)
        assertEquals("SYNCED", queueStatus(secondaryDb, operationId))

        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        primaryDb.writableDatabase.update(
            "sync_actor_mappings", android.content.ContentValues().apply { put("is_active", 0) },
            "device_id=?", arrayOf(secondaryId)
        )
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val probeId = secondaryRepository.enqueueTransportProbe(secondaryAdminId)
        val probeFrame = secondaryRepository.encryptedAuthenticatedOutbox(probeId, primaryId)
        var probeAck: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryProbeProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            sendFrame = { id, recipient, frame ->
                assertEquals(probeId, id); assertEquals(secondaryId, recipient); probeAck = frame; true
            }
        )
        primaryProbeProcessor.onEncryptedFrame(secondaryId, probeId, probeFrame)
        assertEquals("APPLIED", inboxStatus(primaryDb, probeId))
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        secondaryProcessor.onEncryptedFrame(primaryId, probeId, probeAck!!)
        assertEquals("SYNCED", queueStatus(secondaryDb, probeId))
        assertEquals(0, primaryDb.readableDatabase.rawQuery("SELECT COUNT(*) FROM operating_expenses", null)
            .use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun twoIndependentDevicesSyncCashSaleStockAndFinancialEffectsExactlyOnce() {
        // The shared setup queued an expense as sequence 1. Deliver and ACK it
        // before the sale (sequence 2), preserving the receiver's strict cursor.
        var priorExpenseAck: String? = null
        var priorExpenseProtocolError: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val priorExpenseProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) {
                    priorExpenseProtocolError = message
                }
            },
            sendFrame = { id, recipient, frame ->
                assertEquals(operationId, id)
                assertEquals(secondaryId, recipient)
                priorExpenseAck = frame
                true
            }
        )
        priorExpenseProcessor.onEncryptedFrame(secondaryId, operationId, outgoingFrame)
        assertEquals("لا ينبغي رفض الأمر السابق: $priorExpenseProtocolError", null, priorExpenseProtocolError)
        assertEquals("APPLIED", inboxStatus(primaryDb, operationId))
        val acceptedPriorAck = priorExpenseAck
            ?: throw AssertionError("Primary did not issue the prior expense application ACK")

        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val priorAckProcessor = SyncRelayProcessor(
            secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true }
        )
        priorAckProcessor.onEncryptedFrame(primaryId, operationId, acceptedPriorAck)
        assertEquals("SYNCED", queueStatus(secondaryDb, operationId))

        // Prepare authoritative product and opening stock only on the primary database.
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val store = StoreRepository(context)
        val unit = store.getUnits().first()
        val category = store.getCategories().first()
        val productId = store.saveProduct(
            null, "منتج اختبار المزامنة", null, null, category.id, unit.id,
            100, 150, true, 10.0, primaryAdminId
        )
        val warehouseId = primaryDb.readableDatabase.rawQuery(
            "SELECT id FROM warehouses WHERE is_default=1 AND is_active=1 LIMIT 1", null
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        // saveProduct(... openingQuantity = 10.0) creates the opening balance,
        // movement and FIFO cost layer atomically; do not insert a duplicate balance.

        val invoiceId = "secondary-sale-" + UUID.randomUUID()
        val saleOperationId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("business_operation", "POST_SALE_INVOICE")
            .put("id", invoiceId)
            .put("party_id", JSONObject.NULL)
            .put("sale_type", "CASH")
            .put("invoice_date", "2026-11-04")
            .put("discount_usd_cents", 0L)
            .put("total_usd_cents", 300L)
            .put("notes", JSONObject.NULL)
            .put("exchange_rate_syp_per_usd_milli", 10_000_000L)
            .put("posted_at", "2026-11-04T11:15:00Z")
            .put("posted_operating_date", "2026-11-04")
            .put("cash_account_id", "secondary-cash-usd")
            .put("cash_account_type", "CASH_BOX")
            .put("cash_account_currency_code", "USD")
            .put("cash_receipts", org.json.JSONArray().put(JSONObject()
                .put("cash_transaction_id", "remote-sale-receipt-$invoiceId")
                .put("amount_minor", 300L)
                .put("currency_code", "USD")
                .put("exchange_rate_syp_per_usd_milli", 10_000_000L)))
            .put("items", org.json.JSONArray().put(JSONObject()
                .put("product_id", productId)
                .put("quantity", "2")
                .put("unit_price_or_cost_usd_cents", 150L))
            )
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        secondaryDb.writableDatabase.beginTransaction()
        try {
            secondaryRepository.enqueueSecondaryCommand(
                secondaryDb.writableDatabase, secondaryAdminId, saleOperationId,
                "POST_SALE_INVOICE", "SALES_INVOICE", invoiceId, payload.toString()
            )
            secondaryDb.writableDatabase.setTransactionSuccessful()
        } finally {
            secondaryDb.writableDatabase.endTransaction()
        }
        val saleFrame = secondaryRepository.encryptedAuthenticatedOutbox(saleOperationId, primaryId)
        assertEquals("PENDING", queueStatus(secondaryDb, saleOperationId))

        var saleAck: String? = null
        var saleProtocolError: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) {
                    saleProtocolError = message
                }
            },
            sendFrame = { id, recipient, frame ->
                assertEquals(saleOperationId, id)
                assertEquals(secondaryId, recipient)
                saleAck = frame
                true
            }
        )
        primaryProcessor.onEncryptedFrame(secondaryId, saleOperationId, saleFrame)
        // Keep the primary-side rejection visible in the CI JUnit XML report.
        assertEquals("لا ينبغي رفض أمر البيع: $saleProtocolError", null, saleProtocolError)
        assertEquals("PAID", primaryDb.readableDatabase.rawQuery(
            "SELECT status FROM sales_invoices WHERE id=?", arrayOf(invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals(8.0, primaryDb.readableDatabase.rawQuery(
            "SELECT quantity FROM inventory_balances WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId, warehouseId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getDouble(0) }, 0.000001)
        assertEquals(1, scalar(primaryDb,
            "SELECT COUNT(*) FROM inventory_movements WHERE source_id=? AND movement_type='SALE'", invoiceId))
        assertEquals(1, scalar(primaryDb,
            "SELECT COUNT(*) FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?", invoiceId))
        assertEquals("APPLIED", inboxStatus(primaryDb, saleOperationId))

        val ack = saleAck ?: throw AssertionError("Primary did not issue the sales application ACK")
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val secondaryProcessor = SyncRelayProcessor(
            secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true }
        )
        secondaryProcessor.onEncryptedFrame(primaryId, saleOperationId, ack)
        assertEquals("SYNCED", queueStatus(secondaryDb, saleOperationId))

        // A replay after the ACK must not post another invoice, stock movement, or receipt.
        saleAck = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        primaryProcessor.onEncryptedFrame(secondaryId, saleOperationId, saleFrame)
        val repeatedAck = saleAck ?: throw AssertionError("Primary did not re-issue the duplicate sales ACK")
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        secondaryProcessor.onEncryptedFrame(primaryId, saleOperationId, repeatedAck)
        assertEquals(1, scalar(primaryDb, "SELECT COUNT(*) FROM sales_invoices WHERE id=?", invoiceId))
        assertEquals(1, scalar(primaryDb,
            "SELECT COUNT(*) FROM inventory_movements WHERE source_id=? AND movement_type='SALE'", invoiceId))
        assertEquals(1, scalar(primaryDb,
            "SELECT COUNT(*) FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?", invoiceId))
        assertEquals(8.0, primaryDb.readableDatabase.rawQuery(
            "SELECT quantity FROM inventory_balances WHERE product_id=? AND warehouse_id=?",
            arrayOf(productId, warehouseId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getDouble(0) }, 0.000001)
        assertEquals("SYNCED", queueStatus(secondaryDb, saleOperationId))
    }

    @Test fun twoIndependentDevicesRejectCustomerCollectionFromSecondary() {
        var probeAck: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            sendFrame = { _, recipient, frame ->
                assertEquals(secondaryId, recipient)
                probeAck = frame
                true
            }
        )
        primaryProcessor.onEncryptedFrame(secondaryId, operationId, outgoingFrame)
        assertEquals("APPLIED", inboxStatus(primaryDb, operationId))
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val secondaryProcessor = SyncRelayProcessor(
            secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true }
        )
        secondaryProcessor.onEncryptedFrame(primaryId, operationId, probeAck!!)
        assertEquals("SYNCED", queueStatus(secondaryDb, operationId))

        val beforeOutbox = scalar(secondaryDb,
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE origin_device_id=?", secondaryId)
        val operationId = "customer-debt-" + UUID.randomUUID()
        var rejected = false
        try {
            CustomerPaymentRepository(context).addPayment(
                secondaryAdminId, "credit-invoice-not-available", operationId,
                "secondary-wallet-not-available", 15_000L, "تحصيل ممنوع من الجهاز الثانوي"
            )
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue("Secondary must not collect customer debt", rejected)
        val secondarySql = secondaryDb.writableDatabase
        var directCommandRejected = false
        secondarySql.beginTransaction()
        try {
            try {
                secondaryRepository.enqueueSecondaryCommand(
                    secondarySql, secondaryAdminId, "forged-expense-" + UUID.randomUUID(),
                    "POST_OPERATING_EXPENSE", "OPERATING_EXPENSE", "forged-expense", "{}"
                )
            } catch (_: IllegalArgumentException) { directCommandRejected = true }
            assertTrue("Outbox must independently reject non-sale command types", directCommandRejected)
            secondarySql.setTransactionSuccessful()
        } finally { secondarySql.endTransaction() }
        assertEquals(beforeOutbox, scalar(secondaryDb,
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE origin_device_id=?", secondaryId))
        assertEquals(0, scalar(secondaryDb,
            "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?", "credit-invoice-not-available"))
        assertEquals(0, scalar(secondaryDb,
            "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?", operationId))
    }

    private fun createReadyAdmin(): String {
        val auth = AuthRepository(context)
        auth.ensureInitialAdmin()
        val admin = auth.authenticate("admin", "1234")!!
        auth.changePassword(admin.id, "1234", "TestAdmin123")
        return admin.id
    }

    private fun setLocalDeviceId(deviceId: String) {
        check(context.getSharedPreferences("the_daily_shop_sync_identity", Context.MODE_PRIVATE)
            .edit().putString("device_id", deviceId).commit())
    }

    private fun queueStatus(db: AppDatabase, id: String): String =
        db.readableDatabase.rawQuery("SELECT status FROM sync_queue WHERE operation_id=?", arrayOf(id))
            .use { c -> assertTrue(c.moveToFirst()); c.getString(0) }

    private fun inboxStatus(db: AppDatabase, id: String): String =
        db.readableDatabase.rawQuery("SELECT status FROM sync_inbox WHERE operation_id=?", arrayOf(id))
            .use { c -> assertTrue(c.moveToFirst()); c.getString(0) }

    private fun scalar(db: AppDatabase, sql: String, arg: String): Int =
        db.readableDatabase.rawQuery(sql, arrayOf(arg)).use { c -> assertTrue(c.moveToFirst()); c.getInt(0) }

    private fun scalarLong(db: AppDatabase, sql: String, first: String, second: String): Long =
        db.readableDatabase.rawQuery(sql, arrayOf(first, second)).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }

    companion object {
        private const val PRIMARY_DATABASE = "sync-integration-primary.db"
        private const val SECONDARY_DATABASE = "sync-integration-secondary.db"
    }
}
