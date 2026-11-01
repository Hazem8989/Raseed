package com.hazem.dailystore.data

import android.content.Context
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SyncDeviceIdentityTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(
            "the_daily_shop_sync_identity",
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun returnsStableUuidForTheSameInstallation() {
        val first = SyncDeviceIdentity.getOrCreate(context)
        val second = SyncDeviceIdentity.getOrCreate(context)

        assertEquals(first, second)
        assertEquals(UUID.fromString(first).toString(), first)
    }

    @Test
    fun persistsIdentityAcrossIndependentCalls() {
        val first = SyncDeviceIdentity.getOrCreate(context)
        val anotherContextReference = context.applicationContext

        assertEquals(first, SyncDeviceIdentity.getOrCreate(anotherContextReference))
        assertTrue(first.isNotBlank())
    }

    @Test
    fun createsNewIdentityAfterLocalAppIdentityIsCleared() {
        val first = SyncDeviceIdentity.getOrCreate(context)
        context.getSharedPreferences(
            "the_daily_shop_sync_identity",
            Context.MODE_PRIVATE
        ).edit().clear().commit()

        val replacement = SyncDeviceIdentity.getOrCreate(context)

        assertNotEquals(first, replacement)
        assertEquals(UUID.fromString(replacement).toString(), replacement)
    }
}
