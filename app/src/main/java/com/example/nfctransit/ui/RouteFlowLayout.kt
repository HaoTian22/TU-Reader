package com.example.nfctransit.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/**
 * 路线站点的自动换行容器。箭头会和它后面的站点保持在同一行，避免箭头孤立在行尾。
 */
class RouteFlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val lineSpacing = (4f * resources.displayMetrics.density).toInt()

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams = LayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams): Boolean = true

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE / 4
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        val childWidthSpec = MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST)
        val childHeightSpec = getChildMeasureSpec(
            heightMeasureSpec,
            paddingTop + paddingBottom,
            LayoutParams.WRAP_CONTENT
        )

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility != View.GONE) child.measure(childWidthSpec, childHeightSpec)
        }

        // 若“箭头 + 下一站”整体过宽，先收窄站点视图，让站名在站点内部换行。
        for (index in 0 until childCount - 1) {
            val arrow = getChildAt(index)
            val station = getChildAt(index + 1)
            if (arrow.visibility != View.GONE &&
                station.visibility != View.GONE &&
                arrow.tag == KEEP_WITH_NEXT_TAG &&
                arrow.measuredWidth + station.measuredWidth > availableWidth
            ) {
                val stationWidth = (availableWidth - arrow.measuredWidth).coerceAtLeast(0)
                station.measure(
                    MeasureSpec.makeMeasureSpec(stationWidth, MeasureSpec.AT_MOST),
                    childHeightSpec
                )
            }
        }

        var lineWidth = 0
        var lineHeight = 0
        var measuredContentWidth = 0
        var measuredContentHeight = 0
        var index = 0
        while (index < childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) {
                index++
                continue
            }
            val keepNext = child.tag == KEEP_WITH_NEXT_TAG &&
                index + 1 < childCount && getChildAt(index + 1).visibility != View.GONE
            val next = if (keepNext) getChildAt(index + 1) else null
            val unitWidth = child.measuredWidth + (next?.measuredWidth ?: 0)
            val unitHeight = max(child.measuredHeight, next?.measuredHeight ?: 0)

            if (lineWidth > 0 && lineWidth + unitWidth > availableWidth) {
                measuredContentWidth = max(measuredContentWidth, lineWidth)
                measuredContentHeight += lineHeight + lineSpacing
                lineWidth = 0
                lineHeight = 0
            }
            lineWidth += unitWidth
            lineHeight = max(lineHeight, unitHeight)
            index += if (keepNext) 2 else 1
        }
        measuredContentWidth = max(measuredContentWidth, lineWidth)
        measuredContentHeight += lineHeight

        setMeasuredDimension(
            resolveSize(measuredContentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(measuredContentHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = paddingLeft
        var y = paddingTop
        var lineHeight = 0
        var index = 0
        while (index < childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) {
                index++
                continue
            }
            val keepNext = child.tag == KEEP_WITH_NEXT_TAG &&
                index + 1 < childCount && getChildAt(index + 1).visibility != View.GONE
            val next = if (keepNext) getChildAt(index + 1) else null
            val unitWidth = child.measuredWidth + (next?.measuredWidth ?: 0)
            val unitHeight = max(child.measuredHeight, next?.measuredHeight ?: 0)

            if (x > paddingLeft && x - paddingLeft + unitWidth > availableWidth) {
                x = paddingLeft
                y += lineHeight + lineSpacing
                lineHeight = 0
            }
            val childTop = y + (unitHeight - child.measuredHeight) / 2
            child.layout(x, childTop, x + child.measuredWidth, childTop + child.measuredHeight)
            x += child.measuredWidth
            if (next != null) {
                val nextTop = y + (unitHeight - next.measuredHeight) / 2
                next.layout(x, nextTop, x + next.measuredWidth, nextTop + next.measuredHeight)
                x += next.measuredWidth
            }
            lineHeight = max(lineHeight, unitHeight)
            index += if (keepNext) 2 else 1
        }
    }

    companion object {
        const val KEEP_WITH_NEXT_TAG = "route_keep_with_next"
    }
}

/** 站名和线路药丸；宽度不足时只让站名换行，线路药丸保持单行。 */
internal class RouteStationChipLayout(context: Context) : ViewGroup(context) {

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val availableWidth = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE / 4
        } else {
            (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        val childHeightSpec = getChildMeasureSpec(
            heightMeasureSpec,
            paddingTop + paddingBottom,
            LayoutParams.WRAP_CONTENT
        )
        val name = getChildAt(0)
        val pill = getChildAt(1)

        pill?.measure(
            MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
            childHeightSpec
        )
        val pillWidth = pill?.measuredWidth ?: 0
        name?.measure(
            MeasureSpec.makeMeasureSpec((availableWidth - pillWidth).coerceAtLeast(0), MeasureSpec.AT_MOST),
            childHeightSpec
        )

        val contentWidth = (name?.measuredWidth ?: 0) + pillWidth
        val contentHeight = max(name?.measuredHeight ?: 0, pill?.measuredHeight ?: 0)
        setMeasuredDimension(
            resolveSize(contentWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(contentHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        var x = paddingLeft
        val contentHeight = bottom - top - paddingTop - paddingBottom
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            val childTop = paddingTop + (contentHeight - child.measuredHeight) / 2
            child.layout(x, childTop, x + child.measuredWidth, childTop + child.measuredHeight)
            x += child.measuredWidth
        }
    }
}
