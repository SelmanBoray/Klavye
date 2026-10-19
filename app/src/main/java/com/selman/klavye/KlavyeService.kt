package com.selman.klavye

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.selman.klavye.clipboard.ClipboardHistory
import com.selman.klavye.clipboard.ClipboardPanelView
import com.selman.klavye.emoji.EmojiPanelView
import com.selman.klavye.input.ShiftController
import com.selman.klavye.input.WordUtils
import com.selman.klavye.layout.Key
import com.selman.klavye.layout.KeyType
import com.selman.klavye.layout.KeyboardLayout
import com.selman.klavye.layout.Layouts
import com.selman.klavye.layout.ShiftState
import com.selman.klavye.suggest.Suggester
import com.selman.klavye.suggest.SuggesterFactory
import com.selman.klavye.ui.KlavyeTheme
import com.selman.klavye.ui.Themes
import com.selman.klavye.view.KeyboardActionListener
import com.selman.klavye.view.KeyboardRootLayout
import com.selman.klavye.view.KeyboardView
import com.selman.klavye.view.SuggestionStripListener
import com.selman.klavye.view.SuggestionStripView
import java.text.BreakIterator
import java.util.Locale

/**
 * Klavye servisi.
 *
 * Metin modeli: composing text KULLANILMAZ; her şey commitText ile yazılır. İmleçten önceki
 * metnin yerel bir gölgesi ([textBefore]) tutulur: kendi düzenlemelerimiz gölgeye anında
 * uygulanır (IPC yok) ve her birinin editörde oluşturacağı imleç konumu kuyruğa yazılır.
 * onUpdateSelection bu beklenen konumu tutuyorsa hiçbir IPC yapılmaz; tutmuyorsa (kullanıcı imleci
 * taşıdı, uygulama metni değiştirdi, beklenti bilinmiyordu) bir kez getTextBeforeCursor okunur,
 * gölgeyle karşılaştırılır ve gerekirse gölge editörün metniyle değiştirilip durum yeniden kurulur.
 *
 * Kalıcı thread, timer, wake lock yok; tüm iş ana thread'de ve yalnız olaylara tepki olarak yapılır.
 */
class KlavyeService : InputMethodService() {

    private enum class LastAction { NONE, SPACE, AUTO_SPACE }
    private enum class Panel { KEYBOARD, EMOJI, CLIPBOARD }
    private enum class ShadowCheck { MATCH, EXTEND, MISMATCH }

    private val klavyeTheme: KlavyeTheme = Themes.DARK
    private val shift = ShiftController(SystemClock::uptimeMillis)

    private lateinit var suggester: Suggester
    private lateinit var clipboardHistory: ClipboardHistory

    // ---- Görünümler (onCreateInputView'da bir kez kurulur, alanlar arasında yeniden kullanılır) ----
    private var stripView: SuggestionStripView? = null
    private var keyboardFrame: FrameLayout? = null
    private var keyboardView: KeyboardView? = null
    private var emojiPanel: EmojiPanelView? = null
    private var clipboardPanel: ClipboardPanelView? = null
    private var panel = Panel.KEYBOARD
    private var currentLayout: KeyboardLayout? = null
    private var shownShift: ShiftState? = null
    private var shownSuggestions: List<String> = emptyList()
    /**
     * Öneri satırı açık mı: alan açılınca kapalı, klavyeden ilk düzenlemeyle açılır. Satır yalnız bu
     * bayrak açıkken ve klavye panelindeyken görünür ([updateStripVisibility]).
     */
    private var stripActive = false

    // ---- Alan bilgisi (onStartInputView'da EditorInfo'dan) ----
    private var inputViewStarted = false
    private var fieldNumeric = false
    private var startedNumeric = false
    private var suggestionsAllowed = false
    private var learningAllowed = false
    private var autoCapsField = false
    private var capsReqModes = 0
    /** Düz yazı alanı (şifre/URI/e-posta değil): çift boşluk, otomatik boşluk, akıllı noktalama açık. */
    private var proseField = false
    /** TYPE_NULL alan: silme/enter tuş olayıyla gönderilir. */
    private var nullField = false
    private var multiLine = false
    private var enterAction = EditorInfo.IME_ACTION_NONE
    /** Geçerli düzende SHIFT tuşu var mı (yalnız o düzende harfler büyütülür). */
    private var layoutHasShift = true

    // ---- Metin durumu ----
    /** İmleçten (seçim başından) önceki metnin yerel gölgesi. */
    private var textBefore = ""
    /** Gölge alanın başından mı başlıyor (son okuma CONTEXT_LEN'den kısa döndü). */
    private var shadowFromStart = false
    private var selStart = -1
    private var selEnd = -1
    /** Son onUpdateSelection'dan sonra kendi düzenlememiz oldu mu (seçim artık çökmüş sayılır). */
    private var ownEditsSinceUpdate = false
    private var lastAction = LastAction.NONE
    private var rawSuggestions: List<String> = emptyList()
    private var rawForEmptyComposing = true
    private var suggestionsSawSelection = false
    private var graphemes: BreakIterator? = null
    /**
     * Mevcut kelime bu oturumda klavyeyle yazıldı ve henüz öğrenilmedi mi. Yalnız bu durumda
     * ayırıcıda öğrenilir; öğrenince, öneri seçilince, imleç dışarıdan taşınınca sıfırlanır.
     */
    private var wordTyped = false

