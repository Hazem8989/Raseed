package com.hazem.dailystore

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageView

class SplashActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())

    private val openLogin = Runnable {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = android.graphics.Color.rgb(24, 75, 55)
        window.navigationBarColor = android.graphics.Color.rgb(24, 75, 55)

        val splashImage = ImageView(this).apply {
            setImageResource(R.drawable.the_daily_shop_splash)
            // Show the complete artwork without cropping its title or feature labels.
            // The dark-green background fills any remaining space without distortion.
            setBackgroundColor(android.graphics.Color.rgb(24, 75, 55))
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "The Daily Shop"
        }

        setContentView(splashImage)

        handler.postDelayed(openLogin, 1800)
    }

    override fun onDestroy() {
        handler.removeCallbacks(openLogin)
        super.onDestroy()
    }
}
