package com.lhj.statelayout

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.core.content.withStyledAttributes
import androidx.core.view.isVisible

/**
 * 状态帧布局：在同一容器中切换 Loading / Empty / Error / Success 四种状态。
 *
 * stateMargin* 仅作用于 loading/empty/error 视图，使其从 topbar 之下开始绘制，
 * topbar 在任何状态下都保持可见、可点击。
 */
open class StateLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class State { SUCCESS, LOADING, EMPTY, ERROR }

    private var mLoadingLayoutId: Int = R.layout.layout_state_loading
    private var mEmptyLayoutId: Int = R.layout.layout_state_empty
    private var mErrorLayoutId: Int = R.layout.layout_state_error

    private var mEmptyText: CharSequence? = null
    private var mErrorText: CharSequence? = null
    private var mStateBackgroundRes: Int = 0
    private var mRetryText: CharSequence? = null

    private var mStateMarginStart: Int = 0
    private var mStateMarginTop: Int = 0
    private var mStateMarginEnd: Int = 0
    private var mStateMarginBottom: Int = 0

    private var mLoadingView: View? = null
    private var mEmptyView: View? = null
    private var mErrorView: View? = null
    private var mContentView: View? = null

    private var mRetryListener: (() -> Unit)? = null

    private var mState: State = State.SUCCESS
    private var mPendingDefaultState: State = State.SUCCESS

    init {
        if (attrs != null) {
            context.withStyledAttributes(attrs, R.styleable.StateLayout) {
                mLoadingLayoutId =
                    getResourceId(R.styleable.StateLayout_stlLoadingLayout, mLoadingLayoutId)
                mEmptyLayoutId =
                    getResourceId(R.styleable.StateLayout_stlEmptyLayout, mEmptyLayoutId)
                mErrorLayoutId =
                    getResourceId(R.styleable.StateLayout_stlErrorLayout, mErrorLayoutId)
                mEmptyText = getText(R.styleable.StateLayout_stlEmptyText)
                mErrorText = getText(R.styleable.StateLayout_stlErrorText)
                mRetryText = getText(R.styleable.StateLayout_stlRetryText)
                mStateBackgroundRes = getResourceId(R.styleable.StateLayout_stlStateBackground, 0)
                mStateMarginTop = getDimensionPixelSize(R.styleable.StateLayout_stlMarginTop, 0)
                mStateMarginBottom =
                    getDimensionPixelSize(R.styleable.StateLayout_stlMarginBottom, 0)
                mStateMarginStart =
                    getDimensionPixelSize(R.styleable.StateLayout_stlMarginStart, 0)
                mStateMarginEnd = getDimensionPixelSize(R.styleable.StateLayout_stlMarginEnd, 0)
                mPendingDefaultState =
                    State.values()[getInt(R.styleable.StateLayout_stlDefaultState, 0)]
            }
        }
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        check(childCount <= 1) { "StateLayout only allows a single direct child as content" }
        if (childCount == 1) mContentView = getChildAt(0)
        if (mPendingDefaultState != State.SUCCESS) setState(mPendingDefaultState)
    }

    val state: State get() = mState

    fun showEmpty() = setState(State.EMPTY)
    fun showError() = setState(State.ERROR)
    fun showSuccess() = setState(State.SUCCESS)
    fun showPageLoading() = setState(State.LOADING)

    fun setState(state: State) {
        if (mState == state) return
        mState = state
        when (state) {
            State.SUCCESS -> {
                mLoadingView?.isVisible = false
                mEmptyView?.isVisible = false
                mErrorView?.isVisible = false
            }

            State.LOADING -> {
                ensureLoadingView()
                mEmptyView?.isVisible = false
                mErrorView?.isVisible = false
                mLoadingView?.isVisible = true
            }

            State.EMPTY -> {
                ensureEmptyView()
                mLoadingView?.isVisible = false
                mErrorView?.isVisible = false
                mEmptyView?.isVisible = true
            }

            State.ERROR -> {
                ensureErrorView()
                mLoadingView?.isVisible = false
                mEmptyView?.isVisible = false
                mErrorView?.isVisible = true
            }
        }
    }

    fun setEmptyText(text: CharSequence?) {
        mEmptyText = text
        mEmptyView?.findViewById<TextView>(R.id.tvPageStateEmpty)?.text = text
    }

    fun setEmptyText(@StringRes resId: Int) = setEmptyText(context.getText(resId))

    fun setErrorText(text: CharSequence?) {
        mErrorText = text
        mErrorView?.findViewById<TextView>(R.id.tvPageStateError)?.text = text
    }

    fun setErrorText(@StringRes resId: Int) = setErrorText(context.getText(resId))

    fun setRetryText(text: CharSequence?) {
        mRetryText = text
        mErrorView?.findViewById<TextView>(R.id.btnPageStateRetry)?.text = text
    }

    fun setOnRetryClickListener(listener: (() -> Unit)?) {
        mRetryListener = listener
        mErrorView?.findViewById<View>(R.id.btnPageStateRetry)?.setOnClickListener {
            mRetryListener?.invoke()
        }
    }

    fun setStateMargin(start: Int, top: Int, end: Int, bottom: Int) {
        mStateMarginStart = start
        mStateMarginTop = top
        mStateMarginEnd = end
        mStateMarginBottom = bottom
        applyMargin(mLoadingView)
        applyMargin(mEmptyView)
        applyMargin(mErrorView)
    }


    /**
     * 设置 loading/empty/error 视图的统一背景（color 或 drawable 资源）。
     * 典型场景：topbar 在 content 内，状态视图通过 stlMarginTop 让出 topbar 区域，
     * 背景色填满剩余区域以遮住 content。
     * 传 0 表示透明，content 会从背后透出（适合"刷新时显示 loading 但保留旧列表"的体验）。
     */
    fun setStateBackgroundResource(@DrawableRes resId: Int) {
        mStateBackgroundRes = resId
        mLoadingView?.let {
            if (resId != 0) it.setBackgroundResource(resId) else it.background = null
        }
        mEmptyView?.let {
            if (resId != 0) it.setBackgroundResource(resId) else it.background = null
        }
        mErrorView?.let {
            if (resId != 0) it.setBackgroundResource(resId) else it.background = null
        }
    }

    fun setLoadingLayout(@LayoutRes layoutId: Int) {
        if (mLoadingLayoutId == layoutId && mLoadingView != null) return
        mLoadingLayoutId = layoutId
        mLoadingView?.let { removeView(it); mLoadingView = null }
        if (mState == State.LOADING) {
            ensureLoadingView()
            mLoadingView?.isVisible = true
        }
    }

    fun setEmptyLayout(@LayoutRes layoutId: Int) {
        if (mEmptyLayoutId == layoutId && mEmptyView != null) return
        mEmptyLayoutId = layoutId
        mEmptyView?.let { removeView(it); mEmptyView = null }
        if (mState == State.EMPTY) {
            ensureEmptyView()
            mEmptyView?.isVisible = true
        }
    }

    fun setErrorLayout(@LayoutRes layoutId: Int) {
        if (mErrorLayoutId == layoutId && mErrorView != null) return
        mErrorLayoutId = layoutId
        mErrorView?.let { removeView(it); mErrorView = null }
        if (mState == State.ERROR) {
            ensureErrorView()
            mErrorView?.isVisible = true
        }
    }

    private fun ensureLoadingView() {
        if (mLoadingView != null) return
        val v = LayoutInflater.from(context).inflate(mLoadingLayoutId, this, false)
        if (mStateBackgroundRes != 0) v.setBackgroundResource(mStateBackgroundRes)
        addView(v, buildStateLp(v.layoutParams))
        v.isVisible = false
        mLoadingView = v
    }

    private fun ensureEmptyView() {
        if (mEmptyView != null) return
        val v = LayoutInflater.from(context).inflate(mEmptyLayoutId, this, false)
        if (mStateBackgroundRes != 0) v.setBackgroundResource(mStateBackgroundRes)
        addView(v, buildStateLp(v.layoutParams))
        v.isVisible = false
        v.findViewById<TextView>(R.id.tvPageStateEmpty)
            ?.let { tv -> mEmptyText?.let { tv.text = it } }
        mEmptyView = v
    }

    private fun ensureErrorView() {
        if (mErrorView != null) return
        val v = LayoutInflater.from(context).inflate(mErrorLayoutId, this, false)
        if (mStateBackgroundRes != 0) v.setBackgroundResource(mStateBackgroundRes)
        addView(v, buildStateLp(v.layoutParams))
        v.isVisible = false
        v.findViewById<TextView>(R.id.tvPageStateError)
            ?.let { tv -> mErrorText?.let { tv.text = it } }
        v.findViewById<TextView>(R.id.btnPageStateRetry)?.let { btn ->
            mRetryText?.let { btn.text = it }
            btn.setOnClickListener { mRetryListener?.invoke() }
        }
        mErrorView = v
    }

    private fun buildStateLp(src: ViewGroup.LayoutParams?): LayoutParams {
        val lp = when (src) {
            is LayoutParams -> src
            null -> LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            else -> LayoutParams(src.width, src.height)
        }
        lp.leftMargin = mStateMarginStart
        lp.topMargin = mStateMarginTop
        lp.rightMargin = mStateMarginEnd
        lp.bottomMargin = mStateMarginBottom
        return lp
    }

    private fun applyMargin(view: View?) {
        view ?: return
        val lp = view.layoutParams as? LayoutParams ?: return
        lp.leftMargin = mStateMarginStart
        lp.topMargin = mStateMarginTop
        lp.rightMargin = mStateMarginEnd
        lp.bottomMargin = mStateMarginBottom
        view.layoutParams = lp
    }
}
