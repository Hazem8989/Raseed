package com.hazem.dailystore.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/**
 * Central permission catalogue and database-backed authorization.
 * Unknown permissions and users who have not completed password rotation are denied.
 */
data class RolePermissionSetting(val key: String, val description: String, val allowed: Boolean)

object PermissionPolicy {
    const val USERS_MANAGE = "users.manage"
    const val CATALOG_MANAGE = "catalog.manage"
    const val EXCHANGE_RATE_MANAGE = "exchange_rate.manage"
    const val ACCOUNTS_MANAGE = "accounts.manage"
    const val SUPPLIERS_MANAGE = "suppliers.manage"
    const val PURCHASES_MANAGE = "purchases.manage"
    const val SUPPLIER_PAYMENTS_MANAGE = "supplier_payments.manage"
    const val SUPPLIER_PAYMENTS_CORRECT = "supplier_payments.correct"
    const val CUSTOMERS_READ = "customers.read"
    const val CUSTOMERS_CREATE = "customers.create"
    const val CUSTOMERS_MANAGE = "customers.manage"
    const val CUSTOMERS_COLLECT = "customers.collect"
    const val SALES_CHECKOUT = "sales.checkout"
    const val REPORTS_READ = "reports.read"
    const val DAILY_CASH_MANAGE = "daily_cash.manage"
    const val SERVICE_TRANSFERS_MANAGE = "service_transfers.manage"
    const val SERVICE_TRANSFERS_EXECUTE = "service_transfers.execute"
    const val BACKUP_MANAGE = "backup.manage"
    const val INVENTORY_ADJUST = "inventory.adjust"
    const val RETURNS_PROCESS = "returns.process"
    const val EXPENSES_MANAGE = "expenses.manage"
    const val SYNC_MANAGE = "sync.manage"

    private val descriptions = linkedMapOf(
        USERS_MANAGE to "إدارة المستخدمين والصلاحيات",
        CATALOG_MANAGE to "إدارة الأصناف والتصنيفات والوحدات",
        EXCHANGE_RATE_MANAGE to "تغيير سعر الصرف",
        ACCOUNTS_MANAGE to "إدارة الصناديق والمحافظ والحسابات",
        SUPPLIERS_MANAGE to "إدارة بيانات الموردين",
        PURCHASES_MANAGE to "إنشاء واعتماد فواتير الشراء",
        SUPPLIER_PAYMENTS_MANAGE to "تسجيل دفعات الموردين",
        SUPPLIER_PAYMENTS_CORRECT to "تصحيح دفعات الموردين",
        CUSTOMERS_READ to "عرض بيانات العملاء",
        CUSTOMERS_CREATE to "إضافة عملاء جدد",
        CUSTOMERS_MANAGE to "تعديل وإيقاف بيانات العملاء",
        CUSTOMERS_COLLECT to "تحصيل ديون العملاء",
        SALES_CHECKOUT to "تنفيذ عمليات البيع",
        REPORTS_READ to "عرض التقارير",
        DAILY_CASH_MANAGE to "إدارة إقفال وفتح الصندوق اليومي",
        SERVICE_TRANSFERS_MANAGE to "إدارة محافظ الخدمات وإعادة شحنها",
        SERVICE_TRANSFERS_EXECUTE to "تنفيذ تحويلات العملاء للخدمات",
        BACKUP_MANAGE to "النسخ الاحتياطي والاستعادة",
        INVENTORY_ADJUST to "تسويات وجرد المخزون",
        RETURNS_PROCESS to "تنفيذ المرتجعات ورد الأموال",
        EXPENSES_MANAGE to "إدارة المصاريف",
        SYNC_MANAGE to "إدارة الأجهزة والمزامنة"
    )

    // Conservative cashier defaults: sell, view/create customers, and collect customer debts.
    private val cashierDefaults = setOf(
        SALES_CHECKOUT, CUSTOMERS_READ, CUSTOMERS_CREATE, CUSTOMERS_COLLECT, SERVICE_TRANSFERS_EXECUTE
    )

    fun ensureDefaults(db: SQLiteDatabase) {
        descriptions.forEach { (key, description) ->
            db.insertWithOnConflict(
                "permissions", null,
                ContentValues().apply { put("permission_key", key); put("description", description) },
                SQLiteDatabase.CONFLICT_IGNORE
            )
            listOf("ADMIN", "CASHIER").forEach { role ->
                db.insertWithOnConflict(
                    "role_permissions", null,
                    ContentValues().apply {
                        put("role_key", role)
                        put("permission_key", key)
                        put("allowed", if (role == "ADMIN" || key in cashierDefaults) 1 else 0)
                    },
                    SQLiteDatabase.CONFLICT_IGNORE
                )
            }
        }
    }

