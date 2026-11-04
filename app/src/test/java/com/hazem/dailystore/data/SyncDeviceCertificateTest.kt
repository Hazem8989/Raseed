package com.hazem.dailystore.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class SyncDeviceCertificateTest {
    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    private fun b64(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)

    @Test fun certificateIsRootSignedBoundToStoreIssuerDeviceAndPublicKey() {
        val storeId = UUID.randomUUID().toString()
        val issuerId = UUID.randomUUID().toString()
        val deviceId = UUID.randomUUID().toString()
        val root = keyPair()
        val device = keyPair()
        val devicePublic = b64(device.public.encoded)
        val certificate = SyncDeviceCertificate.issue(storeId, issuerId, deviceId, devicePublic, root.private)

        assertTrue(SyncDeviceCertificate.verify(
            certificate, storeId, deviceId, issuerId, b64(root.public.encoded), devicePublic
        ))
        assertFalse(SyncDeviceCertificate.verify(
            certificate, UUID.randomUUID().toString(), deviceId, issuerId, b64(root.public.encoded), devicePublic
        ))
        assertFalse(SyncDeviceCertificate.verify(
            certificate, storeId, UUID.randomUUID().toString(), issuerId, b64(root.public.encoded), devicePublic
        ))
        assertFalse(SyncDeviceCertificate.verify(
            certificate, storeId, deviceId, UUID.randomUUID().toString(), b64(root.public.encoded), devicePublic
        ))
        assertFalse(SyncDeviceCertificate.verify(
            certificate, storeId, deviceId, issuerId, b64(keyPair().public.encoded), devicePublic
        ))
        assertFalse(SyncDeviceCertificate.verify(
            certificate, storeId, deviceId, issuerId, b64(root.public.encoded), b64(keyPair().public.encoded)
        ))
        val altered = JSONObject(certificate).put("expires_at", System.currentTimeMillis() + 60_000).toString()
        assertFalse(SyncDeviceCertificate.verify(
            altered, storeId, deviceId, issuerId, b64(root.public.encoded), devicePublic
        ))
    }

    @Test fun challengeProofAndRevocationAreSignedByRootOrDevicePrivateKey() {
        val storeId = UUID.randomUUID().toString()
        val deviceId = UUID.randomUUID().toString()
        val root = keyPair()
        val device = keyPair()
        val serial = UUID.randomUUID().toString()
        val challenge = "A".repeat(43)
        val expiry = System.currentTimeMillis() + 20_000
        val challengeSignature = Base64.decode(
            SyncDeviceCertificate.challengeSignature(
                storeId, deviceId, serial, challenge, expiry, device.private
            ), Base64.DEFAULT
        )
        val proof = JSONArray().put("THE_DAILY_SHOP_RELAY_AUTH_V1")
            .put(storeId).put(deviceId).put(serial).put(challenge).put(expiry).toString()
        assertTrue(SyncDeviceAuthenticator.verifyBytes(
            device.public.encoded, proof.toByteArray(Charsets.UTF_8), challengeSignature
        ))
        assertFalse(SyncDeviceAuthenticator.verifyBytes(
            root.public.encoded, proof.toByteArray(Charsets.UTF_8), challengeSignature
        ))

        val revocationJson = SyncDeviceCertificate.signedRevocation(
            storeId, deviceId, serial, root.private
        )
        val revocation = JSONObject(revocationJson)
        val canonical = JSONObject()
            .put("version", revocation.getInt("version"))
            .put("store_id", revocation.getString("store_id"))
            .put("device_id", revocation.getString("device_id"))
            .put("serial", revocation.getString("serial"))
            .put("revoked_at", revocation.getLong("revoked_at"))
            .toString()
        assertTrue(SyncDeviceAuthenticator.verifyBytes(
            root.public.encoded,
            canonical.toByteArray(Charsets.UTF_8),
            Base64.decode(revocation.getString("signature"), Base64.DEFAULT)
        ))
    }
}
