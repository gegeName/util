package com.lhj.pagingutil

import android.content.Context
import android.util.AttributeSet
import com.example.statelayout.StateLayout

open class PagingStateLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : StateLayout(context, attrs, defStyleAttr), PageStateHandler {
    override fun bindRetry(retry: () -> Unit) = setOnRetryClickListener(retry)

    override fun showLoading() {
        showPageLoading()
    }

    override fun showEmpty(text: CharSequence?) {
        text?.let { setEmptyText(it) }
        setState(State.EMPTY)
    }

    override fun showError(throwable: Throwable?, text: CharSequence?) {
        val msg = text ?: throwable?.message?.takeIf { it.isNotBlank() }
        msg?.let { setErrorText(it) }
        setState(State.ERROR)
    }

    override fun showContent() = setState(State.SUCCESS)


}