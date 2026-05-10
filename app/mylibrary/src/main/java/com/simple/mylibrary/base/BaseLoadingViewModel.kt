package com.simple.mylibrary.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simple.mylibrary.http.ApiException
import com.simple.mylibrary.http.LoadingEmitter
import com.simple.mylibrary.http.LoadingOwner
import com.simple.mylibrary.http.PageStateEmitter
import com.simple.mylibrary.http.PageStateOwner
import com.simple.mylibrary.http.launchApi
import com.simple.mylibrary.http.launchApiWithLoading
import com.simple.mylibrary.http.launchApiWithLoadingState
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