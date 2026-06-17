package com.chat.myapplication

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.chat.myapplication.databinding.ActivityNestedHeaderDemoBinding
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * 验证 [com.hifylive.myapplication.widget.nestedheader.NestedHeaderLayout] A 阶段：触摸滚动。
 * 验收：
 *   1) 头图/广告位跟随手指 1:1 折叠/展开
 *   2) 折叠到极限后 Tab 吸顶
 *   3) 列表向下滚到顶后，继续下拉能展开头部
 *   4) 切 Tab 后头部状态保留
 */
class NestedHeaderDemoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNestedHeaderDemoBinding

    private val tabTitles = listOf("推荐", "关注")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNestedHeaderDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount(): Int = tabTitles.size
            override fun createFragment(position: Int): Fragment =
                ListFragment.newInstance(position, tabTitles[position])
        }

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab: TabLayout.Tab, pos: Int ->
            tab.text = tabTitles[pos]
        }.attach()

        binding.nestedHeader.addOnOffsetChangedListener { _, offset, max ->
            Log.d(TAG, "offset=$offset / $max")
        }
    }

    class ListFragment : Fragment() {

        private val items: List<String> by lazy {
            val tag = arguments?.getString(ARG_TAG) ?: "?"
            List(60) { "$tag 列表项 #${it + 1}" }
        }

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            val ctx = requireContext()
            val tag = arguments?.getString(ARG_TAG) ?: "?"

            val rv = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                adapter = TextAdapter(items)
                setBackgroundColor(Color.WHITE)
                isNestedScrollingEnabled = true
            }

            // 验证 scenario B：每个 Fragment 自己的 SwipeRefreshLayout。
            // 不需要给 NestedHeaderLayout 做任何额外配置 —— NestedScrolling 协议里
            // mParentOffsetInWindow 的会计机制会自动把"已被头部消化掉的 dy"从 spinner 里扣掉。
            return SwipeRefreshLayout(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(
                    rv,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                setOnRefreshListener {
                    Toast.makeText(ctx, "Tab \"$tag\" 触发刷新", Toast.LENGTH_SHORT).show()
                    postDelayed({ isRefreshing = false }, 1500)
                }
            }
        }

        companion object {
            private const val ARG_INDEX = "index"
            private const val ARG_TAG = "tag"
            fun newInstance(index: Int, tag: String): ListFragment {
                return ListFragment().apply {
                    arguments = Bundle().apply {
                        putInt(ARG_INDEX, index)
                        putString(ARG_TAG, tag)
                    }
                }
            }
        }
    }

    private class TextAdapter(private val items: List<String>) :
        RecyclerView.Adapter<TextAdapter.VH>() {

        class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val tv = TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(48, 36, 48, 36)
                textSize = 16f
                setTextColor(Color.parseColor("#333333"))
            }
            return VH(tv)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.tv.text = items[position]
            holder.tv.setBackgroundColor(
                if (position % 2 == 0) Color.parseColor("#FAFAFA") else Color.WHITE
            )
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        private const val TAG = "NestedHeaderDemo"
    }
}
