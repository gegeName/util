package com.chat.picker.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.chat.picker.R
import com.chat.picker.model.MediaEntity

class MediaPreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "index"
        const val EXTRA_FROM_LIST = "from_list"
        const val EXTRA_MAX_COUNT = "max_count"
        const val RESULT_CONFIRMED = Activity.RESULT_FIRST_USER + 1
    }

    private lateinit var pager: ViewPager2
    private lateinit var title: TextView
    private lateinit var check: TextView
    private lateinit var confirm: TextView
    private lateinit var data: List<MediaEntity>
    private var maxCount: Int = 9

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.picker_activity_preview)
        com.chat.picker.util.EdgeToEdge.apply(
            activity = this,
            root = findViewById(R.id.preview_root),
            topBar = findViewById(R.id.preview_top_bar),
            bottomBar = findViewById(R.id.preview_bottom_bar),
        )

        data = PreviewBridge.previewList
        if (data.isEmpty()) { finish(); return }
        maxCount = intent.getIntExtra(EXTRA_MAX_COUNT, 9)
        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, data.size - 1)

        pager = findViewById(R.id.preview_pager)
        title = findViewById(R.id.preview_title)
        check = findViewById(R.id.preview_check)
        confirm = findViewById(R.id.preview_confirm)
        Selection.max = maxCount

        val previewAdapter = MediaPreviewAdapter()
        pager.adapter = previewAdapter
        pager.offscreenPageLimit = 1
        previewAdapter.submitList(data) {
            pager.setCurrentItem(startIndex, false)
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { refreshFor(position) }
        })

        findViewById<TextView>(R.id.preview_back).setOnClickListener { finish() }
        check.setOnClickListener {
            val cur = data[pager.currentItem]
            val r = Selection.toggle(cur)
            if (!r.accepted) {
                android.widget.Toast.makeText(
                    this, "最多选择 $maxCount 项", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            refreshFor(pager.currentItem)
        }
        confirm.setOnClickListener {
            setResult(RESULT_CONFIRMED)
            finish()
        }

        refreshFor(startIndex)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshFor(position: Int) {
        val item = data[position]
        title.text = "${position + 1} / ${data.size}"
        val idx = Selection.indexOf(item)
        if (idx > 0) {
            check.text = idx.toString()
            check.setBackgroundResource(R.drawable.picker_check_selected)
        } else {
            check.text = ""
            check.setBackgroundResource(R.drawable.picker_check_unselected)
        }
        confirm.text = "完成(${Selection.selected.size}/$maxCount)"
    }

    override fun finish() {
        super.finish()
        // 不清 PreviewBridge：列表页可能复用同一份引用
    }
}
