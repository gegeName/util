
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, Exceptions
-keep interface com.chat.mylibrary.http.HttpResult { *; }
-keep interface com.chat.mylibrary.http.LoadingController { *; }
-keep interface com.chat.mylibrary.http.LoadingOwner { *; }
-keep interface com.chat.mylibrary.http.PageStateOwner { *; }
-keep interface com.chat.mylibrary.http.IStateLayoutOwner { *; }
-keep interface com.chat.mylibrary.floatmsg.FloatMessageAnimator { *; }

# ─── 跨进程 / 序列化的数据载体 ───────────────────────────────────────────────
# DefaultHttpResult 走 Gson/Moshi 反序列化，字段名不能混淆
-keep class com.chat.mylibrary.http.DefaultHttpResult { *; }
-keep class com.chat.mylibrary.http.DefaultHttpResult$Companion { *; }
# ApiException 通过 errorCode / apiTag 字段访问（业务/上报 SDK 反射读取）
-keep class com.chat.mylibrary.http.ApiException { *; }

# ─── sealed interface / sealed class / object 单例 ───────────────────────────
-keep interface com.chat.mylibrary.http.LoadingEvent { *; }
-keep class com.chat.mylibrary.http.LoadingEvent$* { *; }
-keep interface com.chat.mylibrary.http.PageState { *; }
-keep class com.chat.mylibrary.http.PageState$* { *; }
-keep class com.chat.mylibrary.paging.RequestPolicy$* { *; }
-keep class com.chat.mylibrary.http.DefaultLoadingController { *; }
-keep class com.chat.mylibrary.http.ApiErrorHandler { *; }
-keep class com.chat.mylibrary.http.ApiErrorHandler$* { *; }

# ─── ViewModel 通过 ViewModelProvider 反射实例化，必须保留构造方法 ────────────
-keep class com.chat.mylibrary.base.BaseLoadingViewModel { <init>(...); }
-keep class com.chat.mylibrary.base.BaseViewModel { <init>(...); }
-keepclassmembers class * extends com.chat.mylibrary.base.BaseViewModel {
    public <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(android.app.Application);
    public <init>(androidx.lifecycle.SavedStateHandle);
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}

# ─── ActivityComponent / FragmentComponent（业务继承使用）────────────────────
-keep class com.chat.mylibrary.http.ActivityComponent { public protected *; }
-keep class com.chat.mylibrary.http.FragmentComponent { public protected *; }
-keepclassmembers class * extends com.chat.mylibrary.http.ActivityComponent {
    public protected *;
}
-keepclassmembers class * extends com.chat.mylibrary.http.FragmentComponent {
    public protected *;
}

-keep class com.chat.mylibrary.utils.ToastInitProvider { *; }

-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
