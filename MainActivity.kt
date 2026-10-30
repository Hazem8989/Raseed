package com.hazem.dailystore

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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
        window.decorView.layoutDirection =
            android.view.View.LAYOUT_DIRECTION_RTL

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(backgroundColor)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(page)
        }

        val logo = ImageView(this).apply {
            setImageResource(R.drawable.daily_shop_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "The Daily Shop"
            adjustViewBounds = true
        }

        val subtitle = TextView(this).apply {
            text = "إدارة متجرك بسهولة واحترافية"
            textSize = 16f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
        }

        val line = TextView(this).apply {
            text = "━━━━━━━━━━━━━━━━"
            textSize = 18f
            setTextColor(gold)
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 28)
        }

        val title = TextView(this).apply {
            text = "تسجيل الدخول"
            textSize = 23f
            setTextColor(green)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }

        val username = EditText(this).apply {
            hint = "اسم المستخدم"
            textSize = 16f
            setSingleLine(true)
            setPadding(16, 14, 16, 14)
            background = inputBackground()
        }

        val password = EditText(this).apply {
            hint = "كلمة المرور"
            textSize = 16f
            setSingleLine(true)
            setInputType(
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            setPadding(16, 14, 16, 14)
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
            text = "The Daily Shop  •  الإصدار 1.0.0"
            textSize = 12f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER
            setPadding(0, 36, 0, 0)
        }

        page.addView(
            logo,
            LinearLayout.LayoutParams(
                (220 * resources.displayMetrics.density).toInt(),
                (150 * resources.displayMetrics.density).toInt()
            )
        )

        page.addView(subtitle, matchWidth())
        page.addView(line, matchWidth())
        page.addView(title, matchWidth())
        page.addView(username, fieldParams())
        page.addView(password, fieldParams())
        page.addView(login, fieldParams())
        page.addView(footer, matchWidth())

        setContentView(scroll)
    }

    private fun inputBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = 14f
            setStroke(1, Color.rgb(220, 225, 220))
        }
    }

    private fun matchWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun fieldParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 16
        }
    }
}
