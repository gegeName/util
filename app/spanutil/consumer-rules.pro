# spanutil consumer ProGuard rules
# 业务方接入此 AAR 时自动应用,不需要手动配置。

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# ─── 公共 API ────────────────────────────────────────────────────────────────
# SpanBuilder 是 fluent API,业务方反射/直接调用所有 public/protected 成员都要保。
-keep public class com.lhj.spanutil.SpanBuilder { public protected *; }
-keep public class com.lhj.spanutil.SpanBuilder$Companion { public protected *; }

# ─── Span / Drawable 包(被外层反射 / 包装持有) ─────────────────────────────
# RoundMaskDrawable / SvgaSpanDrawable / AnimatedSvgDrawable / BorderedImageDrawable 等,
# 被外层 ImageSpan / Drawable.Callback 反射调用,保 public API。
-keep class com.lhj.spanutil.span.** { public protected *; }

# ─── Loader 接口(业务方实现替换默认 Glide / OkHttp 加载器) ─────────────────
-keep interface com.lhj.spanutil.span.SpanImageLoader { *; }
-keepclassmembers class * implements com.lhj.spanutil.span.SpanImageLoader {
    public protected *;
}

# ─── Releasable / Animatable 转接 ────────────────────────────────────────────
-keep interface com.lhj.spanutil.span.Releasable { *; }
-keepclassmembers class * implements com.lhj.spanutil.span.Releasable {
    public protected *;
}

# ─── CharAnim / 用户自定义动画(fun interface) ──────────────────────────────
-keep interface com.lhj.spanutil.span.CharAnim { *; }
-keep class com.lhj.spanutil.span.CharAnims { *; }
-keep class com.lhj.spanutil.span.CharAnimationDriver { public protected *; }
-keep class com.lhj.spanutil.span.RepeatConfig { *; }
-keep class com.lhj.spanutil.span.RepeatConfig$* { *; }

# ─── EmojiRegistry / SvgaCache(全局单例) ────────────────────────────────
-keep class com.lhj.spanutil.span.EmojiRegistry { *; }
-keep class com.lhj.spanutil.span.SvgaCache { *; }

# ─── 第三方:Glide / AndroidSVG / SVGAPlayer / OkHttp ───────────────────────
# 第三方库自带 consumer rules 通常已就位,这里只保我们直接反射用到的入口。
-keep class com.opensource.svgaplayer.SVGAImageView { public protected *; }
-keep class com.opensource.svgaplayer.SVGAParser { public protected *; }
-keep class com.opensource.svgaplayer.SVGAVideoEntity { public *; }
-keep class com.opensource.svgaplayer.SVGADynamicEntity { public *; }
# 我们通过反射调 SVGACanvasDrawer.drawFrame,该类是 internal 但字节码 public,必保。
-keep class com.opensource.svgaplayer.drawer.SVGACanvasDrawer { *; }

# Glide GifDrawable 反射 setLoopCount / clearAnimationCallbacks 等。
-keep class com.bumptech.glide.load.resource.gif.GifDrawable { public *; }

# AndroidSVG
-keep class com.caverock.androidsvg.SVG { public *; }

# ─── Kotlin 元数据 ───────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
