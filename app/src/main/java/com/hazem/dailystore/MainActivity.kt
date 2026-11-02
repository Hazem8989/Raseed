package com.hazem.dailystore

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.content.ContentValues
import com.hazem.dailystore.data.AppDatabase
import com.hazem.dailystore.data.BackupRestoreManager
import com.hazem.dailystore.data.AuthRepository
import com.hazem.dailystore.data.SecureSessionStore
import com.hazem.dailystore.data.CurrencyConverter
import com.hazem.dailystore.data.StoreRepository
import com.hazem.dailystore.data.InvoiceRepository
import com.hazem.dailystore.data.InvoiceLineInput
import com.hazem.dailystore.data.SupplierRepository
import com.hazem.dailystore.data.CustomerRepository
import com.hazem.dailystore.data.CustomerPaymentRepository
import com.hazem.dailystore.data.CashAccountRepository
import com.hazem.dailystore.data.CashAccountSummary
import com.hazem.dailystore.data.DailyBalanceRepository
import com.hazem.dailystore.data.ServiceTransferRepository
import com.hazem.dailystore.data.PurchasePaymentRepository
import com.hazem.dailystore.data.PurchasePaymentRecord
import com.hazem.dailystore.data.PurchaseInvoicePaymentSummary
import com.hazem.dailystore.data.StoreProduct
import com.hazem.dailystore.data.SpreadsheetProductImporter
import com.hazem.dailystore.data.InventoryValuationRepository
import com.hazem.dailystore.data.SalesProfitRepository
import com.hazem.dailystore.data.PermissionPolicy
import com.hazem.dailystore.data.InventoryAdjustmentRepository
import com.hazem.dailystore.data.SalesReturnRepository
import com.hazem.dailystore.data.SalesReturnItemInput
import com.hazem.dailystore.data.SalesRefundAllocationInput
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val green = Color.rgb(24, 75, 55)
    private val gold = Color.rgb(190, 155, 85)
    private val backgroundColor = Color.rgb(246, 248, 245)
    private val textColor = Color.rgb(48, 58, 51)
    private val mutedColor = Color.rgb(105, 115, 108)

    private val products = mutableListOf<Product>()
    private val cart = linkedMapOf<String, CartLine>()
    private var exchangeRateMilli = 0L
    private var searchQuery = ""
    private var pendingCheckoutDraftId: String? = null
    private var currentUserId: String? = null
    private var currentUserRole: String = "CASHIER"
    private var activeScreenKey: String = "POS"
    private var activeScreenArgument: String? = null
    private val productImportRequestCode = 7101
    private val backupCreateRequestCode = 7110
    private val backupRestoreRequestCode = 7111
    private var pendingBackupPassword: CharArray? = null
    private val sessionPrefs by lazy { getSharedPreferences("the_daily_shop_session", MODE_PRIVATE) }

    private lateinit var productGrid: GridLayout
    private lateinit var cartContainer: LinearLayout
    private lateinit var totalView: TextView
    private lateinit var countView: TextView
    private lateinit var searchField: EditText
    private lateinit var emptyProductsView: TextView

    private data class Product(
        val id: String,
        val name: String,
        val barcode: String?,
        val unit: String,
        val priceUsdCents: Long,
        val tracksInventory: Boolean,
        val stock: Double
    )

    private data class CartLine(val product: Product, var quantity: Double)

    @Deprecated("Using the document picker keeps compatibility with the app's current Activity-based navigation.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == backupCreateRequestCode || requestCode == backupRestoreRequestCode) {
            if (currentUserRole != "ADMIN") {
                pendingBackupPassword?.fill(0.toChar())
                pendingBackupPassword = null
                Toast.makeText(this, "النسخ الاحتياطي والاستعادة متاحان للمدير فقط", Toast.LENGTH_LONG).show()
                return
            }
            val password = pendingBackupPassword
            pendingBackupPassword = null
            val uri = data?.data
            if (resultCode != RESULT_OK || uri == null || password == null) {
                password?.fill(0.toChar())
                return
            }
            try {
                if (requestCode == backupCreateRequestCode) {
                    val output = contentResolver.openOutputStream(uri, "w")
                        ?: error("تعذر فتح ملف النسخة للحفظ")
                    output.use { BackupRestoreManager(this).createBackup(password, it) }
                    Toast.makeText(this, "تم إنشاء نسخة احتياطية مشفّرة بنجاح", Toast.LENGTH_LONG).show()
                } else {
                    val input = contentResolver.openInputStream(uri)
                        ?: error("تعذر فتح ملف النسخة المحدد")
                    input.use { BackupRestoreManager(this).restoreBackup(password, it) }
                    clearSessionState()
                    currentUserId = null
                    cart.clear()
                    showLoginScreen()
                    Toast.makeText(this, "تمت الاستعادة والتحقق من قاعدة البيانات. سجّل الدخول مجدداً.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                AlertDialog.Builder(this)
                    .setTitle(if (requestCode == backupCreateRequestCode) "تعذر إنشاء النسخة الاحتياطية" else "لم تتم الاستعادة")
                    .setMessage(e.message ?: "حدث خطأ غير متوقع. لم يتم اعتماد بيانات غير متحقق منها.")
                    .setPositiveButton("حسناً", null)
                    .show()
            } finally {
                password.fill(0.toChar())
            }
            return
        }
        if (requestCode != productImportRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val fileName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "products.csv"
            val rows = contentResolver.openInputStream(uri)?.use {
                SpreadsheetProductImporter.parse(fileName, it)
            } ?: error("تعذر فتح الملف المحدد")
            val store = StoreRepository(this)
            val rate = store.getCurrentExchangeRateMilli()
            if (rows.any { it.usesSypPrices } && rate <= 0L) {
                error("الملف يحتوي أسعاراً بالليرة السورية. سجّل سعر الصرف الحالي من شاشة إدارة المنتجات ثم أعد الاستيراد؛ لن أخمّن سعر التحويل.")
            }
            val sample = rows.take(5).joinToString("\n") { "• ${it.name}" }
            val currencyNote = if (rows.any { it.usesSypPrices })
                "سيُحوّل سعر الشراء والبيع من الليرة إلى مرجع الدولار بسعر الصرف المسجل حالياً: ${BigDecimal.valueOf(rate, 3).stripTrailingZeros().toPlainString()} ل.س لكل دولار."
            else "الأسعار مدخلة بالدولار وستُحفظ كما هي دون تحويل."
            AlertDialog.Builder(this)
                .setTitle("مراجعة استيراد الأصناف")
                .setMessage("عدد الأصناف: ${rows.size}\nالمعاينة:\n$sample\n\n$currencyNote\n\nسيتم حفظ جميع الأصناف في عملية واحدة أو إلغاء العملية كاملة عند وجود خطأ. سيبقى المخزون صفراً والباركود فارغاً إذا لم يرد في الملف.")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("اعتماد الاستيراد") { _, _ ->
                    try {
                        val count = store.importProducts(rows, rate, currentUserId ?: error("جلسة المستخدم غير صالحة"))
                        Toast.makeText(this, "تم استيراد $count صنفاً بنجاح. المخزون الافتتاحي بقي صفراً بانتظار الجرد.", Toast.LENGTH_LONG).show()
                        showProductManagement()
                    } catch (e: Exception) {
                        AlertDialog.Builder(this).setTitle("لم يتم الاستيراد")
                            .setMessage(e.message ?: "حدث خطأ أثناء الاستيراد؛ لم يتم حفظ أي صنف.")
                            .setPositiveButton("حسناً", null).show()
                    }
                }.show()
        } catch (e: Exception) {
            AlertDialog.Builder(this).setTitle("تعذر قراءة الملف")
                .setMessage(e.message ?: "تأكد من أن الملف بصيغة CSV أو XLSX وأن الأعمدة صحيحة.")
                .setPositiveButton("حسناً", null).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = green
        window.navigationBarColor = green
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        StoreRepository(this).ensureBaseSetup()
        AuthRepository(this).ensureInitialAdmin()
        ServiceTransferRepository(this).ensureSystemAccounts()
        if (restoreSession(savedInstanceState)) restoreActiveScreen() else {
            clearSessionState()
            showLoginScreen()
        }
    }

    override fun setContentView(view: View) {
        assignStableViewIds(view, intArrayOf(1))
        super.setContentView(view)
    }

    private fun assignStableViewIds(view: View, next: IntArray) {
        view.id = STABLE_VIEW_ID_BASE + next[0]++
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) assignStableViewIds(view.getChildAt(index), next)
        }
    }

    override fun onPause() {
        persistSessionState()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        persistSessionState()
        outState.putString(STATE_SCREEN, activeScreenKey)
        outState.putString(STATE_SCREEN_ARGUMENT, activeScreenArgument)
        outState.putString(STATE_CART, serializeCart())
        outState.putString(STATE_CHECKOUT_DRAFT, pendingCheckoutDraftId)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API; retained for compatibility with the current Activity navigation")
    override fun onBackPressed() {
        if (currentUserId.isNullOrBlank()) {
            super.onBackPressed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("إغلاق التطبيق")
            .setMessage("سيتم إغلاق التطبيق فقط. عند فتحه مجدداً ستعود إلى الصفحة التي كنت تعمل عليها. لتسجيل الخروج استخدم القائمة الجانبية.")
            .setNegativeButton("متابعة العمل", null)
            .setPositiveButton("إغلاق التطبيق") { _, _ ->
                persistSessionState()
                finish()
            }
            .show()
    }

    private fun persistSessionState() {
        val editor = sessionPrefs.edit()
            .putString(SESSION_SCREEN, activeScreenKey)
            .putString(SESSION_SCREEN_ARGUMENT, activeScreenArgument)
            .putString(SESSION_CART, serializeCart())
        SecureSessionStore.save(this, currentUserId)
        if (pendingCheckoutDraftId != null) editor.putString(SESSION_CHECKOUT_DRAFT, pendingCheckoutDraftId)
        else editor.remove(SESSION_CHECKOUT_DRAFT)
        editor.apply()
    }

    private fun serializeCart(): String {
        val array = JSONArray()
        cart.values.forEach { line ->
            array.put(JSONObject().put("product_id", line.product.id).put("quantity", line.quantity))
        }
        return array.toString()
    }

    private fun restoreSession(state: Bundle?): Boolean {
        val userId = SecureSessionStore.load(this) ?: return false
        val user = AuthRepository(this).restoreActiveSession(userId) ?: return false
        if (user.mustChangePassword) return false
        currentUserId = user.id
        currentUserRole = user.roleKey
        activeScreenKey = state?.getString(STATE_SCREEN)
            ?: sessionPrefs.getString(SESSION_SCREEN, "POS") ?: "POS"
        activeScreenArgument = state?.getString(STATE_SCREEN_ARGUMENT)
            ?: sessionPrefs.getString(SESSION_SCREEN_ARGUMENT, null)
        pendingCheckoutDraftId = state?.getString(STATE_CHECKOUT_DRAFT)
            ?: sessionPrefs.getString(SESSION_CHECKOUT_DRAFT, null)
        var cartSnapshot = state?.getString(STATE_CART) ?: sessionPrefs.getString(SESSION_CART, "[]")
        if (pendingCheckoutDraftId != null && !isDraftSalesInvoice(pendingCheckoutDraftId!!)) {
            pendingCheckoutDraftId = null
            cartSnapshot = "[]"
            sessionPrefs.edit().remove(SESSION_CHECKOUT_DRAFT).putString(SESSION_CART, "[]").apply()
        }
        restoreCart(cartSnapshot)
        return true
    }

    private fun isDraftSalesInvoice(invoiceId: String): Boolean =
        AppDatabase.getInstance(this).readableDatabase.rawQuery(
            "SELECT 1 FROM sales_invoices WHERE id=? AND status='DRAFT' LIMIT 1",
            arrayOf(invoiceId)
        ).use { it.moveToFirst() }

    private fun restoreCart(snapshot: String?) {
        cart.clear()
        loadCatalog()
        val productsById = products.associateBy { it.id }
        runCatching {
            val array = JSONArray(snapshot ?: "[]")
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val product = productsById[item.getString("product_id")] ?: continue
                val quantity = item.getDouble("quantity")
                if (quantity > 0.0) cart[product.id] = CartLine(product, quantity)
            }
        }
    }

    private fun restoreActiveScreen() {
        when (activeScreenKey) {
            "PRODUCTS" -> showProductManagement()
            "PURCHASES" -> showPurchases()
            "SUPPLIERS" -> showSuppliers()
            "SUPPLIER_DETAILS" -> showSupplierDetails(activeScreenArgument ?: "")
            "CUSTOMERS" -> showCustomers()
            "CUSTOMER_ACCOUNT" -> showCustomerAccount(activeScreenArgument ?: "")
            "CASH_ACCOUNTS" -> showCashAccounts()
            "DAILY_REPORT" -> showDailyCashReport()
            "GENERAL_REPORTS" -> showGeneralReports()
            "SERVICE_TRANSFERS" -> showServiceTransfers()
            "PURCHASE_EDITOR" -> showPurchaseInvoiceEditor()
            "USER_MANAGEMENT" -> showUserManagement()
            else -> showPointOfSale(preserveCart = true)
        }
    }

    private fun markScreen(key: String, argument: String? = null) {
        activeScreenKey = key
        activeScreenArgument = argument
        sessionPrefs.edit().putString(SESSION_SCREEN, key)
            .putString(SESSION_SCREEN_ARGUMENT, argument).apply()
    }

    private fun clearSessionState() {
        currentUserId = null
        currentUserRole = "CASHIER"
        pendingCheckoutDraftId = null
        activeScreenKey = "LOGIN"
        activeScreenArgument = null
        cart.clear()
        sessionPrefs.edit().clear().apply()
        SecureSessionStore.clear(this)
    }

    private fun showLoginScreen() {
        markScreen("LOGIN")
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(24), dp(26), dp(24))
            setBackgroundColor(backgroundColor)
        }
        val brand = label("THE DAILY SHOP", 27f, green, true, Gravity.CENTER)
        brand.letterSpacing = 0.08f
        val subtitle = label("إدارة متجرك بسهولة واحترافية", 15f, mutedColor, false, Gravity.CENTER)
        subtitle.setPadding(0, dp(8), 0, dp(22))
        val divider = label("━━━━━━━━━━━━", 15f, gold, true, Gravity.CENTER)
        divider.setPadding(0, 0, 0, dp(18))
        val title = label("تسجيل الدخول", 22f, green, true, Gravity.CENTER)
        title.setPadding(0, 0, 0, dp(20))
        val username = EditText(this).apply {
            hint = "اسم المستخدم"
            textSize = 16f
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
            background = inputBackground()
        }
        val password = EditText(this).apply {
            hint = "كلمة المرور"
            textSize = 16f
            setSingleLine(true)
            gravity = Gravity.CENTER_VERTICAL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(16), 0, dp(16), 0)
            background = inputBackground()
        }
        val login = Button(this).apply {
            text = "دخول"
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            isAllCaps = false
            setOnClickListener {
                val authenticated = AuthRepository(this@MainActivity).authenticate(
                    username.text.toString(), password.text.toString()
                )
                if (authenticated != null) {
                    if (authenticated.mustChangePassword) {
                        showForcedPasswordChange(authenticated, password.text.toString())
                    } else {
                        currentUserId = authenticated.id
                        currentUserRole = authenticated.roleKey
                        SecureSessionStore.save(this@MainActivity, authenticated.id)
                        sessionPrefs.edit().putString(SESSION_SCREEN, "POS")
                            .putString(SESSION_CART, "[]").apply()
                        showPointOfSale()
                    }
                } else Toast.makeText(
                    this@MainActivity, "اسم المستخدم أو كلمة المرور غير صحيحة", Toast.LENGTH_SHORT
                ).show()
            }
        }
        val footer = label("الإصدار 1.0.0", 12f, Color.GRAY, false, Gravity.CENTER)
        footer.setPadding(0, dp(20), 0, 0)
        page.addView(brand, matchWidth())
        page.addView(subtitle, matchWidth())
        page.addView(divider, matchWidth())
        page.addView(title, matchWidth())
        page.addView(username, fieldParams(dp(54), dp(12)))
        page.addView(password, fieldParams(dp(54), dp(18)))
        page.addView(login, fieldParams(dp(52), 0))
        page.addView(footer, matchWidth())
        setContentView(page)
    }

    private fun showForcedPasswordChange(user: com.hazem.dailystore.data.AuthenticatedUser, currentPassword: String) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), dp(4))
        }
        val newPassword = EditText(this).apply {
            hint = "كلمة المرور الجديدة (10 أحرف على الأقل)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val confirmPassword = EditText(this).apply {
            hint = "تأكيد كلمة المرور الجديدة"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        content.addView(newPassword, fieldParams(dp(54), dp(8)))
        content.addView(confirmPassword, fieldParams(dp(54), dp(12)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("تغيير كلمة المرور إلزامي")
            .setMessage("لأمان المتجر، يجب تعيين كلمة مرور قوية قبل الدخول.")
            .setView(content)
            .setCancelable(false)
            .setPositiveButton("حفظ كلمة المرور", null)
            .setNegativeButton("تسجيل خروج", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                clearSessionState()
                dialog.dismiss()
                showLoginScreen()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val proposed = newPassword.text.toString()
                if (proposed != confirmPassword.text.toString()) {
                    confirmPassword.error = "كلمتا المرور غير متطابقتين"
                    return@setOnClickListener
                }
                try {
                    AuthRepository(this).changePassword(user.id, currentPassword, proposed)
                    currentUserId = user.id
                    currentUserRole = user.roleKey
                    SecureSessionStore.save(this@MainActivity, user.id)
                    sessionPrefs.edit().putString(SESSION_SCREEN, "POS")
                        .putString(SESSION_CART, "[]").apply()
                    dialog.dismiss()
                    showPointOfSale()
                } catch (e: IllegalArgumentException) {
                    newPassword.error = e.message ?: "تعذر تغيير كلمة المرور"
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر تغيير كلمة المرور", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showPointOfSale(preserveCart: Boolean = false) {
        markScreen("POS")
        if (!preserveCart) {
            cart.clear()
            sessionPrefs.edit().putString(SESSION_CART, "[]").apply()
            loadCatalog()
        } else if (products.isEmpty()) loadCatalog()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val menu = Button(this).apply {
            text = "☰"
            textSize = 23f
            setTextColor(green)
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showNavigationMenu(it) }
        }
        val brandBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        brandBlock.addView(label("THE DAILY SHOP", 17f, green, true, Gravity.CENTER))
        brandBlock.addView(label("نظام إدارة المتجر", 11f, mutedColor, false, Gravity.CENTER))
        val status = label("● جاهز للبيع", 12f, green, true, Gravity.CENTER)
        header.addView(menu, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(brandBlock, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(status, LinearLayout.LayoutParams(-2, -2))

        val body = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        val pageTitle = label("نقطة البيع", 25f, green, true, Gravity.RIGHT)
        pageTitle.setPadding(0, 0, 0, dp(4))
        val subtitle = label("ابحث عن المنتج أو اختره لإضافته إلى الفاتورة", 13f, mutedColor, false, Gravity.RIGHT)
        subtitle.setPadding(0, 0, 0, dp(14))
        searchField = EditText(this).apply {
            hint = "⌕  اسم المنتج أو الباركود"
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = inputBackground()
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim().orEmpty()
                renderProducts()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        content.addView(pageTitle, matchWidth())
        content.addView(subtitle, matchWidth())
        content.addView(searchField, fieldParams(dp(52), dp(16)))
        content.addView(sectionHeading("المنتجات"))
        productGrid = GridLayout(this).apply { columnCount = 2 }
        emptyProductsView = label(
            "لا توجد منتجات مسجلة بعد\nأضف المنتجات من قسم إدارة المنتجات لتظهر هنا",
            15f, mutedColor, false, Gravity.CENTER
        ).apply { setPadding(dp(12), dp(30), dp(12), dp(30)) }
        content.addView(emptyProductsView, matchWidth())
        content.addView(productGrid, matchWidth())
        content.addView(sectionHeading("سلة الفاتورة"))
        countView = label("0 أصناف", 12f, mutedColor, false, Gravity.RIGHT)
        countView.setPadding(0, 0, 0, dp(8))
        content.addView(countView, matchWidth())
        cartContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        content.addView(cartContainer, matchWidth())
        totalView = label("الإجمالي\n0 ل.س", 21f, green, true, Gravity.CENTER)
        totalView.background = cardBackground()
        totalView.setPadding(dp(12), dp(14), dp(12), dp(14))
        val totalParams = matchWidth().apply { topMargin = dp(12) }
        content.addView(totalView, totalParams)
        val checkout = Button(this).apply {
            text = "متابعة إلى الدفع"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            setOnClickListener { showCheckoutDialog() }
        }
        content.addView(checkout, fieldParams(dp(54), dp(8)))
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        renderProducts()
        renderCart()
    }

    private fun cartTotalUsdCents(): Long = cart.values.fold(0L) { total, line ->
        val lineTotal = BigDecimal.valueOf(line.product.priceUsdCents)
            .multiply(BigDecimal.valueOf(line.quantity))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
        Math.addExact(total, lineTotal)
    }

    private fun showCheckoutDialog() {
        if (cart.isEmpty()) {
            Toast.makeText(this, "أضف صنفاً واحداً على الأقل قبل متابعة الدفع", Toast.LENGTH_SHORT).show()
            return
        }
        val customers = CustomerRepository(this).listActive()
        val cashAccountRepository = CashAccountRepository(this)
        val dailyDrawerId = cashAccountRepository.currentDayPrimaryDrawer()?.id
        val accounts = cashAccountRepository.listActive().filter {
            it.accountType == "WALLET" ||
                (it.accountType == "CASH_BOX" &&
                    (it.currencyCode == "USD" || (it.currencyCode == "SYP" && it.id == dailyDrawerId)))
        }
        val totalUsd = cartTotalUsdCents()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        content.addView(label("إجمالي الفاتورة", 13f, mutedColor, false, Gravity.RIGHT))
        content.addView(label(formatDualMoney(totalUsd, exchangeRateMilli), 17f, green, true, Gravity.RIGHT)
            .apply { setPadding(0, dp(4), 0, dp(12)) })

        val mode = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val cashMode = RadioButton(this).apply { id = View.generateViewId(); text = "بيع نقدي"; isChecked = accounts.isNotEmpty(); isEnabled = accounts.isNotEmpty() }
        val creditMode = RadioButton(this).apply { id = View.generateViewId(); text = "بيع آجل"; isEnabled = true }
        mode.addView(cashMode)
        mode.addView(creditMode)
        if (accounts.isEmpty()) creditMode.isChecked = true
        content.addView(label("نوع البيع", 13f, mutedColor, true, Gravity.RIGHT))
        content.addView(mode, fieldParams(dp(48), dp(8)))

        val customerBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        customerBlock.addView(label("الزبون", 12f, mutedColor, false, Gravity.RIGHT))
        val customerSpinner = Spinner(this)
        customerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            customers.map { it.name })
        customerBlock.addView(customerSpinner, fieldParams(dp(48), dp(8)))
        if (customers.isEmpty()) customerBlock.addView(label(
            "لا يوجد زبائن نشطون حالياً. أضف زبوناً من قسم العملاء والديون قبل اعتماد البيع الآجل.",
            11f, gold.toInt(), true, Gravity.RIGHT
        ).apply { setPadding(0, dp(4), 0, dp(6)) })

        val accountBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        accountBlock.addView(label("الصندوق أو المحفظة التي ستستلم قيمة البيع كاملة", 12f, mutedColor, false, Gravity.RIGHT))
        val accountSpinner = Spinner(this)
        accountSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            accounts.map { it.name + " • " + it.currencyCode })
        accountBlock.addView(accountSpinner, fieldParams(dp(48), dp(8)))
        val receiptHint = label("", 11f, mutedColor, false, Gravity.RIGHT)
        accountBlock.addView(receiptHint)
        fun refreshMode() {
            val isCredit = mode.checkedRadioButtonId == creditMode.id
            customerBlock.visibility = if (isCredit) View.VISIBLE else View.GONE
            accountBlock.visibility = if (isCredit) View.GONE else View.VISIBLE
            val account = accounts.getOrNull(accountSpinner.selectedItemPosition)
            receiptHint.text = if (account == null) "" else {
                val amount = if (account.currencyCode == "USD") "$" + moneyText(totalUsd)
                else if (exchangeRateMilli > 0L) formatSypAmount(CurrencyConverter.usdCentsToSyp(totalUsd, exchangeRateMilli)) + " ل.س"
                else "يلزم تسجيل سعر الصرف أولاً"
                "سيُسجّل قبض كامل بقيمة " + amount
            }
        }
        accountSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { refreshMode() }
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { refreshMode() }
        }
        mode.setOnCheckedChangeListener { _, _ -> refreshMode() }
        content.addView(customerBlock, matchWidth())
        content.addView(accountBlock, matchWidth())
        refreshMode()

        val dialog = AlertDialog.Builder(this).setTitle("إتمام عملية البيع")
            .setView(content).setNegativeButton("رجوع", null)
            .setPositiveButton("اعتماد الفاتورة", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val isCredit = mode.checkedRadioButtonId == creditMode.id
                    if (isCredit) require(customers.isNotEmpty()) {
                        "أضف زبوناً نشطاً من قسم العملاء والديون أولاً"
                    }
                    val customerId = if (isCredit) customers.getOrNull(customerSpinner.selectedItemPosition)?.id
                        ?: error("اختر الزبون الذي ستسجل الفاتورة على حسابه") else null
                    val account = if (isCredit) null else accounts.getOrNull(accountSpinner.selectedItemPosition)
                        ?: error("اختر الصندوق أو المحفظة التي ستستلم قيمة البيع")
                    if (isCredit) require(exchangeRateMilli > 0L) { "سجّل سعر الصرف قبل اعتماد البيع الآجل" }
                    if (account?.currencyCode == "SYP") require(exchangeRateMilli > 0L) {
                        "سجّل سعر الصرف قبل قبض البيع بالليرة السورية"
                    }
                    val saleType = if (isCredit) "CREDIT" else "CASH"
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    val lines = cart.values.map { line ->
                        InvoiceLineInput(line.product.id, BigDecimal.valueOf(line.quantity), line.product.priceUsdCents)
                    }
                    val saved = InvoiceRepository(this).saveSalesDraft(currentUserId ?: error("جلسة المستخدم غير صالحة"), 
                        UUID.randomUUID().toString(), pendingCheckoutDraftId, customerId,
                        date, 0L, null, lines, saleType
                    )
                    pendingCheckoutDraftId = saved.invoiceId
                    sessionPrefs.edit().putString(SESSION_CHECKOUT_DRAFT, pendingCheckoutDraftId).apply()
                    val posted = if (isCredit) {
                        InvoiceRepository(this).postSale(currentUserId ?: error("جلسة المستخدم غير صالحة"), saved.invoiceId, UUID.randomUUID().toString())
                    } else {
                        InvoiceRepository(this).postCashSale(currentUserId ?: error("جلسة المستخدم غير صالحة"), saved.invoiceId, UUID.randomUUID().toString(), account!!.id)
                    }
                    require(posted) { "الفاتورة مسجلة مسبقاً؛ حدّث الشاشة لمراجعة حالتها" }
                    val invoiceNumber = saved.invoiceNumber
                    pendingCheckoutDraftId = null
                    sessionPrefs.edit().remove(SESSION_CHECKOUT_DRAFT)
                        .putString(SESSION_CART, "[]").apply()
                    cart.clear()
                    dialog.dismiss()
                    Toast.makeText(
                        this,
                        if (isCredit) "تم اعتماد البيع الآجل على حساب الزبون • فاتورة " + invoiceNumber
                        else "تم البيع وقبض كامل القيمة وتحديث الصندوق • فاتورة " + invoiceNumber,
                        Toast.LENGTH_LONG
                    ).show()
                    showPointOfSale()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر إتمام البيع؛ لم يتم ترحيل المخزون أو قبض المبلغ", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun loadCatalog() {
        products.clear()
        val db = AppDatabase.getInstance(this).readableDatabase
        db.rawQuery(
            """SELECT p.id, p.name, p.barcode, u.symbol, p.sale_price_usd_cents,
                      p.track_inventory, COALESCE(SUM(ib.quantity), 0)
               FROM products p
               JOIN units u ON u.id = p.unit_id
               LEFT JOIN inventory_balances ib ON ib.product_id = p.id
               WHERE p.is_active = 1
               GROUP BY p.id
               ORDER BY p.name COLLATE NOCASE""".trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                products.add(
                    Product(
                        cursor.getString(0), cursor.getString(1),
                        if (cursor.isNull(2)) null else cursor.getString(2),
                        cursor.getString(3), cursor.getLong(4),
                        cursor.getInt(5) == 1, cursor.getDouble(6)
                    )
                )
            }
        }
        exchangeRateMilli = db.rawQuery(
            "SELECT rate_syp_per_usd_milli FROM exchange_rates ORDER BY effective_at DESC, created_at DESC LIMIT 1",
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
    }

    private fun renderProducts() {
        if (!::productGrid.isInitialized) return
        productGrid.removeAllViews()
        val filtered = products.filter {
            searchQuery.isBlank() ||
                it.name.contains(searchQuery, true) ||
                (it.barcode?.contains(searchQuery, true) == true)
        }
        emptyProductsView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        productGrid.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        filtered.forEach { product ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(14), dp(10), dp(14))
                background = cardBackground()
                isClickable = true
                isFocusable = true
                setOnClickListener { addToCart(product, 1.0) }
                setOnLongClickListener {
                    showQuantityDialog(product)
                    true
                }
            }
            card.addView(label(product.name, 15f, textColor, true, Gravity.CENTER))
            val price = formatPrice(product.priceUsdCents)
            card.addView(label(price, 16f, green, true, Gravity.CENTER).apply {
                setPadding(0, dp(8), 0, dp(4))
            })
            card.addView(label(
                if (product.tracksInventory) "المتوفر: ${formatQuantity(product.stock)} ${product.unit}" else "غير خاضع للمخزون",
                11f, mutedColor, false, Gravity.CENTER
            ))
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = -2
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            }
            productGrid.addView(card, params)
        }
    }

    private fun showQuantityDialog(product: Product) {
        val quantityInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 20f
            gravity = Gravity.CENTER
            setText("1")
            selectAll()
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(label("أدخل الكمية التي تريد إضافتها من:", 14f, mutedColor, false, Gravity.CENTER))
            addView(label(product.name, 17f, green, true, Gravity.CENTER).apply {
                setPadding(0, dp(8), 0, dp(8))
            })
            addView(quantityInput, fieldParams(dp(52), 0))
        }
        AlertDialog.Builder(this)
            .setTitle("إضافة كمية")
            .setView(container)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إضافة") { _, _ ->
                val quantity = quantityInput.text.toString().toDoubleOrNull()
                if (quantity == null || quantity <= 0.0) {
                    Toast.makeText(this, "أدخل كمية صحيحة أكبر من صفر", Toast.LENGTH_SHORT).show()
                } else addToCart(product, quantity)
            }
            .show()
    }

    private fun addToCart(product: Product, quantity: Double) {
        val current = cart[product.id]?.quantity ?: 0.0
        val requested = current + quantity
        if (product.tracksInventory && requested > product.stock + 0.000001) {
            Toast.makeText(
                this, "الكمية المطلوبة أكبر من المتوفر (${formatQuantity(product.stock)})",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        val line = cart[product.id]
        if (line == null) cart[product.id] = CartLine(product, quantity)
        else line.quantity = requested
        renderCart()
        Toast.makeText(this, "تمت إضافة ${formatQuantity(quantity)} من ${product.name}", Toast.LENGTH_SHORT).show()
    }

    private fun renderCart() {
        sessionPrefs.edit().putString(SESSION_CART, serializeCart()).apply()
        if (!::cartContainer.isInitialized) return
        cartContainer.removeAllViews()
        if (cart.isEmpty()) {
            cartContainer.addView(label("السلة فارغة\nاضغط على المنتج لإضافة قطعة، أو اضغط مطولاً لاختيار الكمية", 13f, mutedColor, false, Gravity.CENTER).apply {
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
        } else {
            cart.values.forEach { line ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(label(line.product.name, 14f, textColor, true, Gravity.RIGHT))
                info.addView(label(
                    "${formatQuantity(line.quantity)} ${line.product.unit} × ${formatPrice(line.product.priceUsdCents)}",
                    12f, mutedColor, false, Gravity.RIGHT
                ))
                val minus = smallButton("−") { changeCartQuantity(line, -1.0) }
                val plus = smallButton("+") { changeCartQuantity(line, 1.0) }
                val remove = smallButton("×") { cart.remove(line.product.id); renderCart() }
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(minus, LinearLayout.LayoutParams(dp(36), dp(36)))
                row.addView(label(formatQuantity(line.quantity), 13f, green, true, Gravity.CENTER).apply {
                    minWidth = dp(38)
                }, LinearLayout.LayoutParams(dp(42), -2))
                row.addView(plus, LinearLayout.LayoutParams(dp(36), dp(36)))
                row.addView(remove, LinearLayout.LayoutParams(dp(36), dp(36)))
                cartContainer.addView(row)
                val divider = View(this).apply { setBackgroundColor(Color.rgb(232, 235, 231)) }
                cartContainer.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        countView.text = "${cart.size} أصناف"
        val totalUsd = cart.values.sumOf {
            BigDecimal.valueOf(it.product.priceUsdCents)
                .multiply(BigDecimal.valueOf(it.quantity))
                .setScale(0, RoundingMode.HALF_UP).longValueExact()
        }
        totalView.text = "الإجمالي\n${formatPrice(totalUsd)}"
    }

    private fun changeCartQuantity(line: CartLine, delta: Double) {
        val updated = line.quantity + delta
        if (updated <= 0.0) cart.remove(line.product.id)
        else if (line.product.tracksInventory && updated > line.product.stock) {
            Toast.makeText(this, "لا توجد كمية إضافية كافية في المخزون", Toast.LENGTH_SHORT).show()
        } else line.quantity = updated
        renderCart()
    }

    private fun showBackupRestoreOptions() {
        if (currentUserRole != "ADMIN") {
            Toast.makeText(this, "النسخ الاحتياطي والاستعادة متاحان للمدير فقط", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("النسخ الاحتياطي والاستعادة")
            .setItems(arrayOf("إنشاء نسخة احتياطية مشفّرة", "استعادة نسخة احتياطية")) { _, choice ->
                if (choice == 0) requestBackupPassword(restore = false)
                else AlertDialog.Builder(this)
                    .setTitle("تنبيه قبل الاستعادة")
                    .setMessage("ستستبدل الاستعادة بيانات المتجر الموجودة على هذا الهاتف ببيانات النسخة المختارة. لن يبدأ الاستبدال إلا بعد فك التشفير والتحقق من سلامة قاعدة البيانات، مع الاحتفاظ بنسخة تراجع مؤقتة.")
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("متابعة") { _, _ -> requestBackupPassword(restore = true) }
                    .show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun requestBackupPassword(restore: Boolean) {
        val passwordField = EditText(this).apply {
            hint = "كلمة مرور النسخة (10 أحرف على الأقل)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(passwordField, matchWidth())
            addView(label(
                if (restore) "أدخل كلمة المرور التي استُخدمت عند إنشاء هذه النسخة."
                else "احتفظ بكلمة المرور في مكان آمن؛ لا يمكن استعادة النسخة دونها.",
                12f, mutedColor, false, Gravity.RIGHT
            ))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (restore) "كلمة مرور النسخة" else "حماية النسخة الاحتياطية")
            .setView(content)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton(if (restore) "اختيار ملف النسخة" else "حفظ النسخة") { _, _ -> }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = passwordField.text.toString()
                if (password.length < 10) {
                    passwordField.error = "استخدم 10 أحرف على الأقل"
                    return@setOnClickListener
                }
                pendingBackupPassword?.fill(0.toChar())
                pendingBackupPassword = password.toCharArray()
                val intent = if (restore) {
                    android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                    }
                } else {
                    android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE)
                        type = "application/octet-stream"
                        putExtra(
                            android.content.Intent.EXTRA_TITLE,
                            "The_Daily_Shop_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.tdsbackup"
                        )
                    }
                }
                try {
                    startActivityForResult(intent, if (restore) backupRestoreRequestCode else backupCreateRequestCode)
                    dialog.dismiss()
                } catch (e: Exception) {
                    pendingBackupPassword?.fill(0.toChar())
                    pendingBackupPassword = null
                    Toast.makeText(this, "لا يتوفر مدير ملفات لاختيار موقع النسخة", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showNavigationMenu(anchor: View) {
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setBackgroundColor(Color.WHITE)
        }
        menu.addView(label("THE DAILY SHOP", 18f, green, true, Gravity.RIGHT).apply {
            setPadding(0, 0, 0, dp(18))
        })
        val entries = listOf(
            "نقطة البيع" to "واجهة البيع الرئيسية",
            "المنتجات والمخزون" to "إدارة المنتجات والكميات",
            "تسويات المخزون" to "تسجيل فروقات الجرد بالزيادة أو النقصان",
            "مرتجعات المبيعات" to "إرجاع أصناف ورد المبالغ للعملاء",
            "المشتريات" to "فواتير الشراء والموردون",
            "العملاء والديون" to "ملفات العملاء والأرصدة",
            "الصناديق والمحافظ" to "الحركة النقدية والحسابات",
            "تحويل رصيد" to "سيرياتيل العادي وكاش وشام كاش",
            "التقارير" to "المبيعات والأرباح والمخزون",
            "النسخ الاحتياطي والاستعادة" to "حفظ بيانات المتجر واستعادتها بأمان",
            "المستخدمون والصلاحيات" to "إضافة الموظفين والتحكم بصلاحياتهم"
        )
        lateinit var popup: PopupWindow
        val visibleEntries = entries.filter { entry ->
            when (entry.first) {
                "تسويات المخزون" -> hasPermission(PermissionPolicy.INVENTORY_ADJUST)
                "مرتجعات المبيعات" -> hasPermission(PermissionPolicy.RETURNS_PROCESS)
                "المنتجات والمخزون", "المشتريات", "التقارير", "النسخ الاحتياطي والاستعادة", "المستخدمون والصلاحيات" -> isManager()
                else -> true
            }
        }
        visibleEntries.forEach { (title, description) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                addView(label(title, 15f, textColor, true, Gravity.RIGHT))
                addView(label(description, 11f, mutedColor, false, Gravity.RIGHT))
                setOnClickListener {
                    popup.dismiss()
                    when (title) {
                        "نقطة البيع" -> showPointOfSale()
                        "المنتجات والمخزون" -> showProductManagement()
                        "تسويات المخزون" -> showInventoryAdjustments()
                        "مرتجعات المبيعات" -> showSalesReturns()
                        "المشتريات" -> showPurchases()
                        "العملاء والديون" -> showCustomers()
                        "الصناديق والمحافظ" -> showCashAccounts()
                        "تحويل رصيد" -> showServiceTransfers()
                        "التقارير" -> showGeneralReports()
                        "النسخ الاحتياطي والاستعادة" -> showBackupRestoreOptions()
                        "المستخدمون والصلاحيات" -> showUserManagement()
                        else -> Toast.makeText(this@MainActivity, "قسم $title سيُفعّل في مرحلته", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            menu.addView(item, matchWidth())
        }
        val logout = Button(this).apply {
            text = "تسجيل الخروج"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            setOnClickListener {
                popup.dismiss()
                clearSessionState()
                showLoginScreen()
            }
        }
        menu.addView(logout, fieldParams(dp(50), 0))
        popup = PopupWindow(menu, dp(285), -1, true).apply {
            elevation = dp(12).toFloat()
            setBackgroundDrawable(cardBackground())
        }
        popup.showAtLocation(window.decorView, Gravity.END, 0, 0)
    }


    private data class PurchaseLineDraft(
        val product: StoreProduct,
        var quantity: BigDecimal,
        var unitCostUsdCents: Long
    )

    private fun showPurchases() {
        if (!isManager()) {
            Toast.makeText(this, "إدارة المشتريات متاحة للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        markScreen("PURCHASES")
        val repository = PurchasePaymentRepository(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"; textSize = 25f; isAllCaps = false
            setTextColor(green); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPointOfSale() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("المشتريات والفواتير", 19f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        content.addView(label("إدارة المشتريات", 23f, green, true, Gravity.RIGHT))
        content.addView(label(
            "تابع الفواتير والمدفوعات والمبالغ المتبقية للموردين من مكان واحد.",
            13f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(5), 0, dp(14)) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val add = Button(this).apply {
            text = "+ فاتورة مشتريات"; isAllCaps = false; textSize = 14f
            setTextColor(Color.WHITE); setBackgroundColor(green)
            setOnClickListener { showPurchaseInvoiceEditor() }
        }
        val suppliers = Button(this).apply {
            text = "الموردون"; isAllCaps = false; textSize = 14f
            setTextColor(green)
            setOnClickListener { showSuppliers() }
        }
        actions.addView(add, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) })
        actions.addView(suppliers, LinearLayout.LayoutParams(0, dp(50), 1f))
        content.addView(actions, fieldParams(dp(50), dp(12)))
        val invoices = repository.listPurchaseInvoices()
        val outstandingTotalSyp = invoices.filter {
            it.invoiceStatus != "DRAFT" && it.invoiceStatus != "CANCELLED"
        }.sumOf { it.outstandingSypMinor }
        val paidTotalSyp = invoices.sumOf { it.paidSypMinor }
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground()
        }
        summary.addView(label("ملخص حسابات الموردين", 16f, green, true, Gravity.RIGHT))
        summary.addView(label("إجمالي المدفوع: ${formatSypAmount(paidTotalSyp)} ل.س",
            13f, textColor, false, Gravity.RIGHT).apply { setPadding(0, dp(6), 0, dp(3)) })
        summary.addView(label("المتبقي للموردين: ${formatSypAmount(outstandingTotalSyp)} ل.س",
            14f, green, true, Gravity.RIGHT))
        content.addView(summary, fieldParams(-2, dp(14)))
        content.addView(sectionHeading("سجل فواتير الشراء"))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, matchWidth())
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        if (invoices.isEmpty()) {
            list.addView(label("لا توجد فواتير مشتريات بعد.", 14f, mutedColor, false, Gravity.CENTER)
                .apply { setPadding(0, dp(30), 0, dp(30)) })
        } else invoices.forEach { invoice ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = cardBackground()
            }
            card.addView(label(invoice.invoiceNumber, 16f, green, true, Gravity.RIGHT))
            card.addView(label("التاريخ: ${invoice.date}  •  اعتماد الفاتورة: ${invoiceStatusArabic(invoice.invoiceStatus)}",
                12f, mutedColor, false, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(4)) })
            card.addView(label("المورد: ${invoice.supplierName ?: "غير محدد"}",
                12f, mutedColor, false, Gravity.RIGHT))
            card.addView(label("حالة الدفع: ${paymentStatusArabic(invoice.paymentStatus)}",
                13f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, dp(3)) })
            card.addView(label("الإجمالي المتفق عليه: ${formatSypAmount(invoice.totalSypMinor)} ل.س",
                13f, textColor, false, Gravity.RIGHT))
            card.addView(label("المدفوع: ${formatSypAmount(invoice.paidSypMinor)} ل.س",
                13f, textColor, false, Gravity.RIGHT))
            card.addView(label("المتبقي الثابت: ${formatSypAmount(invoice.outstandingSypMinor)} ل.س",
                14f, if (invoice.outstandingSypMinor > 0L) green else mutedColor,
                true, Gravity.RIGHT))
            if (invoice.invoiceRateMilli <= 0L) card.addView(label(
                "تنبيه: لا يوجد سعر صرف محفوظ في هذه الفاتورة القديمة، لذلك لا يمكن حساب رصيدها السوري تلقائياً.",
                11f, gold.toInt(), true, Gravity.RIGHT))
            if (invoice.outstandingSypMinor > 0L && invoice.invoiceRateMilli > 0L &&
                (invoice.invoiceStatus == "POSTED" || invoice.invoiceStatus == "PARTIALLY_PAID")) {
                card.addView(smallButton("＋ تسجيل دفعة للمورد") {
                    showPurchasePaymentDialog(invoice.id, invoice.invoiceNumber) { showPurchases() }
                }, fieldParams(dp(44), dp(4)))
            }
            val history = repository.listPayments(invoice.id)
            if (history.isNotEmpty()) {
                card.addView(label("سجل الدفعات:", 12f, mutedColor, true, Gravity.RIGHT)
                    .apply { setPadding(0, dp(8), 0, dp(3)) })
                history.forEach { payment ->
                    val paymentRow = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(8), dp(6), dp(8), dp(6))
                    }
                    paymentRow.addView(label(
                        "${formatAccountBalance(payment.amountMinor, payment.currencyCode)} • ${payment.accountName} • ${payment.createdAt.take(10)}",
                        11f, textColor, false, Gravity.RIGHT
                    ))
                    if (isManager()) paymentRow.addView(smallButton("تعديل هذه الدفعة") {
                        showEditPurchasePaymentDialog(invoice.id, payment) { showPurchases() }
                    }, fieldParams(dp(38), 0))
                    card.addView(paymentRow, matchWidth())
                }
            }
            list.addView(card, matchWidth().apply { bottomMargin = dp(10) })
        }
    }

    private fun paymentStatusArabic(status: String): String = when (status) {
        "UNPAID" -> "آجلة – غير مدفوعة"
        "PARTIALLY_PAID" -> "مدفوعة جزئياً"
        "PAID" -> "مدفوعة بالكامل"
        "DRAFT" -> "مسودة"
        "CANCELLED" -> "ملغاة"
        else -> status
    }

    private fun showEditPurchasePaymentDialog(
        invoiceId: String, payment: PurchasePaymentRecord, onSaved: () -> Unit
    ) {
        if (!isManager()) {
            Toast.makeText(this, "تعديل دفعات الموردين متاح للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        val supplierRolloverId = DailyBalanceRepository(this).currentSupplierRollover()?.accountId
        val accounts = CashAccountRepository(this).listActive().filter {
            it.currencyCode == payment.currencyCode &&
                (it.accountType == "WALLET" ||
                    (it.accountType == "CASH_BOX" &&
                        (payment.currencyCode == "USD" || it.id == supplierRolloverId || it.id == payment.cashAccountId)))
        }
        if (accounts.isEmpty()) {
            Toast.makeText(this, "لا يوجد حساب نشط بعملة هذه الدفعة", Toast.LENGTH_LONG).show()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val accountSpinner = Spinner(this)
        accountSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            accounts.map { "${it.name} • ${it.currencyCode}" })
        accountSpinner.setSelection(accounts.indexOfFirst { it.id == payment.cashAccountId }.coerceAtLeast(0))
        val amount = fieldForDialog("المبلغ").apply {
            setText(if (payment.currencyCode == "USD") moneyText(payment.amountMinor)
                else formatSypAmount(payment.amountMinor))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val note = fieldForDialog("الملاحظة").apply { setText(payment.note.orEmpty()) }
        content.addView(label("تصحيح دفعة المورد", 15f, green, true, Gravity.RIGHT))
        content.addView(label(
            "يمكن تعديل المبلغ أو الحساب من نفس العملة أو الملاحظة. سيُعاد احتساب رصيد الحساب والمتبقي على الفاتورة، مع الاحتفاظ بسعر الصرف الأصلي.",
            11f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(4), 0, dp(8)) })
        content.addView(label("الحساب المالي", 12f, mutedColor, false, Gravity.RIGHT))
        content.addView(accountSpinner, fieldParams(dp(48), dp(8)))
        content.addView(amount, fieldParams(dp(48), dp(8)))
        content.addView(note, fieldParams(dp(48), dp(8)))
        AlertDialog.Builder(this).setTitle("تعديل دفعة المورد").setView(content)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ التصحيح", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        try {
                            val account = accounts[accountSpinner.selectedItemPosition]
                            val entered = amount.text.toString().trim()
                            val amountMinor = if (payment.currencyCode == "USD")
                                parseUsdCents(entered) else parseSypAmount(entered)
                            require(amountMinor > 0L) { "أدخل مبلغاً أكبر من صفر" }
                            PurchasePaymentRepository(this).editPayment(
                                currentUserRole, currentUserId ?: error("جلسة المدير غير صالحة"),
                                payment.id, account.id, amountMinor,
                                note.text.toString().trim().ifEmpty { null }
                            )
                            dialog.dismiss()
                            Toast.makeText(this, "تم تصحيح الدفعة وتحديث الحساب والفاتورة", Toast.LENGTH_LONG).show()
                            onSaved()
                        } catch (e: Exception) {
                            Toast.makeText(this, e.message ?: "تعذر تعديل الدفعة", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showPurchasePaymentDialog(
        invoiceId: String, invoiceNumber: String, onSaved: () -> Unit
    ) {
        val payments = PurchasePaymentRepository(this)
        val supplierRolloverId = DailyBalanceRepository(this).currentSupplierRollover()?.accountId
        val accounts = CashAccountRepository(this).listActive().filter {
            it.accountType == "WALLET" ||
                (it.accountType == "CASH_BOX" &&
                    (it.currencyCode == "USD" || (it.currencyCode == "SYP" && it.id == supplierRolloverId)))
        }
        if (accounts.isEmpty()) {
            AlertDialog.Builder(this).setTitle("لا توجد حسابات مالية")
                .setMessage("أنشئ صندوقاً أو محفظة وأدخل رصيدها الافتتاحي قبل تسجيل دفعة.")
                .setNegativeButton("لاحقاً", null)
                .setPositiveButton("إضافة حساب") { _, _ -> showCashAccounts() }.show()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val accountSpinner = Spinner(this)
        val accountLabels = accounts.map {
            "${it.name} • ${cashAccountTypeArabic(it.accountType)} • ${it.currencyCode} • الرصيد ${formatAccountBalance(it.balanceMinor, it.currencyCode)}"
        }
        accountSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, accountLabels)
        val amount = fieldForDialog("مبلغ الدفعة")
        amount.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val note = fieldForDialog("ملاحظة (اختياري)")
        val history = payments.listPayments(invoiceId)
        val historyText = if (history.isEmpty()) "لا توجد دفعات سابقة." else history.joinToString("\n") {
            "${formatAccountBalance(it.amountMinor, it.currencyCode)} • ${it.accountName} • ${it.createdAt.take(10)}"
        }
        val invoiceBalance = payments.listPurchaseInvoices().firstOrNull { it.id == invoiceId }
        content.addView(label("الفاتورة: $invoiceNumber", 15f, green, true, Gravity.RIGHT))
        if (invoiceBalance != null && invoiceBalance.invoiceRateMilli > 0L) {
            content.addView(label(
                "المتبقي الثابت: ${formatSypAmount(invoiceBalance.outstandingSypMinor)} ل.س • بسعر صرف يوم الشراء",
                13f, green, true, Gravity.RIGHT
            ).apply { setPadding(0, dp(5), 0, dp(5)) })
        }
        content.addView(label("اختر الصندوق أو المحفظة", 12f, mutedColor, false, Gravity.RIGHT)
            .apply { setPadding(0, dp(10), 0, dp(4)) })
        content.addView(accountSpinner, fieldParams(dp(48), dp(8)))
        content.addView(amount, fieldParams(dp(48), dp(8)))
        content.addView(note, fieldParams(dp(48), dp(8)))
        content.addView(label("الدفعات السابقة:\n$historyText", 11f, mutedColor, false, Gravity.RIGHT)
            .apply { setPadding(0, dp(8), 0, dp(4)) })
        val dialog = AlertDialog.Builder(this).setTitle("تسجيل دفعة للمورد")
            .setView(content).setNegativeButton("إلغاء", null)
            .setPositiveButton("تأكيد الدفع", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val account = accounts[accountSpinner.selectedItemPosition]
                    val entered = amount.text.toString().trim()
                    val amountMinor = if (account.currencyCode == "USD")
                        parseUsdCents(entered) else parseSypAmount(entered)
                    require(amountMinor > 0L) { "أدخل مبلغاً أكبر من صفر" }
                    val result = payments.addPayment(
                        currentUserId ?: error("جلسة المستخدم غير صالحة"), invoiceId, UUID.randomUUID().toString(), account.id, amountMinor,
                        note.text.toString().trim().ifEmpty { null }
                    )
                    dialog.dismiss()
                    Toast.makeText(this,
                        if (result.paymentStatus == "PAID") "تم تسديد الفاتورة بالكامل"
                        else "تم تسجيل الدفعة الجزئية بنجاح",
                        Toast.LENGTH_LONG).show()
                    onSaved()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر تسجيل الدفعة", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showSuppliers() {
        if (!isManager()) {
            Toast.makeText(this, "حسابات الموردين متاحة للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        markScreen("SUPPLIERS")
        val repository = PurchasePaymentRepository(this)
        val suppliers = repository.listSupplierSummaries()

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"; textSize = 25f; isAllCaps = false
            setTextColor(green); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPurchases() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("حسابات الموردين", 19f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val totalDueSyp = suppliers.sumOf { it.outstandingSypMinor }
        val totalPaidSyp = suppliers.sumOf { it.paidSypMinor }
        list.addView(label("إجمالي ما دُفع: ${formatSypAmount(totalPaidSyp)} ل.س",
            14f, textColor, false, Gravity.RIGHT))
        list.addView(label("إجمالي المستحق الثابت: ${formatSypAmount(totalDueSyp)} ل.س",
            16f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(14)) })
        val unpricedCount = suppliers.sumOf { it.unpricedInvoiceCount }
        if (unpricedCount > 0) list.addView(label(
            "تنبيه: توجد $unpricedCount فاتورة قديمة بلا سعر صرف محفوظ. لم نُدخلها في إجمالي الليرة ولم نُعد تقييمها تلقائياً.",
            12f, gold.toInt(), true, Gravity.RIGHT
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        if (suppliers.isEmpty()) {
            list.addView(label("لا يوجد موردون مسجلون بعد.", 14f, mutedColor, false, Gravity.CENTER))
        }
        suppliers.forEach { supplier ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = cardBackground()
                isClickable = true
                setOnClickListener { showSupplierDetails(supplier.id) }
            }
            card.addView(label(supplier.name, 16f, green, true, Gravity.RIGHT))
            card.addView(label("الفواتير المعتمدة: ${formatSypAmount(supplier.totalPurchaseSypMinor)} ل.س",
                12f, textColor, false, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, dp(2)) })
            card.addView(label("المدفوع: ${formatSypAmount(supplier.paidSypMinor)} ل.س",
                12f, textColor, false, Gravity.RIGHT))
            card.addView(label("المتبقي الثابت: ${formatSypAmount(supplier.outstandingSypMinor)} ل.س",
                14f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(3), 0, dp(2)) })
            card.addView(label("فواتير غير مسددة: ${supplier.unpaidInvoiceCount}",
                11f, mutedColor, false, Gravity.RIGHT))
            list.addView(card, fieldParams(-2, dp(10)))
        }
        body.addView(list)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showSupplierDetails(supplierId: String) {
        if (!isManager()) {
            Toast.makeText(this, "حسابات الموردين متاحة للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        markScreen("SUPPLIER_DETAILS", supplierId)
        val repository = PurchasePaymentRepository(this)
        val supplier = repository.listSupplierSummaries().firstOrNull { it.id == supplierId }
            ?: run { showSuppliers(); return }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"; textSize = 25f; isAllCaps = false
            setTextColor(green); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showSuppliers() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label(supplier.name, 18f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        content.addView(label("إجمالي الفواتير: ${formatSypAmount(supplier.totalPurchaseSypMinor)} ل.س",
            13f, textColor, false, Gravity.RIGHT))
        content.addView(label("إجمالي المدفوع: ${formatSypAmount(supplier.paidSypMinor)} ل.س",
            13f, textColor, false, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(4)) })
        content.addView(label("المبلغ المستحق الثابت: ${formatSypAmount(supplier.outstandingSypMinor)} ل.س",
            16f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(12)) })
        var filter = "ALL"
        val filterRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun refreshInvoices() {
            list.removeAllViews()
            val filtered = repository.listPurchaseInvoices(supplierId).filter {
                when (filter) {
                    "UNPAID" -> it.paymentStatus == "UNPAID"
                    "PARTIAL" -> it.paymentStatus == "PARTIALLY_PAID"
                    "PAID" -> it.paymentStatus == "PAID"
                    else -> true
                }
            }
            if (filtered.isEmpty()) list.addView(label("لا توجد فواتير ضمن هذا التصنيف.", 13f,
                mutedColor, false, Gravity.CENTER).apply { setPadding(0, dp(20), 0, dp(20)) })
            filtered.forEach { invoice ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = cardBackground()
                }
                card.addView(label(invoice.invoiceNumber, 15f, green, true, Gravity.RIGHT))
                card.addView(label("التاريخ: ${invoice.date} • ${paymentStatusArabic(invoice.paymentStatus)}",
                    12f, mutedColor, false, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(4)) })
                card.addView(label("الإجمالي المتفق عليه: ${formatSypAmount(invoice.totalSypMinor)} ل.س",
                    12f, textColor, false, Gravity.RIGHT))
                card.addView(label("المدفوع: ${formatSypAmount(invoice.paidSypMinor)} ل.س",
                    12f, textColor, false, Gravity.RIGHT))
                card.addView(label("المتبقي الثابت: ${formatSypAmount(invoice.outstandingSypMinor)} ل.س",
                    13f, green, true, Gravity.RIGHT))
                if (invoice.invoiceRateMilli <= 0L) card.addView(label(
                    "فاتورة قديمة بلا سعر صرف محفوظ؛ يلزم مراجعتها يدوياً قبل الدفع.",
                    11f, gold.toInt(), true, Gravity.RIGHT))
                if (invoice.outstandingSypMinor > 0L && invoice.invoiceRateMilli > 0L &&
                    (invoice.invoiceStatus == "POSTED" || invoice.invoiceStatus == "PARTIALLY_PAID")) {
                    card.addView(smallButton("تسجيل دفعة") {
                        showPurchasePaymentDialog(invoice.id, invoice.invoiceNumber) {
                            showSupplierDetails(supplierId)
                        }
                    }, fieldParams(dp(42), dp(2)))
                }
                list.addView(card, fieldParams(-2, dp(8)))
            }
        }
        listOf("ALL" to "الكل", "UNPAID" to "آجلة", "PARTIAL" to "جزئية", "PAID" to "مدفوعة").forEach { (key, title) ->
            val button = smallButton(title) {
                filter = key
                refreshInvoices()
            }
            filterRow.addView(button, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        content.addView(filterRow, fieldParams(dp(42), dp(8)))
        content.addView(list, matchWidth())
        refreshInvoices()
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showCustomers() {
        markScreen("CUSTOMERS")
        val repository = CustomerRepository(this)
        val paymentRepository = CustomerPaymentRepository(this)
        val customers = repository.listActive()
        val balances = paymentRepository.listCustomerSummaries().associateBy { it.id }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"; textSize = 25f; isAllCaps = false
            setTextColor(green); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPointOfSale() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("العملاء والديون", 19f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        content.addView(label("ملفات العملاء", 22f, green, true, Gravity.RIGHT))
        content.addView(label(
            "يعرض هذا القسم فواتير البيع الآجل والتحصيلات المسجلة عليها فقط. البيع النقدي والأرصدة الافتتاحية غير المرتبطة بفواتير لا تدخل في هذا الرصيد.",
            12f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(5), 0, dp(12)) })
        val add = Button(this).apply {
            text = "+ إضافة زبون"; isAllCaps = false
            setTextColor(Color.WHITE); setBackgroundColor(green)
            setOnClickListener { showCustomerEditor(null) { showCustomers() } }
        }
        content.addView(add, fieldParams(dp(50), dp(12)))
        if (customers.isEmpty()) {
            content.addView(label("لا يوجد عملاء مسجلون بعد.", 14f, mutedColor, false, Gravity.CENTER)
                .apply { setPadding(0, dp(24), 0, dp(24)) })
        }
        customers.forEach { customer ->
            val financial = balances[customer.id]
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = cardBackground()
                setOnClickListener { showCustomerAccount(customer.id) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("إيقاف ملف العميل")
                        .setMessage("سيتم إخفاء " + customer.name + " من القوائم الجديدة مع الاحتفاظ بسجلاته.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("إيقاف") { _, _ ->
                            try {
                                repository.setActive(currentUserId ?: error("جلسة المستخدم غير صالحة"), customer.id, false)
                                showCustomers()
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_LONG).show()
                            }
                        }.show()
                    true
                }
            }
            card.addView(label(customer.name, 16f, green, true, Gravity.RIGHT))
            card.addView(label(
                customer.phone?.takeIf { it.isNotBlank() } ?: "لا يوجد رقم هاتف",
                12f, mutedColor, false, Gravity.RIGHT
            ).apply { setPadding(0, dp(4), 0, dp(2)) })
            val outstanding = financial?.outstandingUsdCents ?: 0L
            val dueLine = if (outstanding > 0L)
                "المتبقي على الفواتير الآجلة: " + moneyText(outstanding) + " USD"
            else "لا توجد ديون آجلة متبقية"
            card.addView(label(dueLine, 13f, if (outstanding > 0L) gold.toInt() else mutedColor,
                true, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, dp(8)) })
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            actions.addView(smallButton("الحساب والتحصيل") { showCustomerAccount(customer.id) },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
            actions.addView(smallButton("تعديل البيانات") {
                showCustomerEditor(customer.id) { showCustomers() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            card.addView(actions, matchWidth())
            content.addView(card, fieldParams(-2, dp(9)))
        }
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showCustomerAccount(customerId: String) {
        markScreen("CUSTOMER_ACCOUNT", customerId)
        val customer = CustomerRepository(this).get(customerId)
        if (customer == null) {
            Toast.makeText(this, "ملف العميل غير موجود", Toast.LENGTH_SHORT).show()
            showCustomers()
            return
        }
        val repository = CustomerPaymentRepository(this)
        val invoices = repository.listInvoices(customerId)
        val summary = repository.listCustomerSummaries().firstOrNull { it.id == customerId }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"; textSize = 25f; isAllCaps = false
            setTextColor(green); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showCustomers() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("كشف حساب الزبون", 19f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        content.addView(label(customer.name, 22f, green, true, Gravity.RIGHT))
        content.addView(label(customer.phone ?: "لا يوجد رقم هاتف", 12f, mutedColor, false, Gravity.RIGHT)
            .apply { setPadding(0, dp(4), 0, dp(10)) })
        val outstanding = summary?.outstandingUsdCents ?: 0L
        val paid = summary?.paidUsdCents ?: 0L
        val total = summary?.totalCreditUsdCents ?: 0L
        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        summaryCard.addView(label("إجمالي فواتير البيع الآجل: " + moneyText(total) + " USD",
            14f, textColor, false, Gravity.RIGHT))
        summaryCard.addView(label("إجمالي التحصيلات: " + moneyText(paid) + " USD",
            14f, textColor, false, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, dp(5)) })
        summaryCard.addView(label("الرصيد المتبقي المرجعي: " + moneyText(outstanding) + " USD",
            14f, textColor, false, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, dp(3)) })
        summaryCard.addView(label("المطلوب حالياً: " + formatSypAmount(summary?.outstandingSypMinor ?: 0L) + " ل.س",
            17f, green, true, Gravity.RIGHT))
        content.addView(summaryCard, fieldParams(-2, dp(16)))
        content.addView(sectionHeading("فواتير البيع الآجل"))
        if (invoices.isEmpty()) {
            content.addView(label("لا توجد فواتير بيع آجل لهذا الزبون حتى الآن.",
                14f, mutedColor, false, Gravity.CENTER).apply {
                setPadding(0, dp(22), 0, dp(22))
            })
        }
        invoices.forEach { invoice ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBackground()
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val statusText = when (invoice.paymentStatus) {
                "PAID" -> "مسددة بالكامل"
                "PARTIALLY_PAID" -> "مسددة جزئياً"
                "UNPAID" -> "غير مسددة"
                "DRAFT" -> "مسودة غير معتمدة"
                "CANCELLED" -> "ملغاة"
                else -> invoice.paymentStatus
            }
            card.addView(label("فاتورة " + invoice.invoiceNumber, 15f, green, true, Gravity.RIGHT))
            card.addView(label("التاريخ: " + invoice.date + " • " + statusText,
                11f, mutedColor, false, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(6)) })
            card.addView(label("الإجمالي: " + moneyText(invoice.totalUsdCents) + " USD",
                13f, textColor, false, Gravity.RIGHT))
            card.addView(label("المدفوع: " + moneyText(invoice.paidUsdCents) + " USD",
                13f, textColor, false, Gravity.RIGHT).apply { setPadding(0, dp(3), 0, dp(3)) })
            card.addView(label("المتبقي المرجعي: " + moneyText(invoice.outstandingUsdCents) + " USD",
                13f, textColor, false, Gravity.RIGHT))
            card.addView(label("المطلوب حالياً: " + formatSypAmount(invoice.outstandingSypMinor) + " ل.س",
                15f, green, true, Gravity.RIGHT))
            if (invoice.originalRateMilli <= 0L && invoice.outstandingUsdCents > 0L) {
                card.addView(label("تنبيه: فاتورة قديمة بلا سعر صرف موثوق؛ لا يمكن تقييم دينها أو تحصيله آلياً قبل مراجعتها.",
                    11f, gold.toInt(), true, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, dp(5)) })
            }
            if (invoice.originalRateMilli > 0L && invoice.outstandingUsdCents > 0L &&
                (invoice.invoiceStatus == "POSTED" || invoice.invoiceStatus == "PARTIALLY_PAID")) {
                val collect = Button(this).apply {
                    text = "تسجيل تحصيل على هذه الفاتورة"
                    isAllCaps = false
                    setTextColor(Color.WHITE)
                    setBackgroundColor(green)
                    setOnClickListener { showCustomerPaymentDialog(customerId, invoice) }
                }
                card.addView(collect, fieldParams(dp(48), dp(8)))
            }
            val revaluations = repository.listRevaluations(invoice.id)
            if (revaluations.isNotEmpty()) {
                card.addView(label("تعديلات تقييم الدين حسب سعر الصرف", 11f, gold.toInt(), true, Gravity.RIGHT)
                    .apply { setPadding(0, dp(6), 0, dp(3)) })
                revaluations.forEach { adjustment ->
                    val rateText = BigDecimal.valueOf(adjustment.appliedRateMilli, 3)
                        .stripTrailingZeros().toPlainString()
                    card.addView(label(
                        formatSypAmount(adjustment.previousSypMinor) + " ← " +
                            formatSypAmount(adjustment.newSypMinor) + " ل.س • " +
                            rateText + " ل.س/دولار • " + adjustment.createdAt,
                        10f, mutedColor, false, Gravity.RIGHT
                    ).apply { setPadding(0, dp(2), 0, dp(2)) })
                }
            }
            val history = repository.listPayments(invoice.id)
            if (history.isNotEmpty()) {
                card.addView(label("سجل الدفعات", 12f, gold.toInt(), true, Gravity.RIGHT)
                    .apply { setPadding(0, dp(8), 0, dp(3)) })
                history.forEach { payment ->
                    val amount = if (payment.currencyCode == "SYP")
                        formatSypAmount(payment.amountMinor) + " ل.س" else "$" + moneyText(payment.amountMinor)
                    val channel = if (payment.accountType == "WALLET") "محفظة إلكترونية" else "درج نقدي"
                    card.addView(label(
                        amount + " • " + channel + ": " + payment.accountName + " • " + payment.createdAt,
                        11f, mutedColor, false, Gravity.RIGHT
                    ).apply { setPadding(0, dp(2), 0, dp(2)) })
                    val originalRateText = BigDecimal.valueOf(payment.originalInvoiceRateMilli, 3)
                        .stripTrailingZeros().toPlainString()
                    val collectionRateText = BigDecimal.valueOf(payment.collectionRateMilli, 3)
                        .stripTrailingZeros().toPlainString()
                    val effectiveRateText = BigDecimal.valueOf(payment.effectiveRateMilli, 3)
                        .stripTrailingZeros().toPlainString()
                    card.addView(label(
                        "خفض مرجع الدين: " + moneyText(payment.usdReferenceReductionCents) +
                            " USD • سعر الفاتورة: " + originalRateText +
                            " • سعر التحصيل: " + collectionRateText +
                            " • السعر الفعال: " + effectiveRateText,
                        10f, mutedColor, false, Gravity.RIGHT
                    ).apply { setPadding(0, dp(1), 0, dp(3)) })
                }
            }
            content.addView(card, fieldParams(-2, dp(10)))
        }
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showCustomerPaymentDialog(
        customerId: String, invoice: com.hazem.dailystore.data.CustomerInvoicePaymentSummary
    ) {
        val accountRepository = CashAccountRepository(this)
        val drawer = accountRepository.currentDayPrimaryDrawer()
        val wallets = accountRepository.listActive().filter {
            it.accountType == "WALLET" && it.currencyCode == "SYP"
        }
        if (drawer == null && wallets.isEmpty()) {
            AlertDialog.Builder(this).setTitle("لا يوجد حساب صالح للتحصيل")
                .setMessage("يجب على المدير تعيين درج نقدي رئيسي لليوم أو إنشاء محفظة إلكترونية نشطة بالليرة السورية. لا يمكن استخدام صندوق الموردين المدور.")
                .setPositiveButton("فتح الصناديق والمحافظ") { _, _ -> showCashAccounts() }.show()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val channel = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val cashOption = RadioButton(this).apply {
            id = View.generateViewId()
            text = if (drawer != null) "نقداً إلى درج اليوم الرئيسي: " + drawer.name
                else "نقداً (يتطلب تعيين درج اليوم الرئيسي)"
            isEnabled = drawer != null
            textSize = 13f
        }
        val walletOption = RadioButton(this).apply {
            id = View.generateViewId()
            text = "تحويل إلكتروني إلى محفظة"
            isEnabled = wallets.isNotEmpty()
            textSize = 13f
        }
        channel.addView(cashOption)
        channel.addView(walletOption)
        val walletSpinner = Spinner(this)
        walletSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            wallets.map { it.name + " • الرصيد " + formatAccountBalance(it.balanceMinor, it.currencyCode) }
        )
        val amount = fieldForDialog("مبلغ التحصيل بالليرة السورية", "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val note = fieldForDialog("ملاحظة اختيارية")
        content.addView(label("الفاتورة: " + invoice.invoiceNumber, 15f, green, true, Gravity.RIGHT))
        content.addView(label("المتبقي المرجعي: " + moneyText(invoice.outstandingUsdCents) + " USD",
            12f, mutedColor, false, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(2)) })
        content.addView(label("المطلوب حالياً: " + formatSypAmount(invoice.outstandingSypMinor) + " ل.س",
            14f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(2), 0, dp(8)) })
        content.addView(label("طريقة التحصيل", 12f, mutedColor, true, Gravity.RIGHT))
        content.addView(channel, fieldParams(-2, dp(6)))
        val walletLabel = label("المحفظة المستلمة", 12f, mutedColor, false, Gravity.RIGHT)
        content.addView(walletLabel)
        content.addView(walletSpinner, fieldParams(dp(48), dp(8)))
        content.addView(amount, fieldParams(dp(48), dp(8)))
        content.addView(note, fieldParams(dp(48), dp(8)))
        channel.check(if (drawer != null) cashOption.id else walletOption.id)
        fun updateChannel() {
            val useWallet = channel.checkedRadioButtonId == walletOption.id
            walletLabel.visibility = if (useWallet) View.VISIBLE else View.GONE
            walletSpinner.visibility = if (useWallet) View.VISIBLE else View.GONE
        }
        channel.setOnCheckedChangeListener { _, _ -> updateChannel() }
        updateChannel()
        val dialog = AlertDialog.Builder(this).setTitle("تحصيل دين زبون")
            .setView(content).setNegativeButton("إلغاء", null)
            .setPositiveButton("تسجيل التحصيل", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val useWallet = channel.checkedRadioButtonId == walletOption.id
                    val accountId = if (useWallet) wallets.getOrNull(walletSpinner.selectedItemPosition)?.id
                        ?: error("اختر المحفظة الإلكترونية المستلمة")
                    else drawer?.id ?: error("لم يتم تعيين درج اليوم الرئيسي؛ لا يمكن تسجيل التحصيل النقدي")
                    val amountMinor = parseSypAmount(amount.text.toString())
                    require(amountMinor > 0L) { "أدخل مبلغ تحصيل أكبر من صفر" }
                    val result = CustomerPaymentRepository(this).addPayment(
                        currentUserId ?: error("جلسة المستخدم غير صالحة"),
                        invoice.id, UUID.randomUUID().toString(), accountId, amountMinor,
                        note.text.toString().trim().ifEmpty { null }
                    )
                    dialog.dismiss()
                    Toast.makeText(
                        this,
                        if (result.outstandingUsdCents == 0L) "تم التحصيل وأصبحت الفاتورة مسددة بالكامل"
                        else "تم تسجيل التحصيل المالي الجزئي بنجاح",
                        Toast.LENGTH_LONG
                    ).show()
                    showCustomerAccount(customerId)
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر تسجيل التحصيل", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showCustomerEditor(customerId: String?, onSaved: () -> Unit) {
        if (customerId != null && !isManager()) {
            Toast.makeText(this, "تعديل بيانات العملاء متاح للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        val repository = CustomerRepository(this)
        val existing = customerId?.let { repository.get(it) }
        if (customerId != null && existing == null) {
            Toast.makeText(this, "ملف العميل غير موجود", Toast.LENGTH_SHORT).show()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val name = fieldForDialog("اسم الزبون *", existing?.name.orEmpty())
        val phone = fieldForDialog("رقم الهاتف", existing?.phone.orEmpty(),
            InputType.TYPE_CLASS_PHONE)
        val address = fieldForDialog("العنوان", existing?.address.orEmpty())
        val notes = fieldForDialog("ملاحظات", existing?.notes.orEmpty())
        content.addView(name, fieldParams(dp(48), dp(8)))
        content.addView(phone, fieldParams(dp(48), dp(8)))
        content.addView(address, fieldParams(dp(48), dp(8)))
        content.addView(notes, fieldParams(dp(48), dp(8)))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "إضافة زبون" else "تعديل بيانات الزبون")
            .setView(content).setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val cleanName = name.text.toString().trim()
                    require(cleanName.isNotEmpty()) { "اسم الزبون مطلوب" }
                    if (existing == null) repository.create(
                        currentUserId ?: error("جلسة المستخدم غير صالحة"),
                        cleanName, phone.text.toString(), address.text.toString(), notes.text.toString()
                    ) else repository.update(
                        currentUserId ?: error("جلسة المستخدم غير صالحة"),
                        existing.id, cleanName, phone.text.toString(),
                        address.text.toString(), notes.text.toString()
                    )
                    dialog.dismiss()
                    Toast.makeText(this, "تم حفظ بيانات الزبون", Toast.LENGTH_SHORT).show()
                    onSaved()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر حفظ بيانات الزبون", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun isManager(): Boolean = currentUserRole == "ADMIN" && !currentUserId.isNullOrBlank()

    private fun hasPermission(permissionKey: String): Boolean {
        val actor = currentUserId ?: return false
        return try {
            PermissionPolicy.require(AppDatabase.getInstance(this).readableDatabase, actor, permissionKey)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showUserManagement() {
        if (!isManager()) {
            Toast.makeText(this, "إدارة المستخدمين والصلاحيات متاحة للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        val actor = currentUserId ?: return
        markScreen("USER_MANAGEMENT")
        val auth = AuthRepository(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"; textSize = 25f; isAllCaps = false
            setTextColor(green); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPointOfSale() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("المستخدمون والصلاحيات", 19f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        content.addView(label("إدارة حسابات المتجر", 21f, green, true, Gravity.RIGHT))
        content.addView(label(
            "كل موظف يستخدم حساباً مستقلاً. كلمة المرور المؤقتة يجب تغييرها عند أول دخول، وإيقاف الحساب يمنع تسجيل الدخول والعمليات الجديدة.",
            12f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(5), 0, dp(12)) })
        content.addView(smallButton("+ إضافة مستخدم جديد") { showCreateUserDialog { showUserManagement() } },
            fieldParams(dp(48), dp(10)))
        auth.listUsers().forEach { user ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(13), dp(11), dp(13), dp(11))
                background = cardBackground()
            }
            card.addView(label(user.displayName, 16f, green, true, Gravity.RIGHT))
            card.addView(label(
                "@${user.username} • ${if (user.roleKey == "ADMIN") "مدير" else "موظف"}",
                12f, mutedColor, false, Gravity.RIGHT
            ).apply { setPadding(0, dp(3), 0, dp(3)) })
            val state = when {
                !user.isActive -> "الحساب موقوف"
                user.mustChangePassword -> "بانتظار تغيير كلمة المرور"
                else -> "الحساب نشط"
            }
            card.addView(label(state, 12f, if (user.isActive) green else mutedColor, true, Gravity.RIGHT))
            if (user.id != actor) {
                card.addView(smallButton(if (user.isActive) "إيقاف الحساب" else "إعادة تفعيل الحساب") {
                    val action = if (user.isActive) "إيقاف" else "إعادة تفعيل"
                    AlertDialog.Builder(this)
                        .setTitle("$action حساب المستخدم")
                        .setMessage("هل تريد $action حساب ${user.displayName}؟")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("تأكيد") { _, _ ->
                            try {
                                auth.setUserActive(actor, user.id, !user.isActive)
                                Toast.makeText(this, "تم تحديث حالة الحساب", Toast.LENGTH_SHORT).show()
                                showUserManagement()
                            } catch (e: Exception) {
                                Toast.makeText(this, e.message ?: "تعذر تحديث الحساب", Toast.LENGTH_LONG).show()
                            }
                        }.show()
                }, fieldParams(dp(42), dp(7)))
            }
            content.addView(card, fieldParams(-2, dp(8)))
        }
        content.addView(sectionHeading("صلاحيات دور الموظف"))
        content.addView(label(
            "تُطبق التغييرات على جميع الحسابات ذات دور «موظف». اترك الصلاحيات الإدارية مغلقة، وامنح فقط ما يحتاجه الموظف فعلياً.",
            12f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(4), 0, dp(8)) })
        PermissionPolicy.listRolePermissions(AppDatabase.getInstance(this).readableDatabase, "CASHIER")
            .forEach { permission ->
                val check = CheckBox(this).apply {
                    text = permission.description
                    textSize = 14f
                    setTextColor(textColor)
                    isChecked = permission.allowed
                    setOnCheckedChangeListener { _, checked ->
                        try {
                            PermissionPolicy.setRolePermission(
                                AppDatabase.getInstance(this@MainActivity).writableDatabase,
                                actor, "CASHIER", permission.key, checked
                            )
                            Toast.makeText(this@MainActivity, "تم تحديث صلاحية الموظف", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, e.message ?: "تعذر تغيير الصلاحية", Toast.LENGTH_LONG).show()
                            showUserManagement()
                        }
                    }
                }
                content.addView(check, fieldParams(-2, dp(2)))
            }
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showCreateUserDialog(onSaved: () -> Unit) {
        if (!isManager()) return
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val username = EditText(this).apply {
            hint = "اسم المستخدم (3 أحرف على الأقل)"
            setSingleLine(true); textSize = 15f; background = inputBackground()
        }
        val displayName = EditText(this).apply {
            hint = "الاسم الظاهر للموظف"
            setSingleLine(true); textSize = 15f; background = inputBackground()
        }
        val password = EditText(this).apply {
            hint = "كلمة مرور مؤقتة قوية"
            setSingleLine(true); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = inputBackground()
        }
        val role = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("موظف", "مدير"))
        }
        content.addView(username, fieldParams(dp(50), dp(7)))
        content.addView(displayName, fieldParams(dp(50), dp(7)))
        content.addView(password, fieldParams(dp(50), dp(7)))
        content.addView(label("نوع الحساب", 12f, mutedColor, false, Gravity.RIGHT))
        content.addView(role, fieldParams(dp(48), dp(4)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("إضافة مستخدم")
            .setMessage("سيُطلب من المستخدم تغيير كلمة المرور المؤقتة عند أول تسجيل دخول.")
            .setView(content)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إنشاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val actor = currentUserId ?: error("جلسة المدير غير صالحة")
                    AuthRepository(this).createUser(
                        currentUserRole, actor, username.text.toString(), displayName.text.toString(),
                        password.text.toString(), if (role.selectedItemPosition == 1) "ADMIN" else "CASHIER"
                    )
                    dialog.dismiss()
                    Toast.makeText(this, "تم إنشاء المستخدم. يجب عليه تغيير كلمة المرور عند أول دخول.", Toast.LENGTH_LONG).show()
                    onSaved()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر إنشاء المستخدم", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showCashAccounts() {
        markScreen("CASH_ACCOUNTS")
        val repository = CashAccountRepository(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"; textSize = 25f; isAllCaps = false
            setTextColor(green); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPointOfSale() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("الصناديق والمحافظ", 19f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val serviceWallets = ServiceTransferRepository(this).listWallets()
        content.addView(label("الحسابات المالية", 22f, green, true, Gravity.RIGHT))
        content.addView(label("الحسابات الأساسية تُنشأ وتُربط تلقائياً: درج البيع اليومي، صندوق الموردين المدور، صندوق شراء رصيد الخدمات، ومحفظتا سيرياتيل وشام كاش. يمكنك إضافة حسابات أخرى عند الحاجة.",
            13f, mutedColor, false, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, dp(12)) })
        if (isManager()) {
            val add = Button(this).apply {
                text = "+ إضافة صندوق أو محفظة"; isAllCaps = false
                setTextColor(Color.WHITE); setBackgroundColor(green)
                setOnClickListener { showCreateCashAccountDialog() }
            }
            content.addView(add, fieldParams(dp(50), dp(12)))
        }
        content.addView(smallButton("إدارة الأرصدة والتحويلات اليومية") { showDailyOperationsDialog() }, fieldParams(dp(46), dp(6)))
        content.addView(smallButton("كشف المقبوضات والحركات اليومية") { showDailyCashReport() }, fieldParams(dp(46), dp(10)))
        repository.listAll().forEach { account ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = cardBackground()
            }
            card.addView(label(account.name, 16f, green, true, Gravity.RIGHT))
            card.addView(label("${cashAccountTypeArabic(account.accountType)} • ${account.currencyCode}",
                12f, mutedColor, false, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(4)) })
            card.addView(label("الرصيد الحالي: ${formatAccountBalance(account.balanceMinor, account.currencyCode)}",
                15f, textColor, true, Gravity.RIGHT))
            card.addView(label(if (account.isActive) "نشط" else "موقوف", 11f,
                if (account.isActive) green else mutedColor, false, Gravity.RIGHT))
            val primaryDrawer = repository.currentDayPrimaryDrawer()
            val rechargeAccountId = ServiceTransferRepository(this).rechargeAccountId()
            val supplierRoleAccountId = repository.systemRoleAccountId("SUPPLIER_ROLLOVER")
            if (primaryDrawer?.id == account.id) card.addView(label("الدرج النقدي الرئيسي لليوم", 12f, green, true, Gravity.RIGHT)
                .apply { setPadding(0, dp(4), 0, dp(2)) })
            if (rechargeAccountId == account.id) card.addView(label("صندوق شراء رصيد الخدمات الموحد", 12f, green, true, Gravity.RIGHT)
                .apply { setPadding(0, dp(4), 0, dp(2)) })
            serviceWallets.firstOrNull { it.accountId == account.id }?.let { wallet ->
                val roleName = if(wallet.serviceKey==ServiceTransferRepository.SYRIATEL_SHARED) "محفظة سيرياتيل • تشغيل وقبض إلكتروني" else "محفظة شام كاش • تشغيل وقبض إلكتروني"
                card.addView(label(roleName, 12f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(2)) })
            }
            if (supplierRoleAccountId == account.id) card.addView(label("الصندوق الأساسي للنقد المدور للبضاعة", 12f, green, true, Gravity.RIGHT)
                .apply { setPadding(0, dp(4), 0, dp(2)) })
            if (isManager() && account.isActive && account.accountType == "CASH_BOX" && account.currencyCode == "SYP" &&
                account.id != primaryDrawer?.id && account.id != supplierRoleAccountId) {
                card.addView(smallButton("تعيين كصندوق شراء رصيد الخدمات") {
                    try {
                        ServiceTransferRepository(this).setRechargeAccount(currentUserId ?: error("جلسة المدير غير صالحة"), account.id)
                        Toast.makeText(this, "تم تعيين صندوق شراء رصيد الخدمات", Toast.LENGTH_LONG).show()
                        showCashAccounts()
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message ?: "تعذر تعيين صندوق شراء الرصيد", Toast.LENGTH_LONG).show()
                    }
                }, fieldParams(dp(42), dp(4)))
            }
            if (isManager() && account.isActive && account.accountType == "CASH_BOX" && account.currencyCode == "SYP" &&
                account.id != rechargeAccountId && account.id != supplierRoleAccountId) {
                card.addView(smallButton("تعيين كدرج اليوم الرئيسي") {
                    try {
                        repository.setCurrentDayPrimaryDrawer(
                            currentUserRole, currentUserId ?: error("جلسة المدير غير صالحة"), account.id
                        )
                        Toast.makeText(this, "تم تعيين " + account.name +
                            " كدرج اليوم الرئيسي. صندوق الموردين المدور لم يتغير.", Toast.LENGTH_LONG).show()
                        showCashAccounts()
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message ?: "تعذر تعيين الدرج اليومي", Toast.LENGTH_LONG).show()
                    }
                }, fieldParams(dp(42), dp(4)))
            }
            if (isManager()) card.addView(smallButton("تعديل الحساب") {
                showEditCashAccountDialog(account) { showCashAccounts() }
            }, fieldParams(dp(42), dp(4)))
            content.addView(card, fieldParams(-2, dp(8)))
        }
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }


    private fun showDailyOperationsDialog() {
        if (!isManager()) {
            Toast.makeText(this, "إدارة الأرصدة اليومية متاحة للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        val repo = DailyBalanceRepository(this)
        val cashAccounts = CashAccountRepository(this)
        val all = cashAccounts.listActive()
        val supplierDefaultId = cashAccounts.systemRoleAccountId("SUPPLIER_ROLLOVER")
        val actions = listOf("تحديد النقد المدور للموردين", "تسجيل مسحوبات شخصية", "مطابقة الرصيد الافتتاحي اليومي لمحفظة", "تحويل محفظة إلى صندوق نقدي تختاره", "تحويل نقدية بين صندوقين")
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(4)) }
        val action = Spinner(this)
        action.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, actions)
        val account = Spinner(this)
        val accountHint = label("", 11f, mutedColor, false, Gravity.RIGHT)
        val destination = Spinner(this)
        val destinationTitle = label("وجهة التحويل النقدية", 12f, mutedColor, false, Gravity.RIGHT)
        val destinationHint = label("", 11f, mutedColor, false, Gravity.RIGHT)
        var dailyOperationsDialog: AlertDialog? = null
        var availableAccounts: List<CashAccountSummary> = emptyList()
        var availableDestinations: List<CashAccountSummary> = emptyList()
        fun refreshAccounts() {
            val selected = action.selectedItemPosition.coerceAtLeast(0)
            val previousId = availableAccounts.getOrNull(account.selectedItemPosition)?.id
            val options = when (selected) {
                0 -> all.filter {
                    it.accountType == "CASH_BOX" && it.currencyCode == "SYP" &&
                        it.id != cashAccounts.currentDayPrimaryDrawer()?.id &&
                        it.id != ServiceTransferRepository(this).rechargeAccountId()
                }.sortedBy { if(it.id==supplierDefaultId) 0 else 1 }
                1 -> all.filter { it.accountType == "CASH_BOX" && it.currencyCode == "SYP" && it.id == repo.currentSupplierRollover()?.accountId }
                2 -> all.filter { it.accountType == "WALLET" }
                3 -> all.filter { it.accountType == "WALLET" && it.currencyCode == "SYP" }
                else -> all.filter {
                    it.accountType == "CASH_BOX" && it.currencyCode == "SYP" &&
                        it.id != ServiceTransferRepository(this).rechargeAccountId() &&
                        it.id != supplierDefaultId && it.id != repo.currentSupplierRollover()?.accountId
                }
            }
            availableAccounts = options
            account.tag = options
            val labels = if (options.isEmpty()) listOf("لا يوجد حساب مالي مناسب لهذه العملية")
                else options.map { it.name + " • " + it.currencyCode + " • " + formatAccountBalance(it.balanceMinor, it.currencyCode) }
            account.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            account.isEnabled = options.isNotEmpty()
            accountHint.text = when {
                options.isNotEmpty() -> ""
                selected == 0 -> "أنشئ صندوقاً نقدياً بالليرة السورية مخصصاً لمدفوعات الموردين، غير درج اليوم الرئيسي."
                selected == 1 -> "حدد النقد المدور للموردين لهذا اليوم أولاً من خيار إدارة الأرصدة اليومية."
                selected == 2 -> "أنشئ محفظة إلكترونية نشطة أولاً."
                selected == 3 -> "اختر محفظة مصدر نشطة بالليرة السورية أولاً."
                else -> "اختر صندوقاً نقدياً مصدراً؛ لا يمكن السحب من صندوق الموردين أو صندوق شراء الرصيد."
            }
            accountHint.visibility = if (options.isEmpty()) View.VISIBLE else View.GONE
            if (previousId != null) {
                val restored = options.indexOfFirst { it.id == previousId }
                if (restored >= 0) account.setSelection(restored)
            }
            val previousDestinationId = availableDestinations.getOrNull(destination.selectedItemPosition)?.id
            val rechargeId = ServiceTransferRepository(this).rechargeAccountId()
            val supplierId = repo.currentSupplierRollover()?.accountId
            val sourceId = availableAccounts.getOrNull(account.selectedItemPosition)?.id
            val destinationOptions = when(selected) {
                3 -> all.filter {
                    it.accountType == "CASH_BOX" && it.currencyCode == "SYP" &&
                        it.id != rechargeId && it.id != supplierId && it.id != supplierDefaultId
                }
                4 -> all.filter {
                    it.accountType == "CASH_BOX" && it.currencyCode == "SYP" && it.id != sourceId
                }
                else -> emptyList()
            }
            availableDestinations = destinationOptions
            destination.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                if (destinationOptions.isEmpty()) listOf("لا توجد وجهة نقدية متاحة")
                else destinationOptions.map { it.name + " • " + formatAccountBalance(it.balanceMinor, it.currencyCode) })
            destination.isEnabled = destinationOptions.isNotEmpty()
            destinationTitle.text = if(selected==4) "الصندوق النقدي الوجهة" else "وجهة التحويل النقدية"
            destinationTitle.visibility = if (selected == 3 || selected == 4) View.VISIBLE else View.GONE
            destination.visibility = if (selected == 3 || selected == 4) View.VISIBLE else View.GONE
            destinationHint.visibility = if ((selected == 3 || selected == 4) && destinationOptions.isEmpty()) View.VISIBLE else View.GONE
            destinationHint.text = if(selected==4) "اختر صندوقاً نقدياً وجهة؛ يمكن تمويل صندوق الموردين أو صندوق شراء رصيد الخدمات من أي صندوق مصدر متاح." else "اختر صندوقاً نقدياً نشطاً بالليرة السورية، مع استبعاد صندوق الموردين وصندوق شراء رصيد الخدمات."
            if (previousDestinationId != null) {
                val restored = destinationOptions.indexOfFirst { it.id == previousDestinationId }
                if (restored >= 0) destination.setSelection(restored)
            }
        }
        action.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) { refreshAccounts() }
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { refreshAccounts() }
        }
        account.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if(action.selectedItemPosition!=4) return
                val sourceId=availableAccounts.getOrNull(position)?.id
                val previousDestinationId=availableDestinations.getOrNull(destination.selectedItemPosition)?.id
                val options=all.filter { it.accountType=="CASH_BOX" && it.currencyCode=="SYP" && it.id!=sourceId }
                availableDestinations=options
                destination.adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,
                    if(options.isEmpty()) listOf("لا توجد وجهة نقدية متاحة")
                    else options.map { it.name+" • "+formatSypAmount(it.balanceMinor)+" ل.س" })
                if(previousDestinationId!=null) {
                    val restored=options.indexOfFirst { it.id==previousDestinationId }
                    if(restored>=0) destination.setSelection(restored)
                }
            }
        }
        val amount = fieldForDialog("المبلغ")
        amount.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val reference = fieldForDialog("مرجع / ملاحظة")
        content.addView(label("نوع العملية", 12f, mutedColor, false, Gravity.RIGHT))
        content.addView(action, fieldParams(dp(48), dp(8)))
        content.addView(label("الحساب المالي", 12f, mutedColor, false, Gravity.RIGHT))
        content.addView(account, fieldParams(dp(48), dp(8)))
        content.addView(accountHint, fieldParams(-2, dp(2)))
        content.addView(destinationTitle, fieldParams(-2, dp(2)))
        content.addView(destination, fieldParams(dp(48), dp(8)))
        content.addView(destinationHint, fieldParams(-2, dp(2)))
        content.addView(smallButton("إضافة صندوق أو محفظة") {
            dailyOperationsDialog?.dismiss()
            showCreateCashAccountDialog()
        }, fieldParams(dp(42), dp(4)))
        content.addView(amount, fieldParams(dp(48), dp(8)))
        content.addView(reference, fieldParams(dp(48), dp(8)))
        refreshAccounts()
        val operationId = UUID.randomUUID().toString()
        val dialog = AlertDialog.Builder(this).setTitle("إدارة الأرصدة اليومية").setView(content)
            .setNegativeButton("إلغاء", null).setPositiveButton("تنفيذ", null).create()
        dailyOperationsDialog = dialog
        dialog.setOnShowListener {
            action.post { refreshAccounts() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val selected = availableAccounts.getOrNull(account.selectedItemPosition) ?: error(
                        accountHint.text.toString().ifBlank { "لا يوجد حساب مالي مناسب للعملية المختارة؛ أنشئ الحساب أولاً." }
                    )
                    val entered = amount.text.toString().trim()
                    val note = reference.text.toString().trim().ifEmpty { null }
                    val operation = action.selectedItemPosition
                    val value = if (selected.currencyCode == "USD") parseUsdCents(entered) else parseSypAmount(entered)
                    val actor = currentUserId ?: error("جلسة المدير غير صالحة")
                    when (operation) {
                        0 -> {
                            val result = repo.setSupplierRollover(actor, selected.id, value, note)
                            Toast.makeText(this, "تم تحديد النقد المدور. المتاح: " + formatSypAmount(result.availableMinor) + " ل.س", Toast.LENGTH_LONG).show()
                        }
                        1 -> repo.addPersonalWithdrawal(actor, selected.id, value, operationId, note)
                        2 -> repo.setWalletOpening(actor, selected.id, value, note)
                        3 -> {
                            val target = availableDestinations.getOrNull(destination.selectedItemPosition)
                                ?: error("اختر صندوقاً نقدياً كوجهة للتحويل")
                            require(target.id != selected.id) { "لا يمكن التحويل إلى الحساب نفسه" }
                            repo.transferWalletToDrawer(actor, selected.id, target.id, value, operationId, note, note)
                        }
                        4 -> {
                            val target = availableDestinations.getOrNull(destination.selectedItemPosition)
                                ?: error("اختر صندوقاً نقدياً كوجهة للتحويل")
                            require(target.id != selected.id) { "لا يمكن التحويل إلى الحساب نفسه" }
                            repo.transferCashBetweenBoxes(actor, selected.id, target.id, value, operationId, note, note)
                        }
                    }
                    dialog.dismiss()
                    Toast.makeText(this, "تم تسجيل العملية في السجل المالي", Toast.LENGTH_LONG).show()
                    showCashAccounts()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر تنفيذ العملية", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showDailyCashReport() {
        markScreen("DAILY_REPORT")
        val report = DailyBalanceRepository(this).dailyReport()
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(backgroundColor) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(Color.WHITE) }
        val back = Button(this).apply { text = "‹"; textSize = 25f; isAllCaps = false; setTextColor(green); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { showCashAccounts() } }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("الكشف المالي اليومي", 19f, green, true, Gravity.CENTER), LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(24)) }
        list.addView(label("التاريخ: " + report.operatingDate, 13f, mutedColor, false, Gravity.RIGHT))
        val capital = InventoryValuationRepository(this).dailyCapital()
        val capitalRate = StoreRepository(this).getCurrentExchangeRateMilli()
        list.addView(sectionHeading("رأس المال الكلي للمخزون"))
        list.addView(label(
            "القيمة الدفترية الحالية وفق FIFO: " + formatDualMoney(capital.current.totalCostUsdCents, capitalRate),
            17f, green, true, Gravity.RIGHT
        ).apply { setPadding(0, dp(5), 0, dp(3)) })
        val changePrefix = if (capital.changeUsdCents >= 0L) "+" else "−"
        val changeAbs = if (capital.changeUsdCents == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(capital.changeUsdCents)
        val capitalChangeLabel = if (capital.current.isComplete && capital.previousClose.isComplete)
            "التغير عن إغلاق اليوم السابق: " else "التغير في الجزء المقيم فقط (مقارنة بإغلاق أمس): "
        list.addView(label(
            capitalChangeLabel + changePrefix + formatDualMoney(changeAbs, capitalRate),
            14f, if (capital.changeUsdCents >= 0L) green else textColor, true, Gravity.RIGHT
        ).apply { setPadding(0, dp(2), 0, dp(4)) })
        if (!capital.current.isComplete || !capital.previousClose.isComplete) {
            list.addView(label(
                "تنبيه محاسبي: توجد كميات غير مقيمة أو فروقات في سجل المخزون. الأصناف المتأثرة حالياً: ${capital.current.unvaluedProductCount}، وعند إغلاق أمس: ${capital.previousClose.unvaluedProductCount}. قيمة رأس المال المعروضة هي الجزء المؤكد فقط.",
                12f, Color.rgb(170, 80, 45), true, Gravity.RIGHT
            ).apply { setPadding(0, dp(2), 0, dp(9)) })
        } else {
            list.addView(label(
                "تمت مطابقة رصيد المخزون مع سجل الحركات، والتقييم مبني على طبقات التكلفة FIFO وليس على سعر البيع أو سعر الصنف الحالي.",
                11f, mutedColor, false, Gravity.RIGHT
            ).apply { setPadding(0, dp(2), 0, dp(9)) })
        }
        val serviceDaily = ServiceTransferRepository(this).dailySummary(report.operatingDate)
        if (serviceDaily.isNotEmpty()) {
            list.addView(sectionHeading("تحويلات الرصيد – تفصيل اليوم"))
            serviceDaily.forEach { d ->
                list.addView(label(d.serviceName+": المقبوض "+formatSypAmount(d.customerReceiptsMinor)+" ل.س • الربح "+formatSypAmount(d.profitMinor)+" ل.س • رسوم الشركة "+formatSypAmount(d.providerFeeMinor)+" ل.س",12f,textColor,false,Gravity.RIGHT).apply{setPadding(0,dp(3),0,dp(3))})
                list.addView(label("الرصيد المستهلك "+formatSypAmount(d.deliveredMinor)+" ل.س • قيمة الرصيد المطلوب تعويضه "+formatSypAmount(d.rechargeReserveMinor)+" ل.س",12f,green,true,Gravity.RIGHT).apply{setPadding(0,dp(2),0,dp(5))})
            }
            list.addView(label("قيمة الرصيد المستهلك هي المبلغ الأساسي اللازم لتعويض الرصيد. عمولة 2% تُحسب فقط عند تعبئة رصيد سيرياتيل أو شام كاش من تاجر خارجي، وتظهر حينها ضمن رسوم التعبئة المدفوعة.",11f,mutedColor,false,Gravity.RIGHT).apply{setPadding(0,dp(2),0,dp(8))})
        }
        val electronicText = if (report.electronicReceiptsByCurrency.isEmpty()) "لا توجد مقبوضات إلكترونية" else
            report.electronicReceiptsByCurrency.entries.joinToString(" • ") { formatAccountBalance(it.value, it.key) }
        val liquidatableText = if (report.liquidatableElectronicReceiptsByCurrency.isEmpty()) "لا يوجد مبلغ إلكتروني متبقٍ" else
            report.liquidatableElectronicReceiptsByCurrency.entries.joinToString(" • ") { formatAccountBalance(it.value, it.key) }
        val physicalText = if (report.physicalCashReceiptsByCurrency.isEmpty()) "لا توجد مقبوضات نقدية" else
            report.physicalCashReceiptsByCurrency.entries.joinToString(" • ") { formatAccountBalance(it.value, it.key) }
        list.addView(label("إجمالي المقبوضات الإلكترونية خلال اليوم: " + electronicText, 16f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(8), 0, dp(3)) })
        list.addView(label("المتبقي من مقبوضات اليوم بعد التحويل إلى الكاش: " + liquidatableText, 16f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(2), 0, dp(4)) })
        list.addView(label("إجمالي المقبوضات النقدية الفعلية: " + physicalText, 15f, textColor, true, Gravity.RIGHT).apply { setPadding(0, dp(2), 0, dp(12)) })
        report.supplierRollover?.let { r ->
            list.addView(label("النقد المدور للموردين: " + formatSypAmount(r.allocatedMinor) +
                " ل.س • دفعات الموردين: " + formatSypAmount(r.supplierPaymentsMinor) +
                " • المسحوبات: " + formatSypAmount(r.personalWithdrawalsMinor) +
                " • المتاح: " + formatSypAmount(r.availableMinor) + " ل.س",
                14f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(12)) })
        }
        report.accounts.forEach { a ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = cardBackground() }
            card.addView(label(a.accountName + " • " + a.currencyCode, 15f, green, true, Gravity.RIGHT))
            card.addView(label("رصيد أول اليوم: " + formatAccountBalance(a.openingMinor, a.currencyCode), 12f, textColor, false, Gravity.RIGHT))
            val channelLabel = if (a.accountType == "WALLET") "إلكتروني" else "نقدي"
            card.addView(label("مبيعات اليوم – قبض " + channelLabel + ": " + formatAccountBalance(a.salesReceiptsMinor, a.currencyCode), 12f, textColor, false, Gravity.RIGHT))
            card.addView(label("تحصيل ديون – قبض " + channelLabel + ": " + formatAccountBalance(a.debtCollectionsMinor, a.currencyCode), 12f, textColor, false, Gravity.RIGHT))
            card.addView(label("مقبوض تحويل رصيد – قبض " + channelLabel + ": " + formatAccountBalance(a.serviceTransferReceiptsMinor, a.currencyCode), 12f, textColor, false, Gravity.RIGHT))
            val receiptTotalLabel = if (a.accountType == "WALLET") "إجمالي مقبوضات اليوم: "
                else "إجمالي المقبوضات النقدية في الحساب: "
            card.addView(label(receiptTotalLabel + formatAccountBalance(a.electronicReceiptsMinor, a.currencyCode), 13f, green, true, Gravity.RIGHT))
            if (a.accountType == "WALLET") card.addView(label(
                "المتبقي من مقبوضات اليوم بعد التحويل: " + formatAccountBalance(a.liquidatableElectronicReceiptsMinor, a.currencyCode),
                13f, green, true, Gravity.RIGHT
            ))
            card.addView(label("إضافات أخرى: " + formatAccountBalance(a.otherInflowsMinor, a.currencyCode), 12f, textColor, false, Gravity.RIGHT))
            card.addView(label("دفعات الموردين: " + formatAccountBalance(a.supplierPaymentsMinor, a.currencyCode), 12f, textColor, false, Gravity.RIGHT))
            card.addView(label("المسحوبات الشخصية: " + formatAccountBalance(a.personalWithdrawalsMinor, a.currencyCode), 12f, textColor, false, Gravity.RIGHT))
            card.addView(label("تحويلات واردة: " + formatAccountBalance(a.transferInMinor, a.currencyCode) + " • صادرة: " + formatAccountBalance(a.transferOutMinor, a.currencyCode), 12f, textColor, false, Gravity.RIGHT))
            card.addView(label("رصيد آخر اليوم: " + formatAccountBalance(a.closingMinor, a.currencyCode), 15f, green, true, Gravity.RIGHT))
            list.addView(card, fieldParams(-2, dp(9)))
        }
        list.addView(sectionHeading("حركات اليوم"))
        val transactions = DailyBalanceRepository(this).todayTransactions()
        if (transactions.isEmpty()) list.addView(label("لا توجد حركات مسجلة اليوم.", 12f, mutedColor, false, Gravity.CENTER))
        else transactions.forEach { list.addView(label(it, 11f, textColor, false, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(4)) }) }
        body.addView(list); page.addView(header, matchWidth()); page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(page)
    }


    private fun showGeneralReports() {
        if (!isManager()) {
            Toast.makeText(this, "التقارير المالية متاحة للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        markScreen("GENERAL_REPORTS")
        val capital = InventoryValuationRepository(this).dailyCapital()
        val rate = StoreRepository(this).getCurrentExchangeRateMilli()
        val products = StoreRepository(this).listProducts().filter { it.tracksInventory }
        val stockedProducts = products.count { it.stock > 0.0000001 }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 25f
            isAllCaps = false
            setTextColor(green)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPointOfSale() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("الكشوفات العامة", 19f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
        }
        content.addView(label("رأس المال الكلي للمخزون", 22f, green, true, Gravity.RIGHT))
        content.addView(label(
            "التقييم المحاسبي للمخزون المتبقي وفق طريقة FIFO، على أساس تكلفة الشراء بالدولار المرجعي الثابت، وليس سعر البيع.",
            12f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(4), 0, dp(12)) })
        val capitalCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = cardBackground()
        }
        capitalCard.addView(label("قيمة المخزون المقيم حالياً", 13f, mutedColor, false, Gravity.RIGHT))
        capitalCard.addView(label(
            formatDualMoney(capital.current.totalCostUsdCents, rate),
            21f, green, true, Gravity.RIGHT
        ).apply { setPadding(0, dp(5), 0, dp(8)) })
        val sign = if (capital.changeUsdCents >= 0L) "+" else "−"
        val absoluteChange = if (capital.changeUsdCents == Long.MIN_VALUE) Long.MAX_VALUE
            else kotlin.math.abs(capital.changeUsdCents)
        val capitalChangeLabel = if (capital.current.isComplete && capital.previousClose.isComplete)
            "التغير عن إغلاق اليوم السابق: " else "التغير في الجزء المقيم فقط (مقارنة بإغلاق أمس): "
        capitalCard.addView(label(
            capitalChangeLabel + sign + formatDualMoney(absoluteChange, rate),
            14f, if (capital.changeUsdCents >= 0L) green else textColor, true, Gravity.RIGHT
        ))
        capitalCard.addView(label("آخر احتساب: ${capital.current.asOfLabel}", 10f, mutedColor, false, Gravity.RIGHT)
            .apply { setPadding(0, dp(5), 0, 0) })
        content.addView(capitalCard, fieldParams(-2, dp(10)))
        if (!capital.current.isComplete || !capital.previousClose.isComplete) {
            content.addView(label(
                "تنبيه: لم نحتسب قيمة الأصناف التي تحتوي على كميات بلا تكلفة مسجلة أو فروقات في سجل المخزون. الأصناف المتأثرة حالياً: ${capital.current.unvaluedProductCount}، وعند إغلاق أمس: ${capital.previousClose.unvaluedProductCount}. الرقم أعلاه هو رأس المال المؤكد فقط.",
                12f, Color.rgb(170, 80, 45), true, Gravity.RIGHT
            ).apply { setPadding(0, dp(2), 0, dp(12)) })
        } else {
            content.addView(label(
                "حالة التقييم: مكتمل؛ الكميات مطابقة لسجل الحركات، وتم احتساب الطبقات المتبقية وفق FIFO.",
                12f, green, true, Gravity.RIGHT
            ).apply { setPadding(0, dp(2), 0, dp(12)) })
        }
        content.addView(sectionHeading("ملخص ربح المبيعات وفق تكلفة FIFO"))
        val profitRepo = SalesProfitRepository(this)
        val todayProfit = profitRepo.today()
        val profitCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground()
        }
        profitCard.addView(label("نتيجة المبيعات المسجلة اليوم", 15f, green, true, Gravity.RIGHT))
        profitCard.addView(label("عدد الفواتير: ${todayProfit.invoiceCount}", 12f, mutedColor, false, Gravity.RIGHT))
        profitCard.addView(label("إجمالي المبيعات: ${formatDualMoney(todayProfit.revenueUsdCents, rate)}", 13f, textColor, false, Gravity.RIGHT))
        profitCard.addView(label("مرتجعات المبيعات: ${formatDualMoney(todayProfit.returnUsdCents, rate)}", 13f, textColor, false, Gravity.RIGHT))
        profitCard.addView(label("صافي الإيراد بعد المرتجعات: ${formatDualMoney(todayProfit.netRevenueUsdCents, rate)}", 13f, textColor, true, Gravity.RIGHT))
        profitCard.addView(label("تكلفة FIFO المباعة: ${formatDualMoney(todayProfit.cogsUsdCents, rate)} • تكلفة المرتجع: ${formatDualMoney(todayProfit.returnedCogsUsdCents, rate)}", 12f, mutedColor, false, Gravity.RIGHT))
        if (todayProfit.isComplete) {
            profitCard.addView(label("مجمل الربح بعد المرتجعات وقبل المصاريف: ${formatDualMoney(todayProfit.grossProfitUsdCents, rate)}",
                15f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, 0) })
        } else {
            profitCard.addView(label("الربح غير مكتمل: فواتير بلا تكلفة FIFO: ${todayProfit.uncostedInvoiceCount} • مرتجعات بلا تكلفة معروفة: ${todayProfit.uncostedReturnCount}. راجعها قبل الاعتماد.",
                12f, Color.rgb(170, 80, 45), true, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, 0) })
        }
        val allProfit = profitRepo.allTime()
        profitCard.addView(label("منذ بداية التشغيل • المبيعات: ${formatDualMoney(allProfit.revenueUsdCents, rate)} • المرتجعات: ${formatDualMoney(allProfit.returnUsdCents, rate)}",
            11f, mutedColor, false, Gravity.RIGHT).apply { setPadding(0, dp(8), 0, 0) })
        if (allProfit.isComplete) {
            profitCard.addView(label("مجمل الربح التراكمي بعد المرتجعات وقبل المصاريف: ${formatDualMoney(allProfit.grossProfitUsdCents, rate)}",
                13f, green, true, Gravity.RIGHT))
        } else {
            profitCard.addView(label("الربح التراكمي غير مكتمل؛ فواتير بلا تكلفة: ${allProfit.uncostedInvoiceCount} • مرتجعات بلا تكلفة: ${allProfit.uncostedReturnCount}",
                11f, Color.rgb(170, 80, 45), true, Gravity.RIGHT))
        }
        content.addView(profitCard, fieldParams(-2, dp(12)))
        content.addView(label("ملاحظة محاسبية: هذا مجمل ربح البضاعة بعد احتساب المرتجعات وتكلفتها وفق FIFO، وليس صافي ربح المتجر؛ لا يشمل المصاريف التشغيلية.",
            11f, mutedColor, false, Gravity.RIGHT).apply { setPadding(0, 0, 0, dp(12)) })
        content.addView(sectionHeading("ملخص المخزون"))
        content.addView(label("عدد الأصناف التي تتبع المخزون: ${products.size}", 15f, textColor, true, Gravity.RIGHT)
            .apply { setPadding(0, dp(5), 0, dp(3)) })
        content.addView(label("عدد الأصناف ذات رصيد موجب: $stockedProducts", 14f, textColor, false, Gravity.RIGHT))
        content.addView(label(
            "ملاحظة: لا نجمع كميات القطعة والكيلوغرام والطرد في رقم واحد لاختلاف وحدات القياس. رأس المال محسوب بالقيمة المالية الموحدة بالدولار المرجعي.",
            11f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(10), 0, dp(4)) })
        content.addView(smallButton("فتح الكشف المالي اليومي") { showDailyCashReport() }, fieldParams(dp(46), dp(12)))
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showServiceTransfers() {
        markScreen("SERVICE_TRANSFERS")
        val repo = ServiceTransferRepository(this)
        val page = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(backgroundColor) }
        val header = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(14),dp(10),dp(14),dp(10)); setBackgroundColor(Color.WHITE) }
        val back = Button(this).apply { text="‹"; textSize=25f; isAllCaps=false; setTextColor(green); setBackgroundColor(Color.TRANSPARENT); setOnClickListener { showPointOfSale() } }
        header.addView(back,LinearLayout.LayoutParams(dp(48),dp(48)))
        header.addView(label("تحويل رصيد",19f,green,true,Gravity.CENTER),LinearLayout.LayoutParams(0,-2,1f))
        val body=ScrollView(this).apply{isFillViewport=true}
        val content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(16),dp(24))}
        content.addView(label("محافظ التشغيل والقبض الإلكتروني للخدمات",21f,green,true,Gravity.RIGHT))
        content.addView(label("محفظتا سيرياتيل وشام كاش هما رصيد التشغيل نفسه وحساب القبض الإلكتروني للخدمة؛ كل حركة بيع أو قبض أو تحويل داخلي تظهر على رصيد المحفظة ذاتها.",13f,mutedColor,false,Gravity.RIGHT).apply{setPadding(0,dp(5),0,dp(12))})
        repo.listWallets().forEach { w ->
            val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=cardBackground()}
            card.addView(label(w.name,16f,green,true,Gravity.RIGHT))
            card.addView(label("الرصيد المتاح: "+formatSypAmount(w.balanceMinor)+" ل.س",17f,textColor,true,Gravity.RIGHT).apply{setPadding(0,dp(5),0,dp(2))})
            content.addView(card,fieldParams(-2,dp(8)))
        }
        if(isManager()) {
            content.addView(smallButton("مطابقة الرصيد الفعلي لمحفظة التشغيل"){showServiceOpeningDialog()},fieldParams(dp(46),dp(7)))
            content.addView(smallButton("شراء / شحن رصيد الخدمة"){showServiceRechargeDialog()},fieldParams(dp(46),dp(12)))
        }
        content.addView(Button(this).apply{text="＋ تنفيذ تحويل للزبون";isAllCaps=false;setTextColor(Color.WHITE);setBackgroundColor(green);setOnClickListener{showServiceTransferDialog()}},fieldParams(dp(52),dp(12)))
        content.addView(sectionHeading("ملخص عمليات اليوم ومبلغ إعادة التعبئة"))
        val daily=repo.dailySummary()
        if(daily.isEmpty()) content.addView(label("لا توجد عمليات تحويل رصيد اليوم.",12f,mutedColor,false,Gravity.CENTER))
        daily.forEach { d ->
            val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=cardBackground()}
            card.addView(label(d.serviceName+" • "+d.operationCount+" عملية",14f,green,true,Gravity.RIGHT))
            card.addView(label("إجمالي ما أُرسل من رصيد الخدمة: "+formatSypAmount(d.deliveredMinor)+" ل.س",12f,textColor,false,Gravity.RIGHT))
            card.addView(label("إجمالي ما قبضه المتجر من الزبائن: "+formatSypAmount(d.customerReceiptsMinor)+" ل.س",12f,textColor,false,Gravity.RIGHT))
            card.addView(label("ربح المتجر: "+formatSypAmount(d.profitMinor)+" ل.س • رسوم الشركة: "+formatSypAmount(d.providerFeeMinor)+" ل.س",12f,green,true,Gravity.RIGHT))
            card.addView(label("رسوم التعبئة المدفوعة فعلياً اليوم: "+formatSypAmount(d.rechargeFeePaidMinor)+" ل.س • قيمة الرصيد اللازم تعويضه: "+formatSypAmount(d.rechargeReserveMinor)+" ل.س",12f,mutedColor,true,Gravity.RIGHT))
            content.addView(card,fieldParams(-2,dp(7)))
        }
        content.addView(label("المقبوض النقدي يذهب إلى الدرج الرئيسي، والمقبوض الإلكتروني يضاف إلى محفظة الخدمة نفسها افتراضياً أو إلى المحفظة التي تختارها. عند تصريف المحفظة إلى النقدية تنقص المحفظة ويزيد الصندوق بالمبلغ نفسه. عمولة 2% تُسجل فقط عند تعبئة رصيد سيرياتيل أو شام كاش من تاجر خارجي، ولا تُضاف إلى تحويلات الزبائن.",11f,mutedColor,false,Gravity.RIGHT).apply{setPadding(0,dp(3),0,dp(10))})
        content.addView(sectionHeading("سجل تحويلات الرصيد"))
        val rows=repo.listTransfers(80)
        if(rows.isEmpty()) content.addView(label("لا توجد تحويلات مسجلة حتى الآن.",13f,mutedColor,false,Gravity.CENTER).apply{setPadding(0,dp(18),0,dp(18))})
        rows.forEach { r ->
            val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=cardBackground()}
            card.addView(label(r.serviceName+" • "+if(r.status=="REVERSED")"معكوسة" else "منفذة",15f,green,true,Gravity.RIGHT))
            card.addView(label("الواصل للزبون: "+formatSypAmount(r.deliveredMinor)+" ل.س",13f,textColor,false,Gravity.RIGHT))
            card.addView(label("ربح المتجر: "+formatSypAmount(r.profitMinor)+" • رسوم الشركة: "+formatSypAmount(r.providerFeeMinor)+" ل.س",12f,mutedColor,false,Gravity.RIGHT))
            card.addView(label("المقبوض: "+formatSypAmount(r.customerChargeMinor)+" ل.س • "+r.receiptAccountName+(if(r.receiptChannel=="ELECTRONIC")" (إلكتروني)" else " (نقدي)"),13f,green,true,Gravity.RIGHT))
            card.addView(label(r.createdAt.replace('T',' ').replace("Z",""),10f,mutedColor,false,Gravity.RIGHT))
            content.addView(card,fieldParams(-2,dp(8)))
        }
        body.addView(content);page.addView(header,matchWidth());page.addView(body,LinearLayout.LayoutParams(-1,0,1f));setContentView(page)
    }

    private fun showServiceOpeningDialog() {
        if(!isManager())return
        val repo=ServiceTransferRepository(this)
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(4))}
        val service=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("رصيد سيرياتيل المشترك","رصيد شام كاش"))}
        val amount=fieldForDialog("الرصيد الفعلي الحالي للمحفظة بالليرة السورية").apply{inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL}
        val note=fieldForDialog("ملاحظة / مرجع")
        form.addView(label("الخدمة",12f,mutedColor,false,Gravity.RIGHT));form.addView(service,fieldParams(dp(48),dp(8)));form.addView(amount,fieldParams(dp(48),dp(8)));form.addView(note,fieldParams(dp(48),dp(8)))
        val dialog=AlertDialog.Builder(this).setTitle("مطابقة رصيد محفظة التشغيل").setView(form).setNegativeButton("إلغاء",null).setPositiveButton("حفظ",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try{
                val key=if(service.selectedItemPosition==0)ServiceTransferRepository.SYRIATEL_SHARED else ServiceTransferRepository.SHAM_CASH
                repo.setOpeningBalance(currentUserId?:error("جلسة المدير غير صالحة"),key,parseSypAmount(amount.text.toString()),note.text.toString().trim().ifEmpty{null})
                dialog.dismiss();Toast.makeText(this,"تمت مطابقة رصيد المحفظة مع الرصيد الفعلي",Toast.LENGTH_LONG).show();showServiceTransfers()
            }catch(e:Exception){Toast.makeText(this,e.message?:"تعذر حفظ الرصيد",Toast.LENGTH_LONG).show()}
        }}
        dialog.show()
    }

    private fun showServiceRechargeDialog() {
        if(!isManager())return
        val repo=ServiceTransferRepository(this)
        val designated=repo.rechargeAccountId()
        val sources=CashAccountRepository(this).listActive().filter{it.id==designated&&it.accountType=="CASH_BOX"&&it.currencyCode=="SYP"}
        if(sources.isEmpty()){AlertDialog.Builder(this).setMessage("أنشئ صندوقاً نقدياً مستقلاً بالليرة السورية مخصصاً لشراء رصيد الخدمات أولاً.").setPositiveButton("فتح الصناديق والمحافظ"){_,_->showCashAccounts()}.show();return}
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(4))}
        val service=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("رصيد سيرياتيل المشترك","رصيد شام كاش"))}
        val source=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,sources.map{it.name+" • صندوق شراء الرصيد • المتاح "+formatSypAmount(it.balanceMinor)+" ل.س"})}
        val amount=fieldForDialog("قيمة الرصيد المراد شراؤه").apply{inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL}
        val fee=label("عمولة تعبئة 2% عند شراء رصيد سيرياتيل أو شام كاش من تاجر خارجي فقط؛ لا عمولة على تحويلات الزبائن.",12f,green,false,Gravity.RIGHT)
        service.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){fee.text=if(position==0)"عمولة شحن سيرياتيل: 2% من قيمة الرصيد المشتَرى" else "عمولة شحن شام كاش: 2% من قيمة الرصيد المشتَرى"}
        }
        val ref=fieldForDialog("مرجع عملية الشراء / ملاحظة")
        form.addView(label("محفظة الخدمة",12f,mutedColor,false,Gravity.RIGHT));form.addView(service,fieldParams(dp(48),dp(8)))
        form.addView(label("التمويل من صندوق شراء رصيد الخدمات",12f,mutedColor,false,Gravity.RIGHT));form.addView(source,fieldParams(dp(48),dp(8)))
        form.addView(amount,fieldParams(dp(48),dp(6)));form.addView(fee);form.addView(ref,fieldParams(dp(48),dp(8)))
        val op=UUID.randomUUID().toString()
        val dialog=AlertDialog.Builder(this).setTitle("شراء / شحن رصيد الخدمة").setView(form).setNegativeButton("إلغاء",null).setPositiveButton("شراء الرصيد",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try{
                val key=if(service.selectedItemPosition==0)ServiceTransferRepository.SYRIATEL_SHARED else ServiceTransferRepository.SHAM_CASH
                val selected=sources.getOrNull(source.selectedItemPosition)?:error("اختر صندوق شراء الرصيد")
                val note=ref.text.toString().trim().ifEmpty{null}
                val feeAmount=repo.recharge(currentUserId?:error("جلسة المدير غير صالحة"),key,selected.id,parseSypAmount(amount.text.toString()),op,note,note)
                dialog.dismiss();Toast.makeText(this,"تم شراء الرصيد. عمولة الشحن: "+formatSypAmount(feeAmount)+" ل.س",Toast.LENGTH_LONG).show();showServiceTransfers()
            }catch(e:Exception){Toast.makeText(this,e.message?:"تعذر شراء الرصيد",Toast.LENGTH_LONG).show()}
        }}
        dialog.show()
    }

    private fun showServiceTransferDialog() {
        val repo=ServiceTransferRepository(this)
        val accountRepo=CashAccountRepository(this)
        val drawer=accountRepo.currentDayPrimaryDrawer()
        val receiptOptions=accountRepo.listActive().filter{it.currencyCode=="SYP"&&(it.accountType=="WALLET"||(it.accountType=="CASH_BOX"&&it.id==drawer?.id))}
        if(receiptOptions.isEmpty()){AlertDialog.Builder(this).setMessage("عيّن درج اليوم الرئيسي أو أنشئ محفظة قبض إلكترونية بالليرة السورية قبل تنفيذ التحويل.").setPositiveButton("فتح الصناديق والمحافظ"){_,_->showCashAccounts()}.show();return}
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(4))}
        val service=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("سيرياتيل العادي • ربح 20%","سيرياتيل كاش • ربح 10%","شام كاش • ربح 5%"))}
        val amount=fieldForDialog("المبلغ الواصل للزبون").apply{inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL}
        val companyFee=fieldForDialog("رسوم الشركة (أدخلها يدوياً إن وجدت)").apply{inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;setText("0")}
        val receipt=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,receiptOptions.map{it.name+if(it.accountType=="WALLET")" • قبض إلكتروني" else " • قبض نقدي"})}
        fun selectDefaultServiceWallet() {
            val key=if(service.selectedItemPosition==2) ServiceTransferRepository.SHAM_CASH else ServiceTransferRepository.SYRIATEL_SHARED
            val linkedId=repo.serviceAccountId(key)
            val index=receiptOptions.indexOfFirst{it.id==linkedId}
            if(index>=0) receipt.setSelection(index)
        }
        selectDefaultServiceWallet()
        val ref=fieldForDialog("رقم الزبون / مرجع التحويل / ملاحظة")
        val preview=label("أدخل مبلغ التحويل لاحتساب ربح المتجر وإجمالي المقبوض.",13f,green,true,Gravity.RIGHT)
        fun updatePreview(){
            val rate=when(service.selectedItemPosition){0->20L;1->10L;else->5L}
            companyFee.isEnabled=service.selectedItemPosition!=2
            if(!companyFee.isEnabled&&companyFee.text.toString()!="0")companyFee.setText("0")
            val delivered=runCatching{parseSypAmount(amount.text.toString())}.getOrNull()
            val fee=runCatching{parseSypAmount(companyFee.text.toString())}.getOrNull()
            preview.text=if(delivered==null||delivered<=0L||fee==null)"أدخل مبلغاً صحيحاً." else{
                val profit=BigDecimal.valueOf(delivered).multiply(BigDecimal.valueOf(rate)).divide(BigDecimal.valueOf(100),0,RoundingMode.HALF_UP).longValueExact()
                "ربح المتجر ($rate%): "+formatSypAmount(profit)+" ل.س\nرسوم الشركة: "+formatSypAmount(fee)+" ل.س\nالمبلغ المطلوب من الزبون: "+formatSypAmount(Math.addExact(Math.addExact(delivered,profit),fee))+" ل.س"
            }
        }
        service.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){selectDefaultServiceWallet();updatePreview()}
        }
        val watcher=object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){updatePreview()}
            override fun afterTextChanged(s:Editable?){}
        }
        amount.addTextChangedListener(watcher);companyFee.addTextChangedListener(watcher)
        form.addView(label("نوع الخدمة",12f,mutedColor,false,Gravity.RIGHT));form.addView(service,fieldParams(dp(48),dp(8)))
        form.addView(amount,fieldParams(dp(48),dp(8)));form.addView(companyFee,fieldParams(dp(48),dp(8)))
        form.addView(preview,fieldParams(-2,dp(10)));form.addView(label("استلام المبلغ من الزبون في",12f,mutedColor,false,Gravity.RIGHT))
        form.addView(receipt,fieldParams(dp(48),dp(8)));form.addView(ref,fieldParams(dp(48),dp(8)));updatePreview()
        val op=UUID.randomUUID().toString()
        val dialog=AlertDialog.Builder(this).setTitle("تنفيذ تحويل رصيد").setView(ScrollView(this).apply{addView(form)}).setNegativeButton("إلغاء",null).setPositiveButton("تنفيذ التحويل",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try{
                val type=when(service.selectedItemPosition){0->ServiceTransferRepository.SYRIATEL_BALANCE;1->ServiceTransferRepository.SYRIATEL_CASH;else->ServiceTransferRepository.SHAM_CASH_TRANSFER}
                val selected=receiptOptions.getOrNull(receipt.selectedItemPosition)?:error("اختر حساب استلام المبلغ")
                val note=ref.text.toString().trim().ifEmpty{null}
                val result=repo.executeTransfer(currentUserId?:error("جلسة المستخدم غير صالحة"),type,parseSypAmount(amount.text.toString()),parseSypAmount(companyFee.text.toString()),selected.id,op,note,note)
                dialog.dismiss()
                AlertDialog.Builder(this).setTitle("تم تسجيل التحويل")
                    .setMessage("الواصل للزبون: "+formatSypAmount(result.deliveredMinor)+" ل.س\nربح المتجر: "+formatSypAmount(result.profitMinor)+" ل.س\nرسوم الشركة: "+formatSypAmount(result.providerFeeMinor)+" ل.س\nالمقبوض من الزبون: "+formatSypAmount(result.customerChargeMinor)+" ل.س")
                    .setPositiveButton("حسناً"){_,_->showServiceTransfers()}.show()
            }catch(e:Exception){Toast.makeText(this,e.message?:"تعذر تنفيذ التحويل",Toast.LENGTH_LONG).show()}
        }}
        dialog.show()
    }

    private fun showEditCashAccountDialog(
        account: CashAccountSummary, onSaved: () -> Unit
    ) {
        if (!isManager()) {
            Toast.makeText(this, "تعديل الحسابات المالية متاح للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        val repository = CashAccountRepository(this)
        val hasHistory = repository.hasTransactions(account.id)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val name = fieldForDialog("اسم الحساب").apply { setText(account.name) }
        val type = Spinner(this)
        val typeLabels = listOf("صندوق نقدي", "محفظة إلكترونية", "حساب بنكي", "أخرى")
        val typeCodes = listOf("CASH_BOX", "WALLET", "BANK", "OTHER")
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        type.setSelection(typeCodes.indexOf(account.accountType).coerceAtLeast(0))
        val currency = Spinner(this)
        val currencies = listOf("USD", "SYP")
        currency.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, currencies)
        currency.setSelection(currencies.indexOf(account.currencyCode).coerceAtLeast(0))
        if (hasHistory) {
            type.isEnabled = false
            currency.isEnabled = false
        }
        content.addView(label("اسم الحساب", 12f, mutedColor, false, Gravity.RIGHT))
        content.addView(name, fieldParams(dp(48), dp(8)))
        content.addView(label("نوع الحساب", 12f, mutedColor, false, Gravity.RIGHT))
        content.addView(type, fieldParams(dp(46), dp(6)))
        content.addView(label("العملة", 12f, mutedColor, false, Gravity.RIGHT))
        content.addView(currency, fieldParams(dp(46), dp(6)))
        if (hasHistory) content.addView(label(
            "لأن الحساب يحتوي حركات مالية سابقة، يمكن تعديل الاسم فقط. نوع الحساب والعملة محميان حتى لا تتغير دلالة الحركات التاريخية.",
            11f, gold.toInt(), true, Gravity.RIGHT
        ).apply { setPadding(0, dp(4), 0, dp(4)) })
        AlertDialog.Builder(this).setTitle("تعديل الحساب المالي").setView(content)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ التعديلات") { _, _ ->
                try {
                    repository.updateDetails(
                        currentUserRole, currentUserId ?: error("جلسة المدير غير صالحة"),
                        account.id, name.text.toString(), typeCodes[type.selectedItemPosition],
                        currencies[currency.selectedItemPosition]
                    )
                    Toast.makeText(this, "تم تعديل الحساب مع حفظ سجل التدقيق", Toast.LENGTH_SHORT).show()
                    onSaved()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر تعديل الحساب", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun showCreateCashAccountDialog() {
        if (!isManager()) {
            Toast.makeText(this, "إنشاء الحسابات المالية متاح للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val name = fieldForDialog("اسم الحساب")
        val type = Spinner(this)
        val typeLabels = listOf("صندوق نقدي", "محفظة إلكترونية", "حساب بنكي", "أخرى")
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        val currency = Spinner(this)
        val currencies = listOf("USD", "SYP")
        currency.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, currencies)
        val opening = fieldForDialog("الرصيد الافتتاحي")
        opening.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        content.addView(name, fieldParams(dp(48), dp(8)))
        content.addView(label("نوع الحساب", 12f, mutedColor, false, Gravity.RIGHT))
        content.addView(type, fieldParams(dp(46), dp(6)))
        content.addView(label("العملة", 12f, mutedColor, false, Gravity.RIGHT))
        content.addView(currency, fieldParams(dp(46), dp(6)))
        content.addView(opening, fieldParams(dp(48), dp(6)))
        AlertDialog.Builder(this).setTitle("إضافة حساب مالي").setView(content)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                try {
                    val code = currencies[currency.selectedItemPosition]
                    val balance = if (code == "USD") parseUsdCents(opening.text.toString())
                        else parseSypAmount(opening.text.toString())
                    val typeCode = listOf("CASH_BOX", "WALLET", "BANK", "OTHER")[type.selectedItemPosition]
                    CashAccountRepository(this).create(currentUserId ?: error("جلسة المدير غير صالحة"),
                        name.text.toString(), typeCode, code, balance)
                    Toast.makeText(this, "تم إنشاء الحساب المالي", Toast.LENGTH_SHORT).show()
                    showCashAccounts()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر إنشاء الحساب", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun cashAccountTypeArabic(type: String): String = when (type) {
        "CASH_BOX" -> "صندوق نقدي"
        "WALLET" -> "محفظة"
        "BANK" -> "بنك"
        else -> "أخرى"
    }

    private fun formatAccountBalance(amountMinor: Long, currency: String): String =
        if (currency == "USD") "$" + moneyText(amountMinor)
        else formatSypAmount(amountMinor) + " ل.س"

    private fun invoiceStatusArabic(status: String): String = when (status) {
        "DRAFT" -> "مسودة"
        "POSTED" -> "معتمدة"
        "PARTIALLY_PAID" -> "مدفوعة جزئياً"
        "PAID" -> "مدفوعة"
        "CANCELLED" -> "ملغاة"
        else -> status
    }

    private fun showPurchaseInvoiceEditor() {
        markScreen("PURCHASE_EDITOR")
        val store = StoreRepository(this)
        store.ensureBaseSetup()
        val supplierRepository = SupplierRepository(this)
        val invoiceRepository = InvoiceRepository(this)
        val lines = mutableListOf<PurchaseLineDraft>()
        var draftId: String? = null
        var processing = false
        val suppliers = supplierRepository.listActive().toMutableList()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"; textSize = 25f; isAllCaps = false
            setTextColor(green); setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPurchases() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("فاتورة مشتريات جديدة", 19f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(28))
        }
        fun field(hint: String, value: String = "", inputType: Int = InputType.TYPE_CLASS_TEXT) =
            EditText(this).apply {
                this.hint = hint; setText(value); this.inputType = inputType
                textSize = 14f; setSingleLine(true); setPadding(dp(12), 0, dp(12), 0)
                background = inputBackground()
            }
        val date = field("تاريخ الفاتورة", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
        val supplierSpinner = Spinner(this)
        fun refreshSuppliers(selectedId: String? = null) {
            val options = listOf("بدون تحديد مورد") + suppliers.map { it.name }
            supplierSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
            if (selectedId != null) {
                val index = suppliers.indexOfFirst { it.id == selectedId }
                if (index >= 0) supplierSpinner.setSelection(index + 1)
            }
        }
        refreshSuppliers()
        val addSupplier = smallButton("+ إضافة مورد") {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(8), dp(18), 0)
            }
            val supplierName = field("اسم المورد *")
            val phone = field("رقم الهاتف (اختياري)", "", InputType.TYPE_CLASS_PHONE)
            val address = field("العنوان (اختياري)")
            box.addView(supplierName, fieldParams(dp(48), dp(8)))
            box.addView(phone, fieldParams(dp(48), dp(8)))
            box.addView(address, fieldParams(dp(48), dp(8)))
            AlertDialog.Builder(this).setTitle("إضافة مورد").setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ") { _, _ ->
                    try {
                        val created = supplierRepository.create(supplierName.text.toString(),
                            phone.text.toString(), address.text.toString())
                        suppliers.add(created)
                        refreshSuppliers(created.id)
                    } catch (e: Exception) {
                        Toast.makeText(this, e.message ?: "تعذر حفظ المورد", Toast.LENGTH_LONG).show()
                    }
                }.show()
        }
        val discount = field("خصم الفاتورة بالدولار (مثال 1.50)", "0",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val notes = field("ملاحظات (اختياري)")
        val lineList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val total = label("الإجمالي: 0.00 USD", 17f, green, true, Gravity.RIGHT)
        var renderLines: () -> Unit = {}
        val addProduct = Button(this).apply {
            text = "+ اختيار صنف من المخزون أو الكتالوج"; isAllCaps = false
            setTextColor(green); textSize = 15f
            setOnClickListener { showPurchaseProductPicker { product -> showPurchaseLineDialog(product) { qty, cost ->
                val existing = lines.firstOrNull { it.product.id == product.id }
                if (existing == null) lines.add(PurchaseLineDraft(product, qty, cost))
                else { existing.quantity = existing.quantity.add(qty); existing.unitCostUsdCents = cost }
                renderLines()
            } } }
        }
        renderLines = {
            lineList.removeAllViews()
            if (lines.isEmpty()) {
                lineList.addView(label("لم تتم إضافة أصناف بعد.", 13f, mutedColor, false, Gravity.CENTER)
                    .apply { setPadding(0, dp(22), 0, dp(22)) })
            }
            lines.forEachIndexed { index, line ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                info.addView(label(line.product.name, 14f, green, true, Gravity.RIGHT))
                info.addView(label("${line.quantity.stripTrailingZeros().toPlainString()} ${line.product.unitName} × ${moneyText(line.unitCostUsdCents)} USD",
                    12f, mutedColor, false, Gravity.RIGHT))
                row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(smallButton("تعديل") {
                    showPurchaseLineDialog(line.product, line.quantity, line.unitCostUsdCents) { qty, cost ->
                        line.quantity = qty; line.unitCostUsdCents = cost; renderLines()
                    }
                }, LinearLayout.LayoutParams(dp(68), dp(42)))
                row.addView(smallButton("حذف") { lines.removeAt(index); renderLines() },
                    LinearLayout.LayoutParams(dp(58), dp(42)))
                lineList.addView(row)
                val divider = View(this).apply { setBackgroundColor(Color.rgb(232, 235, 231)) }
                lineList.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            }
            val subtotal = lines.fold(BigDecimal.ZERO) { sum, line ->
                sum.add(line.quantity.multiply(BigDecimal.valueOf(line.unitCostUsdCents)))
            }.setScale(0, RoundingMode.HALF_UP).longValueExact()
            val discountCents = runCatching { parseUsdCents(discount.text.toString()) }.getOrDefault(0L)
            val net = (subtotal - discountCents).coerceAtLeast(0L)
            total.text = "الإجمالي قبل الخصم: ${moneyText(subtotal)} USD\nالصافي: ${formatDualMoney(net, store.getCurrentExchangeRateMilli())}"
        }
        discount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderLines() }
            override fun afterTextChanged(s: Editable?) {}
        })
        form.addView(label("بيانات الفاتورة", 20f, green, true, Gravity.RIGHT))
        form.addView(date, fieldParams(dp(48), dp(8)))
        form.addView(label("المورد", 13f, mutedColor, false, Gravity.RIGHT))
        form.addView(supplierSpinner, fieldParams(dp(48), dp(4)))
        form.addView(addSupplier, fieldParams(dp(44), dp(12)))
        form.addView(sectionHeading("أصناف الفاتورة"))
        form.addView(addProduct, fieldParams(dp(50), dp(8)))
        form.addView(lineList, fieldParams(-2, dp(12)))
        form.addView(discount, fieldParams(dp(48), dp(8)))
        form.addView(total, fieldParams(-2, dp(12)))
        form.addView(notes, fieldParams(dp(48), dp(12)))
        val saveDraft = Button(this).apply {
            text = "حفظ كمسودة"; isAllCaps = false; setTextColor(green)
            setOnClickListener {
                if (processing) return@setOnClickListener
                try {
                    processing = true
                    val supplierId = if (supplierSpinner.selectedItemPosition > 0)
                        suppliers.getOrNull(supplierSpinner.selectedItemPosition - 1)?.id else null
                    val saved = invoiceRepository.savePurchaseDraft(currentUserId ?: error("جلسة المستخدم غير صالحة"), 
                        UUID.randomUUID().toString(), draftId, supplierId, date.text.toString().trim(),
                        parseUsdCents(discount.text.toString()), notes.text.toString().trim().ifEmpty { null },
                        lines.map { InvoiceLineInput(it.product.id, it.quantity, it.unitCostUsdCents) }
                    )
                    draftId = saved.invoiceId
                    Toast.makeText(this@MainActivity, "تم حفظ المسودة ${saved.invoiceNumber}", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, e.message ?: "تعذر حفظ المسودة", Toast.LENGTH_LONG).show()
                } finally { processing = false }
            }
        }
        val post = Button(this).apply {
            text = "حفظ واعتماد الفاتورة وتحديث المخزون"; isAllCaps = false
            setTextColor(Color.WHITE); setBackgroundColor(green)
            setOnClickListener {
                if (processing) return@setOnClickListener
                try {
                    processing = true
                    val supplierId = if (supplierSpinner.selectedItemPosition > 0)
                        suppliers.getOrNull(supplierSpinner.selectedItemPosition - 1)?.id else null
                    val saved = invoiceRepository.savePurchaseDraft(currentUserId ?: error("جلسة المستخدم غير صالحة"), 
                        UUID.randomUUID().toString(), draftId, supplierId, date.text.toString().trim(),
                        parseUsdCents(discount.text.toString()), notes.text.toString().trim().ifEmpty { null },
                        lines.map { InvoiceLineInput(it.product.id, it.quantity, it.unitCostUsdCents) }
                    )
                    draftId = saved.invoiceId
                    invoiceRepository.postPurchase(currentUserId ?: error("جلسة المستخدم غير صالحة"), saved.invoiceId, UUID.randomUUID().toString())
                    Toast.makeText(this@MainActivity, "تم اعتماد الفاتورة وتحديث المخزون", Toast.LENGTH_LONG).show()
                    showPurchases()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, e.message ?: "تعذر اعتماد الفاتورة", Toast.LENGTH_LONG).show()
                } finally { processing = false }
            }
        }
        form.addView(saveDraft, fieldParams(dp(50), dp(8)))
        form.addView(post, fieldParams(dp(54), dp(8)))
        body.addView(form)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        renderLines()
    }

    private fun showPurchaseProductPicker(onSelected: (StoreProduct) -> Unit) {
        val repository = StoreRepository(this)
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val search = fieldForDialog("ابحث باسم المنتج أو الباركود أو SKU")
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(results) }
        val addNew = Button(this).apply {
            text = "+ إضافة منتج جديد"; isAllCaps = false
            setTextColor(Color.WHITE); setBackgroundColor(green)
            setOnClickListener { showProductEditor(null) {
                showPurchaseProductPicker(onSelected)
            } }
        }
        dialogContent.addView(search, fieldParams(dp(48), dp(8)))
        dialogContent.addView(addNew, fieldParams(dp(46), dp(8)))
        dialogContent.addView(scroll, LinearLayout.LayoutParams(-1, dp(300)))
        val dialog = AlertDialog.Builder(this).setTitle("اختيار صنف").setView(dialogContent)
            .setNegativeButton("إغلاق", null).create()
        fun refresh(query: String) {
            results.removeAllViews()
            repository.listProducts().filter {
                query.isBlank() || it.name.contains(query, true) ||
                    (it.barcode?.contains(query, true) == true) || (it.sku?.contains(query, true) == true)
            }.forEach { product ->
                val item = smallButton("${product.name}  •  ${product.unitName}  •  ${moneyText(product.purchaseCostUsdCents)} USD") {
                    dialog.dismiss(); onSelected(product)
                }
                item.gravity = Gravity.RIGHT
                results.addView(item, fieldParams(dp(48), dp(4)))
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        refresh("")
        dialog.show()
    }

    private fun showPurchaseLineDialog(
        product: StoreProduct,
        initialQuantity: BigDecimal = BigDecimal.ONE,
        initialCost: Long = product.purchaseCostUsdCents,
        onSave: (BigDecimal, Long) -> Unit
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val numeric = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val quantity = fieldForDialog("الكمية ${product.unitName}", initialQuantity.stripTrailingZeros().toPlainString(), numeric)
        val cost = fieldForDialog("تكلفة الوحدة بالدولار", moneyText(initialCost), numeric)
        val rate = StoreRepository(this).getCurrentExchangeRateMilli()
        val costSyp = fieldForDialog("تكلفة الوحدة بالليرة السورية",
            if (rate > 0L) formatSypAmount(CurrencyConverter.usdCentsToSyp(initialCost, rate)) else "",
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        var syncing = false
        cost.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (syncing || rate <= 0L) return
                val cents = runCatching { parseUsdCents(s?.toString().orEmpty()) }.getOrNull() ?: return
                syncing = true; costSyp.setText(formatSypAmount(CurrencyConverter.usdCentsToSyp(cents, rate))); syncing = false
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        costSyp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (syncing || rate <= 0L) return
                val amount = runCatching { parseSypAmount(s?.toString().orEmpty()) }.getOrNull() ?: return
                syncing = true; cost.setText(moneyText(CurrencyConverter.sypToUsdCents(amount, rate))); syncing = false
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        costSyp.isEnabled = rate > 0L
        if (rate <= 0L) costSyp.hint = "سجّل سعر الصرف أولاً"
        content.addView(label(product.name, 17f, green, true, Gravity.CENTER).apply {
            setPadding(0, 0, 0, dp(8))
        })
        content.addView(quantity, fieldParams(dp(48), dp(8)))
        content.addView(cost, fieldParams(dp(48), dp(8)))
        content.addView(costSyp, fieldParams(dp(48), dp(8)))
        AlertDialog.Builder(this).setTitle("بيانات الصنف").setView(content)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إضافة") { _, _ ->
                try {
                    val qty = BigDecimal(quantity.text.toString().trim().replace(',', '.'))
                    require(qty.signum() > 0) { "أدخل كمية أكبر من صفر" }
                    onSave(qty, parseUsdCents(cost.text.toString()))
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تحقق من الكمية والتكلفة", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun fieldForDialog(
        hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT
    ) = EditText(this).apply {
        this.hint = hint; setText(value); inputType = type
        textSize = 14f; setSingleLine(true); setPadding(dp(12), 0, dp(12), 0)
        background = inputBackground()
    }



    private fun showSalesReturns() {
        val repository = SalesReturnRepository(this)
        markScreen("SALES_RETURNS")
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(backgroundColor) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(Color.WHITE)
        }
        header.addView(Button(this).apply {
            text = "‹"; textSize = 25f; isAllCaps = false; setTextColor(green)
            setBackgroundColor(Color.TRANSPARENT); setOnClickListener { showPointOfSale() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("مرتجعات المبيعات", 19f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        content.addView(label("فواتير قابلة للإرجاع", 21f, green, true, Gravity.RIGHT))
        content.addView(label(
            "يُخصم الدين المستحق أولاً، ثم يُرد المتبقي نقداً. يمكنك توزيع المبلغ النقدي على أكثر من صندوق أو محفظة.",
            13f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(5), 0, dp(12)) })
        val invoices = repository.listReturnableInvoices()
        if (invoices.isEmpty()) {
            content.addView(label("لا توجد فواتير معتمدة تحتوي كميات متاحة للإرجاع.", 14f, mutedColor, false, Gravity.CENTER))
        } else invoices.forEach { invoice ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(13), dp(11), dp(13), dp(11))
                background = cardBackground()
            }
            card.addView(label("فاتورة " + invoice.number, 16f, green, true, Gravity.RIGHT))
            card.addView(label(
                invoice.date + " • " + (invoice.customerName ?: "بيع نقدي") + " • " + invoice.saleType,
                12f, mutedColor, false, Gravity.RIGHT
            ))
            card.addView(label("الإجمالي: " + invoice.totalUsdCents + " سنت USD", 13f, textColor, true, Gravity.RIGHT))
            card.addView(smallButton("إنشاء مرتجع") { showSalesReturnEditor(invoice.id) },
                fieldParams(dp(42), dp(7)))
            content.addView(card, fieldParams(-2, dp(8)))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        page.addView(header, matchWidth())
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showSalesReturnEditor(invoiceId: String) {
        val actor = currentUserId ?: return
        val repository = SalesReturnRepository(this)
        val items = repository.listReturnableItems(invoiceId)
        val cashRepository = CashAccountRepository(this)
        val supplierRolloverId = cashRepository.systemRoleAccountId("SUPPLIER_ROLLOVER")
        val accounts = cashRepository.listActive().filter {
            (it.currencyCode == "SYP" || it.currencyCode == "USD") && it.id != supplierRolloverId
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val quantities = linkedMapOf<String, EditText>()
        content.addView(label("الكميات المراد إرجاعها", 16f, green, true, Gravity.RIGHT))
        items.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(label(
                item.productName + " • مباع " + item.soldQuantity.toPlainString() +
                    " • سبق إرجاعه " + item.returnedQuantity.toPlainString() +
                    " • متاح " + item.availableQuantity.toPlainString(),
                13f, textColor, true, Gravity.RIGHT
            ))
            val quantity = fieldForDialog("الكمية المرتجعة",
                type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            quantities[item.id] = quantity
            row.addView(quantity, fieldParams(dp(44), dp(4)))
            content.addView(row, matchWidth())
        }
        val reason = fieldForDialog("سبب المرتجع")
        content.addView(reason, fieldParams(dp(46), dp(8)))
        val summary = label("اضغط معاينة لحساب قيمة المرتجع والدين ورد النقد.", 13f, mutedColor, false, Gravity.RIGHT)
        content.addView(summary, fieldParams(-2, dp(8)))
        content.addView(label("توزيع رد المبلغ النقدي", 15f, green, true, Gravity.RIGHT))
        content.addView(label(
            "أضف حساباً أو أكثر. المبلغ يُدخل بعملة الحساب: الدولار للحساب USD والليرة للحساب SYP.",
            12f, mutedColor, false, Gravity.RIGHT
        ))
        val allocationContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val allocationRows = mutableListOf<Pair<Spinner, EditText>>()
        fun addAllocationRow() {
            if (accounts.isEmpty()) {
                Toast.makeText(this, "لا يوجد حساب نشط صالح لرد المبلغ", Toast.LENGTH_LONG).show()
                return
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
            val accountSpinner = Spinner(this@MainActivity)
            accountSpinner.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                accounts.map { it.name + " • " + it.currencyCode + " • " + formatAccountBalance(it.balanceMinor, it.currencyCode) })
            val amount = fieldForDialog("المبلغ من هذا الحساب")
            row.addView(accountSpinner, fieldParams(dp(44), dp(2)))
            row.addView(amount, fieldParams(dp(44), dp(2)))
            allocationContainer.addView(row, matchWidth())
            allocationRows.add(accountSpinner to amount)
        }
        content.addView(allocationContainer, matchWidth())
        content.addView(smallButton("+ إضافة حساب للتوزيع") { addAllocationRow() },
            fieldParams(dp(40), dp(5)))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("إنشاء مرتجع مبيعات")
            .setView(scroll)
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("معاينة", null)
            .setPositiveButton("اعتماد المرتجع", null)
            .create()
        fun selectedItems(): List<SalesReturnItemInput> = quantities.mapNotNull { (id, field) ->
            val raw = field.text.toString().trim().replace(',', '.')
            if (raw.isEmpty()) null else SalesReturnItemInput(id, BigDecimal(raw))
        }
        fun selectedAllocations(): List<SalesRefundAllocationInput> = allocationRows.mapNotNull { (spinner, field) ->
            val raw = field.text.toString().trim().replace(',', '.')
            if (raw.isEmpty()) null else {
                val account = accounts[spinner.selectedItemPosition]
                val minor = BigDecimal(raw).multiply(BigDecimal.valueOf(if (account.currencyCode == "USD") 100L else 1L))
                    .setScale(0, RoundingMode.UNNECESSARY).longValueExact()
                SalesRefundAllocationInput(account.id, minor)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                try {
                    val selected = selectedItems()
                    require(selected.isNotEmpty()) { "أدخل كمية مرتجعة لصنف واحد على الأقل" }
                    val preview = repository.previewSalesReturn(invoiceId, selected)
                    summary.text = "قيمة المرتجع: " + preview.totalUsdCents +
                        " سنت USD\nتخفيض الدين: " + preview.creditReductionUsdCents +
                        " سنت USD\nالمبلغ النقدي المطلوب رده: " + preview.cashRefundSypMinor + " ل.س"
                    if (preview.cashRefundSypMinor > 0L && allocationRows.isEmpty()) addAllocationRow()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذرت معاينة المرتجع", Toast.LENGTH_LONG).show()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val selected = selectedItems()
                    require(selected.isNotEmpty()) { "أدخل كمية مرتجعة لصنف واحد على الأقل" }
                    val preview = repository.previewSalesReturn(invoiceId, selected)
                    val allocations = selectedAllocations()
                    if (preview.cashRefundSypMinor == 0L) require(allocations.isEmpty()) {
                        "لا يوجد مبلغ نقدي مستحق؛ احذف توزيعات رد المبلغ"
                    } else require(allocations.isNotEmpty()) { "حدد حساباً ومبلغ الرد" }
                    val usedIds = allocations.map { it.cashAccountId }
                    require(usedIds.distinct().size == usedIds.size) { "لا تكرر الحساب؛ اجمع مبلغه في سطر واحد" }
                    val result = repository.postSalesReturn(
                        actor, invoiceId, UUID.randomUUID().toString(),
                        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()),
                        reason.text.toString(), selected, allocations
                    )
                    Toast.makeText(this, "تم اعتماد المرتجع " + result.returnNumber, Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    showSalesReturns()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر اعتماد المرتجع", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showInventoryAdjustments() {
        val actor = currentUserId ?: return
        val store = StoreRepository(this)
        val adjustments = InventoryAdjustmentRepository(this)
        markScreen("INVENTORY_ADJUSTMENTS")
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(backgroundColor) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(Color.WHITE)
        }
        header.addView(Button(this).apply {
            text = "‹"; textSize = 25f; isAllCaps = false; setTextColor(green)
            setBackgroundColor(Color.TRANSPARENT); setOnClickListener { showPointOfSale() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("تسويات المخزون", 19f, green, true, Gravity.CENTER),
            LinearLayout.LayoutParams(0, -2, 1f))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        content.addView(label("تسجيل فرق الجرد", 21f, green, true, Gravity.RIGHT))
        content.addView(label(
            "أدخل الفرق موجباً للزيادة وسالباً للنقصان. تكلفة الزيادة مطلوبة بالدولار للوحدة، أما النقص فيُحسب تلقائياً وفق FIFO.",
            13f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(5), 0, dp(12)) })
        val tracked = store.listProducts().filter { it.tracksInventory }
        if (tracked.isEmpty()) {
            content.addView(label("لا توجد أصناف تتبع المخزون.", 14f, mutedColor, false, Gravity.CENTER))
        } else {
            val productSpinner = Spinner(this)
            productSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                tracked.map { it.name + " • الرصيد " + it.stock })
            val delta = fieldForDialog("فرق الكمية: مثال 3 أو -2",
                type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            val unitCost = fieldForDialog("تكلفة شراء الوحدة بالدولار (للزيادة فقط)",
                type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            val reason = fieldForDialog("سبب التسوية")
            content.addView(label("الصنف", 13f, textColor, true, Gravity.RIGHT))
            content.addView(productSpinner, fieldParams(dp(48), dp(5)))
            content.addView(delta, fieldParams(dp(48), dp(8)))
            content.addView(unitCost, fieldParams(dp(48), dp(8)))
            content.addView(reason, fieldParams(dp(48), dp(8)))
            content.addView(smallButton("اعتماد تسوية المخزون") {
                try {
                    val quantity = BigDecimal(delta.text.toString().trim().replace(',', '.'))
                    val costText = unitCost.text.toString().trim().replace(',', '.')
                    val cost = if (quantity.signum() > 0) {
                        BigDecimal(costText).multiply(BigDecimal.valueOf(100L))
                            .setScale(0, RoundingMode.HALF_UP).longValueExact()
                    } else {
                        require(costText.isEmpty()) { "لا تدخل تكلفة للنقص؛ تُحسب تلقائياً وفق FIFO" }
                        null
                    }
                    val result = adjustments.postAdjustment(
                        actor, tracked[productSpinner.selectedItemPosition].id, quantity,
                        reason.text.toString(), UUID.randomUUID().toString(), cost
                    )
                    Toast.makeText(this, "تمت التسوية. الرصيد الجديد: " + result.quantityAfter, Toast.LENGTH_LONG).show()
                    showInventoryAdjustments()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر تسجيل التسوية", Toast.LENGTH_LONG).show()
                }
            }, fieldParams(dp(48), dp(10)))
        }
        content.addView(sectionHeading("آخر تسويات الجرد"))
        adjustments.listRecent(50).forEach { item ->
            val productName = tracked.firstOrNull { it.id == item.productId }?.name ?: item.productId
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = cardBackground()
            }
            card.addView(label(productName, 15f, green, true, Gravity.RIGHT))
            card.addView(label(
                (if (item.adjustmentType == "INCREASE") "زيادة " else "نقصان ") +
                    item.quantity.toPlainString() + " • " + item.quantityBefore.toPlainString() +
                    " ← " + item.quantityAfter.toPlainString(),
                13f, textColor, true, Gravity.RIGHT
            ))
            card.addView(label("السبب: " + item.reason, 12f, mutedColor, false, Gravity.RIGHT))
            content.addView(card, fieldParams(-2, dp(7)))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(content) }
        page.addView(header, matchWidth())
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showProductManagement() {
        if (!isManager()) {
            Toast.makeText(this, "إدارة المنتجات وسعر الصرف متاحة للمدير فقط", Toast.LENGTH_LONG).show()
            showPointOfSale()
            return
        }
        markScreen("PRODUCTS")
        val repository = StoreRepository(this)
        repository.ensureBaseSetup()
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(Color.WHITE)
        }
        val back = Button(this).apply {
            text = "‹"
            textSize = 25f
            setTextColor(green)
            isAllCaps = false
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showPointOfSale() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(label("إدارة المنتجات", 19f, green, true, Gravity.CENTER), LinearLayout.LayoutParams(0, -2, 1f))
        val body = ScrollView(this).apply { isFillViewport = true }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val search = EditText(this).apply {
            hint = "ابحث بالاسم أو الباركود أو SKU"
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
            background = inputBackground()
        }
        val add = Button(this).apply {
            text = "+ إضافة منتج جديد"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            setOnClickListener { showProductEditor(null) }
        }
        val category = Button(this).apply {
            text = "إضافة تصنيف"
            textSize = 14f
            isAllCaps = false
            setTextColor(green)
            setOnClickListener { showAddCategoryDialog() }
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(label("كتالوج المتجر", 24f, green, true, Gravity.RIGHT))
        content.addView(label("أدخل بيانات المنتجات مرة واحدة لتظهر مباشرة في نقطة البيع", 13f, mutedColor, false, Gravity.RIGHT).apply {
            setPadding(0, dp(4), 0, dp(14))
        })
        val rateTitle = label("سعر الصرف الحالي (ليرة سورية لكل 1 دولار)", 14f, green, true, Gravity.RIGHT)
        val currentRate = repository.getCurrentExchangeRateMilli()
        val rateInput = EditText(this).apply {
            hint = "مثال: 15000"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(12), 0, dp(12), 0)
            background = inputBackground()
            if (currentRate > 0L) setText(BigDecimal.valueOf(currentRate, 3).stripTrailingZeros().toPlainString())
        }
        val saveRate = Button(this).apply {
            text = "حفظ سعر الصرف"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(green)
            setOnClickListener {
                try {
                    val value = BigDecimal(rateInput.text.toString().trim().replace(',', '.'))
                    repository.saveExchangeRate(value, currentUserId ?: error("جلسة المدير غير صالحة"))
                    Toast.makeText(this@MainActivity, "تم تحديث سعر الصرف؛ بقيت أسعار الدولار المرجعية ثابتة", Toast.LENGTH_LONG).show()
                    showProductManagement()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, e.message ?: "أدخل سعر صرف صحيحاً", Toast.LENGTH_LONG).show()
                }
            }
        }
        content.addView(rateTitle, matchWidth())
        content.addView(rateInput, fieldParams(dp(48), dp(6)))
        content.addView(saveRate, fieldParams(dp(46), dp(8)))
        content.addView(add, fieldParams(dp(52), dp(8)))
        val importProducts = Button(this).apply {
            text = "استيراد أصناف من CSV / Excel"
            textSize = 14f
            isAllCaps = false
            setTextColor(green)
            setBackgroundColor(Color.WHITE)
            setOnClickListener {
                if (!isManager()) {
                    Toast.makeText(this@MainActivity, "استيراد الأصناف متاح للمدير فقط", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    // Do not restrict EXTRA_MIME_TYPES here: Android file providers often
                    // label valid .csv files as text/plain or application/octet-stream.
                    // SpreadsheetProductImporter validates the selected file extension
                    // and content before any database write.
                    type = "*/*"
                }
                startActivityForResult(intent, productImportRequestCode)
            }
        }
        content.addView(importProducts, fieldParams(dp(48), dp(5)))
        content.addView(category, fieldParams(dp(44), dp(14)))
        content.addView(search, fieldParams(dp(50), dp(12)))
        content.addView(list, matchWidth())
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)

        fun refresh(query: String = search.text.toString()) {
            list.removeAllViews()
            val items = repository.listProducts().filter {
                query.isBlank() || it.name.contains(query, true) ||
                    (it.barcode?.contains(query, true) == true) ||
                    (it.sku?.contains(query, true) == true)
            }
            if (items.isEmpty()) {
                list.addView(label("لا توجد منتجات بعد. ابدأ بإضافة أول منتج إلى متجرك.", 14f, mutedColor, false, Gravity.CENTER).apply {
                    setPadding(dp(12), dp(32), dp(12), dp(32))
                })
            }
            items.forEach { product ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = cardBackground()
                }
                card.addView(label(product.name, 16f, green, true, Gravity.RIGHT))
                card.addView(label(
                    "باركود: ${product.barcode ?: "—"}   |   SKU: ${product.sku ?: "—"}",
                    11f, mutedColor, false, Gravity.RIGHT
                ).apply { setPadding(0, dp(5), 0, dp(3)) })
                card.addView(label(
                    "التصنيف: ${product.categoryName ?: "غير محدد"}   •   الوحدة: ${product.unitName}",
                    12f, mutedColor, false, Gravity.RIGHT
                ))
                val currentRate = repository.getCurrentExchangeRateMilli()
                card.addView(label(
                    "تكلفة الشراء: ${formatDualMoney(product.purchaseCostUsdCents, currentRate)}",
                    13f, textColor, true, Gravity.RIGHT
                ).apply { setPadding(0, dp(5), 0, dp(2)) })
                card.addView(label(
                    "سعر المبيع: ${formatDualMoney(product.salePriceUsdCents, currentRate)}",
                    13f, green, true, Gravity.RIGHT
                ).apply { setPadding(0, 0, 0, dp(3)) })
                card.addView(label(
                    if (product.tracksInventory) "المخزون المتاح: ${formatQuantity(product.stock)} ${product.unitSymbol}"
                    else "لا يتتبع المخزون",
                    12f, mutedColor, false, Gravity.RIGHT
                ))
                val actions = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val edit = smallButton("تعديل") { showProductEditor(product) }
                val deactivate = smallButton("إيقاف") {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("إيقاف المنتج")
                        .setMessage("سيختفي ${product.name} من نقطة البيع، مع الاحتفاظ بسجلاته.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("إيقاف") { _, _ ->
                            try {
                                repository.setProductActive(product.id, false, currentUserId ?: error("جلسة المدير غير صالحة"))
                                refresh()
                                Toast.makeText(this@MainActivity, "تم إيقاف المنتج", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, e.message ?: "تعذر إيقاف المنتج", Toast.LENGTH_LONG).show()
                            }
                        }.show()
                }
                actions.addView(edit, LinearLayout.LayoutParams(0, dp(42), 1f))
                if (isManager() && product.tracksInventory && product.stock <= 0.0000001 && repository.canRecordOpeningStock(product.id)) {
                    val opening = smallButton("تسجيل رصيد الجرد") { showOpeningStockDialog(product) }
                    actions.addView(opening, LinearLayout.LayoutParams(0, dp(42), 1f))
                }
                actions.addView(deactivate, LinearLayout.LayoutParams(0, dp(42), 1f))
                card.addView(actions)
                val params = matchWidth().apply { bottomMargin = dp(10) }
                list.addView(card, params)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        refresh("")
    }

    private fun showOpeningStockDialog(product: StoreProduct) {
        if (!isManager()) return
        if (product.purchaseCostUsdCents <= 0L) {
            AlertDialog.Builder(this)
                .setTitle("تكلفة الشراء غير محددة")
                .setMessage("حدّث تكلفة شراء المنتج أولاً؛ لا يمكن إدخال رصيد جرد في رأس المال بتكلفة مجهولة.")
                .setPositiveButton("حسناً", null)
                .show()
            return
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
        }
        val quantity = EditText(this).apply {
            hint = "الكمية الفعلية بعد الجرد"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
            textSize = 15f
            setPadding(dp(12), 0, dp(12), 0)
            background = inputBackground()
        }
        val note = EditText(this).apply {
            hint = "ملاحظة الجرد (اختياري)"
            setSingleLine(true)
            textSize = 14f
            setPadding(dp(12), 0, dp(12), 0)
            background = inputBackground()
        }
        form.addView(label("الصنف: ${product.name}", 15f, green, true, Gravity.RIGHT))
        form.addView(label(
            "تكلفة الوحدة المعتمدة: ${formatDualMoney(product.purchaseCostUsdCents, StoreRepository(this).getCurrentExchangeRateMilli())}",
            12f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(5), 0, dp(8)) })
        form.addView(quantity, fieldParams(dp(48), dp(8)))
        form.addView(note, fieldParams(dp(48), dp(5)))
        val dialog = AlertDialog.Builder(this)
            .setTitle("تسجيل رصيد الجرد الافتتاحي")
            .setMessage("سيُسجّل هذا الرصيد مرة واحدة فقط، وتُحفظ تكلفته الحالية كطبقة افتتاحية FIFO. لا تستخدمه لتعديل مخزون سبق أن تحرك.")
            .setView(form)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ الرصيد", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val count = BigDecimal(quantity.text.toString().trim().replace(',', '.')).toDouble()
                    StoreRepository(this).recordOpeningStock(
                        product.id,
                        count,
                        currentUserId ?: error("جلسة المدير غير صالحة"),
                        note.text.toString().trim().ifEmpty { null }
                    )
                    dialog.dismiss()
                    Toast.makeText(this, "تم تسجيل رصيد الجرد وتكلفته الافتتاحية", Toast.LENGTH_LONG).show()
                    showProductManagement()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر تسجيل رصيد الجرد", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showProductEditor(existing: com.hazem.dailystore.data.StoreProduct?, onSaved: (() -> Unit)? = null) {
        if (!isManager()) {
            Toast.makeText(this, "إضافة المنتجات وتعديلها متاحان للمدير فقط", Toast.LENGTH_LONG).show()
            return
        }
        val repository = StoreRepository(this)
        val units = repository.getUnits()
        val categories = repository.getCategories()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        fun field(hintText: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(this).apply {
                hint = hintText
                setText(value)
                inputType = type
                textSize = 14f
                setSingleLine(true)
                setPadding(dp(12), 0, dp(12), 0)
                background = inputBackground()
            }
        }
        fun addField(view: View) { form.addView(view, fieldParams(dp(48), dp(9))) }
        val name = field("اسم المنتج *", existing?.name.orEmpty())
        val barcode = field("الباركود (اختياري)", existing?.barcode.orEmpty())
        val sku = field("رمز SKU (اختياري)", existing?.sku.orEmpty())
        val rateMilli = repository.getCurrentExchangeRateMilli()
        val numericType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val purchase = field("تكلفة الشراء بالدولار", existing?.let { moneyText(it.purchaseCostUsdCents) } ?: "0", numericType)
        val purchaseSyp = field("تكلفة الشراء بالليرة السورية", existing?.let {
            if (rateMilli > 0L) formatSypAmount(CurrencyConverter.usdCentsToSyp(it.purchaseCostUsdCents, rateMilli)) else ""
        } ?: "", numericType)
        val sale = field("سعر المبيع بالدولار *", existing?.let { moneyText(it.salePriceUsdCents) } ?: "", numericType)
        val saleSyp = field("سعر المبيع بالليرة السورية", existing?.let {
            if (rateMilli > 0L) formatSypAmount(CurrencyConverter.usdCentsToSyp(it.salePriceUsdCents, rateMilli)) else ""
        } ?: "", numericType)
        val quantity = field("الكمية الافتتاحية", "0", numericType)
        var syncingCurrencyFields = false
        fun bindCurrencyPair(usdField: EditText, sypField: EditText) {
            usdField.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (syncingCurrencyFields || rateMilli <= 0L) return
                    val cents = runCatching { parseUsdCents(s?.toString().orEmpty()) }.getOrNull() ?: return
                    syncingCurrencyFields = true
                    sypField.setText(formatSypAmount(CurrencyConverter.usdCentsToSyp(cents, rateMilli)))
                    syncingCurrencyFields = false
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            sypField.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (syncingCurrencyFields || rateMilli <= 0L) return
                    val syp = runCatching { parseSypAmount(s?.toString().orEmpty()) }.getOrNull() ?: return
                    syncingCurrencyFields = true
                    usdField.setText(moneyText(CurrencyConverter.sypToUsdCents(syp, rateMilli)))
                    syncingCurrencyFields = false
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            sypField.isEnabled = rateMilli > 0L
            if (rateMilli <= 0L) sypField.hint = "سجّل سعر الصرف أولاً"
        }
        bindCurrencyPair(purchase, purchaseSyp)
        bindCurrencyPair(sale, saleSyp)
        val unitSpinner = Spinner(this)
        unitSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units.map { "${it.name} (${it.symbol})" })
        val categorySpinner = Spinner(this)
        val categoryOptions = listOf("بدون تصنيف") + categories.map { it.name }
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categoryOptions)
        existing?.let { p ->
            val unitIndex = units.indexOfFirst { it.name == p.unitName }
            if (unitIndex >= 0) unitSpinner.setSelection(unitIndex)
            val categoryIndex = categories.indexOfFirst { it.name == p.categoryName }
            categorySpinner.setSelection(if (categoryIndex >= 0) categoryIndex + 1 else 0)
        }
        val tracks = CheckBox(this).apply {
            text = "يتتبع المخزون"
            textSize = 14f
            isChecked = existing?.tracksInventory ?: true
            setTextColor(textColor)
        }
        form.addView(label("البيانات الأساسية", 17f, green, true, Gravity.RIGHT).apply {
            setPadding(0, 0, 0, dp(8))
        })
        addField(name); addField(barcode); addField(sku)
        form.addView(label("وحدة القياس", 13f, mutedColor, false, Gravity.RIGHT))
        form.addView(unitSpinner, fieldParams(dp(48), dp(8)))
        form.addView(label("التصنيف", 13f, mutedColor, false, Gravity.RIGHT))
        form.addView(categorySpinner, fieldParams(dp(48), dp(8)))
        form.addView(label("تكلفة الشراء", 15f, green, true, Gravity.RIGHT).apply {
            setPadding(0, dp(6), 0, dp(4))
        })
        addField(purchase)
        addField(purchaseSyp)
        form.addView(label("سعر المبيع (الدولار هو المرجع الثابت)", 15f, green, true, Gravity.RIGHT).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        addField(sale)
        addField(saleSyp)
        form.addView(label(
            if (rateMilli > 0L) "سعر الصرف المستخدم: ${BigDecimal.valueOf(rateMilli, 3).stripTrailingZeros().toPlainString()} ل.س لكل دولار. تعديل أي حقل يحدّث العملة المقابلة تلقائياً."
            else "لم يُسجّل سعر صرف بعد. أدخل سعر الصرف من شاشة إدارة المنتجات لتفعيل حقول الليرة.",
            11f, mutedColor, false, Gravity.RIGHT
        ).apply { setPadding(0, dp(4), 0, dp(8)) })
        form.addView(tracks, fieldParams(dp(44), dp(4)))
        if (existing == null) {
            form.addView(label("المخزون الافتتاحي", 14f, green, true, Gravity.RIGHT))
            addField(quantity)
        } else {
            form.addView(label("المخزون الحالي: ${formatQuantity(existing.stock)} ${existing.unitSymbol}\nلا يتم تعديل المخزون من شاشة بيانات المنتج؛ استخدم حركة مخزون لاحقاً.", 12f, mutedColor, false, Gravity.RIGHT).apply {
                setPadding(0, dp(8), 0, dp(8))
            })
        }
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "إضافة منتج" else "تعديل المنتج")
            .setView(scroll)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val cleanName = name.text.toString().trim()
                    val cost = parseUsdCents(purchase.text.toString())
                    val price = parseUsdCents(sale.text.toString())
                    val opening = if (existing == null) quantity.text.toString().toDoubleOrNull() ?: 0.0 else 0.0
                    require(cleanName.isNotEmpty()) { "اسم المنتج مطلوب" }
                    require(price > 0L) { "أدخل سعر بيع صحيحاً بالدولار" }
                    require(cost >= 0L && opening >= 0.0) { "تحقق من السعر والكمية" }
                    val unit = units.getOrNull(unitSpinner.selectedItemPosition) ?: error("اختر وحدة قياس")
                    val categoryId = if (categorySpinner.selectedItemPosition > 0)
                        categories.getOrNull(categorySpinner.selectedItemPosition - 1)?.id else null
                    repository.saveProduct(
                        existing?.id, cleanName, barcode.text.toString(), sku.text.toString(),
                        categoryId, unit.id, cost, price, tracks.isChecked, opening,
                        currentUserId ?: error("جلسة المدير غير صالحة")
                    )
                    dialog.dismiss()
                    Toast.makeText(this, "تم حفظ بيانات المنتج", Toast.LENGTH_SHORT).show()
                    if (onSaved != null) onSaved() else showProductManagement()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر حفظ المنتج؛ تحقق من عدم تكرار الباركود أو SKU", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun showAddCategoryDialog() {
        if (!isManager()) {
            Toast.makeText(this, "إضافة التصنيفات متاحة للمدير فقط", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "اسم التصنيف"
            textSize = 16f
            setSingleLine(true)
            setPadding(dp(14), 0, dp(14), 0)
        }
        AlertDialog.Builder(this)
            .setTitle("إضافة تصنيف")
            .setView(input)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إضافة") { _, _ ->
                try {
                    StoreRepository(this).addCategory(input.text.toString(), currentUserId ?: error("جلسة المدير غير صالحة"))
                    Toast.makeText(this, "تمت إضافة التصنيف", Toast.LENGTH_SHORT).show()
                    showProductManagement()
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "تعذر إضافة التصنيف؛ ربما الاسم مستخدم", Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private fun moneyText(cents: Long): String =
        BigDecimal.valueOf(cents, 2).setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun formatSypAmount(amount: Long): String =
        DecimalFormat("#,##0").format(amount)

    private fun parseSypAmount(value: String): Long {
        val normalized = value.trim().replace(",", "").replace("٬", "")
        val amount = BigDecimal(normalized)
        require(amount.signum() >= 0) { "لا يمكن إدخال مبلغ سالب" }
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    private fun formatDualMoney(usdCents: Long, rateMilli: Long): String {
        val usd = "$" + moneyText(usdCents)
        return if (rateMilli > 0L) {
            "$usd  •  ${formatSypAmount(CurrencyConverter.usdCentsToSyp(usdCents, rateMilli))} ل.س"
        } else "$usd  •  سعر الصرف غير مسجل"
    }

    private fun parseUsdCents(value: String): Long {
        val normalized = value.trim().replace(',', '.')
        val amount = BigDecimal(normalized)
        require(amount.signum() >= 0) { "لا يمكن إدخال سعر سالب" }
        return amount.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    private fun formatPrice(usdCents: Long): String =
        formatDualMoney(usdCents, exchangeRateMilli)

    private fun formatQuantity(value: Double): String =
        DecimalFormat("#,##0.###").format(value)

    private fun label(
        value: String, size: Float, color: Int, bold: Boolean, gravityValue: Int
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = gravityValue
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionHeading(value: String) =
        label(value, 17f, green, true, Gravity.RIGHT).apply {
            setPadding(0, dp(8), 0, dp(10))
        }

    private fun smallButton(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        textSize = 16f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun inputBackground() = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), Color.rgb(220, 225, 220))
    }

    private fun cardBackground() = GradientDrawable().apply {
        setColor(Color.WHITE)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), Color.rgb(232, 235, 231))
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun fieldParams(height: Int, bottom: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
            bottomMargin = bottom
        }

    companion object {
        private const val SESSION_SCREEN = "screen_key"
        private const val SESSION_SCREEN_ARGUMENT = "screen_argument"
        private const val SESSION_CART = "pos_cart"
        private const val SESSION_CHECKOUT_DRAFT = "checkout_draft_id"
        private const val STATE_USER_ID = "daily_shop.user_id"
        private const val STATE_SCREEN = "daily_shop.screen"
        private const val STATE_SCREEN_ARGUMENT = "daily_shop.screen_argument"
        private const val STATE_CART = "daily_shop.cart"
        private const val STATE_CHECKOUT_DRAFT = "daily_shop.checkout_draft"
        private const val STABLE_VIEW_ID_BASE = 0x7e000000
        // TEST ONLY. Replace with database-backed authentication before release.
        const val TEST_USERNAME = "admin"
        const val TEST_PASSWORD = "1234"
    }
}
