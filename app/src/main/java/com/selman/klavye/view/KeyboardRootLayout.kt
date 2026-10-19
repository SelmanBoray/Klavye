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
 * Öneri şeridi yalnız yazarken görünür (~41,5 dp, aksi halde GONE); bu yüzden en üst sıranın
 * üstünde balon için yer az olabilir ya da hiç olmayabilir. Taşma küçükse (balon yüksekliğinin %40'ı) balon içeri kaydırılır,
 * büyükse tuşu örtmemesi için hiç çizilmez. Kökün geçersiz kılınması yalnız kökün kendi görüntü
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
     * korunarak yatayda [0, genişlik] içine kaydırılır. Dikeyde üst taşması balon yüksekliğinin
     * [MAX_TOP_OVERFLOW_RATIO] oranını aşmıyorsa üst 0'a çekilir; aşıyorsa balon sığmıyor demektir,
     * gösterilmez ([hideKeyPreview] çağrılır).
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
        if (t < 0f) {
            // Üstte yeterli yer yok (şerit gizli ya da dar): küçük taşma aşağı kaydırılır,
            // büyük taşmada balon tuşu ve alttaki sırayı örteceği için hiç gösterilmez.
            if (-t > ph * MAX_TOP_OVERFLOW_RATIO) {
                hideKeyPreview()
                return
            }
            t = 0f
        }
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

    companion object {
        /**
         * Balonun üstten taşabileceği en büyük pay (balon yüksekliğine oranla). Bunun içindeki
         * taşma balonu aşağı kaydırarak giderilir (tuşun üstü biraz örtülür); fazlası için
         * balon gösterilmez.
         */
        private const val MAX_TOP_OVERFLOW_RATIO = 0.4f
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (previewVisible) {
            previewPainter.draw(canvas, previewLeft, previewTop, previewRight, previewBottom, previewLabel)
        }
    }
}
