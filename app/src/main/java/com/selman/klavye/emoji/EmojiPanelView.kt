package com.selman.klavye.emoji

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import com.selman.klavye.ui.KlavyeTheme

/**
 * Emoji paneli. Izgara: framework GridView + BaseAdapter (hucre geri donusumu, convertView).
 * Kategori verisi (EmojiData) ve sekmeler panel ilk ekrana eklendiginde tembel olusturulur.
 */
class EmojiPanelView(context: Context) : FrameLayout(context) {

    var onEmoji: ((String) -> Unit)? = null
    var onDelete: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private val appContext = context.applicationContext ?: context
    private val prefs by lazy { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())

    private var theme: KlavyeTheme? = null
    private var loaded = false
    private var categories: List<EmojiCategory> = emptyList()
    private var recents: MutableList<String> = mutableListOf()
    private var selectedTab = -1
    private var userPickedTab = false

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tabRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val tabViews = ArrayList<TextView>()
    private val adapter = EmojiAdapter()
    private val grid = GridView(context)
    private val emptyView = TextView(context)
    private val divider = View(context)
    private val bottomRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val abcButton = TextView(context)
    private val deleteButton = TextView(context)

    private val deleteRepeat = object : Runnable {
        override fun run() {
            onDelete?.invoke()
            handler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    init {
        val gridFrame = FrameLayout(context)
        grid.apply {
            numColumns = COLUMNS
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = 0
            verticalSpacing = 0
            isVerticalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            this.adapter = this@EmojiPanelView.adapter
            setOnItemClickListener { _, view, position, _ ->
                val emoji = this@EmojiPanelView.adapter.getItem(position)
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                addRecent(emoji)
                onEmoji?.invoke(emoji)
            }
        }
        emptyView.apply {
            text = "Henüz son kullanılan emoji yok"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            visibility = GONE
        }
        gridFrame.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gridFrame.addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        abcButton.apply {
            text = "ABC"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClose?.invoke()
            }
        }
        deleteButton.apply {
            text = "⌫"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.isPressed = true
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        handler.removeCallbacks(deleteRepeat)
                        onDelete?.invoke()
                        handler.postDelayed(deleteRepeat, REPEAT_DELAY_MS)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.isPressed = false
                        handler.removeCallbacks(deleteRepeat)
                    }
                }
                true
            }
        }
        val btnW = dp(76)
        val btnH = dp(38)
        bottomRow.addView(abcButton, LinearLayout.LayoutParams(btnW, btnH))
        bottomRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bottomRow.addView(deleteButton, LinearLayout.LayoutParams(btnW, btnH))
        bottomRow.setPadding(dp(6), dp(3), dp(6), dp(3))

        root.addView(tabRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        root.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        root.addView(gridFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(
            bottomRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        root.setBackgroundColor(Color.parseColor("#1E1E1E"))
    }

    fun setTheme(theme: KlavyeTheme) {
        this.theme = theme
        applyTheme()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!loaded) {
            loadLazily()
        } else if (selectedTab == 0) {
            showTab(0)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(deleteRepeat)
        deleteButton.isPressed = false
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            handler.removeCallbacks(deleteRepeat)
            deleteButton.isPressed = false
        }
    }

    private fun loadLazily() {
        loaded = true
        categories = EmojiData.categories

        addTab("🕘")
        for (c in categories) addTab(c.icon)
        applyTheme()

        showTab(if (categories.isNotEmpty()) 1 else 0)

        // Disk okumasi ana thread'de yapilmaz; bitince son kullanilanlar birlestirilir.
        Thread {
            val stored = try { readRecents() } catch (e: Exception) { mutableListOf() }
            post {
                val merged = ArrayList<String>(recents)
                for (e in stored) if (merged.size < MAX_RECENT && !merged.contains(e)) merged.add(e)
                recents = merged
                if (selectedTab == 0) {
                    showTab(0)
                } else if (!userPickedTab && recents.isNotEmpty()) {
                    showTab(0)
                }
            }
        }.start()
    }

    private fun addTab(label: String) {
        val index = tabViews.size
        val tv = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                userPickedTab = true
                showTab(index)
            }
        }
        tabViews.add(tv)
        tabRow.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun showTab(index: Int) {
        selectedTab = index
        val list: List<String> =
            if (index == 0) recents else categories.getOrNull(index - 1)?.emojis ?: emptyList()
        adapter.setItems(ArrayList(list))
        grid.setSelection(0)
        emptyView.visibility = if (index == 0 && list.isEmpty()) VISIBLE else GONE
        applyTabStyles()
    }

    private fun addRecent(emoji: String) {
        recents.remove(emoji)
        recents.add(0, emoji)
        while (recents.size > MAX_RECENT) recents.removeAt(recents.size - 1)
        prefs.edit().putString(KEY_RECENT, recents.joinToString(SEP)).apply()
    }

    private fun readRecents(): MutableList<String> {
        val s = prefs.getString(KEY_RECENT, null)
        if (s.isNullOrEmpty()) return mutableListOf()
        val out = ArrayList<String>()
        for (e in s.split(SEP)) {
            if (e.isNotEmpty() && !out.contains(e)) out.add(e)
            if (out.size >= MAX_RECENT) break
        }
        return out
    }

    // ---- tema ----

    private fun applyTheme() {
        val t = theme ?: return
        root.setBackgroundColor(t.background)
        divider.setBackgroundColor(t.divider)
        emptyView.setTextColor(t.hintText)
        grid.selector = roundRect(t.keyPressed, t.keyCornerRadiusDp)
        adapter.textColor = t.keyText
        adapter.notifyDataSetChanged()
        abcButton.setTextColor(t.keyText)
        abcButton.background = buttonBg(t.specialKeyBackground, t.keyPressed, t.keyCornerRadiusDp)
        deleteButton.setTextColor(t.keyText)
        deleteButton.background = buttonBg(t.specialKeyBackground, t.keyPressed, t.keyCornerRadiusDp)
        applyTabStyles()
    }

    private fun applyTabStyles() {
        val t = theme ?: return
        for (i in tabViews.indices) {
            val tv = tabViews[i]
            val sel = i == selectedTab
            tv.background =
                if (sel) roundRect(t.accentKeyBackground, t.keyCornerRadiusDp)
                else ColorDrawable(Color.TRANSPARENT)
            tv.alpha = if (sel) 1f else 0.65f
            tv.setTextColor(t.keyText)
        }
    }

    private fun roundRect(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * density
        }

    private fun buttonBg(normal: Int, pressed: Int, radiusDp: Float): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), roundRect(pressed, radiusDp))
            addState(intArrayOf(), roundRect(normal, radiusDp))
        }

    private fun dp(v: Int): Int = (v * density + 0.5f).toInt()

    private inner class EmojiAdapter : BaseAdapter() {
        private var items: List<String> = emptyList()
        var textColor: Int = Color.WHITE
        private val rowHeight = dp(46)

        fun setItems(list: List<String>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): String = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv: TextView
            if (convertView is TextView) {
                tv = convertView
            } else {
                tv = TextView(parent.context).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
                    layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, rowHeight)
                }
            }
            tv.setTextColor(textColor)
            tv.text = items[position]
            return tv
        }
    }

    private companion object {
        const val PREFS = "emoji"
        const val KEY_RECENT = "recent"
        const val SEP = "\n"
        const val MAX_RECENT = 32
        const val COLUMNS = 8
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L
    }
}
