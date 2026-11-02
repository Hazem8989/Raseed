package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * Protocol simulation with two physically separate SQLite files.
 *
 * This is deliberately a test harness, not production transport or a claim that
 * the current repositories are already wired for secondary-authoritative posting.
 * The secondary writes only a pending command; the primary alone mutates ledger
 * and stock. Inbox, effect, cursor and acknowledgement commit atomically.
 */
@RunWith(RobolectricTestRunner::class)
class TwoPeerSyncSimulationTest {
    private lateinit var context: Context
    private lateinit var primary: PeerDb
    private lateinit var secondary: PeerDb
    private val storeId = "simulation-store"
    private val primaryId = "primary-device"
    private val secondaryId = "secondary-device"
    private val actorId = "owner-user"
    private val secret = ByteArray(32) { (it + 3).toByte() }

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(PRIMARY_DB)
        context.deleteDatabase(SECONDARY_DB)
        primary = PeerDb(context, PRIMARY_DB)
        secondary = PeerDb(context, SECONDARY_DB)
        primary.initialize(storeId, primaryId, secondaryId, secondaryId, secret, initialStock = 5)
        secondary.initialize(storeId, secondaryId, primaryId, secondaryId, secret, initialStock = 0)
    }

    @After fun tearDown() {
        primary.close()
        secondary.close()
        context.deleteDatabase(PRIMARY_DB)
        context.deleteDatabase(SECONDARY_DB)
    }

    @Test fun secondaryQueuesPendingCommandWithoutChangingAuthoritativeLedgerOrStock() {
        val envelope = secondary.queueSale("op-pending", 1L, 3, 1200L)
        assertEquals("PENDING_PRIMARY", secondary.outboxStatus(envelope.operationId))
        assertEquals(0, secondary.effectCount())
        assertEquals(0, secondary.stock())
        assertEquals(0, primary.effectCount())
        assertEquals(5, primary.stock())
    }

    @Test fun primaryAppliesOnceAndDuplicateDeliveryReturnsOriginalAcknowledgement() {
        val envelope = secondary.queueSale("op-once", 1L, 2, 900L)
        val first = primary.receive(envelope, secret)
        assertEquals("APPLIED", first.status)
        assertEquals(1, primary.effectCount())
        assertEquals(3, primary.stock())

        val duplicate = primary.receive(envelope, secret)
        assertEquals("DUPLICATE", duplicate.status)
        assertEquals(first.primarySequence, duplicate.primarySequence)
        assertEquals(1, primary.effectCount())
        assertEquals(3, primary.stock())

        secondary.acceptAcknowledgement(duplicate)
        assertEquals("ACKNOWLEDGED", secondary.outboxStatus(envelope.operationId))
    }

    @Test fun operationIdReuseWithDifferentPayloadHashIsRejected() {
        val original = secondary.queueSale("op-reused", 1L, 1, 500L)
        assertEquals("APPLIED", primary.receive(original, secret).status)
        val tamperedButValidlySigned = SyncEnvelopeSecurity.create(
            storeId, original.operationId, original.sequenceNumber, secondaryId, actorId,
            "CREATE", "SALES_INVOICE", "sale-op-reused", 0L,
            """{"quantity":1,"total_cents":9999}""",
            "2026-11-03T12:00:00Z", secret
        )
        assertEquals("OPERATION_ID_HASH_CONFLICT", primary.receive(tamperedButValidlySigned, secret).status)
        assertEquals(1, primary.effectCount())
        assertEquals(4, primary.stock())
    }

    @Test fun sequenceGapIsHeldAndCanBeRetriedAfterMissingPredecessor() {
        val first = secondary.queueSale("op-seq-1", 1L, 1, 100L)
        val second = secondary.queueSale("op-seq-2", 2L, 1, 200L)
        assertEquals("SEQUENCE_GAP", primary.receive(second, secret).status)
        assertEquals(0, primary.effectCount())

        assertEquals("APPLIED", primary.receive(first, secret).status)
        assertEquals("APPLIED", primary.receive(second, secret).status)
        assertEquals(2, primary.effectCount())
        assertEquals(3, primary.stock())
    }

    @Test fun inboxLedgerCursorAndAckSurvivePrimaryProcessRestart() {
        val envelope = secondary.queueSale("op-restart", 1L, 2, 700L)
        assertEquals("APPLIED", primary.receive(envelope, secret).status)
        primary.close()
        primary = PeerDb(context, PRIMARY_DB)

        val replayAfterRestart = primary.receive(envelope, secret)
        assertEquals("DUPLICATE", replayAfterRestart.status)
        assertEquals(1, primary.effectCount())
        assertEquals(3, primary.stock())
        assertEquals(1, primary.ackCount())
    }

    @Test fun invalidCredentialWrongStoreAndRevokedPeerCannotPost() {
        val envelope = secondary.queueSale("op-auth", 1L, 1, 300L)
        assertEquals("AUTHENTICATION_FAILED", primary.receive(envelope, ByteArray(32) { 99 }).status)
        val foreignStoreEnvelope = SyncEnvelopeSecurity.create(
            "other-store", envelope.operationId, envelope.sequenceNumber, secondaryId, actorId,
            "CREATE", "SALES_INVOICE", "sale-foreign-store", 0L,
            """{"quantity":1,"total_cents":300}""",
            "2026-11-03T12:00:00Z", secret
        )
        assertEquals("WRONG_STORE", primary.receive(foreignStoreEnvelope, secret).status)
        primary.revoke(secondaryId)
        assertEquals("DEVICE_REVOKED", primary.receive(envelope, secret).status)
        assertEquals(0, primary.effectCount())
        assertEquals(5, primary.stock())
    }

    @Test fun competingOfflineSalesCannotOversellAuthoritativeStock() {
        val saleA = secondary.queueSale("op-stock-a", 1L, 4, 400L)
        // A second independent client queue is represented by a separately signed
        // command from the enrolled peer at the next sequence.
        val saleB = secondary.queueSale("op-stock-b", 2L, 4, 400L)
        assertEquals("APPLIED", primary.receive(saleA, secret).status)
        assertEquals("INSUFFICIENT_STOCK", primary.receive(saleB, secret).status)
        assertEquals(1, primary.effectCount())
        assertEquals(1, primary.stock())
    }

    private fun PeerDb.queueSale(id: String, sequence: Long, quantity: Int, totalCents: Long): AuthenticatedSyncEnvelope {
        val payload = """{"quantity":$quantity,"total_cents":$totalCents}"""
        val envelope = SyncEnvelopeSecurity.create(
            storeId, id, sequence, secondaryId, actorId, "CREATE",
            "SALES_INVOICE", "sale-$id", 0L, payload,
            "2026-11-03T12:00:00Z", secret
        )
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertOrThrow("client_commands", null, ContentValues().apply {
                put("operation_id", id); put("payload_hash", envelope.payloadSha256)
                put("payload_json", envelope.payloadJson); put("status", "PENDING_PRIMARY")
            })
            writableDatabase.insertOrThrow("outbox", null, ContentValues().apply {
                put("operation_id", id); put("sequence_no", sequence)
                put("payload_hash", envelope.payloadSha256); put("status", "PENDING")
            })
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return envelope
    }

    private data class Ack(val operationId: String, val status: String, val primarySequence: Long)

    private class PeerDb(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE peer_meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            db.execSQL("CREATE TABLE enrolled_devices(device_id TEXT PRIMARY KEY, secret BLOB NOT NULL, active INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE client_commands(operation_id TEXT PRIMARY KEY, payload_hash TEXT NOT NULL, payload_json TEXT NOT NULL, status TEXT NOT NULL)")
            db.execSQL("CREATE TABLE outbox(operation_id TEXT PRIMARY KEY, sequence_no INTEGER NOT NULL UNIQUE, payload_hash TEXT NOT NULL, status TEXT NOT NULL)")
            db.execSQL("CREATE TABLE inbox(operation_id TEXT PRIMARY KEY, payload_hash TEXT NOT NULL, sequence_no INTEGER NOT NULL, status TEXT NOT NULL, primary_sequence INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE device_cursors(device_id TEXT PRIMARY KEY, last_sequence INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE ledger(operation_id TEXT PRIMARY KEY, amount_cents INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE stock(singleton INTEGER PRIMARY KEY CHECK(singleton=1), quantity INTEGER NOT NULL CHECK(quantity>=0))")
            db.execSQL("CREATE TABLE acknowledgements(operation_id TEXT PRIMARY KEY, status TEXT NOT NULL, primary_sequence INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

        fun initialize(store: String, localId: String, peerId: String, secondaryDeviceId: String, credential: ByteArray, initialStock: Int) {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.insertOrThrow("peer_meta", null, ContentValues().apply { put("k", "store_id"); put("v", store) })
                db.insertOrThrow("peer_meta", null, ContentValues().apply { put("k", "local_device_id"); put("v", localId) })
                db.insertOrThrow("peer_meta", null, ContentValues().apply { put("k", "primary_device_id"); put("v", if (localId == primaryId) localId else peerId) })
                db.insertOrThrow("device_cursors", null, ContentValues().apply { put("device_id", secondaryDeviceId); put("last_sequence", 0L) })
                db.insertOrThrow("enrolled_devices", null, ContentValues().apply {
                    put("device_id", peerId); put("secret", credential); put("active", 1)
                })
                db.insertOrThrow("stock", null, ContentValues().apply { put("singleton", 1); put("quantity", initialStock) })
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }

        fun receive(envelope: AuthenticatedSyncEnvelope, credential: ByteArray): Ack {
            if (!SyncEnvelopeSecurity.verify(envelope, credential)) return Ack(envelope.operationId, "AUTHENTICATION_FAILED", 0)
            val db = writableDatabase
            db.beginTransaction()
            try {
                val localStore = scalar(db, "SELECT v FROM peer_meta WHERE k='store_id'")
                if (envelope.storeId != localStore) return Ack(envelope.operationId, "WRONG_STORE", 0)
                val enrolled = db.rawQuery(
                    "SELECT secret,active FROM enrolled_devices WHERE device_id=?",
                    arrayOf(envelope.originDeviceId)
                ).use { c ->
                    if (!c.moveToFirst()) null else Pair(c.getBlob(0), c.getInt(1) == 1)
                } ?: return Ack(envelope.operationId, "UNKNOWN_DEVICE", 0)
                if (!enrolled.second) return Ack(envelope.operationId, "DEVICE_REVOKED", 0)
                if (!SyncEnvelopeSecurity.verify(envelope, enrolled.first)) return Ack(envelope.operationId, "AUTHENTICATION_FAILED", 0)

                val prior = db.rawQuery(
                    "SELECT payload_hash,status,primary_sequence FROM inbox WHERE operation_id=?",
                    arrayOf(envelope.operationId)
                ).use { c -> if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getLong(2)) else null }
                if (prior != null) {
                    if (prior.first != envelope.payloadSha256) return Ack(envelope.operationId, "OPERATION_ID_HASH_CONFLICT", prior.third)
                    db.setTransactionSuccessful()
                    return Ack(envelope.operationId, "DUPLICATE", prior.third)
                }

                val last = db.rawQuery(
                    "SELECT last_sequence FROM device_cursors WHERE device_id=?",
                    arrayOf(envelope.originDeviceId)
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
                if (envelope.sequenceNumber <= last) return Ack(envelope.operationId, "SEQUENCE_REPLAY", 0)
                if (envelope.sequenceNumber != last + 1L) return Ack(envelope.operationId, "SEQUENCE_GAP", 0)

                val payload = org.json.JSONObject(envelope.payloadJson)
                val quantity = payload.getInt("quantity")
                val amount = payload.getLong("total_cents")
                require(quantity > 0 && amount >= 0)
                val available = scalar(db, "SELECT quantity FROM stock WHERE singleton=1").toInt()
                val outcome = if (quantity > available) "INSUFFICIENT_STOCK" else "APPLIED"
                val primarySequence = scalar(db, "SELECT COALESCE(MAX(primary_sequence),0)+1 FROM acknowledgements").toLong()

                db.insertOrThrow("inbox", null, ContentValues().apply {
                    put("operation_id", envelope.operationId); put("payload_hash", envelope.payloadSha256)
                    put("sequence_no", envelope.sequenceNumber); put("status", outcome)
                    put("primary_sequence", primarySequence)
                })
                if (outcome == "APPLIED") {
                    db.insertOrThrow("ledger", null, ContentValues().apply {
                        put("operation_id", envelope.operationId); put("amount_cents", amount)
                    })
                    db.update("stock", ContentValues().apply { put("quantity", available - quantity) }, "singleton=1", null)
                }
                db.update("device_cursors", ContentValues().apply { put("last_sequence", envelope.sequenceNumber) },
                    "device_id=?", arrayOf(envelope.originDeviceId))
                db.insertOrThrow("acknowledgements", null, ContentValues().apply {
                    put("operation_id", envelope.operationId); put("status", outcome)
                    put("primary_sequence", primarySequence)
                })
                db.setTransactionSuccessful()
                return Ack(envelope.operationId, outcome, primarySequence)
            } finally { db.endTransaction() }
        }

        fun acceptAcknowledgement(ack: Ack) {
            writableDatabase.update("outbox", ContentValues().apply { put("status", "ACKNOWLEDGED") },
                "operation_id=?", arrayOf(ack.operationId))
            writableDatabase.update("client_commands", ContentValues().apply { put("status", ack.status) },
                "operation_id=?", arrayOf(ack.operationId))
        }

        fun revoke(deviceId: String) {
            writableDatabase.update("enrolled_devices", ContentValues().apply { put("active", 0) },
                "device_id=?", arrayOf(deviceId))
        }

        fun outboxStatus(id: String): String? =
            writableDatabase.rawQuery("SELECT status FROM outbox WHERE operation_id=?", arrayOf(id))
                .use { if (it.moveToFirst()) it.getString(0) else null }

        fun effectCount(): Int = scalar(writableDatabase, "SELECT COUNT(*) FROM ledger").toInt()
        fun stock(): Int = scalar(writableDatabase, "SELECT quantity FROM stock WHERE singleton=1").toInt()
        fun ackCount(): Int = scalar(writableDatabase, "SELECT COUNT(*) FROM acknowledgements").toInt()

        private fun scalar(db: SQLiteDatabase, sql: String): String =
            db.rawQuery(sql, null).use { c -> check(c.moveToFirst()); c.getString(0) }
    }

    companion object {
        private const val PRIMARY_DB = "sync-sim-primary.db"
        private const val SECONDARY_DB = "sync-sim-secondary.db"
    }
}
