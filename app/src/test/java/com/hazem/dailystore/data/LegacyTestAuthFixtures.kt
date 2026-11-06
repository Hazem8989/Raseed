package com.hazem.dailystore.data

import android.content.ContentValues
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
    val db = AppDatabase.getInstance(androidx.test.core.app.ApplicationProvider.getApplicationContext())
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
