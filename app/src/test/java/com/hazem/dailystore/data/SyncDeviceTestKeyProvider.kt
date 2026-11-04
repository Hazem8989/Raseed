package com.hazem.dailystore.data

import android.content.Context
import android.util.Base64
import org.robolectric.RuntimeEnvironment
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * JVM/Robolectric-only identity provider. Keys are isolated per device ID and
 * remain stable for the duration of each test setup.
 */
internal object SyncDeviceTestKeyProvider {
    fun install() {
        val keys = mutableMapOf<String, KeyPair>()
        SyncDeviceAuthenticator.installTestKeyPairProvider { _, deviceId ->
            synchronized(keys) {
                keys.getOrPut(deviceId) {
                    KeyPairGenerator.getInstance("EC").apply {
                        initialize(ECGenParameterSpec("secp256r1"))
                    }.generateKeyPair()
                }
            }
        }
    }
}


/** Test-only convenience overloads that create real P-256 enrollment material. */
internal fun SyncProtocolRepository.enrollSecondaryDevice(
    actorUserId: String,
    invitationToken: String,
    remoteDeviceId: String,
    displayName: String
): SyncEnrollment {
    val context = RuntimeEnvironment.getApplication()
    val remote = SyncDeviceAuthenticator.getOrCreate(context, remoteDeviceId)
    val publicKey = Base64.encodeToString(remote.public.encoded, Base64.NO_WRAP)
    return enrollSecondaryDevice(actorUserId, invitationToken, remoteDeviceId, displayName, publicKey)
}

internal fun SyncProtocolRepository.configureAsSecondary(
    actorUserId: String,
    storeId: String,
    primaryDeviceId: String,
    credential: String,
    displayName: String,
    storeTimeZoneId: String = StoreTime.DEFAULT_ZONE_ID
) {
    val context = RuntimeEnvironment.getApplication()
    val localId = SyncDeviceIdentity.getOrCreate(context)
    val local = SyncDeviceAuthenticator.getOrCreate(context, localId)
    val primary = SyncDeviceAuthenticator.getOrCreate(context, primaryDeviceId)
    val localPublic = Base64.encodeToString(local.public.encoded, Base64.NO_WRAP)
    val rootPublic = Base64.encodeToString(primary.public.encoded, Base64.NO_WRAP)
    val certificate = SyncDeviceCertificate.issue(
        storeId, primaryDeviceId, localId, localPublic, primary.private
    )
    configureAsSecondary(
        actorUserId, storeId, primaryDeviceId, credential, displayName, storeTimeZoneId,
        rootPublic, certificate
    )
}
