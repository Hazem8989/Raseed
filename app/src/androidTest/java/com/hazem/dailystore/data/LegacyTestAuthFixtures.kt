package com.hazem.dailystore.data

import android.content.ContentValues
import androidx.test.platform.app.InstrumentationRegistry
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Test-only compatibility fixture. Production code deliberately has no default
 * administrator bootstrap; tests create an explicit admin then install a legacy
 * test credential so older repository fixtures remain deterministic.
 */
internal fun AuthRepository.ensureInitialAdmin(): String {
    val id = createInitialAdmin("admin", "مدير الاختبار", "AdminTest12345")
    val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
    val spec = PBEKeySpec("1234".toCharArray(), saltBytes, 120_000, 256)
    val hash = try {
        Base64.encodeToString(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            Base64.NO_WRAP
        )
    } finally {
        spec.clearPassword()
    }
    val db = AppDatabase.getInstance(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
    db.writableDatabase.update(
        "users",
        ContentValues().apply {
            put("password_hash", hash)
            put("password_salt", salt)
            put("must_change_password", 0)
        },
        "id=?",
        arrayOf(id)
    )
    return id
}

/**
 * Creates a ready administrator for sync-domain tests without running password
 * derivation. These tests exercise sync authorization and posting, not login.
 * The placeholder credential is intentionally unusable and must never be used
 * by authentication tests.
 */
internal fun createSyncTestAdmin(context: android.content.Context): String {
    val database = AppDatabase.getInstance(context)
    val id = java.util.UUID.randomUUID().toString()
    val now = java.time.Instant.now().toString()
    database.writableDatabase.insertOrThrow(
        "users",
        null,
        ContentValues().apply {
            put("id", id)
            put("username", "sync-admin-$id")
            put("display_name", "مدير اختبار المزامنة")
            put("password_hash", "TEST_ONLY_NO_LOGIN_HASH")
            put("password_salt", "TEST_ONLY_NO_LOGIN_SALT")
            put("role_key", "ADMIN")
            put("is_active", 1)
            put("must_change_password", 0)
            put("created_at", now)
            put("updated_at", now)
        }
    )
    database.writableDatabase.insertOrThrow(
        "audit_log",
        null,
        ContentValues().apply {
            put("id", java.util.UUID.randomUUID().toString())
            put("user_id", id)
            put("action", "TEST_ADMIN_FIXTURE")
            put("entity_type", "USER")
            put("entity_id", id)
            put("created_at", now)
        }
    )
    return id
}

