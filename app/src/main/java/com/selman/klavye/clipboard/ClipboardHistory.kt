package com.selman.klavye.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

data class ClipItem(val text: String, val timestamp: Long, val pinned: Boolean = false)

/**
 * Pano geçmişi. Tüm metotlar ana thread'den çağrılır.
 *
 * Sıralama: sabitlenmişler üstte, her grubun içinde en yeni önce.
 * Kalıcılık: yalnız sabitlenmişler filesDir/clip_pinned.bin dosyasına yazılır.
 */
class ClipboardHistory(context: Context, maxItems: Int = 20) {

    private val appContext: Context = context.applicationContext ?: context
    private val maxUnpinned: Int = if (maxItems < 1) 1 else maxItems
    private val clipboard: ClipboardManager? =
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val file = File(appContext.filesDir, FILE_NAME)

    private val list = ArrayList<ClipItem>()
    private var started = false
    private var loadRequested = false

    // Arka plan yazımlarının sırasını korumak için.
    private val writeLock = Any()
    private var writeSeq = 0L
    private var lastWrittenSeq = 0L

    var onChanged: (() -> Unit)? = null

    val items: List<ClipItem>
        get() {
            pruneExpired()
            return ArrayList(list)
        }

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { captureCurrent() }

    fun start() {
        if (started) return
        started = true
        try {
            clipboard?.addPrimaryClipChangedListener(listener)
        } catch (_: Throwable) {
        }
        if (!loadRequested) {
            loadRequested = true
            loadPinnedAsync()
        }
        captureCurrent()
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            clipboard?.removePrimaryClipChangedListener(listener)
        } catch (_: Throwable) {
        }
    }

    fun remove(item: ClipItem) {
        val idx = list.indexOfFirst { it.text == item.text }
        if (idx < 0) return
        val wasPinned = list[idx].pinned
        list.removeAt(idx)
        if (wasPinned) persistPinnedAsync()
        notifyChanged()
    }

    fun togglePin(item: ClipItem) {
        val idx = list.indexOfFirst { it.text == item.text }
        if (idx < 0) return
        val cur = list[idx]
        list[idx] = cur.copy(pinned = !cur.pinned)
        sortList()
        trimOverflow()
        persistPinnedAsync()
        notifyChanged()
    }

    fun clearUnpinned() {
        val before = list.size
        list.removeAll { !it.pinned }
        if (list.size != before) notifyChanged()
    }

    // ---- yakalama ----

    private fun captureCurrent() {
        val cm = clipboard ?: return
        var text: String? = null
        try {
            if (!cm.hasPrimaryClip()) return
            val desc = cm.primaryClipDescription
            if (isSensitive(desc)) return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount < 1) return
            // coerceToText() URI öğelerinde ContentProvider IPC'si yapar; yalnız düz text alanı kullanılır.
            text = clip.getItemAt(0).text?.toString()
        } catch (_: Throwable) {
            return
        }
        if (text.isNullOrEmpty() || text.isBlank() || text.length > MAX_LEN) return
        addText(text)
    }

    private fun isSensitive(desc: android.content.ClipDescription?): Boolean {
        if (desc == null) return false
        return try {
            val extras = desc.extras ?: return false
            extras.getBoolean(SENSITIVE_KEY, false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun addText(text: String) {
        val now = System.currentTimeMillis()
        pruneExpired(now)
        val idx = list.indexOfFirst { it.text == text }
        if (idx >= 0) {
            val cur = list[idx]
            list[idx] = cur.copy(timestamp = now)
        } else {
            list.add(ClipItem(text, now, false))
        }
        sortList()
        trimOverflow()
        if (idx >= 0 && list.firstOrNull { it.text == text }?.pinned == true) persistPinnedAsync()
        notifyChanged()
    }

    // ---- bakım ----

    private fun sortList() {
        list.sortWith(compareByDescending<ClipItem> { it.pinned }.thenByDescending { it.timestamp })
    }

    private fun trimOverflow() {
        var unpinned = 0
        val it = list.iterator()
        // Liste sıralı: sabitlenmişler üstte, sonra en yeniler; fazlalık sondan düşer.
        while (it.hasNext()) {
            val c = it.next()
            if (c.pinned) continue
            unpinned++
            if (unpinned > maxUnpinned) it.remove()
        }
    }

    private fun pruneExpired(now: Long = System.currentTimeMillis()) {
        if (list.isEmpty()) return
        list.removeAll { !it.pinned && now - it.timestamp > TTL_MS }
    }

    private fun notifyChanged() {
        onChanged?.invoke()
    }

    // ---- kalıcılık ----

    private fun persistPinnedAsync() {
        val snapshot = list.filter { it.pinned }
        val seq: Long
        synchronized(writeLock) { seq = ++writeSeq }
        val f = file
        Thread({
            try {
                synchronized(writeLock) {
                    if (seq < lastWrittenSeq) return@synchronized
                    writePinned(f, snapshot)
                    lastWrittenSeq = seq
                }
            } catch (_: Throwable) {
            }
        }, "clip-save").start()
    }

    private fun writePinned(f: File, pinned: List<ClipItem>) {
        val af = AtomicFile(f)
        val bos = ByteArrayOutputStream()
        val out = DataOutputStream(bos)
        out.writeInt(MAGIC)
        out.writeInt(pinned.size)
        for (c in pinned) {
            val b = c.text.toByteArray(Charsets.UTF_8)
            out.writeLong(c.timestamp)
            out.writeInt(b.size)
            out.write(b)
        }
        out.flush()
        val fos = af.startWrite()
        try {
            fos.write(bos.toByteArray())
            af.finishWrite(fos)
        } catch (t: Throwable) {
            af.failWrite(fos)
            throw t
        }
    }

    private fun loadPinnedAsync() {
        val f = file
        Thread({
            val loaded = try {
                readPinned(f)
            } catch (_: Throwable) {
                emptyList<ClipItem>()
            }
            if (loaded.isNotEmpty()) {
                mainHandler.post { mergeLoaded(loaded) }
            }
        }, "clip-load").start()
    }

    private fun readPinned(f: File): List<ClipItem> {
        val af = AtomicFile(f)
        if (!f.exists() && !File(f.path + ".new").exists()) return emptyList()
        val data = af.readFully()
        val inp = DataInputStream(data.inputStream())
        if (inp.readInt() != MAGIC) return emptyList()
        val n = inp.readInt()
        if (n < 0 || n > 10_000) return emptyList()
        val res = ArrayList<ClipItem>(n)
        for (i in 0 until n) {
            val ts = inp.readLong()
            val len = inp.readInt()
            if (len < 0 || len > MAX_LEN * 4) break
            val b = ByteArray(len)
            inp.readFully(b)
            res.add(ClipItem(String(b, Charsets.UTF_8), ts, true))
        }
        return res
    }

    private fun mergeLoaded(loaded: List<ClipItem>) {
        var changed = false
        for (p in loaded) {
            val idx = list.indexOfFirst { it.text == p.text }
            if (idx < 0) {
                list.add(p)
                changed = true
            } else if (!list[idx].pinned) {
                // Yüklenirken aynı metin yeniden kopyalandıysa sabitli durumu koru, yeni zamanı tut.
                list[idx] = list[idx].copy(pinned = true)
                changed = true
            }
        }
        if (changed) {
            sortList()
            trimOverflow()
            notifyChanged()
        }
    }

    private companion object {
        const val FILE_NAME = "clip_pinned.bin"
        const val MAGIC = 0x434C5031 // "CLP1"
        const val MAX_LEN = 10_000
        const val TTL_MS = 60L * 60L * 1000L
        const val SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"
    }
}
