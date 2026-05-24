# statelayout consumer ProGuard rules
# 业务方接入此 AAR 时自动应用,不需要手动配置。

# ─── 自定义 View(XML inflate) ─────────────────────────────────────────────
# StateLayout 通过 XML <com.example.statelayout.StateLayout> 引用,LayoutInflater
# 反射 (Context, AttributeSet) 构造器实例化;类名/构造器都不能被混淆。
-keep public class com.chat.statelayout.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

# ─── R.styleable ─────────────────────────────────────────────────────────────
# 自定义属性(stlStateBackground 等)通过 R.styleable.StateLayout_xxx 下标读,
# 字段被 R8 优化掉就读不到值。
-keepclassmembers class **.R$styleable {
    public static <fields>;
}

# ─── State 枚举 / 顶层扩展 ──────────────────────────────────────────────────
# 业务方可能反射 / Kotlin 顶层函数调用 setState(State.EMPTY) 等,保元数据。
-keep class com.chat.statelayout.StateLayout$State { *; }
-keep class com.chat.statelayout.StateLayoutExtKt { *; }
-keep class kotlin.Metadata { *; }
