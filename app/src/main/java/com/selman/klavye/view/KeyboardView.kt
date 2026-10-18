package com.selman.klavye.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.widget.PopupWindow
import com.selman.klavye.layout.Key
import com.selman.klavye.layout.KeyType
import com.selman.klavye.layout.KeyboardLayout
import com.selman.klavye.layout.ShiftState
import com.selman.klavye.ui.KlavyeTheme
import com.selman.klavye.ui.Themes
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Klavye görünümünün servise bildirdiği olaylar. */
interface KeyboardActionListener {
    /** Bir tuş işlendi (CHAR ve özel tuşlar). */
    fun onKey(key: Key)

    /** Uzun basma panelinden bir metin seçildi. */
    fun onText(text: String)
}

/**
 * Tüm tuşları TEK bir View'da Canvas'a çizen klavye görünümü.
 *
 * - Tuş dikdörtgenleri yalnız [setLayout] / boyut / tema değişiminde hesaplanır;
 *   onDraw ve onTouchEvent hiçbir nesne tahsis etmez.
 * - Dokunma alanında ölü bölge yoktur: tuşlar arası görsel boşluğa (ve kenarlara)
 *   dokunmak en yakın tuşa gider.
 * - Tuş önizleme balonu ve uzun basma paneli, IME penceresinin üst sınırının
 *   dışına taşabilmek için dokunulamaz (FLAG_NOT_TOUCHABLE) birer PopupWindow
 *   alt penceresinde çizilir; dokunma akışı her zaman bu görünümde kalır.
 * - Hiçbir zamanlayıcı/animasyon arka planda dönmez; görünüm pencereden ayrılınca
 *   ya da gizlenince tüm Handler mesajları ve popup'lar temizlenir.
 */
class KeyboardView(context: Context) : View(context) {

    /** Tuş olaylarının alıcısı (servis). */
    var listener: KeyboardActionListener? = null

    // ---------------------------------------------------------------- durum
    private var currentLayout: KeyboardLayout? = null
    private var currentTheme: KlavyeTheme = Themes.DARK
    private var shiftState: ShiftState = ShiftState.OFF
    private var enterAction: Int = EditorInfo.IME_ACTION_NONE

    // ------------------------------------------- düzleştirilmiş tuş verisi
    private var keys: Array<Key> = emptyArray()
    private var keyCount = 0
    private var labelLower: Array<String> = emptyArray()
    private var labelUpper: Array<String> = emptyArray()
    private var longPressUpper: Array<List<String>> = emptyArray()
    private var rowCount = 0
    /** rowStart[r] .. rowStart[r+1]-1: r. sıranın tuş indeksleri. */
    private var rowStart = IntArray(1)

    // Dokunma hücreleri: hücrenin tamamı dokunma alanı; görünür tuş = hücre, her yandan keyGap/2 içeri.
    private var cellL = FloatArray(0)
    private var cellT = FloatArray(0)
    private var cellR = FloatArray(0)
    private var cellB = FloatArray(0)
    /**
     * Görünür tuş kutusunun yatay sınırları: hücre − keyGap/2 − visualPad×birim.
     * Yalnız çizim/önizleme konumu içindir; dokunma alanı hücrenin tamamıdır.
     * Dikeyde görünür kutu = hücre ± keyGap/2.
     */
    private var visL = FloatArray(0)
    private var visR = FloatArray(0)
    private var labelSize = FloatArray(0)
    /** rowTop[r] .. rowTop[r+1]: r. sıranın dikey hücre aralığı (sıra adımı = rowHeightDp × heightRatio). */
    private var rowTop = FloatArray(1)
    private var unitPx = 0f
    private var geometryValid = false

    // ------------------------------------------------- tema ölçüleri (px)
    private var keyGapPx = 0f
    private var cornerPx = 0f
    private var keyTextPx = 0f
    private var hintTextPx = 0f
    private var hintBaseline = 0f
    private var hintLabelShift = 0f
    private var spaceTextPx = 0f
    private var spaceTextColor = 0
    private var iconBoxPx = 0f
    private var deleteBoxWPx = 0f
    private var deleteBoxHPx = 0f
    private var iconStrokePx = 0f
    private var baselineFactor = 0f
    private var slideSlopPx = 0f
    private var panelSlopPx = 0f

    // ---------------------------------------------- önceden hazır çizim nesneleri
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fontMetrics = Paint.FontMetrics()
    private val drawRect = RectF()

    // ------------------------------------------------------------ dokunma
    private var activePointerId = INVALID_POINTER
    private var activeKey = -1
    private var lastX = 0f
    private var lastY = 0f
    private var longPressMode = false
    private var panelMoved = false
    private var panelStartX = 0f
    private var panelStartY = 0f
    private var panelLeft = 0f
    private var panelTop = 0f
    private var repeatCount = 0

    private val timerHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable { onLongPressTimeout() }
    private val repeatRunnable = Runnable { onRepeatTick() }
    private val previewHideRunnable = Runnable { hidePreview() }

