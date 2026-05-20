package com.hifylive.myapplication.sample

import com.hifylive.myapplication.R
import com.hifylive.myapplication.databinding.LayoutHeaderBannerBinding
import com.lhj.pagingutil.SingleItemBindingAdapter

data class BannerData(val title: String, val desc: String)

class BannerHeaderAdapter : SingleItemBindingAdapter<BannerData, LayoutHeaderBannerBinding>() {
    init {
        addChildClickViewIds(R.id.tvBannerDesc)
    }
    override fun onBind(binding: LayoutHeaderBannerBinding, data: BannerData) {
        binding.tvBannerTitle.text = data.title
        binding.tvBannerDesc.text = data.desc
    }
}
