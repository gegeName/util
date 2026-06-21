package com.chat.uifoundation.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chat.uifoundation.http.ApiException
import com.chat.uifoundation.http.LoadingEmitter
import com.chat.uifoundation.http.LoadingOwner
import com.chat.uifoundation.http.PageStateEmitter
import com.chat.uifoundation.http.PageStateOwner
import com.chat.uifoundation.http.launchApi
import com.chat.uifoundation.http.launchApiWithLoading
import com.chat.uifoundation.http.launchApiWithLoadingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

open class BaseLoadingViewModel : ViewModel(), LoadingOwner, PageStateOwner {
    private val loadingEmitter = LoadingEmitter()
    override val loading: LoadingEmitter
        get() = loadingEmitter
    private val pageStateEmitter= PageStateEmitter()
    override val pageState: PageStateEmitter
        get() = pageStateEmitter

    protected fun launchApi(
        onApiError: ((ApiException) -> Boolean)? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job = viewModelScope.launchApi(onApiError, block)

    protected fun launchApiWithLoading(
        cancelable: Boolean = true,
        onApiError: ((ApiException) -> Boolean)? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job = viewModelScope.launchApiWithLoading(loadingEmitter, cancelable, onApiError, block)

    protected fun launchApiWithLoadingState(onApiError: ((ApiException) -> Boolean)? = null,
                                            block: suspend CoroutineScope.() -> Unit)=viewModelScope.launchApiWithLoadingState(pageStateEmitter,onApiError,block)
}