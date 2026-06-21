package com.chat.myapplication

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.TextPaint
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.chat.mylibrary.html.HtmlFormulaRenderer
import com.chat.mylibrary.html.HtmlMediaLoader
import com.chat.mylibrary.html.HtmlTextView

class HtmlTextViewDemoActivity : AppCompatActivity() {

    private lateinit var htmlText: HtmlTextView
    private lateinit var inlineVideoOverlay: View
    private lateinit var videoPlayer: VideoView
    private lateinit var tvVideoState: TextView
    private lateinit var btnPlayPauseVideo: Button
    private lateinit var btnReplayVideo: Button
    private lateinit var audioPanel: View
    private lateinit var tvAudioState: TextView
    private lateinit var audioSeekBar: SeekBar
    private lateinit var btnPlayPauseAudio: Button
    private lateinit var btnReplayAudio: Button
    private var audioPlayer: MediaPlayer? = null
    private var currentVideoUrl: String? = null
    private var currentAudioUrl: String? = null
    private var audioPrepared = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val audioProgressTask = object : Runnable {
        override fun run() {
            val player = audioPlayer
            if (player != null && audioPrepared) {
                val duration = safeDuration(player)
                val position = safeCurrentPosition(player)
                audioSeekBar.max = duration
                audioSeekBar.progress = position
                tvAudioState.text = "音频${if (player.isPlaying) "播放中" else "已暂停"}  ${formatTime(position)} / ${formatTime(duration)}"
                progressHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_html_text_view_demo)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left + dp(16), bars.top + dp(16), bars.right + dp(16), bars.bottom + dp(16))
            insets
        }

        inlineVideoOverlay = findViewById(R.id.inlineVideoOverlay)
        videoPlayer = findViewById(R.id.videoPlayer)
        tvVideoState = findViewById(R.id.tvVideoState)
        btnPlayPauseVideo = findViewById(R.id.btnPlayPauseVideo)
        btnReplayVideo = findViewById(R.id.btnReplayVideo)
        audioPanel = findViewById(R.id.audioPanel)
        tvAudioState = findViewById(R.id.tvAudioState)
        audioSeekBar = findViewById(R.id.audioSeekBar)
        btnPlayPauseAudio = findViewById(R.id.btnPlayPauseAudio)
        btnReplayAudio = findViewById(R.id.btnReplayAudio)
        btnPlayPauseVideo.setOnClickListener { toggleVideo() }
        btnReplayVideo.setOnClickListener { replayVideo() }
        findViewById<Button>(R.id.btnCloseVideo).setOnClickListener { stopVideo() }
        btnPlayPauseAudio.setOnClickListener { toggleAudio() }
        btnReplayAudio.setOnClickListener { replayAudio() }
        findViewById<Button>(R.id.btnStopAudio).setOnClickListener { stopAudio() }
        audioSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audioPlayer?.seekTo(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        htmlText = findViewById(R.id.htmlText)
        htmlText.blockImageWidthRatio = 0.45f
        htmlText.blockImageCornerRadius = dp(10).toFloat()
        htmlText.setMediaLoader(DemoMediaLoader())
        htmlText.setFormulaRenderer(DemoFormulaRenderer())
        htmlText.setOnImageClickListener { toast("点击图片: $it") }
        htmlText.setOnVideoClickListener { playVideo(it) }
        htmlText.setOnAudioClickListener { playAudio(it) }
        htmlText.setOnEmbedClickListener { toast("点击外部内容: $it") }
        htmlText.setHtml(sampleHtml())
    }

