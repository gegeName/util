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