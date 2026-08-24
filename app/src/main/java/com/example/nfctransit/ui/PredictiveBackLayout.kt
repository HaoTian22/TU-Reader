package com.example.nfctransit.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

class PredictiveBackLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val previousSnapshotView = snapshotView()
    private val currentSnapshotView = snapshotView()
    private var completionAnimator: Animator? = null
    private var forwardAnimator: ViewPropertyAnimator? = null
    private var liveCurrent = false
    private var liveNavHost: View? = null
    private val exitInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    override fun onFinishInflate() {
        super.onFinishInflate()
        addView(previousSnapshotView, 0, matchParentParams())
        addView(currentSnapshotView, matchParentParams())
    }

    fun showBackSnapshots(
        previous: Bitmap,
        current: Bitmap,
        navHost: View,
        keepCurrentLive: Boolean = false
    ) {
        forwardAnimator?.let {
            forwardAnimator = null
            it.cancel()
        }
        navHost.translationX = 0f
        liveCurrent = keepCurrentLive
        liveNavHost = if (keepCurrentLive) navHost else null
        previousSnapshotView.setImageBitmap(previous)
        previousSnapshotView.translationX = 0f
        previousSnapshotView.scaleX = 1f
        previousSnapshotView.scaleY = 1f
        currentSnapshotView.setImageBitmap(current)
        previousSnapshotView.visibility = View.VISIBLE
        currentSnapshotView.visibility = if (keepCurrentLive) View.GONE else View.VISIBLE
        navHost.visibility = if (keepCurrentLive) View.VISIBLE else View.INVISIBLE
    }

    fun showForwardSnapshot(previous: Bitmap, navHost: View) {
        liveCurrent = false
        liveNavHost = null
        previousSnapshotView.setImageBitmap(previous)
        previousSnapshotView.translationX = 0f
        previousSnapshotView.visibility = View.VISIBLE
        currentSnapshotView.visibility = View.GONE
        navHost.visibility = View.VISIBLE
        navHost.translationX = width.toFloat()
    }

    fun animateForwardSnapshot(navHost: View, onEnd: () -> Unit = {}) {
        forwardAnimator?.cancel()
        lateinit var animation: ViewPropertyAnimator
        animation = navHost.animate()
            .translationX(0f)
            .setDuration(180L)
            .setInterpolator(exitInterpolator)
            .withEndAction {
                if (forwardAnimator === animation) {
                    forwardAnimator = null
                    previousSnapshotView.setImageDrawable(null)
                    previousSnapshotView.visibility = View.GONE
                    navHost.translationX = 0f
                    onEnd()
                }
            }
        forwardAnimator = animation
        animation.start()
    }

    fun updateBackSnapshots(progress: Float, travel: Float) {
        previousSnapshotView.translationX = 0f
        if (liveCurrent) {
            liveNavHost?.translationX = travel * progress
        } else {
            currentSnapshotView.translationX = travel * progress
        }
    }

    fun completeBackSnapshots(travel: Float, onEnd: () -> Unit) {
        completionAnimator?.cancel()

        val currentAnimator = if (liveCurrent) {
            val liveView = requireNotNull(liveNavHost)
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(
                        liveView,
                        View.TRANSLATION_X,
                        liveView.translationX,
                        travel
                    )
                )
            }
        } else {
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(
                        currentSnapshotView,
                        View.TRANSLATION_X,
                        currentSnapshotView.translationX,
                        travel
                    )
                )
            }
        }
        val animation = AnimatorSet().apply {
            playTogether(currentAnimator)
            duration = 180L
            interpolator = exitInterpolator
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (completionAnimator === animation) {
                        completionAnimator = null
                        onEnd()
                    }
                }
            })
        }
        completionAnimator = animation
        animation.start()
    }

    fun cancelBackSnapshots(navHost: View, onEnd: () -> Unit = {}) {
        previousSnapshotView.animate()
            .translationX(0f)
            .setDuration(180L)
            .start()
        val currentAnimation = if (liveCurrent) {
            liveNavHost?.animate()
                ?.translationX(0f)
                ?.setDuration(180L)
        } else {
            currentSnapshotView.animate()
                .translationX(0f)
                .setDuration(180L)
        }
        currentAnimation
            ?.withEndAction {
                hideBackSnapshots(navHost)
                onEnd()
            }
            ?.start()
    }

    fun hideBackSnapshots(navHost: View) {
        completionAnimator?.let {
            completionAnimator = null
            it.cancel()
        }
        forwardAnimator?.let {
            forwardAnimator = null
            it.cancel()
        }
        navHost.animate().cancel()
        navHost.translationX = 0f
        liveNavHost?.translationX = 0f
        liveNavHost = null
        liveCurrent = false
        previousSnapshotView.animate().cancel()
        currentSnapshotView.animate().cancel()
        previousSnapshotView.setImageDrawable(null)
        currentSnapshotView.setImageDrawable(null)
        previousSnapshotView.visibility = View.GONE
        currentSnapshotView.visibility = View.GONE
        previousSnapshotView.translationX = 0f
        currentSnapshotView.translationX = 0f
        navHost.visibility = View.VISIBLE
    }

    private fun snapshotView() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }

    private fun matchParentParams() =
        ViewGroup.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
}
