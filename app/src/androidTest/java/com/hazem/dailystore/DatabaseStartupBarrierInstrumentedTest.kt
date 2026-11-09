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
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_META_DATA
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

        // These AndroidX Startup initializers are contributed by the app's
        // AndroidX dependencies. Their responsibilities are limited to framework
        // lifecycle dispatch, emoji compatibility metadata, and baseline profile
        // installation. None receives or opens AppDatabase/SQLite/SQLCipher.
        // Keep this exact allowlist: any newly merged initializer must be audited
        // before it can execute ahead of TheDailyShopApplication.onCreate().
        val providers = packageInfo.providers.orEmpty()
        assertEquals(
            "Only the audited AndroidX Startup provider may run before Application.onCreate",
            1,
            providers.size
        )
        val startupProvider = providers.singleOrNull()
        assertEquals(
            "Unexpected early ContentProvider",
            "androidx.startup.InitializationProvider",
            startupProvider?.name
        )
        assertEquals(
            "AndroidX Startup must stay in the recovered default process",
            context.packageName,
            startupProvider?.processName
        )
        val startupMetadata = startupProvider?.metaData
        val initializerNames = startupMetadata?.keySet().orEmpty()
            .filter { key -> startupMetadata?.get(key) == "androidx.startup" }
            .toSet()
        assertEquals(
            "Only the audited non-database AndroidX initializers may run before recovery",
            setOf(
                "androidx.emoji2.text.EmojiCompatInitializer",
                "androidx.lifecycle.ProcessLifecycleInitializer",
                "androidx.profileinstaller.ProfileInstallerInitializer"
            ),
            initializerNames
        )

        // Keep the component data strongly typed. Combining the three different
        // PackageItemInfo arrays into one inferred list can degrade to Object on
        // older Android verifiers and trigger a VerifyError before JUnit starts.
        val componentProcesses = buildList<Pair<String, String?>> {
            packageInfo.activities.orEmpty().forEach { add(it.name to it.processName) }
            packageInfo.receivers.orEmpty().forEach { add(it.name to it.processName) }
            packageInfo.services.orEmpty().forEach { add(it.name to it.processName) }
        }
        val splitProcesses = componentProcesses.filter { (_, processName) ->
            !processName.isNullOrBlank() && processName != context.packageName
        }
        assertTrue(
            "Every database-capable component must share the Application recovery process: " +
                splitProcesses.joinToString { (name, processName) -> "$name=$processName" },
            splitProcesses.isEmpty()
        )
    }
}
