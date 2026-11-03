package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class CashAccountSummary(
    val id: String, val name: String, val accountType: String, val currencyCode: String,
    val balanceMinor: Long, val isActive: Boolean
)

/** Cash boxes and wallets. Balances are derived from opening balances and recorded transactions. */
class CashAccountRepository(context: Context) {
    private val database = AppDatabase.getInstance(context)

    fun listActive(): List<CashAccountSummary> {
        ensureSystemCashAccounts()
        return listAccounts(true)
    }

    fun ensureSystemCashAccounts() {
        val db=database.writableDatabase
        db.beginTransaction()
        try {
            ensureSystemCashAccounts(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun systemRoleAccountId(roleKey:String):String? {
        ensureSystemCashAccounts()
        return database.readableDatabase.rawQuery(
            "SELECT account_id FROM system_cash_account_roles WHERE role_key=?",arrayOf(roleKey)
        ).use { if(it.moveToFirst())it.getString(0)else null }
    }

    private fun ensureSystemCashAccounts(db:android.database.sqlite.SQLiteDatabase) {
        val date=operatingDate()
        val existingPrimary=db.rawQuery("SELECT primary_cash_account_id FROM daily_cash_sessions WHERE operating_date=?",arrayOf(date)).use {
            if(it.moveToFirst())it.getString(0)else null
        }
        val existingPrimaryValid=existingPrimary?.let { id ->
            db.rawQuery("SELECT 1 FROM cash_accounts WHERE id=? AND account_type='CASH_BOX' AND currency_code='SYP' AND is_active=1",arrayOf(id)).use{it.moveToFirst()}
        } ?: false
        if(!existingPrimaryValid) db.delete("system_cash_account_roles","role_key='DAILY_DRAWER'",null)
        val dailyId=if(existingPrimaryValid) existingPrimary!! else ensureRoleAccount(db,"DAILY_DRAWER","system_cash_daily_drawer","درج البيع اليومي",
            listOf("درج البيع اليومي","صندوق البيع اليومي","الدرج الرئيسي","درج المبيعات","صندوق المبيعات اليومي"))
        if(!existingPrimaryValid) {
            val values=ContentValues().apply {
                put("operating_date",date);put("primary_cash_account_id",dailyId);putNull("created_by");put("created_at",timestamp())
            }
            if(existingPrimary==null) db.insertOrThrow("daily_cash_sessions",null,values)
            else db.update("daily_cash_sessions",values,"operating_date=?",arrayOf(date))
        }
        setSystemRole(db,"DAILY_DRAWER",dailyId)

        val currentSupplier=db.rawQuery("SELECT account_id FROM daily_supplier_rollovers WHERE operating_date=?",arrayOf(date)).use {
            if(it.moveToFirst())it.getString(0)else null
        }
        val supplierId=currentSupplier ?: ensureRoleAccount(db,"SUPPLIER_ROLLOVER","system_cash_supplier_rollover",
            "صندوق النقد المدور للبضاعة",
            listOf("صندوق النقد المدور للبضاعة","صندوق نقدي مدور للبضاعة","صندوق الموردين المدور","النقد المدور للموردين","صندوق البضاعة"))
        setSystemRole(db,"SUPPLIER_ROLLOVER",supplierId)

        val configuredRecharge=db.rawQuery(
            "SELECT setting_value FROM app_settings WHERE setting_key='service_recharge_cash_account_id'",null
        ).use { if(it.moveToFirst())it.getString(0)else null }
        val validConfigured=configuredRecharge?.let { id ->
            id!=dailyId && id!=supplierId &&
                db.rawQuery("SELECT 1 FROM cash_accounts WHERE id=? AND account_type='CASH_BOX' AND currency_code='SYP' AND is_active=1",arrayOf(id)).use{it.moveToFirst()}
        } ?: false
        if(!validConfigured) {
            val linkedRecharge=db.rawQuery("SELECT account_id FROM system_cash_account_roles WHERE role_key='SERVICE_RECHARGE'",null).use {
                if(it.moveToFirst())it.getString(0)else null
            }
            if(linkedRecharge==dailyId || linkedRecharge==supplierId) {
                db.delete("system_cash_account_roles","role_key='SERVICE_RECHARGE'",null)
            }
        }
        var rechargeId=if(validConfigured) configuredRecharge!! else ensureRoleAccount(db,"SERVICE_RECHARGE",
            "system_cash_service_recharge","صندوق شراء رصيد الخدمات",
            listOf("صندوق رصيد خدمات نقدي","صندوق رصيد الخدمات نقدي","صندوق رصيد الخدمات النقدي","صندوق شراء رصيد الخدمات","صندوق رصيد الخدمات","صندوق تعبئة رصيد الخدمات"))
        if(rechargeId==dailyId || rechargeId==supplierId) {
            db.delete("system_cash_account_roles","role_key='SERVICE_RECHARGE'",null)
            rechargeId=UUID.randomUUID().toString()
            val values=ContentValues().apply {
                put("id",rechargeId);put("name","صندوق شراء رصيد الخدمات");put("account_type","CASH_BOX")
                put("currency_code","SYP");put("opening_balance_minor",0L);put("is_active",1);put("created_at",timestamp())
            }
            db.insertOrThrow("cash_accounts",null,values)
        }
        if(!validConfigured || configuredRecharge!=rechargeId) {
            val values=ContentValues().apply {
                put("setting_key","service_recharge_cash_account_id");put("setting_value",rechargeId);put("updated_at",timestamp())
            }
            db.insertWithOnConflict("app_settings",null,values,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
        setSystemRole(db,"SERVICE_RECHARGE",rechargeId)
    }

    private fun ensureRoleAccount(
        db:android.database.sqlite.SQLiteDatabase,role:String,systemId:String,defaultName:String,aliases:List<String>
    ):String {
        val linked=db.rawQuery("SELECT account_id FROM system_cash_account_roles WHERE role_key=?",arrayOf(role)).use {
            if(it.moveToFirst())it.getString(0)else null
        }
        if(linked!=null) {
            val valid=db.rawQuery("SELECT 1 FROM cash_accounts WHERE id=? AND account_type='CASH_BOX' AND currency_code='SYP' AND is_active=1",arrayOf(linked)).use{it.moveToFirst()}
            require(valid) { "الحساب الأساسي المرتبط بالدور $role غير نشط أو تغير نوعه" }
            return linked
        }
        val placeholders=aliases.joinToString(","){"?"}
        val existing=db.rawQuery(
            "SELECT id FROM cash_accounts WHERE account_type='CASH_BOX' AND currency_code='SYP' AND is_active=1 AND lower(trim(name)) IN ($placeholders) ORDER BY created_at,id LIMIT 1",
            aliases.map{it.lowercase(Locale.ROOT)}.toTypedArray()
        ).use { if(it.moveToFirst())it.getString(0)else null }
        var accountId=existing
        if(accountId==null) {
            accountId=systemId
            if(db.rawQuery("SELECT 1 FROM cash_accounts WHERE id=?",arrayOf(accountId)).use{it.moveToFirst()}) {
                accountId=UUID.randomUUID().toString()
            }
            val values=ContentValues().apply {
                put("id",accountId);put("name",defaultName);put("account_type","CASH_BOX")
                put("currency_code","SYP");put("opening_balance_minor",0L);put("is_active",1);put("created_at",timestamp())
            }
            db.insertOrThrow("cash_accounts",null,values)
        }
        setSystemRole(db,role,accountId)
        return accountId
    }

    private fun setSystemRole(db:android.database.sqlite.SQLiteDatabase,role:String,accountId:String) {
        val current=db.rawQuery("SELECT account_id FROM system_cash_account_roles WHERE role_key=?",arrayOf(role)).use {
            if(it.moveToFirst())it.getString(0)else null
        }
        if(current==accountId)return
        val values=ContentValues().apply{put("role_key",role);put("account_id",accountId);put("linked_at",timestamp())}
        db.insertWithOnConflict("system_cash_account_roles",null,values,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun currentDayPrimaryDrawer(): CashAccountSummary? {
        ensureSystemCashAccounts()
        val date = operatingDate()
        return database.readableDatabase.rawQuery(
            """SELECT a.id,a.name,a.account_type,a.currency_code,a.opening_balance_minor,a.is_active
               FROM daily_cash_sessions s JOIN cash_accounts a ON a.id=s.primary_cash_account_id
               WHERE s.operating_date=? AND a.is_active=1
                 AND a.account_type='CASH_BOX' AND a.currency_code='SYP' LIMIT 1""".trimIndent(),
            arrayOf(date)
        ).use { c ->
            if (!c.moveToFirst()) null else CashAccountSummary(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                balance(c.getString(0), c.getLong(4)), c.getInt(5) == 1
            )
        }
    }

    fun setCurrentDayPrimaryDrawer(actorRole: String, actorUserId: String, accountId: String) {
        ensureSystemCashAccounts()
        require(actorRole == "ADMIN" && actorUserId.isNotBlank()) {
            "تعيين الدرج اليومي الرئيسي متاح للمدير فقط"
        }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            PermissionPolicy.require(db, actorUserId, PermissionPolicy.DAILY_CASH_MANAGE)
            val accountName = db.rawQuery(
                "SELECT name FROM cash_accounts WHERE id=? AND account_type='CASH_BOX' AND currency_code='SYP' AND is_active=1",
                arrayOf(accountId)
            ).use {
                require(it.moveToFirst()) { "اختر صندوقاً نقدياً نشطاً بالليرة السورية" }
                it.getString(0)
            }
            val date = operatingDate()
            val isSupplierRolloverAccount = db.rawQuery(
                "SELECT 1 FROM daily_supplier_rollovers WHERE operating_date=? AND account_id=?",
                arrayOf(date, accountId)
            ).use { it.moveToFirst() }
            require(!isSupplierRolloverAccount) {
                "هذا الصندوق مخصص للنقد المدور للموردين اليوم ولا يمكن تعيينه كدرج المبيعات والتحصيل"
            }
            val isServiceRechargeAccount = db.rawQuery(
                "SELECT 1 FROM app_settings WHERE setting_key='service_recharge_cash_account_id' AND setting_value=?",
                arrayOf(accountId)
            ).use { it.moveToFirst() }
            val isSupplierDefaultAccount = db.rawQuery(
                "SELECT 1 FROM system_cash_account_roles WHERE role_key='SUPPLIER_ROLLOVER' AND account_id=?",
                arrayOf(accountId)
            ).use { it.moveToFirst() }
            require(!isServiceRechargeAccount) {
                "هذا الصندوق مخصص لشراء رصيد الخدمات ولا يمكن تعيينه كدرج المبيعات والتحصيل"
            }
            require(!isSupplierDefaultAccount) {
                "هذا الصندوق مخصص للنقد المدور للموردين ولا يمكن تعيينه كدرج المبيعات والتحصيل"
            }
            val before = db.rawQuery(
                "SELECT primary_cash_account_id FROM daily_cash_sessions WHERE operating_date=?",
                arrayOf(date)
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            val now = timestamp()
            if (before == null) {
                val values = ContentValues().apply {
                    put("operating_date", date)
                    put("primary_cash_account_id", accountId)
                    put("created_by", actorUserId)
                    put("created_at", now)
                }
                db.insertOrThrow("daily_cash_sessions", null, values)
            } else {
                val values = ContentValues().apply {
                    put("primary_cash_account_id", accountId)
                    put("created_by", actorUserId)
                    put("created_at", now)
                }
                require(db.update("daily_cash_sessions", values, "operating_date=?", arrayOf(date)) == 1) {
                    "تعذر تحديث درج اليوم الرئيسي"
                }
            }
            val audit = ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("user_id", actorUserId)
                put("action", "SET_DAILY_PRIMARY_DRAWER")
                put("entity_type", "DAILY_CASH_SESSION")
                put("entity_id", date)
                put("before_json", before?.let { "{\"primary_cash_account_id\":\"$it\"}" })
                put("after_json", "{\"primary_cash_account_id\":\"" + accountId + "\",\"account_name\":\"" + jsonEscape(accountName) + "\"}")
                put("created_at", now)
            }
            db.insertOrThrow("audit_log", null, audit)
            setSystemRole(db,"DAILY_DRAWER",accountId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun operatingDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.SimpleTimeZone(3 * 60 * 60 * 1000, "UTC+03:00") }.format(Date())

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    fun listAll(): List<CashAccountSummary> {
        ensureSystemCashAccounts()
        return listAccounts(false)
    }

    private fun listAccounts(activeOnly: Boolean): List<CashAccountSummary> {
        val result = mutableListOf<CashAccountSummary>()
        val where = if (activeOnly) "WHERE is_active=1" else ""
        database.readableDatabase.rawQuery(
            """SELECT id,name,account_type,currency_code,opening_balance_minor,is_active
               FROM cash_accounts $where ORDER BY name COLLATE NOCASE""".trimIndent(), null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                result.add(CashAccountSummary(
                    id, c.getString(1), c.getString(2), c.getString(3),
                    balance(id, c.getLong(4)), c.getInt(5) == 1
                ))
            }
        }
        return result
    }

    fun create(
        actorUserId: String, name: String, accountType: String, currencyCode: String,
        openingBalanceMinor: Long = 0L
    ): CashAccountSummary {
        val cleanName = name.trim()
        val currency = currencyCode.trim().uppercase(Locale.US)
        require(cleanName.isNotEmpty()) { "اسم الحساب مطلوب" }
        require(accountType in setOf("CASH_BOX", "WALLET", "BANK", "OTHER")) {
            "نوع الحساب غير صالح"
        }
        require(currency in setOf("USD", "SYP")) { "العملة يجب أن تكون USD أو SYP" }
        require(openingBalanceMinor >= 0L) { "الرصيد الافتتاحي لا يمكن أن يكون سالباً" }
        val db = database.writableDatabase
        requireManager(db, actorUserId)
        val id = UUID.randomUUID().toString()
        val now = timestamp()
        db.beginTransaction()
        try {
            requireManager(db, actorUserId)
            val values = ContentValues().apply {
                put("id", id); put("name", cleanName); put("account_type", accountType)
                put("currency_code", currency); put("opening_balance_minor", openingBalanceMinor)
                put("is_active", 1); put("created_at", now)
            }
            db.insertOrThrow("cash_accounts", null, values)
            val after = """{"name":"${jsonEscape(cleanName)}","account_type":"$accountType","currency_code":"$currency","opening_balance_minor":$openingBalanceMinor}"""
            val audit = ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("user_id", actorUserId)
                put("action", "CREATE"); put("entity_type", "CASH_ACCOUNT"); put("entity_id", id)
                put("after_json", after); put("created_at", now)
            }
            db.insertOrThrow("audit_log", null, audit)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return CashAccountSummary(id, cleanName, accountType, currency, openingBalanceMinor, true)
    }

    fun hasTransactions(accountId: String): Boolean =
        database.readableDatabase.rawQuery(
            """SELECT 1 FROM cash_transactions WHERE account_id=?
               UNION ALL
               SELECT 1 FROM cash_accounts WHERE id=? AND opening_balance_minor<>0
               UNION ALL
               SELECT 1 FROM service_wallet_account_links WHERE account_id=?
               UNION ALL
               SELECT 1 FROM system_cash_account_roles WHERE account_id=?
               LIMIT 1""".trimIndent(), arrayOf(accountId, accountId, accountId, accountId)
        ).use { it.moveToFirst() }

    fun updateDetails(
        actorRole: String, actorUserId: String, accountId: String,
        name: String, accountType: String, currencyCode: String
    ) {
        require(actorRole == "ADMIN") { "تعديل الحسابات المالية متاح للمدير فقط" }
        require(actorUserId.isNotBlank()) { "جلسة المدير غير صالحة" }
        val cleanName = name.trim()
        val currency = currencyCode.trim().uppercase(Locale.US)
        require(cleanName.isNotEmpty()) { "اسم الحساب مطلوب" }
        require(accountType in setOf("CASH_BOX", "WALLET", "BANK", "OTHER")) { "نوع الحساب غير صالح" }
        require(currency in setOf("USD", "SYP")) { "العملة يجب أن تكون USD أو SYP" }
        val db = database.writableDatabase
        PermissionPolicy.require(db, actorUserId, PermissionPolicy.ACCOUNTS_MANAGE)
        db.beginTransaction()
        try {
            val old = db.rawQuery(
                "SELECT name,account_type,currency_code FROM cash_accounts WHERE id=?",
                arrayOf(accountId)
            ).use {
                require(it.moveToFirst()) { "الحساب المالي غير موجود" }
                Triple(it.getString(0), it.getString(1), it.getString(2))
            }
            val hasHistory = db.rawQuery(
                "SELECT 1 FROM cash_transactions WHERE account_id=? LIMIT 1", arrayOf(accountId)
            ).use { it.moveToFirst() } || db.rawQuery(
                "SELECT opening_balance_minor FROM cash_accounts WHERE id=?", arrayOf(accountId)
            ).use { it.moveToFirst() && it.getLong(0) != 0L } || db.rawQuery(
                "SELECT 1 FROM service_wallet_account_links WHERE account_id=? LIMIT 1", arrayOf(accountId)
            ).use { it.moveToFirst() } || db.rawQuery(
                "SELECT 1 FROM system_cash_account_roles WHERE account_id=? LIMIT 1", arrayOf(accountId)
            ).use { it.moveToFirst() }
            if (hasHistory) {
                require(accountType == old.second && currency == old.third) {
                    "لا يمكن تغيير نوع الحساب أو عملته بعد تسجيل حركات عليه؛ يمكن تعديل الاسم فقط حفاظاً على سلامة السجلات"
                }
            }
            val now = timestamp()
            val values = ContentValues().apply {
                put("name", cleanName); put("account_type", accountType); put("currency_code", currency)
            }
            require(db.update("cash_accounts", values, "id=?", arrayOf(accountId)) == 1) {
                "تعذر تعديل الحساب المالي"
            }
            val beforeJson = "{\"name\":" + jsonString(old.first) + ",\"type\":" +
                jsonString(old.second) + ",\"currency\":" + jsonString(old.third) + "}"
            val afterJson = "{\"name\":" + jsonString(cleanName) + ",\"type\":" +
                jsonString(accountType) + ",\"currency\":" + jsonString(currency) + "}"
            val audit = ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("user_id", actorUserId)
                put("action", "UPDATE"); put("entity_type", "CASH_ACCOUNT"); put("entity_id", accountId)
                put("before_json", beforeJson); put("after_json", afterJson); put("created_at", now)
            }
            db.insertOrThrow("audit_log", null, audit)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun requireManager(db: android.database.sqlite.SQLiteDatabase, actorUserId: String) {
        PermissionPolicy.require(db, actorUserId, PermissionPolicy.ACCOUNTS_MANAGE)
    }

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\""

    fun balance(accountId: String): Long {
        val opening = database.readableDatabase.rawQuery(
            "SELECT opening_balance_minor FROM cash_accounts WHERE id=?", arrayOf(accountId)
        ).use {
            require(it.moveToFirst()) { "الحساب المالي غير موجود" }
            it.getLong(0)
        }
        return balance(accountId, opening)
    }

    private fun balance(accountId: String, opening: Long): Long {
        val movement = database.readableDatabase.rawQuery(
            """SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor
                       WHEN direction='OUT' THEN -amount_minor ELSE 0 END),0)
               FROM cash_transactions WHERE account_id=?""".trimIndent(), arrayOf(accountId)
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }
        return Math.addExact(opening, movement)
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
}
