# glidespan consumer ProGuard rules

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

-keep public class com.chat.glidespan.GlideSpanImageLoader { public protected *; }
-keep public class com.chat.glidespan.GlideSpanImageLoader$Companion { public protected *; }
-keep public class com.chat.glidespan.GlideSpanGifLoader { public protected *; }
-keep public class com.chat.glidespan.GlideSpanGifLoader$Companion { public protected *; }

# Glide GifDrawable 反射 setLoopCount / clearAnimationCallbacks 等。
-keep class com.bumptech.glide.load.resource.gif.GifDrawable { public *; }

-keep class kotlin.Metadata { *; }
