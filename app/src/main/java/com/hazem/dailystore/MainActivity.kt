package com.hazem.dailystore

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val green = Color.rgb(24, 75, 55)
    private val gold = Color.rgb(190, 155, 85)
    private val backgroundColor = Color.rgb(246, 248, 245)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = green
        window.navigationBarColor = green
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL

        // A fixed, compact login screen: no scroll view, oversized logo, or watermark.
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(24), dp(26), dp(24))
            setBackgroundColor(backgroundColor)
        }

        val brand = TextView(this).apply {
            text = "THE DAILY SHOP"
            textSize = 27f
            setTextColor(green)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
        }

        val subtitle = TextView(this).apply {
            text = "إدارة متجرك بسهولة واحترافية"
            textSize = 15f
            setTextColor(Color.rgb(95, 105, 98))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(22))
        }

        val divider = TextView(this).apply {
            text = "━━━━━━━━━━━━"
            textSize = 15f
            setTextColor(gold)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(18))
        }

        val title = TextView(this).apply {
            text = "تسجيل الدخول"
            textSize = 22f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        }

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
            setInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
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
                Toast.makeText(
                    this@MainActivity,
                    "سيتم ربط التحقق من بيانات الدخول في المرحلة التالية",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        val footer = TextView(this).apply {
            text = "الإصدار 1.0.0"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(20), 0, 0)
        }

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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun inputBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.rgb(220, 225, 220))
        }
    }

    private fun matchWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun fieldParams(height: Int, bottom: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            height
        ).apply {
            bottomMargin = bottom
        }
    }
}