    // ------------------------------------------------------------- popup'lar
    private val windowLoc = IntArray(2)
    /** Gösterilen önizleme görünümün üst kenarının dışına (uygulamanın üstüne) taşıyor mu. */
    private var previewOutside = false
    /**
     * Tuş önizleme balonu (çok kısa süreli, bkz. [PREVIEW_MAX_VISIBLE_MS]). [KEY_PREVIEW_ENABLED]
     * false olursa ne içerik görünümü ne de PopupWindow yaratılır: hiçbir IME alt penceresi açılmaz.
     */
    private val previewContent: KeyPreviewContent? =
        if (KEY_PREVIEW_ENABLED) KeyPreviewContent(context) else null
    private val previewPopup: PopupWindow? = previewContent?.let { createPopup(it) }
    private val moreKeysContent = MoreKeysContent(context)
    private val moreKeysPopup: PopupWindow = createPopup(moreKeysContent)

    init {
        isHapticFeedbackEnabled = true
        previewContent?.visibility = View.INVISIBLE
        applyThemeMetrics()
    }

    // ================================================================ API

    /** Yeni düzeni kurar; tuş geometrisini yeniden hesaplar. */
    fun setLayout(layout: KeyboardLayout) {
        if (layout === currentLayout) return
        resetTouch(dismissWindows = false)
        currentLayout = layout

        val rows = layout.rows
        rowCount = rows.size
        val flat = ArrayList<Key>()
        val starts = IntArray(rowCount + 1)
        for (r in 0 until rowCount) {
            starts[r] = flat.size
            flat.addAll(rows[r].keys)
        }
        starts[rowCount] = flat.size
        rowStart = starts

        val arr = flat.toTypedArray()
        val n = arr.size
        keys = arr
        keyCount = n
        // Büyütme YALNIZ içinde SHIFT tuşu olan düzende (sembol sayfalarında π → Π olmaz).
        var hasShift = false
        for (key in arr) if (key.type == KeyType.SHIFT) hasShift = true
        labelLower = Array(n) { arr[it].label }
        labelUpper = Array(n) { i ->
            if (arr[i].type == KeyType.CHAR) upperSingleLetter(arr[i].label, hasShift) else arr[i].label
        }
        longPressUpper = Array(n) { i -> arr[i].longPress.map { upperSingleLetter(it, hasShift) } }
        cellL = FloatArray(n)
        cellT = FloatArray(n)
        cellR = FloatArray(n)
        cellB = FloatArray(n)
        visL = FloatArray(n)
        visR = FloatArray(n)
        labelSize = FloatArray(n)
        rowTop = FloatArray(rowCount + 1)

        computeGeometry()
        requestLayout()
        invalidate()
    }

    /**
     * Ortak büyütme kuralı (servisle aynı): yalnız [enabled] (düzende SHIFT var) iken ve yalnız
     * tek karakterlik HARF metinlerde, tr-TR büyütmesi yine TEK karaktere eşleniyorsa büyük hali
     * döner (i→İ, ı→I). Çok karaktere açılanlar (ß→SS) ve harf olmayanlar olduğu gibi kalır.
     * Çizilen etikete, popup seçeneklerine ve onText'e giden metne uygulanır.
     */
    private fun upperSingleLetter(text: String, enabled: Boolean): String {
        if (!enabled || text.isEmpty()) return text
        if (text.codePointCount(0, text.length) != 1) return text
        if (!Character.isLetter(text.codePointAt(0))) return text
        val upper = text.uppercase(TR)
        return if (upper.codePointCount(0, upper.length) == 1) upper else text
    }

    /** Shift durumunu günceller (harfler tr-TR büyük çizilir). */
    fun setShiftState(state: ShiftState) {
        if (state == shiftState) return
        shiftState = state
        val k = activeKey
        val content = previewContent
        if (content != null && k in 0 until keyCount && !longPressMode && content.visibility == View.VISIBLE) {
            content.setLabel(if (state != ShiftState.OFF) labelUpper[k] else labelLower[k])
        }
        invalidate()
    }

    /** Temayı uygular. */
    fun setTheme(theme: KlavyeTheme) {
        currentTheme = theme
        applyThemeMetrics()
        computeGeometry()
        requestLayout()
        invalidate()
    }

    /** Enter ikonunu belirler: EditorInfo.IME_ACTION_* (NONE/UNSPECIFIED = yeni satır). */
    fun setEnterAction(imeAction: Int) {
        val action = imeAction and EditorInfo.IME_MASK_ACTION
        if (action == enterAction) return
        enterAction = action
        invalidate()
    }

