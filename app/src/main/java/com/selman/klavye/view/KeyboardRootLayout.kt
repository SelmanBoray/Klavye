package com.selman.klavye.view

import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout

/**
 * IME'nin kök görünümü (dikey LinearLayout: üstte öneri şeridi, altında klavye çerçevesi).
 *
 * Tuş önizleme balonu ayrı bir pencerede değil, burada [dispatchDraw] içinde tüm çocukların
 * üstüne çizilir. Ayrı pencere (PopupWindow) her tuşta taşındığında WindowManager yeni konumu
 * yüzeye yeni harf çizilmeden önce uygulayabiliyor, bir kare ya da daha uzun süre önceki harfin
 * tamponu yeni tuşun üstünde görünüyordu. Burada konum ve etiket aynı görüntü listesine aynı
 * karede kaydedilir; konumla etiket birbirinden ayrı güncellenemez.
 *
 * Şerit klavyenin üstünde olduğu için en üst sıranın balonu da bu görünümün içine sığar; sığmazsa
 * dikdörtgen boyutu korunarak içeri kaydırılır. Kökün geçersiz kılınması yalnız kökün kendi görüntü
 * listesini yeniden kaydeder, çocukların RenderNode'ları yeniden kullanılır. dispatchDraw tahsis yapmaz.
 */
class KeyboardRootLayout(context: Context) : LinearLayout(context) {

    private val previewPainter = KeyPreviewPainter()
    private val sourceLoc = IntArray(2)
    private val selfLoc = IntArray(2)

    private var previewVisible = false
    private var previewLeft = 0f
    private var previewTop = 0f
    private var previewRight = 0f
    private var previewBottom = 0f
    private var previewLabel = ""

    /** Balonun renk ve boyutlarını ayarlar (tema değişiminde). */
    fun configurePreview(background: Int, textColor: Int, textSizePx: Float, cornerRadiusPx: Float) {
        previewPainter.configure(background, textColor, textSizePx, cornerRadiusPx)
        if (previewVisible) invalidate()
    }

    /**
     * Balonu gösterir ya da yeni tuşa/etikete taşır. Dikdörtgen [source]'un yerel koordinatındadır;
     * iki görünümün pencere konumu farkıyla bu görünümün koordinatına çevrilir, sonra boyutu
     * korunarak sınırların içine kaydırılır (üst ≥ 0, yatayda [0, genişlik]).
     */
    fun showKeyPreview(source: View, left: Float, top: Float, right: Float, bottom: Float, label: String) {
        val w = width.toFloat()
        if (!isAttachedToWindow || !source.isAttachedToWindow || w <= 0f || height <= 0) {
            hideKeyPreview()
            return
        }
        source.getLocationInWindow(sourceLoc)
        getLocationInWindow(selfLoc)
        val pw = right - left
        val ph = bottom - top
        var l = left + (sourceLoc[0] - selfLoc[0])
        var t = top + (sourceLoc[1] - selfLoc[1])
        // Önce sağ kenar, sonra sol: balon görünümden genişse sola yaslanır.
        if (l + pw > w) l = w - pw
        if (l < 0f) l = 0f
        if (t < 0f) t = 0f
        previewLeft = l
        previewTop = t
        previewRight = l + pw
        previewBottom = t + ph
        previewLabel = label
        previewVisible = true
        invalidate()
    }

    /** Balonu gizler; zaten gizliyse hiçbir şey yapmaz. */
    fun hideKeyPreview() {
        if (!previewVisible) return
        previewVisible = false
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (previewVisible) {
            previewPainter.draw(canvas, previewLeft, previewTop, previewRight, previewBottom, previewLabel)
        }
    }
}
