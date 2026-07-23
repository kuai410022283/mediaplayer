/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.ui.widget

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 强制跑马灯效果的 TextView
 * 原生 TextView 跑马灯存在焦点缺陷（必须真正获得焦点才会滚动）。
 * 此类重写了 isFocused()，在被选中 (isSelected = true) 时欺骗系统使其始终认为拥有焦点，
 * 从而保证即使是在 RecyclerView 的条目内（条目容器获取焦点，而 TextView 仅被置为 Selected）
 * 也能完美触发跑马灯效果。
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    override fun isFocused(): Boolean {
        // 如果设置为 selected，强制返回 true 欺骗系统触发跑马灯
        return isSelected || super.isFocused()
    }

    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        if (focused) {
            super.onFocusChanged(focused, direction, previouslyFocusedRect)
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (hasWindowFocus) {
            super.onWindowFocusChanged(hasWindowFocus)
        }
    }
}
