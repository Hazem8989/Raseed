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
data class SyncEnrollment(val deviceId: String, val credential: String, val storeId: String, val primaryDeviceId: String, val storeTimeZoneId: String, val primaryPublicKeyBase64: String? = null)
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
            val deviceId = SyncDeviceIdentity.getOrCreate(context)
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

    /** Called by the primary after a future authenticated pairing handshake. */
    fun enrollSecondaryDevice(
        actorUserId: String, invitationToken: String, remoteDeviceId: String, displayName: String,
        remotePublicKeyBase64: String? = null
    ): SyncEnrollment {
        val cleanName = displayName.trim()
        require(cleanName.isNotEmpty() && cleanName.length <= 80) { "اسم الجهاز غير صالح" }
        require(runCatching { UUID.fromString(remoteDeviceId) }.isSuccess) { "معرّف الجهاز غير صالح" }
        require(remoteDeviceId != SyncDeviceIdentity.getOrCreate(context)) { "لا يمكن تسجيل الجهاز الأساسي كجهاز ثانوي" }
        require(invitationToken.length in 32..128) { "رمز الاقتران غير صالح" }
        val db = database.writableDatabase
        val secretBytes = ByteArray(32)
        var credentialSaved = false
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
            if (remotePublicKeyBase64 != null) {
                storeDevicePublicKey(db, remoteDeviceId, remotePublicKeyBase64, now)
            }
            val localIdentity = SyncDeviceAuthenticator.getOrCreate(context, SyncDeviceIdentity.getOrCreate(context))
            storeDevicePublicKey(
                db, SyncDeviceIdentity.getOrCreate(context),
                Base64.encodeToString(localIdentity.public.encoded, Base64.NO_WRAP), now
            )
            db.insertWithOnConflict("sync_cursors", null, ContentValues().apply {
                put("device_id", remoteDeviceId); put("last_sequence", 0L); put("updated_at", now)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            credentialVault.save(remoteDeviceId, secretBytes)
            credentialSaved = true
            audit(db, actorUserId, "SYNC_DEVICE_ENROLLED", remoteDeviceId, null,
                JSONObject().put("display_name", cleanName).put("device_role", "SECONDARY").toString(), now)
            db.setTransactionSuccessful()
            return SyncEnrollment(
                remoteDeviceId, credential,
                state(db, "store_id") ?: error("معرّف المتجر غير مهيأ"),
                SyncDeviceIdentity.getOrCreate(context),
                StoreTime.zoneId(db),
                Base64.encodeToString(localIdentity.public.encoded, Base64.NO_WRAP)
            )
        } catch (error: Exception) {
            if (credentialSaved) credentialVault.remove(remoteDeviceId)
            throw error
        } finally {
            db.endTransaction()
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
        primaryPublicKeyBase64: String? = null
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
            if (primaryPublicKeyBase64 != null) {
                storeDevicePublicKey(db, primaryDeviceId, primaryPublicKeyBase64, now)
            }
            val localIdentity = SyncDeviceAuthenticator.getOrCreate(context, localDeviceId)
            storeDevicePublicKey(
                db, localDeviceId,
                Base64.encodeToString(localIdentity.public.encoded, Base64.NO_WRAP), now
            )
            putState(db, "authority_device_id", primaryDeviceId, now)
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

    fun isConfiguredSecondary(): Boolean =
        state(database.readableDatabase, "local_device_role") == "SECONDARY" &&
            !state(database.readableDatabase, "authority_device_id").isNullOrBlank()

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
            "SELECT sequence_number,payload_sha256,entity_type,entity_id FROM sync_operation_envelopes WHERE operation_id=?",
            arrayOf(operationId)
        ).use { c ->
            if (!c.moveToFirst()) null else arrayOf(
                c.getLong(0).toString(), c.getString(1), c.getString(2), c.getString(3)
            )
        }
        if (prior != null) {
            require(prior[1] == hash && prior[2] == entityType && prior[3] == entityId) {
                "أعيد استخدام معرّف الأمر ببيانات مختلفة"
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
            "POST_SALES_INVOICE" -> PermissionPolicy.SALES_CHECKOUT
            "POST_PURCHASE_INVOICE" -> PermissionPolicy.PURCHASES_MANAGE
            "COLLECT_CUSTOMER_PAYMENT" -> PermissionPolicy.CUSTOMERS_COLLECT
            "POST_SUPPLIER_PAYMENT" -> PermissionPolicy.SUPPLIER_PAYMENTS_MANAGE
            "POST_OPERATING_EXPENSE" -> PermissionPolicy.EXPENSES_MANAGE
            "EXECUTE_SERVICE_TRANSFER" -> PermissionPolicy.SERVICE_TRANSFERS_EXECUTE
            "SET_SUPPLIER_ROLLOVER" -> PermissionPolicy.DAILY_CASH_MANAGE
            "POST_SALES_RETURN", "POST_PURCHASE_RETURN" -> PermissionPolicy.RETURNS_PROCESS
            "POST_INVENTORY_ADJUSTMENT", "ADJUST_INVENTORY" -> PermissionPolicy.INVENTORY_ADJUST
            else -> throw IllegalArgumentException("نوع العملية غير مدعوم للاعتماد عن بعد")
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
            val payload = JSONObject(envelope.payloadJson)
            val businessOperation = payload.optString("business_operation")
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
                    put("id", UUID.randomUUID().toString()); put("operation_id", envelope.operationId)
                    put("entity_type", envelope.entityType); put("entity_id", envelope.entityId)
                    put("local_payload_json", "{}"); put("remote_payload_json", envelope.payloadJson)
                    put("created_at", timestamp())
                })
                db.setTransactionSuccessful()
                return SyncInboxDisposition.CONFLICT
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

    fun revokeDevice(actorUserId: String, remoteDeviceId: String) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            requirePrimaryManager(db, actorUserId)
            require(remoteDeviceId != SyncDeviceIdentity.getOrCreate(context)) {
                "لا يمكن إلغاء الجهاز الأساسي من هذه الشاشة"
            }
            val before = db.rawQuery(
                "SELECT display_name,status FROM sync_devices WHERE device_id=? AND device_role='SECONDARY'",
                arrayOf(remoteDeviceId)
            ).use {
                require(it.moveToFirst()) { "الجهاز غير موجود" }
                JSONObject().put("display_name", it.getString(0)).put("status", it.getString(1)).toString()
            }
            val now = timestamp()
            db.update("sync_devices", ContentValues().apply {
                put("status", "REVOKED"); put("revoked_at", now)
            }, "device_id=?", arrayOf(remoteDeviceId))
            db.update("sync_device_public_keys", ContentValues().apply {
                put("status", "REVOKED"); put("revoked_at", now)
            }, "device_id=?", arrayOf(remoteDeviceId))
            audit(db, actorUserId, "SYNC_DEVICE_REVOKED", remoteDeviceId, before,
                JSONObject().put("status", "REVOKED").toString(), now)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        credentialVault.remove(remoteDeviceId)
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
        require(state(db, "local_device_role") == "PRIMARY" &&
            state(db, "authority_device_id") == localId) {
            "إقرار التطبيق يصدر من الجهاز الأساسي المعتمد فقط"
        }
        val peerActive = db.rawQuery(
            "SELECT 1 FROM sync_devices WHERE device_id=? AND device_role='SECONDARY' AND status='ACTIVE'",
            arrayOf(recipientDeviceId)
        ).use { it.moveToFirst() }
        require(peerActive) { "الجهاز المستلم غير مسجل أو موقوف" }
        val row = db.rawQuery(
            """SELECT i.sequence_number,i.payload_sha256,i.status,i.origin_device_id,
                      (SELECT r.result_reference FROM operation_registry r
                       WHERE r.operation_id=i.operation_id ORDER BY r.created_at DESC LIMIT 1)
               FROM sync_inbox i WHERE i.operation_id=?""".trimIndent(),
            arrayOf(operationId)
        ).use { c ->
            require(c.moveToFirst()) { "عملية الإقرار غير موجودة في صندوق الوارد" }
            arrayOf(
                c.getLong(0).toString(), c.getString(1), c.getString(2), c.getString(3),
                if (c.isNull(4)) "" else c.getString(4)
            )
        }
        require(row[2] == "APPLIED" && row[3] == recipientDeviceId) {
            "لا يمكن إصدار إقرار تطبيق قبل نجاح المعالجة على الجهاز الأساسي"
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
        val secret = credentialVault.load(recipientDeviceId)
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
     * Accepts only a primary-signed APPLIED ACK matching the exact locally queued
     * operation, sequence and payload hash. The queue is marked SYNCED atomically
     * with the durable ACK receipt; forwarded/not_delivered relay events cannot call this.
     */
    fun acceptEncryptedApplicationAck(frameJson: String): SyncApplicationAckDisposition {
        val frame = JSONObject(frameJson)
        require(frame.getInt("frame_version") == 1) { "إصدار إقرار المزامنة غير مدعوم" }
        val storeId = frame.getString("store_id")
        val senderId = frame.getString("sender_device_id")
        val recipientId = frame.getString("recipient_device_id")
        val operationId = frame.getString("operation_id")
        val localId = SyncDeviceIdentity.getOrCreate(context)
        val db = database.writableDatabase
        require(state(db, "local_device_role") == "SECONDARY" &&
            senderId == state(db, "authority_device_id") && recipientId == localId &&
            storeId == state(db, "store_id")) {
            "هوية مرسل أو مستلم إقرار التطبيق لا تطابق إعدادات الجهاز"
        }
        verifyPeerFrameSignature(frame, storeId, senderId, recipientId, operationId)
        val secret = credentialVault.load(localId)
            ?: throw IllegalArgumentException("بيانات اعتماد الجهاز الثانوي غير متوفرة")
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
                    """SELECT e.sequence_number,e.payload_sha256,e.origin_device_id,q.status
                       FROM sync_operation_envelopes e JOIN sync_queue q ON q.operation_id=e.operation_id
                       WHERE e.operation_id=?""".trimIndent(), arrayOf(operationId)
                ).use { c ->
                    require(c.moveToFirst()) { "لا يوجد أمر محلي مطابق لإقرار التطبيق" }
                    arrayOf(c.getLong(0).toString(), c.getString(1), c.getString(2), c.getString(3))
                }
                require(local[0].toLong() == sequence && local[1] == payloadHash &&
                    local[2] == localId) {
                    "الإقرار لا يطابق تسلسل الأمر أو بصمة بياناته المحلية"
                }
                require(local[3] in setOf("PENDING", "FAILED", "IN_PROGRESS", "SYNCED")) {
                    "حالة الأمر المحلي لا تسمح بقبول الإقرار"
                }
                val prior = db.rawQuery(
                    "SELECT sequence_number,status FROM sync_acknowledgements WHERE operation_id=? AND device_id=?",
                    arrayOf(operationId, senderId)
                ).use { c ->
                    if (c.moveToFirst()) arrayOf(c.getLong(0).toString(), c.getString(1)) else null
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
                if (local[3] != "SYNCED") {
                    val changed = db.update("sync_queue", ContentValues().apply {
                        put("status", "SYNCED")
                        putNull("last_error")
                        put("updated_at", timestamp())
                    }, "operation_id=? AND status IN ('PENDING','FAILED','IN_PROGRESS')", arrayOf(operationId))
                    require(changed == 1) { "تعذر تثبيت إقرار التطبيق في صندوق الإرسال" }
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

    /** Lists durable APPLIED commands whose application ACK can safely be retransmitted. */
    fun appliedOperationsAwaitingAckForDevice(deviceId: String, limit: Int = 100): List<String> {
        require(limit in 1..500)
        val db = database.readableDatabase
        require(state(db, "local_device_role") == "PRIMARY" &&
            state(db, "authority_device_id") == SyncDeviceIdentity.getOrCreate(context)) {
            "قائمة إقرارات التطبيق متاحة على الجهاز الأساسي فقط"
        }
        val result = mutableListOf<String>()
        db.rawQuery(
            """SELECT i.operation_id FROM sync_inbox i
               JOIN sync_devices d ON d.device_id=i.origin_device_id
               WHERE i.origin_device_id=? AND i.status='APPLIED'
                 AND d.device_role='SECONDARY' AND d.status='ACTIVE'
               ORDER BY i.sequence_number LIMIT ?""".trimIndent(),
            arrayOf(deviceId, limit.toString())
        ).use { c -> while (c.moveToNext()) result.add(c.getString(0)) }
        return result
    }

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
        private const val INVITATION_TTL_MS = 10 * 60 * 1000L
        private const val MAX_PAYLOAD_CHARS = 128 * 1024
    }
}
