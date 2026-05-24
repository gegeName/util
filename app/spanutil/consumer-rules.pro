# spanutil consumer ProGuard rules
# 业务方接入此 AAR 时自动应用,不需要手动配置。

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# ─── 公共 API ────────────────────────────────────────────────────────────────
# SpanBuilder 是 fluent API,业务方反射/直接调用所有 public/protected 成员都要保。
-keep public class com.chat.spanutil.SpanBuilder { public protected *; }
-keep public class com.chat.spanutil.SpanBuilder$Companion { public protected *; }

# ─── Span / Drawable 包(被外层反射 / 包装持有) ─────────────────────────────
# RoundMaskDrawable / BorderedImageDrawable 等,
# 被外层 ImageSpan / Drawable.Callback 反射调用,保 public API。
-keep class com.chat.spanutil.span.** { public protected *; }

# ─── Loader 接口(业务方实现替换默认 Glide / OkHttp 加载器) ─────────────────
-keep interface com.chat.spanutil.span.SpanImageLoader { *; }
-keepclassmembers class * implements com.chat.spanutil.span.SpanImageLoader {
    public protected *;
}

# ─── Releasable / Animatable 转接 ────────────────────────────────────────────
-keep interface com.chat.spanutil.span.Releasable { *; }
-keepclassmembers class * implements com.chat.spanutil.span.Releasable {
    public protected *;
}

# ─── CharAnim / 用户自定义动画(fun interface) ──────────────────────────────
-keep interface com.chat.spanutil.span.CharAnim { *; }
-keep class com.chat.spanutil.span.CharAnims { *; }
-keep class com.chat.spanutil.span.CharAnimationDriver { public protected *; }
-keep class com.chat.spanutil.span.RepeatConfig { *; }
-keep class com.chat.spanutil.span.RepeatConfig$* { *; }

# ─── EmojiRegistry(全局单例) ────────────────────────────────
-keep class com.chat.spanutil.span.EmojiRegistry { *; }

# ─── Kotlin 元数据 ───────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
