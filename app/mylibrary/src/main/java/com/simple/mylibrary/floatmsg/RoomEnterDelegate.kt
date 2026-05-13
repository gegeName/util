package com.simple.mylibrary.floatmsg

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/**
 * 使用例子
 *
 * 直播间进场消息(301):FIFO 时间序列展示,队列满后合并到队尾。
 * 每条消息独立 inflate [view_room_enter_msg.xml] 加入 [container],动画结束后从容器移除。
 * 容器是空壳,可与其它浮屏消息类型共用。
 *
 * @param animator 自定义入/出场动画,不传则用 [SlideInLeftAnimator] 默认左飞入/出
 */
class RoomEnterDelegate(
    private val activity: AppCompatActivity,
    container: ViewGroup,
    animator: FloatMessageAnimator = SlideInLeftAnimator()
) {
    private data class EnterMsg(
        val avatar: String,
        val nick: String,
        val mergeCount: Int = 0
    )

    private val inflater = LayoutInflater.from(activity)

    private val queue = FloatMessageQueue<EnterMsg>(
        container = container,
        onCreateView = { msg -> createPill(container, msg) },
        animator = animator,
        onMerge = { tail, _ -> tail.copy(mergeCount = tail.mergeCount + 1) },
        lifecycle = activity.lifecycle
    )

    /**
     * 调用入口
     */
    fun enqueue(avatar: String, nick: String) {
        if (nick.isBlank()) return
        queue.enqueue(EnterMsg(avatar, nick))
    }

    /**
     * 释放试图
     */
    fun release() = queue.release()

    private fun createPill(container: ViewGroup, msg: EnterMsg): View {
        //获取试图并绑定数据
        /*val binding = ViewRoomEnterMsgBinding.inflate(inflater, container, false)
        binding.tvNickEnter.text = msg.nick
        binding.tvEnterText.text = if (msg.mergeCount > 0) {
            activity.getString(R.string.And_n_more_joined, msg.mergeCount)
        } else {
            activity.getString(R.string.Joined_the_room)
        }
        if (msg.avatar.isNotBlank()) {
            ImageLoader.load(activity, msg.avatar, binding.ivAvatarEnter)
        }
        return binding.root*/
        return View(activity)
    }
}
