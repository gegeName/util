# mylibrary 自身 release minify 时的混淆规则。
# AAR 默认不开 minify,这份是为开了 minify 的极端场景兜底。

-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod, Exceptions

# ─── 公共 API ────────────────────────────────────────────────────────────────
-keep public class com.chat.uifoundation.** { public protected *; }

# ─── HTTP / sealed 状态类(Gson/Moshi 反射 + sealed 子类) ─────────────────
-keep class com.chat.uifoundation.http.** { *; }
-keep class com.chat.uifoundation.http.**$* { *; }

# ─── ViewModel 通过 ViewModelProvider 反射实例化 ─────────────────────────────
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    public <init>(android.app.Application);
    public <init>(androidx.lifecycle.SavedStateHandle);
    public <init>(android.app.Application, androidx.lifecycle.SavedStateHandle);
}

# ─── 自定义 View(ZoomGestureHelper / LoadingDialog 等) ───────────────────
-keep public class com.chat.uifoundation.utils.ZoomGestureHelper { public protected *; }

# ─── ToastInitProvider(ContentProvider 由系统按 manifest 字符串反射加载) ─
-keep class com.chat.uifoundation.utils.ToastInitProvider { *; }

# ─── Retrofit / OkHttp / Gson(api 依赖,业务方自己接入也会用) ────────────
-keepattributes Signature
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**

# 调试时反注释下面两行可以保留行号
#-keepattributes SourceFile,LineNumberTable
#-renamesourcefileattribute SourceFile
