package com.selman.klavye.ui

/**
 * Klavye tema tanımı. Renkler ARGB Int, boyutlar dp/sp cinsindendir.
 *
 * @property background klavye zemini
 * @property keyBackground harf tuşu arka planı
 * @property keyPressed basılı tuş arka planı
 * @property specialKeyBackground shift/sil/123/emoji gibi özel tuşların arka planı
 * @property accentKeyBackground enter tuşu (vurgu) arka planı
 * @property keyText tuş yazısı rengi
 * @property hintText köşe ipucu (rakam) yazısı rengi
 * @property stripBackground öneri çubuğu arka planı
 * @property stripText öneri çubuğu yazısı
 * @property divider öneri ayırıcılarının rengi
 * @property popupBackground tuş önizleme ve uzun basma popup'ı arka planı
 * @property popupText tuş önizleme ve uzun basma popup'ı yazısı
 * @property keyCornerRadiusDp tuş köşe yarıçapı (dp)
 * @property keyGapDp tuşlar arası boşluk (dp)
 * @property keyTextSizeSp tuş yazı boyutu (sp)
 * @property hintTextSizeSp ipucu yazı boyutu (sp)
 */
data class KlavyeTheme(
    val background: Int,
    val keyBackground: Int,
    val keyPressed: Int,
    val specialKeyBackground: Int,
    val accentKeyBackground: Int,
    val keyText: Int,
    val hintText: Int,
    val stripBackground: Int,
    val stripText: Int,
    val divider: Int,
    val popupBackground: Int,
    val popupText: Int,
    val keyCornerRadiusDp: Float,
    val keyGapDp: Float,
    val keyTextSizeSp: Float,
    val hintTextSizeSp: Float
)

object Themes {
    /** SwiftKey koyu temasından telefonda piksel ölçümüyle alınan değerler (440 dpi, 1 dp = 2.75 px). */
    val DARK: KlavyeTheme = KlavyeTheme(
        background = 0xFF141414.toInt(), // ölçüldü, RE ile doğrulandı
        keyBackground = 0xFF232323.toInt(), // ölçüldü, RE ile doğrulandı
        keyPressed = 0xFF212121.toInt(), // RE ile doğrulandı
        specialKeyBackground = 0xFF232323.toInt(), // ölçüldü (harf tuşuyla aynı)
        accentKeyBackground = 0xFF232323.toInt(), // ölçüldü (vurgu yok)
        keyText = 0xFFFFFFFF.toInt(), // ölçüldü
        hintText = 0xFF777777.toInt(), // ölçüldü, RE ile doğrulandı
        stripBackground = 0xFF141414.toInt(), // ölçüldü
        stripText = 0xFFFFFFFF.toInt(), // ölçüldü (yan öneriler #939393, alfa ile üretilecek)
        divider = 0xFF282828.toInt(), // ölçüldü
        popupBackground = 0xFF3F3F3F.toInt(), // RE ile doğrulandı
        popupText = 0xFFFFFFFF.toInt(), // RE ile doğrulandı
        keyCornerRadiusDp = 7.3f, // ölçüldü (~20 px)
        keyGapDp = 6.5f, // ölçüldü (18 px)
        keyTextSizeSp = 22f, // ölçüldü
        hintTextSizeSp = 10f // tahmin
    )
}
