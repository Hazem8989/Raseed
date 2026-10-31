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

    fun listActive(): List<CashAccountSummary> = listAccounts(true)

    fun currentDayPrimaryDrawer(): CashAccountSummary? {
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
        require(actorRole == "ADMIN" && actorUserId.isNotBlank()) {
            "تعيين الدرج اليومي الرئيسي متاح للمدير فقط"
        }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val managerExists = db.rawQuery(
                "SELECT 1 FROM users WHERE id=? AND role_key='ADMIN' AND is_active=1",
                arrayOf(actorUserId)
            ).use { it.moveToFirst() }
            require(managerExists) { "جلسة المدير غير صالحة" }
            val accountName = db.rawQuery(
                "SELECT name FROM cash_accounts WHERE id=? AND account_type='CASH_BOX' AND currency_code='SYP' AND is_active=1",
                arrayOf(accountId)
            ).use {
                require(it.moveToFirst()) { "اختر صندوقاً نقدياً نشطاً بالليرة السورية" }
                it.getString(0)
            }
            val date = operatingDate()
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
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun operatingDate(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    fun listAll(): List<CashAccountSummary> = listAccounts(false)

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
        name: String, accountType: String, currencyCode: String, openingBalanceMinor: Long = 0L
    ): CashAccountSummary {
        val cleanName = name.trim()
        val currency = currencyCode.trim().uppercase(Locale.US)
        require(cleanName.isNotEmpty()) { "اسم الحساب مطلوب" }
        require(accountType in setOf("CASH_BOX", "WALLET", "BANK", "OTHER")) {
            "نوع الحساب غير صالح"
        }
        require(currency in setOf("USD", "SYP")) { "العملة يجب أن تكون USD أو SYP" }
        require(openingBalanceMinor >= 0L) { "الرصيد الافتتاحي لا يمكن أن يكون سالباً" }
        val id = UUID.randomUUID().toString()
        val now = timestamp()
        val values = ContentValues().apply {
            put("id", id); put("name", cleanName); put("account_type", accountType)
            put("currency_code", currency); put("opening_balance_minor", openingBalanceMinor)
            put("is_active", 1); put("created_at", now)
        }
        database.writableDatabase.insertOrThrow("cash_accounts", null, values)
        return CashAccountSummary(id, cleanName, accountType, currency, openingBalanceMinor, true)
    }

    fun hasTransactions(accountId: String): Boolean =
        database.readableDatabase.rawQuery(
            """SELECT 1 FROM cash_transactions WHERE account_id=?
               UNION ALL
               SELECT 1 FROM cash_accounts WHERE id=? AND opening_balance_minor<>0
               LIMIT 1""".trimIndent(), arrayOf(accountId, accountId)
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
        val managerExists = db.rawQuery(
            "SELECT 1 FROM users WHERE id=? AND role_key='ADMIN' AND is_active=1", arrayOf(actorUserId)
        ).use { it.moveToFirst() }
        require(managerExists) { "يجب تسجيل الدخول بحساب مدير نشط لتعديل الحسابات المالية" }
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
            ).use { it.moveToFirst() && it.getLong(0) != 0L }
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
