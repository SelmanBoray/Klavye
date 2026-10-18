package com.selman.klavye.view

import android.graphics.Path

/**
 * Bir ikon yolu ve onun geometrik sınırları (yol birimi; çizgi kalınlığı hariç).
 * KeyboardView ikonu bu sınırlara göre hedef piksel kutusuna sığdırır ve sınırların
 * merkezini görünür tuş kutusunun merkezine koyar; böylece mürekkep kutusu ölçülebilir
 * ve kesin olur (çizgi kalınlığı px cinsinden sabit tutulur).
 */
internal class KeyIcon(
    val path: Path,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float
)

/**
 * KeyboardView'in özel tuş ikonları. Hepsi bir kez (nesne ilk yüklendiğinde) kurulur;
 * çizimde Canvas dönüşümüyle ölçeklenir, onDraw'da Path/nesne tahsisi olmaz.
 *
 * Sınırlar elle verilir (Path.computeBounds yerine) ve yol koordinatlarıyla birebir aynıdır.
 */
internal object KeyboardViewIcons {

    /**
     * Shift oku (dış hat), geometrik kutu 20×20 (2..22): mürekkep kutusu kare çıkar.
     * OFF: STROKE (içi boş), ON / CAPS_LOCK: FILL_AND_STROKE (dolu, aynı boyut).
     */
    val shiftArrow = KeyIcon(
        Path().apply {
            moveTo(12f, 2f)
            lineTo(22f, 12f)
            lineTo(16.5f, 12f)
            lineTo(16.5f, 22f)
            lineTo(7.5f, 22f)
            lineTo(7.5f, 12f)
            lineTo(2f, 12f)
            close()
        },
        2f, 2f, 22f, 22f
    )

    /**
     * Caps Lock alt çizgisi (FILL). [shiftArrow] ile AYNI dönüşümle çizilir (okun altında);
     * okun konumu üç halde de sabit kalsın diye merkezleme yalnız oka göre yapılır.
     */
    val shiftUnderlinePath: Path = Path().apply {
        addRect(7.5f, 25f, 16.5f, 27f, Path.Direction.CW)
    }

    /**
     * Geri silme: sola sivri gövde + içinde çarpı (STROKE). Geometrik kutu 20×15.3
     * (en-boy 1.307): ~1.8 dp çizgiyle 56×44 px mürekkep kutusuna oturur.
     */
    val delete = KeyIcon(
        Path().apply {
            moveTo(2f, 12f)
            lineTo(8f, 4.35f)
            lineTo(22f, 4.35f)
            lineTo(22f, 19.65f)
            lineTo(8f, 19.65f)
            close()
            moveTo(12.3f, 9f)
            lineTo(18.3f, 15f)
            moveTo(18.3f, 9f)
            lineTo(12.3f, 15f)
        },
        2f, 4.35f, 22f, 19.65f
    )

    /** Yeni satır (↵) (STROKE). */
    val enterNewline = KeyIcon(
        Path().apply {
            moveTo(19.5f, 5.5f)
            lineTo(19.5f, 12.5f)
            lineTo(5.5f, 12.5f)
            moveTo(10f, 8f)
            lineTo(5.5f, 12.5f)
            lineTo(10f, 17f)
        },
        5.5f, 5.5f, 19.5f, 17f
    )

    /** Ara: büyüteç (STROKE). */
    val enterSearch = KeyIcon(
        Path().apply {
            addCircle(10.2f, 10.2f, 6f, Path.Direction.CW)
            moveTo(14.6f, 14.6f)
            lineTo(20.5f, 20.5f)
        },
        4.2f, 4.2f, 20.5f, 20.5f
    )

    /** Gönder: kâğıt uçak (FILL). */
    val enterSend = KeyIcon(
        Path().apply {
            moveTo(3f, 20.5f)
            lineTo(21.5f, 12f)
            lineTo(3f, 3.5f)
            lineTo(3f, 10.2f)
            lineTo(15.5f, 12f)
            lineTo(3f, 13.8f)
            close()
        },
        3f, 3.5f, 21.5f, 20.5f
    )

    /** Git: sağ ok (STROKE). */
    val enterGo = KeyIcon(
        Path().apply {
            moveTo(4f, 12f)
            lineTo(19.5f, 12f)
            moveTo(13.5f, 6f)
            lineTo(19.5f, 12f)
            lineTo(13.5f, 18f)
        },
        4f, 6f, 19.5f, 18f
    )

    /** İleri: sağ ok + dikey çizgi (→|) (STROKE). */
    val enterNext = KeyIcon(
        Path().apply {
            moveTo(3.5f, 12f)
            lineTo(16.5f, 12f)
            moveTo(11f, 6.5f)
            lineTo(16.5f, 12f)
            lineTo(11f, 17.5f)
            moveTo(20.5f, 6f)
            lineTo(20.5f, 18f)
        },
        3.5f, 6f, 20.5f, 18f
    )

    /** Geri: sol ok + dikey çizgi (|←) (STROKE). */
    val enterPrevious = KeyIcon(
        Path().apply {
            moveTo(20.5f, 12f)
            lineTo(7.5f, 12f)
            moveTo(13f, 6.5f)
            lineTo(7.5f, 12f)
            lineTo(13f, 17.5f)
            moveTo(3.5f, 6f)
            lineTo(3.5f, 18f)
        },
        3.5f, 6f, 20.5f, 18f
    )

    /** Bitti: onay işareti (STROKE). */
    val enterDone = KeyIcon(
        Path().apply {
            moveTo(4.5f, 12.5f)
            lineTo(9.5f, 17.5f)
            lineTo(19.5f, 7.5f)
        },
        4.5f, 7.5f, 19.5f, 17.5f
    )

    /** Emoji: yüz çemberi + gülümseme (STROKE); sınırlar çembere göre (3..21). */
    val emoji = KeyIcon(
        Path().apply {
            addCircle(12f, 12f, 9f, Path.Direction.CW)
            addArc(7.2f, 7.5f, 16.8f, 16.8f, 25f, 130f)
        },
        3f, 3f, 21f, 21f
    )

    /** Emoji: gözler (FILL). [emoji] ile AYNI dönüşümle çizilir. */
    val emojiEyesPath: Path = Path().apply {
        addCircle(9f, 9.8f, 1.25f, Path.Direction.CW)
        addCircle(15f, 9.8f, 1.25f, Path.Direction.CW)
    }
}