    // ---- Kendi düzenlemelerimizden beklenen imleç konumları (onUpdateSelection'da IPC'siz doğrulama) ----
    private val expPos = IntArray(EXP_MAX)
    private val expTime = LongArray(EXP_MAX)
    private var expCount = 0

    private val keyListener = object : KeyboardActionListener {
        override fun onKey(key: Key) {
            // Yalnız metni düzenleyen tuşlar öneri satırını açar (shift/düzen/emoji tuşları açmaz).
            when (key.type) {
                KeyType.CHAR, KeyType.SPACE, KeyType.DELETE, KeyType.ENTER -> activateStrip()
                else -> Unit
            }
            handleKey(key)
        }

        override fun onText(text: String) {
            activateStrip()
            // Görünüm shift açıksa metni zaten büyütmüş olarak verir.
            typeText(text, applyShift = false)
        }

        override fun onDeleteWord() {
            activateStrip()
            handleDeleteWord()
        }

        override fun onSpecialLongPress(key: Key) {
            // Araç çubuğu yok: pano emoji tuşuna uzun basınca açılır. Diğer özel tuşlar yok sayılır.
            if (key.type == KeyType.EMOJI) showClipboardPanel()
        }
    }

    private val stripListener = object : SuggestionStripListener {
        override fun onSuggestionPicked(word: String) {
            pickSuggestion(word)
        }
    }

    // =====================================================================================
    // Yaşam döngüsü
    // =====================================================================================

    override fun onCreate() {
        super.onCreate()
        suggester = SuggesterFactory.create(this)
        clipboardHistory = ClipboardHistory(this)
        clipboardHistory.start()
    }

    override fun onDestroy() {
        if (::suggester.isInitialized) suggester.flush()
        if (::clipboardHistory.isInitialized) clipboardHistory.stop()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View {
        configureWindow()

        // Kök, tuş önizleme balonunu tüm çocukların üstüne kendisi çizer (ayrı pencere yok).
        val root = KeyboardRootLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(klavyeTheme.background)
        root.setOnApplyWindowInsetsListener { v, insets -> applySystemInsets(v, insets) }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.requestApplyInsets()
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })

