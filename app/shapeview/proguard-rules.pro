
-keep public class com.chat.shapeview.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public <init>(android.content.Context, android.util.AttributeSet, int, int);
}

-keepclassmembers class **.R$styleable {
    public static <fields>;
}

-keep class com.chat.shapeview.builder.** { *; }
