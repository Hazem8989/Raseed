package com.hazem.dailystore.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The store, not the handset, owns the business calendar.
 *
 * Timestamps remain UTC instants. Operating dates and displayed timestamps are
 * rendered in the IANA time zone selected for the store, including its DST rules.
 */
object StoreTime {
    const val DEFAULT_ZONE_ID = "Asia/Damascus"
    private const val SETTING_KEY = "store_time_zone"
    private const val UTC_ID = "UTC"

    fun availableZoneIds(): List<String> =
        TimeZone.getAvailableIDs().filter { it.contains("/") }.distinct().sorted()

    fun isValidZoneId(value: String): Boolean =
        value == UTC_ID || TimeZone.getAvailableIDs().contains(value)

    fun zoneId(db: SQLiteDatabase): String {
        val configured = db.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key=?",
            arrayOf(SETTING_KEY)
        ).use { if (it.moveToFirst()) it.getString(0) else null }
        return configured?.takeIf(::isValidZoneId) ?: DEFAULT_ZONE_ID
    }

    fun setZoneId(db: SQLiteDatabase, value: String, updatedAt: String) {
        val clean = value.trim()
        require(isValidZoneId(clean)) { "معرّف المنطقة الزمنية غير صالح. اختر منطقة من القائمة." }
        db.insertWithOnConflict(
            "app_settings",
            null,
            ContentValues().apply {
                put("setting_key", SETTING_KEY)
                put("setting_value", clean)
                put("updated_at", updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun today(db: SQLiteDatabase, now: Date = Date()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(zoneId(db))
        }.format(now)

    fun operatingDate(db: SQLiteDatabase, utcTimestamp: String): String =
        today(db, parseUtcTimestamp(utcTimestamp))

    fun formatTimestamp(db: SQLiteDatabase, utcTimestamp: String): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone(zoneId(db))
        }.format(parseUtcTimestamp(utcTimestamp))

    private fun parseUtcTimestamp(value: String): Date {
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX"
        )
        for (pattern in formats) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = TimeZone.getTimeZone(UTC_ID)
            }
            val position = ParsePosition(0)
            val parsed = parser.parse(value, position)
            if (parsed != null && position.index == value.length) return parsed
        }
        throw IllegalArgumentException("طابع الحركة الزمني غير صالح: $value")
    }
}
