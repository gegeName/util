# ─── 通用属性 ────────────────────────────────────────────────────────────────
# Signature 给 Retrofit/Gson 反射 HttpResult<T> 这类泛型用
# Annotation 给 @SerializedName / @Json / @Keep 用
# InnerClasses / EnclosingMethod 给 Kotlin sealed / object / 内部类反射用
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, Exceptions

# ─── 框架公共 API：保留接口 / 抽象类 / 数据载体 ───────────────────────────────
# 业务实现的接口（implements/by 关键字 + 反射 / 委托均可能用到）
-keep interface com.simple.mylibrary.http.HttpResult { *; }
-keep interface com.simple.mylibrary.http.LoadingController { *; }
-keep interface com.simple.mylibrary.http.LoadingOwner { *; }
-keep interface com.simple.mylibrary.http.PageStateOwner { *; }
-keep interface com.simple.mylibrary.http.IStateLayoutOwner { *; }
-keep interface com.simple.mylibrary.paging.PagingRefreshAdapter { *; }
-keep interface com.simple.mylibrary.paging.PageStateHandler { *; }
-keep interface com.simple.mylibrary.paging.MultiTypeItem { *; }
-keep interface com.simple.mylibrary.floatmsg.FloatMessageAnimator { *; }

# ─── 跨进程 / 序列化的数据载体 ───────────────────────────────────────────────
# DefaultHttpResult 走 Gson/Moshi 反序列化，字段名不能混淆
-keep class com.simple.mylibrary.http.DefaultHttpResult { *; }
-keep class com.simple.mylibrary.http.DefaultHttpResult$Companion { *; }
# ApiException 通过 errorCode / apiTag 字段访问（业务/上报 SDK 反射读取）
-keep class com.simple.mylibrary.http.ApiException { *; }

# ─── sealed interface / sealed class / object 单例 ───────────────────────────
-keep interface com.simple.mylibrary.http.LoadingEvent { *; }
-keep class com.simple.mylibrary.http.LoadingEvent$* { *; }
-keep interface com.simple.mylibrary.http.PageState { *; }
-keep class com.simple.mylibrary.http.PageState$* { *; }
-keep class com.simple.mylibrary.paging.RequestPolicy { *; }
-keep class com.simple.mylibrary.paging.RequestPolicy$* { *; }
-keep class com.simple.mylibrary.http.DefaultLoadingController { *; }
-keep class com.simple.mylibrary.http.ApiErrorHandler { *; }
-keep class com.simple.mylibrary.http.ApiErrorHandler$* { *; }

# ─── ViewModel 通过 ViewModelProvider 反射实例化，必须保留构造方法 ────────────
-keep class com.simple.mylibrary.base.BaseLoadingViewModel { <init>(...); }
-keep class com.simple.mylibrary.base.BaseViewModel { <init>(...); }
-keepclassmembers class * extends com.simple.mylibrary.base.BaseViewModel {
    public <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(android.app.Application);
    public <init>(androidx.lifecycle.SavedStateHandle);
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}

# ─── Paging 抽象基类（业务大量继承，泛型签名 + 公共方法都要留）────────────────
-keep public class com.simple.mylibrary.paging.BasePagingAdapter { public protected *; }
-keep public class com.simple.mylibrary.paging.BaseClickPagingAdapter { public protected *; }
-keep public class com.simple.mylibrary.paging.BaseMultiPagingAdapter { public protected *; }
-keep public class com.simple.mylibrary.paging.BasePagingSource { public protected *; }
-keep public class com.simple.mylibrary.paging.SingleItemBindingAdapter { public protected *; }
-keepclassmembers class * extends com.simple.mylibrary.paging.BasePagingAdapter {
    public protected *;
}
-keepclassmembers class * extends com.simple.mylibrary.paging.BaseClickPagingAdapter {
    public protected *;
}
-keepclassmembers class * extends com.simple.mylibrary.paging.BaseMultiPagingAdapter {
    public protected *;
}
-keepclassmembers class * extends com.simple.mylibrary.paging.BasePagingSource {
    public protected *;
}

# ─── ActivityComponent / FragmentComponent（业务继承使用）────────────────────
-keep class com.simple.mylibrary.http.ActivityComponent { public protected *; }
-keep class com.simple.mylibrary.http.FragmentComponent { public protected *; }
-keepclassmembers class * extends com.simple.mylibrary.http.ActivityComponent {
    public protected *;
}
-keepclassmembers class * extends com.simple.mylibrary.http.FragmentComponent {
    public protected *;
}

# ─── ContentProvider（manifest merge 后由系统反射实例化）─────────────────────
-keep class com.simple.mylibrary.utils.ToastInitProvider { *; }

# ─── Kotlin Coroutines / Flow 反射兜底（库内部依赖）──────────────────────────
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ─── ViewBinding / DataBinding 反射 ──────────────────────────────────────────
# Paging Adapter 系列通过 javaClass.genericSuperclass 解析 VB 泛型实参，
# 再 getDeclaredMethod("inflate", LayoutInflater, ViewGroup, boolean) 反射调用。
# 不 keep 的话 R8 minify 会把生成类的 inflate / bind 静态方法剥掉或改名。

# 泛型实参信息（javaClass.genericSuperclass as ParameterizedType）
-keepattributes Signature, InnerClasses, EnclosingMethod

# 所有 ViewBinding 实现类（消费方 *Binding 生成类）
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** inflate(...);
    public static *** bind(...);
}

# 所有 ViewDataBinding 子类（DataBinding 生成类，含 *BindingImpl）
-keep class * extends androidx.databinding.ViewDataBinding {
    public static *** inflate(...);
    public static *** bind(...);
    <init>(...);
}

# DataBinding 运行时映射 / 适配器（在消费方按 BR/包名生成）
-keep class **.databinding.** { *; }
-keep class **.DataBinderMapperImpl { *; }
-keep class androidx.databinding.DataBinderMapper { *; }

# 业务继承本库的 Adapter，必须保住其 VB 泛型签名（R8 在子类上也可能擦签名
-keepclassmembers class * extends com.simple.mylibrary.paging.BasePagingAdapter { *; }
-keepclassmembers class * extends com.simple.mylibrary.paging.BaseMultiPagingAdapter { *; }
-keepclassmembers class * extends com.simple.mylibrary.paging.SingleItemBindingAdapter { *; }
#// ShapeDrawableBuilder 反射设置 Ring 属性所需字段
-keepclassmembers class android.graphics.drawable.GradientDrawable$GradientState {
    int mInnerRadius;
    float mInnerRadiusRatio;
    int mThickness;
    float mThicknessRatio;
}
-keep class android.graphics.drawable.GradientDrawable {
    private android.graphics.drawable.GradientDrawable$GradientState mGradientState;
}