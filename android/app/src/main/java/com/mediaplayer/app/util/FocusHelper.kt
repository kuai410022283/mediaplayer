/**
 * 作者：laok
 * 邮箱：kuai410022283@qq.com
 */
package com.mediaplayer.app.util

import android.view.FocusFinder
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * TV 遥控器焦点导航辅助器
 * 解决 RecyclerView 焦点丢失、焦点跳跃等常见问题
 */
object FocusHelper {

    /**
     * 为 RecyclerView 设置 TV 优化的焦点行为
     */
    fun setupTvRecyclerView(rv: RecyclerView) {
        rv.apply {
            // 确保获得焦点
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

            // 焦点变化时滚动到可见
            addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    val existingListener = view.onFocusChangeListener
                    view.setOnFocusChangeListener { v, hasFocus ->
                        existingListener?.onFocusChange(v, hasFocus)
                        if (hasFocus) {
                            val pos = rv.getChildAdapterPosition(v)
                            if (pos != RecyclerView.NO_POSITION) {
                                smoothScrollToPosition(pos)
                            }
                            // 焦点态动画
                            v.animate()
                                .scaleX(if (hasFocus) 1.03f else 1.0f)
                                .scaleY(if (hasFocus) 1.03f else 1.0f)
                                .alpha(if (hasFocus) 1.0f else 0.85f)
                                .setDuration(150)
                                .start()
                        }
                    }
                }
                override fun onChildViewDetachedFromWindow(view: View) {}
            })
        }
    }

    /**
     * 在两个 RecyclerView 之间建立左右焦点导航
     * 左列表按右键 → 右列表获焦
     * 右列表按左键 → 左列表获焦
     */
    fun linkHorizontalFocus(
        leftRv: RecyclerView, 
        rightRv: RecyclerView,
        onLeftNav: (() -> Boolean)? = null,
        onRightNav: (() -> Boolean)? = null
    ) {
        leftRv.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val handled = onRightNav?.invoke() ?: false
                    if (!handled) {
                        rightRv.requestFocus()
                        val firstVisible = (rightRv.layoutManager as? LinearLayoutManager)
                            ?.findFirstVisibleItemPosition() ?: 0
                        rightRv.getChildAt(firstVisible - (rightRv.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())
                            ?.requestFocus()
                    }
                    return@setOnKeyListener true
                }
                if (trapVerticalScroll(leftRv, keyCode)) return@setOnKeyListener true
            }
            false
        }

        rightRv.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    val handled = onLeftNav?.invoke() ?: false
                    if (!handled) {
                        leftRv.requestFocus()
                    }
                    return@setOnKeyListener true
                }
                if (trapVerticalScroll(rightRv, keyCode)) return@setOnKeyListener true
            }
            false
        }
    }

    /**
     * 捕获极速滚动时的上下焦点逃逸
     */
    fun trapVerticalScroll(rv: RecyclerView, keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP
        val focused = rv.findFocus() ?: return false
        
        // 尝试在 RecyclerView 内部寻找下一个焦点
        val nextFocus = FocusFinder.getInstance().findNextFocus(rv, focused, direction)
        if (nextFocus == null) {
            // 内部找不到焦点，通常是因为 RecyclerView 正在极速动画或 layout，下一个子 View 还没挂载上来。
            // 此时必须强制吃掉按键事件，防止系统 fallback 到全局搜索导致焦点飞到隔壁列表。
            val lm = rv.layoutManager as? LinearLayoutManager
            val adapter = rv.adapter
            if (lm != null && adapter != null) {
                if (direction == View.FOCUS_DOWN) {
                    val lastVisible = lm.findLastVisibleItemPosition()
                    if (lastVisible < adapter.itemCount - 1) {
                        rv.smoothScrollToPosition(lastVisible + 1)
                    }
                } else {
                    val firstVisible = lm.findFirstVisibleItemPosition()
                    if (firstVisible > 0) {
                        rv.smoothScrollToPosition(firstVisible - 1)
                    }
                }
            }
            return true // 捕获焦点！
        }
        return false
    }

    /**
     * 设置 RecyclerView 的上下边界行为
     * 到达列表顶部/底部时，焦点传递给父容器
     */
    fun setupBoundaryFocus(rv: RecyclerView, onTopReach: (() -> Unit)? = null, onBottomReach: (() -> Unit)? = null) {
        rv.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val layoutManager = rv.layoutManager as? LinearLayoutManager ?: return@setOnKeyListener false
            val firstVisible = layoutManager.findFirstCompletelyVisibleItemPosition()
            val lastVisible = layoutManager.findLastCompletelyVisibleItemPosition()
            val itemCount = rv.adapter?.itemCount ?: 0

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (firstVisible == 0) {
                        // 检查当前焦点是否在第一个item上
                        val focusedChild = rv.findFocus()
                        if (focusedChild != null && rv.getChildAdapterPosition(focusedChild) == 0) {
                            onTopReach?.invoke()
                            return@setOnKeyListener false // 让焦点自然传递
                        }
                    }
                    false
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (lastVisible >= itemCount - 1) {
                        val focusedChild = rv.findFocus()
                        val lastChild = layoutManager.findViewByPosition(itemCount - 1)
                        if (focusedChild == lastChild) {
                            onBottomReach?.invoke()
                            return@setOnKeyListener false
                        }
                    }
                    false
                }
                else -> false
            }
        }
    }

    /**
     * 请求初始焦点
     */
    fun requestInitialFocus(vararg views: View) {
        views.firstOrNull()?.let { v -> v.post { v.requestFocus() } }
    }
}
