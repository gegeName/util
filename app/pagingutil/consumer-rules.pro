# pagingutil consumer ProGuard rules
# 业务方接入此 AAR 时自动应用,不需要手动配置。

# ─── ViewBinding / DataBinding ───────────────────────────────────────────────
# 保留 generic signature / inner class / enclosing method 信息,反射拿泛型时需要。
-keepattributes Signature, InnerClasses, EnclosingMethod

-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(...);
    public static *** bind(...);
}

-keep class * extends androidx.databinding.ViewDataBinding {
    public static *** inflate(...);
    public static *** bind(...);
    <init>(...);
}

# ─── Paging Adapter / Source 基类 ────────────────────────────────────────────
# 业务方继承 + 反射可能用到的方法都保 public/protected。
-keep public class com.lhj.pagingutil.BasePagingAdapter { public protected *; }
-keep public class com.lhj.pagingutil.BaseClickPagingAdapter { public protected *; }
-keep public class com.lhj.pagingutil.BaseMultiPagingAdapter { public protected *; }
-keep public class com.lhj.pagingutil.BasePagingSource { public protected *; }
-keep public class com.lhj.pagingutil.SingleItemBindingAdapter { public protected *; }
-keepclassmembers class * extends com.lhj.pagingutil.BasePagingAdapter {
    public protected *;
}
-keepclassmembers class * extends com.lhj.pagingutil.BaseClickPagingAdapter {
    public protected *;
}
-keepclassmembers class * extends com.lhj.pagingutil.BaseMultiPagingAdapter {
    public protected *;
}
-keepclassmembers class * extends com.lhj.pagingutil.BasePagingSource {
    public protected *;
}

# ─── 自定义 View(XML inflate) ─────────────────────────────────────────────
# PagingStateLayout / 任意 com.lhj.pagingutil 下的 View,XML inflate 反射
# (Context, AttributeSet) 构造器,类名混淆后查找失败 → InflateException。
-keep public class com.lhj.pagingutil.PagingStateLayout {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

# 自定义属性 R.styleable 字段保留,obtainStyledAttributes 才能取到正确 index。
-keepclassmembers class **.R$styleable {
    public static <fields>;
}

# ─── 接口 / 协议类 ───────────────────────────────────────────────────────────
# 业务方实现 PageStateHandler / MultiTypeItem 提供自定义状态视图 / 多类型,
# 接口名和方法签名不能被混淆。
-keep interface com.lhj.pagingutil.PageStateHandler { *; }
-keep interface com.lhj.pagingutil.MultiTypeItem { *; }
-keepclassmembers class * implements com.lhj.pagingutil.PageStateHandler {
    public protected *;
}
-keepclassmembers class * implements com.lhj.pagingutil.MultiTypeItem {
    public protected *;
}

# ─── 顶层函数 / 协程 Flow 扩展 ───────────────────────────────────────────────
# Kotlin 顶层函数编译成 XxxKt.class,业务方调用扩展时 R8 通常能正确处理,
# 但显式保留更稳,尤其 inline reified 的元数据。
-keep class com.lhj.pagingutil.PagingFlowExtKt { *; }
-keep class com.lhj.pagingutil.PagingHelper { *; }
-keep class com.lhj.pagingutil.RequestPolicy { *; }

# ─── DragSort / ItemDecoration / Animator ────────────────────────────────────
# 这些类被 RecyclerView 反射 / 业务方持有,保 public API。
-keep public class com.lhj.pagingutil.DragSortHelper { public *; }
-keep public class com.lhj.pagingutil.SpacingItemDecoration { public *; }
-keep public class com.lhj.pagingutil.PagingItemAnimator { public *; }

# ─── Paging 3 / Coroutines / Lifecycle ──────────────────────────────────────
# 这些 androidx 库自带 consumer rules 通常已就位,业务方按需保留。
-keep class kotlin.Metadata { *; }
