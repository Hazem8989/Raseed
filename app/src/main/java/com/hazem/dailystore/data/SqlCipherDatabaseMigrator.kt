package com.hazem.dailystore.data

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

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
        var sourceSnapshot: DatabaseSnapshot? = null
        var sourceVersion = 0
        try {
            SqlCipherDatabaseRuntime.loadNativeLibrary()
            // SQLCipher can read a plaintext SQLite source when no passphrase is supplied.
            sourceDb = SQLiteDatabase.openDatabase(
                source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            sourceVersion = sourceDb.version
            sourceSnapshot = snapshot(sourceDb)
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
            validateEncryptedDatabase(destination, databaseKey, sourceVersion = sourceVersion)
            val encryptedSnapshot = openSnapshot(
                destination, databaseKey.toHex().toByteArray(Charsets.US_ASCII)
            )
            check(sourceSnapshot == encryptedSnapshot) {
                "يختلف محتوى قاعدة البيانات أو مخططها بعد التصدير؛ رُفض اعتماد الملف"
            }
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
            SqlCipherDatabaseRuntime.loadNativeLibrary()
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

    private data class TableContentDigest(
        val rowCount: Long,
        val sha256: String
    )

    private data class DatabaseSnapshot(
        val version: Int,
        val schema: List<List<String?>>,
        val tableDigests: Map<String, TableContentDigest>
    )

    /**
     * Hashes typed SQLite values, not their display strings. Row hashes are sorted so
     * equivalent tables compare equal even if SQLite returns rows in a different order.
     * sqlite_sequence is included separately because it is an internal table and is
     * omitted from the normal sqlite_master schema listing.
     */
    private fun snapshot(database: SQLiteDatabase): DatabaseSnapshot {
        val schema = mutableListOf<List<String?>>()
        val tables = mutableListOf<String>()
        database.rawQuery(
            "SELECT type,name,tbl_name,sql FROM sqlite_master " +
                "WHERE name NOT LIKE 'sqlite_%' AND type IN ('table','index','trigger','view') " +
                "ORDER BY type,name",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val row = (0..3).map { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                }
                schema += row
                if (row[0] == "table") tables += requireNotNull(row[1])
            }
        }
        val hasSequence = database.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='sqlite_sequence' LIMIT 1",
            null
        ).use { it.moveToFirst() }
        if (hasSequence) tables += "sqlite_sequence"

        val digests = linkedMapOf<String, TableContentDigest>()
        tables.sorted().forEach { table ->
            val escaped = table.replace("\"", "\"\"")
            val rowHashes = mutableListOf<String>()
            database.rawQuery("SELECT * FROM \"$escaped\"", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val rowDigest = MessageDigest.getInstance("SHA-256")
                    for (column in 0 until cursor.columnCount) {
                        when (cursor.getType(column)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> rowDigest.update(0)
                            android.database.Cursor.FIELD_TYPE_INTEGER -> {
                                rowDigest.update(1)
                                updateLong(rowDigest, cursor.getLong(column))
                            }
                            android.database.Cursor.FIELD_TYPE_FLOAT -> {
                                rowDigest.update(2)
                                updateLong(rowDigest, java.lang.Double.doubleToLongBits(cursor.getDouble(column)))
                            }
                            android.database.Cursor.FIELD_TYPE_STRING -> {
                                rowDigest.update(3)
                                updateBytes(rowDigest, cursor.getString(column).toByteArray(Charsets.UTF_8))
                            }
                            android.database.Cursor.FIELD_TYPE_BLOB -> {
                                rowDigest.update(4)
                                updateBytes(rowDigest, cursor.getBlob(column))
                            }
                            else -> error("نوع بيانات SQLite غير مدعوم في مقارنة الترحيل")
                        }
                    }
                    rowHashes += rowDigest.digest().toHex()
                }
            }
            rowHashes.sort()
            val tableDigest = MessageDigest.getInstance("SHA-256")
            updateLong(tableDigest, rowHashes.size.toLong())
            rowHashes.forEach { rowHash ->
                updateBytes(tableDigest, rowHash.toByteArray(Charsets.US_ASCII))
            }
            digests[table] = TableContentDigest(rowHashes.size.toLong(), tableDigest.digest().toHex())
        }
        return DatabaseSnapshot(database.version, schema, digests)
    }

    private fun updateLong(digest: MessageDigest, value: Long) {
        digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())
    }

    private fun updateBytes(digest: MessageDigest, value: ByteArray) {
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
        digest.update(value)
    }

    private fun openSnapshot(file: File, passphrase: ByteArray): DatabaseSnapshot {
        var database: SQLiteDatabase? = null
        try {
            database = SQLiteDatabase.openDatabase(
                file.absolutePath, passphrase, null,
                SQLiteDatabase.OPEN_READONLY, null, null
            )
            return snapshot(database)
        } finally {
            database?.close()
            passphrase.fill(0)
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte) }
}
