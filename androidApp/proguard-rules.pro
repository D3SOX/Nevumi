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
-keep class me.d3sox.nevumi.sftp.TailscaleNative { *; }
