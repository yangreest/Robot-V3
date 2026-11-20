# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# EasyPlayer RTSP 库：JNI 反向回调的静态方法/内部类必须保留（开启 minify 时生效）
-keep class org.easydarwin.video.Client { *; }
-keep class org.easydarwin.video.Client$* { *; }
-keep class org.easydarwin.video.EasyPlayerClient { *; }
-keep class org.easydarwin.video.EasyPlayerClient$* { *; }
-keep class org.easydarwin.sw.JNIUtil { *; }
-keep class org.easydarwin.video.VideoCodec { *; }
-keep class org.easydarwin.video.VideoCodec$* { *; }
-keep class org.easydarwin.audio.AudioCodec { *; }
-keep class org.easydarwin.audio.AudioCodec$* { *; }