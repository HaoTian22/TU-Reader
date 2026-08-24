package com.example.nfctransit.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import com.example.nfctransit.R
import java.util.LinkedHashMap

class PredictiveBackFragmentAnimator(
    private val navController: NavController,
    private val navHostFragment: NavHostFragment,
    private val container: PredictiveBackLayout,
    private val navHostView: View
) : OnBackPressedCallback(false), NavController.OnDestinationChangedListener {

    private val snapshots = LinkedHashMap<Int, Bitmap>(4, 0.75f, true)
    private var pendingForwardSnapshot: Bitmap? = null
    private var gestureActive = false
    private var committing = false
    private var currentSnapshot: Bitmap? = null

    init {
        navController.addOnDestinationChangedListener(this)
    }

    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: android.os.Bundle?
    ) {
        isEnabled = controller.previousBackStackEntry != null
        val forwardSnapshot = pendingForwardSnapshot
        pendingForwardSnapshot = null
        navHostView.post {
            captureCurrent(destination.id)
            if (forwardSnapshot != null && !committing) {
                container.showForwardSnapshot(forwardSnapshot, navHostView)
                container.animateForwardSnapshot(navHostView) {
                    releaseForwardSnapshot(forwardSnapshot)
                }
            } else {
                forwardSnapshot?.let(::releaseForwardSnapshot)
            }
        }
        if (committing) {
            navHostView.post {
                container.hideBackSnapshots(navHostView)
                clearGestureState()
                committing = false
            }
        }
    }

    override fun handleOnBackStarted(backEvent: BackEventCompat) {
        if (!isEnabled) return
        val currentView = currentView() ?: return
        val current = captureView(currentView) ?: return
        val previousId = navController.previousBackStackEntry?.destination?.id
        val previous = previousId?.let { snapshots[it] } ?: run {
            current.recycleIfNeeded()
            return
        }
        currentSnapshot = current
        container.showBackSnapshots(
            previous,
            current,
            navHostView,
            keepCurrentLive = isMapDestination()
        )
        gestureActive = true
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) {
        if (!gestureActive) return
        val progress = backEvent.progress.coerceIn(0f, 1f)
        val travel = container.width.toFloat()
        container.updateBackSnapshots(progress, travel)
    }

    override fun handleOnBackCancelled() {
        if (!gestureActive) return
        container.cancelBackSnapshots(navHostView) { clearGestureState() }
    }

    override fun handleOnBackPressed() {
        if (committing) return
        committing = true
        isEnabled = false
        if (!gestureActive) {
            if (!navController.popBackStack()) {
                committing = false
                isEnabled = navController.previousBackStackEntry != null
            }
            return
        }

        val travel = container.width.toFloat()
        container.completeBackSnapshots(travel) {
            gestureActive = false
            if (!navController.popBackStack()) {
                container.hideBackSnapshots(navHostView)
                clearGestureState()
                committing = false
                isEnabled = navController.previousBackStackEntry != null
            }
        }
    }

    fun startBackNavigation() {
        if (!isEnabled || committing || gestureActive) return
        val current = currentView()?.let(::captureView) ?: return
        val previousId = navController.previousBackStackEntry?.destination?.id
        val previous = previousId?.let { snapshots[it] } ?: run {
            current.recycleIfNeeded()
            return
        }
        currentSnapshot = current
        container.showBackSnapshots(
            previous,
            current,
            navHostView,
            keepCurrentLive = isMapDestination()
        )
        gestureActive = true
        handleOnBackPressed()
    }

    fun captureCurrentForNavigation() {
        val destinationId = navController.currentDestination?.id ?: return
        currentView()?.let { view ->
            captureView(view)?.let { bitmap ->
                storeSnapshot(destinationId, bitmap)
                pendingForwardSnapshot = bitmap
            }
        }
    }

    fun dispose() {
        navController.removeOnDestinationChangedListener(this)
        remove()
        container.hideBackSnapshots(navHostView)
        pendingForwardSnapshot?.let { bitmap ->
            if (!snapshots.containsValue(bitmap)) bitmap.recycleIfNeeded()
        }
        pendingForwardSnapshot = null
        snapshots.values.forEach { bitmap -> bitmap.recycleIfNeeded() }
        snapshots.clear()
        clearGestureState()
    }

    private fun captureCurrent(destinationId: Int) {
        val view = currentView() ?: return
        captureView(view)?.let { bitmap -> storeSnapshot(destinationId, bitmap) }
    }

    private fun storeSnapshot(destinationId: Int, bitmap: Bitmap) {
        snapshots.put(destinationId, bitmap)?.let { previous ->
            if (previous !== pendingForwardSnapshot) previous.recycleIfNeeded()
        }
        while (snapshots.size > 3) {
            val eldest = snapshots.entries.iterator().next()
            snapshots.remove(eldest.key)
            if (eldest.value !== pendingForwardSnapshot) {
                eldest.value.recycleIfNeeded()
            }
        }
    }

    private fun releaseForwardSnapshot(bitmap: Bitmap) {
        if (pendingForwardSnapshot === bitmap) {
            pendingForwardSnapshot = null
        }
        if (!snapshots.containsValue(bitmap)) {
            bitmap.recycleIfNeeded()
        }
    }

    private fun isMapDestination(): Boolean =
        navController.currentDestination?.id == R.id.mapTraceFragment

    private fun currentView(): View? =
        navHostFragment.childFragmentManager.primaryNavigationFragment?.view

    private fun captureView(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return try {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun clearGestureState() {
        gestureActive = false
        currentSnapshot?.recycleIfNeeded()
        currentSnapshot = null
    }

    private fun Bitmap.recycleIfNeeded() {
        if (!isRecycled) recycle()
    }
}
