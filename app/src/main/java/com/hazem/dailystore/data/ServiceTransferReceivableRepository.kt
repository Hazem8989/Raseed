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

data class ServiceTransferReceivableSummary(
    val transferId:String,val customerId:String,val customerName:String,val serviceType:String,
    val deliveredMinor:Long,val chargeMinor:Long,val paidMinor:Long,val outstandingMinor:Long,
    val createdAt:String,val note:String?
)
data class ServiceTransferDebtPaymentResult(
    val paymentId:String,val transferId:String,val paidMinor:Long,val outstandingMinor:Long,
    val status:String,val replayed:Boolean
)

/** Separate SYP-only receivables for service transfers; collections never create sales or stock. */
class ServiceTransferReceivableRepository(context:Context) {
    private val appContext=context.applicationContext
    private val database=AppDatabase.getInstance(appContext)

    fun listOpenReceivables(customerId:String?=null,limit:Int=100):List<ServiceTransferReceivableSummary> {
        val result=mutableListOf<ServiceTransferReceivableSummary>()
        database.readableDatabase.rawQuery(
            """SELECT t.id,r.customer_id,c.name,t.service_type,t.amount_delivered_minor,
                      t.customer_charge_minor,COALESCE(SUM(p.amount_minor),0),t.created_at,r.note
               FROM service_transfer_receivables r
               JOIN service_transfers t ON t.id=r.transfer_id
               JOIN customers c ON c.id=r.customer_id
               LEFT JOIN service_transfer_receivable_payments p ON p.transfer_id=t.id
               WHERE t.status='POSTED' AND r.status IN ('OPEN','PARTIALLY_PAID')
                 AND (? IS NULL OR r.customer_id=?)
               GROUP BY t.id
               HAVING t.customer_charge_minor-COALESCE(SUM(p.amount_minor),0)>0
               ORDER BY t.created_at DESC LIMIT ?""".trimIndent(),
            arrayOf(customerId,customerId,limit.coerceIn(1,500).toString())
        ).use{c->while(c.moveToNext()){
            val charge=c.getLong(5);val paid=c.getLong(6)
            result.add(ServiceTransferReceivableSummary(c.getString(0),c.getString(1),c.getString(2),c.getString(3),
                c.getLong(4),charge,paid,(charge-paid).coerceAtLeast(0L),c.getString(7),if(c.isNull(8))null else c.getString(8)))
        }}
        return result
    }

