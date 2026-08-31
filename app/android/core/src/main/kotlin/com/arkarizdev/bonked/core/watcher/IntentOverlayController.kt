package com.arkarizdev.bonked.core.watcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * T-104: the intent-capture overlay — the doorway. Shown when
 * SessionStateMachine transitions a watched package to INTENT_PENDING;
 * dismissed either by a grant or by the user leaving without granting
 * (SessionStateMachine.onAppLeft's INTENT_PENDING -> IDLE case).
 *
 * Unlike the roast overlay (T-106), this window must be FOCUSABLE — the
 * excuse field needs real keyboard input — so it does NOT set
 * FLAG_NOT_FOCUSABLE the way the spike's read-only overlay did. It still
 * sets FLAG_HARDWARE_ACCELERATED: that was the spike's one hard-won
 * lesson (README.md), and it applies to any overlay window, not just the
 * roast one.
 *
 * Visual language matches the Figma tokens (ink/paper/yellow, thick
 * borders, no rounded corners) rather than reusing Flutter/Material
 * widgets — this is a native window, drawn with plain Android views.
 */
class IntentOverlayController(private val context: Context) {
    companion object {
        private const val TAG = "BonkedIntentOverlay"
        private const val INTENT_TEXT_MAX_LEN = 80 // PRD P0-2: shield-subtitle budget
        private val DURATION_OPTIONS = listOf(5, 10, 15, 30)
        private const val DEFAULT_MINUTES = 10

        // T-104 excuse chips: pre-written self-roasting excuses (TONE_GUIDE.md
        // rules apply — action, not person).
        private val EXCUSE_CHIPS = listOf(
            "Need inspiration for a project.",
            "Just checking the weather, basically.",
            "My hands did this automatically.",
            "Waiting for a massive file to download.",
            "It's called a micro-break, okay?",
            "A rewards program for opening my laptop.",
            "Slightly overwhelmed by my to-do list.",
            "Checking if the internet is still on.",
            "Just making sure I'm not missing out.",
            "Purely a tactical retreat.",
        )

        // T-104: the field starts pre-filled with this one (chip
        // highlighted, text set) rather than empty — there's no
        // "uncheck" affordance on a chip, only switching to a different
        // chip or overwriting the text by hand. Must be an exact string
        // from EXCUSE_CHIPS above, or the default won't highlight.
        private const val DEFAULT_EXCUSE = "My hands did this automatically."

        // T-104 typed-excuse roast: reacts live to what the user types in
        // the free-text field, once it stops matching a chip. No on-device
        // LLM/NLP exists here, so "reacting to what they typed" means
        // keyword pattern-matching against common excuse tropes, not real
        // understanding — first match wins, "%APP%" is swapped for the
        // blocked app's label.
        private val EXCUSE_KEYWORD_ROASTS = listOf(
            listOf("automatic", "muscle", "habit", "accident") to "The hands have a mind of their own, apparently.",
            listOf("download", "load", "render", "export") to "Ah, the classic 'waiting for technology' defense.",
            listOf("music", "song", "playlist", "audio") to "An essential audio track for maximum focus, surely.",
            listOf("text", "message", "ping", "notification") to "The Pavlovian response to a vibrating pocket.",
            listOf("link", "source", "article", "read") to "A highly intellectual deep-dive, no doubt.",
            listOf("video", "watch", "stream", "clip") to "Just one video. Which inevitably leads to twelve more.",
            listOf("group", "chat", "community", "server") to "The digital village requires your immediate presence.",
            listOf("break", "pause", "breathe", "lunch") to "Resting hard from the exhaustion of existing.",
        )

        // Used when the typed text doesn't match any keyword above — still
        // reacting to "they're writing a custom one," just without a
        // specific hook to react to. Picked once per typing session (see
        // the TextWatcher below), not re-rolled on every keystroke.
        private val EXCUSE_GENERIC_ROASTS = listOf(
            "An interesting premise. Let's see how it unfolds.",
            "Compiling this specific excuse into the database.",
            "A bespoke justification. Freshly pressed.",
            "The defense rests its case. The timer begins.",
            "A unique plot twist in your productivity arc.",
            "Drafting an original screenplay in the reason field.",
            "The system is processing this custom explanation.",
            "Crafting your own narrative. We respect the art.",
        )

        private const val COLOR_INK = 0xFF0D0D0D.toInt()
        private const val COLOR_PAPER = 0xFFFFFFFF.toInt()
        private const val COLOR_YELLOW = 0xFFFFE600.toInt()
        private const val COLOR_GRAY = 0xFF6B6B6B.toInt()
        private const val COLOR_RED = 0xFFFF2E00.toInt()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    // @Volatile: read/written from both WatcherService's poll loop
    // (Dispatchers.Default's worker-thread pool — a different physical
    // thread on every call) and the main thread (button clicks,
    // dismissViewOnMainThread). A plain var has no cross-thread visibility
    // guarantee, which let two Default-pool workers both pass the
    // `shownForPkg == pkg` guard before either write became visible to the
    // other — observed live as two "SHOWN" log lines for one grant with no
    // intervening state-machine transition between them.
    @Volatile private var shownForPkg: String? = null

    fun isShowing(pkg: String): Boolean = shownForPkg == pkg

    /**
     * No-op if already showing for this pkg (guards against re-showing on
     * every poll tick). Safe to call from any thread — WindowManager needs
     * a Looper (the main thread's), but WatcherService's poll loop runs on
     * Dispatchers.Default, so the actual view work is marshaled onto the
     * main thread here rather than requiring every caller to know that.
     */
    fun show(
        pkg: String,
        eventTs: Long,
        budgetMin: Int,
        usedMinToday: Int,
        onGrant: (minutes: Int, intentText: String?) -> Unit,
        onCancel: () -> Unit,
    ) {
        if (shownForPkg == pkg) return
        shownForPkg = pkg // set immediately so a second poll tick can't double-post
        mainHandler.post { showOnMainThread(pkg, eventTs, budgetMin, usedMinToday, onGrant, onCancel) }
    }

    private fun showOnMainThread(
        pkg: String,
        eventTs: Long,
        budgetMin: Int,
        usedMinToday: Int,
        onGrant: (minutes: Int, intentText: String?) -> Unit,
        onCancel: () -> Unit,
    ) {
        removeCurrentViewOnMainThread() // NOT dismissViewOnMainThread() — see its doc comment; this was T-106's live-caught flicker bug, fixed here too for the same reason

        var selectedMinutes = DEFAULT_MINUTES
        val chipViews = mutableMapOf<Int, Button>()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PAPER)
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }

        // Cancelling backs out exactly like SessionStateMachine.onAppLeft's
        // INTENT_PENDING -> IDLE case already treats "left without
        // granting" — no session was ever created (grant() is what
        // creates the Room row), so there's nothing to finalize. Also
        // sends the user home: dismissing the overlay alone would just
        // reveal the still-blocked app sitting underneath with nothing
        // granted, which defeats the point of backing out of it.
        fun cancel() {
            Log.i(TAG, "CANCEL pkg=$pkg")
            onCancel()
            dismissViewOnMainThread()
            goToHomeScreen()
        }

        root.addView(TextView(context).apply {
            text = "← BACK"
            setTextColor(COLOR_GRAY)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isClickable = true
            isFocusable = true
            setOnClickListener { cancel() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        root.addView(TextView(context).apply {
            text = "HOW LONG THIS TIME?"
            setTextColor(COLOR_INK)
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        root.addView(TextView(context).apply {
            text = "${appLabel(pkg)} is waiting. It can keep waiting."
            setTextColor(COLOR_GRAY)
            textSize = 15f
            setPadding(0, 0, 0, dp(24))
        })

        val chipRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        // T-104 budget-warning: budgetMin (set once, at onboarding/app-picker
        // time) and the minutes picked here are two separate numbers — this
        // line is the only place that ties them together, so the user sees
        // when a session choice would blow past today's budget instead of
        // the two silently drifting apart with no feedback at all.
        val budgetWarning = TextView(context).apply {
            setTextColor(COLOR_RED)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = View.GONE
        }
        fun refreshBudgetWarning() {
            val overBy = usedMinToday + selectedMinutes - budgetMin
            if (budgetMin > 0 && overBy > 0) {
                budgetWarning.text = "⚠️ THAT'S ${overBy} MIN OVER TODAY'S $budgetMin MIN BUDGET FOR ${appLabel(pkg).uppercase()}."
                budgetWarning.visibility = View.VISIBLE
            } else {
                budgetWarning.visibility = View.GONE
            }
        }

        for ((i, minutes) in DURATION_OPTIONS.withIndex()) {
            val chip = Button(context).apply {
                text = "${minutes}M"
                setTextColor(COLOR_INK)
                isAllCaps = true
                background = chipBackground(minutes == DEFAULT_MINUTES)
                setOnClickListener {
                    selectedMinutes = minutes
                    chipViews.forEach { (m, v) -> v.background = chipBackground(m == selectedMinutes) }
                    refreshBudgetWarning()
                }
            }
            chipViews[minutes] = chip
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) params.marginStart = dp(8)
            chipRow.addView(chip, params)
        }
        root.addView(chipRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        root.addView(budgetWarning, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })
        refreshBudgetWarning() // reflects the DEFAULT_MINUTES chip pre-selected above

        root.addView(TextView(context).apply {
            text = "WHY, THOUGH? (PICK ONE OR WRITE YOUR OWN)"
            setTextColor(COLOR_GRAY)
            textSize = 11f
            letterSpacing = 0.08f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        // Nudges the user into a roast either way, without a hard
        // requirement: tapping a chip fills the field with a pre-written
        // self-roasting excuse (still editable after), and typing a custom
        // one is fair game too — either path means whatever ends up in
        // this field is the user roasting themselves, on the record,
        // before the session even starts.
        val excuseChipViews = mutableMapOf<String, TextView>()
        var suppressChipSync = false
        var excuseFieldRef: EditText? = null // assigned once excuseField is built below; chip taps only fire after that
        // FlowLayout, not a HorizontalScrollView: 10 chips of varying width
        // scrolled off-screen sideways with no visual hint there was more
        // — wrapping onto new lines keeps every option visible up front.
        val excuseChipRow = FlowLayout(context).apply {
            horizontalSpacing = dp(8)
            verticalSpacing = dp(8)
        }
        for (excuse in EXCUSE_CHIPS) {
            val chip = TextView(context).apply {
                text = excuse
                setTextColor(COLOR_INK)
                textSize = 12f
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = chipBackground(false)
                setOnClickListener {
                    suppressChipSync = true
                    excuseFieldRef?.setText(excuse)
                    excuseFieldRef?.setSelection(excuse.length)
                    suppressChipSync = false
                    excuseChipViews.forEach { (e, v) -> v.background = chipBackground(e == excuse) }
                }
            }
            excuseChipViews[excuse] = chip
            excuseChipRow.addView(chip)
        }
        root.addView(excuseChipRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        // Live reaction to a custom-typed excuse — see EXCUSE_KEYWORD_ROASTS'
        // doc comment for what "reacting to what they typed" actually means
        // here (pattern-matching, not real understanding).
        val typedExcuseRoast = TextView(context).apply {
            setTextColor(COLOR_RED)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = View.GONE
        }
        var wasCustomTyping = false
        var lastGenericRoast: String? = null
        fun keywordRoastFor(text: String): String? {
            val lower = text.lowercase()
            val (_, template) = EXCUSE_KEYWORD_ROASTS.firstOrNull { (keywords, _) -> keywords.any { lower.contains(it) } }
                ?: return null
            return template.replace("%APP%", appLabel(pkg))
        }

        val excuseField = EditText(context).apply {
            hint = "...or type your own excuse"
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_GRAY)
            setBackgroundColor(COLOR_PAPER)
            filters = arrayOf(InputFilter.LengthFilter(INTENT_TEXT_MAX_LEN))
            setSingleLine(true)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    // Manually editing away from a selected chip's exact
                    // text un-highlights it — the chip row reflects what's
                    // actually in the field, not a stale last tap.
                    if (suppressChipSync) return
                    val text = s?.toString().orEmpty()
                    excuseChipViews.forEach { (e, v) -> v.background = chipBackground(e == text) }

                    if (text.isEmpty() || EXCUSE_CHIPS.contains(text)) {
                        // Empty, or an exact chip pick (even if reached by
                        // typing it out by hand) — nothing custom to react
                        // to yet.
                        wasCustomTyping = false
                        typedExcuseRoast.visibility = View.GONE
                        return
                    }
                    val keywordRoast = keywordRoastFor(text)
                    val roast = keywordRoast ?: run {
                        // No specific hook this keystroke — reuse the same
                        // generic line for the rest of this typing session
                        // instead of re-rolling on every character, or it'd
                        // flicker line-to-line while they type.
                        if (!wasCustomTyping || lastGenericRoast == null) {
                            lastGenericRoast = EXCUSE_GENERIC_ROASTS.random()
                        }
                        lastGenericRoast!!
                    }
                    wasCustomTyping = true
                    typedExcuseRoast.text = "✍️ $roast"
                    typedExcuseRoast.visibility = View.VISIBLE
                }
            })
        }
        excuseFieldRef = excuseField
        // Pre-select DEFAULT_EXCUSE now that the watcher is attached, so
        // this fires the same afterTextChanged path a chip tap would —
        // the matching chip highlights itself via the existing sync logic
        // above, no separate highlighting code needed here.
        excuseField.setText(DEFAULT_EXCUSE)
        excuseField.setSelection(DEFAULT_EXCUSE.length)
        root.addView(excuseField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        root.addView(typedExcuseRoast, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(24) })

        // Spacer pushes the CTA down, matching the Figma layout.
        root.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        root.addView(Button(context).apply {
            text = "START THE CLOCK"
            setTextColor(COLOR_PAPER)
            setBackgroundColor(COLOR_INK)
            isAllCaps = true
            setOnClickListener {
                val text = excuseField.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                Log.i(TAG, "START tapped pkg=$pkg minutes=$selectedMinutes hasText=${text != null}")
                onGrant(selectedMinutes, text)
                dismissViewOnMainThread() // already on the main thread — this is a click listener
            }
        })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, // deliberately NOT FLAG_NOT_FOCUSABLE
            PixelFormat.OPAQUE,
        ).apply {
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            gravity = Gravity.TOP
        }

        // Hardware/gesture back cancels the same way the "← BACK" button
        // does, instead of doing nothing — this window is focusable (the
        // excuse field needs real keyboard input) so it does receive key
        // events, but nothing consumed KEYCODE_BACK before this.
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                cancel()
                true
            } else {
                false
            }
        }

        windowManager.addView(root, lp)
        overlayView = root
        root.requestFocus()
        // shownForPkg was already set synchronously in show() and is no
        // longer touched by removeCurrentViewOnMainThread() above — no
        // need to re-assert it here (it previously masked the same bug
        // T-106 hit; see removeCurrentViewOnMainThread's doc comment).

        var logged = false
        root.viewTreeObserver.addOnPreDrawListener {
            if (!logged) {
                logged = true
                val shownMs = System.currentTimeMillis() - eventTs
                Log.i(TAG, "SHOWN pkg=$pkg shownMs=$shownMs")
            }
            true
        }
    }

    /**
     * Called from WatcherService's onAppLeft handling — no orphaned window
     * if abandoned. Safe to call from any thread, same reasoning as show().
     */
    fun dismissIfShowing(pkg: String) {
        if (shownForPkg != pkg) return
        shownForPkg = null
        mainHandler.post { removeCurrentViewOnMainThread() }
    }

    /** Full dismiss: removes the view AND clears shownForPkg. Used by the START button. */
    private fun dismissViewOnMainThread() {
        removeCurrentViewOnMainThread()
        shownForPkg = null
    }

    /**
     * View-only removal, no shownForPkg side effect — what showOnMainThread
     * must call before rebuilding. Using the full dismiss there was T-106's
     * live-caught flicker bug: it cleared shownForPkg moments after show()
     * had just set it, so the very next poll tick saw "not showing" and
     * rebuilt again, forever. Same fix applied here for the same reason,
     * even though this controller happened to mask it via a redundant
     * re-assignment that's now removed.
     */
    private fun removeCurrentViewOnMainThread() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    /** Same pattern as RoastOverlayController's helper of the same name — used by cancel() so backing out actually leaves the blocked app instead of just clearing the overlay on top of it. */
    private fun goToHomeScreen() {
        try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (t: Exception) {
            Log.w(TAG, "goToHomeScreen failed", t)
        }
    }

    private fun chipBackground(selected: Boolean) = android.graphics.drawable.GradientDrawable().apply {
        setColor(if (selected) COLOR_YELLOW else COLOR_PAPER)
        setStroke(dp(3), COLOR_INK)
    }

    private fun appLabel(pkg: String): String = resolveAppLabel(context, pkg)

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/**
 * Minimal left-to-right wrapping layout — lays children out in a row and
 * starts a new line whenever the next child would overflow the available
 * width. Used for the excuse chip row: pulling in a full flexbox library
 * for one wrapping row of chips would be a lot of dependency for a little
 * bit of behavior, and every other overlay in this file is already plain
 * Android views with no library dependency by the same reasoning.
 */
private class FlowLayout(context: Context) : ViewGroup(context) {
    var horizontalSpacing = 0
    var verticalSpacing = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxWidth = MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            if (x > 0 && x + childWidth > maxWidth) {
                x = 0
                y += lineHeight + verticalSpacing
                lineHeight = 0
            }
            x += childWidth + horizontalSpacing
            lineHeight = maxOf(lineHeight, childHeight)
        }
        setMeasuredDimension(maxWidth, y + lineHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = r - l
        var x = 0
        var y = 0
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            if (x > 0 && x + childWidth > maxWidth) {
                x = 0
                y += lineHeight + verticalSpacing
                lineHeight = 0
            }
            child.layout(x, y, x + childWidth, y + childHeight)
            x += childWidth + horizontalSpacing
            lineHeight = maxOf(lineHeight, childHeight)
        }
    }
}