    private fun sampleHtml(): String = """
        <h1>标题 H1</h1>
        <h2>标题 H2</h2>
        <p>普通段落，包含 <b>加粗 b</b>、<strong>strong</strong>、<i>斜体 i</i>、<em>em</em>、
        <u>下划线 u</u>、<font color="#E53935">font color</font> 和
        <a href="https://example.com">普通链接</a>。</p>

        <p>删除线：<del>del</del>、<s>s</s>、<strike>strike</strike>；
        上下标：H<sub>2</sub>O，x<sup>2</sup>；
        高亮：<mark>mark 标签</mark>；
        键盘：<kbd>Ctrl</kbd> + <kbd>K</kbd>。</p>

        <blockquote>blockquote 引用内容。这里会增加引用样式、缩进和文字颜色。</blockquote>

        <p>无序列表：</p>
        <ul>
            <li>第一项</li>
            <li>第二项，含 <code>inline code</code></li>
        </ul>

        <p>有序列表：默认阿拉伯数字</p>
        <ol type="1" start="3">
            <li>步骤一</li>
            <li>步骤二</li>
        </ol>

        <p>有序列表：大写字母</p>
        <ol type="A">
            <li>Alpha</li>
            <li>Beta</li>
        </ol>

        <p>有序列表：小写罗马数字</p>
        <ol type="i">
            <li>Roman one</li>
            <li>Roman two</li>
        </ol>

        <p>有序列表：中文序号</p>
        <ol style="list-style-type: cjk-ideographic">
            <li>中文第一项</li>
            <li>中文第二项</li>
        </ol>

        <p>代码块 pre/code：</p>
        <pre><code>fun main() {
    val price = "${'$'}19.90"
    println(price)
}</code></pre>

        <p>表格 table/tr/th/td：</p>
        <table>
            <tr><th>标签</th><th>效果</th><th>状态</th></tr>
            <tr><td>mark</td><td>文本高亮</td><td>已支持</td></tr>
            <tr><td>audio</td><td>点击回调</td><td>已支持</td></tr>
        </table>

        <p>行内公式：质能方程 ${'$'}E = mc^2${'$'}，分式 \(\frac{a}{b}\)。</p>
        <p>块级公式：</p>
        $$\int_0^1 x^2 dx = \frac{1}{3}$$
        \[
            a^2 + b^2 = c^2
        \]

        <p>小图 inline：<img src="demo://small-image"> 与文字同一行。</p>
        <p>大图会独占一行、居中、圆角，点击触发图片回调：</p>
        <img src="demo://large-image?name=banner&value=1&amp;safe=true">

        <p>视频 video：展示首帧和播放按钮，点击后在当前页面播放。</p>
        <video src="$DEMO_VIDEO_URL"></video>

        <p>音频 audio：降级成可点击文本，点击后在当前页面播放。</p>
        <audio src="$DEMO_AUDIO_URL"></audio>

        <p>外部内容 iframe/embed/object：降级成可点击文本。</p>
        <iframe src="https://example.com/embed/card"></iframe>
        <embed src="https://example.com/embed/chart" />
        <object data="https://example.com/object/file"></object>
    """.trimIndent()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun playVideo(url: String) {
        currentVideoUrl = url
        placeVideoOverlay(url)
        inlineVideoOverlay.visibility = View.VISIBLE
        tvVideoState.text = "视频准备中..."
        btnPlayPauseVideo.text = "暂停视频"
        videoPlayer.stopPlayback()
        videoPlayer.setVideoURI(Uri.parse(url))
        videoPlayer.setOnPreparedListener { player ->
            player.isLooping = false
            tvVideoState.text = "视频播放中"
            btnPlayPauseVideo.text = "暂停视频"
            videoPlayer.start()
        }
        videoPlayer.setOnCompletionListener {
            tvVideoState.text = "视频播放完成"
            btnPlayPauseVideo.text = "播放视频"
        }
        videoPlayer.setOnErrorListener { _, _, _ ->
            tvVideoState.text = "视频播放失败"
            btnPlayPauseVideo.text = "播放视频"
            true
        }
    }

    private fun toggleVideo() {
        if (inlineVideoOverlay.visibility != View.VISIBLE) return
        if (videoPlayer.isPlaying) {
            videoPlayer.pause()
            tvVideoState.text = "视频已暂停"
            btnPlayPauseVideo.text = "播放视频"
        } else {
            if (currentVideoUrl == null) return
            videoPlayer.start()
            tvVideoState.text = "视频播放中"
            btnPlayPauseVideo.text = "暂停视频"
        }
    }

    private fun replayVideo() {
        val url = currentVideoUrl ?: return
        playVideo(url)
    }

    private fun stopVideo() {
        currentVideoUrl = null
        videoPlayer.stopPlayback()
        inlineVideoOverlay.visibility = View.GONE
    }

    private fun placeVideoOverlay(url: String) {
        htmlText.post {
            if (currentVideoUrl != url) return@post
            val bounds = htmlText.findMediaBounds(url) ?: run {
                htmlText.postDelayed({
                    if (currentVideoUrl == url) placeVideoOverlay(url)
                }, 120)
                return@post
            }
            inlineVideoOverlay.layoutParams = (inlineVideoOverlay.layoutParams as FrameLayout.LayoutParams).apply {
                width = bounds.width()
                height = bounds.height()
                leftMargin = bounds.left
                topMargin = bounds.top
            }
        }
    }

    private fun playAudio(url: String) {
        stopAudio()
        currentAudioUrl = url
        audioPrepared = false
        audioPanel.visibility = View.VISIBLE
        tvAudioState.text = "音频准备中..."
        btnPlayPauseAudio.text = "暂停音频"
        audioSeekBar.progress = 0
        audioPlayer = MediaPlayer().apply {
            setDataSource(this@HtmlTextViewDemoActivity, Uri.parse(url))
            setOnPreparedListener {
                audioPrepared = true
                val duration = safeDuration(this)
                audioSeekBar.max = duration
                tvAudioState.text = "音频播放中  00:00 / ${formatTime(duration)}"
                btnPlayPauseAudio.text = "暂停音频"
                start()
                progressHandler.post(audioProgressTask)
            }
            setOnCompletionListener {
                progressHandler.removeCallbacks(audioProgressTask)
                val duration = safeDuration(this)
                tvAudioState.text = "音频播放完成  ${formatTime(duration)} / ${formatTime(duration)}"
                btnPlayPauseAudio.text = "播放音频"
                audioSeekBar.progress = duration
            }
            setOnErrorListener { _, _, _ ->
                progressHandler.removeCallbacks(audioProgressTask)
                audioPrepared = false
                tvAudioState.text = "音频播放失败"
                btnPlayPauseAudio.text = "播放音频"
                true
            }
            prepareAsync()
        }
    }

