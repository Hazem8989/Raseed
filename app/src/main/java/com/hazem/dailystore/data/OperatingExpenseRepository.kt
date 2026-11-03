package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class ExpenseCategory(val id: String, val name: String, val isActive: Boolean)
data class OperatingExpense(
    val id: String, val date: String, val categoryId: String, val categoryName: String,
    val accountId: String, val accountName: String, val amountMinor: Long,
    val currencyCode: String, val amountUsdCents: Long,
    val reference: String?, val notes: String?, val createdBy: String, val createdAt: String
)
data class OperatingExpenseResult(
    val expenseId: String, val cashTransactionId: String, val amountMinor: Long,
    val currencyCode: String, val amountUsdCents: Long, val replayed: Boolean
)

/**
 * Immutable operating-expense postings. Each expense and its cash outflow are
 * committed atomically, with the exchange rate captured at posting time.
 */
class OperatingExpenseRepository(context: Context) {
    private val context = context.applicationContext
    private val database = AppDatabase.getInstance(context)

    fun listCategories(activeOnly: Boolean = true): List<ExpenseCategory> {
        val result=mutableListOf<ExpenseCategory>()
        val where=if(activeOnly) "WHERE is_active=1" else ""
        database.readableDatabase.rawQuery(
            "SELECT id,name,is_active FROM expense_categories $where ORDER BY name COLLATE NOCASE",null
        ).use { c -> while(c.moveToNext()) result.add(ExpenseCategory(c.getString(0),c.getString(1),c.getInt(2)==1)) }
        return result
    }

    fun createCategory(actorUserId: String, name: String): ExpenseCategory {
        val clean=name.trim()
        require(clean.isNotEmpty() && clean.length<=80) { "اسم تصنيف المصروف مطلوب وبحد أقصى 80 حرفاً" }
        val db=database.writableDatabase
        db.beginTransaction()
        try {
            PermissionPolicy.require(db,actorUserId,PermissionPolicy.EXPENSES_MANAGE)
            val id=UUID.randomUUID().toString()
            val now=timestamp()
            db.insertOrThrow("expense_categories",null,ContentValues().apply {
                put("id",id); put("name",clean); put("is_active",1)
                put("created_by",actorUserId); put("created_at",now)
            })
            db.insertOrThrow("audit_log",null,ContentValues().apply {
                put("id",UUID.randomUUID().toString()); put("user_id",actorUserId)
                put("action","EXPENSE_CATEGORY_CREATED"); put("entity_type","EXPENSE_CATEGORY")
                put("entity_id",id); put("after_json",JSONObject().put("name",clean).toString())
                put("created_at",now)
            })
            db.setTransactionSuccessful()
            return ExpenseCategory(id,clean,true)
        } finally { db.endTransaction() }
    }

