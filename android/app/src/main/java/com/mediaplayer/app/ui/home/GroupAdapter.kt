/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.mediaplayer.app.R
import com.mediaplayer.app.data.model.ChannelGroup

/**
 * 分组列表适配器 - TV模式使用
 */
class GroupAdapter(
    private val onClick: (ChannelGroup) -> Unit,
    private val onFocus: ((ChannelGroup) -> Unit)? = null
) : ListAdapter<ChannelGroup, GroupAdapter.ViewHolder>(DiffCallback()) {

    private var selectedId = 0L

    fun setSelected(id: Long) {
        val old = selectedId
        selectedId = id
        currentList.forEachIndexed { index, group ->
            if (group.id == old || group.id == id) {
                notifyItemChanged(index, "selection_changed")
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains("selection_changed")) {
            val item = getItem(position)
            holder.bind(item, item.id == selectedId)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, item.id == selectedId)
        holder.itemView.setOnClickListener { onClick(item) }

        // TV 焦点与触控兼容处理
        holder.itemView.isFocusable = true
        holder.itemView.isFocusableInTouchMode = false

        // TV 焦点动画
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            android.util.Log.d("TV_FOCUS", "Group ${item.name} hasFocus: $hasFocus")
            // 当用遥控器选中某个分组时，联动防抖
            if (hasFocus) {
                onFocus?.invoke(item)
            }
            v.animate()
                .alpha(if (hasFocus) 1.0f else if (item.id == selectedId) 1.0f else 0.7f)
                .scaleX(if (hasFocus) 1.05f else 1.0f)
                .scaleY(if (hasFocus) 1.05f else 1.0f)
                .setDuration(120)
                .start()
        }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvGroupName)
        private val tvSource: TextView? = itemView.findViewById(R.id.tvGroupSource)
        private val indicator: View = itemView.findViewById(R.id.viewIndicator)

        fun bind(item: ChannelGroup, selected: Boolean) {
            val nameStr = item.name ?: ""
            val regex = Regex("^(.*)\\(([^)]+)\\)$")
            val match = regex.find(nameStr)
            
            if (match != null && tvSource != null) {
                tvName.text = match.groupValues[1].trim()
                tvSource.text = match.groupValues[2]
                tvSource.visibility = View.VISIBLE
            } else {
                tvName.text = nameStr
                tvSource?.visibility = View.GONE
            }
            
            tvName.isSelected = selected
            tvSource?.isSelected = selected
            indicator.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            itemView.alpha = if (selected) 1.0f else 0.7f
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<ChannelGroup>() {
        override fun areItemsTheSame(a: ChannelGroup, b: ChannelGroup) = a.id == b.id
        override fun areContentsTheSame(a: ChannelGroup, b: ChannelGroup) = a == b
    }
}
