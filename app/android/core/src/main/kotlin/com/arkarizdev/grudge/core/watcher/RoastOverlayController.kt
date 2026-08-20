package com.arkarizdev.grudge.core.watcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.arkarizdev.grudge.core.roast.RoastEngine

/**
 * T-106: the roast overlay — the payoff moment. Strictly READ-ONLY: it
 * displays a precomputed roast_payload row (line1/line2/asset) and never
 * computes anything itself, matching the precompute discipline from
 * tech plan §5/§7 — RoastEngine already did the work back at grant time.
 *
 * Unlike the intent overlay (T-104), this window is NOT focusable — no
 * text input here, so it goes back to the spike's original pattern
 * (FLAG_NOT_FOCUSABLE + FLAG_HARDWARE_ACCELERATED). The hardware-
 * acceleration flag is the one thing every overlay window in this app
 * must set; see spike/README.md for why.
 *
 * NO SHARE AFFORDANCE — this is a hard product requirement (PRD/tech plan:
 * "no share affordance on the roast overlay ever — sharing is success-side
 * only"), not an oversight. Do not add one here.
 *
 * T-107: the extend control is a friction gradient, not a fixed button —
 * PRD P0-4 "more time is always available, at escalating friction (tap →
 * type a phrase → wait timer)". Tier is derived from the session's own
 * extension count via RoastEngine.tierFor — the same function that already
 * picks the roast copy's tier, so the friction and the joke escalate in
 * lockstep for free. Every tier still grants the same EXTEND_MINUTES; only
 * the cost of pressing the button changes (the copy itself gets drier per
 * tier, which is RoastEngine/roast_pack.json's job, not this overlay's).
 */
