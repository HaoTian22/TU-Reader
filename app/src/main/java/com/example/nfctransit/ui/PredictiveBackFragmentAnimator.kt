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

    /**
     * 按回栈条目（[androidx.navigation.NavBackStackEntry.id]）而非目的地 ID 缓存页面截图：
     * 同一目的地可在栈中出现多次（如 卡A概览 → 交易A → 卡B概览），按目的地存会互相覆盖，返回时露出别的卡。
     */
    private val snapshots = LinkedHashMap<String, Bitmap>(4, 0.75f, true)
    private var pendingForwardSnapshot: Bitmap? = null
    private var gestureActive = false
    private var committing = false
    private var currentSnapshot: Bitmap? = null
    /** 非空时本次返回一路退到该目的地（长按返回键回首页），否则只退一级 */
    private var popTargetId: Int? = null

    init {
        navController.addOnDestinationChangedListener(this)
    }

    override fun onDestinationChanged(
        controller: NavController,
        destination: NavDestination,
        arguments: android.os.Bundle?
    ) {
        isEnabled = controller.previousBackStackEntry != null
        pruneSnapshots()
        val forwardSnapshot = pendingForwardSnapshot
        pendingForwardSnapshot = null
        val entryId = controller.currentBackStackEntry?.id
        // 同步把新页面移出屏幕并铺上旧页截图：若推迟到 post 里做，低端机会先画出一帧未偏移的新页面（闪一下）
        val animateForward = forwardSnapshot != null && !committing
        if (animateForward) container.showForwardSnapshot(forwardSnapshot!!, navHostView)
        navHostView.post {
            if (entryId != null && navController.currentBackStackEntry?.id == entryId) captureCurrent(entryId)
            if (animateForward) {
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
        popTargetId = null
        val currentView = currentView() ?: return
        val current = captureView(currentView) ?: return
        val previousId = navController.previousBackStackEntry?.id
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
        // 旧版 Android / 三键导航不会回调 handleOnBackStarted：这里补上截图，同样播放返回动画
        if (!gestureActive) showBackSnapshotsFor(popTargetId)
        committing = true
        isEnabled = false
        if (!gestureActive) {
            if (!popBack()) {
                committing = false
                isEnabled = navController.previousBackStackEntry != null
            }
            return
        }

        val travel = container.width.toFloat()
        container.completeBackSnapshots(travel) {
            gestureActive = false
            if (!popBack()) {
                container.hideBackSnapshots(navHostView)
                clearGestureState()
                committing = false
                isEnabled = navController.previousBackStackEntry != null
            }
        }
    }

    fun startBackNavigation() = startBackNavigation(targetId = null)

    /** 直接返回到栈中的 [destinationId]（如长按返回键回首页），转场底图用该页的截图 */
    fun startBackNavigationTo(destinationId: Int) {
        if (navController.currentDestination?.id == destinationId) return
        if (runCatching { navController.getBackStackEntry(destinationId) }.isFailure) return
        startBackNavigation(targetId = destinationId)
    }

    private fun startBackNavigation(targetId: Int?) {
        if (!isEnabled || committing || gestureActive) return
        popTargetId = targetId
        handleOnBackPressed()
    }

    /** 铺好返回转场的截图（底图为目标条目的截图）；没有底图可做转场时返回 false，调用方直接返回 */
    private fun showBackSnapshotsFor(targetId: Int?): Boolean {
        val targetEntryId = if (targetId == null) {
            navController.previousBackStackEntry?.id
        } else {
            runCatching { navController.getBackStackEntry(targetId).id }.getOrNull()
        }
        val previous = targetEntryId?.let { snapshots[it] }
        val current = previous?.let { currentView()?.let(::captureView) }
        if (previous == null || current == null) {
            current?.recycleIfNeeded()
            return false
        }
        currentSnapshot = current
        container.showBackSnapshots(
            previous,
            current,
            navHostView,
            keepCurrentLive = isMapDestination()
        )
        gestureActive = true
        return true
    }

    private fun popBack(): Boolean {
        val target = popTargetId
        popTargetId = null
        return if (target != null) navController.popBackStack(target, false) else navController.popBackStack()
    }

    fun captureCurrentForNavigation() {
        val entryId = navController.currentBackStackEntry?.id ?: return
        currentView()?.let { view ->
            captureView(view)?.let { bitmap ->
                storeSnapshot(entryId, bitmap)
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

    private fun captureCurrent(entryId: String) {
        val view = currentView() ?: return
        captureView(view)?.let { bitmap -> storeSnapshot(entryId, bitmap) }
    }

    /** 丢弃已出栈条目的截图 */
    private fun pruneSnapshots() {
        val live = navController.currentBackStack.value.mapTo(HashSet()) { it.id }
        val iterator = snapshots.entries.iterator()
        while (iterator.hasNext()) {
            val (id, bitmap) = iterator.next()
            if (id in live) continue
            iterator.remove()
            if (bitmap !== pendingForwardSnapshot && bitmap !== currentSnapshot) bitmap.recycleIfNeeded()
        }
    }

    private fun storeSnapshot(entryId: String, bitmap: Bitmap) {
        snapshots.put(entryId, bitmap)?.let { previous ->
            if (previous !== pendingForwardSnapshot) previous.recycleIfNeeded()
        }
        while (snapshots.size > 4) {
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