    fun collect(actorUserId:String,transferId:String,operationId:String,cashAccountId:String,
                amountSypMinor:Long,note:String?=null):ServiceTransferDebtPaymentResult {
        require(actorUserId.isNotBlank()&&transferId.isNotBlank()&&operationId.isNotBlank()&&cashAccountId.isNotBlank()) {
            "بيانات تحصيل دين تحويل الرصيد غير مكتملة"
        }
        require(amountSypMinor>0L){"مبلغ التحصيل يجب أن يكون أكبر من صفر"}
        val cleanNote=note?.trim()?.take(500)?.takeIf{it.isNotEmpty()}
        val payloadHash=hash("$transferId|$cashAccountId|$amountSypMinor|${cleanNote.orEmpty()}")
        val db=database.writableDatabase;db.beginTransaction()
        try {
            val role=db.rawQuery("SELECT state_value FROM sync_protocol_state WHERE state_key='local_device_role'",null)
                .use{if(it.moveToFirst())it.getString(0)else null}
            require(role==null||role=="PRIMARY") {
                "تحصيل ديون تحويل الرصيد من الجهاز الأساسي فقط حالياً؛ لم تكتمل مزامنة كشف هذه الديون إلى الجهاز الثانوي"
            }
            PermissionPolicy.require(db,actorUserId,PermissionPolicy.CUSTOMERS_COLLECT)
            val transfer=db.rawQuery(
                """SELECT t.customer_charge_minor,t.status,r.status
                   FROM service_transfers t JOIN service_transfer_receivables r ON r.transfer_id=t.id
                   WHERE t.id=?""".trimIndent(),arrayOf(transferId)
            ).use{c->
                require(c.moveToFirst()){"دين تحويل الرصيد غير موجود"}
                arrayOf(c.getLong(0).toString(),c.getString(1),c.getString(2))
            }
            require(transfer[1]=="POSTED"&&transfer[2] in setOf("OPEN","PARTIALLY_PAID")) {
                "هذا الدين غير قابل للتحصيل أو أُغلق سابقاً"
            }
            val existing=db.rawQuery(
                "SELECT id,transfer_id,amount_minor,payload_hash FROM service_transfer_receivable_payments WHERE operation_id=?",
                arrayOf(operationId)
            ).use{c->if(c.moveToFirst())arrayOf(c.getString(0),c.getString(1),c.getLong(2).toString(),c.getString(3))else null}
            if(existing!=null) {
                require(existing[1]==transferId&&existing[2]==amountSypMinor.toString()&&existing[3]==payloadHash) {
                    "معرّف التحصيل مستخدم لعملية أو بيانات مختلفة"
                }
                val state=receivableState(db,transferId,transfer[0]!!.toLong())
                db.setTransactionSuccessful()
                return ServiceTransferDebtPaymentResult(existing[0]!!,transferId,state.first,state.second,state.third,true)
            }
            val account=db.rawQuery("SELECT account_type,currency_code,is_active FROM cash_accounts WHERE id=?",
                arrayOf(cashAccountId)).use{c->
                require(c.moveToFirst()&&c.getInt(2)==1){"حساب القبض غير موجود أو موقوف"}
                Triple(c.getString(0),c.getString(1),true)
            }
            require(account.second=="SYP"){"تحصيل ديون تحويل الرصيد متاح بالليرة السورية فقط"}
            when(account.first) {
                "CASH_BOX" -> {
                    val date=StoreTime.today(db)
                    val primary=db.rawQuery("SELECT primary_cash_account_id FROM daily_cash_sessions WHERE operating_date=?",
                        arrayOf(date)).use{if(it.moveToFirst())it.getString(0)else null}
                    require(primary==cashAccountId){"التحصيل النقدي يجب أن يذهب إلى درج اليوم الرئيسي"}
                }
                "WALLET" -> Unit
                else -> throw IllegalArgumentException("اختر الدرج اليومي الرئيسي أو محفظة إلكترونية نشطة")
            }
            val state=receivableState(db,transferId,transfer[0]!!.toLong())
            require(state.second>0L){"تم تسديد الدين بالكامل"}
            require(amountSypMinor<=state.second){"مبلغ التحصيل يتجاوز المتبقي المستحق"}
            val now=timestamp();val date=StoreTime.today(db)
            val paymentId=UUID.randomUUID().toString();val cashId=UUID.randomUUID().toString()
            db.insertOrThrow("cash_transactions",null,ContentValues().apply{
                put("id",cashId);put("account_id",cashAccountId);put("direction","IN")
                put("amount_minor",amountSypMinor);put("currency_code","SYP")
                put("transaction_type","SERVICE_TRANSFER_DEBT_COLLECTION")
                put("source_type","SERVICE_TRANSFER_DEBT_COLLECTION");put("source_id",transferId)
                put("operation_id",operationId);put("description",cleanNote?:"تحصيل دين تحويل رصيد • $transferId")
                put("created_by",actorUserId);put("created_at",now);put("operating_date",date)
            })
            db.insertOrThrow("service_transfer_receivable_payments",null,ContentValues().apply{
                put("id",paymentId);put("transfer_id",transferId);put("amount_minor",amountSypMinor)
                put("cash_transaction_id",cashId);put("operation_id",operationId);put("payload_hash",payloadHash)
                put("created_by",actorUserId);put("created_at",now);put("operating_date",date)
                if(cleanNote==null)putNull("note")else put("note",cleanNote)
            })
            val paid=Math.addExact(state.first,amountSypMinor)
            val status=if(paid==transfer[0]!!.toLong())"PAID" else "PARTIALLY_PAID"
            db.update("service_transfer_receivables",ContentValues().apply{put("status",status)},
                "transfer_id=? AND status IN ('OPEN','PARTIALLY_PAID')",arrayOf(transferId)).also{
                require(it==1){"تعذر تحديث حالة دين تحويل الرصيد"}
            }
            db.insertOrThrow("audit_log",null,ContentValues().apply{
                put("id",UUID.randomUUID().toString());put("user_id",actorUserId)
                put("action","SERVICE_TRANSFER_DEBT_COLLECTED");put("entity_type","SERVICE_TRANSFER_RECEIVABLE")
                put("entity_id",transferId)
                put("after_json","""{"payment_id":"$paymentId","amount_minor":$amountSypMinor,"status":"$status"}""")
                put("created_at",now)
            })
            db.setTransactionSuccessful()
            return ServiceTransferDebtPaymentResult(paymentId,transferId,paid,transfer[0]!!.toLong()-paid,status,false)
        } finally {db.endTransaction()}
    }

    private fun receivableState(db:SQLiteDatabase,transferId:String,charge:Long):Triple<Long,Long,String> {
        val paid=db.rawQuery("SELECT COALESCE(SUM(amount_minor),0) FROM service_transfer_receivable_payments WHERE transfer_id=?",
            arrayOf(transferId)).use{it.moveToFirst();it.getLong(0)}
        val outstanding=(charge-paid).coerceAtLeast(0L)
        return Triple(paid,outstanding,if(outstanding==0L)"PAID" else if(paid>0L)"PARTIALLY_PAID" else "OPEN")
    }
    private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it)}
    private fun timestamp()=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US)
        .apply{timeZone=TimeZone.getTimeZone("UTC")}.format(Date())
}