    /**
     * Until durable client-command submission and primary-side replay are wired into
     * every financial repository, a secondary must never post locally-authoritative
     * data. This is a fail-closed safety gate, not the final secondary workflow.
     */
    private val primaryOnlyPermissions = setOf(
        CATALOG_MANAGE, EXCHANGE_RATE_MANAGE, ACCOUNTS_MANAGE, SUPPLIERS_MANAGE,
        PURCHASES_MANAGE, SUPPLIER_PAYMENTS_MANAGE, SUPPLIER_PAYMENTS_CORRECT,
        CUSTOMERS_CREATE, CUSTOMERS_MANAGE, CUSTOMERS_COLLECT, SALES_CHECKOUT,
        DAILY_CASH_MANAGE, SERVICE_TRANSFERS_MANAGE, SERVICE_TRANSFERS_EXECUTE,
        INVENTORY_ADJUST, RETURNS_PROCESS, EXPENSES_MANAGE
    )

    fun require(db: SQLiteDatabase, userId: String, permissionKey: String) {
        require(userId.isNotBlank()) { "جلسة المستخدم غير صالحة" }
        require(permissionKey in descriptions) { "الصلاحية المطلوبة غير معروفة" }
        if (permissionKey in primaryOnlyPermissions) {
            val role = db.rawQuery(
                "SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'",
                null
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            require(role != "SECONDARY") {
                "هذا الجهاز ثانوي؛ لا يمكن ترحيل عملية مالية أو مخزنية محلياً قبل اعتمادها من الجهاز الأساسي"
            }
        }
        val allowed = db.rawQuery(
            """SELECT 1
               FROM users u
               JOIN role_permissions rp ON rp.role_key=u.role_key
               JOIN permissions p ON p.permission_key=rp.permission_key
               WHERE u.id=? AND u.is_active=1 AND u.must_change_password=0
                 AND p.permission_key=? AND rp.allowed=1""".trimIndent(),
            arrayOf(userId, permissionKey)
        ).use { it.moveToFirst() }
        require(allowed) { "ليس لديك صلاحية تنفيذ هذا الإجراء" }
    }

    fun setRolePermission(
        db: SQLiteDatabase, actorUserId: String, roleKey: String,
        permissionKey: String, allowed: Boolean
    ) {
        require(db.rawQuery(
            """SELECT 1 FROM users
               WHERE id=? AND role_key='ADMIN' AND is_active=1 AND must_change_password=0""".trimIndent(),
            arrayOf(actorUserId)
        ).use { it.moveToFirst() }) { "يجب تسجيل الدخول بحساب مدير نشط" }
        require(roleKey in setOf("ADMIN", "CASHIER")) { "الدور غير صالح" }
        require(permissionKey in descriptions) { "الصلاحية غير معروفة" }
        require(roleKey != "ADMIN" || allowed) { "لا يمكن سحب صلاحيات المدير الأساسية" }
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        db.beginTransaction()
        try {
            val before = db.rawQuery(
                "SELECT allowed FROM role_permissions WHERE role_key=? AND permission_key=?",
                arrayOf(roleKey, permissionKey)
            ).use { if (it.moveToFirst()) it.getInt(0) == 1 else false }
            db.insertWithOnConflict(
                "role_permissions", null,
                ContentValues().apply {
                    put("role_key", roleKey); put("permission_key", permissionKey)
                    put("allowed", if (allowed) 1 else 0)
                }, SQLiteDatabase.CONFLICT_REPLACE
            )
            db.insertOrThrow("audit_log", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("user_id", actorUserId)
                put("action", "ROLE_PERMISSION_CHANGE"); put("entity_type", "ROLE_PERMISSION")
                put("entity_id", "$roleKey:$permissionKey")
                put("before_json", "{\"allowed\":$before}")
                put("after_json", "{\"allowed\":$allowed}")
                put("created_at", now)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun listRolePermissions(db: SQLiteDatabase, roleKey: String): List<RolePermissionSetting> {
        require(roleKey in setOf("ADMIN", "CASHIER")) { "الدور غير صالح" }
        return descriptions.map { (key, description) ->
            val allowed = db.rawQuery(
                "SELECT allowed FROM role_permissions WHERE role_key=? AND permission_key=?",
                arrayOf(roleKey, key)
            ).use { c -> c.moveToFirst() && c.getInt(0) == 1 }
            RolePermissionSetting(key, description, allowed)
        }
    }

    fun rolePermissions(db: SQLiteDatabase, roleKey: String): Map<String, Boolean> =
        listRolePermissions(db, roleKey).associate { it.key to it.allowed }

    fun permissionDescriptions(): Map<String, String> = descriptions.toMap()

    fun allPermissionKeys(): Set<String> = descriptions.keys
}
