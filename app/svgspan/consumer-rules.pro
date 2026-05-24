# svgspan consumer ProGuard rules

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

-keep public class com.chat.svgspan.DefaultSvgLoader { public protected *; }
-keep public class com.chat.svgspan.DefaultSvgLoader$Companion { public protected *; }
-keep public class com.chat.svgspan.AnimatedSvgDrawable { public protected *; }

# AndroidSVG
-keep class com.caverock.androidsvg.SVG { public *; }

-keep class kotlin.Metadata { *; }
