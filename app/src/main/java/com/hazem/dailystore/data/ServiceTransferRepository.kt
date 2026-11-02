package com.hazem.dailystore.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class ServiceWalletSummary(val serviceKey:String,val name:String,val balanceMinor:Long,val accountId:String)
data class ServiceTransferRecord(val id:String,val serviceType:String,val serviceName:String,val deliveredMinor:Long,val profitMinor:Long,val providerFeeMinor:Long,val customerChargeMinor:Long,val receiptAccountName:String,val receiptChannel:String,val createdAt:String,val status:String)
data class ServiceDailySummary(val serviceType:String,val serviceName:String,val operationCount:Int,val deliveredMinor:Long,val customerReceiptsMinor:Long,val profitMinor:Long,val providerFeeMinor:Long,val rechargeFeePaidMinor:Long,val rechargeReserveMinor:Long=deliveredMinor)
data class ServiceTransferResult(val transferId:String,val deliveredMinor:Long,val profitMinor:Long,val providerFeeMinor:Long,val customerChargeMinor:Long,val replayed:Boolean)

/** Each service wallet is one real cash-account wallet used for both provider float and electronic customer receipts. */
class ServiceTransferRepository(context:Context) {
    private val database=AppDatabase.getInstance(context)
    private val appContext=context.applicationContext
    companion object {
        const val SYRIATEL_SHARED="SYRIATEL_SHARED"
        const val SHAM_CASH="SHAM_CASH"
        const val SYRIATEL_BALANCE="SYRIATEL_BALANCE"
        const val SYRIATEL_CASH="SYRIATEL_CASH"
        const val SHAM_CASH_TRANSFER="SHAM_CASH"
    }