        val strip = SuggestionStripView(this)
        strip.setTheme(klavyeTheme)
        strip.listener = stripListener
        strip.setSuggestions(emptyList())
        root.addView(
            strip,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val frame = FrameLayout(this)
        val kv = KeyboardView(this)
        kv.setTheme(klavyeTheme)
        kv.listener = keyListener
        kv.previewHost = root
        val layout = currentLayout ?: Layouts.TURKISH_Q
        kv.setLayout(layout)
        currentLayout = layout
        layoutHasShift = hasShiftKey(layout)
        kv.setShiftState(shift.state)
        shownShift = shift.state
        kv.setEnterAction(enterAction)
        frame.addView(
            kv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        root.addView(
            frame,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        stripView = strip
        keyboardFrame = frame
        keyboardView = kv
        // Yapılandırma değişiminde eski ağaç atılır; paneller tekrar tembel oluşturulur.
        emojiPanel = null
        clipboardPanel = null
        panel = Panel.KEYBOARD
        shownSuggestions = emptyList()
        renderSuggestions()
        // Satır, ağaç pencereye eklenmeden önce doğru görünürlükte başlar (normalde GONE).
        updateStripVisibility()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        selStart = attribute?.initialSelStart ?: -1
        selEnd = attribute?.initialSelEnd ?: -1
        ownEditsSinceUpdate = false
        expCount = 0
        wordTyped = false
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        inputViewStarted = true
        readEditorInfo(info)
        lastAction = LastAction.NONE
        wordTyped = false
        expCount = 0
        if (!restarting) {
            shift.reset()
            // Yeni alan / yeni gösterim: satır kapalı açılır. Yeniden başlatmada (bazı uygulamalar
            // yazarken restartInput yapar) dokunulmaz; yoksa satır kaybolup uygulama zıplar.
            stripActive = false
            showKeyboardPanel()
        }
        if (!restarting || fieldNumeric != startedNumeric || currentLayout == null) {
            setKeyboardLayout(if (fieldNumeric) Layouts.SYMBOLS else Layouts.TURKISH_Q)
        }
        startedNumeric = fieldNumeric
        keyboardView?.setEnterAction(enterAction)

        val ic = currentInputConnection
        if (ic != null) {
            // Başka bir kaynaktan kalmış composing bölgesi commitText'lerimizi yutmasın.
            if (!restarting) ic.finishComposingText()
            syncFromEditor(ic, force = true)
        } else {
            textBefore = ""
            shadowFromStart = false
            pushShiftState()
            updateSuggestions()
        }
        updateStripVisibility()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        inputViewStarted = false
        stripActive = false
        updateStripVisibility()
        if (::suggester.isInitialized) suggester.flush()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        lastAction = LastAction.NONE
        ownEditsSinceUpdate = false
        expCount = 0
        wordTyped = false
        textBefore = ""
        shadowFromStart = false
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val unchanged = !ownEditsSinceUpdate && newSelStart == selStart && newSelEnd == selEnd
        selStart = newSelStart
        selEnd = newSelEnd
        ownEditsSinceUpdate = false
        // Klavye görünmüyorken IPC/iş yok.
        if (!inputViewStarted) {
            expCount = 0
            return
        }
        val ic = currentInputConnection ?: return
        if (candidatesStart != -1) ic.finishComposingText()
        if (unchanged) return
        // Kendi düzenlememizin beklenen sonucu: gölge doğru, editörden okumaya gerek yok.
        if (matchExpectedCursor(newSelStart, newSelEnd)) return
        // Açıklanamayan değişim (imleç taşındı, uygulama metni değiştirdi): mevcut kelime artık
        // klavyeyle yazılmış sayılmaz; editörden oku.
        wordTyped = false
        syncFromEditor(ic, force = false)
    }

    // =====================================================================================
    // Alan türü
    // =====================================================================================

    private fun readEditorInfo(info: EditorInfo?) {
        val inputType = info?.inputType ?: InputType.TYPE_NULL
        val imeOptions = info?.imeOptions ?: 0
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isText = cls == InputType.TYPE_CLASS_TEXT
        val password = (isText && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)

        fieldNumeric = cls == InputType.TYPE_CLASS_NUMBER ||
            cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME
        suggestionsAllowed = isText && !password &&
            (inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
        learningAllowed = isText && !password &&
            (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0
        capsReqModes = inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        autoCapsField = isText && !password && capsReqModes != 0
        proseField = isText && !password &&
            variation != InputType.TYPE_TEXT_VARIATION_URI &&
            variation != InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
            variation != InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        nullField = inputType == InputType.TYPE_NULL
        multiLine = isText && (inputType and (InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE)) != 0

        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        enterAction = if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
            action == EditorInfo.IME_ACTION_NONE ||
            action == EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            EditorInfo.IME_ACTION_NONE
        } else {
            action
        }
    }

    // =====================================================================================
    // Tuşlar
    // =====================================================================================

    private fun handleKey(key: Key) {
        when (key.type) {
            KeyType.CHAR -> typeText(key.output, applyShift = true)
            KeyType.SPACE -> handleSpace()
            KeyType.DELETE -> handleDelete()
            KeyType.ENTER -> handleEnter()
            KeyType.SHIFT -> {
                shift.onShiftKey()
                pushShiftState()
                renderSuggestions()
            }
            KeyType.MODE_SYMBOLS -> setKeyboardLayout(Layouts.SYMBOLS)
            KeyType.MODE_SYMBOLS_2 -> setKeyboardLayout(Layouts.SYMBOLS_2)
            KeyType.MODE_ALPHA -> setKeyboardLayout(Layouts.TURKISH_Q)
            KeyType.EMOJI -> showEmojiPanel()
        }
    }

    /**
     * CHAR tuşu (applyShift=true) ve uzun basma metni (görünüm zaten büyütmüş verir; tekrar büyütülmez).
     * Kelime karakteri değilse ayırıcıdır (öğrenme + akıllı noktalama).
     */
    private fun typeText(text: String, applyShift: Boolean) {
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        val out = if (applyShift) shiftedOutput(text) else text
        val isWord = WordUtils.isWordChar(out.codePointAt(0))
        if (!isWord) learnCurrentWord()
        val t = textBefore
        val base = cursorBase()
        val afterAutoSpace = !isWord && proseField && lastAction == LastAction.AUTO_SPACE &&
            t.endsWith(" ") && !hasSelection()
        if (afterAutoSpace && out in SMART_PUNCT) {
            // Öneriden sonra eklenen boşluğu sil, noktalamayı kelimeye bitiştir, ardından boşluk koy.
            val insert = "$out "
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(insert, 1)
            ic.endBatchEdit()
            applyLocalEdit(t.substring(0, t.length - 1) + insert)
            expectCursor(if (base >= 1) base - 1 + insert.length else UNKNOWN)
            lastAction = LastAction.AUTO_SPACE
        } else if (afterAutoSpace && out in APOSTROPHES) {
            // "Ankara " + ' -> "Ankara'": boşluk silinir, kesmeden sonra boşluk eklenmez.
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(out, 1)
            ic.endBatchEdit()
            applyLocalEdit(t.substring(0, t.length - 1) + out)
            expectCursor(if (base >= 1) base - 1 + out.length else UNKNOWN)
            lastAction = LastAction.NONE
        } else {
            ic.commitText(out, 1)
            applyLocalEdit(t + out)
            expectCursor(if (base >= 0) base + out.length else UNKNOWN)
            lastAction = LastAction.NONE
        }
        // Kelime karakteri: mevcut kelime klavyeyle yazılıyor. Ayırıcı: learnCurrentWord zaten sıfırladı.
        wordTyped = isWord
        shift.onCharacterTyped()
        afterLocalEdit(ic)
    }

    /**
     * Shift büyütme kuralı (KeyboardView ile aynı): yalnız içinde SHIFT tuşu olan düzende, yalnız tek
     * karakterlik harf çıktılarında ve tr-TR büyütmesi yine tek karaktere eşleniyorsa (ß -> "SS" olmaz).
     */
    private fun shiftedOutput(text: String): String {
        if (shift.state == ShiftState.OFF || !layoutHasShift) return text
        if (text.codePointCount(0, text.length) != 1) return text
        if (!Character.isLetter(text.codePointAt(0))) return text
        val up = text.uppercase(TR)
        return if (up.codePointCount(0, up.length) == 1) up else text
    }

    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        learnCurrentWord()
        val t = textBefore
        val base = cursorBase()
        val doubleSpace = proseField && !hasSelection() &&
            (lastAction == LastAction.SPACE || lastAction == LastAction.AUTO_SPACE) &&
            t.length >= 2 && t[t.length - 1] == ' ' &&
            WordUtils.isWordChar(Character.codePointBefore(t, t.length - 1))
        if (doubleSpace) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            applyLocalEdit(t.substring(0, t.length - 1) + ". ")
            expectCursor(if (base >= 1) base + 1 else UNKNOWN)
            lastAction = LastAction.NONE
        } else {
            ic.commitText(" ", 1)
            applyLocalEdit("$t ")
            expectCursor(if (base >= 0) base + 1 else UNKNOWN)
            lastAction = LastAction.SPACE
        }
        afterLocalEdit(ic)
    }

    /**
     * Seçim varsa seçimi siler. Yoksa imleç öncesindeki son grafemi (emoji/surrogate/ZWJ dizisi
     * bölünmeden, gölgeden BreakIterator ile) deleteSurroundingText ile siler: commitText'lerle aynı
     * kuyrukta sıralı işlenir. Tuş olayı (uygulamada ayrı mesaj olarak kuyruklanır ve sonraki
     * commitText'in arkasına düşebilir) yalnız TYPE_NULL alanda ya da imleç öncesi metin bilinmiyorken
     * (alan başında çip silme gibi davranışlar için) kullanılır.
     */
    private fun handleDelete() {
        val ic = currentInputConnection ?: return
        lastAction = LastAction.NONE
        val base = cursorBase()
        if (hasSelection()) {
            ic.commitText("", 1)
            // İmleçten (seçim başından) önceki metin değişmez.
            applyLocalEdit(textBefore)
            expectCursor(base)
        } else {
            val t = textBefore
            val shortened = removeLastGrapheme(t)
            if (!nullField && t.isNotEmpty()) {
                val n = t.length - shortened.length
                ic.deleteSurroundingText(n, 0)
                expectCursor(if (base >= n) base - n else UNKNOWN)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                expectCursor(UNKNOWN)
            }
            applyLocalEdit(shortened)
            // Ayırıcı silindiyse ya da kelime tükendiyse önceki (zaten sayılmış) kelimeye dönülür.
            if (WordUtils.currentWord(t).isEmpty() || WordUtils.currentWord(shortened).isEmpty()) {
                wordTyped = false
            }
        }
        afterLocalEdit(ic)
    }

    /**
     * Silme tuşu basılı tutulurken her tekrarda bir kelime adımı (WordUtils.wordDeleteLength: sondaki
     * satır sonu tek başına; yoksa sondaki boşluklar + önceki kelime ya da noktalama/emoji dizisi).
     * Yapı ve defter tutma [handleDelete] ile aynı: seçim varsa seçim silinir; adım gölgeden
     * deleteSurroundingText ile silinir; TYPE_NULL alanda ya da gölge boşken tek KEYCODE_DEL gönderilir.
     */
    private fun handleDeleteWord() {
        val ic = currentInputConnection ?: return
        lastAction = LastAction.NONE
        val base = cursorBase()
        if (hasSelection()) {
            ic.commitText("", 1)
            // İmleçten (seçim başından) önceki metin değişmez.
            applyLocalEdit(textBefore)
            expectCursor(base)
        } else {
            var t = textBefore
            var n = WordUtils.wordDeleteLength(t)
            // Gölge alan başını bilmiyor ve adım gölgenin başına dayanıyorsa kelimenin kalanı gölgenin
            // dışında olabilir: editörden bir kez taze oku (bekleyen düzenlemelerimizden sonra işlenir).
            if (!nullField && !shadowFromStart && n >= t.length) {
                val read = ic.getTextBeforeCursor(CONTEXT_LEN, 0)?.toString()
                if (read != null) {
                    textBefore = read
                    shadowFromStart = read.length < CONTEXT_LEN
                    t = read
                    n = WordUtils.wordDeleteLength(t)
                }
            }
            n = n.coerceIn(0, t.length)
            // Okunan pencere bir surrogate çiftinin ortasından başlıyorsa yarım çift silinmez.
            if (n == t.length && n > 0 && !shadowFromStart && Character.isLowSurrogate(t[0])) n--
            if (!nullField && n > 0) {
                ic.deleteSurroundingText(n, 0)
                expectCursor(if (base >= n) base - n else UNKNOWN)
                applyLocalEdit(t.substring(0, t.length - n))
                // Adım mevcut kelimeyi baştan siler (ya da yalnız ayırıcı/satır sonu): sayılmış kelimeye dönülür.
                wordTyped = false
            } else {
                // handleDelete'in tuş olayı dalı (alan başında çip silme gibi davranışlar korunur).
                val shortened = removeLastGrapheme(t)
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                expectCursor(UNKNOWN)
                applyLocalEdit(shortened)
                if (WordUtils.currentWord(t).isEmpty() || WordUtils.currentWord(shortened).isEmpty()) {
                    wordTyped = false
                }
            }
        }
        afterLocalEdit(ic)
    }

    /**
     * Eylemli alan: performEditorAction. Çok satırlı metin alanı: commitText("\n") (sıralı).
     * Tek satırlı ve eylemsiz alan ile TYPE_NULL: KEYCODE_ENTER — tek satırlı editöre "\n" metni
     * girmesin, editör Enter'ı kendisi yorumlasın (TextView: onEditorAction(IME_NULL)/odak geçişi,
     * web: form gönderme). Enter'dan hemen sonra yazmak bu alanlarda nadir olduğundan sıralama riski küçük.
     */
    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        learnCurrentWord()
        lastAction = LastAction.NONE
        when {
            enterAction != EditorInfo.IME_ACTION_NONE -> {
                ic.performEditorAction(enterAction)
                ownEditsSinceUpdate = true
                expectCursor(UNKNOWN)
            }
            multiLine && !nullField -> {
                val base = cursorBase()
                ic.commitText("\n", 1)
                applyLocalEdit(textBefore + "\n")
                expectCursor(if (base >= 0) base + 1 else UNKNOWN)
                afterLocalEdit(ic)
            }
            else -> {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                ownEditsSinceUpdate = true
                expectCursor(UNKNOWN)
            }
        }
    }

