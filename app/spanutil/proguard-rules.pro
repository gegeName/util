# spanutil 自身 release minify 时的混淆规则。
# AAR 默认不开 minify,这份是为开了 minify 的极端场景兜底。

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# SpanBuilder 公共 API
-keep public class com.chat.spanutil.** { public protected *; }

# Kotlin 元数据 / 协程
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
