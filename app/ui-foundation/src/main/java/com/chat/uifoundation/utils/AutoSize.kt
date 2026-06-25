package com.chat.uifoundation.utils

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.chat.uifoundation.utils.AutoSize.cancel
import com.chat.uifoundation.utils.AutoSize.disable
import com.chat.uifoundation.utils.AutoSize.disableClasses
import com.chat.uifoundation.utils.AutoSize.disablePackages
import com.chat.uifoundation.utils.AutoSize.resume
import com.chat.uifoundation.utils.AutoSize.setDesignWidth
import com.chat.uifoundation.utils.AutoSize.setFontScaleRange
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 屏幕适配（头条 AutoSize 思路：动态修改 [DisplayMetrics.density]）。
 *
 * 接入：
 *   AutoSize.init(this, designWidthDp = 360)
 *   // 默认白名单 = applicationId，第三方 SDK 自动还原系统 density
 *
 * 多包名：
 *   AutoSize.init(this, 360, ownedPackages = arrayOf("com.foo", "com.bar"))
 *
 * 单页覆盖：@AutoSize.Config(designWidthDp = 750)
 * 单页屏蔽：@AutoSize.Disabled
 * 应急黑名单：[disablePackages] / [disableClasses] / [disable]
 * 字体保护：见 [setFontScaleRange]（防 fontScale=2.0 爆布局）
 *
 * ⚠ WebView 坑（业务自行处理）：
 *   首次 new WebView() 会触发 WebViewFactory.getProvider() 重置全局 Resources 的 density。
 *   推荐两种处理：
 *     ① 业务 Application.onCreate 末尾预热：
 *        try { WebView(this).destroy() } catch (_: Throwable) {}
 *        AutoSize.applyForActivity(this 之后第一个 Activity)  // 或等 onActivityStarted 兜底
 *     ② 业务在 Activity 内首次创建 WebView 后立即调：
 *        AutoSize.applyForActivity(this)
 *   不依赖 WebView 的纯原生 App 无需任何处理。
 */
