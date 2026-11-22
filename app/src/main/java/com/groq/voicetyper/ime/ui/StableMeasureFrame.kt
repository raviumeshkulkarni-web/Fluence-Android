package com.groq.voicetyper.ime.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Pure measurement stabilization logic inspired by wmkeyboard's StableMeasureFrame.
 *
 * Android IME windows perform dual-pass measurement during layout traversal:
 * first offering AT_MOST wholeScreen, and then AT_MOST remainingSpace.
 * When Compose receives different constraints within the same traversal,
 * AndroidComposeView sets wasMeasuredWithMultipleConstraints = true,
 * forcing full window relayout on every single key press, release, and ripple.
 *
 * StableMeasureHelper ensures both measurement passes in a single traversal
 * see a consistent height constraint, preventing Compose jank and layout churn.
 */
class StableMeasureHelper {
    private var roomSize = 0
    private var freshTraversal = true
    private var firstOffer = 0
    private var ceiling = 0
    private var ceilingUnder = 0

    fun onShownAgain() {
        freshTraversal = true
        ceiling = 0
        ceilingUnder = 0
    }

    fun onLayoutFinished() {
        freshTraversal = true
    }

    fun stableHeightSpec(offered: Int, currentMeasuredHeight: Int): Int {
        if (MeasureSpecCompat.getMode(offered) != MeasureSpecCompat.AT_MOST) return offered
        val size = MeasureSpecCompat.getSize(offered)
        roomSize = when {
            freshTraversal -> {
                firstOffer = size
                if (size != ceilingUnder) ceiling = 0
                if (ceiling > 0) minOf(size, ceiling) else size
            }
            // Held below what was just reported: the window's real limit.
            size < roomSize && size < currentMeasuredHeight -> {
                ceiling = size
                ceilingUnder = firstOffer
                size
            }
            else -> maxOf(roomSize, size)
        }
        freshTraversal = false
        return MeasureSpecCompat.makeMeasureSpec(roomSize, MeasureSpecCompat.AT_MOST)
    }

    object MeasureSpecCompat {
        const val UNSPECIFIED = 0
        const val EXACTLY = 1 shl 30
        const val AT_MOST = 2 shl 30
        private const val MODE_MASK = 3 shl 30

        fun getMode(measureSpec: Int): Int = measureSpec and MODE_MASK
        fun getSize(measureSpec: Int): Int = measureSpec and MODE_MASK.inv()
        fun makeMeasureSpec(size: Int, mode: Int): Int = (size and MODE_MASK.inv()) or (mode and MODE_MASK)
    }
}

/**
 * Dual-pass layout stability wrapper FrameLayout wrapping ComposeView in VoiceInputIME.
 */
class StableMeasureFrame @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val helper = StableMeasureHelper()

    fun onShownAgain() {
        helper.onShownAgain()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, helper.stableHeightSpec(heightMeasureSpec, measuredHeight))
        setMeasuredDimension(measuredWidth, View.resolveSize(measuredHeight, heightMeasureSpec))
    }

    internal fun onLayoutFinished() {
        helper.onLayoutFinished()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        onLayoutFinished()
    }

    internal fun stableHeightSpec(offered: Int, currentMeasuredHeight: Int = measuredHeight): Int =
        helper.stableHeightSpec(offered, currentMeasuredHeight)
}
