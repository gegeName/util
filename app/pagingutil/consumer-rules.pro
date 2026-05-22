# pagingutil consumer ProGuard rules
# 业务方接入此 AAR 时自动应用，无需手动配置。
# 原则：只保 R8 自己识别不出来的反射 / inflate / 泛型签名，其他交给 R8 按使用情况裁剪。

# Kotlin 反射 / 泛型 / inline reified 必需
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# PagingStateLayout 在 XML 中 inflate 时通过反射调 (Context, AttributeSet[, int[, int]]) 构造器。
-keep class com.lhj.pagingutil.PagingStateLayout {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

# BasePagingAdapter / BaseMultiPagingAdapter 用反射调 ViewBinding.inflate(LayoutInflater, ViewGroup, boolean)
# 必须保留所有 ViewBinding 实现类不被裁剪/改名,以及那个静态 inflate 三参版本。
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
}
# DataBinding 类继承 ViewDataBinding 而非直接实现 ViewBinding,单独覆盖
-keep class * extends androidx.databinding.ViewDataBinding {
    public static *** inflate(android.view.LayoutInflater, android.view.ViewGroup, boolean);
}

# Kotlin Metadata：BaseMultiPagingAdapter.addType 等 inline reified 函数依赖。
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

