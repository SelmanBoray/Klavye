package com.selman.klavye.clipboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.selman.klavye.ui.KlavyeTheme

class ClipboardPanelView(
    context: Context,
    private val history: ClipboardHistory
) : FrameLayout(context) {

    var onPick: ((String) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private var theme: KlavyeTheme = KlavyeTheme(
        background = Color.parseColor("#1E1F22"),
        keyBackground = Color.parseColor("#34363B"),
        keyPressed = Color.parseColor("#4A4D54"),
        specialKeyBackground = Color.parseColor("#2A2C30"),
        accentKeyBackground = Color.parseColor("#4C8DF6"),
        keyText = Color.WHITE,
        hintText = Color.parseColor("#9AA0A6"),
        stripBackground = Color.parseColor("#26282C"),
        stripText = Color.WHITE,
        divider = Color.parseColor("#3A3C41"),
        popupBackground = Color.parseColor("#34363B"),
        popupText = Color.WHITE,
        keyCornerRadiusDp = 8f,
        keyGapDp = 4f,
        keyTextSizeSp = 18f,
        hintTextSizeSp = 11f
    )

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val backButton = makeHeaderButton("ABC")
    private val clearButton = makeHeaderButton("Temizle")
    private val titleView = TextView(context).apply {
        text = "Pano"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val dividerView = View(context)
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val emptyView = TextView(context).apply {
        text = "Kopyaladığın metinler burada görünür. Sabitlemediklerin 1 saat sonra silinir."
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    init {
        backButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClose?.invoke()
        }
        clearButton.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            history.clearUnpinned()
            refresh()
        }

        header.addView(backButton, LinearLayout.LayoutParams(dp(64), dp(36)))
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(clearButton, LinearLayout.LayoutParams(dp(72), dp(36)))
        header.setPadding(dp(8), dp(4), dp(8), dp(4))

        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        root.addView(dividerView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        list.setPadding(dp(8), dp(8), dp(8), dp(8))
        scroll.addView(
            list,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        emptyView.setPadding(dp(32), dp(46), dp(32), 0)
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        applyThemeToStatic()
        refresh()
    }

    fun setTheme(theme: KlavyeTheme) {
        this.theme = theme
        applyThemeToStatic()
        refresh()
    }

    fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { refresh() }
            return
        }
        list.removeAllViews()
        val items = history.items
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        scroll.visibility = if (items.isEmpty()) View.INVISIBLE else View.VISIBLE
        for (item in items) {
            list.addView(
                buildCard(item),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(theme.keyGapDp.toInt().coerceAtLeast(4)) }
            )
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        history.onChanged = { refresh() }
        refresh()
    }

    override fun onDetachedFromWindow() {
        history.onChanged = null
        super.onDetachedFromWindow()
    }

    private fun applyThemeToStatic() {
        setBackgroundColor(theme.background)
        header.setBackgroundColor(theme.stripBackground)
        titleView.setTextColor(theme.stripText)
        dividerView.setBackgroundColor(theme.divider)
        emptyView.setTextColor(theme.hintText)
        styleHeaderButton(backButton)
        styleHeaderButton(clearButton)
    }

    private fun styleHeaderButton(b: TextView) {
        b.setTextColor(theme.keyText)
        b.background = roundedStates(theme.specialKeyBackground, theme.keyPressed, 0)
    }

    private fun makeHeaderButton(label: String): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        isClickable = true
        isSingleLine = true
    }

    private fun buildCard(item: ClipItem): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            background = roundedStates(
                theme.keyBackground,
                theme.keyPressed,
                if (item.pinned) theme.accentKeyBackground else 0
            )
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onPick?.invoke(item.text)
            }
        }

        val text = TextView(context).apply {
            this.text = item.text
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(theme.keyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), dp(4), dp(6))
        }
        card.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val pin = IconView(context, IconView.PIN).apply {
            color = if (item.pinned) theme.accentKeyBackground else theme.hintText
            filled = item.pinned
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                history.togglePin(item)
                refresh()
            }
        }
        val trash = IconView(context, IconView.TRASH).apply {
            color = theme.hintText
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                history.remove(item)
                refresh()
            }
        }
        card.addView(pin, LinearLayout.LayoutParams(dp(40), dp(40)))
        card.addView(trash, LinearLayout.LayoutParams(dp(40), dp(40)))
        return card
    }

    private fun roundedStates(normal: Int, pressed: Int, strokeColor: Int): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(theme.keyCornerRadiusDp)
            setColor(color)
            if (strokeColor != 0) setStroke(dp(1).coerceAtLeast(1), strokeColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(pressed))
            addState(intArrayOf(), shape(normal))
        }
    }

    private fun dp(v: Int): Int = Math.round(dpF(v.toFloat()))

    private fun dpF(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** Kaynak dosyası gerektirmeyen, Canvas ile çizilen iğne ve çöp ikonu. */
    private class IconView(context: Context, private val kind: Int) : View(context) {
        var color: Int = Color.GRAY
            set(value) { field = value; invalidate() }
        var filled: Boolean = false
            set(value) { field = value; invalidate() }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val rect = RectF()

        init { isClickable = true }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val u = minOf(w, h) / 24f
            val ox = (w - 24f * u) / 2f
            val oy = (h - 24f * u) / 2f
            canvas.save()
            canvas.translate(ox, oy)
            canvas.scale(u, u)
            paint.color = color
            paint.strokeWidth = 1.8f
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.style = if (kind == PIN && filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            if (kind == PIN) drawPin(canvas) else drawTrash(canvas)
            canvas.restore()
        }

        private fun drawPin(canvas: Canvas) {
            // Raptiye: kafa (yamuk) + gövde + iğne ucu
            path.reset()
            path.moveTo(8f, 3f)
            path.lineTo(16f, 3f)
            path.lineTo(15f, 9f)
            path.lineTo(18f, 13f)
            path.lineTo(6f, 13f)
            path.lineTo(9f, 9f)
            path.close()
            canvas.drawPath(path, paint)
            val old = paint.style
            paint.style = Paint.Style.STROKE
            canvas.drawLine(12f, 13f, 12f, 21f, paint)
            paint.style = old
        }

        private fun drawTrash(canvas: Canvas) {
            canvas.drawLine(4f, 7f, 20f, 7f, paint)
            canvas.drawLine(9f, 7f, 9f, 4.5f, paint)
            canvas.drawLine(9f, 4.5f, 15f, 4.5f, paint)
            canvas.drawLine(15f, 4.5f, 15f, 7f, paint)
            rect.set(6f, 7f, 18f, 20.5f)
            canvas.drawRoundRect(rect, 2f, 2f, paint)
            canvas.drawLine(10f, 10.5f, 10f, 17f, paint)
            canvas.drawLine(14f, 10.5f, 14f, 17f, paint)
        }

        companion object {
            const val PIN = 0
            const val TRASH = 1
        }
    }
}
