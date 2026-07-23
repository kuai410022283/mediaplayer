/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.ui.home

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mediaplayer.app.R
import com.mediaplayer.app.data.model.EPGProgram
import java.text.SimpleDateFormat
import java.util.*

class EpgAdapter : RecyclerView.Adapter<EpgAdapter.ViewHolder>() {

    private val programs = mutableListOf<EPGProgram>()
    private var playingIndex = -1

    private var supportCatchup = false
    private var itemClickListener: ((EPGProgram) -> Unit)? = null
    private var activeProgramStartTime: String? = null

    fun setSupportCatchup(support: Boolean) {
        this.supportCatchup = support
    }
    
    fun setActiveProgramStartTime(startTime: String?) {
        this.activeProgramStartTime = startTime
    }

    fun setOnItemClickListener(listener: (EPGProgram) -> Unit) {
        this.itemClickListener = listener
    }

    fun setData(newPrograms: List<EPGProgram>) {
        programs.clear()
        programs.addAll(newPrograms)
        
        // 查找当前应该高亮的节目 (优先判断是否有主动选中的回看节目)
        playingIndex = -1
        if (activeProgramStartTime != null) {
            playingIndex = programs.indexOfFirst { it.startTime == activeProgramStartTime }
        }
        
        // 如果没有选中的回看节目，或者没找到，则使用当前时间判断直播节目
        val now = Date()
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val liveIndex = programs.indexOfFirst {
            try {
                val start = sdf.parse(it.startTime)
                val end = sdf.parse(it.endTime)
                start != null && end != null && now.after(start) && now.before(end)
            } catch (e: Exception) { false }
        }
        
        if (playingIndex == -1) {
            playingIndex = liveIndex
        }

        notifyDataSetChanged()
    }
    
    fun getPlayingIndex(): Int = playingIndex

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_epg, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val prog = programs[position]
        
        // format time from "2026-05-25T19:00:00Z" to "19:00"
        var timeStr = ""
        try {
            val sdfIn = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = sdfIn.parse(prog.startTime)
            if (date != null) {
                val sdfOut = SimpleDateFormat("HH:mm", Locale.getDefault())
                timeStr = sdfOut.format(date)
            }
        } catch (e: Exception) {
            timeStr = prog.startTime
        }
        
        holder.tvTime.text = timeStr
        holder.tvTitle.text = prog.title
        
        val isLiveProgram = position == programs.indexOfFirst {
            val now = Date()
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            try {
                val start = sdf.parse(it.startTime)
                val end = sdf.parse(it.endTime)
                start != null && end != null && now.after(start) && now.before(end)
            } catch (e: Exception) { false }
        }
        
        if (position == playingIndex) {
            holder.tvTime.setTextColor(Color.parseColor("#FFC107")) // accent color
            holder.tvTitle.setTextColor(Color.parseColor("#FFC107"))
            if (activeProgramStartTime != null) {
                holder.tvTitle.text = "${prog.title} (回看中)"
            } else {
                holder.tvTitle.text = "${prog.title} (正在播出)"
            }
            holder.ivCatchup.visibility = View.GONE
        } else if (position < (if (activeProgramStartTime != null) programs.size else playingIndex) && supportCatchup && !isLiveProgram) {
            // 这里判断是否是往期节目。如果是回看模式，且位置在当前直播节目前面，就显示回看图标
            val liveIdx = programs.indexOfFirst {
                val now = Date()
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                try {
                    val start = sdf.parse(it.startTime)
                    val end = sdf.parse(it.endTime)
                    start != null && end != null && now.after(start) && now.before(end)
                } catch (e: Exception) { false }
            }
            
            if (position < liveIdx || (liveIdx == -1 && position < programs.size)) {
                holder.tvTime.setTextColor(Color.WHITE)
                holder.tvTitle.setTextColor(Color.parseColor("#DDDDDD"))
                holder.tvTitle.text = prog.title
                holder.ivCatchup.visibility = View.VISIBLE
            } else {
                holder.tvTime.setTextColor(Color.WHITE)
                holder.tvTitle.setTextColor(Color.parseColor("#DDDDDD"))
                holder.tvTitle.text = prog.title
                holder.ivCatchup.visibility = View.GONE
            }
        } else {
            holder.tvTime.setTextColor(Color.WHITE)
            holder.tvTitle.setTextColor(Color.parseColor("#DDDDDD"))
            holder.tvTitle.text = prog.title
            holder.ivCatchup.visibility = View.GONE
        }
        
        // 点击事件
        holder.itemView.setOnClickListener {
            val liveIdx = programs.indexOfFirst {
                val now = Date()
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                try {
                    val start = sdf.parse(it.startTime)
                    val end = sdf.parse(it.endTime)
                    start != null && end != null && now.after(start) && now.before(end)
                } catch (e: Exception) { false }
            }
            if ((position < liveIdx || (liveIdx == -1 && position < programs.size)) && supportCatchup) {
                itemClickListener?.invoke(prog)
            }
        }
        
        // 初始化当前状态
        holder.tvTitle.isSelected = holder.itemView.hasFocus()
        
        // 关键：当条目获取焦点时，设置 isSelected=true 以激活跑马灯 (Marquee) 效果
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            holder.tvTitle.isSelected = hasFocus
        }
    }

    override fun getItemCount() = programs.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvEpgTime)
        val tvTitle: TextView = view.findViewById(R.id.tvEpgTitle)
        val ivCatchup: android.widget.ImageView = view.findViewById(R.id.ivCatchup)
    }
}
