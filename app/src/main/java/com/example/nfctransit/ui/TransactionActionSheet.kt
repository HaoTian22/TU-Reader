package com.example.nfctransit.ui

import com.example.nfctransit.R
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.nfctransit.model.TransitDirection
import com.example.nfctransit.model.UiTransaction
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 交易行长按后的底部抽屉：上方是这条交易的摘要（图标 / 站名 / 城市·类型·线路 / 日期时间 / 金额与余额），
 * 下方是操作列表。[actions] 依次排成带图标的行，点击后先收起抽屉再执行。
 */
object TransactionActionSheet {

    class Action(val icon: String, val label: String, val onClick: () -> Unit)

    fun show(context: Context, txn: UiTransaction, accentColor: Int, actions: List<Action>) {
        val dialog = BottomSheetDialog(context)
        val fa = Typeface.createFromAsset(context.assets, "fonts/fa-solid-900.otf")
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                val r = 24f * density
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Color.WHITE)
            }
            setPadding(0, dp(10), 0, dp(12))
        }

        // 拖动条
        sheet.addView(View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 2f * density
                setColor(Palette.LINE)
            }
        }, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        sheet.addView(summary(context, txn, fa, ::dp))

        sheet.addView(View(context).apply { setBackgroundColor(Palette.LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(16)
                bottomMargin = dp(6)
                marginStart = dp(20)
                marginEnd = dp(20)
            })

        val tile = ColorUtils.blendARGB(Color.WHITE, accentColor, 0.12f)
        actions.forEach { action ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), 0, dp(20), 0)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    action.onClick()
                }
            }
            row.addView(TextView(context).apply {
                typeface = fa
                text = action.icon
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(accentColor)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(tile)
                }
            }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(14) })
            row.addView(TextView(context).apply {
                text = action.label
                textSize = 15f
                setTextColor(Palette.INK)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(context).apply {
                typeface = fa
                text = ""  // fa-chevron-right
                textSize = 12f
                setTextColor(Palette.INK_3)
            })
            sheet.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        }

        // 底部手势条/导航栏让位
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, dp(12) + bottom)
            insets
        }

        dialog.setContentView(sheet)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.setOnShowListener {
            // 去掉 Material 默认容器底色，露出自绘的圆角
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        sheet.applyTouchFeedback()
        dialog.show()
    }

    /** 摘要：图标圆 + 站名（出入站箭头）/ 城市·类型·线路 / 日期时间，右侧金额与交易后余额 */
    private fun summary(context: Context, txn: UiTransaction, fa: Typeface, dp: (Int) -> Int): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }

        // 圆底需先有 GradientDrawable，applyTransitIcon 只给已有圆底着色
        val circle = FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }
        val icon = TextView(context).apply {
            typeface = fa
            text = txn.icon
            textSize = 18f
            gravity = Gravity.CENTER
        }
        circle.addView(icon, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Palette.applyTransitIcon(icon, circle, txn.transitType)
        row.addView(circle, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) })

        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        when (txn.direction) {
            TransitDirection.ENTRY, TransitDirection.EXIT -> titleRow.addView(TextView(context).apply {
                typeface = fa
                // 入站 = U+F090 箭头进框，出站 = U+F08B 箭头出框
                text = if (txn.direction == TransitDirection.ENTRY) "" else ""
                textSize = 13f
                setTextColor(Palette.INK_3)
                setPadding(0, 0, dp(6), 0)
            })
            else -> Unit
        }
        titleRow.addView(TextView(context).apply {
            text = TransitLabels.station(txn.stationName.ifBlank { txn.transitType })
                .ifBlank { context.getString(R.string.unknown) }
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.INK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(titleRow)

        val meta = listOf(txn.cityName, txn.transitType, txn.lineName)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "-" && it != "—" && it != txn.stationName }
            .distinct()
            .map { TransitLabels.type(TransitLabels.city(it)) }
            .joinToString(" · ")
        if (meta.isNotEmpty()) col.addView(TextView(context).apply {
            text = meta
            textSize = 13f
            setTextColor(Palette.INK_2)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, 0)
        })
        col.addView(TextView(context).apply {
            text = "${txn.date} ${txn.time}".trim()
            textSize = 12f
            setTextColor(Palette.INK_3)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        right.addView(TextView(context).apply {
            text = txn.amountText
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Palette.amountColor(txn.amountText))
        })
        txn.balanceAfterText?.let { balance ->
            right.addView(TextView(context).apply {
                text = balance
                textSize = 12f
                setTextColor(Palette.INK_3)
                setPadding(0, dp(3), 0, 0)
            })
        }
        row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12)
        })
        return row
    }

    /** 复制用的纯文本摘要 */
    fun plainText(txn: UiTransaction): String = listOf(
        "${txn.date} ${txn.time}".trim(),
        listOf(TransitLabels.city(txn.cityName), TransitLabels.type(txn.transitType), txn.lineName, TransitLabels.station(txn.stationName))
            .map { it.trim() }.filter { it.isNotEmpty() && it != "-" && it != "—" }.distinct().joinToString(" · "),
        listOfNotNull(txn.amountText, txn.balanceAfterText).joinToString("  ")
    ).filter { it.isNotBlank() }.joinToString("\n")
}