    // =====================================================================================
    // Öneriler
    // =====================================================================================

    /**
     * Öneri seçimi. Mevcut kelime doluysa onu (imleç kelimenin ortasındaysa sağ parçası dahil)
     * önerilen kelimeyle değiştirir. Mevcut kelime boşsa (sonraki-kelime tahmini) sağdaki metne
     * dokunmaz; yalnız ekler. deleteSurroundingText + commitText tek batch içinde; mutlak konum gerekmez.
     *
     * Otomatik boşluk yalnız düz yazı alanında (URI/e-posta/şifre değil):
     *  - sağda zaten boşluk varsa yeni boşluk eklenmez, imleç commitText(.., 2) ile o boşluğun
     *    ardına geçirilir (metin değişmez);
     *  - sağ taraf boş / satır sonu / (mevcut kelime boşken) bir kelime ise kelime + boşluk;
     *  - sağda noktalama vb. varsa boşluk eklenmez ("merh|." -> "merhaba.").
     */
    private fun pickSuggestion(word: String) {
        if (word.isEmpty()) return
        val ic = currentInputConnection ?: return
        val base = cursorBase()
        if (hasSelection()) {
            val insert = if (proseField) "$word " else word
            ic.commitText(insert, 1)
            applyLocalEdit(textBefore + insert)
            expectCursor(if (base >= 0) base + insert.length else UNKNOWN)
            if (learningAllowed) suggester.onWordCommitted(word, WordUtils.previousWord(textBefore))
            wordTyped = false
            lastAction = if (proseField) LastAction.AUTO_SPACE else LastAction.NONE
            shift.onCharacterTyped()
            afterLocalEdit(ic)
            return
        }
        val readBefore = ic.getTextBeforeCursor(CONTEXT_LEN, 0)?.toString()
        if (readBefore != null) shadowFromStart = readBefore.length < CONTEXT_LEN
        val before = readBefore ?: textBefore
        val after = ic.getTextAfterCursor(CONTEXT_LEN, 0)?.toString() ?: ""
        val current = WordUtils.currentWord(before)
        val previous = WordUtils.previousWord(before)

        // Sağ parça yalnız mevcut kelime doluyken değiştirilir.
        var suffix = 0
        if (current.isNotEmpty()) {
            while (suffix < after.length) {
                val cp = after.codePointAt(suffix)
                if (!WordUtils.isWordChar(cp)) break
                suffix += Character.charCount(cp)
            }
        }
        val next: Char? = if (suffix < after.length) after[suffix] else null
        val nextIsWord = next != null && WordUtils.isWordChar(after.codePointAt(suffix))
        val leadingSpace = proseField && current.isEmpty() && before.isNotEmpty() &&
            LEADING_SPACE_AFTER.indexOf(before[before.length - 1]) >= 0
        val trailing: String
        val cursorAfter: Int
        val autoSpace: Boolean
        when {
            !proseField -> { trailing = ""; cursorAfter = 1; autoSpace = false }
            next == ' ' -> { trailing = ""; cursorAfter = 2; autoSpace = true }
            next == null || next.isWhitespace() || nextIsWord -> { trailing = " "; cursorAfter = 1; autoSpace = true }
            else -> { trailing = ""; cursorAfter = 1; autoSpace = false }
        }
        val insert = (if (leadingSpace) " " else "") + word + trailing

        ic.beginBatchEdit()
        if (current.isNotEmpty()) ic.deleteSurroundingText(current.length, suffix)
        ic.commitText(insert, cursorAfter)
        ic.endBatchEdit()
        val hopped = if (cursorAfter == 2) " " else ""
        applyLocalEdit(before.substring(0, before.length - current.length) + insert + hopped)
        expectCursor(
            if (base >= current.length) base - current.length + insert.length + hopped.length else UNKNOWN
        )

        if (learningAllowed) suggester.onWordCommitted(word, previous)
        wordTyped = false
        lastAction = if (autoSpace) LastAction.AUTO_SPACE else LastAction.NONE
        shift.onCharacterTyped()
        afterLocalEdit(ic)
    }

