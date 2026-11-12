# Audio metadata readers use format-specific classes through reflection.
-keep class org.jaudiotagger.** { *; }
# JCA looks up provider implementations by their registered class names.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
# The metadata library also bundles desktop artwork helpers. Android uses
# AndroidArtwork and never calls these AWT/ImageIO helpers.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
# JNI entry points retain their Kotlin class and method names.
-keep class me.d3sox.nevumi.visualizer.ProjectMNative { *; }
-keep class me.d3sox.nevumi.tunnel.TailscaleNative { *; }
# NewPipe Extractor loads its date patterns by name, and runs YouTube's player
# JavaScript in Rhino, which looks up its own classes by reflection.
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
