package com.hifylive.myapplication.sample

import androidx.recyclerview.widget.DiffUtil
import com.hifylive.myapplication.R
import com.hifylive.myapplication.databinding.ItemFakeBinding
import com.lhj.pagingutil.BasePagingAdapter

class FakeAdapter : BasePagingAdapter<FakeItem, ItemFakeBinding>(DIFF, ItemFakeBinding::inflate) {

    init {
        addChildClickViewIds(R.id.tvTitle)
    }

    override fun onBind(binding: ItemFakeBinding, item: FakeItem, position: Int) {
        binding.tvTitle.text = item.title
        binding.tvSubtitle.text = item.subtitle
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FakeItem>() {
            override fun areItemsTheSame(oldItem: FakeItem, newItem: FakeItem) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: FakeItem, newItem: FakeItem) =
                oldItem == newItem
        }
    }
}
