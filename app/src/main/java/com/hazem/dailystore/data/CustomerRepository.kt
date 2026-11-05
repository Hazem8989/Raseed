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
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)

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

    fun create(actorUserId: String, name: String, phone: String? = null, address: String? = null,
               notes: String? = null): CustomerSummary {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "اسم الزبون مطلوب" }
        requirePrimaryMasterDataDevice()
        val db = database.writableDatabase
        PermissionPolicy.require(db, actorUserId, PermissionPolicy.CUSTOMERS_CREATE)
        val id = UUID.randomUUID().toString()
        val now = timestamp()
        val values = ContentValues().apply {
            put("id", id); put("name", cleanName)
            putNullable("phone", phone); putNullable("address", address)
            putNullable("notes", notes)
            put("opening_balance_minor", 0L); put("opening_balance_currency", "SYP")
            put("is_active", 1); put("created_at", now); put("updated_at", now)
        }
        db.beginTransaction()
        try {
            db.insertOrThrow("customers", null, values)
            insertAudit(db, actorUserId, "CREATE", id, now)
            enqueueCustomerSync(db, actorUserId, id, "CREATE")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            runCatching { SyncRelaySession.flushPending() }
        }
        return get(id) ?: error("تعذر قراءة الزبون بعد الحفظ")
    }

    fun update(actorUserId: String, customerId: String, name: String, phone: String? = null,
               address: String? = null, notes: String? = null): CustomerSummary {
        require(customerId.isNotBlank()) { "معرّف الزبون مطلوب" }
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "اسم الزبون مطلوب" }
        requirePrimaryMasterDataDevice()
        val db = database.writableDatabase
        PermissionPolicy.require(db, actorUserId, PermissionPolicy.CUSTOMERS_MANAGE)
        val values = ContentValues().apply {
            put("name", cleanName); putNullable("phone", phone)
            putNullable("address", address); putNullable("notes", notes)
            put("updated_at", timestamp())
        }
        val now = timestamp()
        values.put("updated_at", now)
        db.beginTransaction()
        try {
            require(db.update(
                "customers", values, "id=? AND is_active=1", arrayOf(customerId)
            ) == 1) { "الزبون غير موجود أو موقوف" }
            insertAudit(db, actorUserId, "UPDATE", customerId, now)
            enqueueCustomerSync(db, actorUserId, customerId, "UPDATE")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            runCatching { SyncRelaySession.flushPending() }
        }
        return get(customerId) ?: error("تعذر قراءة بيانات الزبون")
    }

    fun setActive(actorUserId: String, customerId: String, active: Boolean) {
        require(customerId.isNotBlank()) { "معرّف الزبون مطلوب" }
        val db = database.writableDatabase
        PermissionPolicy.require(db, actorUserId, PermissionPolicy.CUSTOMERS_MANAGE)
        val values = ContentValues().apply {
            put("is_active", if (active) 1 else 0); put("updated_at", timestamp())
        }
        val now = timestamp()
        values.put("updated_at", now)
        db.beginTransaction()
        try {
            require(db.update(
                "customers", values, "id=?", arrayOf(customerId)
            ) == 1) { "الزبون غير موجود" }
            insertAudit(db, actorUserId, if (active) "ACTIVATE" else "DEACTIVATE", customerId, now)
            val payload = org.json.JSONObject().put("business_operation", "SET_CUSTOMER_ACTIVE")
                .put("customer_id", customerId).put("is_active", active).put("occurred_at", now)
            SyncProtocolRepository(appContext).enqueueLocalOperation(
                db, actorUserId, UUID.randomUUID().toString(), "SET_CUSTOMER_ACTIVE",
                "CUSTOMER", customerId, "UPDATE", payload.toString()
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            runCatching { SyncRelaySession.flushPending() }
        }
    }

    private fun requirePrimaryMasterDataDevice() {
        require(!SyncProtocolRepository(appContext).isConfiguredSecondary()) {
            "إنشاء وتعديل بيانات الزبائن متاح من الجهاز الأساسي فقط"
        }
    }

    private fun enqueueCustomerSync(
        db: android.database.sqlite.SQLiteDatabase,
        actorUserId: String,
        customerId: String,
        changeKind: String
    ) {
        val payload = db.rawQuery(
            "SELECT name,phone,address,notes,is_active,created_at,updated_at FROM customers WHERE id=?",
            arrayOf(customerId)
        ).use { c ->
            require(c.moveToFirst()) { "الزبون غير موجود عند تجهيز المزامنة" }
            org.json.JSONObject().apply {
                put("business_operation", "UPSERT_CUSTOMER")
                put("customer_id", customerId)
                put("name", c.getString(0))
                put("phone", if (c.isNull(1)) org.json.JSONObject.NULL else c.getString(1))
                put("address", if (c.isNull(2)) org.json.JSONObject.NULL else c.getString(2))
                put("notes", if (c.isNull(3)) org.json.JSONObject.NULL else c.getString(3))
                put("is_active", c.getInt(4) == 1)
                put("created_at", c.getString(5))
                put("updated_at", c.getString(6))
            }
        }
        SyncProtocolRepository(appContext).enqueueLocalOperation(
            db, actorUserId, UUID.randomUUID().toString(), "UPSERT_CUSTOMER",
            "CUSTOMER", customerId, changeKind, payload.toString()
        )
    }

    private fun insertAudit(
        db: android.database.sqlite.SQLiteDatabase,
        actor: String,
        action: String,
        customerId: String,
        now: String
    ) {
        db.insertOrThrow("audit_log", null, ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("user_id", actor)
            put("action", action)
            put("entity_type", "CUSTOMER")
            put("entity_id", customerId)
            put("created_at", now)
        })
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == null) putNull(key) else put(key, clean)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
