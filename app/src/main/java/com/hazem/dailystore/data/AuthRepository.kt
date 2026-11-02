package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
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

/** Database-backed authentication and role checks. Passwords are never stored in plain text. */
class AuthRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)

    /** Creates the temporary first manager account only when the users table is empty. */
    fun ensureInitialAdmin() {
        val db = database.writableDatabase
        val count = db.rawQuery("SELECT COUNT(*) FROM users", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        if (count != 0) return
        createUserInternal("admin", "مدير المتجر", "1234", "ADMIN", null, true)
    }

    fun authenticate(username: String, password: String): AuthenticatedUser? {
        val cleanUsername = username.trim()
        if (cleanUsername.isEmpty() || password.isEmpty()) return null
        val db = database.writableDatabase
        val user = db.rawQuery(
            "SELECT id,username,display_name,password_hash,password_salt,role_key,is_active,must_change_password FROM users WHERE username=? COLLATE NOCASE",
            arrayOf(cleanUsername)
        ).use { c ->
            if (!c.moveToFirst() || c.getInt(6) != 1) null
            else UserCredential(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getInt(7) == 1)
        } ?: return null
        val candidate = derive(password, user.salt)
        if (!MessageDigest.isEqual(candidate, Base64.decode(user.hash, Base64.NO_WRAP))) return null
        val now = timestamp()
        db.update("users", ContentValues().apply { put("last_login_at", now); put("updated_at", now) },
            "id=?", arrayOf(user.id))
        return AuthenticatedUser(user.id,user.username,user.displayName,user.role,user.mustChangePassword)
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
}