object AutoSize {

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.CLASS)
    annotation class Config(val designWidthDp: Int)

    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.CLASS)
    annotation class Disabled

    @Volatile
    private var application: Application? = null
    private var defaultDesignWidthDp = 360
    private var respectFontScale = true

    @Volatile
    private var minFontScale = 0f
    @Volatile
    private var maxFontScale = Float.MAX_VALUE

    private var systemDensity = 0f
    private var systemScaledDensity = 0f
    private var systemDensityDpi = 0

    private var miuiTmpMetricsField: java.lang.reflect.Field? = null

    private val disabledPackages = CopyOnWriteArraySet<String>()
    private val disabledClasses = CopyOnWriteArraySet<String>()
    private val ownedPackages = CopyOnWriteArraySet<String>()

    private val activeActivities: MutableSet<Activity> =
        Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())

    private val activityOverrides: MutableMap<Activity, Int> =
        Collections.synchronizedMap(WeakHashMap())

    private const val OVERRIDE_DISABLED = -1
    private const val TAG = "AutoSize"

    @Volatile
    private var debug = false

    @JvmStatic
    fun setDebug(enabled: Boolean) {
        debug = enabled
    }

    private inline fun log(msg: () -> String) {
        if (debug) Log.d(TAG, msg())
    }

    /**
     * @param designWidthDp 设计稿基准宽（按短边/竖屏宽，国内常见 360 / 375）
     * @param respectSystemFontScale true=保留系统字体缩放（推荐），false=完全无视
     * @param minFontScale 字体下限，0 = 不限制
     * @param maxFontScale 字体上限，[Float.MAX_VALUE] = 不限制；防爆布局可设 1.3f
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        app: Application,
        designWidthDp: Int = 360,
        respectSystemFontScale: Boolean = true,
        ownedPackages: Array<String>? = null,
        debug: Boolean = false,
        minFontScale: Float = 0f,
        maxFontScale: Float = Float.MAX_VALUE,
    ) {
        require(designWidthDp > 0) { "designWidthDp must be > 0" }
        require(minFontScale >= 0f && maxFontScale > 0f && minFontScale <= maxFontScale) {
            "invalid fontScale range: [$minFontScale, $maxFontScale]"
        }
        if (application != null) {
            log { "init() called twice, ignored" }; return
        }
        this.debug = debug
        this.minFontScale = minFontScale
        this.maxFontScale = maxFontScale
        application = app
        defaultDesignWidthDp = designWidthDp
        respectFontScale = respectSystemFontScale

        when {
            ownedPackages == null -> this.ownedPackages.add(app.packageName)
            ownedPackages.isNotEmpty() -> ownedPackages.forEach {
                if (it.isNotEmpty()) this.ownedPackages.add(
                    it
                )
            }

            else -> this.ownedPackages.clear()
        }

        val dm = app.resources.displayMetrics
        systemDensity = dm.density
        systemScaledDensity = dm.scaledDensity
        systemDensityDpi = dm.densityDpi

        miuiTmpMetricsField = tryReflectMiuiTmpMetrics(app.resources)

        app.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                val newFontScale = if (respectFontScale) newConfig.fontScale else 1f
                applyDensity(app.resources.displayMetrics, defaultDesignWidthDp, app, newFontScale)
                val snapshot = synchronized(activeActivities) { activeActivities.toList() }
                log { "onConfigurationChanged fontScale=$newFontScale activeActivities=${snapshot.size}" }
                snapshot.forEach { applyForActivity(it, newFontScale) }
            }

            override fun onLowMemory() {}
        })

        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                synchronized(activeActivities) { activeActivities.add(activity) }
                applyForActivity(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                synchronized(activeActivities) { activeActivities.add(activity) }
                applyForActivity(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                applyForActivity(activity)
            }

            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                synchronized(activeActivities) { activeActivities.remove(activity) }
                activityOverrides.remove(activity)
            }
        })

        applyDensity(dm, defaultDesignWidthDp, app, null)

        log {
            "init OK designWidth=$designWidthDp respectFontScale=$respectSystemFontScale " +
                    "owned=$ownedPackages systemDensity=$systemDensity systemDpi=$systemDensityDpi"
        }
    }

    /** 运行时切换某 Activity 的设计稿基准，直到 destroy 或 [resume]。 */
    @JvmStatic
    fun setDesignWidth(activity: Activity, designWidthDp: Int) {
        require(designWidthDp > 0)
        activityOverrides[activity] = designWidthDp
        applyDensity(activity.resources.displayMetrics, designWidthDp, activity, null)
    }

    /** 退出适配（仅该 Activity），直到 destroy 或 [resume]。 */
    @JvmStatic
    fun cancel(activity: Activity) {
        activityOverrides[activity] = OVERRIDE_DISABLED
        restore(activity.resources.displayMetrics, activity)
    }

    /** 撤销 [setDesignWidth] / [cancel]，回到注解或全局基准。 */
    @JvmStatic
    fun resume(activity: Activity) {
        activityOverrides.remove(activity)
        applyForActivity(activity)
    }

    /**
     * 推荐值：
     *   (1.0f, 1.3f) → 不缩小，最多放大 1.3 倍（业界主流）
     *   (0f, Float.MAX_VALUE) → 完全不限制（默认）
     */
    @JvmStatic
    fun setFontScaleRange(min: Float, max: Float) {
        require(min >= 0f && max > 0f && min <= max) { "invalid range [$min, $max]" }
        minFontScale = min
        maxFontScale = max
    }

    /** 例：disablePackages("com.tencent", "com.alipay", "cn.jpush") */
    @JvmStatic
    fun disablePackages(vararg prefixes: String) {
        prefixes.filter { it.isNotEmpty() }.forEach { disabledPackages.add(it) }
    }

    /** 例：disableClasses("com.tencent.mm.opensdk.openapi.WXEntryActivity") */
    @JvmStatic
    fun disableClasses(vararg classNames: String) {
        classNames.filter { it.isNotEmpty() }.forEach { disabledClasses.add(it) }
    }

    /** 类型安全版，仅工程内可见的 Class */
    @JvmStatic
    fun disable(vararg classes: Class<out Activity>) {
        classes.forEach { disabledClasses.add(it.name) }
    }

    @JvmStatic
    fun clearDisabled() {
        disabledPackages.clear()
        disabledClasses.clear()
    }

    /** 多模块/多包名场景追加业务自己的包前缀。 */
    @JvmStatic
    fun addOwnedPackages(vararg prefixes: String) {
        prefixes.filter { it.isNotEmpty() }.forEach { ownedPackages.add(it) }
    }

    @JvmStatic
    @JvmOverloads
    fun applyForActivity(activity: Activity, fontScaleOverride: Float? = null) {
        val cls = activity.javaClass
        val name = cls.name

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) {
            restore(activity.resources.displayMetrics, activity)
            log { "[$name] restore (PIP mode)" }
            return
        }

        val override = activityOverrides[activity]
        if (override == OVERRIDE_DISABLED) {
            restore(activity.resources.displayMetrics, activity)
            log { "[$name] restore (override=cancel)" }
            return
        }

        val blockedByList = cls.isAnnotationPresent(Disabled::class.java) ||
                disabledClasses.contains(name) ||
                disabledPackages.any { name.startsWith(it) }
        val blockedByOwnership = ownedPackages.isNotEmpty() &&
                ownedPackages.none { name.startsWith(it) }
        if (blockedByList || blockedByOwnership) {
            restore(activity.resources.displayMetrics, activity)
            log { "[$name] restore (blacklist=$blockedByList, notOwned=$blockedByOwnership)" }
            return
        }

        val annoWidth = cls.getAnnotation(Config::class.java)?.designWidthDp
        if (annoWidth != null && annoWidth <= 0) {
            log { "[$name] @Config(designWidthDp=$annoWidth) invalid, fallback to $defaultDesignWidthDp" }
        }
        val width = override?.takeIf { it > 0 }
            ?: annoWidth?.takeIf { it > 0 }
            ?: defaultDesignWidthDp
        applyDensity(activity.resources.displayMetrics, width, activity, fontScaleOverride)
        log {
            val dm = activity.resources.displayMetrics
            "[$name] apply designWidth=$width density=${"%.3f".format(dm.density)} " +
                    "dpi=${dm.densityDpi} sDensity=${"%.3f".format(dm.scaledDensity)} widthPx=${dm.widthPixels}"
        }
    }

    private fun applyDensity(
        dm: DisplayMetrics,
        designWidthDp: Int,
        ctx: Context,
        fontScaleOverride: Float?
    ) {
        val widthPx = realScreenWidth(ctx)
        if (widthPx <= 0 || designWidthDp <= 0) return
        val target = widthPx.toFloat() / designWidthDp
        val rawFontScale = fontScaleOverride
            ?: if (respectFontScale) ctx.resources.configuration.fontScale else 1f
        val safeFontScale = if (rawFontScale.isFinite() && rawFontScale > 0f) rawFontScale else 1f
        val fontScale = safeFontScale.coerceIn(minFontScale, maxFontScale)
        dm.density = target
        dm.scaledDensity = target * fontScale
        dm.densityDpi = (target * 160f).toInt()
        applyMiuiTmpMetrics(ctx.resources, dm)
    }

    private fun applyMiuiTmpMetrics(res: Resources, source: DisplayMetrics) {
        val field = miuiTmpMetricsField ?: return
        try {
            val miuiDm = field.get(res) as? DisplayMetrics ?: return
            miuiDm.density = source.density
            miuiDm.scaledDensity = source.scaledDensity
            miuiDm.densityDpi = source.densityDpi
        } catch (_: Throwable) {
            miuiTmpMetricsField = null
        }
    }

    private fun tryReflectMiuiTmpMetrics(res: Resources): java.lang.reflect.Field? {
        val name = res.javaClass.simpleName
        if (name != "MiuiResources" && name != "XResources") return null
        return try {
            Resources::class.java.getDeclaredField("mTmpMetrics").apply { isAccessible = true }
                .also { log { "MIUI detected (Resources=$name), mTmpMetrics field cached" } }
        } catch (t: Throwable) {
            log { "MIUI detected ($name) but mTmpMetrics unreachable: ${t.javaClass.simpleName}" }
            null
        }
    }

    // 不依赖 init 时缓存——OEM "显示大小"会改 densityDpi 让缓存过期。
    // AutoSize 只改 DisplayMetrics 不动 Configuration，cfg.densityDpi 始终是系统真实值。
    private fun restore(dm: DisplayMetrics, ctx: Context) {
        val cfg = ctx.resources.configuration
        val dpi = cfg.densityDpi
        val density: Float
        val scaled: Float
        val resolvedDpi: Int
        if (dpi > 0) {
            density = dpi / 160f
            scaled = density * cfg.fontScale
            resolvedDpi = dpi
        } else if (systemDensity > 0f) {
            density = systemDensity
            scaled = systemScaledDensity
            resolvedDpi = systemDensityDpi
        } else {
            return
        }
        dm.density = density
        dm.densityDpi = resolvedDpi
        dm.scaledDensity = scaled
        applyMiuiTmpMetrics(ctx.resources, dm)
    }

    private fun realScreenWidth(ctx: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                val metrics =
                    if (ctx is Activity) wm.currentWindowMetrics else wm.maximumWindowMetrics
                return metrics.bounds.width()
            }
        }
        return ctx.resources.displayMetrics.widthPixels
    }

    // ============== dp/sp <-> px 转换 ==============
    // 走 ctx/view 关联的 Resources.displayMetrics，AutoSize 改 density 后自动生效。
    // 不要用 Resources.getSystem()——那是系统默认 Resources，永远不会被本类修改。

    @JvmStatic
    fun dp2px(ctx: Context, dp: Number): Int =
        (dp.toFloat() * ctx.resources.displayMetrics.density + 0.5f).toInt()

    @JvmStatic
    fun dp2px(view: View, dp: Number): Int = dp2px(view.context, dp)

    @JvmStatic
    fun px2dp(ctx: Context, px: Number): Int =
        (px.toFloat() / ctx.resources.displayMetrics.density + 0.5f).toInt()

    @JvmStatic
    fun px2dp(view: View, px: Number): Int = px2dp(view.context, px)

    @JvmStatic
    fun sp2px(ctx: Context, sp: Number): Int =
        (sp.toFloat() * ctx.resources.displayMetrics.scaledDensity + 0.5f).toInt()

    @JvmStatic
    fun sp2px(view: View, sp: Number): Int = sp2px(view.context, sp)

    @JvmStatic
    fun px2sp(ctx: Context, px: Number): Int =
        (px.toFloat() / ctx.resources.displayMetrics.scaledDensity + 0.5f).toInt()

    @JvmStatic
    fun px2sp(view: View, px: Number): Int = px2sp(view.context, px)
}
