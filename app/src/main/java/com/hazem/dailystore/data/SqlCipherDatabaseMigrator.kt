package com.hazem.dailystore.data

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Isolated, non-destructive export primitive for the one-time plaintext-to-SQLCipher
 * migration. It never renames or deletes the source; the caller owns backup,
 * maintenance mode, parity checks, and the eventual atomic swap.
 */
internal object SqlCipherDatabaseMigrator {
    private const val MAX_DATABASE_BYTES = 512L * 1024L * 1024L

    fun exportPlainDatabase(source: File, destination: File, databaseKey: ByteArray) {
        require(source.isFile && source.length() in 1..MAX_DATABASE_BYTES) {
            "قاعدة المصدر غير موجودة أو تجاوزت الحجم المدعوم"
        }
        require(databaseKey.size == 32) { "SQLCipher key must be 256 bits" }
        require(!destination.exists() || destination.length() == 0L) {
            "ملف الوجهة موجود مسبقاً؛ رُفضت الكتابة فوقه"
        }
        val parent = destination.parentFile
            ?: throw IllegalArgumentException("Encrypted destination has no parent directory")
        check(parent.isDirectory || parent.mkdirs()) { "تعذر إنشاء مجلد قاعدة البيانات المشفرة" }
        if (!destination.exists()) {
            check(destination.createNewFile()) { "تعذر إنشاء ملف قاعدة البيانات المشفرة" }
        }

        val passphrase = databaseKey.toHex().toByteArray(Charsets.US_ASCII)
        val escapedPath = destination.absolutePath.replace("'", "''")
        val escapedPassphrase = String(passphrase, Charsets.US_ASCII).replace("'", "''")
        var sourceDb: SQLiteDatabase? = null
        try {
            System.loadLibrary("sqlcipher")
            // SQLCipher can read a plaintext SQLite source when no passphrase is supplied.
            sourceDb = SQLiteDatabase.openDatabase(
                source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            sourceDb.execSQL("ATTACH DATABASE '$escapedPath' AS encrypted KEY '$escapedPassphrase'")
            try {
                sourceDb.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { cursor ->
                    check(cursor.moveToFirst()) { "فشل تصدير مخطط قاعدة البيانات" }
                }
                sourceDb.execSQL("PRAGMA encrypted.user_version=${sourceDb.version}")
            } finally {
                sourceDb.execSQL("DETACH DATABASE encrypted")
            }
        } catch (error: Exception) {
            destination.delete()
            throw IllegalStateException("تعذر تصدير قاعدة SQLite إلى SQLCipher", error)
        } finally {
            sourceDb?.close()
            passphrase.fill(0)
        }

        try {
            validateEncryptedDatabase(destination, databaseKey, sourceVersion = null)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun validateEncryptedDatabase(
        file: File,
        databaseKey: ByteArray,
        sourceVersion: Int? = null
    ) {
        require(file.isFile && file.length() in 1..MAX_DATABASE_BYTES) {
            "ملف قاعدة SQLCipher غير موجود أو غير صالح"
        }
        require(databaseKey.size == 32) { "SQLCipher key must be 256 bits" }
        val passphrase = databaseKey.toHex().toByteArray(Charsets.US_ASCII)
        var database: SQLiteDatabase? = null
        try {
            System.loadLibrary("sqlcipher")
            database = SQLiteDatabase.openDatabase(
                file.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
                null
            )
            if (sourceVersion != null) {
                check(database.version == sourceVersion) { "إصدار المخطط تغير أثناء التصدير" }
            }
            database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0).equals("ok", true)) {
                    "فشل فحص سلامة قاعدة SQLCipher"
                }
            }
            database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                check(!cursor.moveToFirst()) { "توجد علاقات غير سليمة في قاعدة SQLCipher" }
            }
        } finally {
            database?.close()
            passphrase.fill(0)
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}
