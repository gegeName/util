package com.lhj.pagingutil

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import androidx.recyclerview.widget.RecyclerView
import com.lhj.pagingutil.databinding.LayoutLoadStateFooterBinding


/**
 * 通用分页尾部 Adapter：覆盖加载中 / 加载错误 / 没有更多了 三种状态。
 *
 * 加载中：小转圈 + 文字（默认 "加载中…"）横排，文字与转圈对齐
 * 加载错误：文字提示，点击触发 [onRetry]
 * 没有更多了：可关，关掉就完全不占布局
 *
 * @param showEndText 是否在 endOfPaginationReached 时展示"没有更多了"
 * @param endText "没有更多了"的文本，可自定义
 * @param loadingText 加载中显示的文本，默认 "加载中…"
 * @param onRetry 点击错误文本时的重试回调
 */
class CommonLoadStateAdapter(
    private val showEndText: Boolean = true,
    private val endText: String = "没有更多了",
    private val loadingText: String = "加载中…",
    private val onRetry: () -> Unit
) : LoadStateAdapter<CommonLoadStateAdapter.VH>() {

    class VH(val binding: LayoutLoadStateFooterBinding) : RecyclerView.ViewHolder(binding.root)

    override fun displayLoadStateAsItem(loadState: LoadState): Boolean {
        return loadState is LoadState.Loading
            || loadState is LoadState.Error
            || (showEndText && loadState is LoadState.NotLoading && loadState.endOfPaginationReached)
    }

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState) =
        VH(LayoutLoadStateFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, loadState: LoadState) = with(holder.binding) {
        when (loadState) {
            is LoadState.Loading -> {
                progressBar.visibility = View.VISIBLE
                tvFooter.visibility = View.VISIBLE
                tvFooter.text = loadingText
                tvFooter.setOnClickListener(null)
            }
            is LoadState.Error -> {
                progressBar.visibility = View.GONE
                tvFooter.visibility = View.VISIBLE
                tvFooter.text = loadState.error.message ?: "加载失败，点击重试"
                tvFooter.setOnClickListener { onRetry() }
            }
            is LoadState.NotLoading -> {
                progressBar.visibility = View.GONE
                if (loadState.endOfPaginationReached && showEndText) {
                    tvFooter.visibility = View.VISIBLE
                    tvFooter.text = endText
                } else {
                    tvFooter.visibility = View.GONE
                }
                tvFooter.setOnClickListener(null)
            }
        }
        Unit
    }
}