class RoastOverlayController(private val context: Context) {
    companion object {
        private const val TAG = "GrudgeRoastOverlay"
        private const val EXTEND_MINUTES = 5
        private const val CONFIRM_PHRASE = "I HAVE NO SELF CONTROL"
        private const val WAIT_TIMER_SECONDS = 15

        private const val COLOR_INK = 0xFF0D0D0D.toInt()
        private const val COLOR_PAPER = 0xFFFFFFFF.toInt()
        private const val COLOR_YELLOW = 0xFFFFE600.toInt()
        private const val COLOR_GRAY = 0xFF6B6B6B.toInt()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    // Only live while a tier-3 (wait timer) control is on screen. Must be
    // cancelled on every teardown, or a callback could fire onFinish() and
    // touch a button that's already been removed from the window.
    private var activeCountDownTimer: CountDownTimer? = null

    // @Volatile: same cross-thread reasoning as IntentOverlayController —
    // read/written from both WatcherService's poll loop (Dispatchers.Default
    // pool) and the main thread.
    @Volatile private var shownForPkg: String? = null

    fun isShowing(pkg: String): Boolean = shownForPkg == pkg

    /**
     * @param eventTs baseline for latency logging — pass the poll-tick time
     *   the ROASTING transition was detected at (this overlay isn't driven
     *   by a UsageEvent the way the intent overlay is, so there's no finer
     *   ground truth available).
     */
    fun show(
        pkg: String,
        line1: String,
        line2: String,
        assetRef: String,
        eventTs: Long,
        extensionsSoFar: Int,
        onDone: () -> Unit,
        onExtend: (additionalMinutes: Int) -> Unit,
    ) {
        if (shownForPkg == pkg) return
        shownForPkg = pkg
        mainHandler.post { showOnMainThread(pkg, line1, line2, assetRef, eventTs, extensionsSoFar, onDone, onExtend) }
    }

    private fun showOnMainThread(
        pkg: String,
        line1: String,
        line2: String,
        assetRef: String,
        eventTs: Long,
        extensionsSoFar: Int,
        onDone: () -> Unit,
        onExtend: (Int) -> Unit,
    ) {
        removeCurrentViewOnMainThread() // NOT dismissViewOnMainThread() — must not clear shownForPkg mid-rebuild

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_INK)
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        root.addView(TextView(context).apply {
            text = "TIME'S UP"
            setTextColor(COLOR_YELLOW)
            textSize = 13f
            letterSpacing = 0.15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // Spacer above the meme — pushes content toward vertical center-ish, matching Figma.
        root.addView(View(context), LinearLayout.LayoutParams(0, 0, 0.3f))

        loadMemeBitmap(assetRef)?.let { bitmap ->
            root.addView(
                ImageView(context).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220))
                    .apply { bottomMargin = dp(20) },
            )
        }

        root.addView(TextView(context).apply {
            text = line1
            setTextColor(COLOR_PAPER)
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        root.addView(TextView(context).apply {
            text = line2
            setTextColor(COLOR_YELLOW)
            textSize = 30f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        root.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        root.addView(Button(context).apply {
            text = "FINE. I'M DONE."
            setTextColor(COLOR_INK)
            setBackgroundColor(COLOR_PAPER)
            isAllCaps = true
            setOnClickListener {
                Log.i(TAG, "DONE tapped pkg=$pkg")
                onDone()
                dismissViewOnMainThread()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })

        val tier = RoastEngine.tierFor(extensionsSoFar)
        buildExtendControl(root, pkg, tier, onExtend)

        root.addView(TextView(context).apply {
            text = "No share button on roasts. That's the point."
            setTextColor(COLOR_GRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        // Tier 2 adds a real EditText (the typed-phrase friction) that needs
        // actual keyboard input — a NOT_FOCUSABLE window can still be tapped
        // (hit-testing doesn't require focusability) but can NEVER receive
        // key/IME input; the system just leaves the last focusable window
        // (Flutter's) as the served view and every typed character goes
        // nowhere. Caught live: the field showed a cursor (focus-looking)
        // but `input text` silently no-opped. So the window is focusable
        // only for tier 2; tiers 1/3 have no text field and stay
        // NOT_FOCUSABLE, same as before T-107.
        val focusFlag = if (tier == 2) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            focusFlag or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            if (tier == 2) softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        windowManager.addView(root, lp)
        overlayView = root

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

    fun dismissIfShowing(pkg: String) {
        if (shownForPkg != pkg) return
        shownForPkg = null
        mainHandler.post { removeCurrentViewOnMainThread() }
    }

    /**
     * Full dismiss: removes the view AND clears shownForPkg, so a future
     * roast for this pkg is allowed to show again. Used by dismissIfShowing
     * and by the button click listeners (done/extend) — both are genuine
     * "this roast is over" events.
     */
    private fun dismissViewOnMainThread() {
        removeCurrentViewOnMainThread()
        shownForPkg = null
    }

    /**
     * View-only removal, no shownForPkg side effect. This is what
     * showOnMainThread must call before rebuilding — using the full dismiss
     * there was the actual bug behind T-106's live-observed flicker: it
     * cleared shownForPkg moments after show() had just set it, so the very
     * next poll tick saw "not showing" and rebuilt again, forever.
     */
    private fun removeCurrentViewOnMainThread() {
        activeCountDownTimer?.cancel()
        activeCountDownTimer = null
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        overlayView = null
    }

    /**
     * T-107 friction gradient. Tier 1 (first extend): a plain tap, same as
     * before T-107. Tier 2 (second extend): must type CONFIRM_PHRASE
     * exactly (case-insensitive, trimmed) before the button enables — real
     * friction, not just an extra tap. Tier 3 (third+ extend): the button
     * starts disabled and counts down WAIT_TIMER_SECONDS before enabling —
     * friction that costs time instead of effort. All three ultimately
     * grant the same EXTEND_MINUTES; only getting to press the button
     * changes.
     */
    private fun buildExtendControl(root: LinearLayout, pkg: String, tier: Int, onExtend: (Int) -> Unit) {
        when (tier) {
            1 -> {
                root.addView(outlinedButton("+$EXTEND_MINUTES MIN (WEAK)") {
                    Log.i(TAG, "EXTEND tapped pkg=$pkg minutes=$EXTEND_MINUTES tier=1 friction=tap")
                    onExtend(EXTEND_MINUTES)
                    dismissViewOnMainThread()
                })
            }
            2 -> {
                val phraseField = EditText(context).apply {
                    hint = "Type: $CONFIRM_PHRASE"
                    setTextColor(COLOR_PAPER)
                    setHintTextColor(COLOR_GRAY)
                    setBackgroundColor(Color.TRANSPARENT)
                    setSingleLine(true)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                root.addView(phraseField, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) })

                val confirmButton = outlinedButton("TYPE IT TO MEAN IT") {
                    Log.i(TAG, "EXTEND tapped pkg=$pkg minutes=$EXTEND_MINUTES tier=2 friction=typed-phrase")
                    onExtend(EXTEND_MINUTES)
                    dismissViewOnMainThread()
                }.apply {
                    isEnabled = false
                    alpha = 0.4f
                }
                phraseField.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val matches = s?.toString()?.trim().equals(CONFIRM_PHRASE, ignoreCase = true)
                        confirmButton.isEnabled = matches
                        confirmButton.alpha = if (matches) 1f else 0.4f
                    }
                })
                root.addView(confirmButton)
            }
            else -> {
                val waitButton = outlinedButton("WAIT ${WAIT_TIMER_SECONDS}s...") {
                    Log.i(TAG, "EXTEND tapped pkg=$pkg minutes=$EXTEND_MINUTES tier=3 friction=wait-timer")
                    onExtend(EXTEND_MINUTES)
                    dismissViewOnMainThread()
                }.apply {
                    isEnabled = false
                    alpha = 0.4f
                }
                root.addView(waitButton)
                activeCountDownTimer = object : CountDownTimer(WAIT_TIMER_SECONDS * 1000L, 1000L) {
                    override fun onTick(millisUntilFinished: Long) {
                        val secondsLeft = (millisUntilFinished / 1000L) + 1
                        waitButton.text = "WAIT ${secondsLeft}s..."
                    }
                    override fun onFinish() {
                        waitButton.text = "+$EXTEND_MINUTES MIN (FINE)"
                        waitButton.isEnabled = true
                        waitButton.alpha = 1f
                    }
                }.start()
            }
        }
    }

    private fun outlinedButton(label: String, onClick: () -> Unit): Button = Button(context).apply {
        text = label
        setTextColor(COLOR_PAPER)
        setBackgroundColor(Color.TRANSPARENT)
        isAllCaps = true
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), COLOR_PAPER)
        }
        setOnClickListener { onClick() }
    }

    private fun loadMemeBitmap(assetRef: String): android.graphics.Bitmap? = try {
        context.assets.open("roast_pack_v1/$assetRef.webp").use { BitmapFactory.decodeStream(it) }
    } catch (t: Exception) {
        Log.w(TAG, "loadMemeBitmap failed for assetRef=$assetRef", t)
        null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