    fun ensureSystemAccounts() {
        CashAccountRepository(appContext).ensureSystemCashAccounts()
        val db=database.writableDatabase
        db.beginTransaction()
        try {
            ensureSystemAccounts(db)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun serviceAccountId(serviceKey:String):String {
        require(serviceKey==SYRIATEL_SHARED||serviceKey==SHAM_CASH) { "محفظة الخدمة غير معروفة" }
        ensureSystemAccounts()
        return serviceAccountId(database.readableDatabase,serviceKey)
    }

    fun listWallets():List<ServiceWalletSummary> {
        ensureSystemAccounts()
        val db=database.readableDatabase
        return listOf(SYRIATEL_SHARED,SHAM_CASH).map { key ->
            val id=serviceAccountId(db,key)
            val row=db.rawQuery("SELECT name FROM cash_accounts WHERE id=?",arrayOf(id)).use { it.moveToFirst();it.getString(0) }
            ServiceWalletSummary(key,row,walletBalance(db,key),id)
        }
    }

    fun dailySummary(date:String=today()):List<ServiceDailySummary> {
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))){"تاريخ الكشف غير صالح"}
        val out=mutableListOf<ServiceDailySummary>()
        database.readableDatabase.rawQuery(
            """SELECT service_type,SUM(operation_count),SUM(delivered_minor),SUM(customer_receipts_minor),SUM(profit_minor),SUM(provider_fee_minor)
               FROM (
                 SELECT service_type,COUNT(*) operation_count,COALESCE(SUM(amount_delivered_minor),0) delivered_minor,
                        COALESCE(SUM(customer_charge_minor),0) customer_receipts_minor,COALESCE(SUM(profit_minor),0) profit_minor,
                        COALESCE(SUM(provider_fee_minor),0) provider_fee_minor
                 FROM service_transfers WHERE substr(created_at,1,10)=? AND status='POSTED' GROUP BY service_type
                 UNION ALL
                 SELECT CASE service_key WHEN 'SYRIATEL_SHARED' THEN 'SYRIATEL_BALANCE' ELSE 'SHAM_CASH' END,
                        0,0,0,0,0 FROM service_wallet_transactions
                 WHERE transaction_type='RECHARGE' AND substr(created_at,1,10)=?
                 GROUP BY service_key
               ) GROUP BY service_type ORDER BY service_type""".trimIndent(),arrayOf(date,date)
        ).use { c -> while(c.moveToNext()) {
            val type=c.getString(0); val delivered=c.getLong(2)
            // Show actual fees paid on external recharge only; never add them to customer transfers.
            val rechargeServiceKey=when(type) {
                SYRIATEL_BALANCE -> SYRIATEL_SHARED
                SHAM_CASH_TRANSFER -> SHAM_CASH
                else -> null
            }
            val rechargeFeePaid=rechargeServiceKey?.let { key ->
                database.readableDatabase.rawQuery(
                    "SELECT COALESCE(SUM(fee_minor),0) FROM service_wallet_transactions WHERE service_key=? AND transaction_type='RECHARGE' AND substr(created_at,1,10)=?",
                    arrayOf(key,date)
                ).use { it.moveToFirst();it.getLong(0) }
            } ?: 0L
            out.add(ServiceDailySummary(type,serviceTypeName(type),c.getInt(1),delivered,c.getLong(3),c.getLong(4),c.getLong(5),rechargeFeePaid))
        } }
        return out
    }
    fun listTransfers(limit:Int=50):List<ServiceTransferRecord> {
        val out=mutableListOf<ServiceTransferRecord>()
        database.readableDatabase.rawQuery(
            """SELECT t.id,t.service_type,t.amount_delivered_minor,t.profit_minor,t.provider_fee_minor,
                      t.customer_charge_minor,a.name,t.receipt_channel,t.created_at,t.status
               FROM service_transfers t JOIN cash_accounts a ON a.id=t.receipt_account_id
               ORDER BY t.created_at DESC LIMIT ?""".trimIndent(),arrayOf(limit.coerceIn(1,200).toString())
        ).use { c -> while(c.moveToNext()) {
            val type=c.getString(1)
            out.add(ServiceTransferRecord(c.getString(0),type,serviceTypeName(type),c.getLong(2),c.getLong(3),
                c.getLong(4),c.getLong(5),c.getString(6),c.getString(7),c.getString(8),c.getString(9)))
        } }
        return out
    }

    fun setOpeningBalance(actor:String,serviceKey:String,amount:Long,note:String?=null) {
        require(amount>=0L) { "الرصيد لا يمكن أن يكون سالباً" }
        val accountId=serviceAccountId(serviceKey)
        DailyBalanceRepository(appContext).setWalletOpening(actor,accountId,amount,note)
    }

    fun rechargeAccountId():String? = database.readableDatabase.rawQuery("SELECT setting_value FROM app_settings WHERE setting_key='service_recharge_cash_account_id'",null).use { if(it.moveToFirst())it.getString(0) else null }

    fun setRechargeAccount(actor:String,accountId:String) {
        CashAccountRepository(appContext).ensureSystemCashAccounts()
        val db=database.writableDatabase;db.beginTransaction()
        try {
            requireManager(db,actor)
            val row=receiptAccount(db,accountId)
            require(row.first=="CASH_BOX"&&row.second=="SYP"&&row.third){"اختر صندوقاً نقدياً نشطاً بالليرة السورية"}
            val primary=db.rawQuery("SELECT primary_cash_account_id FROM daily_cash_sessions WHERE operating_date=?",arrayOf(today())).use{if(it.moveToFirst())it.getString(0)else null}
            require(primary!=accountId){"لا يمكن استخدام درج اليوم الرئيسي لشراء رصيد الخدمات"}
            val rollover=db.rawQuery("SELECT account_id FROM daily_supplier_rollovers WHERE operating_date=?",arrayOf(today())).use{if(it.moveToFirst())it.getString(0)else null}
            require(rollover!=accountId){"لا يمكن استخدام صندوق النقد المدور للموردين لشراء رصيد الخدمات"}
            val supplierRole=db.rawQuery("SELECT 1 FROM system_cash_account_roles WHERE role_key='SUPPLIER_ROLLOVER' AND account_id=?",arrayOf(accountId)).use{it.moveToFirst()}
            require(!supplierRole){"هذا الصندوق مخصص للنقد المدور للبضاعة"}
            val old=rechargeAccountId()
            val now=stamp()
            val values=ContentValues().apply{put("setting_key","service_recharge_cash_account_id");put("setting_value",accountId);put("updated_at",now)}
            if(old==null)db.insertOrThrow("app_settings",null,values) else db.update("app_settings",values,"setting_key='service_recharge_cash_account_id'",null)
            audit(db,actor,"SET_SERVICE_RECHARGE_CASH_ACCOUNT",accountId,old?.let{"{\"account_id\":\"$it\"}" },"{\"account_id\":\"$accountId\"}",now)
            val role=ContentValues().apply{put("role_key","SERVICE_RECHARGE");put("account_id",accountId);put("linked_at",now)}
            db.insertWithOnConflict("system_cash_account_roles",null,role,SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    /** Both service floats incur 2% only on actual external recharge; customer transfers incur no recharge fee. */
    fun recharge(actor:String,serviceKey:String,sourceAccountId:String,floatAmount:Long,operationId:String,reference:String?=null,note:String?=null):Long {
        require(floatAmount>0L&&operationId.isNotBlank()){"أدخل مبلغ شحن ومعرّف عملية صحيحين"}
        require(serviceKey==SYRIATEL_SHARED||serviceKey==SHAM_CASH){"محفظة الخدمة غير معروفة"}
        val fee=percent(floatAmount,2) // 2% is charged only when either service float is recharged externally.
        val debit=Math.addExact(floatAmount,fee)
        val db=database.writableDatabase;db.beginTransaction()
        try {
            requireManager(db,actor);ensureSystemAccounts(db)
            val payload=hash("$serviceKey|$sourceAccountId|$floatAmount|$reference|$note")
            val prior=db.rawQuery("SELECT service_key,amount_minor,payload_hash FROM service_wallet_transactions WHERE operation_id=?",arrayOf(operationId)).use{
                if(it.moveToFirst()) Triple(it.getString(0),it.getLong(1),it.getString(2)) else null
            }
            if(prior!=null) {
                require(prior.first==serviceKey&&prior.second==floatAmount&&prior.third==payload){"معرّف الشحن مستخدم لبيانات مختلفة"}
                db.setTransactionSuccessful();return fee
            }
            val account=receiptAccount(db,sourceAccountId)
            require(account.first=="CASH_BOX"&&account.second=="SYP"&&account.third&&rechargeAccountId()==sourceAccountId){"يجب اختيار صندوق شراء رصيد الخدمات المخصص من شاشة الصناديق والمحافظ"}
            require(cashBalance(db,sourceAccountId)>=debit){"رصيد صندوق شراء الرصيد لا يكفي لقيمة الشحن والعمولة"}
            val now=stamp()
            insertCashMovement(db,sourceAccountId,"OUT",debit,"SERVICE_RECHARGE","SERVICE_RECHARGE",operationId,operationId+":SOURCE",actor,
                "شراء رصيد ${serviceName(serviceKey)}"+if(fee>0L)" • عمولة شحن 2%: $fee ل.س" else "",now)
            insertWalletMovement(db,serviceKey,"IN",floatAmount,"RECHARGE",sourceAccountId,operationId,fee,note?:reference,actor,now,payload)
            audit(db,actor,"SERVICE_WALLET_RECHARGE",serviceKey,null,
                "{\"float_minor\":$floatAmount,\"recharge_fee_minor\":$fee,\"source_account_id\":\"${esc(sourceAccountId)}\",\"reference\":\"${esc(reference.orEmpty())}\"}",now)
            db.setTransactionSuccessful();return fee
        } finally {db.endTransaction()}
    }

    /** Syriatel balance and Syriatel Cash share one float; Sham Cash has its own float. */
    fun executeTransfer(actor:String,serviceType:String,deliveredMinor:Long,providerFeeMinor:Long,receiptAccountId:String,
                        operationId:String,reference:String?=null,note:String?=null):ServiceTransferResult {
        require(deliveredMinor>0L&&providerFeeMinor>=0L&&operationId.isNotBlank()){"تحقق من مبلغ التحويل ورسوم الشركة"}
        require(serviceType in listOf(SYRIATEL_BALANCE,SYRIATEL_CASH,SHAM_CASH_TRANSFER)){"نوع خدمة التحويل غير معروف"}
        if(serviceType==SHAM_CASH_TRANSFER) require(providerFeeMinor==0L){"لا توجد رسوم شركة على تحويل شام كاش وفق السياسة المعتمدة"}
        val serviceKey=if(serviceType==SHAM_CASH_TRANSFER)SHAM_CASH else SYRIATEL_SHARED
        val profitRate=when(serviceType){SYRIATEL_BALANCE->20L;SYRIATEL_CASH->10L;else->5L}
        val profit=percent(deliveredMinor,profitRate)
        val charge=Math.addExact(Math.addExact(deliveredMinor,profit),providerFeeMinor)
        val db=database.writableDatabase;db.beginTransaction()
        try {
            requireActiveUser(db,actor);ensureSystemAccounts(db)
            val payload=hash("$serviceType|$deliveredMinor|$providerFeeMinor|$receiptAccountId|$reference|$note")
            val old=db.rawQuery("SELECT id,service_type,amount_delivered_minor,profit_minor,provider_fee_minor,customer_charge_minor,payload_hash FROM service_transfers WHERE operation_id=?",arrayOf(operationId)).use{
                if(it.moveToFirst()) arrayOf(it.getString(0),it.getString(1),it.getLong(2).toString(),it.getLong(3).toString(),it.getLong(4).toString(),it.getLong(5).toString(),it.getString(6)) else null
            }
            if(old!=null) {
                require(old[1]==serviceType&&old[2]==deliveredMinor.toString()&&old[3]==profit.toString()&&old[4]==providerFeeMinor.toString()&&old[5]==charge.toString()&&old[6]==payload){"معرّف التحويل مستخدم لبيانات مختلفة"}
                db.setTransactionSuccessful()
                return ServiceTransferResult(old[0]!!,deliveredMinor,profit,providerFeeMinor,charge,true)
            }
            val account=receiptAccount(db,receiptAccountId)
            require(account.second=="SYP"&&account.third){"اختر حساب قبض نشطاً بالليرة السورية"}
            if(account.first=="CASH_BOX") {
                val primary=db.rawQuery("SELECT primary_cash_account_id FROM daily_cash_sessions WHERE operating_date=?",arrayOf(today())).use{if(it.moveToFirst())it.getString(0)else null}
                require(primary==receiptAccountId){"القبض النقدي يجب أن يذهب إلى درج اليوم الرئيسي"}
            } else require(account.first=="WALLET"){"حساب الاستلام يجب أن يكون درجاً نقدياً أو محفظة إلكترونية"}
            require(walletBalance(db,serviceKey)>=deliveredMinor){"رصيد ${serviceName(serviceKey)} غير كافٍ. المتاح: ${walletBalance(db,serviceKey)} ل.س"}
            val id=UUID.randomUUID().toString();val now=stamp()
            val v=ContentValues().apply {
                put("id",id);put("operation_id",operationId);put("service_type",serviceType);put("service_key",serviceKey)
                put("amount_delivered_minor",deliveredMinor);put("profit_minor",profit);put("provider_fee_minor",providerFeeMinor)
                put("customer_charge_minor",charge);put("receipt_account_id",receiptAccountId)
                put("receipt_channel",if(account.first=="WALLET")"ELECTRONIC" else "CASH")
                put("reference",reference);put("note",note);put("payload_hash",payload);put("created_by",actor)
                put("created_at",now);put("status","POSTED")
            }
            db.insertOrThrow("service_transfers",null,v)
            insertWalletMovement(db,serviceKey,"OUT",deliveredMinor,"TRANSFER_OUT",id,operationId,0L,note?:serviceTypeName(serviceType),actor,now)
            insertCashMovement(db,receiptAccountId,"IN",charge,"SERVICE_TRANSFER_RECEIPT","SERVICE_TRANSFER",id,operationId+":RECEIPT",actor,
                "مقبوض تحويل رصيد • ${serviceTypeName(serviceType)} • ربح $profit ل.س • رسوم الشركة $providerFeeMinor ل.س",now)
            audit(db,actor,"SERVICE_TRANSFER_POSTED",id,null,
                "{\"service_type\":\"$serviceType\",\"delivered_minor\":$deliveredMinor,\"profit_minor\":$profit,\"provider_fee_minor\":$providerFeeMinor,\"customer_charge_minor\":$charge,\"receipt_account_id\":\"${esc(receiptAccountId)}\"}",now)
            db.setTransactionSuccessful()
            return ServiceTransferResult(id,deliveredMinor,profit,providerFeeMinor,charge,false)
        } finally {db.endTransaction()}
    }

    /** Reverses the service float deduction and the full receipt without deleting history. */
    fun reverseTransfer(actor:String,transferId:String,operationId:String,reason:String) {
        require(operationId.isNotBlank()&&reason.isNotBlank()){"سبب العكس مطلوب"}
        val db=database.writableDatabase;db.beginTransaction()
        try {
            requireManager(db,actor)
            ensureSystemAccounts(db)
            val row=db.rawQuery("SELECT service_key,amount_delivered_minor,customer_charge_minor,receipt_account_id,status FROM service_transfers WHERE id=?",arrayOf(transferId)).use{
                require(it.moveToFirst()){"عملية التحويل غير موجودة"}
                arrayOf(it.getString(0),it.getLong(1).toString(),it.getLong(2).toString(),it.getString(3),it.getString(4))
            }
            require(row[4]=="POSTED"){"عملية التحويل معكوسة مسبقاً أو غير قابلة للعكس"}
            require(db.rawQuery("SELECT 1 FROM service_wallet_transactions WHERE operation_id=?",arrayOf(operationId)).use{it.moveToFirst()}.not()){"معرّف العكس مستخدم مسبقاً"}
            val receipt=row[3]!!;val charge=row[2]!!.toLong()
            require(cashBalance(db,receipt)>=charge){"لا يمكن عكس العملية: الرصيد المتبقي في حساب القبض أقل من المبلغ المقبوض"}
            val now=stamp()
            insertWalletMovement(db,row[0]!!,"IN",row[1]!!.toLong(),"TRANSFER_REVERSAL",transferId,operationId,0L,reason,actor,now)
            insertCashMovement(db,receipt,"OUT",charge,"SERVICE_TRANSFER_REVERSAL","SERVICE_TRANSFER_REVERSAL",transferId,operationId+":RECEIPT",actor,"عكس تحويل رصيد: $reason",now)
            val update=ContentValues().apply{put("status","REVERSED");put("reversed_at",now);put("reversed_by",actor);put("reversal_reason",reason)}
            db.update("service_transfers",update,"id=? AND status='POSTED'",arrayOf(transferId))
            audit(db,actor,"SERVICE_TRANSFER_REVERSED",transferId,"{\"status\":\"POSTED\"}","{\"status\":\"REVERSED\",\"reason\":\"${esc(reason)}\"}",now)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    private fun ensureWallets(db:SQLiteDatabase) {
        db.execSQL("INSERT OR IGNORE INTO service_wallets(service_key,name,created_at) VALUES(?,?,?)",arrayOf(SYRIATEL_SHARED,"رصيد سيرياتيل المشترك",stamp()))
        db.execSQL("INSERT OR IGNORE INTO service_wallets(service_key,name,created_at) VALUES(?,?,?)",arrayOf(SHAM_CASH,"رصيد شام كاش",stamp()))
    }

    private fun ensureSystemAccounts(db:SQLiteDatabase) {
        ensureWallets(db)
        ensureServiceAccountLink(db,SYRIATEL_SHARED,"system_service_syriatel","محفظة سيرياتيل كاش",
            listOf("محفظة سيرياتيل كاش","سيرياتيل كاش","محفظة سيرياتيل","رصيد سيرياتيل المشترك","رصيد سيرياتيل","محفظة رصيد سيرياتيل"))
        ensureServiceAccountLink(db,SHAM_CASH,"system_service_sham_cash","محفظة شام كاش",
            listOf("محفظة شام كاش","شام كاش","رصيد شام كاش","محفظة شام"))
        migrateLegacyWalletMovements(db,SYRIATEL_SHARED)
        migrateLegacyWalletMovements(db,SHAM_CASH)
    }

    private fun ensureServiceAccountLink(db:SQLiteDatabase,key:String,systemId:String,defaultName:String,aliases:List<String>) {
        val linked=db.rawQuery("SELECT account_id FROM service_wallet_account_links WHERE service_key=?",arrayOf(key)).use {
            if(it.moveToFirst()) it.getString(0) else null
        }
        if(linked!=null) {
            val valid=db.rawQuery("SELECT 1 FROM cash_accounts WHERE id=? AND account_type='WALLET' AND currency_code='SYP' AND is_active=1",arrayOf(linked)).use{it.moveToFirst()}
            require(valid) { "محفظة الخدمة المرتبطة غير نشطة أو تغير نوعها؛ راجع الحسابات المالية" }
            return
        }
        val placeholders=aliases.joinToString(",") { "?" }
        val existing=db.rawQuery(
            "SELECT id FROM cash_accounts WHERE account_type='WALLET' AND currency_code='SYP' AND is_active=1 AND lower(trim(name)) IN ($placeholders) ORDER BY created_at,id LIMIT 1",
            aliases.map{it.lowercase(Locale.ROOT)}.toTypedArray()
        ).use { if(it.moveToFirst()) it.getString(0) else null }
        var accountId=existing
        if(accountId==null) {
            accountId=systemId
            val collision=db.rawQuery("SELECT 1 FROM cash_accounts WHERE id=?",arrayOf(accountId)).use{it.moveToFirst()}
            if(collision) accountId=UUID.randomUUID().toString()
            val v=ContentValues().apply {
                put("id",accountId);put("name",defaultName);put("account_type","WALLET")
                put("currency_code","SYP");put("opening_balance_minor",0L);put("is_active",1);put("created_at",stamp())
            }
            db.insertOrThrow("cash_accounts",null,v)
        }
        val link=ContentValues().apply{put("service_key",key);put("account_id",accountId);put("linked_at",stamp())}
        db.insertOrThrow("service_wallet_account_links",null,link)
    }

    private fun serviceAccountId(db:SQLiteDatabase,key:String):String =
        db.rawQuery("SELECT account_id FROM service_wallet_account_links WHERE service_key=?",arrayOf(key)).use {
            require(it.moveToFirst()) { "محفظة الخدمة غير مربوطة بحساب مالي" }
            it.getString(0)
        }

    private fun migrateLegacyWalletMovements(db:SQLiteDatabase,key:String) {
        val accountId=serviceAccountId(db,key)
        val accountOpening=db.rawQuery("SELECT opening_balance_minor FROM cash_accounts WHERE id=?",arrayOf(accountId)).use{it.moveToFirst();it.getLong(0)}
        val hasManualDailyOpening=db.rawQuery(
            "SELECT 1 FROM cash_transactions WHERE account_id=? AND transaction_type IN ('WALLET_DAILY_OPENING_ADJUSTMENT','WALLET_TOP_UP') LIMIT 1",
            arrayOf(accountId)
        ).use{it.moveToFirst()}
        db.rawQuery(
            "SELECT id,direction,amount_minor,transaction_type,note,created_by,created_at FROM service_wallet_transactions WHERE service_key=? ORDER BY created_at,id",
            arrayOf(key)
        ).use { c ->
            while(c.moveToNext()) {
                val movementId=c.getString(0)
                val already=db.rawQuery("SELECT 1 FROM cash_transactions WHERE source_type='SERVICE_WALLET_LEDGER' AND source_id=? LIMIT 1",arrayOf(movementId)).use{it.moveToFirst()}
                if(already) continue
                val legacyType=c.getString(3)
                if(legacyType=="OPENING" && (accountOpening>0L || hasManualDailyOpening)) continue
                val cashType=when(legacyType) {
                    "OPENING" -> "SERVICE_FLOAT_OPENING"
                    "RECHARGE" -> "SERVICE_RECHARGE_IN"
                    "TRANSFER_OUT" -> "SERVICE_TRANSFER_OUT"
                    "TRANSFER_REVERSAL" -> "SERVICE_TRANSFER_REVERSAL_IN"
                    else -> "SERVICE_FLOAT_"+legacyType
                }
                val createdAt=c.getString(6)
                val v=ContentValues().apply {
                    put("id",UUID.randomUUID().toString());put("account_id",accountId);put("direction",c.getString(1))
                    put("amount_minor",c.getLong(2));put("currency_code","SYP");put("transaction_type",cashType)
                    put("source_type","SERVICE_WALLET_LEDGER");put("source_id",movementId)
                    put("operation_id","SERVICE_FLOAT_LEDGER:"+movementId)
                    put("description","ترحيل حركة رصيد خدمة قديمة • "+(c.getString(4)?:legacyType))
                    putNull("created_by");put("created_at",createdAt);put("operating_date",createdAt.take(10))
                }
                db.insertOrThrow("cash_transactions",null,v)
            }
        }
    }

    private fun walletBalance(db:SQLiteDatabase,key:String):Long =
        cashBalance(db,serviceAccountId(db,key))

    private fun insertWalletMovement(db:SQLiteDatabase,key:String,direction:String,amount:Long,type:String,sourceId:String?,operationId:String,fee:Long,note:String?,actor:String,now:String,payloadHash:String?=null) {
        val movementId=UUID.randomUUID().toString()
        val v=ContentValues().apply{put("id",movementId);put("service_key",key);put("direction",direction);put("amount_minor",amount);put("transaction_type",type);put("source_id",sourceId);put("operation_id",operationId);if(payloadHash!=null)put("payload_hash",payloadHash);put("fee_minor",fee);put("note",note);put("created_by",actor);put("created_at",now)}
        db.insertOrThrow("service_wallet_transactions",null,v)
        val cashType=when(type) {
            "OPENING" -> "SERVICE_FLOAT_OPENING"
            "RECHARGE" -> "SERVICE_RECHARGE_IN"
            "TRANSFER_OUT" -> "SERVICE_TRANSFER_OUT"
            "TRANSFER_REVERSAL" -> "SERVICE_TRANSFER_REVERSAL_IN"
            else -> "SERVICE_FLOAT_"+type
        }
        insertCashMovement(db,serviceAccountId(db,key),direction,amount,cashType,"SERVICE_WALLET_LEDGER",movementId,
            "SERVICE_FLOAT_LEDGER:"+movementId,actor,note?:type,now)
    }
    private fun insertCashMovement(db:SQLiteDatabase,accountId:String,direction:String,amount:Long,type:String,sourceType:String,sourceId:String,operationId:String,actor:String,description:String,now:String) {
        val v=ContentValues().apply{put("id",UUID.randomUUID().toString());put("account_id",accountId);put("direction",direction);put("amount_minor",amount);put("currency_code","SYP");put("transaction_type",type);put("source_type",sourceType);put("source_id",sourceId);put("operation_id",operationId);put("description",description);put("created_by",actor);put("operating_date",today());put("created_at",now)}
        db.insertOrThrow("cash_transactions",null,v)
    }
    private fun receiptAccount(db:SQLiteDatabase,id:String):Triple<String,String,Boolean> =
        db.rawQuery("SELECT account_type,currency_code,is_active FROM cash_accounts WHERE id=?",arrayOf(id)).use{require(it.moveToFirst()){"حساب القبض غير موجود"};Triple(it.getString(0),it.getString(1),it.getInt(2)==1)}
    private fun cashBalance(db:SQLiteDatabase,id:String):Long {
        val opening=db.rawQuery("SELECT opening_balance_minor FROM cash_accounts WHERE id=?",arrayOf(id)).use{require(it.moveToFirst());it.getLong(0)}
        val moves=db.rawQuery("SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor ELSE -amount_minor END),0) FROM cash_transactions WHERE account_id=?",arrayOf(id)).use{it.moveToFirst();it.getLong(0)}
        return Math.addExact(opening,moves)
    }
    private fun requireManager(db:SQLiteDatabase,actor:String){PermissionPolicy.require(db,actor,PermissionPolicy.SERVICE_TRANSFERS_MANAGE)}
    private fun requireActiveUser(db:SQLiteDatabase,actor:String){require(actor.isNotBlank()){"جلسة المستخدم غير صالحة"};require(db.rawQuery("SELECT 1 FROM users WHERE id=? AND is_active=1",arrayOf(actor)).use{it.moveToFirst()}){"المستخدم غير نشط أو انتهت الجلسة"}}
    private fun audit(db:SQLiteDatabase,actor:String,action:String,id:String,before:String?,after:String,now:String){
        val v=ContentValues().apply{put("id",UUID.randomUUID().toString());put("user_id",actor);put("action",action);put("entity_type","SERVICE_TRANSFER");put("entity_id",id);if(before==null)putNull("before_json")else put("before_json",before);put("after_json",after);put("created_at",now)}
        db.insertOrThrow("audit_log",null,v)
    }
    private fun serviceName(key:String)=if(key==SYRIATEL_SHARED)"رصيد سيرياتيل المشترك" else "رصيد شام كاش"
    private fun serviceTypeName(type:String)=when(type){SYRIATEL_BALANCE->"سيرياتيل العادي";SYRIATEL_CASH->"سيرياتيل كاش";else->"شام كاش"}
    private fun percent(amount:Long,rate:Long):Long=Math.addExact(Math.multiplyExact(amount,rate),50L)/100L
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun esc(value:String)=value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")
    private fun today()=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
    private fun stamp()=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date())
}
