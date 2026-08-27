package com.arkarizdev.grudge.core.watcher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * T-106: the roast overlay — the payoff moment. Strictly READ-ONLY: it
 * displays a precomputed roast_payload row (line1/line2/asset) and never
 * computes anything itself, matching the precompute discipline from
 * tech plan §5/§7 — RoastEngine already did the work back at grant time.
 *
 * Restyled 2026-08-20 to match the Figma "02 · Roast Overlay / 03 ·
 * Negotiation №2" screenshot: "TIME'S UP · ROAST #N" eyebrow, a real
 * meme image box (animated GIF via ImageDecoder — user-sourced files in
 * assets/roast_memes/, see that folder's README; falls back to the
 * bundled roast_pack_v1 mascot WebP), a gray granted-vs-taken stats
 * line, and PER-EXTENSION VARIATION: the roast screen always shows the
 * same two buttons, but what "+5 MIN (WEAK)" does escalates —
 *   extension #1: the free one-tap (T-107 tier 1, unchanged);
 *   extension #2: the window swaps to the white/red NEGOTIATION screen
 *     ("TYPE IT OUT." — type the phrase word for word to unlock);
 *   extension #3+: the WAITING ROOM screen (countdown-gated unlock).
 * The friction tiers themselves are T-107's; this restyle moves them out
 * of inline roast-screen controls into their own full-screen phases.
 *
 * NO SHARE AFFORDANCE — this is a hard product requirement (PRD/tech plan:
 * "no share affordance on the roast overlay ever — sharing is success-side
 * only"), not an oversight. Do not add one here.
 */
class RoastOverlayController(private val context: Context) {
    companion object {
        private const val TAG = "GrudgeRoastOverlay"
        private const val EXTEND_MINUTES = 5
        private const val CONFIRM_PHRASE = "I am choosing to keep scrolling."
        private const val WAIT_TIMER_SECONDS = 15

        private const val COLOR_INK = 0xFF0D0D0D.toInt()
        private const val COLOR_PAPER = 0xFFFFFBF8.toInt()
        private const val COLOR_YELLOW = 0xFFFFE600.toInt()
        private const val COLOR_GRAY = 0xFF6B6B6B.toInt()
        private const val COLOR_RED = 0xFFFF2E00.toInt()

        /** Phrase match is forgiving on case and the trailing period — "word for word" shouldn't punish mobile autocorrect. */
        internal fun phraseMatches(typed: String?): Boolean {
            fun normalize(s: String) = s.trim().trimEnd('.').lowercase()
            return typed != null && normalize(typed) == normalize(CONFIRM_PHRASE)
        }
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null

    // Only live while a waiting-room countdown is on screen. Must be
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
     *   the ROASTING transition was detected at.
     * @param roastNumber lifetime roast count, for the "ROAST #N" eyebrow.
     * @param grantedMin what this session's LAST grant asked for.
     * @param takenMin real minutes since the session opened — the "Taken:"
     *   half of the stats line, computed by the caller from the Room row's
     *   openedAt so this stays a read-only view.
     * @param gifSource "GIPHY" or "BUNDLED" (tech plan §7c, T-208/T-209) —
     *   whatever roast_payload.gifSource says; read-only here, same as
     *   everything else this view displays.
     * @param gifLocalPath set only when gifSource == "GIPHY". The file may
     *   no longer exist (already GC'd) — memeSection() falls through to
     *   the bundled chain rather than trusting this blindly.
     */
    fun show(
        pkg: String,
        line1: String,
        line2: String,
        assetRef: String,
        eventTs: Long,
        extensionsSoFar: Int,
        roastNumber: Int,
        grantedMin: Int,
        takenMin: Int,
        gifSource: String,
        gifLocalPath: String?,
        onDone: () -> Unit,
        onExtend: (additionalMinutes: Int) -> Unit,
    ) {
        if (shownForPkg == pkg) return
        shownForPkg = pkg
        mainHandler.post {
            showOnMainThread(pkg, line1, line2, assetRef, eventTs, extensionsSoFar, roastNumber, grantedMin, takenMin, gifSource, gifLocalPath, onDone, onExtend)
        }
    }

    private fun showOnMainThread(
        pkg: String,
        line1: String,
        line2: String,
        assetRef: String,
        eventTs: Long,
        extensionsSoFar: Int,
        roastNumber: Int,
        grantedMin: Int,
        takenMin: Int,
        gifSource: String,
        gifLocalPath: String?,
        onDone: () -> Unit,
        onExtend: (Int) -> Unit,
    ) {
        removeCurrentViewOnMainThread() // NOT dismissViewOnMainThread() — must not clear shownForPkg mid-rebuild

        val root = buildRoastView(pkg, line1, line2, assetRef, extensionsSoFar, roastNumber, grantedMin, takenMin, gifSource, gifLocalPath, onDone, onExtend)

        windowManager.addView(root, roastLayoutParams())
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

    // ---------------------------------------------------------------------
    // Screen 1: the roast itself (Figma panel "02 · Roast Overlay")
    // ---------------------------------------------------------------------

    private fun buildRoastView(
        pkg: String,
        line1: String,
        line2: String,
        assetRef: String,
        extensionsSoFar: Int,
        roastNumber: Int,
        grantedMin: Int,
        takenMin: Int,
        gifSource: String,
        gifLocalPath: String?,
        onDone: () -> Unit,
        onExtend: (Int) -> Unit,
    ): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_INK)
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        root.addView(eyebrow("TIME'S UP  ·  ROAST #$roastNumber", COLOR_YELLOW))

        root.addView(View(context), LinearLayout.LayoutParams(0, 0, 0.3f))

        val memeBox = memeSection(
            "roast_memes/$assetRef.gif",
            fallbackAssetRef = assetRef,
            cachedGifPath = gifLocalPath.takeIf { gifSource == "GIPHY" },
        )
        memeBox?.let { root.addView(it.view) }
        if (memeBox?.fromCachedGif == true) root.addView(giphyAttributionRow())

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

        root.addView(TextView(context).apply {
            text = "Granted: $grantedMin min. Taken: $takenMin.\nMath sends its regards."
            setTextColor(COLOR_GRAY)
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
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

        root.addView(outlinedButton("+$EXTEND_MINUTES MIN (WEAK)", borderColor = COLOR_PAPER, textColor = COLOR_PAPER) {
            when {
                extensionsSoFar <= 0 -> {
                    // Extension #1: "one tap was the free one."
                    Log.i(TAG, "EXTEND tapped pkg=$pkg minutes=$EXTEND_MINUTES tier=1 friction=tap")
                    onExtend(EXTEND_MINUTES)
                    dismissViewOnMainThread()
                }
                extensionsSoFar == 1 -> swapTo(buildNegotiationView(pkg, extensionsSoFar, onExtend), focusable = true)
                else -> swapTo(buildWaitingRoomView(pkg, extensionsSoFar, onExtend), focusable = false)
            }
        })

        root.addView(TextView(context).apply {
            text = "No share button on roasts. That's the point."
            setTextColor(COLOR_GRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        return root
    }

    // ---------------------------------------------------------------------
    // Screen 2: typed-phrase negotiation (Figma panel "03 · Negotiation №2")
    // ---------------------------------------------------------------------

    private fun buildNegotiationView(pkg: String, extensionsSoFar: Int, onExtend: (Int) -> Unit): View {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        content.addView(eyebrow("EXTENSION #${extensionsSoFar + 1} OF ∞", COLOR_RED))
        content.addView(TextView(context).apply {
            text = "TYPE IT OUT."
            setTextColor(COLOR_INK)
            textSize = 34f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(12))
        })
        content.addView(TextView(context).apply {
            text = "One tap was the free one. Now extensions are earned. Type the phrase, word for word:"
            setTextColor(COLOR_GRAY)
            textSize = 15f
            setPadding(0, 0, 0, dp(16))
        })

        content.addView(TextView(context).apply {
            text = "“$CONFIRM_PHRASE”"
            setTextColor(COLOR_INK)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(3), COLOR_RED)
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        content.addView(eyebrow("GO ON.", COLOR_INK))
        val phraseField = EditText(context).apply {
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_GRAY)
            setSingleLine(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(dp(2), COLOR_INK)
            }
        }
        content.addView(phraseField, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6); bottomMargin = dp(16) })

        memeSection("roast_memes/negotiation.gif", fallbackAssetRef = null)?.let { content.addView(it.view) }

        content.addView(View(context), LinearLayout.LayoutParams(0, dp(24)))

        val unlockButton = Button(context).apply {
            text = "UNLOCK $EXTEND_MINUTES MORE MINUTES"
            setTextColor(Color.WHITE)
            setBackgroundColor(COLOR_RED)
            isAllCaps = true
            isEnabled = false
            alpha = 0.4f
            setOnClickListener {
                Log.i(TAG, "EXTEND tapped pkg=$pkg minutes=$EXTEND_MINUTES tier=2 friction=typed-phrase")
                onExtend(EXTEND_MINUTES)
                dismissViewOnMainThread()
            }
        }
        phraseField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val matches = phraseMatches(s?.toString())
                unlockButton.isEnabled = matches
                unlockButton.alpha = if (matches) 1f else 0.4f
            }
        })
        content.addView(unlockButton)

        content.addView(TextView(context).apply {
            text = "Extension #${extensionsSoFar + 2} comes with a waiting room."
            setTextColor(COLOR_GRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })

        // Scrollable: with the keyboard up (SOFT_INPUT_ADJUST_RESIZE) the
        // content must be reachable, not clipped behind the IME.
        return ScrollView(context).apply {
            setBackgroundColor(COLOR_PAPER)
            isFillViewport = true
            addView(content)
        }
    }

    // ---------------------------------------------------------------------
    // Screen 3: the waiting room (extension #3+)
    // ---------------------------------------------------------------------

    private fun buildWaitingRoomView(pkg: String, extensionsSoFar: Int, onExtend: (Int) -> Unit): View {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PAPER)
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        root.addView(eyebrow("EXTENSION #${extensionsSoFar + 1} OF ∞", COLOR_RED))
        root.addView(TextView(context).apply {
            text = "THE WAITING ROOM."
            setTextColor(COLOR_INK)
            textSize = 34f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(12))
        })
        root.addView(TextView(context).apply {
            text = "Typing was the earned one. Now extensions cost time. Sit with it."
            setTextColor(COLOR_GRAY)
            textSize = 15f
            setPadding(0, 0, 0, dp(16))
        })

        memeSection("roast_memes/waiting.gif", fallbackAssetRef = null)?.let { root.addView(it.view) }

        root.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))

        val waitButton = Button(context).apply {
            text = "WAIT ${WAIT_TIMER_SECONDS}s..."
            setTextColor(Color.WHITE)
            setBackgroundColor(COLOR_RED)
            isAllCaps = true
            isEnabled = false
            alpha = 0.4f
            setOnClickListener {
                Log.i(TAG, "EXTEND tapped pkg=$pkg minutes=$EXTEND_MINUTES tier=3 friction=wait-timer")
                onExtend(EXTEND_MINUTES)
                dismissViewOnMainThread()
            }
        }
        root.addView(waitButton)
        activeCountDownTimer = object : CountDownTimer(WAIT_TIMER_SECONDS * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                waitButton.text = "WAIT ${(millisUntilFinished / 1000L) + 1}s..."
            }
            override fun onFinish() {
                waitButton.text = "UNLOCK $EXTEND_MINUTES MORE MINUTES"
                waitButton.isEnabled = true
                waitButton.alpha = 1f
            }
        }.start()

        root.addView(TextView(context).apply {
            text = "It only gets slower from here."
            setTextColor(COLOR_GRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        return root
    }

    // ---------------------------------------------------------------------
    // Shared plumbing
    // ---------------------------------------------------------------------

    /**
     * Replaces the overlay window's content in place (roast -> negotiation/
     * waiting room). The window itself survives — only removeView/addView on
     * the same LayoutParams identity would lose the IME state, so this uses
     * a fresh addView with the right focusability: the negotiation screen
     * needs real keyboard input, and a NOT_FOCUSABLE window can never
     * receive it (the T-107 live-caught lesson).
     */
    private fun swapTo(view: View, focusable: Boolean) {
        // Deliberately does NOT cancel activeCountDownTimer here: [view] is
        // evaluated as this call's argument BEFORE this body runs, so if
        // [view] is the waiting room (which starts the timer as a side
        // effect of being built), cancelling here would kill it within
        // milliseconds of starting, before a single tick. Real teardown of
        // a running timer already happens correctly in
        // removeCurrentViewOnMainThread(), called on every path that
        // actually ends the overlay (done/unlock/dismiss).
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        windowManager.addView(view, if (focusable) focusableLayoutParams() else roastLayoutParams())
        overlayView = view
    }

    private fun roastLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        android.graphics.PixelFormat.OPAQUE,
    )

    private fun focusableLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        android.graphics.PixelFormat.OPAQUE,
    ).apply { softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE }

    private fun eyebrow(text: String, color: Int) = TextView(context).apply {
        this.text = text
        setTextColor(color)
        textSize = 13f
        letterSpacing = 0.15f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private data class MemeBoxResult(val view: View, val fromCachedGif: Boolean)

    /**
     * The meme box: a white-framed image section. Fallback chain, first
     * match wins:
     *   0. [cachedGifPath] — a live-fetched GIPHY GIF cached to filesDir at
     *      grant time (T-208/T-209). May be null (no key, fetch failed, or
     *      the file's since been GC'd) — never assumed to exist.
     *   1. [gifAssetPath] — the user-sourced bundled animated GIF
     *      (ImageDecoder animates multi-frame sources natively, API 28+;
     *      minSdk is 29).
     *   2. [fallbackAssetRef] — the bundled roast_pack_v1 mascot WebP.
     *   3. null (section hidden entirely) — a missing meme, at any tier,
     *      must never break the unlock flow.
     */
    private fun memeSection(gifAssetPath: String, fallbackAssetRef: String?, cachedGifPath: String? = null): MemeBoxResult? {
        val cachedDrawable = cachedGifPath?.let { loadDrawableFromFile(it) }
        val drawable = cachedDrawable
            ?: loadDrawable(gifAssetPath)
            ?: fallbackAssetRef?.let { ref ->
                try {
                    context.assets.open("roast_pack_v1/$ref.webp").use {
                        BitmapDrawable(context.resources, BitmapFactory.decodeStream(it))
                    }
                } catch (t: Exception) {
                    Log.w(TAG, "meme fallback failed for assetRef=$ref", t)
                    null
                }
            }
            ?: return null

        val frame = LinearLayout(context).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(
                ImageView(context).apply {
                    setImageDrawable(drawable)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    (drawable as? AnimatedImageDrawable)?.start()
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220)),
            )
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(frame, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(20) })
        }
        return MemeBoxResult(view, fromCachedGif = drawable === cachedDrawable)
    }

    private fun loadDrawable(assetPath: String): Drawable? = try {
        val source = ImageDecoder.createSource(context.assets, assetPath)
        ImageDecoder.decodeDrawable(source)
    } catch (_: Exception) {
        null
    }

    /** T-208/T-209: same null-on-any-failure shape as [loadDrawable] — a missing/deleted cache file must fall through silently, not crash. */
    private fun loadDrawableFromFile(path: String): Drawable? = try {
        val file = File(path)
        if (!file.exists()) null else ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))
    } catch (_: Exception) {
        null
    }

    /**
     * T-208: GIPHY's ToS requires "Powered by GIPHY" text + logo wherever
     * fetched content shows. Shown only when the meme box actually
     * rendered from a live-fetched GIF (memeSection's fromCachedGif flag),
     * not just because roast_payload.gifSource says GIPHY — a since-GC'd
     * cache file falls through to the bundled mascot, and attribution
     * must track what's truly on screen.
     *
     * Uses GIPHY's own official "Powered by GIPHY" lockup image (bundled
     * at giphy/attribution_badge.png, sourced from their brand-assets
     * portal — see assets/giphy/README.md) rather than recreating the
     * text+logo combo by hand: it's both the ToS-safest choice (their
     * exact approved artwork, not our approximation) and simpler code.
     * Falls back to a plain text line if the asset is ever missing —
     * same graceful-hide-on-missing-asset pattern as every other image
     * in this app, so a stale checkout without the file still ships a
     * ToS-compliant (if less polished) attribution rather than nothing.
     */
    private fun giphyAttributionRow(): View {
        val badge = loadDrawable("giphy/attribution_badge.png")
        if (badge != null) {
            val heightPx = dp(18)
            val widthPx = (heightPx * badge.intrinsicWidth / badge.intrinsicHeight.toFloat()).toInt()
            return ImageView(context).apply {
                setImageDrawable(badge)
                layoutParams = LinearLayout.LayoutParams(widthPx, heightPx).apply {
                    bottomMargin = dp(12)
                }
            }
        }
        return TextView(context).apply {
            text = "Powered by GIPHY"
            setTextColor(COLOR_GRAY)
            textSize = 10f
            setPadding(0, 0, 0, dp(12))
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

    private fun outlinedButton(label: String, borderColor: Int, textColor: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            setTextColor(textColor)
            isAllCaps = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), borderColor)
            }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
