# svgspan consumer ProGuard rules

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

-keep public class com.lhj.svgspan.DefaultSvgLoader { public protected *; }
-keep public class com.lhj.svgspan.DefaultSvgLoader$Companion { public protected *; }
-keep public class com.lhj.svgspan.AnimatedSvgDrawable { public protected *; }

# AndroidSVG
-keep class com.caverock.androidsvg.SVG { public *; }

-keep class kotlin.Metadata { *; }
