package com.hazem.dailystore.data

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/**
 * Shared SQLCipher helper base for the production database and isolated fixtures.
 *
 * DatabaseKeyVault owns a raw 256-bit key. The migration/export pipeline uses
 * its lowercase hexadecimal ASCII representation as the SQLCipher passphrase;
 * this base applies exactly the same representation to SQLiteOpenHelper.
 */
internal abstract class SqlCipherOpenHelperBase(
    context: Context,
    databaseName: String,
    databaseKey: ByteArray?,
    databaseVersion: Int
) : SQLiteOpenHelper(
    context,
    databaseName,
    prepareDatabaseKey(databaseKey),
    null,
    databaseVersion,
    0,
    null,
    null,
    false
) {
    companion object {
        private fun prepareDatabaseKey(databaseKey: ByteArray?): ByteArray? {
            // SQLCipher Android requires its JNI library to be loaded before
            // SQLiteOpenHelper opens a connection. Do this while evaluating
            // the superclass arguments, before the helper can be used.
            SqlCipherDatabaseRuntime.loadNativeLibrary()
            if (databaseKey == null) return null // Explicit plaintext fixture/migration source only.
            require(databaseKey.size == 32) { "SQLCipher database key must be exactly 256 bits" }
            val hex = CharArray(databaseKey.size * 2)
            val digits = "0123456789abcdef"
            databaseKey.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                hex[index * 2] = digits[value ushr 4]
                hex[index * 2 + 1] = digits[value and 0x0f]
            }
            return String(hex).toByteArray(Charsets.US_ASCII)
        }
    }
}
