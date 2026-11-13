package com.hazem.dailystore.data

/**
 * Single backend type seam for the production database layer.
 *
 * All repositories and sync consumers use SQLCipher's API-compatible SQLite
 * types. Legacy plaintext fixtures are opened with a null passphrase only in
 * explicitly isolated test/migration helpers.
 */
internal typealias AppSqliteDatabase = net.zetetic.database.sqlcipher.SQLiteDatabase
internal typealias AppSqliteOpenHelper = net.zetetic.database.sqlcipher.SQLiteOpenHelper
