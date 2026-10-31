package com.github.catvod.net

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Call
import okhttp3.EventListener

/**
 * 搜索线程发起的 HTTP 调用追踪 (只追踪名字以 [THREAD_PREFIX] 开头的线程).
 *
 * 背景: jar (csp) 爬虫的搜索是在调用线程里同步 execute() 的, 协程取消打不断阻塞中的
 * socket 读, 只有 Call.cancel() 才行. 这里在 call 开始/结束时登记, 进入详情页时
 * [cancelTracked] 一次性取消, 让被占着的线程立刻释放. 不影响播放/图片等其他线程的请求.
 */
object CallTracker {

    const val THREAD_PREFIX = "ubox-search"

    private val calls: MutableSet<Call> = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

    val factory: EventListener.Factory = object : EventListener.Factory {
        override fun create(call: Call): EventListener {
            if (!Thread.currentThread().name.startsWith(THREAD_PREFIX)) return EventListener.NONE
            return object : EventListener() {
                override fun callStart(call: Call) {
                    calls.add(call)
                }

                override fun callEnd(call: Call) {
                    calls.remove(call)
                }

                override fun callFailed(call: Call, ioe: java.io.IOException) {
                    calls.remove(call)
                }

                override fun canceled(call: Call) {
                    calls.remove(call)
                }
            }
        }
    }

    @JvmStatic
    fun cancelTracked() {
        val snapshot = calls.toList()
        calls.clear()
        for (call in snapshot) {
            try {
                call.cancel()
            } catch (_: Throwable) {
            }
        }
    }
}
