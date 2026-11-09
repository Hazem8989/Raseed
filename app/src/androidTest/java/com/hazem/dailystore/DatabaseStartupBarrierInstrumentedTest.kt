package com.hazem.dailystore

import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the process-startup recovery barrier against manifest changes that
 * could let a ContentProvider or a secondary process touch storage first.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseStartupBarrierInstrumentedTest {
    @Suppress("DEPRECATION")
    @Test
    fun recoveryApplicationRunsBeforeAnyProviderOrSecondaryProcessCanOpenStorage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_ACTIVITIES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS
        )

        assertEquals(
            "The manifest must install the recovery Application class",
            TheDailyShopApplication::class.java.name,
            packageInfo.applicationInfo?.className
        )
        assertTrue(
            "The target process must already be running the recovery Application",
            context.applicationContext is TheDailyShopApplication
        )

        val providers = packageInfo.providers.orEmpty()
        assertTrue(
            "A ContentProvider can run before Application.onCreate and bypass recovery: " +
                providers.joinToString { it.name },
            providers.isEmpty()
        )

        val components = packageInfo.activities.orEmpty().toList() +
            packageInfo.receivers.orEmpty().toList() +
            packageInfo.services.orEmpty().toList()
        val splitProcesses = components.filter {
            !it.processName.isNullOrBlank() && it.processName != context.packageName
        }
        assertTrue(
            "Every database-capable component must share the Application recovery process: " +
                splitProcesses.joinToString { "${it.name}=${it.processName}" },
            splitProcesses.isEmpty()
        )
    }
}
