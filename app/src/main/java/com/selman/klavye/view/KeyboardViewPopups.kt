package com.selman.klavye.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.ceil

/**
 * Tuş önizleme balonunun çizimi: yuvarlak köşeli arka plan + ortalanmış etiket. Görünüm
 * değildir; KeyboardRootLayout balonu kendi dispatchDraw'unda bununla çizer.
 * Paint/RectF önceden hazır; [draw] tahsis yapmaz.
 */
internal class KeyPreviewPainter {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fm = Paint.FontMetrics()
    private val rect = RectF()

    private var cornerPx = 0f
    private var baseTextSize = 0f
    private var baselineFactor = 0f

    /** Renk ve boyutları ayarlar (tema değişiminde). */
    fun configure(background: Int, textColor: Int, textSizePx: Float, cornerRadiusPx: Float) {
        bgPaint.color = background
        textPaint.color = textColor
        baseTextSize = textSizePx
        cornerPx = cornerRadiusPx
        textPaint.textSize = REF_SIZE
        textPaint.getFontMetrics(fm)
        baselineFactor = -(fm.ascent + fm.descent) / 2f / REF_SIZE
        textPaint.textSize = textSizePx
    }

    /** Balonu verilen dikdörtgene çizer; etiket genişliğin %86'sına sığacak kadar küçülür. */
    fun draw(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, label: String) {
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return
        rect.set(left, top, right, bottom)
        canvas.drawRoundRect(rect, cornerPx, cornerPx, bgPaint)
        if (label.isEmpty()) return
        var size = baseTextSize
        textPaint.textSize = size
        val maxW = w * 0.86f
        val tw = textPaint.measureText(label)
        if (tw > maxW && tw > 0f) {
            size = size * maxW / tw
            textPaint.textSize = size
        }
        canvas.drawText(label, left + w / 2f, top + h / 2f + baselineFactor * size, textPaint)
    }

    private companion object {
        const val REF_SIZE = 100f
    }
}

/**
 * Uzun basma seçenek panelinin içeriği. Seçenekler bir ızgaraya dizilir:
 * satır 0 en altta (parmağa en yakın), [reversed] ise sütunlar sağdan sola
 * doldurulur (klavyenin sağ yarısındaki tuşlar için ilk seçenek tuşun üstünde kalsın diye).
 * Dokunulamaz bir PopupWindow içinde gösterilir; seçim KeyboardView'in dokunma
 * akışından [indexAt] ile hesaplanır.
 */
internal class MoreKeysContent(context: Context) : View(context) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fm = Paint.FontMetrics()
    private val rect = RectF()

    private var options: List<String> = emptyList()
    private var columns = 1
    private var rows = 1
    private var cellW = 1f
    private var cellH = 1f
    private var pad = 0f
    private var reversed = false
    private var cornerPx = 0f
    private var baseTextSize = 0f
    private var baselineFactor = 0f

    /** Seçili seçeneğin indeksi. */
    var selectedIndex = 0
        private set

    /** Panelin piksel genişliği. */
    val panelWidth: Int get() = ceil(columns * cellW + 2f * pad).toInt()

    /** Panelin piksel yüksekliği. */
    val panelHeight: Int get() = ceil(rows * cellH + 2f * pad).toInt()

    /** Seçenek sayısı. */
    val optionCount: Int get() = options.size

    /** Renk ve boyutları ayarlar (tema değişiminde). */
    fun configure(
        background: Int,
        selectedBackground: Int,
        textColor: Int,
        textSizePx: Float,
        cornerRadiusPx: Float
    ) {
        bgPaint.color = background
        selPaint.color = selectedBackground
        textPaint.color = textColor
        baseTextSize = textSizePx
        cornerPx = cornerRadiusPx
        textPaint.textSize = REF_SIZE
        textPaint.getFontMetrics(fm)
        baselineFactor = -(fm.ascent + fm.descent) / 2f / REF_SIZE
        textPaint.textSize = textSizePx
        invalidate()
    }

    /** Yeni seçenek setini ve ızgarayı kurar; seçim ilk elemana döner. */
    fun setup(
        options: List<String>,
        columns: Int,
        rows: Int,
        cellW: Float,
        cellH: Float,
        pad: Float,
        reversed: Boolean
    ) {
        this.options = options
        this.columns = columns.coerceAtLeast(1)
        this.rows = rows.coerceAtLeast(1)
        this.cellW = cellW
        this.cellH = cellH
        this.pad = pad
        this.reversed = reversed
        selectedIndex = 0
        invalidate()
    }

    /** Seçili seçeneğin metni (seçenek yoksa boş). */
    fun selectedText(): String =
        if (selectedIndex in options.indices) options[selectedIndex] else ""

    /** Seçimi değiştirir; değişirse yeniden çizer. */
    fun select(index: Int) {
        if (options.isEmpty()) return
        val i = index.coerceIn(0, options.size - 1)
        if (i != selectedIndex) {
            selectedIndex = i
            invalidate()
        }
    }

    /**
     * Panel koordinatındaki (x, y) noktasına en yakın seçeneğin indeksi.
     * Nokta panelin dışındaysa en yakın hücreye sıkıştırılır (iptal yok).
     */
    fun indexAt(x: Float, y: Float): Int {
        val n = options.size
        if (n == 0) return 0
        var visualCol = ((x - pad) / cellW).toInt()
        if (x < pad) visualCol = 0
        visualCol = visualCol.coerceIn(0, columns - 1)
        var visualRowFromTop = ((y - pad) / cellH).toInt()
        if (y < pad) visualRowFromTop = 0
        visualRowFromTop = visualRowFromTop.coerceIn(0, rows - 1)
        val col = if (reversed) columns - 1 - visualCol else visualCol
        val row = rows - 1 - visualRowFromTop
        val idx = row * columns + col
        return if (idx >= n) n - 1 else idx
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, cornerPx, cornerPx, bgPaint)
        val n = options.size
        if (n == 0) return
        val inset = pad * 0.5f
        val selCorner = (cornerPx - inset).coerceAtLeast(0f)
        val maxTextW = cellW * 0.86f
        for (i in 0 until n) {
            val row = i / columns
            val col = i % columns
            val visualCol = if (reversed) columns - 1 - col else col
            val visualRowFromTop = rows - 1 - row
            val left = pad + visualCol * cellW
            val top = pad + visualRowFromTop * cellH
            if (i == selectedIndex) {
                rect.set(left + inset, top + inset, left + cellW - inset, top + cellH - inset)
                canvas.drawRoundRect(rect, selCorner, selCorner, selPaint)
            }
            val text = options[i]
            var size = baseTextSize
            textPaint.textSize = size
            val tw = textPaint.measureText(text)
            if (tw > maxTextW && tw > 0f) {
                size = size * maxTextW / tw
                textPaint.textSize = size
            }
            canvas.drawText(text, left + cellW / 2f, top + cellH / 2f + baselineFactor * size, textPaint)
        }
    }

    private companion object {
        const val REF_SIZE = 100f
    }
}
