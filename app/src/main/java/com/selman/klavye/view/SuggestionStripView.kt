package com.selman.klavye.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.selman.klavye.ui.KlavyeTheme
import com.selman.klavye.ui.Themes

interface SuggestionStripListener {
    fun onSuggestionPicked(word: String)
}

/** Tek satırlık öneri çubuğu: 3 slot, ortadaki en iyi öneri. */
class SuggestionStripView(context: Context) : View(context) {

    var listener: SuggestionStripListener? = null

    private val density = context.resources.displayMetrics.density
    private val heightPx = (41.5f * density + 0.5f).toInt()

    private var theme: KlavyeTheme = Themes.DARK

    private val words = arrayOfNulls<String>(3)
    private var count = 0
    // Kısaltılmış metinler, slot sırasına göre (0 sol, 1 orta, 2 sağ)
    private val shown = arrayOfNulls<String>(3)

    private val bgPaint = Paint()
    private val pressPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var textBaselineOffset = 0f

    private var pressedSlot = -1
    private var downSlot = -1

    init {
        textPaint.textAlign = Paint.Align.CENTER
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        applyTheme()
    }

    fun setTheme(theme: KlavyeTheme) {
        if (this.theme == theme) return
        this.theme = theme
        applyTheme()
        prepareText()
        invalidate()
    }

    fun setSuggestions(words: List<String>) {
        val n = if (words.size > 3) 3 else words.size
        var same = n == count
        if (same) {
            for (i in 0 until n) {
                if (this.words[i] != words[i]) {
                    same = false
                    break
                }
            }
        }
        if (same) return
        for (i in 0 until 3) this.words[i] = if (i < n) words[i] else null
        count = n
        pressedSlot = -1
        downSlot = -1
        prepareText()
        invalidate()
    }

    private fun applyTheme() {
        bgPaint.color = theme.stripBackground
        pressPaint.color = theme.keyPressed
        val size = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 19.5f, resources.displayMetrics
        )
        textPaint.textSize = size
        boldPaint.textSize = size
        textPaint.color = theme.stripText
        textPaint.alpha = (255 * 0.58f).toInt()
        boldPaint.color = theme.stripText
        val fm = textPaint.fontMetrics
        textBaselineOffset = -(fm.ascent + fm.descent) / 2f
    }

    private fun slotWord(slot: Int): String? = when (slot) {
        0 -> words[1]
        1 -> words[0]
        else -> words[2]
    }

    private fun prepareText() {
        for (slot in 0 until 3) shown[slot] = null
        val w = width
        if (count == 0 || w <= 0) return
        val avail = w / 3f - 16f * density
        for (slot in 0 until 3) {
            val word = slotWord(slot) ?: continue
            val p = if (slot == 1) boldPaint else textPaint
            shown[slot] = if (p.measureText(word) <= avail) {
                word
            } else {
                val ell = p.measureText("…")
                val n = p.breakText(word, true, (avail - ell).coerceAtLeast(0f), null)
                word.substring(0, n).trimEnd() + "…"
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            0
        } else {
            MeasureSpec.getSize(widthMeasureSpec)
        }
        setMeasuredDimension(w, heightPx)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        prepareText()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Öneriler
        if (count == 0) return
        val slotW = w / 3f
        val cy = h / 2f
        for (slot in 0 until 3) {
            val left = slot * slotW
            if (pressedSlot == slot) {
                canvas.drawRect(left, 0f, left + slotW, h, pressPaint)
            }
            val text = shown[slot] ?: continue
            val p = if (slot == 1) boldPaint else textPaint
            canvas.drawText(text, left + slotW / 2f, cy + textBaselineOffset, p)
        }
    }

    private fun slotAt(x: Float, y: Float): Int {
        if (y < 0f || y > height || x < 0f) return -1
        if (count == 0) return -1
        val w = width
        if (w <= 0 || x >= w) return -1
        val slot = (x / (w / 3f)).toInt().coerceIn(0, 2)
        return if (slotWord(slot) != null) slot else -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val s = slotAt(event.x, event.y)
                downSlot = s
                if (s < 0) return false
                pressedSlot = s
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (downSlot >= 0) {
                    val s = if (slotAt(event.x, event.y) == downSlot) downSlot else -1
                    if (s != pressedSlot) {
                        pressedSlot = s
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val slot = downSlot
                val fire = slot >= 0 && pressedSlot == slot
                downSlot = -1
                if (pressedSlot != -1) {
                    pressedSlot = -1
                    invalidate()
                }
                if (fire) {
                    val l = listener
                    if (l != null) {
                        val word = slotWord(slot)
                        if (word != null) l.onSuggestionPicked(word)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                downSlot = -1
                if (pressedSlot != -1) {
                    pressedSlot = -1
                    invalidate()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
