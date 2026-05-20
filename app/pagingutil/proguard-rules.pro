# pagingutil 自身 release minify 时的混淆规则。
# AAR 默认不开 minify,这份是为开了 minify 的极端场景兜底。

-keepattributes Signature, InnerClasses, EnclosingMethod

# ViewBinding / DataBinding
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(...);
    public static *** bind(...);
}
-keep class * extends androidx.databinding.ViewDataBinding {
    public static *** inflate(...);
    public static *** bind(...);
    <init>(...);
}

# 模块自身 public API,业务方反射调用必须保留。
-keep public class com.lhj.pagingutil.** { public protected *; }

# 自定义 View 构造器 + R.styleable
-keep public class com.lhj.pagingutil.PagingStateLayout {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}
-keepclassmembers class **.R$styleable {
    public static <fields>;
}

# Kotlin 元数据(顶层函数 / 默认参数 / 反射相关)
-keep class kotlin.Metadata { *; }

# 调试时反注释下面两行可以保留行号
#-keepattributes SourceFile,LineNumberTable
#-renamesourcefileattribute SourceFile
