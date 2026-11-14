package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import com.hazem.dailystore.data.AppSqliteDatabase as SQLiteDatabase
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class AuthenticatedUser(val id: String, val username: String, val displayName: String, val roleKey: String, val mustChangePassword: Boolean = false)

data class ManagedUserSummary(
    val id: String, val username: String, val displayName: String,
    val roleKey: String, val isActive: Boolean, val mustChangePassword: Boolean
)

/** Database-backed authentication and role checks. Passwords are never stored in plain text. */
class AuthRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)

    /**
     * First-run setup is required when no account exists, or when upgrading an
     * installation that still has the old, unclaimed temporary admin account.
     */
    fun needsInitialAdminSetup(): Boolean {
        val db = database.readableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM users", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        if (count == 0) return true
        if (count != 1) return false
        return db.rawQuery(
            "SELECT 1 FROM users WHERE username='admin' COLLATE NOCASE AND role_key='ADMIN' AND is_active=1 AND must_change_password=1",
            null
        ).use { it.moveToFirst() }
    }

    /**
     * Creates the first administrator with credentials chosen by the owner.
     * Also safely claims the legacy temporary admin on already-installed builds.
     */
    fun createInitialAdmin(username: String, displayName: String, password: String): String {
        val cleanUsername = username.trim()
        val cleanDisplayName = displayName.trim()
        require(cleanUsername.length >= 3) { "اسم المستخدم يجب ألا يقل عن 3 أحرف" }
        require(cleanDisplayName.isNotEmpty()) { "اسم العرض مطلوب" }
        require(isStrongPassword(password)) {
            "كلمة المرور يجب أن تتكون من 10 أحرف على الأقل وتتضمن حرفاً ورقماً"
        }

        val db = database.writableDatabase
        val salt = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val hash = Base64.encodeToString(derive(password, salt), Base64.NO_WRAP)
        val now = timestamp()
        db.beginTransaction()
        try {
            val count = db.rawQuery("SELECT COUNT(*) FROM users", null).use {
                if (it.moveToFirst()) it.getInt(0) else 0
            }
            val id = if (count == 0) {
                val newId = UUID.randomUUID().toString()
                db.insertOrThrow("users", null, ContentValues().apply {
                    put("id", newId); put("username", cleanUsername); put("display_name", cleanDisplayName)
                    put("password_hash", hash); put("password_salt", salt); put("role_key", "ADMIN")
                    put("is_active", 1); put("must_change_password", 0)
                    put("created_at", now); put("updated_at", now)
                })
                newId
            } else {
                val legacyId = db.rawQuery(
                    "SELECT id FROM users WHERE username='admin' COLLATE NOCASE AND role_key='ADMIN' AND is_active=1 AND must_change_password=1 AND (SELECT COUNT(*) FROM users)=1",
                    null
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?: throw IllegalStateException("تم إعداد حساب مستخدم مسبقاً؛ لا يمكن إعادة تهيئة المدير")
                val changed = db.update("users", ContentValues().apply {
                    put("username", cleanUsername); put("display_name", cleanDisplayName)
                    put("password_hash", hash); put("password_salt", salt)
                    put("must_change_password", 0); put("updated_at", now)
                }, "id=?", arrayOf(legacyId))
                require(changed == 1) { "تعذر حفظ حساب المدير" }
                legacyId
            }
            db.insertOrThrow("audit_log", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("user_id", id)
                put("action", "INITIAL_ADMIN_SETUP"); put("entity_type", "USER")
                put("entity_id", id); put("after_json", org.json.JSONObject().put("username", cleanUsername).put("role", "ADMIN").toString())
                put("created_at", now)
            })
            db.setTransactionSuccessful()
            return id
        } catch (e: SQLiteConstraintException) {
            throw IllegalArgumentException("اسم المستخدم مستخدم مسبقاً")
        } finally {
            db.endTransaction()
        }
    }

    fun authenticate(username: String, password: String): AuthenticatedUser? = synchronized(LOGIN_LOCK) {
        val cleanUsername = username.trim()
        if (cleanUsername.isEmpty() || password.isEmpty()) return@synchronized null
        val db = database.writableDatabase
        ensureLoginAttemptSchema(db)
        val usernameKey = MessageDigest.getInstance("SHA-256")
            .digest(cleanUsername.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val nowMillis = System.currentTimeMillis()
        val attempt = db.rawQuery(
            "SELECT failed_attempts,locked_until_ms FROM auth_login_attempts WHERE username_hash=?",
            arrayOf(usernameKey)
        ).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else null }
        if (attempt != null && attempt.second > nowMillis) return@synchronized null

        val user = db.rawQuery(
            "SELECT id,username,display_name,password_hash,password_salt,role_key,is_active,must_change_password FROM users WHERE username=? COLLATE NOCASE",
            arrayOf(cleanUsername)
        ).use { c ->
            if (!c.moveToFirst() || c.getInt(6) != 1) null
            else UserCredential(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getInt(7) == 1)
        }
        // Perform the same expensive KDF for unknown/inactive usernames to reduce
        // username-enumeration timing differences.
        val candidate = derive(password, user?.salt ?: DUMMY_LOGIN_SALT)
        val valid = user?.let {
            runCatching { MessageDigest.isEqual(candidate, Base64.decode(it.hash, Base64.NO_WRAP)) }
                .getOrDefault(false)
        } ?: false
        if (!valid || user == null) {
            // Password derivation can be slow on low-powered devices and test emulators.
            // Start the lock window after the KDF completes, not before it.
            val failureTimeMillis = System.currentTimeMillis()
            val priorFailures = when {
                attempt == null -> 0
                attempt.second == 0L -> attempt.first
                attempt.second <= failureTimeMillis -> 0
                else -> attempt.first
            }
            val failures = priorFailures + 1
            val lockUntil = if (failures >= MAX_LOGIN_FAILURES) failureTimeMillis + LOGIN_LOCK_MS else 0L
            db.insertWithOnConflict("auth_login_attempts", null, ContentValues().apply {
                put("username_hash", usernameKey)
                put("failed_attempts", if (lockUntil > 0L) 0 else failures)
                put("locked_until_ms", lockUntil)
                put("updated_at", timestamp())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            return@synchronized null
        }
        db.delete("auth_login_attempts", "username_hash=?", arrayOf(usernameKey))
        val now = timestamp()
        db.update("users", ContentValues().apply { put("last_login_at", now); put("updated_at", now) },
            "id=?", arrayOf(user.id))
        AuthenticatedUser(user.id,user.username,user.displayName,user.role,user.mustChangePassword)
    }

    private fun ensureLoginAttemptSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS auth_login_attempts (
                username_hash TEXT PRIMARY KEY NOT NULL,
                failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK(failed_attempts >= 0),
                locked_until_ms INTEGER NOT NULL DEFAULT 0 CHECK(locked_until_ms >= 0),
                updated_at TEXT NOT NULL
            )""".trimIndent()
        )
    }

    /** Restores a previously authenticated local session only for an active user. */
    fun restoreActiveSession(userId: String): AuthenticatedUser? {
        if (userId.isBlank()) return null
        return database.readableDatabase.rawQuery(
            "SELECT id,username,display_name,role_key,must_change_password FROM users WHERE id=? AND is_active=1",
            arrayOf(userId)
        ).use { c ->
            if (!c.moveToFirst()) null
            else AuthenticatedUser(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getInt(4) == 1)
        }
    }

    /**
     * Replaces a temporary/current password. A password change is required
     * before a user with must_change_password=1 can enter the store.
     */
    fun changePassword(userId: String, currentPassword: String, newPassword: String) {
        require(userId.isNotBlank()) { "جلسة المستخدم غير صالحة" }
        require(isStrongPassword(newPassword)) {
            "كلمة المرور الجديدة يجب أن تتكون من 10 أحرف على الأقل وتتضمن حرفاً ورقماً"
        }
        require(currentPassword != newPassword) { "اختر كلمة مرور مختلفة عن الحالية" }
        val db = database.writableDatabase
        val credential = db.rawQuery(
            "SELECT password_hash,password_salt FROM users WHERE id=? AND is_active=1",
            arrayOf(userId)
        ).use { c ->
            if (!c.moveToFirst()) null else c.getString(0) to c.getString(1)
        } ?: throw IllegalArgumentException("حساب المستخدم غير نشط")
        val currentHash = derive(currentPassword, credential.second)
        require(MessageDigest.isEqual(currentHash, Base64.decode(credential.first, Base64.NO_WRAP))) {
            "كلمة المرور الحالية غير صحيحة"
        }
        val salt = Base64.encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val hash = Base64.encodeToString(derive(newPassword, salt), Base64.NO_WRAP)
        val now = timestamp()
        db.beginTransaction()
        try {
            val changed = db.update(
                "users",
                ContentValues().apply {
                    put("password_hash", hash)
                    put("password_salt", salt)
                    put("must_change_password", 0)
                    put("updated_at", now)
                },
                "id=? AND is_active=1",
                arrayOf(userId)
            )
            require(changed == 1) { "تعذر تحديث كلمة المرور" }
            db.insertOrThrow("audit_log", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("user_id", userId)
                put("action", "PASSWORD_CHANGE")
                put("entity_type", "USER")
                put("entity_id", userId)
                put("created_at", now)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun isStrongPassword(password: String): Boolean =
        password.length >= 10 &&
            password.any(Char::isLetter) &&
            password.any(Char::isDigit)

    fun listUsers(actorUserId: String): List<ManagedUserSummary> {
        val db = database.readableDatabase
        PermissionPolicy.require(db, actorUserId, PermissionPolicy.USERS_MANAGE)
        val result = mutableListOf<ManagedUserSummary>()
        db.rawQuery(
            "SELECT id,username,display_name,role_key,is_active,must_change_password FROM users ORDER BY created_at,id",
            null
        ).use { c ->
            while (c.moveToNext()) result.add(ManagedUserSummary(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                c.getInt(4) == 1, c.getInt(5) == 1
            ))
        }
        return result
    }

    fun setUserActive(actorUserId: String, targetUserId: String, active: Boolean) {
        require(targetUserId.isNotBlank()) { "معرّف المستخدم مطلوب" }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            PermissionPolicy.require(db, actorUserId, PermissionPolicy.USERS_MANAGE)
            require(actorUserId != targetUserId || active) {
                "لا يمكن إيقاف حسابك أثناء استخدامه؛ سجّل الخروج من الحساب الآخر أولاً"
            }
            val target = db.rawQuery(
                "SELECT username,role_key,is_active FROM users WHERE id=?",
                arrayOf(targetUserId)
            ).use { c ->
                require(c.moveToFirst()) { "المستخدم غير موجود" }
                Triple(c.getString(0), c.getString(1), c.getInt(2) == 1)
            }
            if (target.second == "ADMIN" && target.third && !active) {
                val otherAdmins = db.rawQuery(
                    "SELECT COUNT(*) FROM users WHERE role_key='ADMIN' AND is_active=1 AND id<>?",
                    arrayOf(targetUserId)
                ).use { c -> c.moveToFirst(); c.getInt(0) }
                require(otherAdmins > 0) { "لا يمكن إيقاف آخر حساب مدير نشط" }
            }
            if (target.third != active) {
                val now = timestamp()
                require(db.update(
                    "users", ContentValues().apply {
                        put("is_active", if (active) 1 else 0)
                        put("updated_at", now)
                    }, "id=?", arrayOf(targetUserId)
                ) == 1) { "تعذر تحديث حالة المستخدم" }
                db.insertOrThrow("audit_log", null, ContentValues().apply {
                    put("id", UUID.randomUUID().toString()); put("user_id", actorUserId)
                    put("action", if (active) "USER_ACTIVATED" else "USER_DEACTIVATED")
                    put("entity_type", "USER"); put("entity_id", targetUserId)
                    put("before_json", org.json.JSONObject().put("is_active", target.third).toString())
                    put("after_json", org.json.JSONObject().put("is_active", active).toString())
                    put("created_at", now)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun createUser(
        actorRole: String, actorUserId: String, username: String, displayName: String,
        password: String, roleKey: String
    ): String {
        requireManager(actorRole)
        PermissionPolicy.require(database.writableDatabase, actorUserId, PermissionPolicy.USERS_MANAGE)
        require(roleKey in setOf("ADMIN", "CASHIER")) { "صلاحية المستخدم غير صالحة" }
        return createUserInternal(username, displayName, password, roleKey, actorUserId, true)
    }

    fun requireManager(roleKey: String) {
        require(roleKey == "ADMIN") { "هذا الإجراء متاح للمدير فقط" }
    }

    fun requirePermission(actorUserId: String, permissionKey: String) {
        PermissionPolicy.require(database.readableDatabase, actorUserId, permissionKey)
    }

    private fun requireActiveManager(userId: String) {
        val valid = database.readableDatabase.rawQuery(
            "SELECT 1 FROM users WHERE id=? AND role_key='ADMIN' AND is_active=1 AND must_change_password=0", arrayOf(userId)
        ).use { it.moveToFirst() }
        require(valid) { "يجب تسجيل الدخول بحساب مدير نشط لتنفيذ هذا الإجراء" }
    }

    private fun createUserInternal(
        username: String, displayName: String, password: String, roleKey: String, actorUserId: String?, mustChangePassword: Boolean
    ): String {
        val cleanUsername = username.trim()
        val cleanDisplayName = displayName.trim()
        require(cleanUsername.length >= 3) { "اسم المستخدم يجب ألا يقل عن 3 أحرف" }
        require(cleanDisplayName.isNotEmpty()) { "اسم العرض مطلوب" }
        require(if (mustChangePassword && actorUserId == null) password.length >= 4 else isStrongPassword(password)) {
            "كلمة المرور يجب أن تتكون من 10 أحرف على الأقل وتتضمن حرفاً ورقماً"
        }
        require(roleKey in setOf("ADMIN", "CASHIER")) { "صلاحية المستخدم غير صالحة" }
        val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val salt = Base64.encodeToString(saltBytes, Base64.NO_WRAP)
        val hash = Base64.encodeToString(derive(password, salt), Base64.NO_WRAP)
        val id = UUID.randomUUID().toString()
        val now = timestamp()
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id",id); put("username",cleanUsername); put("display_name",cleanDisplayName)
                put("password_hash",hash); put("password_salt",salt); put("role_key",roleKey)
                put("is_active",1); put("must_change_password", if (mustChangePassword) 1 else 0); put("created_at",now); put("updated_at",now)
            }
            try {
                db.insertOrThrow("users",null,values)
            } catch (e: SQLiteConstraintException) {
                throw IllegalArgumentException("اسم المستخدم مستخدم مسبقاً")
            }
            if (actorUserId != null) {
                val audit = ContentValues().apply {
                    put("id",UUID.randomUUID().toString()); put("user_id",actorUserId)
                    put("action","CREATE"); put("entity_type","USER"); put("entity_id",id)
                    put("after_json","{\"username\":\"$cleanUsername\",\"role\":\"$roleKey\"}")
                    put("created_at",now)
                }
                db.insertOrThrow("audit_log",null,audit)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }

    private data class UserCredential(
        val id:String,val username:String,val displayName:String,val hash:String,val salt:String,val role:String,val mustChangePassword:Boolean
    )

    private fun derive(password: String, salt: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    companion object {
        private val LOGIN_LOCK = Any()
        private const val MAX_LOGIN_FAILURES = 5
        private const val LOGIN_LOCK_MS = 60_000L
        private const val DUMMY_LOGIN_SALT = "AAECAwQFBgcICQoLDA0ODw=="
    }
}
