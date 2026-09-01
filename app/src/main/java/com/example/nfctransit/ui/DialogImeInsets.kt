package com.example.nfctransit.ui

import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

/** Applies IME-aware sizing to the flexible content area of a floating dialog. */
object DialogImeInsets {
    fun install(window: Window, content: View, flexibleContent: View): () -> Unit {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val density = content.resources.displayMetrics.density
        val minimumFlexibleHeight = (64 * density).toInt()
        var preferredFlexibleHeight = 0
        var latestInsets: WindowInsetsCompat? = null
        var applyingLayout = false
        val baseWindowY = window.attributes.y

        fun updateFlexibleHeight() {
            val insets = latestInsets ?: return
            if (applyingLayout || content.height == 0 || flexibleContent.height == 0) return

            if (preferredFlexibleHeight == 0) {
                preferredFlexibleHeight = flexibleContent.height
            }
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = max(systemBars.bottom, ime.bottom)
            val targetWindowY = baseWindowY + (systemBars.top - bottomInset) / 2
            if (window.attributes.y != targetWindowY) {
                window.attributes = window.attributes.apply { y = targetWindowY }
            }
            val fixedHeight = (content.height - flexibleContent.height).coerceAtLeast(0)
            val availableFlexibleHeight = (
                content.resources.displayMetrics.heightPixels -
                    systemBars.top -
                    bottomInset -
                    fixedHeight
                ).coerceAtLeast(minimumFlexibleHeight)
            val targetHeight = minOf(preferredFlexibleHeight, availableFlexibleHeight)
            if (flexibleContent.layoutParams.height == targetHeight) return

            applyingLayout = true
            flexibleContent.layoutParams = flexibleContent.layoutParams.apply {
                height = targetHeight
            }
            flexibleContent.requestLayout()
            applyingLayout = false
        }

        val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateFlexibleHeight()
        }
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            latestInsets = insets
            updateFlexibleHeight()
            insets
        }
        content.addOnLayoutChangeListener(layoutListener)
        ViewCompat.requestApplyInsets(content)
        content.post {
            WindowInsetsControllerCompat(window, content).show(WindowInsetsCompat.Type.ime())
        }

        return {
            ViewCompat.setOnApplyWindowInsetsListener(content, null)
            content.removeOnLayoutChangeListener(layoutListener)
            window.attributes = window.attributes.apply { y = baseWindowY }
        }
    }
}
