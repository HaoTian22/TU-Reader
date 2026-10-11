package com.example.nfctransit.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.nfctransit.CardProfiles
import com.example.nfctransit.model.WalletBalance

/**
 * 多币种联名卡（澳门通/八达通 + TU 等）余额：各钱包并排等宽、同字号，地位相同。
 * 单钱包或同币种卡不走这里，仍显示单个大余额。
 */
internal object WalletBalanceViews {

    /** 币种符号缩小、金额大字；[symbolColor] 为 null 时与金额同色。 */
    fun amountText(wallet: WalletBalance, amountSp: Int, symbolSp: Int, symbolColor: Int? = null): CharSequence {
        val symbol = CardProfiles.currencySymbol(wallet.protocol)
        return SpannableStringBuilder(wallet.text).apply {
            setSpan(AbsoluteSizeSpan(symbolSp, true), 0, symbol.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            symbolColor?.let { setSpan(ForegroundColorSpan(it), 0, symbol.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            setSpan(AbsoluteSizeSpan(amountSp, true), symbol.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 往 [row]（水平 LinearLayout）填入每个钱包一列：钱包名 + 金额，列间细分隔线。 */
    fun fillColumns(
        row: LinearLayout,
        wallets: List<WalletBalance>,
        labelColor: Int,
        amountColor: Int,
        symbolColor: Int,
        dividerColor: Int
    ) {
        row.removeAllViews()
        val ctx = row.context
        val density = ctx.resources.displayMetrics.density
        wallets.forEachIndexed { index, wallet ->
            if (index > 0) {
                row.addView(View(ctx).apply { setBackgroundColor(dividerColor) },
                    LinearLayout.LayoutParams((1 * density).toInt().coerceAtLeast(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                        marginStart = (14 * density).toInt()
                        marginEnd = (14 * density).toInt()
                    })
            }
            val column = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            column.addView(TextView(ctx).apply {
                text = wallet.label
                setTextColor(labelColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                maxLines = 1
            })
            column.addView(TextView(ctx).apply {
                text = amountText(wallet, amountSp = 26, symbolSp = 15, symbolColor = symbolColor)
                setTextColor(amountColor)
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                includeFontPadding = false
                maxLines = 1
                setPadding(0, (4 * density).toInt(), 0, 0)
            })
            row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }
}
