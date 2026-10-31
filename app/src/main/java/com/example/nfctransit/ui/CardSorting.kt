package com.example.nfctransit.ui

import android.content.Context
import com.example.nfctransit.R
import com.example.nfctransit.data.prefs.CardSortMode
import com.example.nfctransit.model.UiCard
import java.text.Collator
import java.util.Locale

/**
 * 卡片自动排序：最近读取 / 卡名（中文按拼音）/ 余额，ascending 决定升降序；
 * 无余额的卡始终排在最后；手动模式保持原顺序
 */
fun List<UiCard>.sortedByMode(mode: CardSortMode, ascending: Boolean): List<UiCard> {
    fun <T> List<T>.directed() = if (ascending) this else asReversed().toList()
    return when (mode) {
        CardSortMode.MANUAL -> this
        CardSortMode.READ_TIME -> sortedBy { it.lastReadAt }.directed()
        CardSortMode.NAME -> {
            val collator = Collator.getInstance(Locale.CHINA)
            sortedWith(compareBy<UiCard, String>(collator) { it.name }.thenBy { it.lastFour }).directed()
        }
        CardSortMode.BALANCE -> {
            val (known, unknown) = partition { it.balanceFen != null }
            known.sortedBy { it.balanceFen }.directed() + unknown
        }
    }
}

fun CardSortMode.nameRes(): Int = when (this) {
    CardSortMode.MANUAL -> R.string.card_sort_manual
    CardSortMode.READ_TIME -> R.string.card_sort_read_time
    CardSortMode.NAME -> R.string.card_sort_name
    CardSortMode.BALANCE -> R.string.card_sort_balance
}

/** 排序方式的显示名；自动方式带升降序箭头 */
fun CardSortMode.label(context: Context, ascending: Boolean): String {
    val name = context.getString(nameRes())
    return if (this == CardSortMode.MANUAL) name else "$name ${if (ascending) "↑" else "↓"}"
}
