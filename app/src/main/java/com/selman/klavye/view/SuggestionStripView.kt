package com.selman.klavye.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.selman.klavye.ui.KlavyeTheme
import com.selman.klavye.ui.Themes

interface SuggestionStripListener {
    fun onSuggestionPicked(word: String)
    fun onEmojiClicked()
    fun onClipboardClicked()
}

class SuggestionStripView(context: Context) : View(context) {

    var listener: SuggestionStripListener? = null

    private val density = context.resources.displayMetrics.density
    private val heightPx = (84f * density + 0.5f).toInt()
    private val toolbarHeightPx = 42.5f * density
    private val iconFirstCenterPx = 33f * density
    private val iconSpacingPx = 54f * density
    private val toolbarLinePaint = Paint()

    private var theme: KlavyeTheme = Themes.DARK

    private val words = arrayOfNulls<String>(3)
    private var count = 0
    // Kısaltılmış metinler, slot sırasına göre (0 sol, 1 orta, 2 sağ)
    private val shown = arrayOfNulls<String>(3)

    private val bgPaint = Paint()
    private val toolbarPaint = Paint()
    private val pressPaint = Paint()
    private val dividerPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val rect2 = RectF()

    private var textBaselineOffset = 0f

    private var pressedSlot = -1
    private var downSlot = -1

    init {
        textPaint.textAlign = Paint.Align.CENTER
        boldPaint.textAlign = Paint.Align.CENTER
        boldPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        iconStroke.style = Paint.Style.STROKE
        iconStroke.strokeCap = Paint.Cap.ROUND
        iconStroke.strokeWidth = 1.5f * density
        iconFill.style = Paint.Style.FILL
        dividerPaint.strokeWidth = 1f
        toolbarLinePaint.color = 0xFF292929.toInt()
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
        toolbarPaint.color = theme.toolbarBackground
        pressPaint.color = theme.keyPressed
        dividerPaint.color = theme.divider
        val size = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 19.5f, resources.displayMetrics
        )
        textPaint.textSize = size
        boldPaint.textSize = size
        textPaint.color = theme.stripText
        textPaint.alpha = (255 * 0.58f).toInt()
        boldPaint.color = theme.stripText
        iconStroke.color = theme.stripText
        iconFill.color = theme.stripText
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
        val tb = toolbarHeightPx
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Üst şerit: düğmeler
        canvas.drawRect(0f, 0f, w, tb, toolbarPaint)
        for (i in 0 until 2) {
            val cx = iconFirstCenterPx + i * iconSpacingPx
            if (pressedSlot == TOOL_BASE + i) {
                canvas.drawRect(cx - iconSpacingPx / 2f, 0f, cx + iconSpacingPx / 2f, tb, pressPaint)
            }
            val cy = tb / 2f
            if (i == 0) drawEmojiIcon(canvas, cx, cy) else drawClipboardIcon(canvas, cx, cy)
        }
        canvas.drawRect(0f, 0f, w, 1f, toolbarLinePaint)
        canvas.drawRect(0f, tb - 1f, w, tb, dividerPaint)

        // Alt şerit: öneriler
        if (count == 0) return
        val slotW = w / 3f
        val cy = tb + (h - tb) / 2f
        for (slot in 0 until 3) {
            val left = slot * slotW
            if (pressedSlot == slot) {
                canvas.drawRect(left, tb, left + slotW, h, pressPaint)
            }
            val text = shown[slot] ?: continue
            val p = if (slot == 1) boldPaint else textPaint
            canvas.drawText(text, left + slotW / 2f, cy + textBaselineOffset, p)
        }
    }

    private fun drawEmojiIcon(canvas: Canvas, cx: Float, cy: Float) {
        val r = 12f * density
        canvas.drawCircle(cx, cy, r, iconStroke)
        val eyeR = 1.3f * density
        canvas.drawCircle(cx - r * 0.38f, cy - r * 0.25f, eyeR, iconFill)
        canvas.drawCircle(cx + r * 0.38f, cy - r * 0.25f, eyeR, iconFill)
        val m = r * 0.55f
        rect.set(cx - m, cy - m, cx + m, cy + m)
        canvas.drawArc(rect, 25f, 130f, false, iconStroke)
    }

    private fun drawClipboardIcon(canvas: Canvas, cx: Float, cy: Float) {
        val hw = 9.5f * density
        val hh = 12f * density
        rect.set(cx - hw, cy - hh + 2f * density, cx + hw, cy + hh)
        canvas.drawRoundRect(rect, 2f * density, 2f * density, iconStroke)
        val cw = 5f * density
        rect2.set(cx - cw, cy - hh, cx + cw, cy - hh + 4f * density)
        canvas.drawRoundRect(rect2, 1.5f * density, 1.5f * density, iconFill)
        val lx = 5f * density
        canvas.drawLine(cx - lx, cy + 1f * density, cx + lx, cy + 1f * density, iconStroke)
        canvas.drawLine(cx - lx, cy + 5f * density, cx + lx * 0.4f, cy + 5f * density, iconStroke)
    }

    private fun slotAt(x: Float, y: Float): Int {
        if (y < 0f || y > height || x < 0f) return -1
        if (y < toolbarHeightPx) {
            val rel = x - (iconFirstCenterPx - iconSpacingPx / 2f)
            if (rel < 0f) return -1
            val i = (rel / iconSpacingPx).toInt()
            return if (i in 0..1) TOOL_BASE + i else -1
        }
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
                        if (slot >= TOOL_BASE) {
                            if (slot == TOOL_BASE) l.onEmojiClicked() else l.onClipboardClicked()
                        } else {
                            val word = slotWord(slot)
                            if (word != null) l.onSuggestionPicked(word)
                        }
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

    private companion object {
        const val TOOL_BASE = 10
    }
}
