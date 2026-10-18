package com.selman.klavye.suggest

/**
 * Kelime önerisi sağlayıcısı.
 *
 * Uygulamalar tek thread'den kullanılır; thread güvenliği gerekmez.
 */
interface Suggester {

    /**
     * Yazılmakta olan [composing] önekine (ve varsa bir önceki kelime [previousWord]'e) göre
     * en çok [limit] öneri döndürür. [composing] boşsa yalnız öğrenilmiş "sonraki kelime"
     * tahminleri döner. Sonuç tekrarsızdır.
     */
    fun suggest(composing: String, previousWord: String?, limit: Int = 3): List<String>

    /** Kullanıcının [word] kelimesini (önünde [previousWord] varken) yazıp bitirdiğini bildirir. */
    fun onWordCommitted(word: String, previousWord: String?)

    /** Bekleyen kullanıcı verisini kalıcı depoya yazar (uygulamaya göre no-op olabilir). */
    fun flush()
}
