package com.selman.klavye.setup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class SetupActivity : Activity() {

    private lateinit var enabledStatus: TextView
    private lateinit var defaultStatus: TextView

    private val bg = Color.parseColor("#121212")
    private val card = Color.parseColor("#1E1E1E")
    private val textMain = Color.parseColor("#ECECEC")
    private val textDim = Color.parseColor("#9A9A9A")
    private val accent = Color.parseColor("#4F8CFF")
    private val okColor = Color.parseColor("#5BD18A")
    private val offColor = Color.parseColor("#E0A458")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(bg))
        window.statusBarColor = bg
        window.navigationBarColor = bg
        @Suppress("DEPRECATION")
        run {
            var flags = window.decorView.systemUiVisibility
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            window.decorView.systemUiVisibility = flags
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        content.addView(text("Klavye", 32f, textMain, bold = true))
        content.addView(
            text("İnternet izni yoktur; yazdıkların cihazdan dışarı çıkmaz.", 15f, textDim),
            lp(top = 6)
        )

        enabledStatus = statusText()
        content.addView(
            stepCard("1. Adım", "Klavyeyi etkinleştir", enabledStatus) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            },
            lp(top = 24)
        )

        defaultStatus = statusText()
        content.addView(
            stepCard("2. Adım", "Varsayılan klavye yap", defaultStatus) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            },
            lp(top = 12)
        )

        val tryBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 16)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        tryBox.addView(text("3. Adım", 12f, textDim))
        tryBox.addView(text("Dene", 18f, textMain, bold = true), lp(top = 2))
        val edit = EditText(this).apply {
            hint = "Buraya yazarak klavyeyi dene"
            setHintTextColor(textDim)
            setTextColor(textMain)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            background = rounded(Color.parseColor("#2A2A2A"), 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        tryBox.addView(edit, lp(top = 10))
        content.addView(tryBox, lp(top = 12))

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        content.addView(
            text("Sürüm $version", 12f, textDim).apply { gravity = Gravity.CENTER_HORIZONTAL },
            lp(top = 28)
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            clipToPadding = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(scroll)

        // Sistem çubukları, çentik ve klavye boşluklarını padding olarak uygula.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            var l = 0
            var t = 0
            var r = 0
            var b = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val ime = insets.getInsets(WindowInsets.Type.ime())
                l = bars.left
                t = bars.top
                r = bars.right
                b = maxOf(bars.bottom, ime.bottom)
            } else {
                @Suppress("DEPRECATION")
                run {
                    l = insets.systemWindowInsetLeft
                    t = insets.systemWindowInsetTop
                    r = insets.systemWindowInsetRight
                    b = insets.systemWindowInsetBottom
                }
            }
            v.setPadding(l, t, r, b)
            insets
        }
        scroll.requestApplyInsets()

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshStatus()
    }

    private fun refreshStatus() {
        if (!::enabledStatus.isInitialized || !::defaultStatus.isInitialized) return
        setStatus(enabledStatus, isEnabled(), "✓ Etkin", "Etkin değil")
        setStatus(defaultStatus, isDefault(), "✓ Varsayılan", "Varsayılan değil")
    }

    private fun isEnabled(): Boolean {
        return try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.any { it.packageName == packageName }
        } catch (e: Exception) {
            false
        }
    }

    private fun isDefault(): Boolean {
        return try {
            val id = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            id != null && id.substringBefore('/') == packageName
        } catch (e: Exception) {
            false
        }
    }

    private fun setStatus(view: TextView, ok: Boolean, yes: String, no: String) {
        view.text = if (ok) yes else no
        view.setTextColor(if (ok) okColor else offColor)
    }

    private fun stepCard(
        label: String,
        buttonText: String,
        status: TextView,
        onClick: () -> Unit
    ): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(card, 16)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        box.addView(text(label, 12f, textDim))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btn = Button(this).apply {
            text = buttonText
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            background = rounded(accent, 12)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { onClick() }
        }
        row.addView(btn, LinearLayout.LayoutParams(0, dp(48), 1f))
        row.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(14) }
        )
        box.addView(row, lp(top = 8))
        return box
    }

    private fun statusText(): TextView =
        text("", 14f, offColor, bold = true).apply { gravity = Gravity.END }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(top) }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
        ).toInt()
}
