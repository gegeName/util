# statelayout 自身 release minify 时的混淆规则。
# AAR 默认不开 minify,这份是为开了 minify 的极端场景兜底。

-keepattributes Signature, InnerClasses, EnclosingMethod

# 自定义 View 4 个构造器
-keep public class com.lhj.statelayout.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

# 自定义属性
-keepclassmembers class **.R$styleable {
    public static <fields>;
}

# 公共 API
-keep public class com.lhj.statelayout.** { public protected *; }

# Kotlin 元数据
-keep class kotlin.Metadata { *; }
