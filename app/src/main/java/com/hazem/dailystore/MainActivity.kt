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
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat

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
                    Toast.makeText(this@MainActivity, "قسم $title سيُفعّل في مرحلته", Toast.LENGTH_SHORT).show()
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

    private fun formatPrice(usdCents: Long): String {
        if (exchangeRateMilli <= 0L) {
            return "$" + BigDecimal.valueOf(usdCents, 2).setScale(2, RoundingMode.HALF_UP).toPlainString()
        }
        val syp = CurrencyConverter.usdCentsToSyp(usdCents, exchangeRateMilli)
        return DecimalFormat("#,##0").format(syp) + " ل.س"
    }

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
