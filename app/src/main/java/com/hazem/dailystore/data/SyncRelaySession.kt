package com.hazem.dailystore.data

import android.content.Context

/** Process-wide relay session so opening/closing the manager screen does not duplicate sockets. */
object SyncRelaySession {
    @Volatile private var runtime: SyncRelayRuntime? = null
    @Volatile private var endpoint: String? = null

    @Synchronized
    fun connect(context: Context, wssEndpoint: String, repository: SyncProtocolRepository) {
        require(wssEndpoint.startsWith("wss://", ignoreCase = true)) {
            "يجب استخدام عنوان WSS مشفر"
        }
        if (runtime == null || endpoint != wssEndpoint) {
            runtime?.shutdown()
            runtime = SyncRelayRuntime(context.applicationContext, wssEndpoint, repository)
            endpoint = wssEndpoint
        }
        runtime?.connect()
    }

    @Synchronized
    fun refreshDeviceRegistry() {
        runtime?.refreshDeviceRegistry()
    }

    @Synchronized
    fun flushPending() {
        runtime?.flushPending()
    }

    @Synchronized
    fun isConnected(): Boolean = runtime?.isConnected == true

    @Synchronized
    fun disconnect() {
        runtime?.disconnect()
    }

    @Synchronized
    fun shutdown() {
        runtime?.shutdown()
        runtime = null
        endpoint = null
    }
}
