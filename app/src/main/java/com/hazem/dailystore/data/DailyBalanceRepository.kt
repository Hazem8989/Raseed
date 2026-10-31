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

data class DailyAccountSummary(
    val accountId:String,val accountName:String,val accountType:String,val currencyCode:String,
    val openingMinor:Long,val salesReceiptsMinor:Long,val debtCollectionsMinor:Long,val otherInflowsMinor:Long,
    val supplierPaymentsMinor:Long,val personalWithdrawalsMinor:Long,val transferInMinor:Long,val transferOutMinor:Long,
    val otherOutflowsMinor:Long,val closingMinor:Long
) { val electronicReceiptsMinor:Long get()=Math.addExact(salesReceiptsMinor,debtCollectionsMinor) }
data class DailyCashReport(val operatingDate:String,val accounts:List<DailyAccountSummary>,val electronicReceiptsByCurrency:Map<String,Long>,val physicalCashReceiptsByCurrency:Map<String,Long>,val supplierRollover:SupplierRolloverSummary?)
data class SupplierRolloverSummary(
    val operatingDate:String,val accountId:String,val accountName:String,val allocatedMinor:Long,
    val supplierPaymentsMinor:Long,val personalWithdrawalsMinor:Long
) { val availableMinor:Long get()=(allocatedMinor-supplierPaymentsMinor-personalWithdrawalsMinor).coerceAtLeast(0L) }
data class AccountTransferResult(val transferId:String,val replayed:Boolean)

class DailyBalanceRepository(context:Context) {
    private val database=AppDatabase.getInstance(context)

