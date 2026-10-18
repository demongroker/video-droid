# VideoDroid — ProGuard/R8 keep rules (release builds minify + shrink).

# ---------------------------------------------------------------------------
# Chaquopy — Python runtime is reached over JNI and reflected into by name.
# ---------------------------------------------------------------------------
-keep class com.chaquo.python.** { *; }
-keepclassmembers class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**
-keep class com.chaquo.python.android.** { *; }

# ---------------------------------------------------------------------------
# ffmpeg-kit — native library calls back into Java; classes are looked up
# reflectively by the JNI bridge.
# ---------------------------------------------------------------------------
-keep class com.arthenica.ffmpegkit.** { *; }
-keepclassmembers class com.arthenica.ffmpegkit.** { *; }
-dontwarn com.arthenica.ffmpegkit.**

# ---------------------------------------------------------------------------
# App entry points reached from the manifest / framework.
# ---------------------------------------------------------------------------
-keep class com.xerxes.videodroid.MainActivity { *; }
-keep class com.xerxes.videodroid.XLoginActivity { *; }
-keep class com.xerxes.videodroid.FormatWorker { *; }
-keep class com.xerxes.videodroid.UpdateChecker { *; }

# Kotlin/coroutines metadata
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
