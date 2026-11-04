package com.hazem.dailystore.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.PrivateKey
import java.util.UUID

/** Canonical root-signed device certificate and relay challenge protocol. */
object SyncDeviceCertificate {
    private const val VERSION = 1
    private const val DOMAIN = "THE_DAILY_SHOP_RELAY_AUTH_V1"
    private const val CERTIFICATE_TTL_MS = 365L * 24 * 60 * 60 * 1000

    fun issue(
        storeId: String,
        issuerDeviceId: String,
        deviceId: String,
        devicePublicKeyBase64: String,
        rootPrivateKey: PrivateKey,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        requireUuid(storeId); requireUuid(issuerDeviceId); requireUuid(deviceId)
        val key = Base64.decode(devicePublicKeyBase64, Base64.DEFAULT)
        require(key.size in 64..256)
        val serial = UUID.randomUUID().toString()
        val payload = certificatePayload(
            storeId, issuerDeviceId, deviceId, devicePublicKeyBase64, serial, nowMillis, nowMillis + CERTIFICATE_TTL_MS
        )
        val signature = SyncDeviceAuthenticator.signBytes(rootPrivateKey, payload.toByteArray(Charsets.UTF_8))
        return JSONObject(payload)
            .put("signature", Base64.encodeToString(signature, Base64.NO_WRAP))
            .toString()
    }

    fun verify(
        certificateJson: String,
        expectedStoreId: String,
        expectedDeviceId: String,
        expectedIssuerDeviceId: String,
        rootPublicKeyBase64: String,
        expectedDevicePublicKeyBase64: String? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = runCatching {
        val cert = JSONObject(certificateJson)
        val storeId = cert.getString("store_id")
        val deviceId = cert.getString("device_id")
        val issuerDeviceId = cert.getString("issuer_device_id")
        val publicKey = cert.getString("public_key")
        val serial = cert.getString("serial")
        val issuedAt = cert.getLong("issued_at")
        val expiresAt = cert.getLong("expires_at")
        val signature = Base64.decode(cert.getString("signature"), Base64.DEFAULT)
        if (cert.getInt("version") != VERSION || storeId != expectedStoreId ||
            deviceId != expectedDeviceId || issuerDeviceId != expectedIssuerDeviceId ||
            expectedDevicePublicKeyBase64?.let { it != publicKey } == true ||
            !isUuid(serial) || issuedAt > nowMillis + 60_000 || expiresAt <= nowMillis ||
            expiresAt <= issuedAt || expiresAt - issuedAt > CERTIFICATE_TTL_MS) return false
        val payload = certificatePayload(storeId, issuerDeviceId, deviceId, publicKey, serial, issuedAt, expiresAt)
        SyncDeviceAuthenticator.verifyBytes(
            Base64.decode(rootPublicKeyBase64, Base64.DEFAULT),
            payload.toByteArray(Charsets.UTF_8), signature
        )
    }.getOrDefault(false)

    fun serial(certificateJson: String): String = JSONObject(certificateJson).getString("serial")

    fun challengeSignature(
        storeId: String,
        deviceId: String,
        serial: String,
        challenge: String,
        expiresAt: Long,
        privateKey: PrivateKey
    ): String {
        requireUuid(storeId); requireUuid(deviceId)
        require(serial == "ROOT" || isUuid(serial))
        require(challenge.matches(Regex("[A-Za-z0-9_-]{20,128}")))
        require(expiresAt > 0L)
        val proof = JSONArray().put(DOMAIN).put(storeId).put(deviceId)
            .put(serial).put(challenge).put(expiresAt).toString()
        val signature = SyncDeviceAuthenticator.signBytes(privateKey, proof.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    fun signedRevocation(
        storeId: String,
        deviceId: String,
        serial: String,
        rootPrivateKey: PrivateKey,
        revokedAt: Long = System.currentTimeMillis()
    ): String {
        requireUuid(storeId); requireUuid(deviceId); requireUuid(serial)
        val payload = JSONObject()
            .put("version", VERSION)
            .put("store_id", storeId)
            .put("device_id", deviceId)
            .put("serial", serial)
            .put("revoked_at", revokedAt)
        val signature = SyncDeviceAuthenticator.signBytes(
            rootPrivateKey, payload.toString().toByteArray(Charsets.UTF_8)
        )
        return payload.put("signature", Base64.encodeToString(signature, Base64.NO_WRAP)).toString()
    }

    private fun certificatePayload(
        storeId: String, deviceId: String, publicKey: String,
        serial: String, issuedAt: Long, expiresAt: Long
    ): String = JSONObject()
        .put("version", VERSION)
        .put("store_id", storeId)
        .put("device_id", deviceId)
        .put("public_key", publicKey)
        .put("serial", serial)
        .put("issued_at", issuedAt)
        .put("expires_at", expiresAt)
        .toString()

    private fun requireUuid(value: String) {
        require(isUuid(value)) { "معرّف شهادة الجهاز غير صالح" }
    }
    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value) }.isSuccess
}