    private fun toggleAudio() {
        val player = audioPlayer ?: return
        if (!audioPrepared) return
        if (player.isPlaying) {
            player.pause()
            btnPlayPauseAudio.text = "播放音频"
            tvAudioState.text = "音频已暂停  ${formatTime(safeCurrentPosition(player))} / ${formatTime(safeDuration(player))}"
        } else {
            player.start()
            btnPlayPauseAudio.text = "暂停音频"
            progressHandler.post(audioProgressTask)
        }
    }

    private fun replayAudio() {
        val url = currentAudioUrl ?: return
        playAudio(url)
    }

    private fun stopAudio() {
        progressHandler.removeCallbacks(audioProgressTask)
        audioPlayer?.release()
        audioPlayer = null
        audioPrepared = false
        currentAudioUrl = null
        if (::audioSeekBar.isInitialized) {
            audioSeekBar.progress = 0
        }
        if (::audioPanel.isInitialized) {
            audioPanel.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        stopAudio()
        if (::videoPlayer.isInitialized) {
            videoPlayer.stopPlayback()
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun formatTime(ms: Int): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    private fun safeDuration(player: MediaPlayer): Int = try {
        player.duration.coerceAtLeast(0)
    } catch (e: IllegalStateException) {
        0
    }

    private fun safeCurrentPosition(player: MediaPlayer): Int = try {
        player.currentPosition.coerceAtLeast(0)
    } catch (e: IllegalStateException) {
        0
    }

    private inner class DemoMediaLoader : HtmlMediaLoader {
        override fun loadImage(url: String, maxWidth: Int, callback: (Drawable?) -> Unit) {
            val isSmall = url.contains("small-image")
            val width = if (isSmall) dp(42) else maxWidth.coerceAtLeast(dp(280))
            val height = if (isSmall) dp(24) else (width * 9f / 16f).toInt()
            callback(DemoDrawable(width, height, "#E3F2FD", "#1565C0", "IMG"))
        }

        override fun loadVideoFrame(url: String, maxWidth: Int, callback: (Drawable?) -> Unit) {
            val width = maxWidth.coerceAtLeast(dp(280))
            callback(DemoDrawable(width, (width * 9f / 16f).toInt(), "#FFF3E0", "#EF6C00", "VIDEO"))
        }
    }

    private inner class DemoFormulaRenderer : HtmlFormulaRenderer {
        override fun render(latex: String, isBlock: Boolean, textSizePx: Float, color: Int): Drawable {
            val text = if (isBlock) latex else latex.replace("\\s+".toRegex(), " ")
            val width = if (isBlock) dp(260) else (text.length * textSizePx * 0.55f).toInt().coerceAtLeast(dp(60))
            val height = if (isBlock) dp(48) else (textSizePx * 1.35f).toInt().coerceAtLeast(dp(22))
            return FormulaDrawable(width, height, text, color)
        }
    }

    private class DemoDrawable(
        private val w: Int,
        private val h: Int,
        background: String,
        foreground: String,
        private val label: String
    ) : Drawable() {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(background)
            style = Paint.Style.FILL
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(foreground)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(foreground)
            textAlign = Paint.Align.CENTER
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            canvas.drawRoundRect(rect, 16f, 16f, bgPaint)
            canvas.drawRoundRect(rect, 16f, 16f, borderPaint)
            val y = bounds.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, bounds.exactCenterX(), y, textPaint)
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
            borderPaint.alpha = alpha
            textPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            bgPaint.colorFilter = colorFilter
            borderPaint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = w

        override fun getIntrinsicHeight(): Int = h
    }

    private class FormulaDrawable(
        private val w: Int,
        private val h: Int,
        latex: String,
        color: Int
    ) : Drawable() {

        private val label = TextUtils.ellipsize(
            latex,
            TextPaint().apply { textSize = 28f },
            420f,
            TextUtils.TruncateAt.END
        ).toString()
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0x11_00_00_00
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 28f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            rect.set(bounds)
            canvas.drawRoundRect(rect, 8f, 8f, bgPaint)
            val y = bounds.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, bounds.exactCenterX(), y, textPaint)
        }

        override fun setAlpha(alpha: Int) {
            bgPaint.alpha = alpha
            textPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            bgPaint.colorFilter = colorFilter
            textPaint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = w

        override fun getIntrinsicHeight(): Int = h
    }

    companion object {
        private const val DEMO_VIDEO_URL =
            "https://sf1-cdn-tos.huoshanstatic.com/obj/media-fe/xgplayer_doc_video/mp4/xgplayer-demo-360p.mp4"
        private const val DEMO_AUDIO_URL =
            "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"
    }
}
