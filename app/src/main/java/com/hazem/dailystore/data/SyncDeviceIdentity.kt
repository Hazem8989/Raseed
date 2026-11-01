package com.hazem.dailystore.data

import android.content.Context
import java.util.UUID

/**
 * Stable local identifier for this installation's device.
 *
 * This identifier is provenance metadata only. It is NOT an authentication
 * credential and must never be accepted as proof that a device is enrolled.
 * Remote requests must be authenticated independently before synchronization
 * is enabled.
 */
class SyncDeviceIdentity private constructor() {

    companion object {
        private const val PREFERENCES_NAME = "the_daily_shop_sync_identity"
        private const val DEVICE_ID_KEY = "device_id"

        /**
         * Returns a UUID generated once per app installation and persisted in
         * private app preferences. Clearing app data creates a new identity;
         * device enrollment must therefore be revoked/re-established safely.
         */
        fun getOrCreate(context: Context): String {
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
            )
            synchronized(SyncDeviceIdentity::class.java) {
                val existing = preferences.getString(DEVICE_ID_KEY, null)
                if (!existing.isNullOrBlank()) {
                    return existing
                }

                val generated = UUID.randomUUID().toString()
                check(preferences.edit().putString(DEVICE_ID_KEY, generated).commit()) {
                    "Unable to persist synchronization device identity"
                }
                return generated
            }
        }
    }
}