    fun setSupplierRollover(actor:String,accountId:String,amount:Long,note:String?=null):SupplierRolloverSummary {
        require(amount>=0L){"مبلغ النقد المدور لا يمكن أن يكون سالباً"}
        val db=database.writableDatabase; manager(db,actor); db.beginTransaction()
        try {
            val name=db.rawQuery("SELECT name FROM cash_accounts WHERE id=? AND account_type='CASH_BOX' AND currency_code='SYP' AND is_active=1",arrayOf(accountId)).use {
                require(it.moveToFirst()){"اختر صندوقاً نقدياً نشطاً بالليرة السورية"};it.getString(0)
            }
            val date=today();val used=usage(db,date,accountId)
            require(amount>=used.first+used.second){"المبلغ الجديد أقل من دفعات الموردين والمسحوبات المسجلة اليوم"}
            val old=db.rawQuery("SELECT account_id,allocated_minor FROM daily_supplier_rollovers WHERE operating_date=?",arrayOf(date)).use {
                if(it.moveToFirst()) Pair(it.getString(0),it.getLong(1)) else null
            }
            require(old == null || old.first == accountId || usage(db, date, old.first).let { it.first == 0L && it.second == 0L }) {
                "لا يمكن تبديل صندوق النقد المدور بعد تسجيل دفعات أو مسحوبات عليه اليوم"
            }
            val primary = db.rawQuery("SELECT primary_cash_account_id FROM daily_cash_sessions WHERE operating_date=?", arrayOf(date)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            require(primary == null || primary != accountId) {
                "يجب فصل صندوق الموردين المدور عن درج الكاش الرئيسي اليومي"
            }
            require(balance(db, accountId) >= amount) {
                "المبلغ المخصص يتجاوز الرصيد الفعلي الموجود في صندوق الموردين"
            }
            val now=stamp();val v=ContentValues().apply {
                put("operating_date",date);put("account_id",accountId);put("allocated_minor",amount)
                put("note",note?.trim()?.takeIf { x->x.isNotEmpty() });put("updated_by",actor)
                if(old==null)put("created_at",now);put("updated_at",now)
            }
            if(old==null)db.insertOrThrow("daily_supplier_rollovers",null,v)
            else require(db.update("daily_supplier_rollovers",v,"operating_date=?",arrayOf(date))==1)
            audit(db,actor,if(old==null)"CREATE_DAILY_SUPPLIER_ROLLOVER" else "UPDATE_DAILY_SUPPLIER_ROLLOVER",
                "DAILY_SUPPLIER_ROLLOVER",date,old?.let { "{\"account_id\":\""+esc(it.first)+"\",\"allocated_minor\":"+it.second+"}" },
                "{\"account_id\":\""+esc(accountId)+"\",\"account_name\":\""+esc(name)+"\",\"allocated_minor\":"+amount+",\"note\":\""+esc(note.orEmpty())+"\"}",now)
            db.setTransactionSuccessful()
            return SupplierRolloverSummary(date,accountId,name,amount,used.first,used.second)
        } finally { db.endTransaction() }
    }

    fun currentSupplierRollover():SupplierRolloverSummary? {
        val date=today()
        return database.readableDatabase.rawQuery("SELECT r.account_id,a.name,r.allocated_minor FROM daily_supplier_rollovers r JOIN cash_accounts a ON a.id=r.account_id WHERE r.operating_date=?",arrayOf(date)).use { c->
            if(!c.moveToFirst())null else {val u=usage(database.readableDatabase,date,c.getString(0));SupplierRolloverSummary(date,c.getString(0),c.getString(1),c.getLong(2),u.first,u.second)}
        }
    }

    fun setWalletOpening(actor:String,walletId:String,amount:Long,note:String?=null) {
        require(amount>=0L){"الرصيد الافتتاحي لا يمكن أن يكون سالباً"}
        val db=database.writableDatabase;manager(db,actor);db.beginTransaction()
        try {
            val w=account(db,walletId);require(w.type=="WALLET"&&w.active){"اختر محفظة إلكترونية نشطة"}
            val date=today()
            val old=db.rawQuery("SELECT opening_minor FROM wallet_daily_openings WHERE operating_date=? AND account_id=?",arrayOf(date,walletId)).use { if(it.moveToFirst())it.getLong(0) else null }
            val activity=db.rawQuery("SELECT COUNT(*) FROM cash_transactions WHERE account_id=? AND COALESCE(operating_date,substr(created_at,1,10))=? AND transaction_type<>'WALLET_DAILY_OPENING_ADJUSTMENT'",arrayOf(walletId,date)).use { it.moveToFirst();it.getInt(0) }
            require(old!=null||activity==0){"يجب إدخال الرصيد الافتتاحي قبل تسجيل أي حركة على المحفظة اليوم"}
            val before=balance(db,walletId);val delta=Math.subtractExact(amount,before);val now=stamp()
            if(delta!=0L) movement(db,walletId,if(delta>0)"IN" else "OUT",kotlin.math.abs(delta),w.currency,
                "WALLET_DAILY_OPENING_ADJUSTMENT","WALLET_DAILY_OPENING",date+":"+walletId,
                "WALLET_OPENING:"+date+":"+walletId+":"+UUID.randomUUID(),actor,"تسوية الرصيد الافتتاحي اليومي",now)
            val v=ContentValues().apply {put("operating_date",date);put("account_id",walletId);put("opening_minor",amount);put("note",note);put("updated_by",actor);if(old==null)put("created_at",now);put("updated_at",now)}
            if(old==null)db.insertOrThrow("wallet_daily_openings",null,v) else db.update("wallet_daily_openings",v,"operating_date=? AND account_id=?",arrayOf(date,walletId))
            audit(db,actor,if(old==null)"SET_WALLET_DAILY_OPENING" else "UPDATE_WALLET_DAILY_OPENING","WALLET_DAILY_OPENING",date+":"+walletId,
                old?.let{"{\"opening_minor\":"+it+"}"}, "{\"opening_minor\":"+amount+",\"balance_before\":"+before+",\"adjustment_minor\":"+delta+"}",now)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    fun addWalletTopUp(actor:String,walletId:String,amount:Long,operationId:String,reference:String?=null,note:String?=null) {
        require(amount>0L&&operationId.isNotBlank()){"أدخل مبلغاً ومعرّف عملية صحيحين"}
        val cleanReference=reference?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("مرجع عملية التغذية مطلوب لمنع تكرار تسجيل الرصيد")
        val db=database.writableDatabase;manager(db,actor);db.beginTransaction()
        try {
            val w=account(db,walletId);require(w.type=="WALLET"&&w.active){"اختر محفظة إلكترونية نشطة"}
            val openingAlreadyContainsReference=db.rawQuery(
                "SELECT 1 FROM wallet_daily_openings WHERE operating_date=? AND account_id=? AND note=?",
                arrayOf(today(),walletId,cleanReference)
            ).use { it.moveToFirst() }
            require(!openingAlreadyContainsReference) {
                "هذا المرجع مسجل ضمن الرصيد الافتتاحي اليدوي اليوم؛ لن نضيفه مرة ثانية"
            }
            val sameReference=db.rawQuery(
                "SELECT account_id,amount_minor FROM cash_transactions WHERE transaction_type='WALLET_TOP_UP' AND source_type='WALLET_TOP_UP' AND source_id=?",
                arrayOf(cleanReference)
            ).use { if(it.moveToFirst()) Pair(it.getString(0),it.getLong(1)) else null }
            if(sameReference!=null) {
                require(sameReference.first==walletId&&sameReference.second==amount) {
                    "مرجع التغذية مستخدم مسبقاً بمبلغ أو محفظة مختلفة"
                }
                db.setTransactionSuccessful();return
            }
            val old=db.rawQuery("SELECT account_id,amount_minor,transaction_type,source_id FROM cash_transactions WHERE operation_id=?",arrayOf(operationId)).use { if(it.moveToFirst())arrayOf(it.getString(0),it.getLong(1).toString(),it.getString(2),it.getString(3))else null }
            if(old!=null){require(old[0]==walletId&&old[1]==amount.toString()&&old[2]=="WALLET_TOP_UP"&&old[3]==cleanReference){"معرّف العملية مستخدم لبيانات مختلفة"};db.setTransactionSuccessful();return}
            val now=stamp();movement(db,walletId,"IN",amount,w.currency,"WALLET_TOP_UP","WALLET_TOP_UP",cleanReference,operationId,actor,note?:"تغذية رصيد محفظة",now)
            audit(db,actor,"WALLET_TOP_UP","CASH_ACCOUNT",walletId,null,"{\"amount_minor\":"+amount+",\"currency\":\""+w.currency+"\",\"reference\":\""+esc(cleanReference)+"\"}",now)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    fun addPersonalWithdrawal(actor:String,accountId:String,amount:Long,operationId:String,note:String?=null) {
        require(amount>0L&&operationId.isNotBlank()){"بيانات المسحوب الشخصي غير مكتملة"}
        val db=database.writableDatabase;manager(db,actor);db.beginTransaction()
        try {
            val r=rollover(db,accountId)?:throw IllegalArgumentException("يجب تحديد النقد المدور لهذا اليوم أولاً")
            val old=db.rawQuery("SELECT account_id,amount_minor,transaction_type FROM cash_transactions WHERE operation_id=?",arrayOf(operationId)).use { if(it.moveToFirst())Triple(it.getString(0),it.getLong(1),it.getString(2))else null }
            if(old!=null){require(old.first==accountId&&old.second==amount&&old.third=="PERSONAL_WITHDRAWAL"){"معرّف العملية مستخدم لبيانات مختلفة"};db.setTransactionSuccessful();return}
            require(r.availableMinor>=amount){"المتاح من النقد المدور لا يكفي. المتاح: "+r.availableMinor+" ل.س"}
            require(balance(db,accountId)>=amount){"الرصيد الفعلي في صندوق الموردين لا يكفي للمسحوب الشخصي"}
            val now=stamp();movement(db,accountId,"OUT",amount,"SYP","PERSONAL_WITHDRAWAL","PERSONAL_WITHDRAWAL",operationId,operationId,actor,note?:"مسحوبات شخصية",now)
            audit(db,actor,"PERSONAL_WITHDRAWAL","CASH_ACCOUNT",accountId,null,"{\"amount_minor\":"+amount+",\"note\":\""+esc(note.orEmpty())+"\"}",now)
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }

    fun transferWalletToDrawer(actor:String,walletId:String,amount:Long,operationId:String,reference:String?=null,note:String?=null):AccountTransferResult {
        require(amount>0L&&operationId.isNotBlank()){"بيانات التحويل غير مكتملة"}
        val db=database.writableDatabase;manager(db,actor);db.beginTransaction()
        try {
            val payload=hash(walletId+"|"+amount+"|"+reference.orEmpty()+"|"+note.orEmpty())
            val old=db.rawQuery("SELECT id,source_account_id,amount_minor,payload_hash FROM cash_account_transfers WHERE operation_id=?",arrayOf(operationId)).use { if(it.moveToFirst())arrayOf(it.getString(0),it.getString(1),it.getLong(2).toString(),it.getString(3))else null }
            if(old!=null){require(old[1]==walletId&&old[2]==amount.toString()&&old[3]==payload){"معرّف التحويل مستخدم لبيانات مختلفة"};db.setTransactionSuccessful();return AccountTransferResult(old[0]!!,true)}
            val w=account(db,walletId);require(w.type=="WALLET"&&w.active&&w.currency=="SYP"){"مصدر التحويل يجب أن يكون محفظة سورية نشطة"}
            val drawer=db.rawQuery("SELECT a.id FROM daily_cash_sessions s JOIN cash_accounts a ON a.id=s.primary_cash_account_id WHERE s.operating_date=? AND a.is_active=1 AND a.account_type='CASH_BOX' AND a.currency_code='SYP'",arrayOf(today())).use { require(it.moveToFirst()){"لم يتم تعيين درج اليوم الرئيسي"};it.getString(0) }
            require(balance(db,walletId)>=amount){"رصيد المحفظة غير كافٍ"}
            val id=UUID.randomUUID().toString();val now=stamp()
            val v=ContentValues().apply {put("id",id);put("operation_id",operationId);put("source_account_id",walletId);put("destination_account_id",drawer);put("amount_minor",amount);put("currency_code","SYP");put("reference",reference);put("note",note);put("payload_hash",payload);put("created_by",actor);put("created_at",now)}
            db.insertOrThrow("cash_account_transfers",null,v)
            movement(db,walletId,"OUT",amount,"SYP","ACCOUNT_TRANSFER_OUT","ACCOUNT_TRANSFER",id,operationId+":OUT",actor,"تحويل داخلي – تصريف محفظة إلى درج الكاش",now)
            movement(db,drawer,"IN",amount,"SYP","ACCOUNT_TRANSFER_IN","ACCOUNT_TRANSFER",id,operationId+":IN",actor,"تحويل داخلي – تصريف محفظة من "+w.name,now)
            audit(db,actor,"WALLET_TO_CASH_TRANSFER","CASH_ACCOUNT_TRANSFER",id,null,"{\"source_account_id\":\""+walletId+"\",\"destination_account_id\":\""+drawer+"\",\"amount_minor\":"+amount+",\"currency\":\"SYP\"}",now)
            db.setTransactionSuccessful();return AccountTransferResult(id,false)
        } finally {db.endTransaction()}
    }

    fun dailyReport(date:String=today()):DailyCashReport {
        require(date.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))){"تاريخ التشغيل غير صالح"}
        val db=database.readableDatabase;val rows=mutableListOf<DailyAccountSummary>()
        db.rawQuery("SELECT id,name,account_type,currency_code,opening_balance_minor FROM cash_accounts WHERE is_active=1 ORDER BY account_type,name",null).use { c->
            while(c.moveToNext()){
                val id=c.getString(0);val base=c.getLong(4)
                val opening=db.rawQuery("SELECT opening_minor FROM wallet_daily_openings WHERE operating_date=? AND account_id=?",arrayOf(date,id)).use { if(it.moveToFirst())it.getLong(0)else openingBefore(db,id,base,date) }
                val s=totals(db,id,date)
                rows.add(DailyAccountSummary(id,c.getString(1),c.getString(2),c.getString(3),opening,s[0],s[1],s[2],s[3],s[4],s[5],s[6],s[7],Math.addExact(opening,s.sum())))
            }
        }
        val electronic=rows.filter{it.accountType=="WALLET"}.groupBy{it.currencyCode}.mapValues { entry -> entry.value.sumOf { it.electronicReceiptsMinor } }
        val cash=rows.filter{it.accountType=="CASH_BOX"}.groupBy{it.currencyCode}.mapValues { entry -> entry.value.sumOf { Math.addExact(it.salesReceiptsMinor,it.debtCollectionsMinor) } }
        return DailyCashReport(date,rows,electronic,cash,rolloverForDate(db,date))
    }

    fun todayTransactions():List<String>{
        val out=mutableListOf<String>()
        database.readableDatabase.rawQuery("SELECT t.created_at,a.name,t.transaction_type,t.direction,t.amount_minor,t.currency_code,COALESCE(t.description,'') FROM cash_transactions t JOIN cash_accounts a ON a.id=t.account_id WHERE COALESCE(t.operating_date,substr(t.created_at,1,10))=? ORDER BY t.created_at DESC",arrayOf(today())).use { c->
            while(c.moveToNext())out.add(c.getString(0)+" • "+c.getString(1)+" • "+typeName(c.getString(2))+" • "+(if(c.getString(3)=="IN")"وارد" else "صادر")+" • "+c.getLong(4)+" "+c.getString(5)+" • "+c.getString(6))
        };return out
    }

    private fun totals(db:SQLiteDatabase,id:String,date:String):List<Long>{
        val s=MutableList(8){0L}
        db.rawQuery("SELECT transaction_type,direction,SUM(amount_minor) FROM cash_transactions WHERE account_id=? AND COALESCE(operating_date,substr(created_at,1,10))=? AND transaction_type<>'WALLET_DAILY_OPENING_ADJUSTMENT' GROUP BY transaction_type,direction",arrayOf(id,date)).use { c->
            while(c.moveToNext()){val t=c.getString(0);val d=c.getString(1);val n=c.getLong(2)
                when{t=="SALE_RECEIPT"&&d=="IN"->s[0]+=n;t=="CUSTOMER_CREDIT_COLLECTION"&&d=="IN"->s[1]+=n
                    t=="ACCOUNT_TRANSFER_IN"&&d=="IN"->s[6]+=n;t=="PURCHASE_PAYMENT"&&d=="OUT"->s[3]+=n
                    t=="PERSONAL_WITHDRAWAL"&&d=="OUT"->s[4]+=n;t=="ACCOUNT_TRANSFER_OUT"&&d=="OUT"->s[7]+=n
                    d=="IN"->s[2]+=n;d=="OUT"->s[5]+=n}
            }
        };return s
    }
    private fun openingBefore(db:SQLiteDatabase,id:String,base:Long,date:String):Long{
        val n=db.rawQuery("SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor ELSE -amount_minor END),0) FROM cash_transactions WHERE account_id=? AND COALESCE(operating_date,substr(created_at,1,10))<?",arrayOf(id,date)).use{it.moveToFirst();it.getLong(0)}
        return Math.addExact(base,n)
    }
    private fun usage(db:SQLiteDatabase,date:String,id:String):Pair<Long,Long>{
        var p=0L;var w=0L
        db.rawQuery("SELECT transaction_type,SUM(amount_minor) FROM cash_transactions WHERE account_id=? AND direction='OUT' AND COALESCE(operating_date,substr(created_at,1,10))=? AND transaction_type IN ('PURCHASE_PAYMENT','PERSONAL_WITHDRAWAL') GROUP BY transaction_type",arrayOf(id,date)).use{c->while(c.moveToNext())if(c.getString(0)=="PURCHASE_PAYMENT")p=c.getLong(1)else w=c.getLong(1)}
        return Pair(p,w)
    }
    private fun rolloverForDate(db:SQLiteDatabase,date:String):SupplierRolloverSummary?{
        val row=db.rawQuery("SELECT r.account_id,a.name,r.allocated_minor FROM daily_supplier_rollovers r JOIN cash_accounts a ON a.id=r.account_id WHERE r.operating_date=?",arrayOf(date)).use {
            if(it.moveToFirst()) Triple(it.getString(0),it.getString(1),it.getLong(2)) else null
        } ?: return null
        val used=usage(db,date,row.first)
        return SupplierRolloverSummary(date,row.first,row.second,row.third,used.first,used.second)
    }

    private fun rollover(db:SQLiteDatabase,id:String):SupplierRolloverSummary?{
        val date=today();val a=db.rawQuery("SELECT a.name,r.allocated_minor FROM daily_supplier_rollovers r JOIN cash_accounts a ON a.id=r.account_id WHERE r.operating_date=? AND r.account_id=?",arrayOf(date,id)).use{if(it.moveToFirst())Pair(it.getString(0),it.getLong(1))else null}?:return null
        val u=usage(db,date,id);return SupplierRolloverSummary(date,id,a.first,a.second,u.first,u.second)
    }
    private data class Account(val name:String,val type:String,val currency:String,val active:Boolean)
    private fun account(db:SQLiteDatabase,id:String)=db.rawQuery("SELECT name,account_type,currency_code,is_active FROM cash_accounts WHERE id=?",arrayOf(id)).use{require(it.moveToFirst()){"الحساب غير موجود"};Account(it.getString(0),it.getString(1),it.getString(2),it.getInt(3)==1)}
    private fun balance(db:SQLiteDatabase,id:String):Long{
        val b=db.rawQuery("SELECT opening_balance_minor FROM cash_accounts WHERE id=?",arrayOf(id)).use{require(it.moveToFirst());it.getLong(0)}
        val m=db.rawQuery("SELECT COALESCE(SUM(CASE WHEN direction='IN' THEN amount_minor ELSE -amount_minor END),0) FROM cash_transactions WHERE account_id=?",arrayOf(id)).use{it.moveToFirst();it.getLong(0)}
        return Math.addExact(b,m)
    }
    private fun movement(db:SQLiteDatabase,id:String,direction:String,amount:Long,currency:String,type:String,sourceType:String,sourceId:String,op:String,actor:String,description:String,now:String){
        val v=ContentValues().apply{put("id",UUID.randomUUID().toString());put("account_id",id);put("direction",direction);put("amount_minor",amount);put("currency_code",currency);put("transaction_type",type);put("source_type",sourceType);put("source_id",sourceId);put("operation_id",op);put("description",description);put("created_by",actor);put("operating_date",today());put("created_at",now)}
        db.insertOrThrow("cash_transactions",null,v)
    }
    private fun audit(db:SQLiteDatabase,user:String,action:String,kind:String,id:String,before:String?,after:String,now:String){
        val v=ContentValues().apply{put("id",UUID.randomUUID().toString());put("user_id",user);put("action",action);put("entity_type",kind);put("entity_id",id);if(before==null)putNull("before_json")else put("before_json",before);put("after_json",after);put("created_at",now)}
        db.insertOrThrow("audit_log",null,v)
    }
    private fun manager(db:SQLiteDatabase,id:String){require(id.isNotBlank()){"جلسة المدير غير صالحة"};val ok=db.rawQuery("SELECT 1 FROM users WHERE id=? AND role_key='ADMIN' AND is_active=1",arrayOf(id)).use{it.moveToFirst()};require(ok){"هذه العملية متاحة للمدير فقط"}}
    private fun typeName(t:String)=when(t){"SALE_RECEIPT"->"قبض مبيعات";"CUSTOMER_CREDIT_COLLECTION"->"تحصيل ديون";"PURCHASE_PAYMENT"->"دفعة مورد";"PERSONAL_WITHDRAWAL"->"مسحوبات شخصية";"ACCOUNT_TRANSFER_IN","ACCOUNT_TRANSFER_OUT"->"تحويل داخلي – تصريف محفظة";"WALLET_TOP_UP"->"تغذية محفظة";else->t}
    private fun esc(s:String)=s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    private fun today()=SimpleDateFormat("yyyy-MM-dd",Locale.US).format(Date())
    private fun stamp()=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date())
}
