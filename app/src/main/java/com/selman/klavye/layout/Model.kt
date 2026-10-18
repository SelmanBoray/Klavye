package com.selman.klavye.layout

/** Tuşun işlevini belirten tür. */
enum class KeyType {
    /** Karakter yazan normal tuş. */
    CHAR,

    /** Shift (büyük/küçük harf) tuşu. */
    SHIFT,

    /** Geri silme tuşu. */
    DELETE,

    /** Enter / eylem tuşu. */
    ENTER,

    /** Boşluk çubuğu. */
    SPACE,

    /** Birinci sembol düzenine geçiş tuşu. */
    MODE_SYMBOLS,

    /** İkinci sembol düzenine geçiş tuşu. */
    MODE_SYMBOLS_2,

    /** Harf düzenine dönüş tuşu. */
    MODE_ALPHA,

    /** Emoji paneli tuşu. */
    EMOJI
}

/** Shift durumu: kapalı, tek harflik açık, kalıcı büyük harf. */
enum class ShiftState {
    /** Shift kapalı. */
    OFF,

    /** Shift bir sonraki harf için açık. */
    ON,

    /** Caps Lock: kalıcı büyük harf. */
    CAPS_LOCK
}

/**
 * Tek bir klavye tuşu.
 *
 * @property type tuşun işlev türü.
 * @property label tuşta görünen metin (CHAR için küçük harf; görünüm shift açıkken tr-TR ile büyütür; özel tuşlarda ör. "123", "abc").
 * @property output CHAR tuşunda yazılacak metin.
 * @property width göreli genişlik; standart harf tuşu = 1f.
 * @property longPress uzun basma popup seçenekleri; ilk eleman varsayılan seçimdir.
 * @property hint tuşun köşesindeki küçük etiket (ör. üst sıradaki rakam).
 * @property visualPadLeft görünür tuş kutusunun hücre içinde soldan ek olarak ne kadar daraltılacağı (Key.width ile aynı birim). Yalnız çizimi etkiler; dokunma alanı hücrenin tamamıdır.
 * @property visualPadRight görünür tuş kutusunun hücre içinde sağdan ek olarak ne kadar daraltılacağı (Key.width ile aynı birim). Yalnız çizimi etkiler; dokunma alanı hücrenin tamamıdır.
 */
data class Key(
    val type: KeyType,
    val label: String,
    val output: String = label,
    val width: Float = 1f,
    val longPress: List<String> = emptyList(),
    val hint: String? = null,
    val visualPadLeft: Float = 0f,
    val visualPadRight: Float = 0f
)

/**
 * Klavyedeki bir tuş sırası.
 *
 * @property keys sıradaki tuşlar.
 * @property leftGap sıranın sol boşluğu, Key.width ile aynı birimde.
 * @property rightGap sıranın sağ boşluğu, Key.width ile aynı birimde.
 * @property heightRatio sıranın yüksekliği = KeyboardLayout.rowHeightDp × heightRatio (rowHeightDp, tuşlar arası dikey boşluk dahil sıra adımıdır).
 */
data class KeyRow(
    val keys: List<Key>,
    val leftGap: Float = 0f,
    val rightGap: Float = 0f,
    val heightRatio: Float = 1f
)

/**
 * Tam bir klavye düzeni.
 *
 * @property id düzenin benzersiz kimliği.
 * @property rows düzenin sıraları, yukarıdan aşağıya.
 * @property rowHeightDp bir sıranın dp cinsinden dikey adımı (görünür tuş yüksekliği + tuşlar arası dikey boşluk); heightRatio=1 olan sıralar için.
 */
data class KeyboardLayout(
    val id: String,
    val rows: List<KeyRow>,
    val rowHeightDp: Float = 54f
)
