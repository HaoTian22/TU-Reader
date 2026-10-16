package com.example.nfctransit.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.example.nfctransit.R

/**
 * M3 分段列表容器：每个可见子行各自成一块白底（行间 [GAP_DP] 间隙露出页面底色），
 * 组外侧圆角 [OUTER_RADIUS_DP]、组内相邻处 [INNER_RADIUS_DP]。
 * 首/末行按当前可见子视图在每次测量前重算，行显隐变化后形状仍正确。
 * 不用 clipToOutline 裁圆角：返回手势的页面快照是软件绘制，不支持轮廓裁剪，会露出直角。
 * tag 为 [EXCLUDE_TAG] 的子视图（空状态文字等）不画底色、不参与首末判断（XML 写 android:tag="segment_exclude"）。
 */
class SegmentedListLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    init {
        orientation = VERTICAL
        background = null
        dividerDrawable = GradientDrawable().apply { setSize(0, dp(GAP_DP)) }  // 透明间隙
        showDividers = SHOW_DIVIDER_MIDDLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateSegments()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun updateSegments() {
        val rows = (0 until childCount).map(::getChildAt)
            .filter { it.visibility != View.GONE && it.tag != EXCLUDE_TAG }
        rows.forEachIndexed { i, row ->
            val first = i == 0
            val last = i == rows.lastIndex
            val key = (if (first) 1 else 0) or (if (last) 2 else 0)
            if (row.getTag(R.id.segmentShape) == key) return@forEachIndexed
            row.setTag(R.id.segmentShape, key)
            row.background = segmentShape(context, first, last, Palette.SURFACE)
            // 水波纹蒙版跟随分段形状（TouchFeedback 只在首次处理时取一次背景形状）
            if (row.isClickable) {
                row.foreground = RippleDrawable(
                    ColorStateList.valueOf(RIPPLE_COLOR), null,
                    segmentShape(context, first, last, Color.WHITE)
                )
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXCLUDE_TAG = "segment_exclude"
        const val GAP_DP = 2
        const val OUTER_RADIUS_DP = 16
        const val INNER_RADIUS_DP = 4

        /** 分段行底色形状：首行圆上角、末行圆下角（外侧大圆角），组内相邻处小圆角 */
        fun segmentShape(context: Context, first: Boolean, last: Boolean, color: Int) = GradientDrawable().apply {
            val density = context.resources.displayMetrics.density
            val top = (if (first) OUTER_RADIUS_DP else INNER_RADIUS_DP) * density
            val bottom = (if (last) OUTER_RADIUS_DP else INNER_RADIUS_DP) * density
            cornerRadii = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            setColor(color)
        }
    }
}
