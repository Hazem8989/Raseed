package com.hazem.raseedmanager

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val NORMAL_RATE = 41.34 / 43.0
private const val TOPUP_FEE_RATE = 0.02

private fun walletFor(service: String): String = when (service) {
    "سيريتل العادي", "سيريتل كاش", "سيريتل" -> "سيريتل"
    "شام كاش" -> "شام كاش"
    else -> service
}

private data class Tx(
    val id: Long,
    val company: String,
    val phone: String,
    val amount: Double,
    val profit: Double,
    val received: Double,
    val fee: Double,
    val time: Long
)

private data class Report(
    val count: Int,
    val profit: Double,
    val companyFees: Double,
    val topupFees: Double
) {
    val net: Double
        get() = profit - companyFees - topupFees
}

private class TxDb(context: Context) :
    SQLiteOpenHelper(context, "raseed_local.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE balances (" +
                "company TEXT PRIMARY KEY, " +
                "amount REAL NOT NULL DEFAULT 0)"
        )

        db.execSQL(
            "CREATE TABLE tx (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "company TEXT NOT NULL, " +
                "phone TEXT NOT NULL, " +
                "amount REAL NOT NULL, " +
                "commission REAL NOT NULL, " +
                "created_at INTEGER NOT NULL, " +
                "received_amount REAL NOT NULL DEFAULT 0, " +
                "gross_profit REAL NOT NULL DEFAULT 0, " +
                "company_fee REAL NOT NULL DEFAULT 0)"
        )

        db.execSQL(
            "CREATE TABLE topups (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "wallet TEXT NOT NULL, " +
                "amount REAL NOT NULL, " +
                "fee REAL NOT NULL, " +
                "created_at INTEGER NOT NULL)"
        )

        insertBalance(db, "سيريتل")
        insertBalance(db, "MTN")
        insertBalance(db, "شام كاش")
    }

    private fun insertBalance(db: SQLiteDatabase, name: String) {
        val values = ContentValues().apply {
            put("company", name)
            put("amount", 0.0)
        }
        db.insertWithOnConflict(
            "balances", null, values, SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            db.execSQL(
                "ALTER TABLE tx ADD COLUMN received_amount REAL NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE tx ADD COLUMN gross_profit REAL NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE tx ADD COLUMN company_fee REAL NOT NULL DEFAULT 0"
            )
            db.execSQL("UPDATE tx SET gross_profit = commission")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS topups (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "wallet TEXT NOT NULL, " +
                    "amount REAL NOT NULL, " +
                    "fee REAL NOT NULL, " +
                    "created_at INTEGER NOT NULL)"
            )
            insertBalance(db, "شام كاش")
        }
    }

    fun balance(walletName: String): Double {
        val wallet = walletFor(walletName)
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO balances(company, amount) VALUES(?, 0)",
            arrayOf(wallet)
        )

        readableDatabase.rawQuery(
            "SELECT amount FROM balances WHERE company = ?",
            arrayOf(wallet)
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getDouble(0) else 0.0
        }
    }

    fun addTransaction(
        service: String,
        phone: String,
        amount: Double,
        received: Double,
        profit: Double,
        fee: Double
    ): Long {
        val db = writableDatabase
        val wallet = walletFor(service)
        var id = -1L

        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO balances(company, amount) VALUES(?, 0)",
                arrayOf(wallet)
            )

            val values = ContentValues().apply {
                put("company", service)
                put("phone", phone)
                put("amount", amount)
                put("commission", profit)
                put("created_at", System.currentTimeMillis())
                put("received_amount", received)
                put("gross_profit", profit)
                put("company_fee", fee)
            }

            id = db.insertOrThrow("tx", null, values)
            db.execSQL(
                "UPDATE balances SET amount = amount - ? WHERE company = ?",
                arrayOf(amount, wallet)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
    }

    fun addTopUp(walletName: String, amount: Double): Long {
        val db = writableDatabase
        val wallet = walletFor(walletName)
        val fee = amount * TOPUP_FEE_RATE
        var id = -1L

        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR IGNORE INTO balances(company, amount) VALUES(?, 0)",
                arrayOf(wallet)
            )

            val values = ContentValues().apply {
                put("wallet", wallet)
                put("amount", amount)
                put("fee", fee)
                put("created_at", System.currentTimeMillis())
            }

            id = db.insertOrThrow("topups", null, values)
            db.execSQL(
                "UPDATE balances SET amount = amount + ? WHERE company = ?",
                arrayOf(amount, wallet)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
    }

    fun transactions(limit: Int = 50): List<Tx> {
        val result = mutableListOf<Tx>()
        readableDatabase.rawQuery(
            "SELECT id, company, phone, amount, gross_profit, " +
                "received_amount, company_fee, created_at " +
                "FROM tx ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    Tx(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getDouble(3),
                        cursor.getDouble(4),
                        cursor.getDouble(5),
                        cursor.getDouble(6),
                        cursor.getLong(7)
                    )
                )
            }
        }
        return result
    }

    fun report(since: Long): Report {
        var count = 0
        var profit = 0.0
        var fees = 0.0
        var topupFees = 0.0

        readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(gross_profit),0), " +
                "COALESCE(SUM(company_fee),0) FROM tx WHERE created_at >= ?",
            arrayOf(since.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                count = cursor.getInt(0)
                profit = cursor.getDouble(1)
                fees = cursor.getDouble(2)
            }
        }

        readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(fee),0) FROM topups WHERE created_at >= ?",
            arrayOf(since.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) topupFees = cursor.getDouble(0)
        }

        return Report(count, profit, fees, topupFees)
    }

    fun deleteTransaction(id: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var wallet = ""
            var amount = 0.0

            db.rawQuery(
                "SELECT company, amount FROM tx WHERE id = ?",
                arrayOf(id.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) return false
                wallet = walletFor(cursor.getString(0))
                amount = cursor.getDouble(1)
            }

            db.execSQL(
                "UPDATE balances SET amount = amount + ? WHERE company = ?",
                arrayOf(amount, wallet)
            )
            db.delete("tx", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }
}class MainActivity : Activity() {

    private lateinit var db: TxDb
    private lateinit var root: LinearLayout

    private val services = arrayOf(
        "سيريتل العادي",
        "سيريتل كاش",
        "شام كاش"
    )

    private fun fmt(value: Double): String {
        return String.format(Locale.US, "%,.3f", value)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun money(value: Double) = "${fmt(value)} ل.س"

    private fun text(
        value: String,
        size: Float = 16f,
        bold: Boolean = false
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(0xFF202020.toInt())
            setPadding(8, 10, 8, 10)
            if (bold) setTypeface(null, Typeface.BOLD)
        }
    }

    private fun field(hintText: String, numeric: Boolean = false) =
        EditText(this).apply {
            hint = hintText
            gravity = Gravity.CENTER
            setSingleLine(true)
            if (numeric) {
                inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
        }

    private fun addButton(title: String, action: () -> Unit) {
        root.addView(Button(this).apply {
            text = title
            isAllCaps = false
            setOnClickListener { action() }
        })
    }

    private fun showMessage(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("إغلاق", null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = TxDb(this)

        val prefs = getSharedPreferences("security", Context.MODE_PRIVATE)
        if (prefs.getString("pin", null) == null) {
            askPin(true)
        } else {
            askPin(false)
        }
    }

    private fun askPin(firstSetup: Boolean) {
        val input = field(
            if (firstSetup) "أنشئ رمز PIN" else "أدخل رمز PIN"
        ).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (firstSetup) "حماية التطبيق" else "تسجيل الدخول")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("متابعة", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val entered = input.text.toString().trim()

                    if (entered.length < 4) {
                        input.error = "أدخل 4 أرقام على الأقل"
                        return@setOnClickListener
                    }

                    val prefs = getSharedPreferences(
                        "security", Context.MODE_PRIVATE
                    )

                    if (firstSetup) {
                        prefs.edit().putString("pin", entered).apply()
                        dialog.dismiss()
                        showHome()
                    } else if (
                        entered == prefs.getString("pin", null)
                    ) {
                        dialog.dismiss()
                        showHome()
                    } else {
                        input.error = "الرمز غير صحيح"
                    }
                }
        }
        dialog.show()
    }

    private fun showHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(16, 12, 16, 24)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(text("رصيد المتجر اليومي", 24f, true))
        root.addView(text("إدارة التحويلات والأرباح"))

        addButton("عملية تحويل جديدة") { newTransaction() }
        addButton("شحن رصيد التاجر") { topUp() }
        addButton("عرض الأرصدة") { showBalances() }
        addButton("التقرير اليومي") { showReport(false) }
        addButton("التقرير الشهري") { showReport(true) }
        addButton("سجل آخر العمليات") { showTransactions() }
        addButton("تغيير رمز PIN") { changePin() }

        root.addView(text("الأرصدة الحالية", 18f, true))
        root.addView(text("سيريتل: ${money(db.balance("سيريتل"))}"))
        root.addView(text("شام كاش: ${money(db.balance("شام كاش"))}"))
    }

    private fun newTransaction() {
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            services
        )

        val phone = field("رقم الهاتف أو مرجع العملية")
        val amountField = field("المبلغ المخصوم أو مبلغ التحويل", true)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 8)
            addView(text("نوع الخدمة"))
            addView(spinner)
            addView(phone)
            addView(amountField)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("تسجيل تحويل")
            .setView(layout)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("متابعة", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val service = spinner.selectedItem.toString()
                    val phoneNumber = phone.text.toString().trim()
                    val amount = amountField.text.toString()
                        .replace(',', '.').toDoubleOrNull()

                    if (phoneNumber.isBlank()) {
                        phone.error = "أدخل الرقم أو المرجع"
                        return@setOnClickListener
                    }

                    if (amount == null || !amount.isFinite() || amount <= 0) {
                        amountField.error = "أدخل مبلغًا صحيحًا"
                        return@setOnClickListener
                    }

                    val received: Double
                    val profit: Double
                    val fee: Double

                    when (service) {
                        "سيريتل العادي" -> {
                            received = amount * NORMAL_RATE
                            profit = received * 0.20
                            fee = 0.0
                        }
                        "سيريتل كاش" -> {
                            received = amount
                            profit = amount * 0.10
                            fee = if (amount < 200.0) 1.5 else 2.0
                        }
                        else -> {
                            received = amount
                            profit = amount * 0.05
                            fee = 0.0
                        }
                    }

                    val wallet = walletFor(service)
                    val current = db.balance(wallet)

                    if (amount > current) {
                        showMessage(
                            "الرصيد غير كافٍ",
                            "الرصيد الحالي: ${money(current)}\n" +
                                "المبلغ المطلوب: ${money(amount)}"
                        )
                        return@setOnClickListener
                    }

                    val details = """
                        الخدمة: $service
                        المبلغ المخصوم: ${money(amount)}
                        المبلغ الواصل المحسوب: ${money(received)}
                        الربح الإجمالي: ${money(profit)}
                        رسوم الشركة: ${money(fee)}
                        صافي ربح العملية: ${money(profit - fee)}
                    """.trimIndent()

                    AlertDialog.Builder(this)
                        .setTitle("تأكيد العملية")
                        .setMessage(details)
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("حفظ") { _, _ ->
                            try {
                                db.addTransaction(
                                    service, phoneNumber, amount,
                                    received, profit, fee
                                )
                                Toast.makeText(
                                    this,
                                    "تم تسجيل العملية",
                                    Toast.LENGTH_LONG
                                ).show()
                                showHome()
                            } catch (e: Exception) {
                                showMessage(
                                    "خطأ",
                                    "تعذر حفظ العملية: ${e.message}"
                                )
                            }
                        }
                        .show()
                }
        }
        dialog.show()
    }

    private fun topUp() {
        val spinner = Spinner(this)
        val wallets = arrayOf("سيريتل", "شام كاش")

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            wallets
        )

        val amountField = field("قيمة الشحن بالليرة", true)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 8, 20, 8)
            addView(text("المحفظة"))
            addView(spinner)
            addView(amountField)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("شحن رصيد التاجر")
            .setView(layout)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("متابعة", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    val amount = amountField.text.toString()
                        .replace(',', '.').toDoubleOrNull()

                    if (amount == null || !amount.isFinite() || amount <= 0) {
                        amountField.error = "أدخل قيمة صحيحة"
                        return@setOnClickListener
                    }

                    val wallet = spinner.selectedItem.toString()
                    val fee = amount * TOPUP_FEE_RATE

                    AlertDialog.Builder(this)
                        .setTitle("تأكيد الشحن")
                        .setMessage(
                            "المحفظة: $wallet\n" +
                                "قيمة الشحن: ${money(amount)}\n" +
                                "مصروف الشحن 2%: ${money(fee)}"
                        )
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("تأكيد") { _, _ ->
                            try {
                                db.addTopUp(wallet, amount)
                                Toast.makeText(
                                    this,
                                    "تم تسجيل الشحن",
                                    Toast.LENGTH_LONG
                                ).show()
                                showHome()
                            } catch (e: Exception) {
                                showMessage(
                                    "خطأ",
                                    "تعذر تسجيل الشحن: ${e.message}"
                                )
                            }
                        }
                        .show()
                }
        }
        dialog.show()
    }

    private fun showBalances() {
        showMessage(
            "الأرصدة الحالية",
            "سيريتل: ${money(db.balance("سيريتل"))}\n" +
                "شام كاش: ${money(db.balance("شام كاش"))}"
        )
    }

    private fun showReport(monthly: Boolean) {
        val calendar = java.util.Calendar.getInstance()

        if (monthly) {
            calendar.set(java.util.Calendar.DAY_OF_MONTH, 1)
        }

        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)

        val report = db.report(calendar.timeInMillis)

        val title = if (monthly) "التقرير الشهري" else "التقرير اليومي"
        val message = """
            عدد التحويلات: ${report.count}
            إجمالي الربح: ${money(report.profit)}
            رسوم الشركات: ${money(report.companyFees)}
            مصروفات الشحن: ${money(report.topupFees)}
            صافي الربح: ${money(report.net)}

            رصيد سيريتل: ${money(db.balance("سيريتل"))}
            رصيد شام كاش: ${money(db.balance("شام كاش"))}
        """.trimIndent()

        showMessage(title, message)
    }

    private fun showTransactions() {
        val transactions = db.transactions(50)

        if (transactions.isEmpty()) {
            showMessage("سجل العمليات", "لا توجد عمليات مسجلة.")
            return
        }

        val labels = transactions.map {
            "${it.company} | ${money(it.amount)} | ربح ${money(it.profit)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("آخر 50 عملية")
            .setItems(labels) { _, index ->
                val tx = transactions[index]
                val date = SimpleDateFormat(
                    "yyyy-MM-dd HH:mm", Locale.getDefault()
                ).format(Date(tx.time))

                val details = """
                    الخدمة: ${tx.company}
                    الهاتف/المرجع: ${tx.phone}
                    التاريخ: $date
                    المبلغ المخصوم: ${money(tx.amount)}
                    المبلغ الواصل: ${money(tx.received)}
                    الربح: ${money(tx.profit)}
                    رسوم الشركة: ${money(tx.fee)}
                """.trimIndent()

                AlertDialog.Builder(this)
                    .setTitle("تفاصيل العملية")
                    .setMessage(details)
                    .setNegativeButton("إغلاق", null)
                    .setPositiveButton("حذف واسترداد الرصيد") { _, _ ->
                        AlertDialog.Builder(this)
                            .setTitle("تأكيد الحذف")
                            .setMessage(
                                "سيتم حذف العملية وإعادة المبلغ إلى الرصيد. هل تريد المتابعة؟"
                            )
                            .setNegativeButton("إلغاء", null)
                            .setPositiveButton("حذف") { _, _ ->
                                val deleted = db.deleteTransaction(tx.id)
                                Toast.makeText(
                                    this,
                                    if (deleted) "تم الحذف واسترداد الرصيد"
                                    else "تعذر حذف العملية",
                                    Toast.LENGTH_LONG
                                ).show()
                                showHome()
                            }
                            .show()
                    }
                    .show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun changePin() {
        val input = field("رمز PIN الجديد").apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        AlertDialog.Builder(this)
            .setTitle("تغيير رمز PIN")
            .setView(input)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val pin = input.text.toString().trim()

                if (pin.length < 4) {
                    Toast.makeText(
                        this,
                        "أدخل 4 أرقام على الأقل",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    getSharedPreferences(
                        "security", Context.MODE_PRIVATE
                    ).edit().putString("pin", pin).apply()

                    Toast.makeText(
                        this,
                        "تم تغيير الرمز",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }
}
