
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, Exceptions
-keep interface com.simple.mylibrary.http.HttpResult { *; }
-keep interface com.simple.mylibrary.http.LoadingController { *; }
-keep interface com.simple.mylibrary.http.LoadingOwner { *; }
-keep interface com.simple.mylibrary.http.PageStateOwner { *; }
-keep interface com.simple.mylibrary.http.IStateLayoutOwner { *; }
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

# ─── ActivityComponent / FragmentComponent（业务继承使用）────────────────────
-keep class com.simple.mylibrary.http.ActivityComponent { public protected *; }
-keep class com.simple.mylibrary.http.FragmentComponent { public protected *; }
-keepclassmembers class * extends com.simple.mylibrary.http.ActivityComponent {
    public protected *;
}
-keepclassmembers class * extends com.simple.mylibrary.http.FragmentComponent {
    public protected *;
}

-keep class com.simple.mylibrary.utils.ToastInitProvider { *; }

-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