    private fun updateSuggestions() {
        val sel = hasSelection()
        suggestionsSawSelection = sel
        var raw: List<String> = emptyList()
        var emptyComposing = true
        if (suggestionsAllowed && !sel) {
            val t = textBefore
            val composing = WordUtils.currentWord(t)
            val prev = WordUtils.previousWord(t)
            emptyComposing = composing.isEmpty()
            if (!emptyComposing || prev != null) {
                raw = suggester.suggest(composing, prev, SUGGESTION_COUNT)
            }
        }
        rawSuggestions = raw
        rawForEmptyComposing = emptyComposing
        renderSuggestions()
    }

    /**
     * Caps Lock'ta tüm öneriler (mevcut kelime dolu ya da boş) tr-TR ile tamamen büyük gösterilir ve
     * öyle yazılır. Shift ON iken yalnız sonraki-kelime tahminlerinin baş harfi büyütülür (cümle başı);
     * mevcut kelime doluyken büyük/küçük harfi motor korur.
     */
    private fun renderSuggestions() {
        val strip = stripView ?: return
        val raw = rawSuggestions
        val shown = when {
            raw.isEmpty() -> raw
            shift.state == ShiftState.CAPS_LOCK -> raw.map { w -> w.uppercase(TR) }
            shift.state == ShiftState.ON && rawForEmptyComposing ->
                raw.map { w -> w.replaceFirstChar { c -> c.titlecase(TR) } }
            else -> raw
        }
        if (shown == shownSuggestions) return
        shownSuggestions = shown
        strip.setSuggestions(shown)
    }

