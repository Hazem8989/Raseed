package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import android.util.Base64

data class SyncPairingInvitation(val invitationId: String, val token: String, val expiresAt: String)
data class SyncEnrollment(val deviceId: String, val credential: String, val storeId: String, val primaryDeviceId: String, val storeTimeZoneId: String, val primaryPublicKeyBase64: String? = null, val deviceCertificateJson: String? = null)
data class SyncRelayRegistrySyncResult(val certificatesRegistered: Int, val revocationsSent: Int)
data class SyncOutboxItem(
    val operationId: String, val sequence: Long, val operationType: String,
    val entityType: String, val entityId: String, val payloadJson: String,
    val payloadSha256: String, val createdAt: String, val retryCount: Int
)
enum class SyncInboxDisposition { RECEIVED, DUPLICATE, CONFLICT }
enum class SyncApplicationAckDisposition { APPLIED, DUPLICATE }

/**
 * Durable local-first synchronization protocol primitives.
 *
 * This class intentionally does not open sockets, declare data synchronized, or
 * apply remote financial operations. Transport authentication/encryption and
 * domain-specific replay must be completed before any network listener is enabled.
 */
class SyncProtocolRepository(private val context: Context, private val credentialVault: SyncCredentialVault = SyncCredentialVault(context)) {
    private val database = AppDatabase.getInstance(context)
    private val random = SecureRandom()

