package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class SupplierSummary(
    val id: String,
    val name: String,
    val phone: String?,
    val address: String?
)

/** Local supplier directory used by purchase invoices. */
class SupplierRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)

    fun listActive(): List<SupplierSummary> {
        val result = mutableListOf<SupplierSummary>()
        database.readableDatabase.rawQuery(
            "SELECT id,name,phone,address FROM suppliers WHERE is_active=1 ORDER BY name COLLATE NOCASE",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    SupplierSummary(
                        cursor.getString(0),
                        cursor.getString(1),
                        if (cursor.isNull(2)) null else cursor.getString(2),
                        if (cursor.isNull(3)) null else cursor.getString(3)
                    )
                )
            }
        }
        return result
    }

    fun create(name: String, phone: String? = null, address: String? = null): SupplierSummary {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "اسم المورد مطلوب" }
        val now = timestamp()
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", id)
            put("name", cleanName)
            putNullable("phone", phone?.trim()?.takeIf { it.isNotEmpty() })
            putNullable("address", address?.trim()?.takeIf { it.isNotEmpty() })
            put("created_at", now)
            put("updated_at", now)
        }
        database.writableDatabase.insertOrThrow("suppliers", null, values)
        return SupplierSummary(id, cleanName, phone?.trim()?.takeIf { it.isNotEmpty() },
            address?.trim()?.takeIf { it.isNotEmpty() })
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