    fun setCategoryActive(actorUserId: String, categoryId: String, active: Boolean) {
        val db=database.writableDatabase
        db.beginTransaction()
        try {
            PermissionPolicy.require(db,actorUserId,PermissionPolicy.EXPENSES_MANAGE)
            val changed=db.update("expense_categories",ContentValues().apply {
                put("is_active",if(active) 1 else 0)
            },"id=?",arrayOf(categoryId))
            require(changed==1) { "تصنيف المصروف غير موجود" }
            db.insertOrThrow("audit_log",null,ContentValues().apply {
                put("id",UUID.randomUUID().toString()); put("user_id",actorUserId)
                put("action",if(active) "EXPENSE_CATEGORY_ACTIVATED" else "EXPENSE_CATEGORY_DEACTIVATED")
                put("entity_type","EXPENSE_CATEGORY"); put("entity_id",categoryId)
                put("after_json",JSONObject().put("is_active",active).toString())
                put("created_at",timestamp())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun postExpense(
        actorUserId: String, operationId: String, categoryId: String, cashAccountId: String,
        amountMinor: Long, expenseDate: String, reference: String? = null, notes: String? = null
    ): OperatingExpenseResult {
        require(actorUserId.isNotBlank() && operationId.isNotBlank() &&
            categoryId.isNotBlank() && cashAccountId.isNotBlank()) { "بيانات المصروف غير مكتملة" }
        require(amountMinor>0L) { "قيمة المصروف يجب أن تكون أكبر من صفر" }
        require(expenseDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) { "تاريخ المصروف غير صالح" }
        val cleanReference=reference?.trim()?.takeIf { it.isNotEmpty() }
        val cleanNotes=notes?.trim()?.takeIf { it.isNotEmpty() }
        val payloadHash=hash(listOf(categoryId,cashAccountId,amountMinor.toString(),expenseDate,
            cleanReference.orEmpty(),cleanNotes.orEmpty()).joinToString("|"))
        val db=database.writableDatabase
        db.beginTransaction()
        try {
            val localRole = db.rawQuery(
                "SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'", null
            ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (localRole == "SECONDARY") {
                PermissionPolicy.requireAssigned(db, actorUserId, PermissionPolicy.EXPENSES_MANAGE)
                require(db.rawQuery(
                    "SELECT 1 FROM expense_categories WHERE id=? AND is_active=1",
                    arrayOf(categoryId)
                ).use { it.moveToFirst() }) { "تصنيف المصروف غير موجود أو موقوف على هذا الجهاز" }
                val account = db.rawQuery(
                    "SELECT account_type,currency_code,is_active FROM cash_accounts WHERE id=?",
                    arrayOf(cashAccountId)
                ).use { c ->
                    require(c.moveToFirst() && c.getInt(2) == 1) { "الحساب المالي غير موجود أو موقوف" }
                    c.getString(0) to c.getString(1)
                }
                require(account.first in setOf("CASH_BOX", "WALLET") && account.second in setOf("USD", "SYP")) {
                    "نوع أو عملة حساب المصروف غير مدعومين"
                }
                require(!db.rawQuery(
                    "SELECT 1 FROM system_cash_account_roles WHERE role_key='SUPPLIER_ROLLOVER' AND account_id=?",
                    arrayOf(cashAccountId)
                ).use { it.moveToFirst() }) { "لا يمكن تسجيل مصروف من مخصص دفعات الموردين اليومي" }
                val rate = currentRate(db)
                if (account.second == "SYP") require(rate > 0L) {
                    "يجب تحديد سعر الصرف قبل تسجيل مصروف بالليرة السورية"
                }
                val amountUsd = if (account.second == "USD") amountMinor else CurrencyConverter.sypToUsdCents(amountMinor, rate)
                val sync = SyncProtocolRepository(context)
                val priorPayload = db.rawQuery(
                    "SELECT payload_json FROM sync_operation_envelopes WHERE operation_id=?",
                    arrayOf(operationId)
                ).use { c -> if (c.moveToFirst()) JSONObject(c.getString(0)) else null }
                val occurredAt = priorPayload?.optString("occurred_at")
                    ?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) } ?: timestamp()
                val eventDate = priorPayload?.optString("expense_date")
                    ?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: expenseDate
                val movementDate = priorPayload?.optString("operating_date")
                    ?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                    ?: StoreTime.operatingDate(db, occurredAt)
                val recordedRate = priorPayload?.optLong("exchange_rate_syp_per_usd_milli", rate) ?: rate
                val recordedUsd = if (account.second == "USD") amountMinor else CurrencyConverter.sypToUsdCents(amountMinor, recordedRate)
                val expenseId = "pending-expense-$operationId"
                val cashId = "pending-cash-$operationId"
                val payload = JSONObject()
                    .put("expense_id", expenseId).put("cash_transaction_id", cashId)
                    .put("category_id", categoryId).put("cash_account_id", cashAccountId)
                    .put("cash_account_type", account.first).put("cash_account_currency_code", account.second)
                    .put("amount_minor", amountMinor).put("currency_code", account.second)
                    .put("exchange_rate_syp_per_usd_milli", recordedRate)
                    .put("amount_usd_cents", recordedUsd).put("expense_date", eventDate)
                    .put("operating_date", movementDate).put("occurred_at", occurredAt)
                    .put("reference", cleanReference ?: JSONObject.NULL)
                    .put("notes", cleanNotes ?: JSONObject.NULL)
                sync.enqueueSecondaryCommand(
                    db, actorUserId, operationId, "POST_OPERATING_EXPENSE",
                    "OPERATING_EXPENSE", expenseId, payload.toString()
                )
                db.setTransactionSuccessful()
                return OperatingExpenseResult(expenseId, cashId, amountMinor, account.second, recordedUsd, false)
            }
            PermissionPolicy.require(db,actorUserId,PermissionPolicy.EXPENSES_MANAGE)
            val existing=db.rawQuery(
                "SELECT operation_type,payload_hash,result_reference FROM operation_registry WHERE operation_id=?",
                arrayOf(operationId)
            ).use { c ->
                if(c.moveToFirst()) Triple(c.getString(0),c.getString(1),if(c.isNull(2)) null else c.getString(2)) else null
            }
            if(existing!=null) {
                require(existing.first=="POST_OPERATING_EXPENSE" && existing.second==payloadHash) {
                    "معرّف العملية مستخدم لمصروف مختلف أو بيانات مختلفة"
                }
                val result=loadResult(db,existing.third ?: error("مرجع المصروف السابق مفقود"),true)
                db.setTransactionSuccessful()
                return result
            }

            require(db.rawQuery("SELECT 1 FROM expense_categories WHERE id=? AND is_active=1",
                arrayOf(categoryId)).use { it.moveToFirst() }) { "تصنيف المصروف غير موجود أو موقوف" }
            val account=db.rawQuery(
                "SELECT currency_code,is_active,opening_balance_minor FROM cash_accounts WHERE id=?",
                arrayOf(cashAccountId)
            ).use { c ->
                require(c.moveToFirst() && c.getInt(1)==1) { "الحساب المالي غير موجود أو موقوف" }
                Triple(c.getString(0),c.getInt(1),c.getLong(2))
            }
            require(!db.rawQuery(
                "SELECT 1 FROM system_cash_account_roles WHERE role_key='SUPPLIER_ROLLOVER' AND account_id=?",
                arrayOf(cashAccountId)
            ).use { it.moveToFirst() }) { "لا يمكن تسجيل مصروف من مخصص دفعات الموردين اليومي" }
            val currency=account.first
            require(currency=="USD" || currency=="SYP") { "عملة الحساب غير مدعومة لتسجيل المصروف" }
            val rate=currentRate(db)
            if(currency=="SYP") require(rate>0L) { "يجب تحديد سعر الصرف قبل تسجيل مصروف بالليرة السورية" }
            val amountUsd=if(currency=="USD") amountMinor else CurrencyConverter.sypToUsdCents(amountMinor,rate)
            val balance=cashBalance(db,cashAccountId,account.third)
            require(balance>=amountMinor) {
                "رصيد الحساب لا يكفي لتسجيل المصروف. الرصيد المتاح: $balance"
            }

            val now=timestamp()
            val expenseId=UUID.randomUUID().toString()
            val cashId=UUID.randomUUID().toString()
            val cashOperation=operationId+":CASH"
            db.insertOrThrow("cash_transactions",null,ContentValues().apply {
                put("id",cashId); put("account_id",cashAccountId); put("direction","OUT")
                put("amount_minor",amountMinor); put("currency_code",currency)
                if(rate>0L) put("exchange_rate_syp_per_usd_milli",rate) else putNull("exchange_rate_syp_per_usd_milli")
                put("transaction_type","OPERATING_EXPENSE"); put("source_type","OPERATING_EXPENSE")
                put("source_id",expenseId); put("operation_id",cashOperation)
                put("description","مصروف تشغيلي: "+(cleanReference ?: cleanNotes ?: categoryId))
                put("created_by",actorUserId); put("created_at",now); put("operating_date",expenseDate)
            })
            db.insertOrThrow("operating_expenses",null,ContentValues().apply {
                put("id",expenseId); put("category_id",categoryId); put("cash_account_id",cashAccountId)
                put("cash_transaction_id",cashId); put("amount_minor",amountMinor); put("currency_code",currency)
                if(rate>0L) put("exchange_rate_syp_per_usd_milli",rate) else putNull("exchange_rate_syp_per_usd_milli")
                put("amount_usd_cents",amountUsd); put("expense_date",expenseDate)
                if(cleanReference==null) putNull("reference") else put("reference",cleanReference)
                if(cleanNotes==null) putNull("notes") else put("notes",cleanNotes)
                put("created_by",actorUserId); put("operation_id",operationId); put("created_at",now)
            })
            db.insertOrThrow("operation_registry",null,ContentValues().apply {
                put("operation_id",operationId); put("operation_type","POST_OPERATING_EXPENSE")
                put("payload_hash",payloadHash); put("status","COMPLETED")
                put("result_reference",expenseId); put("created_at",now); put("completed_at",now)
            })
            db.insertOrThrow("audit_log",null,ContentValues().apply {
                put("id",UUID.randomUUID().toString()); put("user_id",actorUserId)
                put("action","OPERATING_EXPENSE_POSTED"); put("entity_type","OPERATING_EXPENSE")
                put("entity_id",expenseId)
                put("after_json",JSONObject().put("category_id",categoryId).put("cash_account_id",cashAccountId)
                    .put("amount_minor",amountMinor).put("currency_code",currency)
                    .put("amount_usd_cents",amountUsd).put("expense_date",expenseDate)
                    .put("reference",cleanReference).put("notes",cleanNotes).toString())
                put("created_at",now)
            })
            SyncProtocolRepository(context).enqueueLocalOperation(
                db, actorUserId, operationId, "POST_OPERATING_EXPENSE", "OPERATING_EXPENSE",
                expenseId, "CREATE",
                JSONObject().put("expense_id", expenseId).put("cash_transaction_id", cashId)
                    .put("category_id", categoryId).put("cash_account_id", cashAccountId)
                    .put("amount_minor", amountMinor).put("currency_code", currency)
                    .put("exchange_rate_syp_per_usd_milli", rate).put("amount_usd_cents", amountUsd)
                    .put("expense_date", expenseDate).put("reference", cleanReference)
                    .put("notes", cleanNotes).put("created_at", now).toString()
            )
            db.setTransactionSuccessful()
            return OperatingExpenseResult(expenseId,cashId,amountMinor,currency,amountUsd,false)
        } finally { db.endTransaction() }
    }

    fun listExpenses(fromDate: String? = null, throughDate: String? = null, limit: Int = 300): List<OperatingExpense> {
        require((fromDate==null)==(throughDate==null)) { "يجب تحديد تاريخي البداية والنهاية معاً" }
        if(fromDate!=null) require(fromDate<=throughDate!!) { "نطاق التاريخ غير صالح" }
        require(limit in 1..1000) { "عدد السجلات المطلوب غير صالح" }
        val where=if(fromDate==null) "" else "WHERE e.expense_date>=? AND e.expense_date<=?"
        val args=if(fromDate==null) arrayOf(limit.toString()) else arrayOf(fromDate,throughDate!!,limit.toString())
        val result=mutableListOf<OperatingExpense>()
        database.readableDatabase.rawQuery(
            """SELECT e.id,e.expense_date,e.category_id,c.name,e.cash_account_id,a.name,
                      e.amount_minor,e.currency_code,e.amount_usd_cents,e.reference,e.notes,
                      e.created_by,e.created_at
               FROM operating_expenses e JOIN expense_categories c ON c.id=e.category_id
               JOIN cash_accounts a ON a.id=e.cash_account_id
               $where ORDER BY e.expense_date DESC,e.created_at DESC LIMIT ?""".trimIndent(),args
        ).use { c -> while(c.moveToNext()) result.add(OperatingExpense(
            c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),
            c.getLong(6),c.getString(7),c.getLong(8),if(c.isNull(9)) null else c.getString(9),
            if(c.isNull(10)) null else c.getString(10),c.getString(11),c.getString(12)
        )) }
        return result
    }

    fun totalUsdCents(fromDate: String? = null, throughDate: String? = null): Long {
        require((fromDate==null)==(throughDate==null)) { "يجب تحديد تاريخي البداية والنهاية معاً" }
        if(fromDate!=null) require(fromDate<=throughDate!!) { "نطاق التاريخ غير صالح" }
        val where=if(fromDate==null) "" else "WHERE expense_date>=? AND expense_date<=?"
        val args=if(fromDate==null) null else arrayOf(fromDate,throughDate!!)
        return database.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(amount_usd_cents),0) FROM operating_expenses $where",args
        ).use { it.moveToFirst();it.getLong(0) }
    }