    // ======================================================= ölçü/geometri

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val w = if (widthMode == View.MeasureSpec.UNSPECIFIED) {
            resources.displayMetrics.widthPixels
        } else {
            View.MeasureSpec.getSize(widthMeasureSpec)
        }
        // Toplam yükseklik = Σ(rowHeightDp × heightRatio) + üst/alt kenar boşlukları (+ padding;
        // gezinme çubuğu boşluğunu servis inset/padding ile ekler).
        val layout = currentLayout
        val rowsH = if (layout != null) dp(layout.rowHeightDp) * ratioSum(layout) else 0f
        val edges = if (layout != null && layout.rows.isNotEmpty()) dp(EDGE_TOP_DP + EDGE_BOTTOM_DP) else 0f
        val desired = (paddingTop + paddingBottom + edges + rowsH).roundToInt()
        setMeasuredDimension(w, View.resolveSize(desired, heightMeasureSpec))
    }

    /** Sıraların heightRatio toplamı (negatifler 0 sayılır). */
    private fun ratioSum(layout: KeyboardLayout): Float {
        val rows = layout.rows
        var sum = 0f
        for (r in rows.indices) sum += max(0f, rows[r].heightRatio)
        return sum
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeGeometry()
    }

    private fun applyThemeMetrics() {
        val t = currentTheme
        val dm = resources.displayMetrics
        keyGapPx = t.keyGapDp * dm.density
        cornerPx = t.keyCornerRadiusDp * dm.density
        keyTextPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, t.keyTextSizeSp, dm)
        hintTextPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, t.hintTextSizeSp, dm)

        textPaint.textSize = REF_TEXT_SIZE
        textPaint.getFontMetrics(fontMetrics)
        baselineFactor = -(fontMetrics.ascent + fontMetrics.descent) / 2f / REF_TEXT_SIZE
        textPaint.textSize = keyTextPx

        // İpucu (ör. ".": ",!?") tuşun üst ortasında; yazı yüksekliği ≈ 0.75 em, üstten küçük boşluk.
        hintPaint.textSize = hintTextPx
        hintPaint.color = t.hintText
        hintBaseline = dp(3f) + hintTextPx * 0.75f
        // İpucu varken ana etiket biraz aşağı kayar (çizimde tuş yüksekliğinin %12'siyle sınırlı).
        hintLabelShift = hintTextPx * 0.45f

        // SPACE etiketi: ~10 sp, keyText'in ~%58 alfası (SwiftKey ölçümü ≈ #939393 görünüm).
        spaceTextPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, SPACE_TEXT_SP, dm)
        val spaceAlpha = ((t.keyText ushr 24) * SPACE_TEXT_ALPHA).roundToInt().coerceIn(0, 255)
        spaceTextColor = (spaceAlpha shl 24) or (t.keyText and 0x00FFFFFF)

        iconPaint.color = t.keyText
        // İkon mürekkep kutuları (SwiftKey ölçümü, 2.75 px/dp): shift/emoji/enter 56×56, sil 56×44.
        iconBoxPx = dp(ICON_BOX_DP)
        deleteBoxWPx = dp(DELETE_ICON_W_DP)
        deleteBoxHPx = dp(DELETE_ICON_H_DP)
        iconStrokePx = dp(ICON_STROKE_DP)
        slideSlopPx = dp(6f)
        panelSlopPx = ViewConfiguration.get(context).scaledTouchSlop * 0.5f

        previewContent?.configure(t.popupBackground, t.popupText, keyTextPx * 1.5f, cornerPx * 1.5f)
        moreKeysContent.configure(
            t.popupBackground, t.accentKeyBackground, t.popupText, keyTextPx * 1.05f, cornerPx * 1.5f
        )
    }

    /** Tuş hücrelerini mevcut genişlik/yüksekliğe göre hesaplar (onDraw'da değil). */
    private fun computeGeometry() {
        geometryValid = false
        val layout = currentLayout ?: return
        val w = width
        val h = height
        if (w <= 0 || h <= 0 || rowCount == 0 || keyCount == 0) return

        // Yatay: satır = genişlik − her yandan EDGE_H_DP; en dış tuşun görünür kenarı
        // ekrandan keyGap/2 + EDGE_H_DP içeride. Dikey: üst/alt EDGE_*_DP kenar boşluğu.
        val edgeH = dp(EDGE_H_DP)
        val edgeTop = dp(EDGE_TOP_DP)
        val half = keyGapPx / 2f
        val contentL = paddingLeft + edgeH
        val contentR = w - paddingRight - edgeH
        val contentW = contentR - contentL
        val contentH = h - paddingTop - paddingBottom - edgeTop - dp(EDGE_BOTTOM_DP)
        val ratios = ratioSum(layout)
        if (contentW <= 0f || contentH <= 0f || ratios <= 0f) return
        // Ölçülen yükseklik istenene eşitse ratioPx = rowHeightDp (px); dayatılmış yükseklikte orantılı.
        val ratioPx = contentH / ratios
        rowTop[0] = paddingTop + edgeTop
        for (r in 0 until rowCount) {
            rowTop[r + 1] = rowTop[r] + max(0f, layout.rows[r].heightRatio) * ratioPx
        }

        var maxUnits = 0f
        for (r in 0 until rowCount) {
            val units = rowUnits(layout, r)
            if (units > maxUnits) maxUnits = units
        }
        if (maxUnits <= 0f) return
        unitPx = contentW / maxUnits

        for (r in 0 until rowCount) {
            val row = layout.rows[r]
            val units = rowUnits(layout, r)
            var x = contentL + (maxUnits - units) * unitPx / 2f + row.leftGap * unitPx
            val top = rowTop[r]
            val bottom = rowTop[r + 1]
            var i = rowStart[r]
            for (key in row.keys) {
                cellL[i] = x
                x += key.width * unitPx
                cellR[i] = x
                cellT[i] = top
                cellB[i] = bottom
                visL[i] = cellL[i] + half + max(0f, key.visualPadLeft) * unitPx
                visR[i] = cellR[i] - half - max(0f, key.visualPadRight) * unitPx
                i++
            }
        }
        for (i in 0 until keyCount) labelSize[i] = fitLabelSize(i)
        geometryValid = true
    }

    private fun rowUnits(layout: KeyboardLayout, r: Int): Float {
        val row = layout.rows[r]
        var units = row.leftGap + row.rightGap
        for (key in row.keys) units += key.width
        return units
    }

    /** Etiketin tuşa sığacağı yazı boyutu (geometri anında önceden hesaplanır). */
    private fun fitLabelSize(i: Int): Float {
        val key = keys[i]
        val base = when (key.type) {
            KeyType.CHAR -> keyTextPx
            KeyType.SPACE -> spaceTextPx
            else -> keyTextPx * 0.68f
        }
        val maxW = (visR[i] - visL[i]) * 0.82f
        if (maxW <= 0f) return base
        textPaint.textSize = base
        val tw = max(textPaint.measureText(labelLower[i]), textPaint.measureText(labelUpper[i]))
        return if (tw > maxW && tw > 0f) base * maxW / tw else base
    }

    // ================================================================ çizim

    override fun onDraw(canvas: Canvas) {
        val t = currentTheme
        canvas.drawColor(t.background)
        if (!geometryValid) return
        val half = keyGapPx / 2f
        val shifted = shiftState != ShiftState.OFF
        for (i in 0 until keyCount) {
            val key = keys[i]
            // Görünür kutu (visualPad dahil); dokunma alanı hücrenin tamamı olarak kalır.
            val l = visL[i]
            val tp = cellT[i] + half
            val r = visR[i]
            val b = cellB[i] - half
            if (r <= l || b <= tp) continue
            drawRect.set(l, tp, r, b)
            keyPaint.color = if (i == activeKey) {
                t.keyPressed
            } else {
                when (key.type) {
                    KeyType.CHAR, KeyType.SPACE -> t.keyBackground
                    KeyType.ENTER -> t.accentKeyBackground
                    else -> t.specialKeyBackground
                }
            }
            canvas.drawRoundRect(drawRect, cornerPx, cornerPx, keyPaint)

            val cx = (l + r) / 2f
            val hint = key.hint
            val hasHint = hint != null && hint.isNotEmpty()
            // İpucu varsa: ipucu üst ortada, ana etiket biraz aşağıda (SwiftKey yerleşimi).
            val cy = if (hasHint) {
                (tp + b) / 2f + min(hintLabelShift, (b - tp) * 0.12f)
            } else {
                (tp + b) / 2f
            }
            when (key.type) {
                KeyType.CHAR ->
                    drawLabel(canvas, if (shifted) labelUpper[i] else labelLower[i], cx, cy, labelSize[i], t.keyText)
                KeyType.SPACE ->
                    drawLabel(canvas, labelLower[i], cx, cy, labelSize[i], spaceTextColor)
                KeyType.MODE_SYMBOLS, KeyType.MODE_SYMBOLS_2, KeyType.MODE_ALPHA ->
                    drawLabel(canvas, labelLower[i], cx, cy, labelSize[i], t.keyText)
                KeyType.SHIFT -> drawShift(canvas, cx, cy, r - l, b - tp)
                KeyType.DELETE -> drawDelete(canvas, cx, cy, r - l, b - tp)
                KeyType.ENTER -> drawEnter(canvas, cx, cy, r - l, b - tp)
                KeyType.EMOJI -> drawEmoji(canvas, cx, cy, r - l, b - tp)
            }

            if (hint != null && hasHint) {
                canvas.drawText(hint, cx, tp + hintBaseline, hintPaint)
            }
        }
    }

    private fun drawLabel(canvas: Canvas, text: String, cx: Float, cy: Float, size: Float, color: Int) {
        if (text.isEmpty()) return
        textPaint.textSize = size
        textPaint.color = color
        canvas.drawText(text, cx, cy + baselineFactor * size, textPaint)
    }

    /**
     * Shift: SwiftKey ölçüsü 56×56 px (≈20.4 dp) mürekkep kutusu, görünür tuşun ortasında.
     * OFF içi boş çizgi ok; ON dolu ok; CAPS_LOCK dolu ok + alt çizgi. Ok üç halde aynı boyut/konum.
     */
    private fun drawShift(canvas: Canvas, cx: Float, cy: Float, visW: Float, visH: Float) {
        val icon = KeyboardViewIcons.shiftArrow
        if (!beginIcon(canvas, icon, true, cx, cy, iconBoxPx, iconBoxPx, visW, visH)) return
        iconPaint.style = if (shiftState == ShiftState.OFF) Paint.Style.STROKE else Paint.Style.FILL_AND_STROKE
        canvas.drawPath(icon.path, iconPaint)
        if (shiftState == ShiftState.CAPS_LOCK) {
            iconPaint.style = Paint.Style.FILL
            canvas.drawPath(KeyboardViewIcons.shiftUnderlinePath, iconPaint)
        }
        canvas.restore()
    }

    /** Sil: SwiftKey ölçüsü 56×44 px (≈20.4×16 dp) kontur gövde + ×, görünür tuşun ortasında. */
    private fun drawDelete(canvas: Canvas, cx: Float, cy: Float, visW: Float, visH: Float) {
        val icon = KeyboardViewIcons.delete
        if (!beginIcon(canvas, icon, true, cx, cy, deleteBoxWPx, deleteBoxHPx, visW, visH)) return
        iconPaint.style = Paint.Style.STROKE
        canvas.drawPath(icon.path, iconPaint)
        canvas.restore()
    }

    /** Enter: eyleme göre ikon, ~20.4 dp kare kutuya en-boy korunarak sığar. */
    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float, visW: Float, visH: Float) {
        val icon = when (enterAction) {
            EditorInfo.IME_ACTION_SEARCH -> KeyboardViewIcons.enterSearch
            EditorInfo.IME_ACTION_SEND -> KeyboardViewIcons.enterSend
            EditorInfo.IME_ACTION_GO -> KeyboardViewIcons.enterGo
            EditorInfo.IME_ACTION_NEXT -> KeyboardViewIcons.enterNext
            EditorInfo.IME_ACTION_PREVIOUS -> KeyboardViewIcons.enterPrevious
            EditorInfo.IME_ACTION_DONE -> KeyboardViewIcons.enterDone
            else -> KeyboardViewIcons.enterNewline
        }
        val stroked = enterAction != EditorInfo.IME_ACTION_SEND
        if (!beginIcon(canvas, icon, stroked, cx, cy, iconBoxPx, iconBoxPx, visW, visH)) return
        iconPaint.style = if (stroked) Paint.Style.STROKE else Paint.Style.FILL
        canvas.drawPath(icon.path, iconPaint)
        canvas.restore()
    }

    /** Emoji: ~20.4 dp kare kutuda yüz çemberi (kontur) + gözler (dolu). */
    private fun drawEmoji(canvas: Canvas, cx: Float, cy: Float, visW: Float, visH: Float) {
        val icon = KeyboardViewIcons.emoji
        if (!beginIcon(canvas, icon, true, cx, cy, iconBoxPx, iconBoxPx, visW, visH)) return
        iconPaint.style = Paint.Style.STROKE
        canvas.drawPath(icon.path, iconPaint)
        iconPaint.style = Paint.Style.FILL
        canvas.drawPath(KeyboardViewIcons.emojiEyesPath, iconPaint)
        canvas.restore()
    }

    /**
     * İkon dönüşümünü kurar: geometrik sınırların merkezi (cx, cy)'ye gelir, mürekkep kutusu
     * (çizgi kalınlığı dahil) [boxW]×[boxH] px'e en-boy korunarak sığar; çizgi kalınlığı px'te
     * sabittir (iconStrokePx). Görünür tuş kutudan küçükse ikon orantılı küçülür.
     * true dönerse canvas.save() yapılmıştır; çağıran çizer ve canvas.restore() çağırır.
     */
    private fun beginIcon(
        canvas: Canvas,
        icon: KeyIcon,
        stroked: Boolean,
        cx: Float,
        cy: Float,
        boxW: Float,
        boxH: Float,
        visW: Float,
        visH: Float
    ): Boolean {
        val gw = icon.maxX - icon.minX
        val gh = icon.maxY - icon.minY
        if (gw <= 0f || gh <= 0f || boxW <= 0f || boxH <= 0f) return false
        val fit = min(1f, min(visW * ICON_MAX_KEY_W / boxW, visH * ICON_MAX_KEY_H / boxH))
        if (fit <= 0f) return false
        val strokePx = iconStrokePx * fit
        val inset = if (stroked) strokePx else 0f
        val scale = min((boxW * fit - inset) / gw, (boxH * fit - inset) / gh)
        if (scale <= 0f) return false
        iconPaint.strokeWidth = strokePx / scale
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.translate(-(icon.minX + icon.maxX) / 2f, -(icon.minY + icon.maxY) / 2f)
        return true
    }

    // ============================================================= dokunma

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Düzgün akışta burada aktif parmak olmaz; kalmışsa (kayıp olay) sessizce temizle.
                if (activePointerId != INVALID_POINTER) resetTouch(dismissWindows = false)
                val idx = event.actionIndex
                onPointerDown(event.getPointerId(idx), event.getX(idx), event.getY(idx))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                val x = event.getX(idx)
                val y = event.getY(idx)
                // Rollover: önceki parmağın tuşu hemen işlenir, hiçbir vuruş kaybolmaz.
                if (activePointerId != INVALID_POINTER) commitActive()
                onPointerDown(id, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (activePointerId != INVALID_POINTER) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx >= 0) onActiveMove(event.getX(idx), event.getY(idx), releasing = false)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                if (event.getPointerId(idx) == activePointerId) {
                    onActiveMove(event.getX(idx), event.getY(idx), releasing = true)
                    commitActive()
                }
            }
            MotionEvent.ACTION_UP -> {
                val idx = event.actionIndex
                if (activePointerId != INVALID_POINTER) {
                    if (event.getPointerId(idx) == activePointerId) {
                        onActiveMove(event.getX(idx), event.getY(idx), releasing = true)
                        commitActive()
                    } else {
                        resetTouch(dismissWindows = false)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> resetTouch(dismissWindows = false)
        }
        return true
    }

    private fun onPointerDown(id: Int, x: Float, y: Float) {
        val k = hitTest(x, y)
        if (k < 0) return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        activePointerId = id
        activeKey = k
        lastX = x
        lastY = y
        longPressMode = false
        panelMoved = false
        invalidate()
        val key = keys[k]
        if (key.type == KeyType.DELETE) {
            hidePreview()
            repeatCount = 0
            timerHandler.postDelayed(repeatRunnable, REPEAT_START_DELAY_MS)
            // Silme basınca hemen bir kez; kalkınca ek çağrı yok.
            listener?.onKey(key)
        } else {
            if (key.longPress.isNotEmpty()) timerHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
            showPreview(k)
        }
    }

    /**
     * Aktif parmağın hareketi. Parmak başka bir tuşa kayarsa (histerezisle) basılı tuş değişir.
     * [releasing] = kalkış olayının son konumu: tuş değişebilir ama önizleme/zamanlayıcı kurulmaz.
     */
    private fun onActiveMove(x: Float, y: Float, releasing: Boolean) {
        lastX = x
        lastY = y
        if (longPressMode) {
            updateMoreKeysSelection(x, y)
            return
        }
        val k = activeKey
        if (k < 0 || k >= keyCount) return
        if (keys[k].type == KeyType.DELETE) return
        if (isNearKey(k, x, y)) return
        val nk = hitTest(x, y)
        if (nk < 0 || nk == k) return
        val nkey = keys[nk]
        // Kaydırarak silme tuşuna geçilmez (silme yalnız basınca çalışır).
        if (nkey.type == KeyType.DELETE) return
        timerHandler.removeCallbacks(longPressRunnable)
        activeKey = nk
        if (releasing) return
        if (nkey.longPress.isNotEmpty()) timerHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
        showPreview(nk)
        invalidate()
    }

    /** Aktif parmağın tuşunu işler. Durum ÖNCE temizlenir, dinleyici EN SON çağrılır (yeniden girişe güvenli). */
    private fun commitActive() {
        val k = activeKey
        val key = if (k in 0 until keyCount) keys[k] else null
        val text = if (longPressMode) moreKeysContent.selectedText() else null
        resetTouch(dismissWindows = false)
        val l = listener ?: return
        if (text != null) {
            if (text.isNotEmpty()) l.onText(text)
        } else if (key != null && key.type != KeyType.DELETE) {
            l.onKey(key)
        }
    }

    private fun onLongPressTimeout() {
        val k = activeKey
        if (k < 0 || k >= keyCount || activePointerId == INVALID_POINTER || longPressMode) return
        val key = keys[k]
        if (key.type == KeyType.DELETE || key.longPress.isEmpty()) return
        val options = if (shiftState != ShiftState.OFF) longPressUpper[k] else key.longPress
        // Panel gösterilemezse (pencere hazır değil) uzun basma normal dokunma gibi kalır.
        if (!showMoreKeys(k, options)) return
        longPressMode = true
        panelMoved = false
        panelStartX = lastX
        panelStartY = lastY
        hidePreview()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun updateMoreKeysSelection(x: Float, y: Float) {
        if (!panelMoved) {
            // Panel açıldığında ilk eleman seçili gelir; parmak gerçekten kayınca mutlak eşlemeye geçilir.
            if (abs(x - panelStartX) < panelSlopPx && abs(y - panelStartY) < panelSlopPx) return
            panelMoved = true
        }
        moreKeysContent.select(moreKeysContent.indexAt(x - panelLeft, y - panelTop))
    }

    private fun onRepeatTick() {
        val k = activeKey
        if (k < 0 || k >= keyCount || activePointerId == INVALID_POINTER) return
        val key = keys[k]
        if (key.type != KeyType.DELETE) return
        repeatCount++
        val interval = when {
            repeatCount < 10 -> 50L
            repeatCount < 30 -> 40L
            else -> 30L
        }
        timerHandler.postDelayed(repeatRunnable, interval)
        listener?.onKey(key)
    }

    /** En yakın tuş; ölü bölge yok (boşluklar ve kenarlar en yakın tuşa gider). */
    private fun hitTest(x: Float, y: Float): Int {
        if (!geometryValid || rowCount == 0 || keyCount == 0) return -1
        // Sıra yükseklikleri farklı olabilir (heightRatio): üstten tarayarak bul; üstü/altı taşanlar
        // ilk/son sıraya gider (en fazla birkaç sıra, tahsis yok).
        var r = 0
        while (r < rowCount - 1 && y >= rowTop[r + 1]) r++
        if (rowStart[r] == rowStart[r + 1]) {
            r = nearestNonEmptyRow(r)
            if (r < 0) return -1
        }
        val start = rowStart[r]
        val end = rowStart[r + 1]
        if (x < cellL[start]) return start
        for (i in start until end) {
            if (x < cellR[i]) return i
        }
        return end - 1
    }

    private fun nearestNonEmptyRow(r: Int): Int {
        for (d in 1 until rowCount) {
            val up = r - d
            if (up >= 0 && rowStart[up] < rowStart[up + 1]) return up
            val down = r + d
            if (down < rowCount && rowStart[down] < rowStart[down + 1]) return down
        }
        return -1
    }

    /** Kayma histerezisi: parmak tuş hücresinin biraz dışına taşmadıkça tuş değişmez. */
    private fun isNearKey(k: Int, x: Float, y: Float): Boolean {
        val s = slideSlopPx
        return x >= cellL[k] - s && x < cellR[k] + s && y >= cellT[k] - s && y < cellB[k] + s
    }

    /** Tüm dokunma durumunu ve zamanlayıcıları sıfırlar; dinleyici çağrılmaz. */
    private fun resetTouch(dismissWindows: Boolean) {
        timerHandler.removeCallbacksAndMessages(null)
        activePointerId = INVALID_POINTER
        activeKey = -1
        longPressMode = false
        panelMoved = false
        repeatCount = 0
        if (dismissWindows) {
            dismissPreview()
        } else {
            hidePreview()
        }
        dismissMoreKeys()
        invalidate()
    }

    // ============================================================= popup'lar

    private fun createPopup(content: View): PopupWindow {
        val popup = PopupWindow(content, 0, 0, false)
        popup.setBackgroundDrawable(null)
        popup.setTouchable(false)
        popup.setFocusable(false)
        popup.setOutsideTouchable(false)
        // IME penceresinin üst kenarının dışına (uygulamanın üstüne) taşabilsin.
        popup.setClippingEnabled(false)
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED)
        popup.setAnimationStyle(0)
        return popup
    }

    private fun canShowPopup(): Boolean =
        isAttachedToWindow && windowToken != null && windowVisibility == View.VISIBLE && width > 0

    /** CHAR tuşunun üstünde büyütülmüş önizleme balonu. */
    private fun showPreview(k: Int) {
        // Önizleme kapalıysa (varsayılan) hiçbir şey yapılmaz; pencere zaten yaratılmadı.
        val content = previewContent ?: return
        val popup = previewPopup ?: return
        val key = keys[k]
        if (key.type != KeyType.CHAR) {
            hidePreview()
            return
        }
        val label = if (shiftState != ShiftState.OFF) labelUpper[k] else labelLower[k]
        if (label.isEmpty() || !canShowPopup()) {
            hidePreview()
            return
        }
        val half = keyGapPx / 2f
        val vl = visL[k]
        val vr = visR[k]
        val vt = cellT[k] + half
        val vb = cellB[k] - half
        val vw = vr - vl
        val vh = vb - vt
        val pw = min(max(vw * 1.25f, dp(40f)), width.toFloat()).roundToInt()
        val ph = max(vh * 1.45f, dp(56f)).roundToInt()
        val maxLeft = (width - pw).coerceAtLeast(0)
        val px = ((vl + vr) / 2f - pw / 2f).roundToInt().coerceIn(0, maxLeft)
        val py = (vt - ph - dp(2f)).roundToInt()

        getLocationInWindow(windowLoc)
        val wx = windowLoc[0] + px
        val wy = windowLoc[1] + py
        content.setLabel(label)
        if (content.visibility != View.VISIBLE) content.visibility = View.VISIBLE
        try {
            if (popup.isShowing) {
                popup.update(wx, wy, pw, ph)
            } else {
                popup.width = pw
                popup.height = ph
                popup.showAtLocation(this, Gravity.NO_GRAVITY, wx, wy)
            }
            previewOutside = py < 0
            // Balon en fazla PREVIEW_MAX_VISIBLE_MS görünsün; yeni tuşta süre baştan başlar.
            timerHandler.removeCallbacks(previewHideRunnable)
            timerHandler.postDelayed(previewHideRunnable, PREVIEW_MAX_VISIBLE_MS)
        } catch (e: RuntimeException) {
            // Pencere token'ı geçersiz / pencere kapanıyor: önizlemesiz devam. PopupWindow
            // addView'dan ÖNCE isShowing=true yaptığı için tam dismiss ile durumu sıfırla.
            dismissPreview()
        }
    }

    /**
     * Önizlemeyi gizler. Pencere, tuşlar arası her vuruşta yeniden eklenip kaldırılmasın
     * (addView/removeView + yüzey yaratma maliyeti) diye açık kalır, içerik INVISIBLE olur.
     * Ancak son konumu klavyenin üstüne (uygulamanın üzerine) taşıyorsa pencere klavyenin
     * kendi alanına park edilir: Android 12+ başka UID'li, dokunulamaz bir pencerenin altına
     * gelen dokunuşları engelleyebildiği için boş pencere uygulamanın üstünde bırakılmaz.
     */
    private fun hidePreview() {
        timerHandler.removeCallbacks(previewHideRunnable)
        val content = previewContent ?: return
        val popup = previewPopup ?: return
        if (content.visibility == View.VISIBLE) content.visibility = View.INVISIBLE
        if (!previewOutside) return
        previewOutside = false
        if (!popup.isShowing) return
        if (!isAttachedToWindow || windowToken == null) {
            dismissPreview()
            return
        }
        getLocationInWindow(windowLoc)
        try {
            popup.update(windowLoc[0], windowLoc[1], -1, -1)
        } catch (e: RuntimeException) {
            dismissPreview()
        }
    }

    private fun dismissPreview() {
        timerHandler.removeCallbacks(previewHideRunnable)
        previewOutside = false
        val content = previewContent ?: return
        val popup = previewPopup ?: return
        if (content.visibility == View.VISIBLE) content.visibility = View.INVISIBLE
        try {
            if (popup.isShowing) popup.dismiss()
        } catch (e: RuntimeException) {
            // Üst pencere zaten kaldırılmış olabilir.
        }
    }

    private fun dismissMoreKeys() {
        try {
            if (moreKeysPopup.isShowing) moreKeysPopup.dismiss()
        } catch (e: RuntimeException) {
            // Üst pencere zaten kaldırılmış olabilir.
        }
    }

    /**
     * Uzun basma panelini tuşun üstünde açar. İlk seçenek tuşun tam üstüne gelir;
     * tuş klavyenin sağ yarısındaysa seçenekler sağdan sola dizilir.
     * @return panel gösterilebildiyse true
     */
    private fun showMoreKeys(k: Int, options: List<String>): Boolean {
        val n = options.size
        if (n == 0 || !canShowPopup()) return false
        val pad = dp(4f)
        val cellW = max(unitPx, dp(34f))
        val cellH = max((cellB[k] - cellT[k]) * 0.9f, dp(40f))
        val fit = max(1, ((width - 2f * pad) / cellW).toInt())
        val maxCols = min(MAX_PANEL_COLUMNS, fit)
        val rows = (n + maxCols - 1) / maxCols
        val cols = (n + rows - 1) / rows
        val keyCx = (visL[k] + visR[k]) / 2f
        val reversed = keyCx > width / 2f

        moreKeysContent.setup(options, cols, rows, cellW, cellH, pad, reversed)
        val pw = moreKeysContent.panelWidth
        val ph = moreKeysContent.panelHeight
        val idealLeft = if (reversed) {
            keyCx - pad - (cols - 0.5f) * cellW
        } else {
            keyCx - pad - 0.5f * cellW
        }
        val maxLeft = (width - pw).coerceAtLeast(0)
        val left = idealLeft.roundToInt().coerceIn(0, maxLeft)
        val keyTop = cellT[k] + keyGapPx / 2f
        val top = (keyTop - ph).roundToInt()
        panelLeft = left.toFloat()
        panelTop = top.toFloat()

        getLocationInWindow(windowLoc)
        val wx = windowLoc[0] + left
        val wy = windowLoc[1] + top
        return try {
            if (moreKeysPopup.isShowing) {
                moreKeysPopup.update(wx, wy, pw, ph)
            } else {
                moreKeysPopup.width = pw
                moreKeysPopup.height = ph
                moreKeysPopup.showAtLocation(this, Gravity.NO_GRAVITY, wx, wy)
            }
            true
        } catch (e: RuntimeException) {
            // PopupWindow addView'dan ÖNCE isShowing=true yapar; takılı kalmasın.
            dismissMoreKeys()
            false
        }
    }

    // ============================================================ yaşam döngüsü

    override fun onDetachedFromWindow() {
        resetTouch(dismissWindows = true)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) resetTouch(dismissWindows = true)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) resetTouch(dismissWindows = true)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private companion object {
        const val INVALID_POINTER = -1
        const val LONG_PRESS_MS = 300L
        const val REPEAT_START_DELAY_MS = 400L
        const val MAX_PANEL_COLUMNS = 8
        const val REF_TEXT_SIZE = 100f

        /**
         * Tuşa basınca üstte çıkan önizleme balonu. AÇIK, ama çok kısa süre görünür: en fazla
         * [PREVIEW_MAX_VISIBLE_MS], parmak kalkınca daha erken gizlenir. false olsaydı
         * PopupWindow hiç yaratılmazdı; uzun basma paneli etkilenmez.
         */
        const val KEY_PREVIEW_ENABLED = true

        /** Önizleme balonunun parmak basılı olsa bile ekranda kalabileceği en uzun süre (ms). */
        const val PREVIEW_MAX_VISIBLE_MS = 80L

        /** Yatay kenar boşluğu (her yan): 12 birimlik satır = genişlik − 2 × bu. */
        const val EDGE_H_DP = 1f
        const val EDGE_TOP_DP = 1f
        const val EDGE_BOTTOM_DP = 1f

        /** SPACE etiketi: boyut ve keyText üzerine uygulanan alfa (SwiftKey ölçümü). */
        const val SPACE_TEXT_SP = 10f
        const val SPACE_TEXT_ALPHA = 0.58f

        /** İkon mürekkep kutuları (çizgi dahil), dp. 20.4 dp = 56 px, 16 dp = 44 px @2.75. */
        const val ICON_BOX_DP = 20.4f
        const val DELETE_ICON_W_DP = 20.4f
        const val DELETE_ICON_H_DP = 16f

        /** İkon çizgi kalınlığı (dp); shift OFF konturu ≈ kutunun %25'i dolu piksel hedefi. */
        const val ICON_STROKE_DP = 1.8f

        /** İkon kutusu görünür tuşun en çok bu oranına sığar (küçük tuşlarda orantılı küçülür). */
        const val ICON_MAX_KEY_W = 0.8f
        const val ICON_MAX_KEY_H = 0.7f

        @Suppress("DEPRECATION")
        val TR: Locale = Locale("tr", "TR")
    }
}