    /** Klavyeden metin düzenlendi: öneri satırı açılır (klavye panelindeyse görünür olur). */
    private fun activateStrip() {
        stripActive = true
        updateStripVisibility()
    }

    /**
     * Öneri satırı yalnız [stripActive] ve klavye panelindeyken VISIBLE, aksi halde GONE. Satır IME
     * yüksekliğini değiştirir (uygulama yeniden boyutlanır): görünürlük yalnız gerçekten değişince yazılır.
     * Satır GONE iken de öneriler güncellenir; açıldığında güncel kelimeleri gösterir.
     */
    private fun updateStripVisibility() {
        val strip = stripView ?: return
        val v = if (stripActive && panel == Panel.KEYBOARD) View.VISIBLE else View.GONE
        if (strip.visibility != v) strip.visibility = v
    }

    /** Ayırıcıda: yalnız bu oturumda klavyeyle yazılmış ve henüz sayılmamış kelime, bir kez. */
    private fun learnCurrentWord() {
        if (!wordTyped) return
        wordTyped = false
        if (!learningAllowed || hasSelection()) return
        val t = textBefore
        val word = WordUtils.currentWord(t)
        if (word.isEmpty()) return
        suggester.onWordCommitted(word, WordUtils.previousWord(t))
    }

    // =====================================================================================
    // Emoji / pano
    // =====================================================================================

    private fun commitEmoji(emoji: String) {
        if (emoji.isEmpty()) return
        val ic = currentInputConnection ?: return
        // Emoji panelindeyken satır GONE kalır; klavyeye dönünce görünür.
        activateStrip()
        learnCurrentWord()
        val base = cursorBase()
        ic.commitText(emoji, 1)
        applyLocalEdit(textBefore + emoji)
        expectCursor(if (base >= 0) base + emoji.length else UNKNOWN)
        lastAction = LastAction.NONE
        shift.onCharacterTyped()
        afterLocalEdit(ic)
    }

    private fun pasteClip(text: String) {
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        // showKeyboardPanel aşağıda satırı görünür yapar.
        activateStrip()
        val base = cursorBase()
        ic.commitText(text, 1)
        applyLocalEdit(textBefore + text)
        expectCursor(if (base >= 0) base + text.length else UNKNOWN)
        // Yapıştırılan metin klavyeyle yazılmış kelime sayılmaz.
        wordTyped = false
        lastAction = LastAction.NONE
        showKeyboardPanel()
        afterLocalEdit(ic)
    }

    private fun showKeyboardPanel() {
        emojiPanel?.visibility = View.GONE
        clipboardPanel?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
        panel = Panel.KEYBOARD
        updateStripVisibility()
    }

