package com.hifylive.myapplication

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.lhj.effect.EffectManager
import com.lhj.effect.EffectPlaybackListener
import com.lhj.effect.EffectPriority
import com.lhj.effect.EffectResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * 模拟服务端推送 GIF / SVGA / MP4 (alpha-mp4) 特效，按钮点一下入队播放，
 * "服务器推送"按钮启动一个协程定时随机往队列里塞特效。
 *
 * EffectManager 已在 [App.onCreate] 完成 init + enableAutoStage,Activity 内
 * 不需要手动 attach EffectStageView,自动 stage 会在 onActivityResumed 时把 stage
 * 挂到当前 Activity 的 `android.R.id.content` 上。
 */
class EffectActivity : AppCompatActivity() {

    private val gifs = listOf(
        "https://media0.giphy.com/media/v1.Y2lkPTc5MGI3NjExM3lwNWtqMGdocWpvdWp1Ymtrc2hmeTZnbTI2MG5naHZ6ZnZwbWY3bCZlcD12MV9pbnRlcm5hbF9naWZfYnlfaWQmY3Q9Zw/DzFj5QLRs7AZ2/giphy.gif",
        "https://media.giphy.com/media/3oz8xKaR836UJOYeOc/giphy.gif",
        "https://media.giphy.com/media/l0HlBO7eyXzSZkJri/giphy.gif",
    )
    private val svgas = listOf(
        "https://cdn.jsdelivr.net/gh/svga/SVGA-Samples@master/angel.svga",
        "https://cdn.jsdelivr.net/gh/svga/SVGA-Samples@master/kingset.svga",
        "https://cdn.jsdelivr.net/gh/svga/SVGA-Samples@master/PinJump.svga",
        "https://cdn.jsdelivr.net/gh/svga/SVGA-Samples@master/EmptyState.svga",
    )
    private val mp4s = listOf(
        "https://github.com/Tencent/vap/raw/master/Android/PlayerProj/animplayer-sample/src/main/assets/demo.mp4",
    )

    private lateinit var tvLog: TextView
    private var pushJob: Job? = null

    private val playbackListener = object : EffectPlaybackListener {
        override fun onStart(resource: EffectResource) {
            appendLog("▶ start  ${resource.type.key}  ${shortUrl(resource.url)}")
        }

        override fun onComplete(resource: EffectResource) {
            appendLog("✓ done   ${resource.type.key}  ${shortUrl(resource.url)}")
        }

        override fun onError(resource: EffectResource, reason: String) {
            appendLog("✗ error  ${resource.type.key}  $reason")
        }

        override fun onQueueFinished() {
            appendLog("— queue empty")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(buildContentView())
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        EffectManager.addPlaybackListener(playbackListener)

        EffectManager.preload(gifs + svgas + mp4s)
    }

    override fun onDestroy() {
        super.onDestroy()
        pushJob?.cancel()
        pushJob = null
        EffectManager.removePlaybackListener(playbackListener)
    }

    private fun buildContentView(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(button("入队 1 条 GIF（随机）") {
            EffectManager.enqueue(gifs.random(), tag = "manual-gif")
        })
        root.addView(button("入队 1 条 SVGA（随机）") {
            EffectManager.enqueue(svgas.random(), tag = "manual-svga")
        })
        root.addView(button("入队 1 条 MP4（VAP）") {
            EffectManager.enqueue(mp4s.random(), tag = "manual-mp4")
        })
        root.addView(button("入队 HIGH 优先级 SVGA") {
            EffectManager.enqueue(
                url = svgas.random(),
                priority = EffectPriority.HIGH,
                tag = "high-svga",
            )
        })
        root.addView(button("入队 persistent SVGA") {
            EffectManager.enqueue(
                url = svgas.random(),
                tag = "persistent-svga",
                persistent = true,
            )
        })
        root.addView(button("启动『服务器推送』(每 2~4s 随机一条)") {
            startMockServerPush()
        })
        root.addView(button("停止『服务器推送』") {
            stopMockServerPush()
        })
        root.addView(button("清空队列 (含 persistent)") {
            EffectManager.clear()
        })

        tvLog = TextView(this).apply {
            setTextIsSelectable(true)
            setLineSpacing(0f, 1.2f)
            textSize = 12f
        }
        root.addView(tvLog)
        return root
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { onClick() }
    }

    private fun startMockServerPush() {
        if (pushJob?.isActive == true) {
            toast("推送已在运行")
            return
        }
        toast("已启动模拟推送")
        appendLog("== mock server push started ==")
        pushJob = lifecycleScope.launch {
            withContext(Dispatchers.Default) {
                while (true) {
                    delay(Random.nextLong(2_000L, 4_000L))
                    val url = (gifs + svgas + mp4s).random()
                    val priority = if (Random.nextInt(10) < 2) {
                        EffectPriority.HIGH
                    } else {
                        EffectPriority.NORMAL
                    }
                    val tag = "push-${System.currentTimeMillis() % 100000}"
                    withContext(Dispatchers.Main) {
                        appendLog("◀ server push  $tag  $priority  ${shortUrl(url)}")
                        EffectManager.enqueue(url, priority = priority, tag = tag)
                    }
                }
            }
        }
    }

    private fun stopMockServerPush() {
        if (pushJob?.isActive != true) {
            toast("推送未启动")
            return
        }
        pushJob?.cancel()
        pushJob = null
        appendLog("== mock server push stopped ==")
        toast("已停止模拟推送")
    }

    private fun appendLog(line: String) {
        val ts = System.currentTimeMillis() % 100000L
        val text = "$ts | $line\n"
        runOnUiThread {
            tvLog.append(text)
            val maxLines = 200
            val lines = tvLog.text.toString().split('\n')
            if (lines.size > maxLines) {
                tvLog.text = lines.takeLast(maxLines).joinToString("\n")
            }
        }
    }

    private fun shortUrl(url: String): String =
        url.substringAfterLast('/').take(40)

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
