package com.hifylive.myapplication.sample

import com.hifylive.myapplication.databinding.LayoutFooterRecommendBinding
import com.simple.mylibrary.paging.SingleItemBindingAdapter

data class FooterData(val title: String, val desc: String)

class RecommendFooterAdapter : SingleItemBindingAdapter<FooterData, LayoutFooterRecommendBinding>() {
    override fun onBind(binding: LayoutFooterRecommendBinding, data: FooterData) {
        binding.tvFooterTitle.text = data.title
        binding.tvFooterDesc.text = data.desc
    }
}