    private fun loadResult(db: SQLiteDatabase,id: String,replayed: Boolean): OperatingExpenseResult =
        db.rawQuery(
            "SELECT cash_transaction_id,amount_minor,currency_code,amount_usd_cents FROM operating_expenses WHERE id=?",
            arrayOf(id)
        ).use { c ->
            require(c.moveToFirst()) { "المصروف السابق غير موجود" }
            OperatingExpenseResult(id,c.getString(0),c.getLong(1),c.getString(2),c.getLong(3),replayed)
        }

    private fun currentRate(db: SQLiteDatabase): Long =
        db.rawQuery(
            "SELECT rate_syp_per_usd_milli FROM exchange_rates WHERE currency_code='USD' ORDER BY effective_at DESC,created_at DESC LIMIT 1",
            null
        ).use { if(it.moveToFirst()) it.getLong(0) else 0L }

    private fun cashBalance(db: SQLiteDatabase,accountId: String,opening: Long): Long {
        val movements=db.rawQuery(
            """SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor
                       WHEN direction='OUT' THEN -amount_minor ELSE 0 END),0)
               FROM cash_transactions WHERE account_id=?""".trimIndent(),arrayOf(accountId)
        ).use { it.moveToFirst();it.getLong(0) }
        return Math.addExact(opening,movements)
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US)
        .apply { timeZone=TimeZone.getTimeZone("UTC") }.format(Date())
}
