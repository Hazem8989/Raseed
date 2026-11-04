package com.hazem.dailystore.data

import android.content.Context
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
