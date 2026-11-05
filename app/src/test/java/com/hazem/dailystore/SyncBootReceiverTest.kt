package com.hazem.dailystore

import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SyncBootReceiverTest {
    private lateinit var context: Context
    private val preferencesName = "daily_shop_sync_relay"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(context).clearStartedServices()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(context).clearStartedServices()
    }

    @Test
    fun bootDoesNotStartSyncWhenUserDisabledIt() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
            .putBoolean("sync_enabled", false)
            .putString("relay_wss_url", "wss://relay.example.test")
            .commit()

        SyncBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun bootDoesNotStartSyncWithoutRelayEndpoint() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
            .putBoolean("sync_enabled", true)
            .remove("relay_wss_url")
            .commit()

        SyncBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun bootRestoresExplicitlyEnabledSyncService() {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit()
            .putBoolean("sync_enabled", true)
            .putString("relay_wss_url", "wss://relay.example.test")
            .commit()

        SyncBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val started = shadowOf(context).nextStartedService
        assertNotNull(started)
        assertEquals(SyncForegroundService::class.java.name, started!!.component!!.className)
        assertEquals(true, preferences.getBoolean("sync_enabled", false))
    }

    @Test
    fun unrelatedBroadcastDoesNotStartSync() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
            .putBoolean("sync_enabled", true)
            .putString("relay_wss_url", "wss://relay.example.test")
            .commit()

        SyncBootReceiver().onReceive(context, Intent("com.example.UNRELATED"))

        assertNull(shadowOf(context).nextStartedService)
    }

    @Test
    fun packageReplacementAlsoRestoresEnabledSync() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
            .putBoolean("sync_enabled", true)
            .putString("relay_wss_url", "wss://relay.example.test")
            .commit()

        SyncBootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val started = shadowOf(context).nextStartedService
        assertNotNull(started)
        assertEquals(SyncForegroundService::class.java.name, started!!.component!!.className)
    }
}
