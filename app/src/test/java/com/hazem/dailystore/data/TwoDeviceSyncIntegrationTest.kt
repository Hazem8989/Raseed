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
import java.math.BigDecimal
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
        cashAccountId = CashAccountRepository(context).create(
            primaryAdminId, "صندوق اختبار التكامل", "CASH_BOX", "USD", 50000L
        ).id

        val invitation = primaryRepository.createPairingInvitation(primaryAdminId)
        val remoteIdentity = SyncDeviceAuthenticator.getOrCreate(context, secondaryId)
        val remotePublicKey = Base64.encodeToString(remoteIdentity.public.encoded, Base64.NO_WRAP)
        val enrollment = primaryRepository.enrollSecondaryDevice(
            primaryAdminId, invitation.token, secondaryId, "جهاز التكامل الثانوي", remotePublicKey
        )
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

    @Test fun primaryOutboxNeverBroadcastsFinancialCommandsToSalesOnlySecondary() {
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)

        val legacyOperationId = UUID.randomUUID().toString()
        val now = "2026-11-04T10:30:00.000Z"
        val db = primaryDb.writableDatabase
        db.beginTransaction()
        try {
            // Keep the persisted allocator consistent with the legacy envelope inserted below.
            val nextSequence = db.rawQuery(
                "SELECT state_value FROM sync_protocol_state WHERE state_key='next_sequence'", null
            ).use { c -> assertTrue(c.moveToFirst()); c.getString(0).toLong() }
            // Simulate a pending financial event written by an older app version.
            db.insertOrThrow("sync_operation_envelopes", null, android.content.ContentValues().apply {
                put("operation_id", legacyOperationId)
                put("sequence_number", nextSequence)
                put("origin_device_id", primaryId)
                put("actor_user_id", primaryAdminId)
                put("operation_type", "POST_PURCHASE_INVOICE")
                put("entity_type", "PURCHASE_INVOICE")
                put("entity_id", legacyOperationId)
                put("payload_json", JSONObject().put("business_operation", "POST_PURCHASE_INVOICE").toString())
                put("payload_sha256", "a".repeat(64))
                put("created_at", now)
            })
            db.insertOrThrow("sync_queue", null, android.content.ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("operation_id", legacyOperationId)
                put("entity_type", "PURCHASE_INVOICE")
                put("entity_id", legacyOperationId)
                put("operation_type", "CREATE")
                put("payload_json", "{}")
                put("status", "PENDING")
                put("retry_count", 0)
                put("created_at", now)
                put("updated_at", now)
            })
            db.execSQL(
                "UPDATE sync_protocol_state SET state_value=?, updated_at=? WHERE state_key='next_sequence'",
                arrayOf((nextSequence + 1L).toString(), now)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val openingStockOperationId = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            primaryRepository.enqueueLocalOperation(
                db, primaryAdminId, openingStockOperationId,
                "SET_PRODUCT_OPENING_STOCK", "PRODUCT_OPENING_STOCK", "existing-product",
                "CREATE", JSONObject().put("business_operation", "SET_PRODUCT_OPENING_STOCK").toString()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val pending = primaryRepository.pendingOutboxForDevice(secondaryId)
        assertTrue("Primary opening-stock updates must remain deliverable",
            pending.any { it.operationId == openingStockOperationId })
        assertTrue("Reference catalog events must remain deliverable",
            pending.any { it.entityType == "CATEGORY" })
        assertTrue("Standalone unit events must remain deliverable",
            pending.any { it.entityType == "UNIT" })
        assertFalse("A sales-only secondary must never receive historical purchase/financial events",
            pending.any { it.operationId == legacyOperationId })

        val newFinancialOperationId = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            val queued = primaryRepository.enqueueLocalOperation(
                db, primaryAdminId, newFinancialOperationId,
                "POST_OPERATING_EXPENSE", "OPERATING_EXPENSE", newFinancialOperationId,
                "CREATE", JSONObject().put("business_operation", "POST_OPERATING_EXPENSE").toString()
            )
            assertNull("Primary-only financial operations must not enter peer outbox", queued)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        assertEquals(0, scalar(primaryDb,
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE operation_id=?", newFinancialOperationId))
        assertFalse(primaryRepository.pendingOutboxForDevice(secondaryId)
            .any { it.operationId == newFinancialOperationId })
    }

    @Test fun twoIndependentDatabasesBootstrapStandaloneUnitsAndMapProductOpeningStock() {
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val db = primaryDb.writableDatabase
        db.execSQL(
            "INSERT INTO units(id,name,symbol,decimal_places) VALUES(500,?,?,0)",
            arrayOf("كيس اختبار", "كيس")
        )
        db.execSQL(
            "INSERT INTO units(id,name,symbol,decimal_places) VALUES(501,?,?,0)",
            arrayOf("وحدة مستقلة غير مستخدمة", "وحدة")
        )

        val updatedWalletId = CashAccountRepository(context).create(
            primaryAdminId, "محفظة اختبار", "WALLET", "USD", 7500L
        ).id
        CashAccountRepository(context).updateDetails(
            "ADMIN", primaryAdminId, updatedWalletId, "محفظة اختبار محدثة", "WALLET", "USD"
        )
        val bankAccountId = CashAccountRepository(context).create(
            primaryAdminId, "حساب بنكي اختباري", "BANK", "SYP", 2500L
        ).id

        db.beginTransaction()
        try {
            for ((unitId, name, symbol) in listOf(
                Triple(500L, "كيس اختبار", "كيس"),
                Triple(501L, "وحدة مستقلة غير مستخدمة", "وحدة")
            )) {
                val payload = JSONObject()
                    .put("business_operation", "UPSERT_UNIT")
                    .put("primary_unit_id", unitId)
                    .put("unit_name", name)
                    .put("unit_symbol", symbol)
                    .put("decimal_places", 0)
                primaryRepository.enqueueLocalOperation(
                    db, primaryAdminId, "bootstrap-unit-$unitId", "UPSERT_UNIT",
                    "UNIT", unitId.toString(), "CREATE", payload.toString()
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val category = StoreRepository(context).getCategories().first()
        val productId = StoreRepository(context).saveProduct(
            null, "منتج بوحدة مستقلة", null, null, category.id, 500L,
            250L, 350L, true, 4.0, primaryAdminId
        )
        val pending = primaryRepository.pendingOutboxForDevice(secondaryId)
        val productItem = pending.single { it.entityType == "PRODUCT" && it.entityId == productId }
        assertTrue("Pairing and account mutations must queue account metadata",
            pending.any { it.entityType == "CASH_ACCOUNT" && JSONObject(it.payloadJson).optString("cash_account_id") == cashAccountId })
        assertTrue(pending.any { it.entityType == "CASH_ACCOUNT" && JSONObject(it.payloadJson).optString("cash_account_id") == updatedWalletId })
        assertTrue(pending.any { it.entityType == "CASH_ACCOUNT" && JSONObject(it.payloadJson).optString("cash_account_id") == bankAccountId })
        val productEnvelope = primaryRepository.authenticatedOutbox(productItem.operationId, secondaryId)
        assertEquals(500L, JSONObject(productEnvelope.payloadJson).getLong("primary_unit_id"))
        val customUnitIndex = pending.indexOfFirst { it.entityType == "UNIT" && it.entityId == "500" }
        val productIndex = pending.indexOfFirst { it.operationId == productItem.operationId }
        assertTrue("The standalone unit must precede the product that references it",
            customUnitIndex >= 0 && customUnitIndex < productIndex)

        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val ackFrames = mutableMapOf<String, String>()
        var secondaryProtocolError: String? = null
        val secondaryProcessor = SyncRelayProcessor(
            secondaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) {
                    secondaryProtocolError = "$operationId: $message"
                }
            },
            sendFrame = { operationId, recipient, frame ->
                assertEquals(primaryId, recipient)
                ackFrames[operationId] = frame
                true
            }
        )
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true }
        )
        val frames = mutableMapOf<String, String>()
        for (item in pending) {
            setLocalDeviceId(primaryId)
            AppDatabase.installForTests(primaryDb)
            val frame = primaryRepository.encryptedAuthenticatedOutbox(item.operationId, secondaryId)
            frames[item.operationId] = frame

            setLocalDeviceId(secondaryId)
            AppDatabase.installForTests(secondaryDb)
            secondaryProcessor.onEncryptedFrame(primaryId, item.operationId, frame)
            assertEquals("تعذر تطبيق حدث دليل مستقل: $secondaryProtocolError", null, secondaryProtocolError)
            assertEquals("APPLIED", inboxStatus(secondaryDb, item.operationId))
            val ack = ackFrames[item.operationId]
                ?: throw AssertionError("لم يصدر الجهاز الثانوي إقرار التطبيق للعملية ${item.operationId}")

            setLocalDeviceId(primaryId)
            AppDatabase.installForTests(primaryDb)
            primaryProcessor.onEncryptedFrame(secondaryId, item.operationId, ack)
            assertEquals("SYNCED", queueStatus(primaryDb, item.operationId))
        }

        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val mappedUnitId = secondaryDb.readableDatabase.rawQuery(
            "SELECT secondary_unit_id FROM sync_unit_mappings WHERE primary_device_id=? AND primary_unit_id=500",
            arrayOf(primaryId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
        assertNotEquals("The secondary must use its own unit row ID", 500L, mappedUnitId)
        assertEquals(1, scalar(secondaryDb,
            "SELECT COUNT(*) FROM sync_unit_mappings WHERE primary_device_id=? AND primary_unit_id=501",
            primaryId))
        val productUnitId = secondaryDb.readableDatabase.rawQuery(
            "SELECT unit_id FROM products WHERE id=?", arrayOf(productId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
        assertEquals(mappedUnitId, productUnitId)
        assertEquals(4.0, secondaryDb.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(quantity),0) FROM inventory_balances WHERE product_id=?",
            arrayOf(productId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getDouble(0) }, 0.000001)
        assertEquals(1, scalar(secondaryDb,
            "SELECT COUNT(*) FROM inventory_movements WHERE product_id=? AND movement_type='OPENING'",
            productId))
        val mirroredCash = secondaryDb.readableDatabase.rawQuery(
            "SELECT name,account_type,currency_code,opening_balance_minor,is_active FROM cash_accounts WHERE id=?",
            arrayOf(cashAccountId)
        ).use { c ->
            assertTrue(c.moveToFirst())
            listOf(c.getString(0), c.getString(1), c.getString(2), c.getLong(3).toString(), c.getInt(4).toString())
        }
        assertEquals(listOf("صندوق اختبار التكامل", "CASH_BOX", "USD", "0", "1"), mirroredCash)
        val mirroredWallet = secondaryDb.readableDatabase.rawQuery(
            "SELECT name,account_type,currency_code,opening_balance_minor FROM cash_accounts WHERE id=?",
            arrayOf(updatedWalletId)
        ).use { c -> assertTrue(c.moveToFirst()); listOf(c.getString(0),c.getString(1),c.getString(2),c.getLong(3).toString()) }
        assertEquals(listOf("محفظة اختبار محدثة", "WALLET", "USD", "0"), mirroredWallet)
        assertEquals(1, scalar(secondaryDb,
            "SELECT COUNT(*) FROM cash_accounts WHERE id=? AND account_type='BANK' AND opening_balance_minor=0",
            bankAccountId))
        assertEquals("1", secondaryDb.readableDatabase.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key=?",
            arrayOf("cash_account_sales_allowed:$cashAccountId")
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals("0", secondaryDb.readableDatabase.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key=?",
            arrayOf("cash_account_sales_allowed:$bankAccountId")
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertFalse("Bank accounts must not appear as secondary POS receipt choices",
            CashAccountRepository(context).listActive().any { it.id == bankAccountId })
        assertEquals(1, primaryDb.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_cash_account_mappings WHERE device_id=? AND remote_cash_account_id=? AND primary_cash_account_id=? AND is_active=1",
            arrayOf(secondaryId, cashAccountId, cashAccountId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getInt(0) })

        val repeatedProductFrame = frames[productItem.operationId]!!
        secondaryProcessor.onEncryptedFrame(primaryId, productItem.operationId, repeatedProductFrame)
        assertEquals(4.0, secondaryDb.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(quantity),0) FROM inventory_balances WHERE product_id=?",
            arrayOf(productId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getDouble(0) }, 0.000001)
        assertEquals(1, scalar(secondaryDb,
            "SELECT COUNT(*) FROM inventory_movements WHERE product_id=? AND movement_type='OPENING'",
            productId))
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
        // Consume the secondary probe first so the sale is the next command in sequence.
        var probeAck: String? = null
        var probeError: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val probeProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) { probeError = message }
            },
            sendFrame = { _, recipient, frame ->
                assertEquals(secondaryId, recipient)
                probeAck = frame
                true
            }
        )
        probeProcessor.onEncryptedFrame(secondaryId, operationId, outgoingFrame)
        assertEquals("لم يقبل الجهاز الأساسي اختبار النقل: $probeError", null, probeError)
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val secondaryProcessor = SyncRelayProcessor(
            secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true }
        )
        secondaryProcessor.onEncryptedFrame(primaryId, operationId, probeAck!!)
        assertEquals("SYNCED", queueStatus(secondaryDb, operationId))

        // Create an authoritative exchange rate and product/opening stock on primary.
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val rateId = UUID.randomUUID().toString()
        val rateAt = "2026-11-04T11:00:00Z"
        val ratePayload = JSONObject()
            .put("business_operation", "SET_EXCHANGE_RATE")
            .put("id", rateId)
            .put("currency_code", "USD")
            .put("rate_syp_per_usd_milli", 10_000_000L)
            .put("effective_at", rateAt)
            .put("created_at", rateAt)
        val primarySql = primaryDb.writableDatabase
        primarySql.beginTransaction()
        try {
            primarySql.execSQL(
                "INSERT INTO exchange_rates(id,currency_code,rate_syp_per_usd_milli,effective_at,created_by,created_at) VALUES(?, 'USD', ?, ?, NULL, ?)",
                arrayOf(rateId, 10_000_000L, rateAt, rateAt)
            )
            primaryRepository.enqueueLocalOperation(
                primarySql, primaryAdminId, "exchange-rate-$rateId", "SET_EXCHANGE_RATE",
                "EXCHANGE_RATE", rateId, "CREATE", ratePayload.toString()
            )
            primarySql.setTransactionSuccessful()
        } finally { primarySql.endTransaction() }

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

        // Deliver all primary reference events in order, including account metadata.
        val referenceItems = primaryRepository.pendingOutboxForDevice(secondaryId)
        assertTrue(referenceItems.any { it.entityType == "CASH_ACCOUNT" && it.entityId == cashAccountId })
        assertTrue(referenceItems.any { it.entityType == "PRODUCT" && it.entityId == productId })
        val referenceAcks = mutableMapOf<String, String>()
        var secondaryReferenceError: String? = null
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val secondaryReferenceProcessor = SyncRelayProcessor(
            secondaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) {
                    secondaryReferenceError = "$operationId: $message"
                }
            },
            sendFrame = { id, recipient, frame ->
                assertEquals(primaryId, recipient)
                referenceAcks[id] = frame
                true
            }
        )
        for (item in referenceItems) {
            setLocalDeviceId(primaryId)
            AppDatabase.installForTests(primaryDb)
            val frame = primaryRepository.encryptedAuthenticatedOutbox(item.operationId, secondaryId)
            setLocalDeviceId(secondaryId)
            AppDatabase.installForTests(secondaryDb)
            secondaryReferenceProcessor.onEncryptedFrame(primaryId, item.operationId, frame)
            assertEquals("تعذر تطبيق المرجع الوارد: $secondaryReferenceError", null, secondaryReferenceError)
            assertEquals("APPLIED", inboxStatus(secondaryDb, item.operationId))
            val ack = referenceAcks[item.operationId]
                ?: throw AssertionError("لم يصدر إقرار تطبيق للمرجع ${item.operationId}")
            setLocalDeviceId(primaryId)
            AppDatabase.installForTests(primaryDb)
            val primaryAckProcessor = SyncRelayProcessor(
                primaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true }
            )
            primaryAckProcessor.onEncryptedFrame(secondaryId, item.operationId, ack)
            assertEquals("SYNCED", queueStatus(primaryDb, item.operationId))
        }

        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val secondaryCash = secondaryDb.readableDatabase.rawQuery(
            "SELECT account_type,currency_code,opening_balance_minor FROM cash_accounts WHERE id=?",
            arrayOf(cashAccountId)
        ).use { c -> assertTrue(c.moveToFirst()); Triple(c.getString(0),c.getString(1),c.getLong(2)) }
        assertEquals(Triple("CASH_BOX", "USD", 0L), secondaryCash)
        assertEquals("1", secondaryDb.readableDatabase.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key=?",
            arrayOf("cash_account_sales_allowed:$cashAccountId")
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals(10.0, secondaryDb.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(quantity),0) FROM inventory_balances WHERE product_id=?",
            arrayOf(productId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getDouble(0) }, 0.000001)

        // Exercise the real POS repository: save draft, post cash sale, then sync.
        val invoiceId = "secondary-sale-" + UUID.randomUUID()
        val saveOperationId = UUID.randomUUID().toString()
        val saleOperationId = UUID.randomUUID().toString()
        val saved = InvoiceRepository(context).saveSalesDraft(
            secondaryAdminId, saveOperationId, invoiceId, null, "2026-11-04",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal("2"), 150L)), "CASH"
        )
        assertEquals(invoiceId, saved.invoiceId)
        InvoiceRepository(context).postCashSale(
            secondaryAdminId, invoiceId, saleOperationId, cashAccountId,
            "2026-11-04T11:15:00Z", "2026-11-04"
        )
        assertEquals("PENDING", queueStatus(secondaryDb, saleOperationId))
        assertEquals(1, scalar(secondaryDb,
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE operation_id=? AND entity_id=?",
            saleOperationId, invoiceId))
        // Retrying with the same id is idempotent; a new id for this invoice is rejected.
        assertEquals(false, InvoiceRepository(context).postCashSale(
            secondaryAdminId, invoiceId, saleOperationId, cashAccountId,
            "2026-11-04T11:15:00Z", "2026-11-04"
        ))
        var duplicateTokenRejected = false
        try {
            InvoiceRepository(context).postCashSale(
                secondaryAdminId, invoiceId, UUID.randomUUID().toString(), cashAccountId,
                "2026-11-04T11:16:00Z", "2026-11-04"
            )
        } catch (_: IllegalArgumentException) {
            duplicateTokenRejected = true
        }
        assertTrue("A second token must not create a second invoice command", duplicateTokenRejected)
        assertEquals(1, scalar(secondaryDb,
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE entity_type='SALES_INVOICE' AND entity_id=?",
            invoiceId))
        assertEquals(0, scalar(secondaryDb,
            "SELECT COUNT(*) FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?",
            invoiceId))
        val saleFrame = secondaryRepository.encryptedAuthenticatedOutbox(saleOperationId, primaryId)

        var saleAck: String? = null
        var saleProtocolError: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primarySaleProcessor = SyncRelayProcessor(
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
        primarySaleProcessor.onEncryptedFrame(secondaryId, saleOperationId, saleFrame)
        assertEquals("لا ينبغي رفض البيع الناتج من InvoiceRepository: $saleProtocolError", null, saleProtocolError)
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

        val ack = saleAck ?: throw AssertionError("لم يرسل الأساسي إقرار تطبيق البيع")
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        secondaryProcessor.onEncryptedFrame(primaryId, saleOperationId, ack)
        assertEquals("SYNCED", queueStatus(secondaryDb, saleOperationId))

        // Replayed delivery and repeated signed ACK must not post financial or stock effects twice.
        saleAck = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        primarySaleProcessor.onEncryptedFrame(secondaryId, saleOperationId, saleFrame)
        val repeatedAck = saleAck ?: throw AssertionError("لم يُعد الأساسي إرسال ACK للبيع المكرر")
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


    @Test fun twoIndependentDevicesCollectCustomerDebtThroughSignedAckExactlyOnce() {
        // The fixture creates secondary sequence 1 as a transport probe. Deliver
        // and acknowledge it before later commands; the primary correctly rejects
        // sequence 2 if sequence 1 has not been committed to its inbound cursor.
        var probeAck: String? = null
        var probeError: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryProbeProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) { probeError = message }
            },
            sendFrame = { id, recipient, frame ->
                assertEquals(operationId, id)
                assertEquals(secondaryId, recipient)
                probeAck = frame
                true
            }
        )
        primaryProbeProcessor.onEncryptedFrame(secondaryId, operationId, outgoingFrame)
        assertEquals("تعذر تطبيق أمر التهيئة السابق: $probeError", null, probeError)
        assertEquals("APPLIED", inboxStatus(primaryDb, operationId))
        val signedProbeAck = probeAck ?: throw AssertionError("لم يصدر الأساسي ACK لأمر التهيئة")
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        SyncRelayProcessor(secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true })
            .onEncryptedFrame(primaryId, operationId, signedProbeAck)
        assertEquals("SYNCED", queueStatus(secondaryDb, operationId))

        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val rateId = UUID.randomUUID().toString()
        val rateAt = "2026-11-05T09:00:00.000Z"
        val primarySql = primaryDb.writableDatabase
        primarySql.beginTransaction()
        try {
            primarySql.execSQL(
                "INSERT INTO exchange_rates(id,currency_code,rate_syp_per_usd_milli,effective_at,created_by,created_at) VALUES(?, 'USD', ?, ?, NULL, ?)",
                arrayOf(rateId, 10_000_000L, rateAt, rateAt)
            )
            primaryRepository.enqueueLocalOperation(
                primarySql, primaryAdminId, "collection-rate-$rateId", "SET_EXCHANGE_RATE",
                "EXCHANGE_RATE", rateId, "CREATE",
                JSONObject().put("business_operation", "SET_EXCHANGE_RATE")
                    .put("id", rateId).put("currency_code", "USD")
                    .put("rate_syp_per_usd_milli", 10_000_000L)
                    .put("effective_at", rateAt).put("created_at", rateAt).toString()
            )
            primarySql.setTransactionSuccessful()
        } finally { primarySql.endTransaction() }

        val customer = CustomerRepository(context).create(primaryAdminId, "زبون اختبار تحصيل " + UUID.randomUUID())
        val category = StoreRepository(context).getCategories().first()
        val unit = StoreRepository(context).getUnits().first()
        val productId = StoreRepository(context).saveProduct(
            null, "صنف دين اختباري " + UUID.randomUUID(), null, null,
            category.id, unit.id, 100L, 150L, true, 10.0, primaryAdminId
        )
        val primaryWallet = CashAccountRepository(context).create(
            primaryAdminId, "محفظة تحصيل اختبار", "WALLET", "SYP", 0L
        )
        primaryRepository.mapSecondaryCashAccount(primaryAdminId, secondaryId, primaryWallet.id, primaryWallet.id)

        val referenceItems = primaryRepository.pendingOutboxForDevice(secondaryId)
        assertTrue(referenceItems.any { it.entityType == "CUSTOMER" && it.entityId == customer.id })
        assertTrue(referenceItems.any { it.entityType == "PRODUCT" && it.entityId == productId })
        assertTrue(referenceItems.any { it.entityType == "CASH_ACCOUNT" && it.entityId == primaryWallet.id })
        assertTrue(referenceItems.any { it.entityType == "EXCHANGE_RATE" && it.entityId == rateId })

        val referenceAcks = mutableMapOf<String, String>()
        var referenceError: String? = null
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val secondaryReferenceProcessor = SyncRelayProcessor(
            secondaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) { referenceError = "$operationId: $message" }
            },
            sendFrame = { id, recipient, frame ->
                assertEquals(primaryId, recipient); referenceAcks[id] = frame; true
            }
        )
        for (item in referenceItems) {
            setLocalDeviceId(primaryId)
            AppDatabase.installForTests(primaryDb)
            val frame = primaryRepository.encryptedAuthenticatedOutbox(item.operationId, secondaryId)
            setLocalDeviceId(secondaryId)
            AppDatabase.installForTests(secondaryDb)
            secondaryReferenceProcessor.onEncryptedFrame(primaryId, item.operationId, frame)
            assertEquals("فشل تطبيق بيانات المرجع: $referenceError", null, referenceError)
            assertEquals("APPLIED", inboxStatus(secondaryDb, item.operationId))
            val referenceAck = referenceAcks[item.operationId] ?: throw AssertionError("لم يصدر ACK مرجعي")
            setLocalDeviceId(primaryId)
            AppDatabase.installForTests(primaryDb)
            SyncRelayProcessor(primaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true })
                .onEncryptedFrame(secondaryId, item.operationId, referenceAck)
            assertEquals("SYNCED", queueStatus(primaryDb, item.operationId))
        }

        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        assertEquals(customer.id, secondaryDb.readableDatabase.rawQuery(
            "SELECT id FROM customers WHERE id=?", arrayOf(customer.id)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals("WALLET", secondaryDb.readableDatabase.rawQuery(
            "SELECT account_type FROM cash_accounts WHERE id=?", arrayOf(primaryWallet.id)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals(10.0, secondaryDb.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(quantity),0) FROM inventory_balances WHERE product_id=?",
            arrayOf(productId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getDouble(0) }, 0.000001)

        val invoiceId = "secondary-credit-" + UUID.randomUUID()
        val invoiceSaveOperationId = UUID.randomUUID().toString()
        val invoicePostOperationId = UUID.randomUUID().toString()
        InvoiceRepository(context).saveSalesDraft(
            secondaryAdminId, invoiceSaveOperationId, invoiceId, customer.id, "2026-11-05",
            0L, null, listOf(InvoiceLineInput(productId, BigDecimal("2"), 150L)), "CREDIT"
        )
        InvoiceRepository(context).postSale(
            secondaryAdminId, invoiceId, invoicePostOperationId, "2026-11-05T09:15:00.000Z", "2026-11-05"
        )
        val invoiceFrame = secondaryRepository.encryptedAuthenticatedOutbox(invoicePostOperationId, primaryId)
        var invoiceAck: String? = null
        var invoiceError: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryInvoiceProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) { invoiceError = message }
            },
            sendFrame = { id, recipient, frame ->
                assertEquals(invoicePostOperationId, id); assertEquals(secondaryId, recipient); invoiceAck = frame; true
            }
        )
        primaryInvoiceProcessor.onEncryptedFrame(secondaryId, invoicePostOperationId, invoiceFrame)
        assertEquals("تعذر اعتماد فاتورة الدين على الأساسي: $invoiceError", null, invoiceError)
        assertEquals("POSTED", primaryDb.readableDatabase.rawQuery(
            "SELECT status FROM sales_invoices WHERE id=?", arrayOf(invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals(customer.id, primaryDb.readableDatabase.rawQuery(
            "SELECT customer_id FROM sales_invoices WHERE id=?", arrayOf(invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        val postedInvoiceAck = invoiceAck ?: throw AssertionError("لم يصدر ACK لفاتورة الدين")
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        SyncRelayProcessor(secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true })
            .onEncryptedFrame(primaryId, invoicePostOperationId, postedInvoiceAck)
        assertEquals("SYNCED", queueStatus(secondaryDb, invoicePostOperationId))
        assertEquals("POSTED", secondaryDb.readableDatabase.rawQuery(
            "SELECT status FROM sales_invoices WHERE id=?", arrayOf(invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals(0, scalar(secondaryDb,
            "SELECT COUNT(*) FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?", invoiceId))

        val collectionOperationId = UUID.randomUUID().toString()
        val secondaryPayments = CustomerPaymentRepository(context)
        val collection = secondaryPayments.addPayment(
            secondaryAdminId, invoiceId, collectionOperationId, primaryWallet.id, 10_000L, "تحصيل تكامل بين جهازين"
        )
        assertFalse(collection.replayed)
        assertEquals("PENDING", queueStatus(secondaryDb, collectionOperationId))
        assertEquals(0, scalar(secondaryDb, "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?", invoiceId))
        assertEquals(0, scalar(secondaryDb, "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?", collectionOperationId))
        val collectionFrame = secondaryRepository.encryptedAuthenticatedOutbox(collectionOperationId, primaryId)

        var collectionAck: String? = null
        var collectionError: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryCollectionProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) { collectionError = message }
            },
            sendFrame = { id, recipient, frame ->
                assertEquals(collectionOperationId, id); assertEquals(secondaryId, recipient); collectionAck = frame; true
            }
        )
        primaryCollectionProcessor.onEncryptedFrame(secondaryId, collectionOperationId, collectionFrame)
        assertEquals("رفض الأساسي تحصيل الدين الصحيح: $collectionError", null, collectionError)
        assertEquals("APPLIED", inboxStatus(primaryDb, collectionOperationId))
        assertEquals(secondaryAdminId, primaryDb.readableDatabase.rawQuery(
            "SELECT actor_user_id FROM sync_inbox WHERE operation_id=?",
            arrayOf(collectionOperationId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals("PARTIALLY_PAID", primaryDb.readableDatabase.rawQuery(
            "SELECT status FROM sales_invoices WHERE id=?", arrayOf(invoiceId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals(1, scalar(primaryDb, "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?", invoiceId))
        assertEquals(1, scalar(primaryDb, "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?", collectionOperationId))
        assertEquals(10_000L, primaryDb.readableDatabase.rawQuery(
            "SELECT amount_minor FROM cash_transactions WHERE operation_id=?", arrayOf(collectionOperationId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) })
        assertEquals(primaryWallet.id, primaryDb.readableDatabase.rawQuery(
            "SELECT account_id FROM cash_transactions WHERE operation_id=?", arrayOf(collectionOperationId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        assertEquals(1, scalar(primaryDb,
            "SELECT COUNT(*) FROM operation_registry WHERE operation_id=? AND status='COMPLETED'", collectionOperationId))
        val signedCollectionAck = collectionAck ?: throw AssertionError("لم يصدر الأساسي إقرار تطبيق التحصيل")
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        val secondaryAckProcessor = SyncRelayProcessor(
            secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true }
        )
        secondaryAckProcessor.onEncryptedFrame(primaryId, collectionOperationId, signedCollectionAck)
        assertEquals("SYNCED", queueStatus(secondaryDb, collectionOperationId))

        collectionAck = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        primaryCollectionProcessor.onEncryptedFrame(secondaryId, collectionOperationId, collectionFrame)
        val repeatedCollectionAck = collectionAck ?: throw AssertionError("لم يُعد الأساسي إرسال ACK للتحصيل المكرر")
        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        secondaryAckProcessor.onEncryptedFrame(primaryId, collectionOperationId, repeatedCollectionAck)
        assertEquals("SYNCED", queueStatus(secondaryDb, collectionOperationId))
        assertEquals(1, scalar(primaryDb, "SELECT COUNT(*) FROM invoice_payments WHERE invoice_id=?", invoiceId))
        assertEquals(1, scalar(primaryDb, "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?", collectionOperationId))
        assertEquals(1, scalar(primaryDb, "SELECT COUNT(*) FROM operation_registry WHERE operation_id=?", collectionOperationId))

        var excessiveRejected = false
        try {
            secondaryPayments.addPayment(
                secondaryAdminId, invoiceId, UUID.randomUUID().toString(), primaryWallet.id, 30_001L, "اختبار مبلغ زائد"
            )
        } catch (_: IllegalArgumentException) { excessiveRejected = true }
        assertTrue("يجب رفض تحصيل يتجاوز أصل الدين", excessiveRejected)
        assertEquals(1, scalar(primaryDb,
            "SELECT COUNT(*) FROM cash_transactions WHERE source_id=? AND transaction_type='CUSTOMER_CREDIT_COLLECTION'",
            invoiceId))
    }

    @Test fun secondaryMayQueueCustomerCollectionButNotAdministrativeCommands() {
        val beforeOutbox = scalar(secondaryDb,
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE origin_device_id=?", secondaryId)
        val operationId = UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("business_operation", "COLLECT_CUSTOMER_PAYMENT")
            .put("invoice_id", "credit-invoice-test")
            .put("payment_id", "payment-$operationId")
            .put("cash_transaction_id", "cash-$operationId")
            .put("cash_account_id", "secondary-wallet")
            .put("cash_account_type", "WALLET")
            .put("cash_account_currency_code", "SYP")
            .put("amount_minor", 15_000L)
            .put("currency_code", "SYP")
            .put("collection_rate_milli", 10_000_000L)
            .put("effective_rate_milli", 10_000_000L)
            .put("usd_reference_reduction_cents", 150L)
            .put("occurred_at", "2026-11-04T12:00:00Z")
            .put("operating_date", "2026-11-04")
        val secondarySql = secondaryDb.writableDatabase
        secondarySql.beginTransaction()
        try {
            secondaryRepository.enqueueSecondaryCommand(
                secondarySql, secondaryAdminId, operationId,
                "COLLECT_CUSTOMER_PAYMENT", "CUSTOMER_PAYMENT",
                "payment-$operationId", payload.toString()
            )
            secondarySql.setTransactionSuccessful()
        } finally { secondarySql.endTransaction() }
        assertEquals(beforeOutbox + 1, scalar(secondaryDb,
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE origin_device_id=?", secondaryId))
        assertEquals("PENDING", queueStatus(secondaryDb, operationId))
        assertEquals(1, scalar(secondaryDb,
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE operation_id=? AND entity_type='CUSTOMER_PAYMENT'",
            operationId))

        var directCommandRejected = false
        val expenseId = UUID.randomUUID().toString()
        secondarySql.beginTransaction()
        try {
            try {
                secondaryRepository.enqueueSecondaryCommand(
                    secondarySql, secondaryAdminId, expenseId,
                    "POST_OPERATING_EXPENSE", "OPERATING_EXPENSE", "forged-expense",
                    JSONObject().put("business_operation", "POST_OPERATING_EXPENSE").toString()
                )
            } catch (_: IllegalArgumentException) { directCommandRejected = true }
            assertTrue("Secondary must still reject administrative expense commands", directCommandRejected)
            secondarySql.setTransactionSuccessful()
        } finally { secondarySql.endTransaction() }
        assertEquals(beforeOutbox + 1, scalar(secondaryDb,
            "SELECT COUNT(*) FROM sync_operation_envelopes WHERE origin_device_id=?", secondaryId))
        assertEquals(0, scalar(secondaryDb,
            "SELECT COUNT(*) FROM cash_transactions WHERE operation_id=?", operationId))
    }

    @Test fun secondaryOutboxWaitsDuringNetworkOutageAndCompletesAfterReconnect() {
        // Simulate a network outage by withholding delivery to the primary.
        // The durable command must remain pending and be deliverable after reconnect.
        assertEquals("PENDING", queueStatus(secondaryDb, operationId))
        assertTrue(secondaryRepository.pendingOutboxForDevice(primaryId)
            .any { it.operationId == operationId })

        var ack: String? = null
        var protocolError: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) {
                    protocolError = message
                }
            },
            sendFrame = { id, recipient, frame ->
                assertEquals(operationId, id)
                assertEquals(secondaryId, recipient)
                ack = frame
                true
            }
        )
        primaryProcessor.onEncryptedFrame(secondaryId, operationId, outgoingFrame)
        assertEquals("لم يستعد الأمر بعد عودة الاتصال: $protocolError", null, protocolError)
        assertEquals("APPLIED", inboxStatus(primaryDb, operationId))

        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        SyncRelayProcessor(secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true })
            .onEncryptedFrame(primaryId, operationId, ack
                ?: throw AssertionError("لم يصل ACK بعد استعادة الاتصال"))
        assertEquals("SYNCED", queueStatus(secondaryDb, operationId))
        assertEquals(1, scalar(primaryDb, "SELECT COUNT(*) FROM sync_inbox WHERE operation_id=?", operationId))
    }

    @Test fun pendingSecondaryOutboxSurvivesDatabaseReopenAndProcessRecreation() {
        // Reopen the named SQLite database and recreate the repository, as after
        // the app process is killed and Android starts it again.
        secondaryDb.close()
        secondaryDb = AppDatabase(context, SECONDARY_DATABASE)
        AppDatabase.installForTests(secondaryDb)
        setLocalDeviceId(secondaryId)
        secondaryRepository = SyncProtocolRepository(
            context, SyncCredentialVault(context) { vaultKey }
        )

        assertEquals("PENDING", queueStatus(secondaryDb, operationId))
        val persisted = secondaryRepository.pendingOutboxForDevice(primaryId)
            .single { it.operationId == operationId }
        assertEquals(operationId, persisted.operationId)
        val restoredFrame = secondaryRepository.encryptedAuthenticatedOutbox(operationId, primaryId)

        var ack: String? = null
        var protocolError: String? = null
        setLocalDeviceId(primaryId)
        AppDatabase.installForTests(primaryDb)
        val primaryProcessor = SyncRelayProcessor(
            primaryRepository, SyncDomainApplier(context),
            listener = object : SyncRelayProcessor.Listener {
                override fun onProtocolError(operationId: String?, message: String) {
                    protocolError = message
                }
            },
            sendFrame = { id, recipient, frame ->
                assertEquals(operationId, id)
                assertEquals(secondaryId, recipient)
                ack = frame
                true
            }
        )
        primaryProcessor.onEncryptedFrame(secondaryId, operationId, restoredFrame)
        assertEquals("رفض الأساسي الأمر المستعاد: $protocolError", null, protocolError)
        assertEquals("APPLIED", inboxStatus(primaryDb, operationId))

        setLocalDeviceId(secondaryId)
        AppDatabase.installForTests(secondaryDb)
        SyncRelayProcessor(secondaryRepository, SyncDomainApplier(context), sendFrame = { _, _, _ -> true })
            .onEncryptedFrame(primaryId, operationId, ack
                ?: throw AssertionError("لم يصدر ACK للأمر المستعاد"))
        assertEquals("SYNCED", queueStatus(secondaryDb, operationId))
        assertEquals(1, scalar(primaryDb, "SELECT COUNT(*) FROM sync_inbox WHERE operation_id=?", operationId))
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

    private fun scalar(db: AppDatabase, sql: String, first: String, second: String): Int =
        db.readableDatabase.rawQuery(sql, arrayOf(first, second)).use { c -> assertTrue(c.moveToFirst()); c.getInt(0) }

    private fun scalarLong(db: AppDatabase, sql: String, first: String, second: String): Long =
        db.readableDatabase.rawQuery(sql, arrayOf(first, second)).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }

    companion object {
        private const val PRIMARY_DATABASE = "sync-integration-primary.db"
        private const val SECONDARY_DATABASE = "sync-integration-secondary.db"
    }
}
