package com.hazem.dailystore

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncBootReceiverTest {
    private lateinit var baseContext: Context
    private lateinit var context: RecordingContext
    private val preferencesName = "daily_shop_sync_relay"

    @Before
    fun setUp() {
        baseContext = RuntimeEnvironment.getApplication()
        baseContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context = RecordingContext(baseContext)
    }

    @After
    fun tearDown() {
        baseContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun bootDoesNotStartSyncWhenUserDisabledIt() {
        preferences().edit()
            .putBoolean("sync_enabled", false)
            .putString("relay_wss_url", "wss://relay.example.test")
            .commit()

        SyncBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(context.startedService)
    }

    @Test
    fun bootDoesNotStartSyncWithoutRelayEndpoint() {
        preferences().edit()
            .putBoolean("sync_enabled", true)
            .remove("relay_wss_url")
            .commit()

        SyncBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(context.startedService)
    }

    @Test
    fun bootRestoresExplicitlyEnabledSyncService() {
        preferences().edit()
            .putBoolean("sync_enabled", true)
            .putString("relay_wss_url", "wss://relay.example.test")
            .commit()

        SyncBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertServiceStartRecorded()
        assertEquals(true, preferences().getBoolean("sync_enabled", false))
    }

    @Test
    fun unrelatedBroadcastDoesNotStartSync() {
        preferences().edit()
            .putBoolean("sync_enabled", true)
            .putString("relay_wss_url", "wss://relay.example.test")
            .commit()

        SyncBootReceiver().onReceive(context, Intent("com.example.UNRELATED"))

        assertNull(context.startedService)
    }

    @Test
    fun packageReplacementAlsoRestoresEnabledSync() {
        preferences().edit()
            .putBoolean("sync_enabled", true)
            .putString("relay_wss_url", "wss://relay.example.test")
            .commit()

        SyncBootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        assertServiceStartRecorded()
    }

    private fun preferences() =
        baseContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private fun assertServiceStartRecorded() {
        val started = context.startedService
        assertNotNull(started)
        assertEquals(SyncForegroundService::class.java.name, started!!.component!!.className)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var startedService: Intent? = null
            private set

        override fun getApplicationContext(): Context = this

        override fun startForegroundService(service: Intent): ComponentName? {
            startedService = service
            return service.component
        }

        override fun startService(service: Intent): ComponentName? {
            startedService = service
            return service.component
        }
    }
}
