# pagingutil consumer ProGuard rules
# 业务方接入此 AAR 时自动应用，无需手动配置。
# 原则：只保 R8 自己识别不出来的反射 / inflate / 泛型签名，其他交给 R8 按使用情况裁剪。

# Kotlin 反射 / 泛型 / inline reified 必需
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# PagingStateLayout 在 XML 中 inflate 时通过反射调 (Context, AttributeSet[, int[, int]]) 构造器。
-keep class com.chat.pagingutil.PagingStateLayout {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
