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
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

@RunWith(RobolectricTestRunner::class)
class SyncProtocolRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: SyncProtocolRepository
    private lateinit var vault: SyncCredentialVault
    private lateinit var adminId: String

    @Before fun setUp() {
        SyncDeviceTestKeyProvider.install()
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

    private fun enrollRemote(
        invitation: SyncPairingInvitation, deviceId: String, displayName: String
    ): SyncEnrollment {
        val remote = SyncDeviceAuthenticator.getOrCreate(
            RuntimeEnvironment.getApplication(), deviceId
        )
        val publicKey = android.util.Base64.encodeToString(remote.public.encoded, android.util.Base64.NO_WRAP)
        return repository.enrollSecondaryDevice(adminId, invitation.token, deviceId, displayName, publicKey)
    }

    private fun configureSecondaryForTest(
        storeId: String, primaryId: String, credential: String, displayName: String,
        zoneId: String = StoreTime.DEFAULT_ZONE_ID
    ) {
        val context = RuntimeEnvironment.getApplication()
        val localId = SyncDeviceIdentity.getOrCreate(context)
        val local = SyncDeviceAuthenticator.getOrCreate(context, localId)
        val primary = SyncDeviceAuthenticator.getOrCreate(context, primaryId)
        val localPublic = android.util.Base64.encodeToString(local.public.encoded, android.util.Base64.NO_WRAP)
        val primaryPublic = android.util.Base64.encodeToString(primary.public.encoded, android.util.Base64.NO_WRAP)
        val certificate = SyncDeviceCertificate.issue(storeId, primaryId, localId, localPublic, primary.private)
        repository.configureAsSecondary(
            adminId, storeId, primaryId, credential, displayName, zoneId,
            primaryPublicKeyBase64 = primaryPublic,
            deviceCertificateJson = certificate
        )
    }


    @Test fun primaryIssuesRootSignedDeviceCertificateAndRevocationStatement() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val remoteId = UUID.randomUUID().toString()
        val invitation = repository.createPairingInvitation(adminId)
        val enrollment = enrollRemote(invitation, remoteId, "الثانوي")
        val certificate = enrollment.deviceCertificateJson!!
        val trust = org.json.JSONObject(repository.primaryRelayTrustRootJson())
        val storeId = enrollment.storeId
        val root = trust.getJSONObject(storeId)
        assertEquals(enrollment.primaryDeviceId, root.getString("device_id"))
        assertTrue(SyncDeviceCertificate.verify(
            certificate, storeId, remoteId, enrollment.primaryDeviceId, root.getString("public_key")
        ))
        val revocation = repository.revokeDevice(adminId, remoteId)
        val revocationJson = org.json.JSONObject(revocation)
        val canonical = org.json.JSONObject()
            .put("version", revocationJson.getInt("version"))
            .put("store_id", revocationJson.getString("store_id"))
            .put("device_id", revocationJson.getString("device_id"))
            .put("serial", revocationJson.getString("serial"))
            .put("revoked_at", revocationJson.getLong("revoked_at"))
            .toString()
        val rootPublic = android.util.Base64.decode(root.getString("public_key"), android.util.Base64.DEFAULT)
        assertTrue(SyncDeviceAuthenticator.verifyBytes(
            rootPublic, canonical.toByteArray(Charsets.UTF_8),
            android.util.Base64.decode(revocationJson.getString("signature"), android.util.Base64.DEFAULT)
        ))
        assertNull(repository.trustedPeerPublicKey(remoteId))
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_device_revocations WHERE device_id=? AND certificate_serial=?",
            arrayOf(remoteId, revocationJson.getString("serial"))
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals("PENDING", c.getString(0)) }
    }

    @Test fun revokedSecondaryCanBeRepairedWithNewCertificateWithoutClearingOldRevocation() {
        repository.configureAsPrimary(adminId, "الجهاز الأساسي")
        val remoteId = UUID.randomUUID().toString()
        val first = enrollRemote(repository.createPairingInvitation(adminId), remoteId, "الهاتف الثانوي")
        val oldSerial = SyncDeviceCertificate.serial(first.deviceCertificateJson!!)
        val oldPublicKey = repository.trustedPeerPublicKey(remoteId)!!
        repository.revokeDevice(adminId, remoteId)
        assertNull(repository.trustedPeerPublicKey(remoteId))

        val replacement = enrollRemote(repository.createPairingInvitation(adminId), remoteId, "الهاتف الثانوي بعد إعادة الاقتران")
        val newSerial = SyncDeviceCertificate.serial(replacement.deviceCertificateJson!!)
        assertTrue(oldSerial != newSerial)
        assertTrue(oldPublicKey.contentEquals(repository.trustedPeerPublicKey(remoteId)!!))
        assertTrue(repository.activeSecondaryDeviceIds().contains(remoteId))
        assertTrue(SyncDeviceCertificate.verify(
            replacement.deviceCertificateJson!!,
            replacement.storeId,
            remoteId,
            replacement.primaryDeviceId,
            android.util.Base64.encodeToString(
                SyncDeviceAuthenticator.getOrCreate(
                    RuntimeEnvironment.getApplication(), replacement.primaryDeviceId
                ).public.encoded,
                android.util.Base64.NO_WRAP
            )
        ))
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_device_revocations WHERE device_id=? AND certificate_serial=?",
            arrayOf(remoteId, oldSerial)
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("PENDING", c.getString(0))
        }
        database.readableDatabase.rawQuery(
            "SELECT status,certificate_json FROM sync_device_public_keys WHERE device_id=?",
            arrayOf(remoteId)
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("ACTIVE", c.getString(0))
            assertEquals(newSerial, SyncDeviceCertificate.serial(c.getString(1)))
        }
    }

    @Test fun primaryRegistrySyncPinsRootAndRetriesPendingRevocationBeforeRegisteringActivePeers() {
        repository.configureAsPrimary(adminId, "الجهاز الأساسي")
        val revokedId = UUID.randomUUID().toString()
        val activeId = UUID.randomUUID().toString()
        val revokedEnrollment = enrollRemote(repository.createPairingInvitation(adminId), revokedId, "ثانوي ملغى")
        val activeEnrollment = enrollRemote(repository.createPairingInvitation(adminId), activeId, "ثانوي نشط")
        repository.revokeDevice(adminId, revokedId)

        val trustJson = JSONObject(repository.primaryRelayTrustRootJson())
        val storeId = revokedEnrollment.storeId
        val root = trustJson.getJSONObject(storeId)
        val tlsCertificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(tlsCertificate).build()
        val clientTls = HandshakeCertificates.Builder()
            .addTrustedCertificate(tlsCertificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.enqueue(MockResponse().setBody(JSONObject()
            .put("store_id", storeId)
            .put("device_id", root.getString("device_id"))
            .put("public_key", root.getString("public_key"))
            .put("algorithm", "EC_P256_SHA256").toString()))
        server.enqueue(MockResponse().setBody("""{"revoked":true}"""))
        server.enqueue(MockResponse().setBody("""{"registered":true}"""))
        server.start()
        // This test exercises HTTPS routing and registry behavior, not public-CA validation.
        // Production continues to use OkHttp's normal platform trust manager.
        val testTrustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        val testSslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(testTrustManager), SecureRandom())
        }
        val httpClient = OkHttpClient.Builder()
            .sslSocketFactory(testSslContext.socketFactory, testTrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        try {
            val result = repository.syncDeviceRegistry(
                server.url("/").toString(),
                SyncRelayRegistryClient(server.url("/").toString(), httpClient)
            )
            assertEquals(1, result.revocationsSent)
            assertEquals(1, result.certificatesRegistered)
            val trustRequest = server.takeRequest()
            assertEquals("GET", trustRequest.method)
            assertEquals("/v1/trust/$storeId", trustRequest.path)
            val revokeRequest = server.takeRequest()
            assertEquals("POST", revokeRequest.method)
            assertEquals("/v1/devices/$storeId/$revokedId/revoke", revokeRequest.path)
            val registerRequest = server.takeRequest()
            assertEquals("POST", registerRequest.method)
            assertEquals("/v1/devices/$storeId/$activeId/certificate", registerRequest.path)
            database.readableDatabase.rawQuery(
                "SELECT status FROM sync_device_revocations WHERE device_id=?",
                arrayOf(revokedId)
            ).use { c -> assertTrue(c.moveToFirst()); assertEquals("SYNCED", c.getString(0)) }
            assertNotNull(activeEnrollment.deviceCertificateJson)
        } finally { server.shutdown() }
    }

    @Test fun primaryCreatesEncryptedSignedApplicationAckOnlyAfterDurableApply() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val context = RuntimeEnvironment.getApplication()
        val remoteId = UUID.randomUUID().toString()
        val remoteIdentity = SyncDeviceAuthenticator.getOrCreate(context, remoteId)
        val remotePublicKey = android.util.Base64.encodeToString(
            remoteIdentity.public.encoded, android.util.Base64.NO_WRAP
        )
        val invitation = repository.createPairingInvitation(adminId)
        val enrolled = repository.enrollSecondaryDevice(
            adminId, invitation.token, remoteId, "الثانوي", remotePublicKey
        )
        val operationId = UUID.randomUUID().toString()
        val payloadHash = "b".repeat(64)
        val now = "2026-11-04T12:00:00.000Z"
        database.writableDatabase.insertOrThrow("sync_inbox", null, ContentValues().apply {
            put("operation_id", operationId)
            put("origin_device_id", remoteId)
            put("sequence_number", 1L)
            put("operation_type", "CREATE")
            put("entity_type", "SALES_INVOICE")
            put("entity_id", "sale-$operationId")
            put("payload_json", """{"business_operation":"POST_SALE_INVOICE"}""")
            put("payload_sha256", payloadHash)
            put("status", "APPLIED")
            put("received_at", now)
            put("applied_at", now)
            put("origin_created_at", now)
        })
        val frameJson = repository.createEncryptedApplicationAck(remoteId, operationId)
        val frame = org.json.JSONObject(frameJson)
        assertEquals(operationId, frame.getString("operation_id"))
        assertEquals("APPLICATION_ACK", frame.getString("frame_type"))
        assertEquals(remoteId, frame.getString("recipient_device_id"))
        assertEquals("ECDSA_P256_SHA256", frame.getString("signature_algorithm"))
        val signed = org.json.JSONObject(frame.toString()).apply {
            remove("signature")
            remove("signature_algorithm")
            remove("sender_key_fingerprint")
        }
        val primaryId = repository.hasConfiguredPrimary().let {
            database.readableDatabase.rawQuery(
                "SELECT state_value FROM sync_protocol_state WHERE state_key='authority_device_id'", null
            ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        }
        val signingContext = SyncDeviceAuthenticator.SigningContext(
            enrolled.storeId, primaryId, remoteId, operationId
        )
        val signature = android.util.Base64.decode(frame.getString("signature"), android.util.Base64.DEFAULT)
        assertTrue(SyncDeviceAuthenticator.verify(
            repository.trustedPeerPublicKey(primaryId)!!, signingContext,
            signed.toString().toByteArray(Charsets.UTF_8), signature
        ))
        val secret = vault.load(remoteId)!!
        val clear = SyncEnvelopeCipher.decrypt(
            android.util.Base64.decode(
                frame.getString("ciphertext"),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            ),
            SecretKeySpec(secret, "AES"),
            SyncEnvelopeCipher.PeerContext(enrolled.storeId, primaryId, remoteId, operationId)
        )!!
        secret.fill(0)
        val ack = org.json.JSONObject(clear.toString(Charsets.UTF_8))
        assertEquals("APPLIED", ack.getString("ack_status"))
        assertEquals(payloadHash, ack.getString("payload_sha256"))
        assertEquals(1L, ack.getLong("sequence_number"))
    }

    @Test fun primaryCannotCreateApplicationAckForReceivedOrOtherDeviceOperation() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val remoteId = UUID.randomUUID().toString()
        val invitation = repository.createPairingInvitation(adminId)
        enrollRemote(invitation, remoteId, "الثانوي")
        val operationId = UUID.randomUUID().toString()
        val now = "2026-11-04T12:00:00.000Z"
        database.writableDatabase.insertOrThrow("sync_inbox", null, ContentValues().apply {
            put("operation_id", operationId); put("origin_device_id", remoteId)
            put("sequence_number", 1L); put("operation_type", "CREATE")
            put("entity_type", "OPERATING_EXPENSE"); put("entity_id", "expense-$operationId")
            put("payload_json", """{"business_operation":"POST_OPERATING_EXPENSE"}""")
            put("payload_sha256", "c".repeat(64)); put("status", "RECEIVED")
            put("received_at", now); put("origin_created_at", now)
        })
        var rejected = false
        try { repository.createEncryptedApplicationAck(remoteId, operationId) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
    }

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
        val enrolled = enrollRemote(invitation, remoteId, "الهاتف الثاني")
        assertEquals(remoteId, enrolled.deviceId)
        assertEquals(StoreTime.DEFAULT_ZONE_ID, enrolled.storeTimeZoneId)
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
        try { enrollRemote(invitation, UUID.randomUUID().toString(), "جهاز آخر") }
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
        configureSecondaryForTest("store-remote-01", primaryId, credential, "الهاتف الثاني", "America/Los_Angeles")
        assertTrue(repository.isConfiguredSecondary())
        assertEquals("America/Los_Angeles", StoreTime.zoneId(database.readableDatabase))
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
            db, adminId, operationId, "UPSERT_PRODUCT", "PRODUCT", "product-1",
            "CREATE", """{"product_id":"product-1","name":"Test"}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()
        assertEquals(1L, first)
        assertEquals(1, repository.pendingOutbox().size)

        db.beginTransaction()
        val replay = repository.enqueueLocalOperation(
            db, adminId, operationId, "UPSERT_PRODUCT", "PRODUCT", "product-1",
            "CREATE", """{"product_id":"product-1","name":"Test"}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()
        assertEquals(first, replay)
        assertEquals(1, repository.pendingOutbox().size)

        var mismatchRejected = false
        db.beginTransaction()
        try {
            repository.enqueueLocalOperation(
                db, adminId, operationId, "UPSERT_PRODUCT", "PRODUCT", "product-1",
                "CREATE", """{"product_id":"product-1","name":"Tampered"}"""
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
        enrollRemote(invitation, remoteId, "الثانوي")
        val operationId = UUID.randomUUID().toString()
        val db = database.writableDatabase
        db.beginTransaction()
        repository.enqueueLocalOperation(
            db, adminId, operationId, "UPSERT_PRODUCT", "PRODUCT", "product-verified",
            "CREATE", """{"product_id":"product-verified","name":"Test"}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()

        val envelope = repository.authenticatedOutbox(operationId, remoteId)
        val recipientSecret = vault.load(remoteId)!!
        assertTrue(SyncEnvelopeSecurity.verify(envelope, recipientSecret))
        assertEquals("CREATE", envelope.operationType)
        assertEquals("UPSERT_PRODUCT", org.json.JSONObject(envelope.payloadJson).getString("business_operation"))
        recipientSecret.fill(0)
    }

    @Test fun storeTimeZoneChangeIsQueuedForEachEnrolledSecondaryAndSigned() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val invitation = repository.createPairingInvitation(adminId)
        val secondaryId = UUID.randomUUID().toString()
        enrollRemote(invitation, secondaryId, "الثانوي")

        assertEquals("America/New_York",
            StoreRepository(RuntimeEnvironment.getApplication())
                .saveStoreTimeZoneId("America/New_York", adminId))
        val pending = repository.pendingStoreSettingsForDevice(secondaryId)
        assertEquals(1, pending.size)
        assertEquals("STORE_SETTINGS", pending.single().entityType)
        assertEquals("UPDATE", pending.single().operationType)
        val envelope = repository.authenticatedOutbox(pending.single().operationId, secondaryId)
        val secret = vault.load(secondaryId)!!
        assertTrue(SyncEnvelopeSecurity.verify(envelope, secret))
        assertEquals("UPDATE_STORE_TIME_ZONE",
            org.json.JSONObject(envelope.payloadJson).getString("business_operation"))
        assertEquals("America/New_York",
            org.json.JSONObject(envelope.payloadJson).getString("time_zone"))
        secret.fill(0)
        repository.acknowledgeStoreSettingsApplied(secondaryId, pending.single().operationId)
        assertTrue(repository.pendingStoreSettingsForDevice(secondaryId).isEmpty())
    }

    @Test fun newlyEnrolledSecondaryReceivesCurrentStoreTimeZoneInBootstrapData() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        StoreRepository(RuntimeEnvironment.getApplication())
            .saveStoreTimeZoneId("Asia/Dubai", adminId)
        val invitation = repository.createPairingInvitation(adminId)
        val enrollment = enrollRemote(invitation, UUID.randomUUID().toString(), "جهاز جديد")
        assertEquals("Asia/Dubai", enrollment.storeTimeZoneId)
    }

    @Test fun secondaryCannotChangeAuthoritativeStoreTimeZoneLocally() {
        val primaryId = UUID.randomUUID().toString()
        val secret = ByteArray(32) { (it + 51).toByte() }
        val credential = android.util.Base64.encodeToString(
            secret,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        configureSecondaryForTest("store-time-zone-02", primaryId, credential, "الثانوي")
        var rejected = false
        try {
            StoreRepository(RuntimeEnvironment.getApplication())
                .saveStoreTimeZoneId("America/Los_Angeles", adminId)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(StoreTime.DEFAULT_ZONE_ID, StoreTime.zoneId(database.readableDatabase))
        secret.fill(0)
    }

    @Test fun secondaryReceivesAndAppliesPrimaryTimeZoneUpdateIdempotently() {
        val primaryId = UUID.randomUUID().toString()
        val secret = ByteArray(32) { (it + 41).toByte() }
        val credential = android.util.Base64.encodeToString(
            secret,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        configureSecondaryForTest("store-time-zone-01", primaryId, credential, "الثانوي", "Asia/Damascus")
        val envelope = SyncEnvelopeSecurity.create(
            "store-time-zone-01", UUID.randomUUID().toString(), 9L,
            primaryId, adminId, "UPDATE", "STORE_SETTINGS", "store_time_zone", 0L,
            """{"business_operation":"UPDATE_STORE_TIME_ZONE","time_zone":"America/New_York"}""",
            "2026-11-03T12:00:00Z", secret
        )
        val encoded = SyncEnvelopeSecurity.encode(envelope)
        assertEquals(SyncInboxDisposition.RECEIVED,
            repository.recordIncomingStoreSettingsFromPrimary(encoded))
        assertEquals(SyncInboxDisposition.DUPLICATE,
            repository.recordIncomingStoreSettingsFromPrimary(encoded))

        val applier = SyncDomainApplier(RuntimeEnvironment.getApplication())
        assertTrue(applier.applyReceivedStoreTimeZone(envelope.operationId).applied)
        assertEquals("America/New_York", StoreTime.zoneId(database.readableDatabase))
        assertTrue(applier.applyReceivedStoreTimeZone(envelope.operationId).applied)
        assertEquals("APPLIED", database.readableDatabase.rawQuery(
            "SELECT status FROM sync_inbox WHERE operation_id=?",
            arrayOf(envelope.operationId)
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) })
        secret.fill(0)
    }

    @Test fun secondaryCommandIsQueuedAndSignedWithItsOwnCredentialWithoutPostingLocally() {
        val primaryId = UUID.randomUUID().toString()
        val secretBytes = ByteArray(32) { (it + 31).toByte() }
        val credential = android.util.Base64.encodeToString(
            secretBytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        configureSecondaryForTest("store-command-01", primaryId, credential, "الثانوي")
        val operationId = UUID.randomUUID().toString()
        val db = database.writableDatabase
        db.beginTransaction()
        val sequence = repository.enqueueSecondaryCommand(
            db, adminId, operationId, "POST_SALE_INVOICE", "SALES_INVOICE", "sale-command-1",
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
        assertEquals("POST_SALE_INVOICE", org.json.JSONObject(envelope.payloadJson).getString("business_operation"))
        secretBytes.fill(0)
    }

    @Test fun primaryDurablyReceivesSecondaryCommandAndHandlesDuplicateAndHashConflict() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val invitation = repository.createPairingInvitation(adminId)
        val secondaryId = UUID.randomUUID().toString()
        enrollRemote(invitation, secondaryId, "الثانوي")
        repository.mapSecondaryActor(adminId, secondaryId, "remote-admin", adminId)
        val secret = vault.load(secondaryId)!!
        val operationId = UUID.randomUUID().toString()
        val envelope = SyncEnvelopeSecurity.create(
            database.readableDatabase.rawQuery(
                "SELECT state_value FROM sync_protocol_state WHERE state_key='store_id'", null
            ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) },
            operationId, 1L, secondaryId, "remote-admin", "CREATE", "SALES_INVOICE",
            "sale-incoming-1", 0L, """{"business_operation":"POST_SALE_INVOICE","invoice_id":"sale-incoming-1","total_usd_cents":2500}""",
            "2026-11-03T12:00:00Z", secret
        )
        val encoded = SyncEnvelopeSecurity.encode(envelope)
        assertEquals(SyncInboxDisposition.RECEIVED, repository.recordIncomingFromSecondary(encoded))
        assertEquals(SyncInboxDisposition.DUPLICATE, repository.recordIncomingFromSecondary(encoded))
        val changed = SyncEnvelopeSecurity.create(
            envelope.storeId, operationId, 1L, secondaryId, "remote-admin", "CREATE", "SALES_INVOICE",
            "sale-incoming-1", 0L, """{"business_operation":"POST_SALE_INVOICE","invoice_id":"sale-incoming-1","total_usd_cents":9900}""",
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

    @Test fun operationIdReplayMustMatchOriginSequenceAndEnvelopeMetadata() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val firstId = UUID.randomUUID().toString()
        val firstInvitation = repository.createPairingInvitation(adminId)
        enrollRemote(firstInvitation, firstId, "الثانوي الأول")
        repository.mapSecondaryActor(adminId, firstId, "remote-admin-1", adminId)
        val firstSecret = vault.load(firstId)!!
        val storeId = repository.storeId()
        val operationId = UUID.randomUUID().toString()
        val payload = """{"business_operation":"POST_SALE_INVOICE","invoice_id":"shared-id","total_usd_cents":2500}"""
        val createdAt = "2026-11-04T12:00:00Z"
        val first = SyncEnvelopeSecurity.create(
            storeId, operationId, 1L, firstId, "remote-admin-1", "CREATE",
            "SALES_INVOICE", "shared-id", 0L, payload, createdAt, firstSecret
        )
        val encodedFirst = SyncEnvelopeSecurity.encode(first)
        assertEquals(SyncInboxDisposition.RECEIVED, repository.recordIncomingFromSecondary(encodedFirst))

        // A lost ACK must still be recoverable for the exact accepted operation,
        // even if the manager later disables that actor mapping.
        database.writableDatabase.update(
            "sync_actor_mappings", ContentValues().apply { put("is_active", 0) },
            "device_id=?", arrayOf(firstId)
        )
        assertEquals(SyncInboxDisposition.DUPLICATE, repository.recordIncomingFromSecondary(encodedFirst))

        val secondId = UUID.randomUUID().toString()
        val secondInvitation = repository.createPairingInvitation(adminId)
        enrollRemote(secondInvitation, secondId, "الثانوي الثاني")
        repository.mapSecondaryActor(adminId, secondId, "remote-admin-2", adminId)
        val secondSecret = vault.load(secondId)!!
        val crossDeviceReuse = SyncEnvelopeSecurity.create(
            storeId, operationId, 1L, secondId, "remote-admin-2", "CREATE",
            "SALES_INVOICE", "shared-id", 0L, payload, createdAt, secondSecret
        )
        assertEquals(
            SyncInboxDisposition.CONFLICT,
            repository.recordIncomingFromSecondary(SyncEnvelopeSecurity.encode(crossDeviceReuse))
        )

        val changedSequence = SyncEnvelopeSecurity.create(
            storeId, operationId, 2L, firstId, "remote-admin-1", "CREATE",
            "SALES_INVOICE", "shared-id", 0L, payload, createdAt, firstSecret
        )
        assertEquals(
            SyncInboxDisposition.CONFLICT,
            repository.recordIncomingFromSecondary(SyncEnvelopeSecurity.encode(changedSequence))
        )

        database.readableDatabase.rawQuery(
            "SELECT last_sequence FROM sync_cursors WHERE device_id=?", arrayOf(firstId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0)) }
        database.readableDatabase.rawQuery(
            "SELECT last_sequence FROM sync_cursors WHERE device_id=?", arrayOf(secondId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(0L, c.getLong(0)) }
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_inbox WHERE operation_id=?", arrayOf(operationId)
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        assertEquals(2, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_conflicts WHERE operation_id=?", arrayOf(operationId)
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        firstSecret.fill(0)
        secondSecret.fill(0)
    }

    @Test fun secondaryRejectsPrimaryOperationIdReuseWithChangedSequence() {
        val primaryId = UUID.randomUUID().toString()
        val secret = ByteArray(32) { (it + 51).toByte() }
        val credential = android.util.Base64.encodeToString(
            secret,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        configureSecondaryForTest("store-primary-replay-01", primaryId, credential, "الثانوي")
        val operationId = UUID.randomUUID().toString()
        val payload = """{"business_operation":"SET_EXCHANGE_RATE","id":"rate-replay","currency_code":"USD","rate_syp_per_usd_milli":15000000,"effective_at":"2026-11-04T12:00:00Z","created_at":"2026-11-04T12:00:00Z"}"""
        val first = SyncEnvelopeSecurity.create(
            "store-primary-replay-01", operationId, 1L, primaryId, adminId,
            "CREATE", "EXCHANGE_RATE", "rate-replay", 0L, payload,
            "2026-11-04T12:00:00Z", secret
        )
        assertEquals(
            SyncInboxDisposition.RECEIVED,
            repository.recordIncomingFromPrimary(SyncEnvelopeSecurity.encode(first))
        )
        assertEquals(
            SyncInboxDisposition.DUPLICATE,
            repository.recordIncomingFromPrimary(SyncEnvelopeSecurity.encode(first))
        )
        val changedSequence = SyncEnvelopeSecurity.create(
            "store-primary-replay-01", operationId, 2L, primaryId, adminId,
            "CREATE", "EXCHANGE_RATE", "rate-replay", 0L, payload,
            "2026-11-04T12:00:00Z", secret
        )
        assertEquals(
            SyncInboxDisposition.CONFLICT,
            repository.recordIncomingFromPrimary(SyncEnvelopeSecurity.encode(changedSequence))
        )
        database.readableDatabase.rawQuery(
            "SELECT last_sequence FROM sync_cursors WHERE device_id=?", arrayOf(primaryId)
        ).use { c -> assertTrue(c.moveToFirst()); assertEquals(1L, c.getLong(0)) }
        assertEquals(1, database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_conflicts WHERE operation_id=?", arrayOf(operationId)
        ).use { c -> c.moveToFirst(); c.getInt(0) })
        secret.fill(0)
    }

    @Test fun primaryRejectsSequenceGapWithoutAdvancingDurableCursor() {
        repository.configureAsPrimary(adminId, "الرئيسي")
        val invitation = repository.createPairingInvitation(adminId)
        val secondaryId = UUID.randomUUID().toString()
        enrollRemote(invitation, secondaryId, "الثانوي")
        repository.mapSecondaryActor(adminId, secondaryId, "remote-admin", adminId)
        val secret = vault.load(secondaryId)!!
        val storeId = database.readableDatabase.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='store_id'", null
        ).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
        val gap = SyncEnvelopeSecurity.create(
            storeId, UUID.randomUUID().toString(), 2L, secondaryId, "remote-admin", "CREATE",
            "SALES_INVOICE", "sale-gap", 0L, """{"business_operation":"POST_SALE_INVOICE","invoice_id":"sale-gap"}""",
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
            db, adminId, UUID.randomUUID().toString(), "UPSERT_PRODUCT", "PRODUCT", "product-1",
            "CREATE", """{"product_id":"product-1"}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()
        assertNull(result)
        assertTrue(repository.pendingOutbox().isEmpty())
    }

    private data class AckAcceptanceScenario(
        val secondaryDeviceId: String,
        val operationId: String,
        val frameJson: String
    )

    private fun prepareAckAcceptanceScenario(): AckAcceptanceScenario {
        val context = RuntimeEnvironment.getApplication()
        repository.configureAsPrimary(adminId, "الجهاز الأساسي")
        val primaryId = SyncDeviceIdentity.getOrCreate(context)
        val secondaryId = UUID.randomUUID().toString()
        val secondaryIdentity = SyncDeviceAuthenticator.getOrCreate(context, secondaryId)
        val secondaryPublicKey = android.util.Base64.encodeToString(
            secondaryIdentity.public.encoded, android.util.Base64.NO_WRAP
        )
        val invitation = repository.createPairingInvitation(adminId)
        repository.enrollSecondaryDevice(
            adminId, invitation.token, secondaryId, "الجهاز الثانوي", secondaryPublicKey
        )

        // Simulate the separately installed secondary identity against an isolated
        // logical device in this repository test database.
        context.getSharedPreferences("the_daily_shop_sync_identity", android.content.Context.MODE_PRIVATE)
            .edit().putString("device_id", secondaryId).commit()
        setProtocolStateForTest("local_device_role", "SECONDARY")
        setProtocolStateForTest("authority_device_id", primaryId)

        val operationId = UUID.randomUUID().toString()
        val db = database.writableDatabase
        // This test simulates two devices in one SQLite file. Production devices
        // have independent databases, while this test schema has one global sequence index.
        val nextSecondarySequence = db.rawQuery(
            "SELECT COALESCE(MAX(sequence_number),0)+1 FROM sync_operation_envelopes", null
        ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }
        setProtocolStateForTest("secondary_next_sequence", nextSecondarySequence.toString())
        db.beginTransaction()
        val sequence = repository.enqueueSecondaryCommand(
            db, adminId, operationId, "POST_SALE_INVOICE",
            "SALES_INVOICE", "sale-$operationId",
            """{"invoice_id":"sale-$operationId","total_usd_cents":1250}"""
        )
        db.setTransactionSuccessful()
        db.endTransaction()
        val payloadHash = db.rawQuery(
            "SELECT payload_sha256 FROM sync_operation_envelopes WHERE operation_id=?",
            arrayOf(operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }

        context.getSharedPreferences("the_daily_shop_sync_identity", android.content.Context.MODE_PRIVATE)
            .edit().putString("device_id", primaryId).commit()
        setProtocolStateForTest("local_device_role", "PRIMARY")
        setProtocolStateForTest("authority_device_id", primaryId)
        val storeId = db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='store_id'", null
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }
        val now = "2026-11-04T12:00:00.000Z"
        db.insertOrThrow("sync_inbox", null, ContentValues().apply {
            put("operation_id", operationId)
            put("origin_device_id", secondaryId)
            put("sequence_number", sequence)
            put("operation_type", "CREATE")
            put("entity_type", "OPERATING_EXPENSE")
            put("entity_id", "expense-$operationId")
            put("payload_json", """{"business_operation":"POST_OPERATING_EXPENSE"}""")
            put("payload_sha256", payloadHash)
            put("status", "APPLIED")
            put("received_at", now)
            put("applied_at", now)
            put("origin_created_at", now)
        })
        val ackFrame = repository.createEncryptedApplicationAck(secondaryId, operationId)
        context.getSharedPreferences("the_daily_shop_sync_identity", android.content.Context.MODE_PRIVATE)
            .edit().putString("device_id", secondaryId).commit()
        setProtocolStateForTest("local_device_role", "SECONDARY")
        setProtocolStateForTest("authority_device_id", primaryId)
        assertEquals(storeId, org.json.JSONObject(ackFrame).getString("store_id"))
        return AckAcceptanceScenario(secondaryId, operationId, ackFrame)
    }

    private fun setProtocolStateForTest(key: String, value: String) {
        val changed = database.writableDatabase.update(
            "sync_protocol_state", ContentValues().apply { put("state_value", value) },
            "state_key=?", arrayOf(key)
        )
        assertEquals(1, changed)
    }

    @Test fun secondaryAcceptsValidPrimaryApplicationAckAndMarksQueueSyncedAtomically() {
        val scenario = prepareAckAcceptanceScenario()
        assertEquals(SyncApplicationAckDisposition.APPLIED,
            repository.acceptEncryptedApplicationAck(scenario.frameJson))
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_queue WHERE operation_id=?", arrayOf(scenario.operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("SYNCED", cursor.getString(0)) }
        database.readableDatabase.rawQuery(
            "SELECT status,sequence_number FROM sync_acknowledgements WHERE operation_id=?",
            arrayOf(scenario.operationId)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("APPLIED", cursor.getString(0))
            assertEquals(1L, cursor.getLong(1))
        }
    }

    @Test fun repeatedPrimaryApplicationAckIsIdempotentAndDoesNotDuplicateReceipt() {
        val scenario = prepareAckAcceptanceScenario()
        assertEquals(SyncApplicationAckDisposition.APPLIED,
            repository.acceptEncryptedApplicationAck(scenario.frameJson))
        assertEquals(SyncApplicationAckDisposition.DUPLICATE,
            repository.acceptEncryptedApplicationAck(scenario.frameJson))
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_acknowledgements WHERE operation_id=?",
            arrayOf(scenario.operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0)) }
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_queue WHERE operation_id=?", arrayOf(scenario.operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("SYNCED", cursor.getString(0)) }
    }

    @Test fun forgedOrTamperedApplicationAckIsRejectedAndOutboxRemainsPending() {
        val scenario = prepareAckAcceptanceScenario()
        val forged = org.json.JSONObject(scenario.frameJson)
            .put("signature", android.util.Base64.encodeToString(
                ByteArray(72) { 0x41 }, android.util.Base64.NO_WRAP
            )).toString()
        var rejected = false
        try { repository.acceptEncryptedApplicationAck(forged) }
        catch (_: Exception) { rejected = true }
        assertTrue("يجب رفض الإقرار ذي التوقيع المزور", rejected)
        val tamperedCiphertext = org.json.JSONObject(scenario.frameJson)
            .put("ciphertext", "AAAA" + org.json.JSONObject(scenario.frameJson).getString("ciphertext"))
            .toString()
        rejected = false
        try { repository.acceptEncryptedApplicationAck(tamperedCiphertext) }
        catch (_: Exception) { rejected = true }
        assertTrue("يجب رفض العبث بالمحتوى المشفر", rejected)
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_queue WHERE operation_id=?", arrayOf(scenario.operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("PENDING", cursor.getString(0)) }
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM sync_acknowledgements WHERE operation_id=?",
            arrayOf(scenario.operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) }
    }


    @Test fun relayProcessorFlushesDurableSecondaryOutboxAndForwardNoticeDoesNotCompleteIt() {
        val scenario = prepareAckAcceptanceScenario()
        val context = RuntimeEnvironment.getApplication()
        val sent = mutableListOf<Triple<String, String, String>>()
        val processor = SyncRelayProcessor(
            repository, SyncDomainApplier(context),
            sendFrame = { operationId, recipientId, frame ->
                sent.add(Triple(operationId, recipientId, frame))
                true
            }
        )
        processor.onRelayReady()
        assertEquals(1, sent.size)
        assertEquals(scenario.operationId, sent.single().first)
        assertEquals(repository.authorityDeviceId(), sent.single().second)
        val wire = SyncRelayFrameCodec.encodeRelayRequest(
            repository.storeId(), scenario.secondaryDeviceId, sent.single().second,
            scenario.operationId, sent.single().third
        )
        assertEquals(scenario.operationId, org.json.JSONObject(wire).getString("frame_id"))
        processor.onRelayDelivery(scenario.operationId, forwarded = true, retryable = false)
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_queue WHERE operation_id=?", arrayOf(scenario.operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("PENDING", cursor.getString(0)) }
    }

    @Test fun relayProcessorRoutesInboundApplicationAckToDurableAcceptance() {
        val scenario = prepareAckAcceptanceScenario()
        val context = RuntimeEnvironment.getApplication()
        val processor = SyncRelayProcessor(
            repository, SyncDomainApplier(context),
            sendFrame = { _, _, _ -> true }
        )
        processor.onEncryptedFrame(
            repository.authorityDeviceId()!!, scenario.operationId, scenario.frameJson
        )
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_queue WHERE operation_id=?", arrayOf(scenario.operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("SYNCED", cursor.getString(0)) }
    }

    @Test fun primaryTracksApplicationAckPerSecondaryBeforeCompletingSharedOutbox() {
        val context = RuntimeEnvironment.getApplication()
        repository.configureAsPrimary(adminId, "الجهاز الأساسي")
        val primaryId = SyncDeviceIdentity.getOrCreate(context)
        val secondaryIds = (1..2).map { index ->
            val id = UUID.randomUUID().toString()
            val key = SyncDeviceAuthenticator.getOrCreate(context, id)
            val publicKey = android.util.Base64.encodeToString(key.public.encoded, android.util.Base64.NO_WRAP)
            val invitation = repository.createPairingInvitation(adminId)
            repository.enrollSecondaryDevice(adminId, invitation.token, id, "ثانوي $index", publicKey)
            id
        }
        val operationId = UUID.randomUUID().toString()
        val db = database.writableDatabase
        db.beginTransaction()
        val sequence = repository.enqueueLocalOperation(
            db, adminId, operationId, "UPSERT_PRODUCT", "PRODUCT",
            "product-$operationId", "CREATE", """{"product_id":"product-$operationId"}"""
        )!!
        db.setTransactionSuccessful()
        db.endTransaction()
        val payloadHash = db.rawQuery(
            "SELECT payload_sha256 FROM sync_operation_envelopes WHERE operation_id=?",
            arrayOf(operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); cursor.getString(0) }

        fun makeSecondaryAck(secondaryId: String): String {
            context.getSharedPreferences(
                "the_daily_shop_sync_identity", android.content.Context.MODE_PRIVATE
            ).edit().putString("device_id", secondaryId).commit()
            setProtocolStateForTest("local_device_role", "SECONDARY")
            setProtocolStateForTest("authority_device_id", primaryId)
            db.delete("sync_inbox", "operation_id=?", arrayOf(operationId))
            val now = "2026-11-04T12:00:00.000Z"
            db.insertOrThrow("sync_inbox", null, ContentValues().apply {
                put("operation_id", operationId); put("origin_device_id", primaryId)
                put("sequence_number", sequence); put("operation_type", "CREATE")
                put("entity_type", "PRODUCT"); put("entity_id", "product-$operationId")
                put("payload_json", """{"business_operation":"UPSERT_PRODUCT"}""")
                put("payload_sha256", payloadHash); put("status", "APPLIED")
                put("received_at", now); put("applied_at", now); put("origin_created_at", now)
            })
            return repository.createEncryptedApplicationAck(primaryId, operationId)
        }

        val firstAck = makeSecondaryAck(secondaryIds[0])
        context.getSharedPreferences(
            "the_daily_shop_sync_identity", android.content.Context.MODE_PRIVATE
        ).edit().putString("device_id", primaryId).commit()
        setProtocolStateForTest("local_device_role", "PRIMARY")
        setProtocolStateForTest("authority_device_id", primaryId)
        assertEquals(SyncApplicationAckDisposition.APPLIED,
            repository.acceptEncryptedApplicationAck(firstAck))
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_queue WHERE operation_id=?", arrayOf(operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("PENDING", cursor.getString(0)) }
        assertTrue(repository.pendingOutboxForDevice(secondaryIds[0]).isEmpty())
        assertEquals(operationId, repository.pendingOutboxForDevice(secondaryIds[1]).single().operationId)

        val secondAck = makeSecondaryAck(secondaryIds[1])
        context.getSharedPreferences(
            "the_daily_shop_sync_identity", android.content.Context.MODE_PRIVATE
        ).edit().putString("device_id", primaryId).commit()
        setProtocolStateForTest("local_device_role", "PRIMARY")
        setProtocolStateForTest("authority_device_id", primaryId)
        assertEquals(SyncApplicationAckDisposition.APPLIED,
            repository.acceptEncryptedApplicationAck(secondAck))
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_queue WHERE operation_id=?", arrayOf(operationId)
        ).use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("SYNCED", cursor.getString(0)) }
        assertTrue(repository.pendingOutboxForDevice(secondaryIds[0]).isEmpty())
        assertTrue(repository.pendingOutboxForDevice(secondaryIds[1]).isEmpty())

        // A newly enrolled device must receive the primary's already-acknowledged
        // history; SYNCED is per the currently enrolled peer set, not a global
        // reason to omit an operation from a later peer's bootstrap stream.
        val lateDeviceId = UUID.randomUUID().toString()
        val lateIdentity = SyncDeviceAuthenticator.getOrCreate(context, lateDeviceId)
        val latePublicKey = android.util.Base64.encodeToString(
            lateIdentity.public.encoded, android.util.Base64.NO_WRAP
        )
        val lateInvitation = repository.createPairingInvitation(adminId)
        repository.enrollSecondaryDevice(
            adminId, lateInvitation.token, lateDeviceId, "ثانوي جديد", latePublicKey
        )
        assertEquals(operationId, repository.pendingOutboxForDevice(lateDeviceId).single().operationId)
    }

    @Test fun forwardedApplicationAcksAdvanceInBoundedBatchesAndRetryAfterCooldown() {
        repository.configureAsPrimary(adminId, "الجهاز الأساسي")
        val secondaryId = UUID.randomUUID().toString()
        val invitation = repository.createPairingInvitation(adminId)
        enrollRemote(invitation, secondaryId, "الجهاز الثانوي")

        val db = database.writableDatabase
        val now = "2026-11-05T12:00:00.000Z"
        val operationIds = (1..120).map { UUID.randomUUID().toString() }
        db.beginTransaction()
        try {
            operationIds.forEachIndexed { index, operationId ->
                db.insertOrThrow("sync_inbox", null, ContentValues().apply {
                    put("operation_id", operationId)
                    put("origin_device_id", secondaryId)
                    put("sequence_number", index + 1L)
                    put("operation_type", "CREATE")
                    put("entity_type", "SYNC_TRANSPORT_PROBE")
                    put("entity_id", operationId)
                    put("payload_json", JSONObject().put("business_operation", "SYNC_TRANSPORT_PROBE").toString())
                    put("payload_sha256", "%064x".format(index + 1))
                    put("status", "APPLIED")
                    put("received_at", now)
                    put("applied_at", now)
                    put("origin_created_at", now)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val first = repository.appliedOperationsAwaitingAckForDevice(secondaryId, limit = 50)
        assertEquals(50, first.size)
        first.forEach { assertTrue(repository.markApplicationAckForwarded(it)) }

        val second = repository.appliedOperationsAwaitingAckForDevice(secondaryId, limit = 50)
        assertEquals(50, second.size)
        assertTrue(first.toSet().intersect(second.toSet()).isEmpty())
        second.forEach { assertTrue(repository.markApplicationAckForwarded(it)) }

        val third = repository.appliedOperationsAwaitingAckForDevice(secondaryId, limit = 50)
        assertEquals(20, third.size)
        assertTrue((first + second).toSet().intersect(third.toSet()).isEmpty())
        third.forEach { assertTrue(repository.markApplicationAckForwarded(it)) }

        assertTrue(repository.appliedOperationsAwaitingAckForDevice(secondaryId, limit = 50).isEmpty())
        val retry = repository.appliedOperationsAwaitingAckForDevice(
            secondaryId, limit = 50, retryAfterMillis = 0L
        )
        assertEquals(50, retry.size)
        assertTrue(retry.all { it in first })
        assertEquals(120, db.rawQuery(
            "SELECT COUNT(*) FROM sync_inbox WHERE status='APPLIED' AND origin_device_id=?",
            arrayOf(secondaryId)
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) })
    }

}
