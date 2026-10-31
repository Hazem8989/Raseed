package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class CustomerSummary(
    val id: String, val name: String, val phone: String?, val address: String?,
    val notes: String?, val openingBalanceMinor: Long, val openingBalanceCurrency: String,
    val isActive: Boolean
)

/** Customer master data; financial balances are maintained by invoice/payment ledgers. */
class CustomerRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)

    fun listActive(): List<CustomerSummary> = list(false)
    fun listAll(): List<CustomerSummary> = list(true)

    private fun list(includeInactive: Boolean): List<CustomerSummary> {
        val result = mutableListOf<CustomerSummary>()
        val where = if (includeInactive) "" else "WHERE is_active=1"
        database.readableDatabase.rawQuery(
            """SELECT id,name,phone,address,notes,opening_balance_minor,
                      opening_balance_currency,is_active
               FROM customers $where ORDER BY name COLLATE NOCASE""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) result.add(CustomerSummary(
                c.getString(0), c.getString(1),
                if (c.isNull(2)) null else c.getString(2),
                if (c.isNull(3)) null else c.getString(3),
                if (c.isNull(4)) null else c.getString(4),
                c.getLong(5), c.getString(6), c.getInt(7) == 1
            ))
        }
        return result
    }

    fun get(customerId: String): CustomerSummary? =
        list(true).firstOrNull { it.id == customerId }

    fun create(name: String, phone: String? = null, address: String? = null,
               notes: String? = null): CustomerSummary {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "اسم الزبون مطلوب" }
        val id = UUID.randomUUID().toString()
        val now = timestamp()
        val values = ContentValues().apply {
            put("id", id); put("name", cleanName)
            putNullable("phone", phone); putNullable("address", address)
            putNullable("notes", notes)
            put("opening_balance_minor", 0L); put("opening_balance_currency", "SYP")
            put("is_active", 1); put("created_at", now); put("updated_at", now)
        }
        database.writableDatabase.insertOrThrow("customers", null, values)
        return get(id) ?: error("تعذر قراءة الزبون بعد الحفظ")
    }

    fun update(customerId: String, name: String, phone: String? = null,
               address: String? = null, notes: String? = null): CustomerSummary {
        require(customerId.isNotBlank()) { "معرّف الزبون مطلوب" }
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "اسم الزبون مطلوب" }
        val values = ContentValues().apply {
            put("name", cleanName); putNullable("phone", phone)
            putNullable("address", address); putNullable("notes", notes)
            put("updated_at", timestamp())
        }
        require(database.writableDatabase.update(
            "customers", values, "id=? AND is_active=1", arrayOf(customerId)
        ) == 1) { "الزبون غير موجود أو موقوف" }
        return get(customerId) ?: error("تعذر قراءة بيانات الزبون")
    }

    fun setActive(customerId: String, active: Boolean) {
        require(customerId.isNotBlank()) { "معرّف الزبون مطلوب" }
        val values = ContentValues().apply {
            put("is_active", if (active) 1 else 0); put("updated_at", timestamp())
        }
        require(database.writableDatabase.update(
            "customers", values, "id=?", arrayOf(customerId)
        ) == 1) { "الزبون غير موجود" }
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == null) putNull(key) else put(key, clean)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