    fun configureAsPrimary(actorUserId: String, displayName: String) {
        val cleanName = displayName.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80) { "اسم الجهاز مطلوب وبحد أقصى 80 حرفاً" }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            PermissionPolicy.require(db, actorUserId, PermissionPolicy.SYNC_MANAGE)
            val currentRole = state(db, "local_device_role")
            require(currentRole == null || currentRole == "PRIMARY") {
                "لا يمكن ترقية جهاز ثانوي إلى جهاز أساسي؛ يجب إعداد جهاز أساسي مستقل"
            }
            val deviceId = SyncDeviceIdentity.getOrCreate(context)
            val enrolledRole = db.rawQuery(
                "SELECT device_role FROM sync_devices WHERE device_id=?",
                arrayOf(deviceId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            require(enrolledRole == null || enrolledRole == "PRIMARY") {
                "سجل هذا الجهاز يثبت أنه ثانوي؛ لا يمكن ترقيته إلى أساسي"
            }
            val existingAuthority = state(db, "authority_device_id")
            require(existingAuthority == null || existingAuthority == deviceId) {
                "يوجد جهاز أساسي آخر؛ لا يمكن نقل سلطة الاعتماد تلقائياً"
            }
            val now = timestamp()
            val localIdentity = SyncDeviceAuthenticator.getOrCreate(context, deviceId)
            db.insertWithOnConflict("sync_devices", null, ContentValues().apply {
                put("device_id", deviceId)
                put("display_name", cleanName)
                put("device_role", "PRIMARY")
                put("status", "ACTIVE")
                put("enrolled_by", actorUserId)
                put("created_at", now)
                put("last_seen_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("sync_devices", ContentValues().apply {
                put("display_name", cleanName); put("last_seen_at", now)
                put("status", "ACTIVE"); putNull("revoked_at")
            }, "device_id=?", arrayOf(deviceId))
            storeDevicePublicKey(
                db, deviceId,
                Base64.encodeToString(localIdentity.public.encoded, Base64.NO_WRAP), now
            )
            putState(db, "authority_device_id", deviceId, now)
            putState(db, "local_device_id", deviceId, now)
            putState(db, "local_device_role", "PRIMARY", now)
            if (state(db, "store_id") == null) putState(db, "store_id", UUID.randomUUID().toString(), now)
            if (state(db, "next_sequence") == null) putState(db, "next_sequence", "1", now)
            audit(db, actorUserId, "SYNC_PRIMARY_CONFIGURED", deviceId,
                null, JSONObject().put("display_name", cleanName).toString(), now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun createPairingInvitation(actorUserId: String): SyncPairingInvitation {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            requirePrimaryManager(db, actorUserId)
            val token = randomToken()
            val now = timestamp()
            val expires = timestamp(System.currentTimeMillis() + INVITATION_TTL_MS)
            val id = UUID.randomUUID().toString()
            db.delete("sync_pairing_invitations", "expires_at<=? OR consumed_at IS NOT NULL", arrayOf(now))
            db.insertOrThrow("sync_pairing_invitations", null, ContentValues().apply {
                put("invitation_id", id)
                put("invitation_hash", sha256(token))
                put("created_by", actorUserId)
                put("expires_at", expires)
                put("created_at", now)
            })
            audit(db, actorUserId, "SYNC_PAIRING_INVITATION_CREATED", id, null,
                JSONObject().put("expires_at", expires).toString(), now)
            db.setTransactionSuccessful()
            return SyncPairingInvitation(id, token, expires)
        } finally { db.endTransaction() }
    }

    /** Called by the primary after the secondary public key has been received through the one-time pairing flow. */
    fun enrollSecondaryDevice(
        actorUserId: String, invitationToken: String, remoteDeviceId: String, displayName: String,
        remotePublicKeyBase64: String
    ): SyncEnrollment {
        val cleanName = displayName.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80) { "اسم الجهاز غير صالح" }
        require(runCatching { UUID.fromString(remoteDeviceId) }.isSuccess) { "معرّف الجهاز غير صالح" }
        require(remoteDeviceId != SyncDeviceIdentity.getOrCreate(context)) { "لا يمكن تسجيل الجهاز الأساسي كجهاز ثانوي" }
        require(invitationToken.length in 32..128) { "رمز الاقتران غير صالح" }
        require(remotePublicKeyBase64.isNotBlank()) { "يجب استلام المفتاح العام للجهاز قبل تسجيله" }
        // Validate the caller before seeding deterministic primary-owned account records.
        requirePrimaryManager(database.readableDatabase, actorUserId)
        CashAccountRepository(context).ensureSystemCashAccounts()
        val db = database.writableDatabase
        val secretBytes = ByteArray(32)
        var credentialSaved = false
        var flushCatalogAfterCommit = false
        db.beginTransaction()
        try {
            requirePrimaryManager(db, actorUserId)
            val now = timestamp()
            val invitation = db.rawQuery(
                """SELECT invitation_id FROM sync_pairing_invitations
                   WHERE invitation_hash=? AND consumed_at IS NULL AND expires_at>?""".trimIndent(),
                arrayOf(sha256(invitationToken), now)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
                ?: throw IllegalArgumentException("رمز الاقتران غير صالح أو منتهي الصلاحية أو مستخدم سابقاً")
            val credential = randomToken()
            val decodedCredential = Base64.decode(
                credential, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            require(decodedCredential.size == 32)
            decodedCredential.copyInto(secretBytes)
            val existing = db.rawQuery(
                "SELECT status FROM sync_devices WHERE device_id=?", arrayOf(remoteDeviceId)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            require(existing == null || existing == "REVOKED") { "هذا الجهاز مسجل بالفعل" }
            db.insertWithOnConflict("sync_devices", null, ContentValues().apply {
                put("device_id", remoteDeviceId)
                put("display_name", cleanName)
                put("device_role", "SECONDARY")
                put("credential_hash", sha256(credential))
                put("status", "ACTIVE")
                put("enrolled_by", actorUserId)
                put("created_at", now)
                put("last_seen_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("sync_devices", ContentValues().apply {
                put("display_name", cleanName); put("device_role", "SECONDARY")
                put("credential_hash", sha256(credential)); put("status", "ACTIVE")
                put("enrolled_by", actorUserId); put("last_seen_at", now); putNull("revoked_at")
            }, "device_id=?", arrayOf(remoteDeviceId))
            db.update("sync_pairing_invitations", ContentValues().apply { put("consumed_at", now) },
                "invitation_id=?", arrayOf(invitation))
            val remotePublicKeyBytes = Base64.decode(remotePublicKeyBase64, Base64.DEFAULT)
            require(remotePublicKeyBytes.size in 64..256) { "مفتاح الجهاز الثانوي غير صالح" }
            val canonicalRemotePublicKey = Base64.encodeToString(remotePublicKeyBytes, Base64.NO_WRAP)
            storeDevicePublicKey(db, remoteDeviceId, canonicalRemotePublicKey, now)
            val localIdentity = SyncDeviceAuthenticator.getOrCreate(context, SyncDeviceIdentity.getOrCreate(context))
            storeDevicePublicKey(
                db, SyncDeviceIdentity.getOrCreate(context),
                Base64.encodeToString(localIdentity.public.encoded, Base64.NO_WRAP), now
            )
            val storeId = state(db, "store_id") ?: error("معرّف المتجر غير مهيأ")
            val certificateJson = SyncDeviceCertificate.issue(
                storeId, SyncDeviceIdentity.getOrCreate(context), remoteDeviceId, canonicalRemotePublicKey, localIdentity.private
            )
            db.update("sync_device_public_keys", ContentValues().apply {
                put("certificate_json", certificateJson)
            }, "device_id=?", arrayOf(remoteDeviceId))
            db.insertWithOnConflict("sync_cursors", null, ContentValues().apply {
                put("device_id", remoteDeviceId); put("last_sequence", 0L); put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            // Bootstrap a newly paired terminal from the authoritative primary catalog.
            // Events are durable and use the same ordered per-device ACK protocol.
            enqueueCatalogSnapshot(db, actorUserId)
            credentialVault.save(remoteDeviceId, secretBytes)
            credentialSaved = true
            audit(db, actorUserId, "SYNC_DEVICE_ENROLLED", remoteDeviceId, null,
                JSONObject().put("display_name", cleanName).put("device_role", "SECONDARY").toString(), now)
            db.setTransactionSuccessful()
            flushCatalogAfterCommit = true
            return SyncEnrollment(
                remoteDeviceId, credential,
                state(db, "store_id") ?: error("معرّف المتجر غير مهيأ"),
                SyncDeviceIdentity.getOrCreate(context),
                StoreTime.zoneId(db),
                Base64.encodeToString(localIdentity.public.encoded, Base64.NO_WRAP),
                certificateJson
            )
        } catch (error: Exception) {
            if (credentialSaved) credentialVault.remove(remoteDeviceId)
            throw error
        } finally {
            db.endTransaction()
            if (flushCatalogAfterCommit) runCatching { SyncRelaySession.flushPending() }
            secretBytes.fill(0)
        }
    }

    /**
     * Called on the secondary only after a future TLS-pinned pairing handshake
     * has delivered the one-time credential and primary/store identities.
     */
    fun configureAsSecondary(
        actorUserId: String, storeId: String, primaryDeviceId: String,
        credential: String, displayName: String,
        storeTimeZoneId: String = StoreTime.DEFAULT_ZONE_ID,
        primaryPublicKeyBase64: String,
        deviceCertificateJson: String
    ) {
        require(storeId.isNotBlank() && displayName.trim().isNotEmpty() && displayName.length <= 80)
        require(StoreTime.isValidZoneId(storeTimeZoneId)) { "منطقة المتجر الزمنية غير صالحة" }
        require(runCatching { UUID.fromString(primaryDeviceId) }.isSuccess) { "معرّف الجهاز الأساسي غير صالح" }
        val secret = runCatching {
            Base64.decode(credential, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrElse { throw IllegalArgumentException("بيانات اعتماد الجهاز غير صالحة") }
        require(secret.size == 32) { "بيانات اعتماد الجهاز يجب أن تكون 256 بت" }
        val localDeviceId = SyncDeviceIdentity.getOrCreate(context)
        require(localDeviceId != primaryDeviceId) { "هذا الهاتف هو الجهاز الأساسي بالفعل" }
        val enrolledRole = database.readableDatabase.rawQuery(
            "SELECT device_role FROM sync_devices WHERE device_id=?",
            arrayOf(localDeviceId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        require(enrolledRole == null || enrolledRole == "SECONDARY") {
            "سجل هذا الجهاز يثبت أنه أساسي؛ لا يمكن تحويله إلى ثانوي تلقائياً"
        }
        val localIdentity = SyncDeviceAuthenticator.getOrCreate(context, localDeviceId)
        val localPublicKeyBase64 = Base64.encodeToString(localIdentity.public.encoded, Base64.NO_WRAP)
        require(SyncDeviceCertificate.verify(
            deviceCertificateJson, storeId, localDeviceId, primaryDeviceId,
            primaryPublicKeyBase64, localPublicKeyBase64
        )) { "شهادة الجهاز غير موقعة من جذر الثقة أو لا تخص هذا الجهاز" }

        val db = database.writableDatabase
        var credentialSaved = false
        db.beginTransaction()
        try {
            PermissionPolicy.require(db, actorUserId, PermissionPolicy.SYNC_MANAGE)
            val oldRole = state(db, "local_device_role")
            require(oldRole == null || oldRole == "SECONDARY") {
                "لا يمكن تحويل جهاز أساسي قائم إلى ثانوي تلقائياً"
            }
            val now = timestamp()
            db.insertWithOnConflict("sync_devices", null, ContentValues().apply {
                put("device_id", primaryDeviceId); put("display_name", "الجهاز الأساسي")
                put("device_role", "PRIMARY"); put("status", "ACTIVE")
                put("created_at", now); put("last_seen_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("sync_devices", ContentValues().apply {
                put("status", "ACTIVE"); putNull("revoked_at")
            }, "device_id=?", arrayOf(primaryDeviceId))
            db.insertWithOnConflict("sync_devices", null, ContentValues().apply {
                put("device_id", localDeviceId); put("display_name", displayName.trim())
                put("device_role", "SECONDARY"); put("status", "ACTIVE")
                put("enrolled_by", actorUserId); put("created_at", now); put("last_seen_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update("sync_devices", ContentValues().apply {
                put("display_name", displayName.trim()); put("status", "ACTIVE"); putNull("revoked_at")
            }, "device_id=?", arrayOf(localDeviceId))
            db.insertWithOnConflict("sync_cursors", null, ContentValues().apply {
                put("device_id", primaryDeviceId); put("last_sequence", 0L); put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            storeDevicePublicKey(db, primaryDeviceId, primaryPublicKeyBase64, now)
            storeDevicePublicKey(db, localDeviceId, localPublicKeyBase64, now)
            db.update("sync_device_public_keys", ContentValues().apply {
                put("certificate_json", deviceCertificateJson)
            }, "device_id=?", arrayOf(localDeviceId))
            putState(db, "authority_device_id", primaryDeviceId, now)
            putState(db, "local_device_id", localDeviceId, now)
            putState(db, "local_device_role", "SECONDARY", now)
            putState(db, "store_id", storeId, now)
            StoreTime.setZoneId(db, storeTimeZoneId, now)
            if (state(db, "secondary_next_sequence") == null) {
                putState(db, "secondary_next_sequence", "1", now)
            }
            credentialVault.save(localDeviceId, secret)
            credentialSaved = true
            audit(db, actorUserId, "SYNC_SECONDARY_CONFIGURED", localDeviceId, null,
                JSONObject().put("primary_device_id", primaryDeviceId)
                    .put("store_id", storeId).put("display_name", displayName.trim()).toString(), now)
            db.setTransactionSuccessful()
        } catch (error: Exception) {
            if (credentialSaved) credentialVault.remove(localDeviceId)
            throw error
        } finally {
            db.endTransaction()
            secret.fill(0)
        }
    }

    private fun storeDevicePublicKey(
        db: SQLiteDatabase, deviceId: String, encodedBase64: String, now: String
    ) {
        val encoded = runCatching {
            Base64.decode(encodedBase64, Base64.DEFAULT)
        }.getOrElse { throw IllegalArgumentException("المفتاح العام للجهاز غير صالح") }
        require(encoded.size in 64..256) { "حجم المفتاح العام غير صالح" }
        val publicKey = runCatching {
            java.security.KeyFactory.getInstance("EC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(encoded))
        }.getOrElse { throw IllegalArgumentException("صيغة المفتاح العام غير مدعومة") }
        require(publicKey.algorithm.equals("EC", ignoreCase = true)) {
            "خوارزمية هوية الجهاز غير مدعومة"
        }
        val fingerprint = SyncDeviceAuthenticator.fingerprint(encoded)
        val existing = db.rawQuery(
            "SELECT public_key_fingerprint FROM sync_device_public_keys WHERE device_id=?",
            arrayOf(deviceId)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        require(existing == null || existing == fingerprint) {
            "تغيير مفتاح جهاز مسجل يتطلب إلغاء الاقتران وإعادة تسجيله"
        }
        db.insertWithOnConflict("sync_device_public_keys", null, ContentValues().apply {
            put("device_id", deviceId)
            put("public_key_base64", Base64.encodeToString(encoded, Base64.NO_WRAP))
            put("public_key_fingerprint", fingerprint)
            put("algorithm", "EC_P256_SHA256")
            put("status", "ACTIVE")
            put("enrolled_at", now)
            putNull("revoked_at")
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Returns a pinned public key only for an active enrolled peer. */
    fun trustedPeerPublicKey(deviceId: String): ByteArray? {
        if (deviceId.isBlank()) return null
        val db = database.readableDatabase
        val activeDevice = db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND status='ACTIVE'",
            arrayOf(deviceId)
        ).use { it.moveToFirst() }
        if (!activeDevice) return null
        return db.rawQuery(
            "SELECT public_key_base64 FROM sync_device_public_keys WHERE device_id=? AND status='ACTIVE'",
            arrayOf(deviceId)
        ).use { c ->
            if (!c.moveToFirst()) null
            else runCatching { Base64.decode(c.getString(0), Base64.DEFAULT) }.getOrNull()
        }
    }

    fun secondaryCredential(): ByteArray? {
        if (state(database.readableDatabase, "local_device_role") != "SECONDARY") return null
        return credentialVault.load(SyncDeviceIdentity.getOrCreate(context))
    }

    /**
     * Resolve secondary status from both the mutable protocol flags and the durable
     * enrolled-device record. Losing local_device_role or authority_device_id must
     * never make an enrolled secondary look like a primary installation.
     */
    fun isConfiguredSecondary(): Boolean {
        val db = database.readableDatabase
        if (state(db, "local_device_role") == "SECONDARY") return true
        val localId = state(db, "local_device_id") ?: return false
        val enrolledRole = db.rawQuery(
            "SELECT device_role FROM sync_devices WHERE device_id=?",
            arrayOf(localId)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        return enrolledRole == "SECONDARY"
    }

    /** Public root configuration to provision in the relay Worker after primary setup. */
    fun primaryRelayTrustRootJson(): String {
        require(localDeviceRole() == "PRIMARY" && authorityDeviceId() == localDeviceId()) {
            "جذر الثقة متاح على الجهاز الأساسي فقط"
        }
        val storeId = state(database.readableDatabase, "store_id") ?: error("معرّف المتجر غير مهيأ")
        val pair = SyncDeviceAuthenticator.getOrCreate(context, localDeviceId())
        val root = JSONObject()
            .put("device_id", localDeviceId())
            .put("public_key", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
        return JSONObject().put(storeId, root).toString()
    }

    fun deviceCertificateForRelay(deviceId: String): String? {
        val db = database.readableDatabase
        return db.rawQuery(
            """SELECT k.certificate_json FROM sync_device_public_keys k
               JOIN sync_devices d ON d.device_id=k.device_id
               WHERE k.device_id=? AND k.status='ACTIVE' AND d.status='ACTIVE'""".trimIndent(),
            arrayOf(deviceId)
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }

    fun relayAuthentication(): SyncRelayDeviceAuthentication {
        val deviceId = localDeviceId()
        val role = localDeviceRole()
        val pair = SyncDeviceAuthenticator.getOrCreate(context, deviceId)
        val serial = if (role == "PRIMARY" && authorityDeviceId() == deviceId) {
            "ROOT"
        } else {
            require(role == "SECONDARY" && deviceId != authorityDeviceId()) {
                "دور الجهاز غير مهيأ للمزامنة"
            }
            val cert = deviceCertificateForRelay(deviceId)
                ?: throw IllegalStateException("شهادة الجهاز غير موجودة أو ملغاة")
            SyncDeviceCertificate.serial(cert)
        }
        return SyncRelayDeviceAuthentication(serial, pair.private)
    }

    /** Fetches the relay's pinned root, registers active peers and retries durable revocations. */
    fun syncDeviceRegistry(
        relayBaseUrl: String,
        registry: SyncRelayRegistryClient = SyncRelayRegistryClient(relayBaseUrl)
    ): SyncRelayRegistrySyncResult {
        val localId = localDeviceId()
        require(localDeviceRole() == "PRIMARY" && authorityDeviceId() == localId) {
            "إدارة سجل شهادات الوسيط متاحة على الجهاز الأساسي فقط"
        }
        val db = database.writableDatabase
        val storeId = state(db, "store_id") ?: error("معرّف المتجر غير مهيأ")
        val identity = SyncDeviceAuthenticator.getOrCreate(context, localId)
        val trust = registry.fetchTrustRoot(storeId)
        require(trust.deviceId == localId &&
            SyncDeviceAuthenticator.fingerprint(Base64.decode(trust.publicKeyBase64, Base64.DEFAULT)) ==
            SyncDeviceAuthenticator.fingerprint(identity.public.encoded)) {
            "جذر الثقة المثبت في الوسيط لا يطابق هوية الجهاز الأساسي؛ أوقفنا المزامنة الآمنة"
        }

        var revocationsSent = 0
        val pending = mutableListOf<Triple<String, String, String>>()
        db.rawQuery(
            """SELECT device_id,certificate_serial,revocation_json
               FROM sync_device_revocations WHERE status='PENDING'
               ORDER BY created_at""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) pending.add(Triple(c.getString(0), c.getString(1), c.getString(2)))
        }
        pending.forEach { (deviceId, serial, _) ->
            // Re-sign on each retry so an offline revocation does not expire before delivery.
            val statement = SyncDeviceCertificate.signedRevocation(storeId, deviceId, serial, identity.private)
            val delivered = registry.revokeCertificate(storeId, deviceId, statement)
            if (delivered) {
                db.beginTransaction()
                try {
                    db.update("sync_device_revocations", ContentValues().apply {
                        put("revocation_json", statement)
                        put("status", "SYNCED")
                        put("sent_at", timestamp())
                    }, "device_id=? AND certificate_serial=?", arrayOf(deviceId, serial))
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                revocationsSent++
            }
        }

        val activeCertificates = mutableListOf<Pair<String, String>>()
        db.rawQuery(
            """SELECT d.device_id,k.certificate_json
               FROM sync_devices d JOIN sync_device_public_keys k ON k.device_id=d.device_id
               WHERE d.device_role='SECONDARY' AND d.status='ACTIVE'
                 AND k.status='ACTIVE' AND k.certificate_json IS NOT NULL
               ORDER BY d.created_at""".trimIndent(), null
        ).use { c -> while (c.moveToNext()) activeCertificates.add(c.getString(0) to c.getString(1)) }
        var certificatesRegistered = 0
        activeCertificates.forEach { (deviceId, certificate) ->
            if (registry.registerCertificate(storeId, deviceId, certificate)) certificatesRegistered++
        }
        return SyncRelayRegistrySyncResult(certificatesRegistered, revocationsSent)
    }

    /** The secondary fetches its root from the HTTPS relay, never from an untrusted pairing payload. */
    fun configureAsSecondaryFromRelay(
        relayBaseUrl: String,
        actorUserId: String,
        storeId: String,
        primaryDeviceId: String,
        credential: String,
        displayName: String,
        deviceCertificateJson: String,
        storeTimeZoneId: String = StoreTime.DEFAULT_ZONE_ID
    ) {
        val trust = SyncRelayRegistryClient(relayBaseUrl).fetchTrustRoot(storeId)
        require(trust.deviceId == primaryDeviceId && trust.algorithm == "EC_P256_SHA256") {
            "هوية الجهاز الأساسي لا تطابق جذر الثقة المنشور لدى وسيط المزامنة"
        }
        configureAsSecondary(
            actorUserId, storeId, primaryDeviceId, credential, displayName,
            storeTimeZoneId, trust.publicKeyBase64, deviceCertificateJson
        )
    }

    /**
     * Signs an outbox item with the correct direction-specific credential:
     * a primary signs with the enrolled recipient's secret; a secondary signs
     * with its own local secret and may address only its configured primary.
     */
    fun authenticatedOutbox(operationId: String, recipientDeviceId: String): AuthenticatedSyncEnvelope {
        require(operationId.isNotBlank() && recipientDeviceId.isNotBlank())
        val db = database.readableDatabase
        val localId = SyncDeviceIdentity.getOrCreate(context)
        val role = state(db, "local_device_role")
        val origin = db.rawQuery(
            "SELECT origin_device_id FROM sync_operation_envelopes WHERE operation_id=?",
            arrayOf(operationId)
        ).use { c -> require(c.moveToFirst()) { "عملية المزامنة غير موجودة" }; c.getString(0) }
        val secret = when {
            role == "SECONDARY" && origin == localId -> {
                require(recipientDeviceId == state(db, "authority_device_id")) {
                    "الجهاز الثانوي يرسل أوامره إلى الجهاز الأساسي المعتمد فقط"
                }
                credentialVault.load(localId)
                    ?: throw IllegalArgumentException("بيانات اعتماد الجهاز الثانوي غير متوفرة")
            }
            role == "PRIMARY" && origin == localId -> {
                val enrolled = db.rawQuery(
                    "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
                    arrayOf(recipientDeviceId)
                ).use { it.moveToFirst() }
                require(enrolled) { "الجهاز المستلم غير مسجل أو غير مفعل" }
                credentialVault.load(recipientDeviceId)
                    ?: throw IllegalArgumentException("لا توجد بيانات اعتماد آمنة للجهاز المستلم")
            }
            else -> throw IllegalArgumentException("مصدر العملية لا يطابق دور هذا الجهاز")
        }
        try {
            val storeId = state(db, "store_id") ?: error("معرّف المتجر غير مهيأ")
            return db.rawQuery(
                """SELECT operation_id,sequence_number,origin_device_id,actor_user_id,
                          operation_type,entity_type,entity_id,payload_json,created_at
                   FROM sync_operation_envelopes WHERE operation_id=?""".trimIndent(),
                arrayOf(operationId)
            ).use { c ->
                require(c.moveToFirst()) { "عملية المزامنة غير موجودة" }
                SyncEnvelopeSecurity.create(
                    storeId, c.getString(0), c.getLong(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), 0L,
                    c.getString(7), c.getString(8), secret
                )
            }
        } finally { secret.fill(0) }
    }

    /**
     * Builds an encrypted wire frame for an already persisted outbox operation.
     * The relay sees only routing metadata and authenticated ciphertext.
     */
    fun encryptedAuthenticatedOutbox(operationId: String, recipientDeviceId: String): String {
        val envelope = authenticatedOutbox(operationId, recipientDeviceId)
        val db = database.readableDatabase
        val localId = SyncDeviceIdentity.getOrCreate(context)
        val role = state(db, "local_device_role")
        val secret = when {
            role == "SECONDARY" && envelope.originDeviceId == localId -> {
                require(recipientDeviceId == state(db, "authority_device_id"))
                credentialVault.load(localId)
                    ?: throw IllegalArgumentException("بيانات اعتماد الجهاز الثانوي غير متوفرة")
            }
            role == "PRIMARY" && envelope.originDeviceId == localId -> {
                val active = db.rawQuery(
                    "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
                    arrayOf(recipientDeviceId)
                ).use { it.moveToFirst() }
                require(active) { "الجهاز المستلم غير مسجل أو موقوف" }
                credentialVault.load(recipientDeviceId)
                    ?: throw IllegalArgumentException("بيانات اعتماد الجهاز المستلم غير متوفرة")
            }
            else -> throw IllegalArgumentException("اتجاه إرسال المزامنة غير صالح")
        }
        try {
            val peerContext = SyncEnvelopeCipher.PeerContext(
                envelope.storeId, envelope.originDeviceId, recipientDeviceId, envelope.operationId
            )
            val encrypted = SyncEnvelopeCipher.encrypt(
                SyncEnvelopeSecurity.encode(envelope).toByteArray(Charsets.UTF_8),
                javax.crypto.spec.SecretKeySpec(secret, "AES"),
                peerContext
            )
            val unsignedFrame = JSONObject().put("frame_version", 1)
                .put("store_id", envelope.storeId)
                .put("sender_device_id", localId)
                .put("recipient_device_id", recipientDeviceId)
                .put("operation_id", envelope.operationId)
                .put("ciphertext", Base64.encodeToString(
                    encrypted, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                ))
            val signingIdentity = SyncDeviceAuthenticator.getOrCreate(context, localId)
            val signingContext = SyncDeviceAuthenticator.SigningContext(
                envelope.storeId, localId, recipientDeviceId, envelope.operationId
            )
            val signature = SyncDeviceAuthenticator.sign(
                signingIdentity.private, signingContext,
                unsignedFrame.toString().toByteArray(Charsets.UTF_8)
            )
            unsignedFrame.put("signature_algorithm", "ECDSA_P256_SHA256")
                .put("sender_key_fingerprint",
                    SyncDeviceAuthenticator.fingerprint(signingIdentity.public.encoded))
                .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
            return unsignedFrame.toString()
        } finally { secret.fill(0) }
    }

    /**
     * Verify the sender's asymmetric signature against the public key pinned
     * during the authenticated enrollment handshake. Missing pins fail closed.
     */
    private fun verifyPeerFrameSignature(frame: JSONObject, storeId: String,
                                         senderId: String, recipientId: String,
                                         operationId: String) {
        require(frame.optString("signature_algorithm") == "ECDSA_P256_SHA256") {
            "خوارزمية توقيع الجهاز غير مدعومة"
        }
        val publicKey = trustedPeerPublicKey(senderId)
            ?: throw IllegalArgumentException("لا توجد هوية موثوقة مثبتة للجهاز المرسل")
        val fingerprint = SyncDeviceAuthenticator.fingerprint(publicKey)
        require(frame.getString("sender_key_fingerprint") == fingerprint) {
            "بصمة مفتاح الجهاز المرسل لا تطابق المفتاح المسجل"
        }
        val signature = Base64.decode(frame.getString("signature"), Base64.DEFAULT)
        val signedFrame = JSONObject(frame.toString())
        signedFrame.remove("signature")
        signedFrame.remove("signature_algorithm")
        signedFrame.remove("sender_key_fingerprint")
        val context = SyncDeviceAuthenticator.SigningContext(
            storeId, senderId, recipientId, operationId
        )
        require(SyncDeviceAuthenticator.verify(
            publicKey, context, signedFrame.toString().toByteArray(Charsets.UTF_8), signature
        )) { "فشل التحقق من توقيع هوية الجهاز المرسل" }
    }

    /** Decrypts, binds routing identity, then performs the existing HMAC/enrollment checks. */
    fun verifyEncryptedIncomingFromSecondary(frameJson: String): AuthenticatedSyncEnvelope {
        val frame = JSONObject(frameJson)
        require(frame.getInt("frame_version") == 1) { "إصدار إطار المزامنة غير مدعوم" }
        val storeId = frame.getString("store_id")
        val senderId = frame.getString("sender_device_id")
        val recipientId = frame.getString("recipient_device_id")
        val operationId = frame.getString("operation_id")
        val localId = SyncDeviceIdentity.getOrCreate(context)
        require(recipientId == localId) { "إطار المزامنة موجه إلى جهاز آخر" }
        val db = database.readableDatabase
        require(state(db, "local_device_role") == "PRIMARY" &&
            state(db, "authority_device_id") == localId) { "هذا الجهاز ليس الجهاز الأساسي المعتمد" }
        require(storeId == state(db, "store_id")) { "الإطار يخص متجراً آخر" }
        verifyPeerFrameSignature(frame, storeId, senderId, recipientId, operationId)
        val enrolled = db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
            arrayOf(senderId)
        ).use { it.moveToFirst() }
        require(enrolled) { "الجهاز المرسل غير مسجل أو تم إلغاء تسجيله" }
        val secret = credentialVault.load(senderId)
            ?: throw IllegalArgumentException("بيانات اعتماد الجهاز المرسل غير متوفرة")
        try {
            val context = SyncEnvelopeCipher.PeerContext(storeId, senderId, recipientId, operationId)
            val ciphertext = Base64.decode(
                frame.getString("ciphertext"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val plaintext = SyncEnvelopeCipher.decrypt(
                ciphertext, javax.crypto.spec.SecretKeySpec(secret, "AES"), context
            ) ?: throw IllegalArgumentException("فشل فك تشفير إطار المزامنة أو التحقق من سلامته")
            val encoded = plaintext.toString(Charsets.UTF_8)
            val envelope = SyncEnvelopeSecurity.decode(encoded)
            require(envelope.storeId == storeId && envelope.originDeviceId == senderId &&
                envelope.operationId == operationId) { "بيانات هوية الإطار لا تطابق الرسالة المشفرة" }
            return verifyIncomingFromSecondary(encoded)
        } finally { secret.fill(0) }
    }

    /** Decrypts a primary frame and verifies the configured authority and HMAC. */
    fun verifyEncryptedIncomingFromPrimary(frameJson: String): AuthenticatedSyncEnvelope {
        val frame = JSONObject(frameJson)
        require(frame.getInt("frame_version") == 1) { "إصدار إطار المزامنة غير مدعوم" }
        val storeId = frame.getString("store_id")
        val senderId = frame.getString("sender_device_id")
        val recipientId = frame.getString("recipient_device_id")
        val operationId = frame.getString("operation_id")
        val localId = SyncDeviceIdentity.getOrCreate(context)
        val db = database.readableDatabase
        require(state(db, "local_device_role") == "SECONDARY") { "هذا الجهاز ليس جهازاً ثانوياً مسجلاً" }
        require(recipientId == localId && senderId == state(db, "authority_device_id")) {
            "مصدر أو مستلم الإطار لا يطابق هوية الجهاز المعتمدة"
        }
        require(storeId == state(db, "store_id")) { "الإطار يخص متجراً آخر" }
        verifyPeerFrameSignature(frame, storeId, senderId, recipientId, operationId)
        val secret = credentialVault.load(localId)
            ?: throw IllegalArgumentException("بيانات اعتماد الجهاز الأساسي غير متوفرة")
        try {
            val context = SyncEnvelopeCipher.PeerContext(storeId, senderId, recipientId, operationId)
            val ciphertext = Base64.decode(
                frame.getString("ciphertext"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val plaintext = SyncEnvelopeCipher.decrypt(
                ciphertext, javax.crypto.spec.SecretKeySpec(secret, "AES"), context
            ) ?: throw IllegalArgumentException("فشل فك تشفير إطار المزامنة أو التحقق من سلامته")
            val encoded = plaintext.toString(Charsets.UTF_8)
            val envelope = SyncEnvelopeSecurity.decode(encoded)
            require(envelope.storeId == storeId && envelope.originDeviceId == senderId &&
                envelope.operationId == operationId) { "بيانات هوية الإطار لا تطابق الرسالة المشفرة" }
            return verifyIncomingFromPrimary(encoded)
        } finally { secret.fill(0) }
    }

    /**
     * Persists a secondary's business intent as a pending command only.
     * It deliberately performs no invoice, cash, debt, or inventory posting.
     * The caller must keep this insert in the same transaction as local command creation.
     */
    /** Queue a harmless end-to-end transport probe on a secondary device. */
    fun enqueueTransportProbe(actorUserId: String): String {
        require(isConfiguredSecondary()) { "اختبار النقل متاح على الجهاز الثانوي المرتبط فقط" }
        val db = database.writableDatabase
        val operationId = UUID.randomUUID().toString()
        db.beginTransaction()
        try {
            PermissionPolicy.requireAssigned(db, actorUserId, PermissionPolicy.SYNC_MANAGE)
            val payload = JSONObject()
                .put("probe_id", operationId)
                .put("nonce", UUID.randomUUID().toString())
                .put("created_at", timestamp())
                .toString()
            enqueueSecondaryCommand(
                db, actorUserId, operationId, "SYNC_TRANSPORT_PROBE",
                "SYNC_TRANSPORT_PROBE", operationId, payload
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return operationId
    }

    fun outboxStatus(operationId: String): String? {
        if (operationId.isBlank()) return null
        return database.readableDatabase.rawQuery(
            "SELECT status FROM sync_queue WHERE operation_id=?", arrayOf(operationId)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    fun enqueueSecondaryCommand(
        db: SQLiteDatabase, actorUserId: String, operationId: String,
        businessOperation: String, entityType: String, entityId: String,
        payloadJson: String
    ): Long {
        require(db.inTransaction()) { "يجب حفظ الأمر ضمن معاملة قاعدة البيانات" }
        require(state(db, "local_device_role") == "SECONDARY") {
            "إرسال الأوامر المعلقة متاح للجهاز الثانوي المسجل فقط"
        }
        require(state(db, "authority_device_id").isNullOrBlank().not()) { "الجهاز الأساسي غير محدد" }
        require(businessOperation in setOf(
            "POST_SALE_INVOICE", "COLLECT_CUSTOMER_PAYMENT",
            "COLLECT_SERVICE_TRANSFER_DEBT", "SYNC_TRANSPORT_PROBE"
        )) {
            "الجهاز الثانوي مخول بإرسال فواتير البيع وتحصيل ديون العملاء وتحويلات الرصيد الآجلة واختبارات المزامنة فقط"
        }
        PermissionPolicy.requireAssigned(
            db, actorUserId,
            when (businessOperation) {
                "POST_SALE_INVOICE" -> PermissionPolicy.SALES_CHECKOUT
                "COLLECT_CUSTOMER_PAYMENT", "COLLECT_SERVICE_TRANSFER_DEBT" -> PermissionPolicy.CUSTOMERS_COLLECT
                else -> PermissionPolicy.SYNC_MANAGE
            }
        )
        require(operationId.isNotBlank() && businessOperation.isNotBlank() &&
            entityType.isNotBlank() && entityId.isNotBlank())
        require(payloadJson.length in 2..MAX_PAYLOAD_CHARS)
        val actorActive = db.rawQuery(
            "SELECT 1 FROM users WHERE id=? AND is_active=1 AND must_change_password=0",
            arrayOf(actorUserId)
        ).use { it.moveToFirst() }
        require(actorActive) { "المستخدم غير مفعل أو يجب تغيير كلمة المرور" }
        val payload = SyncEnvelopeSecurity.canonicalObject(
            JSONObject(payloadJson).put("business_operation", businessOperation).toString()
        )
        val hash = sha256(payload)
        val prior = db.rawQuery(
            """SELECT sequence_number,payload_sha256,entity_type,entity_id,
                      actor_user_id,operation_type,origin_device_id
               FROM sync_operation_envelopes WHERE operation_id=?""".trimIndent(),
            arrayOf(operationId)
        ).use { c ->
            if (!c.moveToFirst()) null else arrayOf(
                c.getLong(0).toString(), c.getString(1), c.getString(2), c.getString(3),
                c.getString(4), c.getString(5), c.getString(6)
            )
        }
        if (prior != null) {
            require(
                prior[1] == hash && prior[2] == entityType && prior[3] == entityId &&
                    prior[4] == actorUserId && prior[5] == "CREATE" && prior[6] == SyncDeviceIdentity.getOrCreate(context)
            ) {
                "أعيد استخدام معرّف الأمر بهوية أو بيانات مختلفة"
            }
            return prior[0].toLong()
        }
        val localId = SyncDeviceIdentity.getOrCreate(context)
        val active = db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
            arrayOf(localId)
        ).use { it.moveToFirst() }
        check(active) { "الجهاز الثانوي غير مسجل أو تم إيقافه" }
        val now = timestamp()
        val sequence = (state(db, "secondary_next_sequence") ?: "1").toLong()
        check(sequence > 0L && sequence < Long.MAX_VALUE) { "تسلسل أوامر الجهاز الثانوي غير صالح" }
        putState(db, "secondary_next_sequence", (sequence + 1L).toString(), now)
        db.insertOrThrow("sync_operation_envelopes", null, ContentValues().apply {
            put("operation_id", operationId); put("sequence_number", sequence)
            put("origin_device_id", localId); put("actor_user_id", actorUserId)
            put("operation_type", "CREATE"); put("entity_type", entityType); put("entity_id", entityId)
            put("payload_json", payload); put("payload_sha256", hash); put("created_at", now)
        })
        db.insertOrThrow("sync_queue", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("operation_id", operationId)
            put("entity_type", entityType); put("entity_id", entityId); put("operation_type", "CREATE")
            put("payload_json", payload); put("status", "PENDING"); put("retry_count", 0)
            put("created_at", now); put("updated_at", now)
        })
        return sequence
    }

    /** Explicitly maps a remote secondary login to a local primary account. */
    fun mapSecondaryActor(
        managerUserId: String, secondaryDeviceId: String,
        remoteActorUserId: String, primaryUserId: String
    ) {
        require(remoteActorUserId.isNotBlank() && primaryUserId.isNotBlank())
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            requirePrimaryManager(db, managerUserId)
            val enrolled = db.rawQuery(
                "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
                arrayOf(secondaryDeviceId)
            ).use { it.moveToFirst() }
            require(enrolled) { "الجهاز الثانوي غير مسجل أو غير مفعل" }
            val userActive = db.rawQuery(
                "SELECT 1 FROM users WHERE id=? AND is_active=1 AND must_change_password=0",
                arrayOf(primaryUserId)
            ).use { it.moveToFirst() }
            require(userActive) { "حساب المستخدم الأساسي غير مفعل أو يتطلب تغيير كلمة المرور" }
            val now = timestamp()
            db.insertWithOnConflict("sync_actor_mappings", null, ContentValues().apply {
                put("device_id", secondaryDeviceId); put("remote_actor_user_id", remoteActorUserId)
                put("primary_user_id", primaryUserId); put("is_active", 1)
                put("mapped_by", managerUserId); put("mapped_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            audit(db, managerUserId, "SYNC_ACTOR_MAPPED", secondaryDeviceId, null,
                JSONObject().put("remote_actor_user_id", remoteActorUserId)
                    .put("primary_user_id", primaryUserId).toString(), now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Maps a secondary's cash box or wallet to an explicitly selected primary account. */
    fun mapSecondaryCashAccount(
        managerUserId: String, secondaryDeviceId: String,
        remoteCashAccountId: String, primaryCashAccountId: String
    ) {
        require(secondaryDeviceId.isNotBlank() && remoteCashAccountId.isNotBlank() &&
            primaryCashAccountId.isNotBlank()) { "بيانات ربط الحساب المالي غير مكتملة" }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            requirePrimaryManager(db, managerUserId)
            val enrolled = db.rawQuery(
                "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
                arrayOf(secondaryDeviceId)
            ).use { it.moveToFirst() }
            require(enrolled) { "الجهاز الثانوي غير مسجل أو غير مفعل" }
            val account = db.rawQuery(
                "SELECT account_type,currency_code,is_active FROM cash_accounts WHERE id=?",
                arrayOf(primaryCashAccountId)
            ).use { c ->
                require(c.moveToFirst()) { "الحساب المالي الأساسي غير موجود" }
                require(c.getInt(2) == 1) { "لا يمكن الربط بحساب مالي موقوف" }
                c.getString(0) to c.getString(1)
            }
            require(account.first in setOf("CASH_BOX", "WALLET") &&
                account.second in setOf("USD", "SYP")) {
                "يجب اختيار صندوق أو محفظة نشطة بعملة مدعومة"
            }
            val now = timestamp()
            db.insertWithOnConflict("sync_cash_account_mappings", null, ContentValues().apply {
                put("device_id", secondaryDeviceId)
                put("remote_cash_account_id", remoteCashAccountId)
                put("primary_cash_account_id", primaryCashAccountId)
                put("is_active", 1)
                put("mapped_by", managerUserId)
                put("mapped_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            audit(db, managerUserId, "SYNC_CASH_ACCOUNT_MAPPED", secondaryDeviceId, null,
                JSONObject().put("remote_cash_account_id", remoteCashAccountId)
                    .put("primary_cash_account_id", primaryCashAccountId)
                    .put("account_type", account.first).put("currency_code", account.second).toString(), now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun requiredPermissionForBusinessOperation(operation: String): String =
        when (operation) {
            "POST_SALE_INVOICE" -> PermissionPolicy.SALES_CHECKOUT
            "COLLECT_CUSTOMER_PAYMENT", "COLLECT_SERVICE_TRANSFER_DEBT" -> PermissionPolicy.CUSTOMERS_COLLECT
            else -> throw IllegalArgumentException(
                "الجهاز الثانوي مخول بإرسال فواتير البيع وتحصيل ديون العملاء وتحويلات الرصيد الآجلة واختبارات المزامنة فقط"
            )
        }

    /**
     * Durable authenticated receive on the primary. This only records an inbox
     * command and advances its per-device receive cursor; domain posting is a
     * separate, permission-checked, idempotent transaction and is not implied.
     */
    fun recordIncomingFromSecondary(encodedEnvelope: String): SyncInboxDisposition {
        val envelope = verifyIncomingFromSecondary(encodedEnvelope)
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val active = db.rawQuery(
                "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
                arrayOf(envelope.originDeviceId)
            ).use { it.moveToFirst() }
            require(active) { "تم إلغاء تسجيل الجهاز المرسل" }
            // Resolve an existing operation before re-checking mutable actor permissions.
            // This lets an already accepted operation receive its lost ACK after a
            // permission change, while never allowing a different device or envelope
            // metadata to impersonate that operation ID.
            val existing = db.rawQuery(
                """SELECT payload_sha256,origin_device_id,sequence_number,operation_type,
                          entity_type,entity_id,origin_created_at,payload_json,actor_user_id,status
                   FROM sync_inbox WHERE operation_id=?""".trimIndent(),
                arrayOf(envelope.operationId)
            ).use { c ->
                if (!c.moveToFirst()) null else arrayOf(
                    c.getString(0), c.getString(1), c.getLong(2).toString(), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), c.getString(7),
                    c.getString(8), c.getString(9)
                )
            }
            if (existing != null) {
                val exactReplay = existing[0] == envelope.payloadSha256 &&
                    existing[1] == envelope.originDeviceId &&
                    existing[2].toLong() == envelope.sequenceNumber &&
                    existing[3] == envelope.operationType &&
                    existing[4] == envelope.entityType &&
                    existing[5] == envelope.entityId &&
                    existing[6] == envelope.createdAt &&
                    (existing[8] == envelope.actorUserId ||
                        (existing[8] == null && existing[9] == "APPLIED"))
                if (exactReplay) {
                    db.setTransactionSuccessful()
                    return SyncInboxDisposition.DUPLICATE
                }
                db.insertOrThrow("sync_conflicts", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("operation_id", envelope.operationId)
                    put("entity_type", envelope.entityType); put("entity_id", envelope.entityId)
                    put("local_payload_json", existing[7]); put("remote_payload_json", envelope.payloadJson)
                    put("created_at", timestamp())
                })
                db.setTransactionSuccessful()
                return SyncInboxDisposition.CONFLICT
            }

            val payload = JSONObject(envelope.payloadJson)
            val businessOperation = payload.optString("business_operation")
            require(businessOperation in setOf(
                "POST_SALE_INVOICE", "COLLECT_CUSTOMER_PAYMENT",
                "COLLECT_SERVICE_TRANSFER_DEBT", "SYNC_TRANSPORT_PROBE"
            )) {
                "الجهاز الثانوي مخول بإرسال فواتير البيع وتحصيل ديون العملاء وتحويلات الرصيد الآجلة واختبارات المزامنة فقط"
            }
            if (businessOperation == "COLLECT_SERVICE_TRANSFER_DEBT") {
                require(envelope.operationType == "CREATE" &&
                    envelope.entityType == "SERVICE_TRANSFER_DEBT_PAYMENT" &&
                    payload.optString("payment_id") == envelope.entityId &&
                    payload.optString("transfer_id").isNotBlank() &&
                    payload.optString("customer_id").isNotBlank() &&
                    payload.optLong("amount_minor", 0L) > 0L &&
                    payload.optString("cash_account_id").isNotBlank() &&
                    payload.optString("cash_account_type") in setOf("CASH_BOX", "WALLET") &&
                    payload.optString("cash_account_currency_code") == "SYP" &&
                    payload.optString("operating_date").matches(Regex("\\d{4}-\\d{2}-\\d{2}")) &&
                    payload.optString("occurred_at").matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
                    "بيانات تحصيل دين تحويل الرصيد غير صالحة"
                }
            }
            if (businessOperation == "SYNC_TRANSPORT_PROBE") {
                // A transport probe is deliberately non-financial. It proves the
                // authenticated route and signed ACK without requiring business
                // actor/account/catalog mappings or touching the store ledger.
                require(envelope.operationType == "CREATE" &&
                    envelope.entityType == "SYNC_TRANSPORT_PROBE" &&
                    envelope.entityId == envelope.operationId &&
                    payload.optString("probe_id") == envelope.operationId &&
                    runCatching { UUID.fromString(payload.optString("nonce")) }.isSuccess &&
                    payload.optString("created_at").matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
                    "بيانات اختبار النقل غير صالحة"
                }
            } else {
                val requiredPermission = requiredPermissionForBusinessOperation(businessOperation)
                val mappedUser = db.rawQuery(
                    """SELECT m.primary_user_id FROM sync_actor_mappings m
                       JOIN users u ON u.id=m.primary_user_id
                       WHERE m.device_id=? AND m.remote_actor_user_id=? AND m.is_active=1
                         AND u.is_active=1 AND u.must_change_password=0""".trimIndent(),
                    arrayOf(envelope.originDeviceId, envelope.actorUserId)
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: throw IllegalArgumentException("لم يتم ربط مستخدم الجهاز الثانوي بحساب مخول على الجهاز الأساسي")
                PermissionPolicy.require(db, mappedUser, requiredPermission)
            }
            val last = db.rawQuery(
                "SELECT last_sequence FROM sync_cursors WHERE device_id=?",
                arrayOf(envelope.originDeviceId)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            require(envelope.sequenceNumber == last + 1L) {
                if (envelope.sequenceNumber <= last) "تم رفض إعادة تشغيل تسلسل قديم"
                else "فجوة في تسلسل أوامر الجهاز الثانوي؛ انتظر الأمر السابق"
            }
            val now = timestamp()
            db.insertOrThrow("sync_inbox", null, ContentValues().apply {
                put("operation_id", envelope.operationId); put("origin_device_id", envelope.originDeviceId)
                put("sequence_number", envelope.sequenceNumber); put("operation_type", envelope.operationType)
                put("entity_type", envelope.entityType); put("entity_id", envelope.entityId)
                put("actor_user_id", envelope.actorUserId)
                put("payload_json", envelope.payloadJson); put("payload_sha256", envelope.payloadSha256)
                put("status", "RECEIVED"); put("received_at", now)
                put("origin_created_at", envelope.createdAt)
            })
            db.update("sync_cursors", ContentValues().apply {
                put("last_sequence", envelope.sequenceNumber); put("updated_at", now)
            }, "device_id=?", arrayOf(envelope.originDeviceId))
            db.setTransactionSuccessful()
            return SyncInboxDisposition.RECEIVED
        } finally { db.endTransaction() }
    }

    fun verifyIncomingFromPrimary(encodedEnvelope: String): AuthenticatedSyncEnvelope {
        val envelope = SyncEnvelopeSecurity.decode(encodedEnvelope)
        val db = database.readableDatabase
        require(state(db, "local_device_role") == "SECONDARY") { "هذا الجهاز ليس جهازاً ثانوياً مسجلاً" }
        require(envelope.originDeviceId == state(db, "authority_device_id")) {
            "مصدر الرسالة ليس الجهاز الأساسي المعتمد"
        }
        require(envelope.storeId == state(db, "store_id")) { "الرسالة تخص متجراً آخر" }
        val secret = credentialVault.load(SyncDeviceIdentity.getOrCreate(context))
            ?: throw IllegalArgumentException("بيانات اعتماد الجهاز الأساسي غير متوفرة")
        try {
            require(SyncEnvelopeSecurity.verify(envelope, secret)) { "فشل التحقق من توقيع المزامنة" }
            return envelope
        } finally { secret.fill(0) }
    }

    fun verifyIncomingFromSecondary(encodedEnvelope: String): AuthenticatedSyncEnvelope {
        val envelope = SyncEnvelopeSecurity.decode(encodedEnvelope)
        val db = database.readableDatabase
        require(state(db, "authority_device_id") == SyncDeviceIdentity.getOrCreate(context)) {
            "هذا الهاتف ليس الجهاز الأساسي"
        }
        require(envelope.storeId == state(db, "store_id")) { "الرسالة تخص متجراً آخر" }
        val enrolled = db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
            arrayOf(envelope.originDeviceId)
        ).use { it.moveToFirst() }
        require(enrolled) { "الجهاز المرسل غير مسجل أو تم إلغاء تسجيله" }
        val secret = credentialVault.load(envelope.originDeviceId)
            ?: throw IllegalArgumentException("بيانات اعتماد الجهاز المرسل غير متوفرة")
        try {
            require(SyncEnvelopeSecurity.verify(envelope, secret)) { "فشل التحقق من توقيع الجهاز المرسل" }
            return envelope
        } finally { secret.fill(0) }
    }

    /**
     * Returns only durable store-setting changes not yet acknowledged by this enrolled device.
     * The settings stream is deliberately separate from business-event delivery until the
     * corresponding domain appliers are implemented.
     */
    fun pendingStoreSettingsForDevice(deviceId: String, limit: Int = 100): List<SyncOutboxItem> {
        require(limit in 1..500)
        val db = database.readableDatabase
        require(state(db, "local_device_role") == "PRIMARY" &&
            state(db, "authority_device_id") == SyncDeviceIdentity.getOrCreate(context)) {
            "توزيع إعدادات المتجر متاح من الجهاز الأساسي فقط"
        }
        val active = db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
            arrayOf(deviceId)
        ).use { it.moveToFirst() }
        require(active) { "الجهاز المستلم غير مسجل أو غير مفعل" }
        val result = mutableListOf<SyncOutboxItem>()
        db.rawQuery(
            """SELECT e.operation_id,e.sequence_number,e.operation_type,e.entity_type,e.entity_id,
                      e.payload_json,e.payload_sha256,e.created_at,q.retry_count
               FROM sync_operation_envelopes e
               JOIN sync_queue q ON q.operation_id=e.operation_id
               WHERE e.entity_type='STORE_SETTINGS'
                 AND NOT EXISTS (
                   SELECT 1 FROM sync_acknowledgements a
                   WHERE a.operation_id=e.operation_id AND a.device_id=? AND a.status='APPLIED'
                 )
               ORDER BY e.sequence_number LIMIT ?""".trimIndent(),
            arrayOf(deviceId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) result.add(SyncOutboxItem(
                c.getString(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getString(6), c.getString(7), c.getInt(8)
            ))
        }
        return result
    }

    /** Authenticated primary-to-secondary receipt with a durable settings-only cursor. */
    fun recordIncomingStoreSettingsFromPrimary(encodedEnvelope: String): SyncInboxDisposition {
        val envelope = verifyIncomingFromPrimary(encodedEnvelope)
        require(envelope.operationType == "UPDATE" && envelope.entityType == "STORE_SETTINGS" &&
            envelope.entityId == "store_time_zone") {
            "نوع إعداد المتجر الوارد غير مدعوم"
        }
        val payload = JSONObject(envelope.payloadJson)
        require(payload.optString("business_operation") == "UPDATE_STORE_TIME_ZONE" &&
            StoreTime.isValidZoneId(payload.optString("time_zone"))) {
            "تحديث المنطقة الزمنية الوارد غير صالح"
        }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT payload_sha256 FROM sync_inbox WHERE operation_id=?",
                arrayOf(envelope.operationId)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (existing != null) {
                if (existing == envelope.payloadSha256) {
                    db.setTransactionSuccessful()
                    return SyncInboxDisposition.DUPLICATE
                }
                db.insertOrThrow("sync_conflicts", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("operation_id", envelope.operationId)
                    put("entity_type", envelope.entityType)
                    put("entity_id", envelope.entityId)
                    put("local_payload_json", "{}")
                    put("remote_payload_json", envelope.payloadJson)
                    put("created_at", timestamp())
                })
                db.setTransactionSuccessful()
                return SyncInboxDisposition.CONFLICT
            }
            val lastSequence = state(db, "primary_store_settings_cursor")?.toLongOrNull() ?: 0L
            require(envelope.sequenceNumber > lastSequence) {
                "تم رفض تحديث إعداد متجر أقدم أو معاد التشغيل"
            }
            val now = timestamp()
            db.insertOrThrow("sync_inbox", null, ContentValues().apply {
                put("operation_id", envelope.operationId)
                put("origin_device_id", envelope.originDeviceId)
                put("sequence_number", envelope.sequenceNumber)
                put("operation_type", envelope.operationType)
                put("entity_type", envelope.entityType)
                put("entity_id", envelope.entityId)
                put("payload_json", envelope.payloadJson)
                put("payload_sha256", envelope.payloadSha256)
                put("status", "RECEIVED")
                put("received_at", now)
                put("origin_created_at", envelope.createdAt)
            })
            putState(db, "primary_store_settings_cursor", envelope.sequenceNumber.toString(), now)
            db.setTransactionSuccessful()
            return SyncInboxDisposition.RECEIVED
        } finally { db.endTransaction() }
    }

    /** Authenticated primary-to-secondary receipt for authoritative exchange-rate snapshots. */
    fun recordIncomingExchangeRateFromPrimary(encodedEnvelope: String): SyncInboxDisposition {
        val envelope = verifyIncomingFromPrimary(encodedEnvelope)
        require(envelope.operationType == "CREATE" && envelope.entityType == "EXCHANGE_RATE") {
            "نوع تحديث سعر الصرف الوارد غير مدعوم"
        }
        val payload = JSONObject(envelope.payloadJson)
        require(payload.optString("business_operation") == "SET_EXCHANGE_RATE" &&
            payload.optString("id") == envelope.entityId &&
            payload.optString("currency_code") == "USD" &&
            payload.optLong("rate_syp_per_usd_milli") > 0L &&
            payload.optString("effective_at").matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
            "بيانات سعر الصرف الواردة غير صالحة"
        }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery(
                "SELECT payload_sha256 FROM sync_inbox WHERE operation_id=?",
                arrayOf(envelope.operationId)
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (existing != null) {
                if (existing == envelope.payloadSha256) {
                    db.setTransactionSuccessful()
                    return SyncInboxDisposition.DUPLICATE
                }
                db.insertOrThrow("sync_conflicts", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString())
                    put("operation_id", envelope.operationId)
                    put("entity_type", envelope.entityType)
                    put("entity_id", envelope.entityId)
                    put("local_payload_json", "{}")
                    put("remote_payload_json", envelope.payloadJson)
                    put("created_at", timestamp())
                })
                db.setTransactionSuccessful()
                return SyncInboxDisposition.CONFLICT
            }
            val lastSequence = state(db, "primary_exchange_rate_cursor")?.toLongOrNull() ?: 0L
            require(envelope.sequenceNumber > lastSequence) {
                "تم رفض سعر صرف أقدم أو معاد التشغيل"
            }
            val now = timestamp()
            db.insertOrThrow("sync_inbox", null, ContentValues().apply {
                put("operation_id", envelope.operationId)
                put("origin_device_id", envelope.originDeviceId)
                put("sequence_number", envelope.sequenceNumber)
                put("operation_type", envelope.operationType)
                put("entity_type", envelope.entityType)
                put("entity_id", envelope.entityId)
                put("payload_json", envelope.payloadJson)
                put("payload_sha256", envelope.payloadSha256)
                put("status", "RECEIVED")
                put("received_at", now)
                put("origin_created_at", envelope.createdAt)
            })
            putState(db, "primary_exchange_rate_cursor", envelope.sequenceNumber.toString(), now)
            db.setTransactionSuccessful()
            return SyncInboxDisposition.RECEIVED
        } finally { db.endTransaction() }
    }

    /** Records an APPLIED acknowledgement for a primary-origin exchange-rate event. */
    fun acknowledgeExchangeRateApplied(deviceId: String, operationId: String) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            require(state(db, "local_device_role") == "PRIMARY" &&
                state(db, "authority_device_id") == SyncDeviceIdentity.getOrCreate(context)) {
                "تثبيت إقرار سعر الصرف متاح على الجهاز الأساسي فقط"
            }
            val valid = db.rawQuery(
                """SELECT 1 FROM sync_devices d
                   JOIN sync_operation_envelopes e ON e.operation_id=?
                   WHERE d.device_id=? AND d.device_role='SECONDARY' AND d.status='ACTIVE'
                     AND e.origin_device_id=? AND e.entity_type='EXCHANGE_RATE'""".trimIndent(),
                arrayOf(operationId, deviceId, SyncDeviceIdentity.getOrCreate(context))
            ).use { it.moveToFirst() }
            require(valid) { "إقرار سعر الصرف لا يطابق جهازاً أو عملية مسجلة" }
            db.insertWithOnConflict("sync_acknowledgements", null, ContentValues().apply {
                put("operation_id", operationId)
                put("device_id", deviceId)
                put("sequence_number", db.rawQuery(
                    "SELECT sequence_number FROM sync_operation_envelopes WHERE operation_id=?",
                    arrayOf(operationId)
                ).use { c -> require(c.moveToFirst()); c.getLong(0) })
                put("status", "APPLIED")
                put("acknowledged_at", timestamp())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Durable per-device acknowledgement; delivery is complete only after APPLIED. */
    fun acknowledgeStoreSettingsApplied(deviceId: String, operationId: String) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            require(state(db, "local_device_role") == "PRIMARY" &&
                state(db, "authority_device_id") == SyncDeviceIdentity.getOrCreate(context)) {
                "تثبيت إقرار إعداد المتجر متاح على الجهاز الأساسي فقط"
            }
            val valid = db.rawQuery(
                """SELECT 1 FROM sync_devices d
                   JOIN sync_operation_envelopes e ON e.operation_id=?
                   WHERE d.device_id=? AND d.device_role='SECONDARY' AND d.status='ACTIVE'
                     AND e.origin_device_id=? AND e.entity_type='STORE_SETTINGS'""".trimIndent(),
                arrayOf(operationId, deviceId, SyncDeviceIdentity.getOrCreate(context))
            ).use { it.moveToFirst() }
            require(valid) { "إقرار إعداد المتجر لا يطابق جهازاً أو عملية مسجلة" }
            db.insertWithOnConflict("sync_acknowledgements", null, ContentValues().apply {
                put("operation_id", operationId)
                put("device_id", deviceId)
                put("sequence_number", db.rawQuery(
                    "SELECT sequence_number FROM sync_operation_envelopes WHERE operation_id=?",
                    arrayOf(operationId)
                ).use { c -> require(c.moveToFirst()); c.getLong(0) })
                put("status", "APPLIED")
                put("acknowledged_at", timestamp())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun authenticateDevice(deviceId: String, credential: String): Boolean {
        if (deviceId.isBlank() || credential.isBlank()) return false
        val stored = database.readableDatabase.rawQuery(
            "SELECT credential_hash FROM sync_devices WHERE device_id=? AND status='ACTIVE' AND device_role='SECONDARY'",
            arrayOf(deviceId)
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
        return MessageDigest.isEqual(stored.toByteArray(Charsets.US_ASCII), sha256(credential).toByteArray(Charsets.US_ASCII))
    }

    fun revokeDevice(actorUserId: String, remoteDeviceId: String): String {
        val db = database.writableDatabase
        var signedRevocation: String? = null
        db.beginTransaction()
        try {
            requirePrimaryManager(db, actorUserId)
            require(remoteDeviceId != SyncDeviceIdentity.getOrCreate(context)) {
                "لا يمكن إلغاء الجهاز الأساسي من هذه الشاشة"
            }
            val before = db.rawQuery(
                """SELECT d.display_name,d.status,k.certificate_json
                   FROM sync_devices d JOIN sync_device_public_keys k ON k.device_id=d.device_id
                   WHERE d.device_id=? AND d.device_role='SECONDARY'""".trimIndent(),
                arrayOf(remoteDeviceId)
            ).use {
                require(it.moveToFirst()) { "الجهاز غير موجود أو لا يملك شهادة" }
                val certificate = it.getString(2)
                require(certificate.isNotBlank()) { "شهادة الجهاز غير موجودة" }
                val serial = SyncDeviceCertificate.serial(certificate)
                val root = SyncDeviceAuthenticator.getOrCreate(context, SyncDeviceIdentity.getOrCreate(context))
                val storeId = state(db, "store_id") ?: error("معرّف المتجر غير مهيأ")
                signedRevocation = SyncDeviceCertificate.signedRevocation(
                    storeId, remoteDeviceId, serial, root.private
                )
                JSONObject().put("display_name", it.getString(0)).put("status", it.getString(1)).toString()
            }
            val now = timestamp()
            db.update("sync_devices", ContentValues().apply {
                put("status", "REVOKED"); put("revoked_at", now)
            }, "device_id=?", arrayOf(remoteDeviceId))
            db.update("sync_device_public_keys", ContentValues().apply {
                put("status", "REVOKED"); put("revoked_at", now)
            }, "device_id=?", arrayOf(remoteDeviceId))
            val statement = signedRevocation ?: error("تعذر إنشاء بيان إلغاء الشهادة")
            val serial = JSONObject(statement).getString("serial")
            db.insertWithOnConflict("sync_device_revocations", null, ContentValues().apply {
                put("device_id", remoteDeviceId)
                put("certificate_serial", serial)
                put("revocation_json", statement)
                put("status", "PENDING")
                put("created_at", now)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            audit(db, actorUserId, "SYNC_DEVICE_REVOKED", remoteDeviceId, before,
                JSONObject().put("status", "REVOKED").toString(), now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        credentialVault.remove(remoteDeviceId)
        return signedRevocation ?: error("تعذر إنشاء بيان إلغاء الشهادة")
    }

    /**
     * Must be invoked inside the same SQLite transaction as the business posting.
     * It only records an outbox item when this installation is the configured primary.
     */
    fun enqueueLocalOperation(
        db: SQLiteDatabase, actorUserId: String, operationId: String,
        operationType: String, entityType: String, entityId: String,
        changeKind: String, payloadJson: String
    ): Long? {
        require(db.inTransaction()) { "يجب تسجيل حدث المزامنة ضمن معاملة العملية الأصلية" }
        require(operationId.isNotBlank() && operationType.isNotBlank() &&
            entityType.isNotBlank() && entityId.isNotBlank()) { "بيانات حدث المزامنة غير مكتملة" }
        require(changeKind in setOf("CREATE", "UPDATE", "DELETE")) { "نوع تغيير المزامنة غير صالح" }
        require(payloadJson.length in 2..MAX_PAYLOAD_CHARS) { "حجم بيانات المزامنة غير صالح" }
        val payloadObject = JSONObject(payloadJson)
        val payloadWithAction = JSONObject(payloadObject.toString())
            .put("business_operation", operationType)
        val canonicalPayload = SyncEnvelopeSecurity.canonicalObject(payloadWithAction.toString())
        val deviceId = SyncDeviceIdentity.getOrCreate(context)
        if (state(db, "authority_device_id") != deviceId) return null
        // Secondary terminals are sales-only. Never enqueue primary financial-ledger
        // events for peer delivery; secondaries consume only authoritative reference data.
        if (entityType !in PRIMARY_TO_SECONDARY_REFERENCE_ENTITIES) return null
        val activePrimary = db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='PRIMARY' AND status='ACTIVE'",
            arrayOf(deviceId)
        ).use { it.moveToFirst() }
        check(activePrimary) { "هوية الجهاز الأساسي غير مفعلة" }
        val payloadHash = sha256(canonicalPayload)
        val existing = db.rawQuery(
            "SELECT sequence_number,payload_sha256,operation_type,entity_type,entity_id FROM sync_operation_envelopes WHERE operation_id=?",
            arrayOf(operationId)
        ).use { c ->
            if (c.moveToFirst()) {
                require(c.getString(1) == payloadHash && c.getString(2) == changeKind &&
                    c.getString(3) == entityType && c.getString(4) == entityId) {
                    "أعيد استخدام معرّف العملية بمحتوى مزامنة مختلف"
                }
                c.getLong(0)
            } else null
        }
        if (existing != null) return existing
        val now = timestamp()
        val next = (state(db, "next_sequence") ?: "1").toLong()
        check(next > 0L && next < Long.MAX_VALUE) { "تسلسل المزامنة غير صالح" }
        putState(db, "next_sequence", (next + 1L).toString(), now)
        db.insertOrThrow("sync_operation_envelopes", null, ContentValues().apply {
            put("operation_id", operationId); put("sequence_number", next)
            put("origin_device_id", deviceId); put("actor_user_id", actorUserId)
            put("operation_type", changeKind); put("entity_type", entityType); put("entity_id", entityId)
            put("payload_json", canonicalPayload); put("payload_sha256", payloadHash); put("created_at", now)
        })
        db.insertOrThrow("sync_queue", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("operation_id", operationId)
            put("entity_type", entityType); put("entity_id", entityId); put("operation_type", changeKind)
            put("payload_json", canonicalPayload); put("status", "PENDING"); put("retry_count", 0)
            put("created_at", now); put("updated_at", now)
        })
        db.update("sync_devices", ContentValues().apply { put("last_seen_at", now) },
            "device_id=?", arrayOf(deviceId))
        return next
    }

    /**
     * Replicates account identity and selection metadata only. Balances, opening amounts,
     * and cash movements remain exclusively on the primary.
     */
    fun enqueueCashAccountReference(
        db: SQLiteDatabase, actorUserId: String, accountId: String
    ): Long? {
        require(db.inTransaction()) { "يجب تسجيل مرجع الحساب المالي ضمن معاملة" }
        val account = db.rawQuery(
            "SELECT name,account_type,currency_code,is_active,created_at FROM cash_accounts WHERE id=?",
            arrayOf(accountId)
        ).use { c ->
            require(c.moveToFirst()) { "الحساب المالي غير موجود عند تجهيز المزامنة" }
            arrayOf(c.getString(0), c.getString(1), c.getString(2), c.getInt(3).toString(), c.getString(4))
        }
        require(account[1] in setOf("CASH_BOX", "WALLET", "BANK", "OTHER")) {
            "نوع الحساب المالي غير مدعوم للمزامنة"
        }
        require(account[2] in setOf("USD", "SYP")) { "عملة الحساب المالي غير مدعومة للمزامنة" }
        val active = account[3] == "1"
        val dailyDrawerId = db.rawQuery(
            "SELECT account_id FROM system_cash_account_roles WHERE role_key='DAILY_DRAWER'", null
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        val supplierRolloverId = db.rawQuery(
            "SELECT account_id FROM system_cash_account_roles WHERE role_key='SUPPLIER_ROLLOVER'", null
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        val salesReceiptAllowed = active && when (account[1]) {
            "WALLET" -> true
            "CASH_BOX" -> account[2] == "USD" ||
                (account[2] == "SYP" && accountId == dailyDrawerId && accountId != supplierRolloverId)
            else -> false
        }
        val mappingActive = active && account[1] in setOf("CASH_BOX", "WALLET")
        val payload = JSONObject()
            .put("business_operation", "UPSERT_CASH_ACCOUNT")
            .put("cash_account_id", accountId)
            .put("name", account[0])
            .put("account_type", account[1])
            .put("currency_code", account[2])
            .put("is_active", active)
            .put("sales_receipt_allowed", salesReceiptAllowed)
            .put("created_at", account[4])
        val sequence = enqueueLocalOperation(
            db, actorUserId, UUID.randomUUID().toString(), "UPSERT_CASH_ACCOUNT",
            "CASH_ACCOUNT", accountId, "UPDATE", payload.toString()
        )
        if (state(db, "authority_device_id") == SyncDeviceIdentity.getOrCreate(context)) {
            val now = timestamp()
            db.rawQuery(
                "SELECT device_id FROM sync_devices WHERE device_role='SECONDARY' AND status='ACTIVE'",
                null
            ).use { c ->
                while (c.moveToNext()) {
                    val deviceId = c.getString(0)
                    db.insertWithOnConflict("sync_cash_account_mappings", null, ContentValues().apply {
                        put("device_id", deviceId)
                        put("remote_cash_account_id", accountId)
                        put("primary_cash_account_id", accountId)
                        put("is_active", if (mappingActive) 1 else 0)
                        put("mapped_by", actorUserId)
                        put("mapped_at", now)
                    }, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
        }
        return sequence
    }

    /**
     * Queues an authoritative catalog snapshot after pairing. Product events carry
     * semantic unit data and category IDs, never SQLite row IDs for local units.
     * The secondary resolves those values into its own database identifiers.
     */
    private fun enqueueCatalogSnapshot(db: SQLiteDatabase, actorUserId: String) {
        require(db.inTransaction()) { "يجب إنشاء لقطة الدليل ضمن معاملة الاقتران" }
        db.rawQuery(
            "SELECT id,name,created_at FROM categories ORDER BY id", null
        ).use { c ->
            while (c.moveToNext()) {
                val categoryId = c.getLong(0)
                val name = c.getString(1)
                val createdAt = c.getString(2)
                enqueueLocalOperation(
                    db, actorUserId, UUID.randomUUID().toString(), "CREATE_CATEGORY",
                    "CATEGORY", name.lowercase(java.util.Locale.ROOT), "CREATE",
                    JSONObject().put("name", name).put("primary_category_id", categoryId)
                        .put("created_at", createdAt).toString()
                )
            }
        }
        // Units are first-class reference data. Send them even when no product uses them.
        db.rawQuery("SELECT id,name,symbol,decimal_places FROM units ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val unitId = c.getLong(0)
                val unitName = c.getString(1)
                val unitSymbol = c.getString(2)
                val decimals = c.getInt(3)
                val payload = JSONObject()
                    .put("business_operation", "UPSERT_UNIT")
                    .put("primary_unit_id", unitId)
                    .put("unit_name", unitName)
                    .put("unit_symbol", unitSymbol)
                    .put("decimal_places", decimals)
                enqueueLocalOperation(
                    db, actorUserId, UUID.randomUUID().toString(), "UPSERT_UNIT",
                    "UNIT", unitId.toString(), "CREATE", payload.toString()
                )
            }
        }
        db.rawQuery(
            """SELECT p.id,p.name,p.barcode,p.sku,p.category_id,c.name,u.name,u.symbol,u.decimal_places,
                      p.purchase_cost_usd_cents,p.sale_price_usd_cents,p.track_inventory,p.is_active,
                      p.created_at,p.updated_at,
                      COALESCE((SELECT SUM(ib.quantity) FROM inventory_balances ib WHERE ib.product_id=p.id),0),
                      p.unit_id
               FROM products p JOIN units u ON u.id=p.unit_id
               LEFT JOIN categories c ON c.id=p.category_id
               ORDER BY p.id""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                val productId = c.getString(0)
                val payload = JSONObject()
                    .put("business_operation", "UPSERT_PRODUCT")
                    .put("product_id", productId)
                    .put("name", c.getString(1))
                    .put("barcode", if (c.isNull(2)) JSONObject.NULL else c.getString(2))
                    .put("sku", if (c.isNull(3)) JSONObject.NULL else c.getString(3))
                    .put("primary_category_id", if (c.isNull(4)) JSONObject.NULL else c.getLong(4))
                    .put("category_name", if (c.isNull(5)) JSONObject.NULL else c.getString(5))
                    .put("unit_name", c.getString(6))
                    .put("unit_symbol", c.getString(7))
                    .put("unit_decimal_places", c.getInt(8))
                    .put("primary_unit_id", c.getLong(16))
                    .put("purchase_cost_usd_cents", c.getLong(9))
                    .put("sale_price_usd_cents", c.getLong(10))
                    .put("tracks_inventory", c.getInt(11) == 1)
                    .put("is_active", c.getInt(12) == 1)
                    .put("created_at", c.getString(13))
                    .put("updated_at", c.getString(14))
                    .put("opening_quantity", if (c.getInt(11) == 1) maxOf(0.0, c.getDouble(15)) else 0.0)
                enqueueLocalOperation(
                    db, actorUserId, UUID.randomUUID().toString(), "UPSERT_PRODUCT",
                    "PRODUCT", productId, "CREATE", payload.toString()
                )
            }
        }
        db.rawQuery(
            """SELECT id,name,phone,address,notes,is_active,created_at,updated_at
               FROM customers ORDER BY id""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                val customerId = c.getString(0)
                val payload = JSONObject()
                    .put("business_operation", "UPSERT_CUSTOMER")
                    .put("customer_id", customerId)
                    .put("name", c.getString(1))
                    .put("phone", if (c.isNull(2)) JSONObject.NULL else c.getString(2))
                    .put("address", if (c.isNull(3)) JSONObject.NULL else c.getString(3))
                    .put("notes", if (c.isNull(4)) JSONObject.NULL else c.getString(4))
                    .put("is_active", c.getInt(5) == 1)
                    .put("created_at", c.getString(6))
                    .put("updated_at", c.getString(7))
                enqueueLocalOperation(
                    db, actorUserId, UUID.randomUUID().toString(), "UPSERT_CUSTOMER",
                    "CUSTOMER", customerId, "CREATE", payload.toString()
                )
            }
        }
        // Account IDs remain stable across devices; only selection metadata is mirrored.
        db.rawQuery(
            "SELECT id FROM cash_accounts ORDER BY id",
            null
        ).use { c ->
            while (c.moveToNext()) enqueueCashAccountReference(db, actorUserId, c.getString(0))
        }
        val latestRate = db.rawQuery(
            """SELECT id,currency_code,rate_syp_per_usd_milli,effective_at,created_at
               FROM exchange_rates WHERE currency_code='USD'
               ORDER BY effective_at DESC,created_at DESC LIMIT 1""".trimIndent(), null
        ).use { c ->
            if (!c.moveToFirst()) null else JSONObject()
                .put("business_operation", "SET_EXCHANGE_RATE")
                .put("id", c.getString(0)).put("currency_code", c.getString(1))
                .put("rate_syp_per_usd_milli", c.getLong(2))
                .put("effective_at", c.getString(3)).put("created_at", c.getString(4))
        }
        if (latestRate != null) {
            enqueueLocalOperation(
                db, actorUserId, UUID.randomUUID().toString(), "SET_EXCHANGE_RATE",
                "EXCHANGE_RATE", latestRate.getString("id"), "CREATE", latestRate.toString()
            )
        }
    }

    fun enqueueInvoicePosting(
        db: SQLiteDatabase, actorUserId: String, operationId: String,
        invoiceType: String, invoiceId: String, cashAccountId: String? = null,
        occurredAt: String? = null, operatingDate: String? = null
    ): Long? {
        require(invoiceType == "SALE" || invoiceType == "PURCHASE")
        val table = if (invoiceType == "SALE") "sales_invoices" else "purchase_invoices"
        val itemTable = if (invoiceType == "SALE") "sales_items" else "purchase_items"
        val fk = if (invoiceType == "SALE") "sales_invoice_id" else "purchase_invoice_id"
        val header = db.rawQuery(
            """SELECT id,invoice_number,${if (invoiceType == "SALE") "customer_id,sale_type" else "supplier_id,'PURCHASE'"},
                      warehouse_id,invoice_date,status,subtotal_usd_cents,discount_usd_cents,
                      total_usd_cents,exchange_rate_syp_per_usd_milli,notes,created_by,
                      ${if (invoiceType == "SALE") "posted_at,posted_operating_date" else "updated_at,invoice_date"}
               FROM $table WHERE id=?""".trimIndent(), arrayOf(invoiceId)
        ).use { c ->
            require(c.moveToFirst()) { "الفاتورة غير موجودة عند تجهيز حدث المزامنة" }
            JSONObject().apply {
                put("id", c.getString(0)); put("invoice_number", c.getString(1))
                if (c.isNull(2)) put("party_id", JSONObject.NULL) else put("party_id", c.getString(2))
                put("sale_type", c.getString(3)); put("warehouse_id", c.getString(4))
                put("invoice_date", c.getString(5)); put("status", c.getString(6))
                put("subtotal_usd_cents", c.getLong(7)); put("discount_usd_cents", c.getLong(8))
                put("total_usd_cents", c.getLong(9)); put("exchange_rate_syp_per_usd_milli", c.getLong(10))
                if (c.isNull(11)) put("notes", JSONObject.NULL) else put("notes", c.getString(11))
                if (c.isNull(12)) put("created_by", JSONObject.NULL) else put("created_by", c.getString(12))
                put("posted_at", c.getString(13)); put("posted_operating_date", c.getString(14))
            }
        }
        val existingSecondaryPayload = if (state(db, "local_device_role") == "SECONDARY") {
            db.rawQuery(
                "SELECT payload_json FROM sync_operation_envelopes WHERE operation_id=?",
                arrayOf(operationId)
            ).use { c -> if (c.moveToFirst()) JSONObject(c.getString(0)) else null }
        } else null
        val eventAt = occurredAt
            ?: existingSecondaryPayload?.optString("posted_at")?.takeIf { it.isNotBlank() && it != "null" }
            ?: timestamp()
        val eventOperatingDate = operatingDate
            ?: existingSecondaryPayload?.optString("posted_operating_date")?.takeIf {
                it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))
            }
            ?: StoreTime.operatingDate(db, eventAt)
        header.put("posted_at", eventAt)
        header.put("posted_operating_date", eventOperatingDate)

        val items = org.json.JSONArray()
        db.rawQuery(
            """SELECT id,product_id,quantity,${if (invoiceType == "SALE") "unit_price_usd_cents,unit_cost_usd_cents" else "unit_cost_usd_cents,unit_cost_usd_cents"},line_total_usd_cents
               FROM $itemTable WHERE $fk=? ORDER BY id""".trimIndent(), arrayOf(invoiceId)
        ).use { c ->
            while (c.moveToNext()) items.put(JSONObject().apply {
                put("id", c.getString(0)); put("product_id", c.getString(1))
                put("quantity", c.getDouble(2)); put("unit_price_or_cost_usd_cents", c.getLong(3))
                put("unit_cost_usd_cents", c.getLong(4)); put("line_total_usd_cents", c.getLong(5))
            })
        }
        header.put("business_operation", "POST_${invoiceType}_INVOICE")
        header.put("items", items)
        header.put("cash_account_id", cashAccountId ?: JSONObject.NULL)
        if (invoiceType == "SALE" && cashAccountId != null) {
            val sourceAccount = db.rawQuery(
                "SELECT account_type,currency_code,is_active FROM cash_accounts WHERE id=?",
                arrayOf(cashAccountId)
            ).use { c ->
                require(c.moveToFirst() && c.getInt(2) == 1) { "حساب قبض البيع غير موجود أو موقوف" }
                c.getString(0) to c.getString(1)
            }
            header.put("cash_account_type", sourceAccount.first)
            header.put("cash_account_currency_code", sourceAccount.second)
            val receipts = org.json.JSONArray()
            db.rawQuery(
                """SELECT id,amount_minor,currency_code,exchange_rate_syp_per_usd_milli
                   FROM cash_transactions WHERE source_type='SALES_INVOICE' AND source_id=?
                     AND transaction_type='SALE_RECEIPT' ORDER BY created_at,id""".trimIndent(),
                arrayOf(invoiceId)
            ).use { c -> while (c.moveToNext()) receipts.put(JSONObject().apply {
                put("cash_transaction_id", c.getString(0)); put("amount_minor", c.getLong(1))
                put("currency_code", c.getString(2))
                if (c.isNull(3)) put("exchange_rate_syp_per_usd_milli", JSONObject.NULL)
                else put("exchange_rate_syp_per_usd_milli", c.getLong(3))
            }) }
            if (receipts.length() == 0) {
                // A secondary has not posted locally, so its receipt exists only as
                // a validated intent. The primary will create the real cash ledger row.
                val totalUsdCents = header.getLong("total_usd_cents")
                val rate = header.getLong("exchange_rate_syp_per_usd_milli")
                val currency = sourceAccount.second
                val amountMinor = when (currency) {
                    "USD" -> totalUsdCents
                    "SYP" -> CurrencyConverter.usdCentsToSyp(totalUsdCents, rate)
                    else -> throw IllegalArgumentException("عملة حساب القبض غير مدعومة")
                }
                receipts.put(JSONObject().apply {
                    put("cash_transaction_id", "pending-cash-$operationId")
                    put("amount_minor", amountMinor)
                    put("currency_code", currency)
                    put("exchange_rate_syp_per_usd_milli", rate)
                })
            }
            header.put("cash_receipts", receipts)
        }
        return if (state(db, "local_device_role") == "SECONDARY") {
            enqueueSecondaryCommand(
                db, actorUserId, operationId, "POST_${invoiceType}_INVOICE",
                if (invoiceType == "SALE") "SALES_INVOICE" else "PURCHASE_INVOICE",
                invoiceId, header.toString()
            )
        } else {
            enqueueLocalOperation(
                db, actorUserId, operationId, "POST_${invoiceType}_INVOICE",
                if (invoiceType == "SALE") "SALES_INVOICE" else "PURCHASE_INVOICE",
                invoiceId, "CREATE", header.toString()
            )
        }
    }


    /**
     * Creates a signed, end-to-end encrypted application ACK from a durable APPLIED
     * inbox row. Relay delivery is deliberately not consulted here.
     */
    fun createEncryptedApplicationAck(recipientDeviceId: String, operationId: String): String {
        require(operationId.isNotBlank() && runCatching { UUID.fromString(recipientDeviceId) }.isSuccess) {
            "معرّف الجهاز أو العملية غير صالح"
        }
        val db = database.readableDatabase
        val localId = SyncDeviceIdentity.getOrCreate(context)
        val role = state(db, "local_device_role")
        val authorityId = state(db, "authority_device_id")
        require(
            (role == "PRIMARY" && authorityId == localId) ||
                (role == "SECONDARY" && authorityId == recipientDeviceId)
        ) { "إقرار التطبيق يصدر فقط بين الجهاز الأساسي والجهاز الثانوي المعتمد" }
        val peerActive = db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND status='ACTIVE' AND device_role=?",
            arrayOf(recipientDeviceId, if (role == "PRIMARY") "SECONDARY" else "PRIMARY")
        ).use { it.moveToFirst() }
        require(peerActive) { "الجهاز المستلم غير مسجل أو موقوف" }
        val row = db.rawQuery(
            """SELECT i.sequence_number,i.payload_sha256,i.status,i.origin_device_id,
                      (SELECT r.result_reference FROM operation_registry r
                       WHERE r.operation_id=i.operation_id ORDER BY r.created_at DESC LIMIT 1)
               FROM sync_inbox i WHERE i.operation_id=?""".trimIndent(),
            arrayOf(operationId)
        ).use { cursor ->
            require(cursor.moveToFirst()) { "عملية الإقرار غير موجودة في صندوق الوارد" }
            arrayOf(
                cursor.getLong(0).toString(), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), if (cursor.isNull(4)) "" else cursor.getString(4)
            )
        }
        require(row[2] == "APPLIED" && row[3] == recipientDeviceId) {
            "لا يمكن إصدار إقرار تطبيق قبل نجاح المعالجة على الجهاز المستلم"
        }
        val storeId = state(db, "store_id") ?: error("معرّف المتجر غير مهيأ")
        val acknowledgedAt = timestamp()
        val ackPayload = JSONObject()
            .put("ack_version", 1)
            .put("ack_status", "APPLIED")
            .put("store_id", storeId)
            .put("device_id", recipientDeviceId)
            .put("operation_id", operationId)
            .put("sequence_number", row[0].toLong())
            .put("payload_sha256", row[1])
            .put("result_reference", if (row[4].isBlank()) JSONObject.NULL else row[4])
            .put("acknowledged_at", acknowledgedAt)
        val secretOwner = if (role == "PRIMARY") recipientDeviceId else localId
        val secret = credentialVault.load(secretOwner)
            ?: throw IllegalArgumentException("بيانات اعتماد الجهاز المستلم غير متوفرة")
        try {
            val peerContext = SyncEnvelopeCipher.PeerContext(
                storeId, localId, recipientDeviceId, operationId
            )
            val encrypted = SyncEnvelopeCipher.encrypt(
                ackPayload.toString().toByteArray(Charsets.UTF_8),
                javax.crypto.spec.SecretKeySpec(secret, "AES"),
                peerContext
            )
            val frame = JSONObject()
                .put("frame_version", 1)
                .put("frame_type", "APPLICATION_ACK")
                .put("store_id", storeId)
                .put("sender_device_id", localId)
                .put("recipient_device_id", recipientDeviceId)
                .put("operation_id", operationId)
                .put("ciphertext", Base64.encodeToString(
                    encrypted, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
                ))
            val identity = SyncDeviceAuthenticator.getOrCreate(context, localId)
            val signingContext = SyncDeviceAuthenticator.SigningContext(
                storeId, localId, recipientDeviceId, operationId
            )
            val signature = SyncDeviceAuthenticator.sign(
                identity.private, signingContext, frame.toString().toByteArray(Charsets.UTF_8)
            )
            frame.put("signature_algorithm", "ECDSA_P256_SHA256")
                .put("sender_key_fingerprint", SyncDeviceAuthenticator.fingerprint(identity.public.encoded))
                .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
            return frame.toString()
        } finally {
            secret.fill(0)
        }
    }

    /**
     * Accepts an APPLIED ACK only from the paired peer and only for a local
     * operation whose exact sequence/hash match. Primary-origin operations are
     * acknowledged per secondary; one secondary cannot complete another's queue.
     */
    fun acceptEncryptedApplicationAck(frameJson: String): SyncApplicationAckDisposition {
        require(frameJson.length in 2..700_000) { "حجم إقرار المزامنة غير صالح" }
        val frame = JSONObject(frameJson)
        require(frame.getInt("frame_version") == 1 &&
            frame.getString("frame_type") == "APPLICATION_ACK") {
            "نوع أو إصدار إقرار المزامنة غير مدعوم"
        }
        val storeId = frame.getString("store_id")
        val senderId = frame.getString("sender_device_id")
        val recipientId = frame.getString("recipient_device_id")
        val operationId = frame.getString("operation_id")
        val localId = SyncDeviceIdentity.getOrCreate(context)
        val db = database.writableDatabase
        val role = state(db, "local_device_role")
        val authorityId = state(db, "authority_device_id")
        require(storeId == state(db, "store_id") && recipientId == localId &&
            ((role == "SECONDARY" && senderId == authorityId) ||
                (role == "PRIMARY" && authorityId == localId && senderId != localId))) {
            "هوية مرسل أو مستلم إقرار التطبيق لا تطابق إعدادات الجهاز"
        }
        val activePeer = db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND status='ACTIVE' AND device_role=?",
            arrayOf(senderId, if (role == "PRIMARY") "SECONDARY" else "PRIMARY")
        ).use { it.moveToFirst() }
        require(activePeer) { "الجهاز المقر غير مسجل أو موقوف" }
        verifyPeerFrameSignature(frame, storeId, senderId, recipientId, operationId)
        val secretOwner = if (role == "SECONDARY") localId else senderId
        val secret = credentialVault.load(secretOwner)
            ?: throw IllegalArgumentException("بيانات اعتماد الجهاز المقابل غير متوفرة")
        try {
            val peerContext = SyncEnvelopeCipher.PeerContext(storeId, senderId, recipientId, operationId)
            val ciphertext = Base64.decode(
                frame.getString("ciphertext"), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val plaintext = SyncEnvelopeCipher.decrypt(
                ciphertext, javax.crypto.spec.SecretKeySpec(secret, "AES"), peerContext
            ) ?: throw IllegalArgumentException("فشل التحقق من تشفير إقرار التطبيق")
            val ack = JSONObject(plaintext.toString(Charsets.UTF_8))
            require(ack.getInt("ack_version") == 1 && ack.getString("ack_status") == "APPLIED" &&
                ack.getString("store_id") == storeId && ack.getString("device_id") == localId &&
                ack.getString("operation_id") == operationId) {
                "محتوى إقرار التطبيق غير صالح أو لا يخص هذا الجهاز"
            }
            val sequence = ack.getLong("sequence_number")
            val payloadHash = ack.getString("payload_sha256")
            require(sequence > 0L && payloadHash.matches(Regex("[0-9a-f]{64}")) &&
                ack.getString("acknowledged_at").matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*"))) {
                "بيانات إقرار التطبيق غير مكتملة"
            }
            db.beginTransaction()
            try {
                val local = db.rawQuery(
                    """SELECT e.sequence_number,e.payload_sha256,e.origin_device_id,q.status,
                              e.entity_type,e.operation_type,e.entity_id,e.payload_json
                       FROM sync_operation_envelopes e JOIN sync_queue q ON q.operation_id=e.operation_id
                       WHERE e.operation_id=?""".trimIndent(), arrayOf(operationId)
                ).use { cursor ->
                    require(cursor.moveToFirst()) { "لا يوجد أمر محلي مطابق لإقرار التطبيق" }
                    arrayOf(cursor.getLong(0).toString(), cursor.getString(1),
                        cursor.getString(2), cursor.getString(3), cursor.getString(4),
                        cursor.getString(5), cursor.getString(6), cursor.getString(7))
                }
                require(local[0].toLong() == sequence && local[1] == payloadHash &&
                    local[2] == localId) {
                    "الإقرار لا يطابق تسلسل الأمر أو بصمة بياناته المحلية"
                }
                require(role != "PRIMARY" || local[4] in setOf(
                    "CATEGORY", "UNIT", "PRODUCT", "PRODUCT_OPENING_STOCK",
                    "CUSTOMER", "CASH_ACCOUNT", "EXCHANGE_RATE", "STORE_SETTINGS",
                    "SERVICE_TRANSFER_RECEIVABLE"
                )) {
                    "لا يجوز قبول إقرار من جهاز ثانوي لعملية إدارية أو مالية غير موزعة عليه"
                }
                require(local[3] in setOf("PENDING", "FAILED", "IN_PROGRESS", "SYNCED")) {
                    "حالة الأمر المحلي لا تسمح بقبول الإقرار"
                }
                val prior = db.rawQuery(
                    "SELECT sequence_number,status FROM sync_acknowledgements WHERE operation_id=? AND device_id=?",
                    arrayOf(operationId, senderId)
                ).use { cursor ->
                    if (cursor.moveToFirst()) arrayOf(cursor.getLong(0).toString(), cursor.getString(1))
                    else null
                }
                if (prior != null) {
                    require(prior[0].toLong() == sequence && prior[1] == "APPLIED") {
                        "تعارض مع إقرار محفوظ سابقاً"
                    }
                } else {
                    db.insertOrThrow("sync_acknowledgements", null, ContentValues().apply {
                        put("operation_id", operationId)
                        put("device_id", senderId)
                        put("sequence_number", sequence)
                        put("status", "APPLIED")
                        put("acknowledged_at", ack.getString("acknowledged_at"))
                    })
                }
                if (role == "SECONDARY") {
                    if (local[3] != "SYNCED") {
                        val changed = db.update("sync_queue", ContentValues().apply {
                            put("status", "SYNCED")
                            putNull("last_error")
                            put("updated_at", timestamp())
                        }, "operation_id=? AND status IN ('PENDING','FAILED','IN_PROGRESS')",
                            arrayOf(operationId))
                        require(changed == 1) { "تعذر تثبيت إقرار التطبيق في صندوق الإرسال" }
                    }
                    // A signed application ACK for a credit-sale posting is the
                    // authoritative confirmation that the primary accepted the invoice.
                    // Reflect only its workflow status locally; stock, debt and cash
                    // ledgers remain exclusively owned by the primary device.
                    if (local[4] == "SALES_INVOICE") {
                        val posted = JSONObject(local[7])
                        // Cash sales are also POST_SALE_INVOICE commands, but their
                        // workflow is already represented by the queued ACK. Only
                        // credit sales need the local invoice status reconciliation.
                        if (posted.optString("business_operation") == "POST_SALE_INVOICE" &&
                            posted.optString("sale_type") == "CREDIT") {
                            require(posted.optString("id") == local[6]) {
                                "إقرار الفاتورة لا يطابق أمر بيع آجل محلياً"
                            }
                        val invoiceId = local[6]
                        val customerId = posted.optString("party_id").takeIf {
                            it.isNotBlank() && it != "null"
                        } ?: throw IllegalArgumentException("فاتورة الدين المؤكدة لا تحدد زبوناً")
                        val invoice = db.rawQuery(
                            """SELECT status,customer_id,total_usd_cents,exchange_rate_syp_per_usd_milli
                               FROM sales_invoices WHERE id=?""".trimIndent(), arrayOf(invoiceId)
                        ).use { c ->
                            require(c.moveToFirst()) { "فاتورة الدين المحلية غير موجودة عند استلام الإقرار" }
                            arrayOf(c.getString(0), c.getString(1),
                                c.getLong(2).toString(), c.getLong(3).toString())
                        }
                        require(invoice[1] == customerId &&
                            invoice[2].toLong() == posted.getLong("total_usd_cents") &&
                            invoice[3].toLong() == posted.getLong("exchange_rate_syp_per_usd_milli") &&
                            invoice[0] in setOf("DRAFT", "POSTED", "PARTIALLY_PAID", "PAID")) {
                            "الفاتورة المحلية لا تطابق الفاتورة الآجلة التي اعتمدها الأساسي"
                        }
                        if (invoice[0] == "DRAFT") {
                            val changed = db.update("sales_invoices", ContentValues().apply {
                                put("status", "POSTED")
                                put("posted_at", posted.getString("posted_at"))
                                put("posted_operating_date", posted.getString("posted_operating_date"))
                                put("updated_at", timestamp())
                            }, "id=? AND status='DRAFT'", arrayOf(invoiceId))
                            require(changed == 1) { "تعذر تثبيت اعتماد فاتورة الدين على الجهاز الثانوي" }
                        }
                        }
                    }
                } else {
                    val awaiting = db.rawQuery(
                        """SELECT COUNT(*) FROM sync_devices d
                           WHERE d.device_role='SECONDARY' AND d.status='ACTIVE'
                             AND NOT EXISTS (
                               SELECT 1 FROM sync_acknowledgements a
                               WHERE a.operation_id=? AND a.device_id=d.device_id AND a.status='APPLIED'
                             )""".trimIndent(), arrayOf(operationId)
                    ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
                    if (awaiting == 0 && local[3] != "SYNCED") {
                        val changed = db.update("sync_queue", ContentValues().apply {
                            put("status", "SYNCED")
                            putNull("last_error")
                            put("updated_at", timestamp())
                        }, "operation_id=? AND status IN ('PENDING','FAILED','IN_PROGRESS')",
                            arrayOf(operationId))
                        require(changed == 1) { "تعذر تثبيت اكتمال توزيع العملية على الأجهزة" }
                    }
                }
                db.setTransactionSuccessful()
                return if (prior == null) SyncApplicationAckDisposition.APPLIED
                    else SyncApplicationAckDisposition.DUPLICATE
            } finally {
                db.endTransaction()
            }
        } finally {
            secret.fill(0)
        }
    }

    fun localDeviceRole(): String? = state(database.readableDatabase, "local_device_role")

    fun localDeviceId(): String = SyncDeviceIdentity.getOrCreate(context)

    fun authorityDeviceId(): String? = state(database.readableDatabase, "authority_device_id")

    fun activeSecondaryDeviceIds(): List<String> {
        require(localDeviceRole() == "PRIMARY" && authorityDeviceId() == localDeviceId()) {
            "قائمة الأجهزة الثانوية متاحة على الجهاز الأساسي فقط"
        }
        val ids = mutableListOf<String>()
        database.readableDatabase.rawQuery(
            "SELECT device_id FROM sync_devices WHERE device_role='SECONDARY' AND status='ACTIVE' ORDER BY created_at",
            null
        ).use { cursor -> while (cursor.moveToNext()) ids.add(cursor.getString(0)) }
        return ids
    }

    fun pendingOutboxForDevice(deviceId: String, limit: Int = 100): List<SyncOutboxItem> {
        require(limit in 1..500 && runCatching { UUID.fromString(deviceId) }.isSuccess)
        val db = database.readableDatabase
        val role = state(db, "local_device_role")
        val localId = SyncDeviceIdentity.getOrCreate(context)
        val sql = if (role == "PRIMARY" && state(db, "authority_device_id") == localId) {
            require(db.rawQuery(
                "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
                arrayOf(deviceId)
            ).use { it.moveToFirst() }) { "الجهاز المستلم غير مسجل أو موقوف" }
            """SELECT e.operation_id,e.sequence_number,e.operation_type,e.entity_type,e.entity_id,
                      e.payload_json,e.payload_sha256,e.created_at,q.retry_count
               FROM sync_operation_envelopes e JOIN sync_queue q ON q.operation_id=e.operation_id
               WHERE e.origin_device_id=?
                 AND e.entity_type IN ('CATEGORY','UNIT','PRODUCT','PRODUCT_OPENING_STOCK','CUSTOMER','CASH_ACCOUNT','EXCHANGE_RATE','STORE_SETTINGS','SERVICE_TRANSFER_RECEIVABLE')
                 AND NOT EXISTS (SELECT 1 FROM sync_acknowledgements a
                   WHERE a.operation_id=e.operation_id AND a.device_id=? AND a.status='APPLIED')
               ORDER BY e.sequence_number LIMIT ?""".trimIndent()
        } else {
            require(role == "SECONDARY" && deviceId == state(db, "authority_device_id")) {
                "الجهاز الثانوي يرسل إلى الجهاز الأساسي المعتمد فقط"
            }
            """SELECT e.operation_id,e.sequence_number,e.operation_type,e.entity_type,e.entity_id,
                      e.payload_json,e.payload_sha256,e.created_at,q.retry_count
               FROM sync_operation_envelopes e JOIN sync_queue q ON q.operation_id=e.operation_id
               WHERE e.origin_device_id=? AND q.status IN ('PENDING','FAILED')
               ORDER BY e.sequence_number LIMIT ?""".trimIndent()
        }
        val args = if (role == "PRIMARY") arrayOf(localId, deviceId, limit.toString())
            else arrayOf(localId, limit.toString())
        val result = mutableListOf<SyncOutboxItem>()
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) result.add(SyncOutboxItem(
                cursor.getString(0), cursor.getLong(1), cursor.getString(2), cursor.getString(3),
                cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7),
                cursor.getInt(8)
            ))
        }
        return result
    }

    fun incomingOperationStatus(operationId: String): String? =
        database.readableDatabase.rawQuery(
            "SELECT status FROM sync_inbox WHERE operation_id=?", arrayOf(operationId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun recordIncomingFromPrimary(encodedEnvelope: String): SyncInboxDisposition {
        val envelope = verifyIncomingFromPrimary(encodedEnvelope)
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val primaryId = state(db, "authority_device_id")
                ?: throw IllegalArgumentException("الجهاز الأساسي غير محدد")
            val active = db.rawQuery(
                "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='PRIMARY' AND status='ACTIVE'",
                arrayOf(primaryId)
            ).use { it.moveToFirst() }
            require(active && envelope.originDeviceId == primaryId) {
                "مصدر العملية ليس الجهاز الأساسي النشط"
            }
            val existing = db.rawQuery(
                """SELECT payload_sha256,origin_device_id,sequence_number,operation_type,
                          entity_type,entity_id,origin_created_at,payload_json
                   FROM sync_inbox WHERE operation_id=?""".trimIndent(),
                arrayOf(envelope.operationId)
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else arrayOf(
                    cursor.getString(0), cursor.getString(1), cursor.getLong(2).toString(),
                    cursor.getString(3), cursor.getString(4), cursor.getString(5),
                    cursor.getString(6), cursor.getString(7)
                )
            }
            if (existing != null) {
                val exactReplay = existing[0] == envelope.payloadSha256 &&
                    existing[1] == envelope.originDeviceId &&
                    existing[2].toLong() == envelope.sequenceNumber &&
                    existing[3] == envelope.operationType &&
                    existing[4] == envelope.entityType &&
                    existing[5] == envelope.entityId &&
                    existing[6] == envelope.createdAt
                if (exactReplay) {
                    db.setTransactionSuccessful()
                    return SyncInboxDisposition.DUPLICATE
                }
                db.insertOrThrow("sync_conflicts", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("operation_id", envelope.operationId)
                    put("entity_type", envelope.entityType); put("entity_id", envelope.entityId)
                    put("local_payload_json", existing[7]); put("remote_payload_json", envelope.payloadJson)
                    put("created_at", timestamp())
                })
                db.setTransactionSuccessful()
                return SyncInboxDisposition.CONFLICT
            }
            val last = db.rawQuery(
                "SELECT last_sequence FROM sync_cursors WHERE device_id=?",
                arrayOf(primaryId)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            require(envelope.sequenceNumber == last + 1L) {
                if (envelope.sequenceNumber <= last) "تم رفض إعادة تشغيل تسلسل قديم"
                else "فجوة في تسلسل أوامر الجهاز الأساسي؛ انتظر الأمر السابق"
            }
            val now = timestamp()
            db.insertOrThrow("sync_inbox", null, ContentValues().apply {
                put("operation_id", envelope.operationId); put("origin_device_id", primaryId)
                put("sequence_number", envelope.sequenceNumber); put("operation_type", envelope.operationType)
                put("entity_type", envelope.entityType); put("entity_id", envelope.entityId)
                put("payload_json", envelope.payloadJson); put("payload_sha256", envelope.payloadSha256)
                put("status", "RECEIVED"); put("received_at", now); put("origin_created_at", envelope.createdAt)
            })
            db.update("sync_cursors", ContentValues().apply {
                put("last_sequence", envelope.sequenceNumber); put("updated_at", now)
            }, "device_id=?", arrayOf(primaryId))
            db.setTransactionSuccessful()
            return SyncInboxDisposition.RECEIVED
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Returns APPLIED inbox operations whose application ACK has not recently
     * been forwarded. Relay forwarding is only a retry hint, never a business ACK.
     */
    fun appliedOperationsAwaitingAckForDevice(
        deviceId: String,
        limit: Int = 100,
        retryAfterMillis: Long = 60_000L
    ): List<String> {
        require(limit in 1..500 && retryAfterMillis in 0..86_400_000L &&
            runCatching { UUID.fromString(deviceId) }.isSuccess)
        val db = database.readableDatabase
        val role = state(db, "local_device_role")
        val localId = SyncDeviceIdentity.getOrCreate(context)
        val expectedPeerRole = when (role) {
            "PRIMARY" -> {
                require(state(db, "authority_device_id") == localId)
                "SECONDARY"
            }
            "SECONDARY" -> {
                require(state(db, "authority_device_id") == deviceId)
                "PRIMARY"
            }
            else -> throw IllegalArgumentException("دور الجهاز غير مهيأ")
        }
        require(db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role=? AND status='ACTIVE'",
            arrayOf(deviceId, expectedPeerRole)
        ).use { it.moveToFirst() }) { "الجهاز المقابل غير مسجل أو موقوف" }
        val retryBefore = timestamp(System.currentTimeMillis() - retryAfterMillis)
        val result = mutableListOf<String>()
        db.rawQuery(
            """SELECT i.operation_id FROM sync_inbox i
               JOIN sync_devices d ON d.device_id=i.origin_device_id
               LEFT JOIN sync_ack_delivery a
                 ON a.operation_id=i.operation_id AND a.device_id=i.origin_device_id
               WHERE i.origin_device_id=? AND i.status='APPLIED'
                 AND d.device_role=? AND d.status='ACTIVE'
                 AND (a.last_forwarded_at IS NULL OR a.last_forwarded_at<=?)
               ORDER BY CASE WHEN a.last_forwarded_at IS NULL THEN 0 ELSE 1 END,
                        a.last_forwarded_at,i.sequence_number LIMIT ?""".trimIndent(),
            arrayOf(deviceId, expectedPeerRole, retryBefore, limit.toString())
        ).use { cursor -> while (cursor.moveToNext()) result.add(cursor.getString(0)) }
        return result
    }

    /** Relay forwarding updates retry pacing only; it never changes business ACK state. */
    fun markApplicationAckForwarded(operationId: String): Boolean {
        require(operationId.isNotBlank())
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val peer = db.rawQuery(
                "SELECT origin_device_id,status FROM sync_inbox WHERE operation_id=?",
                arrayOf(operationId)
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(1) != "APPLIED") null
                else cursor.getString(0)
            } ?: return false
            val role = state(db, "local_device_role")
            val localId = SyncDeviceIdentity.getOrCreate(context)
            val expectedPeerRole = when (role) {
                "PRIMARY" -> {
                    require(state(db, "authority_device_id") == localId)
                    "SECONDARY"
                }
                "SECONDARY" -> {
                    require(state(db, "authority_device_id") == peer)
                    "PRIMARY"
                }
                else -> throw IllegalArgumentException("دور الجهاز غير مهيأ")
            }
            require(db.rawQuery(
                "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role=? AND status='ACTIVE'",
                arrayOf(peer, expectedPeerRole)
            ).use { it.moveToFirst() }) { "الجهاز المقابل غير مسجل أو موقوف" }

            val now = timestamp()
            val attempts = db.rawQuery(
                "SELECT attempt_count FROM sync_ack_delivery WHERE operation_id=? AND device_id=?",
                arrayOf(operationId, peer)
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }
            val values = ContentValues().apply {
                put("last_forwarded_at", now)
                put("attempt_count", (attempts ?: 0) + 1)
                put("updated_at", now)
            }
            if (attempts == null) {
                values.put("operation_id", operationId)
                values.put("device_id", peer)
                db.insertOrThrow("sync_ack_delivery", null, values)
            } else {
                require(db.update(
                    "sync_ack_delivery", values, "operation_id=? AND device_id=?",
                    arrayOf(operationId, peer)
                ) == 1) { "تعذر حفظ حالة تمرير إقرار التطبيق" }
            }
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    fun storeId(): String = state(database.readableDatabase, "store_id")
        ?: throw IllegalStateException("معرّف المتجر غير مهيأ")

    fun storeTimeZoneId(): String = StoreTime.zoneId(database.readableDatabase)

    fun pendingOutbox(limit: Int = 100): List<SyncOutboxItem> {
        require(limit in 1..500)
        val items = mutableListOf<SyncOutboxItem>()
        database.readableDatabase.rawQuery(
            """SELECT e.operation_id,e.sequence_number,e.operation_type,e.entity_type,e.entity_id,
                      e.payload_json,e.payload_sha256,e.created_at,q.retry_count
               FROM sync_operation_envelopes e JOIN sync_queue q ON q.operation_id=e.operation_id
               WHERE q.status IN ('PENDING','FAILED') ORDER BY e.sequence_number LIMIT ?""".trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) items.add(SyncOutboxItem(
                c.getString(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4),
                c.getString(5), c.getString(6), c.getString(7), c.getInt(8)
            ))
        }
        return items
    }

    fun hasConfiguredPrimary(): Boolean =
        state(database.readableDatabase, "local_device_role") == "PRIMARY" &&
            state(database.readableDatabase, "authority_device_id") == SyncDeviceIdentity.getOrCreate(context)

    private fun requirePrimaryManager(db: SQLiteDatabase, actorUserId: String) {
        PermissionPolicy.require(db, actorUserId, PermissionPolicy.SYNC_MANAGE)
        check(state(db, "authority_device_id") == SyncDeviceIdentity.getOrCreate(context)) {
            "يجب إعداد هذا الهاتف كالجهاز الأساسي أولاً"
        }
    }

    private fun state(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key=?", arrayOf(key))
            .use { if (it.moveToFirst()) it.getString(0) else null }

    private fun putState(db: SQLiteDatabase, key: String, value: String, now: String) {
        db.insertWithOnConflict("sync_protocol_state", null, ContentValues().apply {
            put("state_key", key); put("state_value", value); put("updated_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun audit(db: SQLiteDatabase, actor: String, action: String, entityId: String,
                      before: String?, after: String?, now: String) {
        db.insertOrThrow("audit_log", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString()); put("user_id", actor); put("action", action)
            put("entity_type", "SYNC_DEVICE"); put("entity_id", entityId)
            if (before != null) put("before_json", before)
            if (after != null) put("after_json", after)
            put("device_id", SyncDeviceIdentity.getOrCreate(context)); put("created_at", now)
        })
    }

    private fun randomToken(): String = ByteArray(32).also(random::nextBytes)
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun timestamp(atMillis: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(atMillis))

    companion object {
        // Only reference data required by the sales-only secondary is replicated from primary.
        private val PRIMARY_TO_SECONDARY_REFERENCE_ENTITIES = setOf(
            "CATEGORY", "UNIT", "PRODUCT", "PRODUCT_OPENING_STOCK", "CUSTOMER", "CASH_ACCOUNT", "EXCHANGE_RATE", "STORE_SETTINGS", "SERVICE_TRANSFER_RECEIVABLE"
        )
        private const val INVITATION_TTL_MS = 10 * 60 * 1000L
        private const val MAX_PAYLOAD_CHARS = 128 * 1024
    }
}
