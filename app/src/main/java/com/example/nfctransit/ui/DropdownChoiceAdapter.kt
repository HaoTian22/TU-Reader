package com.example.nfctransit.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.TextView
import com.example.nfctransit.R

/**
 * AutoCompleteTextView 候选适配器：列表显示 [label]，选中后输入框填入 [value]。
 * 筛选按 [keys] 做不区分大小写的子串匹配（ArrayAdapter 只按词首匹配，"(Shenzhen)" 输入 shen 匹配不到）；
 * [keys] 为 null 时不筛选，始终显示全部候选。
 */
class DropdownChoiceAdapter<T>(
    context: Context,
    private val items: List<T>,
    private val label: (T) -> String,
    private val value: (T) -> String = label,
    private val keys: ((T) -> List<String>)? = { listOf(label(it)) }
) : BaseAdapter(), Filterable {

    private val inflater = LayoutInflater.from(context)
    private var shown: List<T> = items

    override fun getCount() = shown.size
    override fun getItem(position: Int): T = shown[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = (convertView ?: inflater.inflate(R.layout.item_dropdown_option, parent, false)) as TextView
        view.text = label(shown[position])
        return view
    }

    private val filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.trim().orEmpty()
            val matched = if (query.isEmpty() || keys == null) items else items.filter { item ->
                keys.invoke(item).any { it.contains(query, ignoreCase = true) }
            }
            return FilterResults().apply { values = matched; count = matched.size }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            shown = (results?.values as? List<T>) ?: items
            if (shown.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
        }

        @Suppress("UNCHECKED_CAST")
        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? T)?.let(value) ?: ""
    }

    override fun getFilter(): Filter = filter
}
