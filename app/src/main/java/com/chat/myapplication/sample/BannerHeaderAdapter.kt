package com.chat.myapplication.sample

import com.chat.myapplication.R
import com.chat.myapplication.databinding.LayoutHeaderBannerBinding
import com.chat.pagingutil.SingleItemBindingAdapter

data class BannerData(val title: String, val desc: String)

class BannerHeaderAdapter : SingleItemBindingAdapter<BannerData, LayoutHeaderBannerBinding>(LayoutHeaderBannerBinding::inflate) {
    init {
        addChildClickViewIds(R.id.tvBannerDesc)
    }
    override fun onBind(binding: LayoutHeaderBannerBinding, data: BannerData) {
        binding.tvBannerTitle.text = data.title
        binding.tvBannerDesc.text = data.desc
    }
}
