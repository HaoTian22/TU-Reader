package com.example.nfctransit.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * 轻量 FlowLayout：子 View 按行排布，一行放不下时自动换到下一行（类似 flex-wrap: wrap）。
 * 用于交易行的药丸列表（城市/类型/线路），任一个药丸超宽就整个换行。
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val verticalGap = (4 * resources.displayMetrics.density).toInt()

    override fun generateLayoutParams(attrs: AttributeSet?): ViewGroup.LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: ViewGroup.LayoutParams): ViewGroup.LayoutParams =
        MarginLayoutParams(p)

    override fun checkLayoutParams(p: ViewGroup.LayoutParams): Boolean = p is MarginLayoutParams

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        var maxLineWidth = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val childW = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childH = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x + childW > width - paddingRight && x > paddingLeft) {
                y += lineHeight + verticalGap
                x = paddingLeft
                lineHeight = 0
            }
            x += childW
            lineHeight = maxOf(lineHeight, childH)
            maxLineWidth = maxOf(maxLineWidth, x)
        }
        val totalHeight = y + lineHeight + paddingBottom
        val desiredWidth = maxOf(maxLineWidth + paddingRight, paddingLeft + paddingRight)
        setMeasuredDimension(
            when (widthMode) {
                MeasureSpec.AT_MOST -> minOf(desiredWidth, width)
                MeasureSpec.EXACTLY -> width
                else -> desiredWidth
            },
            resolveSize(totalHeight, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val mLeft = lp.leftMargin
            val mTop = lp.topMargin
            val mRight = lp.rightMargin
            val childW = child.measuredWidth
            val childH = child.measuredHeight
            if (x + mLeft + childW + mRight > width - paddingRight && x > paddingLeft) {
                y += lineHeight + verticalGap
                x = paddingLeft
                lineHeight = 0
            }
            child.layout(x + mLeft, y + mTop, x + mLeft + childW, y + mTop + childH)
            x += mLeft + childW + mRight
            lineHeight = maxOf(lineHeight, mTop + childH + lp.bottomMargin)
        }
    }
}
