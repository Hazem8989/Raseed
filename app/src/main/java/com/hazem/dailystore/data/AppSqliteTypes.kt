package com.hazem.dailystore.data

/**
 * Single backend type seam for the production database layer.
 *
 * Keep these aliases mapped to Android SQLite until every repository,
 * sync consumer, and backup/restore path has been migrated and verified.
 * The aliases are intentionally internal so this transition does not become
 * part of the app's public API.
 */
internal typealias AppSqliteDatabase = android.database.sqlite.SQLiteDatabase
internal typealias AppSqliteOpenHelper = android.database.sqlite.SQLiteOpenHelper
