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
import android.view.View
import android.view.WindowManager
import android.widget.Button
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
import com.hazem.dailystore.data.CurrencyConverter
import com.hazem.dailystore.data.StoreRepository
import com.hazem.dailystore.data.InvoiceRepository
import com.hazem.dailystore.data.InvoiceLineInput
import com.hazem.dailystore.data.SupplierRepository
import com.hazem.dailystore.data.CustomerRepository
import com.hazem.dailystore.data.CashAccountRepository
import com.hazem.dailystore.data.CashAccountSummary
import com.hazem.dailystore.data.PurchasePaymentRepository
import com.hazem.dailystore.data.PurchaseInvoicePaymentSummary
import com.hazem.dailystore.data.StoreProduct
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = green
        window.navigationBarColor = green
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        StoreRepository(this).ensureBaseSetup()
        showLoginScreen()
    }

    private fun showLoginScreen() {
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
                if (username.text.toString().trim().equals(TEST_USERNAME, true) &&
                    password.text.toString() == TEST_PASSWORD
                ) showPointOfSale() else Toast.makeText(
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

    private fun showPointOfSale() {
        cart.clear()
        loadCatalog()
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
            setOnClickListener {
                Toast.makeText(
                    this@MainActivity,
                    "سنعمل على ربط الدفع وإصدار الفاتورة في المرحلة التالية",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        content.addView(checkout, fieldParams(dp(54), dp(8)))
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        renderProducts()
        renderCart()
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
            "المشتريات" to "فواتير الشراء والموردون",
            "العملاء والديون" to "ملفات العملاء والأرصدة",
            "الصناديق والمحافظ" to "الحركة النقدية والحسابات",
            "التقارير" to "المبيعات والأرباح والمخزون"
        )
        entries.forEach { (title, description) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(10), dp(8), dp(10))
                addView(label(title, 15f, textColor, true, Gravity.RIGHT))
                addView(label(description, 11f, mutedColor, false, Gravity.RIGHT))
                setOnClickListener {
                    when (title) {
                        "نقطة البيع" -> showPointOfSale()
                        "المنتجات والمخزون" -> showProductManagement()
                        "المشتريات" -> showPurchases()
                        "العملاء والديون" -> showCustomers()
                        "الصناديق والمحافظ" -> showCashAccounts()
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
                cart.clear()
                showLoginScreen()
            }
        }
        menu.addView(logout, fieldParams(dp(50), 0))
        val popup = PopupWindow(menu, dp(285), -1, true).apply {
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
        val outstandingTotal = invoices.filter {
            it.invoiceStatus != "DRAFT" && it.invoiceStatus != "CANCELLED"
        }.sumOf { it.outstandingUsdCents }
        val paidTotal = invoices.sumOf { it.paidUsdCents }
        val rate = StoreRepository(this).getCurrentExchangeRateMilli()
        val summary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground()
        }
        summary.addView(label("ملخص حسابات الموردين", 16f, green, true, Gravity.RIGHT))
        summary.addView(label("إجمالي المدفوع: ${formatDualMoney(paidTotal, rate)}",
            13f, textColor, false, Gravity.RIGHT).apply { setPadding(0, dp(6), 0, dp(3)) })
        summary.addView(label("المتبقي للموردين: ${formatDualMoney(outstandingTotal, rate)}",
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
            card.addView(label("الإجمالي: ${formatDualMoney(invoice.totalUsdCents, rate)}",
                13f, textColor, false, Gravity.RIGHT))
            card.addView(label("المدفوع: ${formatDualMoney(invoice.paidUsdCents, rate)}",
                13f, textColor, false, Gravity.RIGHT))
            card.addView(label("المتبقي: ${formatDualMoney(invoice.outstandingUsdCents, rate)}",
                14f, if (invoice.outstandingUsdCents > 0L) green else mutedColor,
                true, Gravity.RIGHT))
            if (invoice.outstandingUsdCents > 0L &&
                (invoice.invoiceStatus == "POSTED" || invoice.invoiceStatus == "PARTIALLY_PAID")) {
                card.addView(smallButton("＋ تسجيل دفعة للمورد") {
                    showPurchasePaymentDialog(invoice.id, invoice.invoiceNumber) { showPurchases() }
                }, fieldParams(dp(44), dp(4)))
            }
            val history = repository.listPayments(invoice.id)
            if (history.isNotEmpty()) {
                val historyText = history.joinToString("\n") {
                    "${formatAccountBalance(it.amountMinor, it.currencyCode)} • ${it.accountName} • ${it.createdAt.take(10)}"
                }
                card.addView(label("سجل الدفعات:\n$historyText", 11f, mutedColor, false, Gravity.RIGHT)
                    .apply { setPadding(0, dp(6), 0, 0) })
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

    private fun showPurchasePaymentDialog(
        invoiceId: String, invoiceNumber: String, onSaved: () -> Unit
    ) {
        val payments = PurchasePaymentRepository(this)
        val accounts = CashAccountRepository(this).listActive()
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
        content.addView(label("الفاتورة: $invoiceNumber", 15f, green, true, Gravity.RIGHT))
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
                        invoiceId, UUID.randomUUID().toString(), account.id, amountMinor,
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
        val repository = PurchasePaymentRepository(this)
        val suppliers = repository.listSupplierSummaries()
        val rate = StoreRepository(this).getCurrentExchangeRateMilli()
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
        val totalDue = suppliers.sumOf { it.outstandingUsdCents }
        val totalPaid = suppliers.sumOf { it.paidUsdCents }
        list.addView(label("إجمالي ما دُفع: ${formatDualMoney(totalPaid, rate)}",
            14f, textColor, false, Gravity.RIGHT))
        list.addView(label("إجمالي المستحق: ${formatDualMoney(totalDue, rate)}",
            16f, green, true, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(14)) })
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
            card.addView(label("الفواتير المعتمدة: ${formatDualMoney(supplier.totalPurchaseUsdCents, rate)}",
                12f, textColor, false, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, dp(2)) })
            card.addView(label("المدفوع: ${formatDualMoney(supplier.paidUsdCents, rate)}",
                12f, textColor, false, Gravity.RIGHT))
            card.addView(label("المتبقي: ${formatDualMoney(supplier.outstandingUsdCents, rate)}",
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
        val repository = PurchasePaymentRepository(this)
        val supplier = repository.listSupplierSummaries().firstOrNull { it.id == supplierId }
            ?: run { showSuppliers(); return }
        val rate = StoreRepository(this).getCurrentExchangeRateMilli()
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
        content.addView(label("إجمالي الفواتير: ${formatDualMoney(supplier.totalPurchaseUsdCents, rate)}",
            13f, textColor, false, Gravity.RIGHT))
        content.addView(label("إجمالي المدفوع: ${formatDualMoney(supplier.paidUsdCents, rate)}",
            13f, textColor, false, Gravity.RIGHT).apply { setPadding(0, dp(4), 0, dp(4)) })
        content.addView(label("المبلغ المستحق: ${formatDualMoney(supplier.outstandingUsdCents, rate)}",
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
                card.addView(label("الإجمالي: ${formatDualMoney(invoice.totalUsdCents, rate)}",
                    12f, textColor, false, Gravity.RIGHT))
                card.addView(label("المدفوع: ${formatDualMoney(invoice.paidUsdCents, rate)}",
                    12f, textColor, false, Gravity.RIGHT))
                card.addView(label("المتبقي: ${formatDualMoney(invoice.outstandingUsdCents, rate)}",
                    13f, green, true, Gravity.RIGHT))
                if (invoice.outstandingUsdCents > 0L &&
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
        val repository = CustomerRepository(this)
        val customers = repository.listActive()
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
            "يمكنك إنشاء ملف لكل زبون وتحديث بياناته. ستظهر الفواتير والتحصيلات والرصيد بعد استكمال مرحلة الدفع.",
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
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = cardBackground()
                isClickable = true
                setOnClickListener { showCustomerEditor(customer.id) { showCustomers() } }
                setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("إيقاف ملف العميل")
                        .setMessage("سيتم إخفاء ${customer.name} من القوائم الجديدة مع الاحتفاظ بسجلاته.")
                        .setNegativeButton("إلغاء", null)
                        .setPositiveButton("إيقاف") { _, _ ->
                            try {
                                repository.setActive(customer.id, false)
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
            card.addView(label("اضغط للتعديل • اضغط مطولاً لإيقاف الملف",
                10f, mutedColor, false, Gravity.RIGHT))
            content.addView(card, fieldParams(-2, dp(9)))
        }
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showCustomerEditor(customerId: String?, onSaved: () -> Unit) {
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
                        cleanName, phone.text.toString(), address.text.toString(), notes.text.toString()
                    ) else repository.update(
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

    private fun showCashAccounts() {
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
        content.addView(label("الحسابات المالية", 22f, green, true, Gravity.RIGHT))
        content.addView(label("كل حساب يحتفظ بعملة مستقلة، ويُحسب رصيده من الرصيد الافتتاحي والحركات المسجلة.",
            13f, mutedColor, false, Gravity.RIGHT).apply { setPadding(0, dp(5), 0, dp(12)) })
        val add = Button(this).apply {
            text = "+ إضافة صندوق أو محفظة"; isAllCaps = false
            setTextColor(Color.WHITE); setBackgroundColor(green)
            setOnClickListener { showCreateCashAccountDialog() }
        }
        content.addView(add, fieldParams(dp(50), dp(12)))
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
            content.addView(card, fieldParams(-2, dp(8)))
        }
        body.addView(content)
        page.addView(header, matchWidth())
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
    }

    private fun showCreateCashAccountDialog() {
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
                    CashAccountRepository(this).create(name.text.toString(), typeCode, code, balance)
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
                    val saved = invoiceRepository.savePurchaseDraft(
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
                    val saved = invoiceRepository.savePurchaseDraft(
                        UUID.randomUUID().toString(), draftId, supplierId, date.text.toString().trim(),
                        parseUsdCents(discount.text.toString()), notes.text.toString().trim().ifEmpty { null },
                        lines.map { InvoiceLineInput(it.product.id, it.quantity, it.unitCostUsdCents) }
                    )
                    draftId = saved.invoiceId
                    invoiceRepository.postPurchase(saved.invoiceId, UUID.randomUUID().toString())
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

    private fun showProductManagement() {
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
                    repository.saveExchangeRate(value)
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
                                repository.setProductActive(product.id, false)
                                refresh()
                                Toast.makeText(this@MainActivity, "تم إيقاف المنتج", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, e.message ?: "تعذر إيقاف المنتج", Toast.LENGTH_LONG).show()
                            }
                        }.show()
                }
                actions.addView(edit, LinearLayout.LayoutParams(0, dp(42), 1f))
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

    private fun showProductEditor(existing: com.hazem.dailystore.data.StoreProduct?, onSaved: (() -> Unit)? = null) {
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
                        categoryId, unit.id, cost, price, tracks.isChecked, opening
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
                    StoreRepository(this).addCategory(input.text.toString())
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
        // TEST ONLY. Replace with database-backed authentication before release.
        const val TEST_USERNAME = "admin"
        const val TEST_PASSWORD = "1234"
    }
}
