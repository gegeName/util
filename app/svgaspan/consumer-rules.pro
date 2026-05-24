# svgaspan consumer ProGuard rules

-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# ─── 公共 API ────────────────────────────────────────────────────────────────
-keep public class com.chat.svgaspan.SvgaSpanLoader { public protected *; }
-keep public class com.chat.svgaspan.SvgaSpanLoader$Companion { public protected *; }
-keep public class com.chat.svgaspan.SvgaSpanDrawable { public protected *; }
-keep class com.chat.svgaspan.SvgaCache { *; }

# ─── 第三方:SVGAPlayer ──────────────────────────────────────────────────────
-keep class com.opensource.svgaplayer.SVGAImageView { public protected *; }
-keep class com.opensource.svgaplayer.SVGAParser { public protected *; }
-keep class com.opensource.svgaplayer.SVGAVideoEntity { public *; }
-keep class com.opensource.svgaplayer.SVGADynamicEntity { public *; }
# 我们通过反射调 SVGACanvasDrawer.drawFrame,该类是 internal 但字节码 public,必保。
-keep class com.opensource.svgaplayer.drawer.SVGACanvasDrawer { *; }

-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.coroutines.**
