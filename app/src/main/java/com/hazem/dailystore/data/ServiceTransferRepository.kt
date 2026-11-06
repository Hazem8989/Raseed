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
data class ServiceTransferRecord(
    val id:String,val serviceType:String,val serviceName:String,val deliveredMinor:Long,val profitMinor:Long,
    val providerFeeMinor:Long,val customerChargeMinor:Long,val receiptAccountName:String,val receiptChannel:String,
    val createdAt:String,val status:String,val paymentMode:String="CASH",val customerName:String?=null,
    val paidMinor:Long=0L,val outstandingMinor:Long=0L,
    val amountDebitedMinor:Long=deliveredMinor,val deliveredMilliSyp:Long=deliveredMinor*1000L,
    val profitMilliSyp:Long=profitMinor*1000L
)
data class ServiceDailySummary(
    val serviceType:String,val serviceName:String,val operationCount:Int,val deliveredMinor:Long,
    val customerReceiptsMinor:Long,val profitMinor:Long,val providerFeeMinor:Long,
    val rechargeFeePaidMinor:Long,val rechargeReserveMinor:Long=deliveredMinor,val creditChargesMinor:Long=0L,
    val deliveredMilliSyp:Long=deliveredMinor*1000L,val profitMilliSyp:Long=profitMinor*1000L
)
data class ServiceTransferResult(
    val transferId:String,val deliveredMinor:Long,val profitMinor:Long,val providerFeeMinor:Long,
    val customerChargeMinor:Long,val replayed:Boolean,
    val amountDebitedMinor:Long=deliveredMinor,val deliveredMilliSyp:Long=deliveredMinor*1000L,
    val profitMilliSyp:Long=profitMinor*1000L
)

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
        const val SYRIATEL_CASH_FEE_THRESHOLD_MINOR=200L
        /** 1.5 SYP is rounded half-up to the app's whole-SYP unit; 2 SYP remains 2. */
        fun expectedProviderFeeMinor(serviceType:String, deliveredMinor:Long):Long {
            require(deliveredMinor>0L) { "مبلغ التحويل يجب أن يكون أكبر من صفر" }
            return when(serviceType) {
                SYRIATEL_CASH -> if(deliveredMinor<SYRIATEL_CASH_FEE_THRESHOLD_MINOR) 2L else 2L
                SHAM_CASH_TRANSFER -> 0L
                else -> -1L // Other service fee handling is unchanged.
            }
        }
    }

    fun ensureSystemAccounts() {
        // Service-wallet setup and legacy-ledger migration are primary-owned writes.
        // A secondary may read already-synchronized links, but must never seed local
        // wallets/accounts or materialize financial movements as a side effect of a read.
        if (SyncProtocolRepository(appContext).isConfiguredSecondary()) return
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
            """SELECT service_type,SUM(operation_count),SUM(delivered_minor),SUM(customer_receipts_minor),SUM(profit_minor),SUM(provider_fee_minor),SUM(credit_charges_minor),SUM(delivered_milli_syp),SUM(profit_milli_syp),SUM(debited_minor)
               FROM (
                 SELECT service_type,COUNT(*) operation_count,COALESCE(SUM(amount_delivered_minor),0) delivered_minor,
                        COALESCE(SUM(CASE WHEN payment_mode='CASH' THEN customer_charge_minor ELSE 0 END),0) customer_receipts_minor,
                        COALESCE(SUM(CASE WHEN payment_mode='CASH' THEN profit_minor ELSE 0 END),0) profit_minor,COALESCE(SUM(provider_fee_minor),0) provider_fee_minor,
                        COALESCE(SUM(CASE WHEN payment_mode='CREDIT' THEN customer_charge_minor ELSE 0 END),0) credit_charges_minor,
                        COALESCE(SUM(amount_delivered_milli_syp),0) delivered_milli_syp,
                        COALESCE(SUM(CASE WHEN payment_mode='CASH' THEN profit_milli_syp ELSE 0 END),0) profit_milli_syp,
                        COALESCE(SUM(amount_debited_minor),0) debited_minor
                 FROM service_transfers WHERE operating_date=? AND status='POSTED' GROUP BY service_type
                 UNION ALL
                 SELECT CASE service_key WHEN 'SYRIATEL_SHARED' THEN 'SYRIATEL_BALANCE' ELSE 'SHAM_CASH' END,
                        0,0,0,0,0,0,0,0,0 FROM service_wallet_transactions
                 WHERE transaction_type='RECHARGE' AND operating_date=?
                 GROUP BY service_key
                 UNION ALL
                 -- Collections are real customer receipts on the collection date,
                 -- but remain collections (not new transfers or sales).
                 SELECT t.service_type,0,0,COALESCE(SUM(p.amount_minor),0),0,0,0,0,0,0
                 FROM service_transfer_receivable_payments p
                 JOIN service_transfers t ON t.id=p.transfer_id
                 WHERE p.operating_date=? AND t.status='POSTED'
                 GROUP BY t.service_type
               ) GROUP BY service_type ORDER BY service_type""".trimIndent(),arrayOf(date,date,date)
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
                    "SELECT COALESCE(SUM(fee_minor),0) FROM service_wallet_transactions WHERE service_key=? AND transaction_type='RECHARGE' AND operating_date=?",
                    arrayOf(key,date)
                ).use { it.moveToFirst();it.getLong(0) }
            } ?: 0L
            val creditProfitMilli=realizedCreditProfitMilliForDate(database.readableDatabase,date,type)
            val totalProfitMinor=Math.addExact(c.getLong(4),roundMilliToWhole(creditProfitMilli))
            val totalProfitMilli=Math.addExact(c.getLong(8),creditProfitMilli)
            out.add(ServiceDailySummary(type,serviceTypeName(type),c.getInt(1),delivered,c.getLong(3),totalProfitMinor,c.getLong(5),rechargeFeePaid,c.getLong(9),c.getLong(6),c.getLong(7),totalProfitMilli))
        } }
        return out
    }
    fun listTransfers(limit:Int=50):List<ServiceTransferRecord> {
        val out=mutableListOf<ServiceTransferRecord>()
        database.readableDatabase.rawQuery(
            """SELECT t.id,t.service_type,t.amount_delivered_minor,t.profit_minor,t.provider_fee_minor,
                      t.customer_charge_minor,a.name,t.receipt_channel,t.created_at,t.status,t.payment_mode,c.name,
                      COALESCE(SUM(p.amount_minor),0),t.amount_debited_minor,t.amount_delivered_milli_syp,t.profit_milli_syp
               FROM service_transfers t JOIN cash_accounts a ON a.id=t.receipt_account_id
               LEFT JOIN customers c ON c.id=t.customer_id
               LEFT JOIN service_transfer_receivable_payments p ON p.transfer_id=t.id
               GROUP BY t.id
               ORDER BY t.created_at DESC LIMIT ?""".trimIndent(),arrayOf(limit.coerceIn(1,200).toString())
        ).use { c -> while(c.moveToNext()) {
            val type=c.getString(1)
            val charge=c.getLong(5);val paid=c.getLong(12)
            val totalProfitMilli=c.getLong(15)
            val realizedProfit=if(c.getString(10)=="CREDIT") realizedProfitMilliSyp(totalProfitMilli,charge,paid) else totalProfitMilli
            out.add(ServiceTransferRecord(c.getString(0),type,serviceTypeName(type),c.getLong(2),roundMilliToWhole(realizedProfit),
                c.getLong(4),charge,c.getString(6),c.getString(7),c.getString(8),c.getString(9),
                c.getString(10),if(c.isNull(11)) null else c.getString(11),paid,(charge-paid).coerceAtLeast(0L),
                c.getLong(13),c.getLong(14),realizedProfit))
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
        require(!SyncProtocolRepository(appContext).isConfiguredSecondary()) { "تغيير حساب شحن الخدمات متاح من الجهاز الأساسي فقط حتى تكتمل مزامنته" }
        val db=database.writableDatabase
        // Check authority before the helper opens its own transaction or repairs local account records.
        requireManager(db,actor)
        CashAccountRepository(appContext).ensureSystemCashAccounts()
        db.beginTransaction()
        try {
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
        require(!SyncProtocolRepository(appContext).isConfiguredSecondary()) { "شحن محافظ الخدمات متاح من الجهاز الأساسي فقط حتى يكتمل مسار مزامنته المالية" }
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
                        operationId:String,reference:String?=null,note:String?=null,
                        occurredAt:String?=null,operatingDate:String?=null,syncOrigin:Boolean=false,
                        paymentMode:String="CASH",customerId:String?=null,legacyBalanceDelivered:Boolean=false):ServiceTransferResult {
        require(deliveredMinor>0L&&providerFeeMinor>=0L&&operationId.isNotBlank()){"تحقق من مبلغ التحويل ورسوم الشركة"}
        require(paymentMode in setOf("CASH","CREDIT")){"طريقة دفع تحويل الرصيد غير صالحة"}
        if(paymentMode=="CREDIT") require(!customerId.isNullOrBlank()){"اختر الزبون لتسجيل التحويل الآجل"}
        else require(customerId.isNullOrBlank()){"لا تربط زبوناً بعملية تحويل نقدية"}
        require(serviceType in listOf(SYRIATEL_BALANCE,SYRIATEL_CASH,SHAM_CASH_TRANSFER)){"نوع خدمة التحويل غير معروف"}
        if(serviceType==SHAM_CASH_TRANSFER) require(providerFeeMinor==0L){"لا توجد رسوم شركة على تحويل شام كاش وفق السياسة المعتمدة"}
        if(serviceType==SYRIATEL_CASH) require(providerFeeMinor==expectedProviderFeeMinor(serviceType,deliveredMinor)) {
            "رسم شركة سيريتل كاش يُحتسب تلقائياً وفق السياسة المعتمدة"
        }
        val serviceKey=if(serviceType==SHAM_CASH_TRANSFER)SHAM_CASH else SYRIATEL_SHARED
        val profitRate=when(serviceType){SYRIATEL_BALANCE->20L;SYRIATEL_CASH->10L;else->5L}
        // For Syriatel Balance, input is the amount deducted from the trader wallet.
        // Keep delivered amount and profit exact in milli-SYP; round only the customer charge.
        // Company/provider fees are the shop owner's cost, never part of the customer's charge.
        // For Syriatel Cash the fee is deducted from the service float in addition to the delivered amount.
        val amountDebitedMinor=when(serviceType) {
            SYRIATEL_BALANCE -> deliveredMinor
            SYRIATEL_CASH -> Math.addExact(deliveredMinor,providerFeeMinor)
            else -> deliveredMinor
        }
        val deliveredMilliSyp=if(serviceType==SYRIATEL_BALANCE && !legacyBalanceDelivered) roundRatioMilli(amountDebitedMinor,41340L,43L) else Math.multiplyExact(deliveredMinor,1000L)
        val profitMilliSyp=roundRatioMilli(deliveredMilliSyp,profitRate,100L)
        val deliveredDisplayMinor=roundMilliToWhole(deliveredMilliSyp)
        val profit=roundMilliToWhole(profitMilliSyp)
        // Provider fees are the shop's expense for Syriatel Cash, not part of the customer's charge.
        // Preserve the legacy provider-fee charge behavior for other service types.
        val charge=roundMilliToWhole(Math.addExact(
            Math.addExact(deliveredMilliSyp,profitMilliSyp),
            if(serviceType==SYRIATEL_CASH) 0L else Math.multiplyExact(providerFeeMinor,1000L)
        ))
        val walletDebitMinor=amountDebitedMinor
        val db=database.writableDatabase;db.beginTransaction()
        try {
            val localRole=db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'",null).use{if(it.moveToFirst())it.getString(0)else null}
            if(localRole=="SECONDARY") PermissionPolicy.requireAssigned(db,actor,PermissionPolicy.SERVICE_TRANSFERS_EXECUTE)
            else PermissionPolicy.require(db,actor,PermissionPolicy.SERVICE_TRANSFERS_EXECUTE)
            if(paymentMode=="CREDIT") {
                require(localRole==null || localRole in setOf("PRIMARY","SECONDARY")) {
                    "دور الجهاز غير صالح لإنشاء ذمم تحويل الرصيد"
                }
                // The secondary may only queue a deferred transfer. It must never seed
                // financial accounts or post a local wallet/cash movement.
                if(localRole!="SECONDARY") ensureSystemAccounts(db)
            }
            val effectiveReceiptAccountId=if(paymentMode=="CREDIT") serviceAccountId(db,serviceKey) else receiptAccountId
            val account=receiptAccount(db,effectiveReceiptAccountId)
            require(account.second=="SYP"&&account.third){"اختر حساب قبض نشطاً بالليرة السورية"}
            if(paymentMode=="CREDIT") require(account.first=="WALLET"){"حساب حفظ التحويل الآجل غير صالح"}
            val priorSecondaryPayload=if(localRole=="SECONDARY") db.rawQuery(
                "SELECT payload_json FROM sync_operation_envelopes WHERE operation_id=?",arrayOf(operationId)
            ).use { c -> if(c.moveToFirst()) org.json.JSONObject(c.getString(0)) else null } else null
            val eventAt=occurredAt ?: priorSecondaryPayload?.optString("occurred_at")?.takeIf{it.isNotBlank() && it!="null"} ?: stamp()
            val eventDate=operatingDate ?: priorSecondaryPayload?.optString("operating_date")?.takeIf{it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))} ?: StoreTime.operatingDate(db,eventAt)
            require(eventAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T.*")) &&
                eventDate.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))){"تاريخ أو وقت التحويل غير صالح"}
            if(paymentMode=="CASH") {
                if(account.first=="CASH_BOX") {
                    val primary=db.rawQuery("SELECT primary_cash_account_id FROM daily_cash_sessions WHERE operating_date=?",arrayOf(eventDate)).use{if(it.moveToFirst())it.getString(0)else null}
                    require(primary==effectiveReceiptAccountId){"القبض النقدي يجب أن يذهب إلى درج اليوم الرئيسي"}
                } else require(account.first=="WALLET"){"حساب الاستلام يجب أن يكون درجاً نقدياً أو محفظة إلكترونية"}
            }
            // Preserve the historical cash-operation hash so pending pre-upgrade retries remain idempotent.
            val payload=if(legacyBalanceDelivered) {
                if(paymentMode=="CASH") hash("$serviceType|$deliveredMinor|$providerFeeMinor|$effectiveReceiptAccountId|$reference|$note")
                else hash("$serviceType|$deliveredMinor|$providerFeeMinor|$effectiveReceiptAccountId|$paymentMode|${customerId.orEmpty()}|$reference|$note")
            } else if(paymentMode=="CASH")
                hash("$serviceType|$amountDebitedMinor|$deliveredMilliSyp|$profitMilliSyp|$providerFeeMinor|$effectiveReceiptAccountId|$reference|$note")
            else hash("$serviceType|$amountDebitedMinor|$deliveredMilliSyp|$profitMilliSyp|$providerFeeMinor|$effectiveReceiptAccountId|$paymentMode|${customerId.orEmpty()}|$reference|$note")
            val old=db.rawQuery("SELECT id,service_type,amount_delivered_minor,profit_minor,provider_fee_minor,customer_charge_minor,payload_hash,payment_mode,customer_id,amount_debited_minor,amount_delivered_milli_syp,profit_milli_syp FROM service_transfers WHERE operation_id=?",arrayOf(operationId)).use{
                if(it.moveToFirst()) arrayOf(it.getString(0),it.getString(1),it.getLong(2).toString(),it.getLong(3).toString(),it.getLong(4).toString(),it.getLong(5).toString(),it.getString(6),it.getString(7),if(it.isNull(8)) "" else it.getString(8),it.getLong(9).toString(),it.getLong(10).toString(),it.getLong(11).toString()) else null
            }
            if(old!=null) {
                require(old[1]==serviceType&&old[2]==deliveredDisplayMinor.toString()&&old[3]==profit.toString()&&old[4]==providerFeeMinor.toString()&&old[5]==charge.toString()&&old[6]==payload&&old[7]==paymentMode&&old[8]==customerId.orEmpty()&&old[9]==amountDebitedMinor.toString()&&old[10]==deliveredMilliSyp.toString()&&old[11]==profitMilliSyp.toString()){"معرّف التحويل مستخدم لبيانات مختلفة"}
                db.setTransactionSuccessful()
                return ServiceTransferResult(old[0]!!,deliveredDisplayMinor,profit,providerFeeMinor,charge,true,walletDebitMinor,deliveredMilliSyp,profitMilliSyp)
            }
            if(paymentMode=="CREDIT") {
                require(db.rawQuery("SELECT 1 FROM customers WHERE id=? AND is_active=1",arrayOf(customerId)).use{it.moveToFirst()}) {
                    "الزبون غير موجود أو موقوف"
                }
            }
            if(localRole=="SECONDARY") {
                require(paymentMode=="CREDIT") {
                    "الجهاز الثانوي يستطيع تسجيل تحويل رصيد آجل فقط؛ التحويل النقدي وإدارة المحافظ على الجهاز الأساسي"
                }
                val pendingId="pending-service-transfer-$operationId"
                val command=org.json.JSONObject().put("business_operation","EXECUTE_SERVICE_TRANSFER_CREDIT")
                    .put("service_type",serviceType).put("service_key",serviceKey)
                    .put("schema_version",2).put("amount_debited_minor",amountDebitedMinor)
                    .put("delivered_minor",deliveredDisplayMinor).put("delivered_milli_syp",deliveredMilliSyp)
                    .put("profit_minor",profit).put("profit_milli_syp",profitMilliSyp)
                    .put("provider_fee_minor",providerFeeMinor).put("customer_charge_minor",charge)
                    .put("cash_account_id",effectiveReceiptAccountId).put("cash_account_type",account.first)
                    .put("cash_account_currency_code",account.second)
                    .put("payment_mode",paymentMode).put("customer_id",customerId ?: org.json.JSONObject.NULL)
                    .put("reference",reference ?: org.json.JSONObject.NULL).put("note",note ?: org.json.JSONObject.NULL)
                    .put("occurred_at",eventAt).put("operating_date",eventDate)
                SyncProtocolRepository(appContext).enqueueSecondaryCommand(
                    db,actor,operationId,"EXECUTE_SERVICE_TRANSFER_CREDIT","SERVICE_TRANSFER",pendingId,command.toString()
                )
                db.setTransactionSuccessful()
                return ServiceTransferResult(pendingId,deliveredDisplayMinor,profit,providerFeeMinor,charge,false,walletDebitMinor,deliveredMilliSyp,profitMilliSyp)
            }
            require(localRole==null || localRole=="PRIMARY"){"دور الجهاز غير صالح لتنفيذ تحويل الرصيد"}
            ensureSystemAccounts(db)
            require(walletBalance(db,serviceKey)>=walletDebitMinor){"رصيد ${serviceName(serviceKey)} غير كافٍ. المتاح: ${walletBalance(db,serviceKey)} ل.س"}
            val id=UUID.randomUUID().toString();val now=eventAt
            val v=ContentValues().apply {
                put("id",id);put("operation_id",operationId);put("service_type",serviceType);put("service_key",serviceKey)
                put("amount_delivered_minor",deliveredDisplayMinor);put("profit_minor",profit);put("provider_fee_minor",providerFeeMinor)
                put("amount_debited_minor",walletDebitMinor);put("amount_delivered_milli_syp",deliveredMilliSyp);put("profit_milli_syp",profitMilliSyp)
                put("customer_charge_minor",charge);put("receipt_account_id",effectiveReceiptAccountId)
                put("receipt_channel",if(account.first=="WALLET")"ELECTRONIC" else "CASH")
                put("payment_mode",paymentMode);if(customerId.isNullOrBlank())putNull("customer_id")else put("customer_id",customerId)
                put("reference",reference);put("note",note);put("payload_hash",payload);put("created_by",actor)
                put("created_at",now);put("operating_date",eventDate);put("status","POSTED")
            }
            db.insertOrThrow("service_transfers",null,v)
            if(paymentMode=="CREDIT") {
                db.insertOrThrow("service_transfer_receivables",null,ContentValues().apply {
                    put("transfer_id",id);put("customer_id",customerId);put("original_amount_minor",charge)
                    put("status","OPEN");put("note",note);put("created_at",now)
                })
                if(!syncOrigin) ServiceTransferReceivableRepository(appContext)
                    .enqueueMirrorSnapshot(db,actor,id,operationId)
            }
            insertWalletMovement(db,serviceKey,"OUT",walletDebitMinor,"TRANSFER_OUT",id,operationId,0L,note?:serviceTypeName(serviceType),actor,now,null,eventDate)
            if(paymentMode=="CASH") insertCashMovement(db,effectiveReceiptAccountId,"IN",charge,"SERVICE_TRANSFER_RECEIPT","SERVICE_TRANSFER",id,operationId+":RECEIPT",actor,
                "مقبوض تحويل رصيد • ${serviceTypeName(serviceType)} • ربح $profit ل.س",now,eventDate)
            if(!syncOrigin) {
                val syncPayload=org.json.JSONObject().put("business_operation","EXECUTE_SERVICE_TRANSFER")
                    .put("service_type",serviceType).put("service_key",serviceKey)
                    .put("schema_version",2).put("amount_debited_minor",amountDebitedMinor)
                    .put("delivered_minor",deliveredDisplayMinor).put("delivered_milli_syp",deliveredMilliSyp)
                    .put("profit_minor",profit).put("profit_milli_syp",profitMilliSyp)
                    .put("provider_fee_minor",providerFeeMinor).put("customer_charge_minor",charge)
                    .put("cash_account_id",effectiveReceiptAccountId).put("cash_account_type",account.first)
                    .put("cash_account_currency_code",account.second)
                    .put("payment_mode",paymentMode).put("customer_id",customerId ?: org.json.JSONObject.NULL)
                    .put("reference",reference ?: org.json.JSONObject.NULL).put("note",note ?: org.json.JSONObject.NULL)
                    .put("occurred_at",now).put("operating_date",eventDate)
                SyncProtocolRepository(appContext).enqueueLocalOperation(
                    db,actor,operationId,"EXECUTE_SERVICE_TRANSFER","SERVICE_TRANSFER",id,"CREATE",syncPayload.toString()
                )
            }
            audit(db,actor,"SERVICE_TRANSFER_POSTED",id,null,
                "{\"service_type\":\"$serviceType\",\"delivered_minor\":$deliveredMinor,\"profit_minor\":$profit,\"provider_fee_minor\":$providerFeeMinor,\"customer_charge_minor\":$charge,\"receipt_account_id\":\"${esc(receiptAccountId)}\"}",now)
            db.setTransactionSuccessful()
            return ServiceTransferResult(id,deliveredDisplayMinor,profit,providerFeeMinor,charge,false,walletDebitMinor,deliveredMilliSyp,profitMilliSyp)
        } finally {db.endTransaction()}
    }

    /** Reverses the service float deduction and the full receipt without deleting history. */
    fun reverseTransfer(actor:String,transferId:String,operationId:String,reason:String) {
        require(operationId.isNotBlank()&&reason.isNotBlank()){"سبب العكس مطلوب"}
        val db=database.writableDatabase;db.beginTransaction()
        try {
            requireManager(db,actor)
            ensureSystemAccounts(db)
            val row=db.rawQuery("SELECT service_key,amount_delivered_minor,customer_charge_minor,receipt_account_id,status,payment_mode,amount_debited_minor FROM service_transfers WHERE id=?",arrayOf(transferId)).use{
                require(it.moveToFirst()){"عملية التحويل غير موجودة"}
                arrayOf(it.getString(0),it.getLong(1).toString(),it.getLong(2).toString(),it.getString(3),it.getString(4),it.getString(5),it.getLong(6).toString())
            }
            val isCredit=row[5]=="CREDIT"
            if(isCredit) {
                val paid=db.rawQuery("SELECT COALESCE(SUM(amount_minor),0) FROM service_transfer_receivable_payments WHERE transfer_id=?",arrayOf(transferId)).use{it.moveToFirst();it.getLong(0)}
                require(paid==0L){"لا يمكن عكس تحويل آجل بعد تسجيل أي تحصيل عليه"}
            }
            val receipt=row[3]!!;val charge=row[2]!!.toLong()
            val prior=db.rawQuery(
                """SELECT service_key,direction,amount_minor,transaction_type,source_id,note
                   FROM service_wallet_transactions WHERE operation_id=?""".trimIndent(),arrayOf(operationId)
            ).use { c ->
                if(!c.moveToFirst()) null else arrayOf(c.getString(0),c.getString(1),c.getLong(2).toString(),
                    c.getString(3),c.getString(4),if(c.isNull(5)) null else c.getString(5))
            }
            if(prior!=null) {
                require(prior[0]==row[0] && prior[1]=="IN" && prior[2]==row[6] &&
                    prior[3]=="TRANSFER_REVERSAL" && prior[4]==transferId && prior[5]==reason) {
                    "معرّف العكس مستخدم مسبقاً لعملية أو بيانات مختلفة"
                }
                require(row[4]=="REVERSED") { "يوجد سجل عكس دون اكتمال حالة العملية؛ يلزم فحص سلامة الدفتر" }
                if(!isCredit) {
                    val receiptReversal=db.rawQuery(
                        """SELECT 1 FROM cash_transactions WHERE operation_id=? AND account_id=?
                           AND direction='OUT' AND amount_minor=? AND transaction_type='SERVICE_TRANSFER_REVERSAL'""".trimIndent(),
                        arrayOf(operationId+":RECEIPT",receipt,charge.toString())
                    ).use { it.moveToFirst() }
                    require(receiptReversal) { "سجل عكس المحفظة موجود لكن حركة حساب القبض مفقودة" }
                }
                db.setTransactionSuccessful()
                return
            }
            require(row[4]=="POSTED"){"عملية التحويل معكوسة مسبقاً أو غير قابلة للعكس"}
            if(!isCredit) require(cashBalance(db,receipt)>=charge){"لا يمكن عكس العملية: الرصيد المتبقي في حساب القبض أقل من المبلغ المقبوض"}
            val now=stamp()
            insertWalletMovement(db,row[0]!!,"IN",row[6]!!.toLong(),"TRANSFER_REVERSAL",transferId,operationId,0L,reason,actor,now)
            if(!isCredit) insertCashMovement(db,receipt,"OUT",charge,"SERVICE_TRANSFER_REVERSAL","SERVICE_TRANSFER_REVERSAL",transferId,operationId+":RECEIPT",actor,"عكس تحويل رصيد: $reason",now)
            else db.update("service_transfer_receivables",ContentValues().apply{put("status","REVERSED")},"transfer_id=? AND status='OPEN'",arrayOf(transferId))
            val update=ContentValues().apply{put("status","REVERSED");put("reversed_at",now);put("reversed_by",actor);put("reversal_reason",reason)}
            db.update("service_transfers",update,"id=? AND status='POSTED'",arrayOf(transferId))
            audit(db,actor,"SERVICE_TRANSFER_REVERSED",transferId,"{\"status\":\"POSTED\"}","{\"status\":\"REVERSED\",\"reason\":\"${esc(reason)}\"}",now)
            if(isCredit) ServiceTransferReceivableRepository(appContext)
                .enqueueMirrorSnapshot(db,actor,transferId,operationId)
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
                    putNull("created_by");put("created_at",createdAt);put("operating_date",StoreTime.operatingDate(db,createdAt))
                }
                db.insertOrThrow("cash_transactions",null,v)
            }
        }
    }

    /** Profit on a credit transfer is recognized only as its receivable is actually collected. */
    private fun realizedCreditProfitMilliForDate(db:SQLiteDatabase,date:String,serviceType:String):Long {
        var currentTransfer:String?=null
        var charge=0L
        var profit=0L
        var paid=0L
        var recognized=0L
        var total=0L
        db.rawQuery(
            """SELECT t.id,t.customer_charge_minor,t.profit_milli_syp,p.amount_minor,p.operating_date
               FROM service_transfer_receivable_payments p
               JOIN service_transfers t ON t.id=p.transfer_id
               WHERE p.operating_date<=? AND t.service_type=? AND t.payment_mode='CREDIT' AND t.status='POSTED'
               ORDER BY t.id,p.created_at,p.id""".trimIndent(),arrayOf(date,serviceType)
        ).use { c -> while(c.moveToNext()) {
            val id=c.getString(0)
            if(currentTransfer!=id) {
                currentTransfer=id;charge=c.getLong(1);profit=c.getLong(2);paid=0L;recognized=0L
            }
            require(charge>0L) { "مبلغ ذمة تحويل الرصيد غير صالح لحساب الربح المحصل" }
            paid=Math.addExact(paid,c.getLong(3))
            val nextRecognized=realizedProfitMilliSyp(profit,charge,paid)
            val increment=nextRecognized-recognized
            if(c.getString(4)==date) total=Math.addExact(total,increment)
            recognized=nextRecognized
        } }
        return total
    }

    private fun realizedProfitMilliSyp(profitMilliSyp:Long,chargeMinor:Long,paidMinor:Long):Long {
        if(profitMilliSyp<=0L||paidMinor<=0L) return 0L
        require(chargeMinor>0L&&paidMinor<=chargeMinor) { "تحصيل ذمة تحويل الرصيد يتجاوز أصل الدين" }
        return roundRatioMilli(profitMilliSyp,paidMinor,chargeMinor)
    }

    private fun walletBalance(db:SQLiteDatabase,key:String):Long =
        cashBalance(db,serviceAccountId(db,key))

    private fun insertWalletMovement(db:SQLiteDatabase,key:String,direction:String,amount:Long,type:String,sourceId:String?,operationId:String,fee:Long,note:String?,actor:String,now:String,payloadHash:String?=null,eventDate:String?=null) {
        val movementId=UUID.randomUUID().toString()
        val v=ContentValues().apply{put("id",movementId);put("service_key",key);put("direction",direction);put("amount_minor",amount);put("transaction_type",type);put("source_id",sourceId);put("operation_id",operationId);if(payloadHash!=null)put("payload_hash",payloadHash);put("fee_minor",fee);put("note",note);put("created_by",actor);put("created_at",now);put("operating_date",eventDate ?: StoreTime.operatingDate(db,now))}
        db.insertOrThrow("service_wallet_transactions",null,v)
        val cashType=when(type) {
            "OPENING" -> "SERVICE_FLOAT_OPENING"
            "RECHARGE" -> "SERVICE_RECHARGE_IN"
            "TRANSFER_OUT" -> "SERVICE_TRANSFER_OUT"
            "TRANSFER_REVERSAL" -> "SERVICE_TRANSFER_REVERSAL_IN"
            else -> "SERVICE_FLOAT_"+type
        }
        insertCashMovement(db,serviceAccountId(db,key),direction,amount,cashType,"SERVICE_WALLET_LEDGER",movementId,
            "SERVICE_FLOAT_LEDGER:"+movementId,actor,note?:type,now,eventDate)
    }
    private fun insertCashMovement(db:SQLiteDatabase,accountId:String,direction:String,amount:Long,type:String,sourceType:String,sourceId:String,operationId:String,actor:String,description:String,now:String,eventDate:String?=null) {
        val v=ContentValues().apply{put("id",UUID.randomUUID().toString());put("account_id",accountId);put("direction",direction);put("amount_minor",amount);put("currency_code","SYP");put("transaction_type",type);put("source_type",sourceType);put("source_id",sourceId);put("operation_id",operationId);put("description",description);put("created_by",actor);put("operating_date",eventDate ?: StoreTime.operatingDate(db,now));put("created_at",now)}
        db.insertOrThrow("cash_transactions",null,v)
    }
    private fun receiptAccount(db:SQLiteDatabase,id:String):Triple<String,String,Boolean> =
        db.rawQuery("SELECT account_type,currency_code,is_active FROM cash_accounts WHERE id=?",arrayOf(id)).use{require(it.moveToFirst()){"حساب القبض غير موجود"};Triple(it.getString(0),it.getString(1),it.getInt(2)==1)}
    private fun cashBalance(db:SQLiteDatabase,id:String):Long {
        val opening=db.rawQuery("SELECT opening_balance_minor FROM cash_accounts WHERE id=?",arrayOf(id)).use{require(it.moveToFirst());it.getLong(0)}
        val moves=db.rawQuery("SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor ELSE -amount_minor END),0) FROM cash_transactions WHERE account_id=?",arrayOf(id)).use{it.moveToFirst();it.getLong(0)}
        return Math.addExact(opening,moves)
    }
    private fun requireManager(db:SQLiteDatabase,actor:String){
        PermissionPolicy.require(db,actor,PermissionPolicy.SERVICE_TRANSFERS_MANAGE)
        val role=db.rawQuery(
            "SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'",null
        ).use { if(it.moveToFirst()) it.getString(0) else null }
        require(role!="SECONDARY") {
            "إدارة أرصدة الخدمات وتعبئتها لم يكتمل مسار مزامنتها بعد؛ نفّذها من الجهاز الأساسي"
        }
        require(role==null || role=="PRIMARY") { "دور الجهاز غير صالح لإدارة أرصدة الخدمات" }
    }
    private fun requireActiveUser(db:SQLiteDatabase,actor:String){require(actor.isNotBlank()){"جلسة المستخدم غير صالحة"};require(db.rawQuery("SELECT 1 FROM users WHERE id=? AND is_active=1",arrayOf(actor)).use{it.moveToFirst()}){"المستخدم غير نشط أو انتهت الجلسة"}}
    private fun audit(db:SQLiteDatabase,actor:String,action:String,id:String,before:String?,after:String,now:String){
        val v=ContentValues().apply{put("id",UUID.randomUUID().toString());put("user_id",actor);put("action",action);put("entity_type","SERVICE_TRANSFER");put("entity_id",id);if(before==null)putNull("before_json")else put("before_json",before);put("after_json",after);put("created_at",now)}
        db.insertOrThrow("audit_log",null,v)
    }
    private fun serviceName(key:String)=if(key==SYRIATEL_SHARED)"رصيد سيرياتيل المشترك" else "رصيد شام كاش"
    private fun serviceTypeName(type:String)=when(type){SYRIATEL_BALANCE->"سيرياتيل العادي";SYRIATEL_CASH->"سيرياتيل كاش";else->"شام كاش"}
    private fun percent(amount:Long,rate:Long):Long=Math.addExact(Math.multiplyExact(amount,rate),50L)/100L
    private fun roundRatioMilli(value:Long,numerator:Long,denominator:Long):Long {
        require(value>=0L&&numerator>=0L&&denominator>0L)
        val product=java.math.BigInteger.valueOf(value).multiply(java.math.BigInteger.valueOf(numerator))
        val divisor=java.math.BigInteger.valueOf(denominator)
        return product.add(divisor.divide(java.math.BigInteger.valueOf(2L))).divide(divisor).longValueExact()
    }
    private fun roundMilliToWhole(value:Long):Long { require(value>=0L); return Math.addExact(value,500L)/1000L }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun esc(value:String)=value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")
    private fun today()=StoreTime.today(database.readableDatabase)
    private fun stamp()=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date())
}
