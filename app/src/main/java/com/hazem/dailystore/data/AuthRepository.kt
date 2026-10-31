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

data class AuthenticatedUser(val id: String, val username: String, val displayName: String, val roleKey: String)

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
        createUserInternal("admin", "مدير المتجر", "1234", "ADMIN", null)
    }

    fun authenticate(username: String, password: String): AuthenticatedUser? {
        val cleanUsername = username.trim()
        if (cleanUsername.isEmpty() || password.isEmpty()) return null
        val db = database.writableDatabase
        val user = db.rawQuery(
            "SELECT id,username,display_name,password_hash,password_salt,role_key,is_active FROM users WHERE username=? COLLATE NOCASE",
            arrayOf(cleanUsername)
        ).use { c ->
            if (!c.moveToFirst() || c.getInt(6) != 1) null
            else UserCredential(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5))
        } ?: return null
        val candidate = derive(password, user.salt)
        if (!MessageDigest.isEqual(candidate, Base64.decode(user.hash, Base64.NO_WRAP))) return null
        val now = timestamp()
        db.update("users", ContentValues().apply { put("last_login_at", now); put("updated_at", now) },
            "id=?", arrayOf(user.id))
        return AuthenticatedUser(user.id,user.username,user.displayName,user.role)
    }

    /** Restores a previously authenticated local session only for an active user. */
    fun restoreActiveSession(userId: String): AuthenticatedUser? {
        if (userId.isBlank()) return null
        return database.readableDatabase.rawQuery(
            "SELECT id,username,display_name,role_key FROM users WHERE id=? AND is_active=1",
            arrayOf(userId)
        ).use { c ->
            if (!c.moveToFirst()) null
            else AuthenticatedUser(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
        }
    }

    /** Restores a previously authenticated local session only for an active user. */
    fun restoreActiveSession(userId: String): AuthenticatedUser? {
        if (userId.isBlank()) return null
        return database.readableDatabase.rawQuery(
            "SELECT id,username,display_name,role_key FROM users WHERE id=? AND is_active=1",
            arrayOf(userId)
        ).use { c ->
            if (!c.moveToFirst()) null
            else AuthenticatedUser(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
        }
    }

    fun createUser(
        actorRole: String, actorUserId: String, username: String, displayName: String,
        password: String, roleKey: String
    ): String {
        requireManager(actorRole)
        require(actorUserId.isNotBlank()) { "جلسة المدير غير صالحة" }
        requireActiveManager(actorUserId)
        require(roleKey in setOf("ADMIN", "CASHIER")) { "صلاحية المستخدم غير صالحة" }
        return createUserInternal(username, displayName, password, roleKey, actorUserId)
    }

    fun requireManager(roleKey: String) {
        require(roleKey == "ADMIN") { "هذا الإجراء متاح للمدير فقط" }
    }

    private fun requireActiveManager(userId: String) {
        val valid = database.readableDatabase.rawQuery(
            "SELECT 1 FROM users WHERE id=? AND role_key='ADMIN' AND is_active=1", arrayOf(userId)
        ).use { it.moveToFirst() }
        require(valid) { "يجب تسجيل الدخول بحساب مدير نشط لتنفيذ هذا الإجراء" }
    }

    private fun createUserInternal(
        username: String, displayName: String, password: String, roleKey: String, actorUserId: String?
    ): String {
        val cleanUsername = username.trim()
        val cleanDisplayName = displayName.trim()
        require(cleanUsername.length >= 3) { "اسم المستخدم يجب ألا يقل عن 3 أحرف" }
        require(cleanDisplayName.isNotEmpty()) { "اسم العرض مطلوب" }
        require(password.length >= 4) { "كلمة المرور يجب ألا تقل عن 4 أحرف" }
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
                put("is_active",1); put("created_at",now); put("updated_at",now)
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
        val id:String,val username:String,val displayName:String,val hash:String,val salt:String,val role:String
    )

    private fun derive(password: String, salt: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), Base64.decode(salt, Base64.NO_WRAP), 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