    private fun showEmojiPanel() {
        val frame = keyboardFrame ?: return
        val kv = keyboardView ?: return
        val h = keyboardHeight(kv)
        val p = emojiPanel ?: EmojiPanelView(this).also { ep ->
            ep.setTheme(klavyeTheme)
            ep.onEmoji = { e -> commitEmoji(e) }
            ep.onDelete = { activateStrip(); handleDelete() }
            ep.onClose = { showKeyboardPanel() }
            ep.visibility = View.GONE
            frame.addView(ep, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            emojiPanel = ep
        }
        fitPanelHeight(p, h)
        clipboardPanel?.visibility = View.GONE
        p.visibility = View.VISIBLE
        // INVISIBLE: klavye yerini korur, çerçevenin yüksekliği değişmez.
        kv.visibility = View.INVISIBLE
        panel = Panel.EMOJI
        updateStripVisibility()
    }

    private fun showClipboardPanel() {
        val frame = keyboardFrame ?: return
        val kv = keyboardView ?: return
        val h = keyboardHeight(kv)
        val existing = clipboardPanel
        val p = existing ?: ClipboardPanelView(this, clipboardHistory).also { cp ->
            cp.setTheme(klavyeTheme)
            cp.onPick = { text -> pasteClip(text) }
            cp.onClose = { showKeyboardPanel() }
            cp.visibility = View.GONE
            frame.addView(cp, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            clipboardPanel = cp
        }
        if (existing != null) existing.refresh()
        fitPanelHeight(p, h)
        emojiPanel?.visibility = View.GONE
        p.visibility = View.VISIBLE
        kv.visibility = View.INVISIBLE
        panel = Panel.CLIPBOARD
        updateStripVisibility()
    }

    private fun keyboardHeight(kv: KeyboardView): Int {
        if (kv.height > 0) return kv.height
        val parentW = (kv.parent as? View)?.width ?: 0
        val w = if (parentW > 0) parentW else resources.displayMetrics.widthPixels
        kv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (kv.measuredHeight > 0) return kv.measuredHeight
        val layout = currentLayout ?: Layouts.TURKISH_Q
        return (layout.rows.size * layout.rowHeightDp * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun fitPanelHeight(p: View, h: Int) {
        val lp = p.layoutParams ?: return
        if (lp.height != h) {
            lp.height = h
            p.layoutParams = lp
        }
    }

    private fun setKeyboardLayout(layout: KeyboardLayout) {
        if (layout === currentLayout) return
        currentLayout = layout
        layoutHasShift = hasShiftKey(layout)
        keyboardView?.setLayout(layout)
    }

    private fun hasShiftKey(layout: KeyboardLayout): Boolean =
        layout.rows.any { row -> row.keys.any { it.type == KeyType.SHIFT } }

    // =====================================================================================
    // Beklenen imleç konumu
    // =====================================================================================

    /**
     * Bir sonraki kendi düzenlememizin uygulanacağı imleç konumu: bekleyen düzenleme varsa son
     * beklenen konum, yoksa editörün bildirdiği seçimin başı; bilinmiyorsa UNKNOWN.
     */
    private fun cursorBase(): Int {
        pruneExpectations(SystemClock.uptimeMillis())
        if (expCount > 0) return expPos[expCount - 1]
        if (ownEditsSinceUpdate || selStart < 0 || selEnd < 0) return UNKNOWN
        return minOf(selStart, selEnd)
    }

    /** Kendi düzenlememizden sonra editörün bildirmesi beklenen (çökmüş) imleç konumunu kuyruğa ekler. */
    private fun expectCursor(pos: Int) {
        val now = SystemClock.uptimeMillis()
        pruneExpectations(now)
        if (expCount == EXP_MAX) dropExpectations(1)
        expPos[expCount] = if (pos >= 0) pos else UNKNOWN
        expTime[expCount] = now
        expCount++
    }

    /**
     * onUpdateSelection bekleyen düzenlemelerimizden birinin sonucuysa (çökmüş seçim, beklenen konum)
     * true döner ve o noktaya kadar kuyruğu tüketir. UNKNOWN bir beklentinin ötesi doğrulanamaz.
     * Eşleşmezse kuyruk boşaltılır (çağıran editörden okur).
     */
    private fun matchExpectedCursor(newSelStart: Int, newSelEnd: Int): Boolean {
        pruneExpectations(SystemClock.uptimeMillis())
        if (newSelStart >= 0 && newSelStart == newSelEnd) {
            for (i in 0 until expCount) {
                val p = expPos[i]
                if (p == UNKNOWN) break
                if (p == newSelStart) {
                    dropExpectations(i + 1)
                    return true
                }
            }
        }
        expCount = 0
        return false
    }

    /** Hiç gelmeyen bildirimler (ör. editör girdiyi reddetti) sonsuza dek eşleşme adayı kalmasın. */
    private fun pruneExpectations(now: Long) {
        var n = 0
        while (n < expCount && now - expTime[n] > EXP_TIMEOUT_MS) n++
        if (n > 0) dropExpectations(n)
    }

    private fun dropExpectations(n: Int) {
        if (n >= expCount) {
            expCount = 0
            return
        }
        System.arraycopy(expPos, n, expPos, 0, expCount - n)
        System.arraycopy(expTime, n, expTime, 0, expCount - n)
        expCount -= n
    }

    // =====================================================================================
    // Metin gölgesi ve editörle senkron
    // =====================================================================================

    private fun hasSelection(): Boolean =
        !ownEditsSinceUpdate && selStart >= 0 && selEnd >= 0 && selStart != selEnd

    /** Kendi düzenlememizi gölgeye uygular. */
    private fun applyLocalEdit(newShadow: String) {
        var s = newShadow
        if (s.length > SHADOW_MAX) {
            var start = s.length - SHADOW_KEEP
            if (Character.isLowSurrogate(s[start])) start++
            s = s.substring(start)
            shadowFromStart = false
        }
        textBefore = s
        ownEditsSinceUpdate = true
    }

    /** Yerel düzenleme sonrası: auto-caps (gölgeden) + öneriler. Normalde IPC yok. */
    private fun afterLocalEdit(ic: InputConnection) {
        if (autoCapsField && shift.state != ShiftState.CAPS_LOCK) {
            shift.applyAutoCaps(capsFromShadow(ic))
        }
        pushShiftState()
        updateSuggestions()
    }

    /**
     * Editörden imleç öncesi metni bir kez okur. force=false iken gölgeyle uyuşuyorsa hiçbir şey
     * yeniden hesaplanmaz; uyuşmuyorsa (imleç taşındı / uygulama metni değiştirdi) gölge değiştirilir,
     * son eylem unutulur, auto-caps getCursorCapsMode ile ve öneriler yeniden kurulur.
     */
    private fun syncFromEditor(ic: InputConnection, force: Boolean) {
        val read = ic.getTextBeforeCursor(CONTEXT_LEN, 0)?.toString()
        if (read == null) {
            if (force) {
                textBefore = ""
                shadowFromStart = false
                resyncDerivedState(ic)
            } else if (hasSelection() != suggestionsSawSelection) {
                updateSuggestions()
            }
            return
        }
        if (!force) {
            when (compareShadow(read)) {
                ShadowCheck.MATCH -> {
                    if (hasSelection() != suggestionsSawSelection) updateSuggestions()
                    return
                }
                ShadowCheck.EXTEND -> {
                    textBefore = read
                    shadowFromStart = read.length < CONTEXT_LEN
                    updateSuggestions()
                    return
                }
                ShadowCheck.MISMATCH -> Unit
            }
        }
        textBefore = read
        shadowFromStart = read.length < CONTEXT_LEN
        lastAction = LastAction.NONE
        resyncDerivedState(ic)
    }

    private fun resyncDerivedState(ic: InputConnection) {
        if (autoCapsField && shift.state != ShiftState.CAPS_LOCK) {
            shift.applyAutoCaps(ic.getCursorCapsMode(capsReqModes) != 0)
        }
        pushShiftState()
        updateSuggestions()
    }

    private fun compareShadow(read: String): ShadowCheck {
        val s = textBefore
        return if (s.length >= read.length) {
            // Okuma CONTEXT_LEN'den kısaysa gerçek metin o kadardır: gölge birebir eşit olmalı.
            if (s.endsWith(read) && (read.length >= CONTEXT_LEN || s.length == read.length)) {
                ShadowCheck.MATCH
            } else {
                ShadowCheck.MISMATCH
            }
        } else {
            if (!shadowFromStart && read.endsWith(s)) ShadowCheck.EXTEND else ShadowCheck.MISMATCH
        }
    }

    /**
     * Gölge üzerinde TextUtils.getCapsMode (BaseInputConnection.getCursorCapsMode'un yaptığı hesap).
     * Gölge alan başını bilmiyorsa ve tarama başa dayanabilecekse editöre sorulur.
     */
    private fun capsFromShadow(ic: InputConnection): Boolean {
        val t = textBefore
        var reliable = shadowFromStart
        if (!reliable) {
            for (ch in t) {
                if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '?' || ch == '!' || ch == '\n') {
                    reliable = true
                    break
                }
            }
        }
        return if (reliable) {
            TextUtils.getCapsMode(t, t.length, capsReqModes) != 0
        } else {
            ic.getCursorCapsMode(capsReqModes) != 0
        }
    }

    /** Gölgenin son grafemini (genişletilmiş grafem kümesi: surrogate, ZWJ emoji, bayrak, CRLF) atar. */
    private fun removeLastGrapheme(s: String): String {
        if (s.isEmpty()) return s
        val bi = graphemes ?: BreakIterator.getCharacterInstance().also { graphemes = it }
        bi.setText(s)
        val b = bi.preceding(s.length)
        val cut = if (b == BreakIterator.DONE || b < 0 || b >= s.length) {
            s.length - Character.charCount(s.codePointBefore(s.length))
        } else {
            b
        }
        return s.substring(0, cut)
    }

    private fun pushShiftState() {
        val s = shift.state
        if (s == shownShift) return
        shownShift = s
        keyboardView?.setShiftState(s)
    }

    // =====================================================================================
    // Pencere / gezinme çubuğu
    // =====================================================================================

    /** Eski davranışta (dekor gezinme çubuğunu kendisi boyarken) bölge klavye renginde olsun. */
    @Suppress("DEPRECATION")
    private fun configureWindow() {
        val w = window?.window ?: return
        w.navigationBarColor = klavyeTheme.background
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            w.setNavigationBarContrastEnforced(false)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Koyu klavye: gezinme çubuğu simgeleri açık renk.
            w.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
    }

    /**
     * Kenardan kenara IME penceresi (targetSdk 35+ ile dekor, IME içeriğini gezinme çubuğu için
     * artık kaydırmıyor) — alt sıra sistem şeridinin altında kalmasın diye kökün altına dolgu verilir;
     * dolgu kökün zemin rengiyle (klavye zemini) boyanır.
     *
     * Hareketle gezinmede sistemin IME gezinme şeridi (⌄ gizle / 🌐 klavye değiştir düğmeleri,
     * InputMethodService'in NavigationBarFrame'i) IME penceresine navigationBars() olarak DEĞİL,
     * captionBar() inset'i olarak bildirilir; Xiaomi tam ekran hareketlerinde navigationBars() 0 gelir
     * (cihazda ölçüldü: nav=0, captionBar=132 px, şerit y=2268 h=132). Bu yüzden
     * navigationBars | captionBar | displayCutout birleşiminin (kenar başına en büyüğü) alınır:
     * 3 tuşlu gezinmede navigationBars, hareketle gezinmede captionBar, şerit yoksa 0.
     * Dekor insets'i zaten tükettiyse (eski davranış) burada 0 gelir; çift dolgu olmaz.
     */
    @Suppress("DEPRECATION")
    private fun applySystemInsets(v: View, insets: WindowInsets): WindowInsets {
        val left: Int
        val right: Int
        val bottom: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(
                WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.captionBar() or
                    WindowInsets.Type.displayCutout()
            )
            left = bars.left
            right = bars.right
            bottom = bars.bottom
        } else {
            left = insets.systemWindowInsetLeft
            right = insets.systemWindowInsetRight
            bottom = insets.systemWindowInsetBottom
        }
        if (v.paddingLeft != left || v.paddingRight != right || v.paddingBottom != bottom || v.paddingTop != 0) {
            v.setPadding(left, 0, right, bottom)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets.CONSUMED
        } else {
            insets.consumeSystemWindowInsets()
        }
    }

    private companion object {
        val TR: Locale = Locale("tr", "TR")
        const val CONTEXT_LEN = 64
        const val SUGGESTION_COUNT = 3
        const val SHADOW_MAX = 256
        const val SHADOW_KEEP = 128
        val SMART_PUNCT: Set<String> = setOf(".", ",", "!", "?", ":", ";")
        /** Otomatik boşluktan sonra gelirse boşluğu silen, ardından boşluk eklenmeyen kesme işaretleri. */
        val APOSTROPHES: Set<String> = setOf("'", "’")
        const val UNKNOWN = -1
        const val EXP_MAX = 16
        const val EXP_TIMEOUT_MS = 1500L
        /** Boş composing ile tahmin seçilirken bunlardan sonra önce bir boşluk eklenir. */
        const val LEADING_SPACE_AFTER = ",.!?:;)]}…"
    }
}
